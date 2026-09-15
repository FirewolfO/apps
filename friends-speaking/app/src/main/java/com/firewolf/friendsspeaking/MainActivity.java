package com.firewolf.friendsspeaking;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashSet;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends AppCompatActivity implements EpisodeAdapter.Listener {
    private static final Pattern EPISODE_FILE = Pattern.compile("(?i).*S(\\d{1,2})[^A-Z0-9]*E(\\d{1,2}).*");
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(
            Arrays.asList("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav"));
    private static final Set<String> SUBTITLE_EXTENSIONS = new HashSet<>(Arrays.asList("srt", "vtt"));

    private final ExecutorService importer = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<Uri> libraryPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(), this::importLibrary);

    private LearningStore store;
    private EpisodeAdapter adapter;
    private LinearLayout seasonTabs;
    private TextView seasonSummary;
    private TextView importStatus;
    private int selectedSeason = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);
        applyInsets(findViewById(R.id.root));

        store = new LearningStore(this);
        adapter = new EpisodeAdapter(store, this);
        seasonTabs = findViewById(R.id.season_tabs);
        seasonSummary = findViewById(R.id.season_summary);
        importStatus = findViewById(R.id.import_status);
        RecyclerView list = findViewById(R.id.episode_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.import_library).setOnClickListener(view -> libraryPicker.launch(null));
        findViewById(R.id.check_update).setOnClickListener(view -> AppUpdateChecker.check(this, true));
        findViewById(R.id.resume_card).setOnClickListener(view -> {
            Episode last = store.lastEpisode();
            if (last != null) open(last);
        });
        bindSeasonTabs();
        renderSeason();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) {
            renderSeason();
            bindResume();
        }
        AppUpdateChecker.resumePending(this);
        AppUpdateChecker.check(this, false);
    }

    @Override
    protected void onDestroy() {
        importer.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onEpisode(Episode episode) {
        open(episode);
    }

    private void bindSeasonTabs() {
        seasonTabs.removeAllViews();
        for (int season = 1; season <= Episode.seasonCount(); season++) {
            int value = season;
            TextView chip = new TextView(this);
            chip.setText("第 " + season + " 季");
            chip.setTextSize(13);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setSelected(season == selectedSeason);
            chip.setTextColor(getColor(chip.isSelected() ? R.color.ink : R.color.muted));
            chip.setBackgroundResource(R.drawable.bg_season_chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            chip.setLayoutParams(params);
            chip.setOnClickListener(view -> {
                selectedSeason = value;
                bindSeasonTabs();
                renderSeason();
            });
            seasonTabs.addView(chip);
        }
    }

    private void renderSeason() {
        int ready = store.readyCount(selectedSeason);
        int total = Episode.season(selectedSeason).size();
        seasonSummary.setText("第 " + selectedSeason + " 季 · " + total + " 集 · " + ready + " 集已就绪");
        adapter.submit(Episode.season(selectedSeason));
        bindResume();
    }

    private void bindResume() {
        Episode episode = store.lastEpisode();
        View card = findViewById(R.id.resume_card);
        if (episode == null || store.audio(episode) == null) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.resume_title)).setText(episode.displayTitle() + " · " + episode.key
                + (store.isBundledDemo(episode) ? " · 原创演示" : ""));
        long position = store.progress(episode);
        long duration = store.duration(episode);
        String detail = "从 " + time(position) + " 继续";
        if (duration > 0) detail += " · 已学习 " + Math.min(100, position * 100 / duration) + "%";
        ((TextView) findViewById(R.id.resume_progress)).setText(detail);
    }

    private void importLibrary(Uri tree) {
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers keep the grant without exposing persistence.
        }
        Button button = findViewById(R.id.import_library);
        button.setEnabled(false);
        importStatus.setText("正在扫描并匹配 S01E01 文件…");
        importer.execute(() -> {
            ImportResult result = new ImportResult();
            try {
                DocumentFile root = DocumentFile.fromTreeUri(this, tree);
                if (root == null) throw new IllegalStateException("无法打开目录");
                scan(root, 0, result);
            } catch (RuntimeException error) {
                result.failed = true;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                button.setEnabled(true);
                if (result.failed) {
                    importStatus.setText("目录扫描未完成，请确认目录仍可访问后重试");
                } else {
                    importStatus.setText("已匹配 " + result.audio.size() + " 个音频、" + result.subtitles.size()
                            + " 个字幕；文件名包含 S01E01 即可自动识别");
                }
                renderSeason();
                Toast.makeText(this, result.failed ? "媒体目录导入失败" : "媒体目录导入完成", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void scan(DocumentFile document, int depth, ImportResult result) {
        if (depth > 5 || result.scanned >= 5000) return;
        if (document.isDirectory()) {
            for (DocumentFile child : document.listFiles()) scan(child, depth + 1, result);
            return;
        }
        result.scanned++;
        String name = document.getName();
        if (name == null) return;
        Matcher matcher = EPISODE_FILE.matcher(name);
        if (!matcher.matches()) return;
        Episode episode = Episode.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        if (episode == null) return;
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (AUDIO_EXTENSIONS.contains(extension)) {
            store.saveAudio(episode, document.getUri());
            result.audio.add(episode.key);
        } else if (SUBTITLE_EXTENSIONS.contains(extension)) {
            store.saveSubtitle(episode, document.getUri());
            result.subtitles.add(episode.key);
        }
    }

    private void open(Episode episode) {
        Intent intent = new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.SEASON, episode.season)
                .putExtra(PlayerActivity.EPISODE, episode.number);
        startActivity(intent);
    }

    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left + dp(18), bars.top + dp(14), bars.right + dp(18), bars.bottom + dp(10));
            return insets;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static final class ImportResult {
        final Set<String> audio = new HashSet<>();
        final Set<String> subtitles = new HashSet<>();
        int scanned;
        boolean failed;
    }
}
