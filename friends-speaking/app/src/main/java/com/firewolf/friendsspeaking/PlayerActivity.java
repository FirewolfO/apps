package com.firewolf.friendsspeaking;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends AppCompatActivity {
    public static final String SEASON = "season";
    public static final String EPISODE = "episode";
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService subtitleLoader = Executors.newSingleThreadExecutor();
    private final Runnable updateProgress = new Runnable() {
        @Override public void run() {
            if (player != null) renderSubtitle(player.getCurrentPosition());
            progressHandler.postDelayed(this, 100);
        }
    };
    private final ActivityResultLauncher<String[]> audioPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> savePicked(uri, true));
    private final ActivityResultLauncher<String[]> subtitlePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> savePicked(uri, false));

    private Episode episode;
    private LearningStore store;
    private PlayerView playerView;
    private ExoPlayer player;
    private TextView previousSubtitle;
    private TextView activeSubtitle;
    private TextView nextSubtitle;
    private TextView mediaStatus;
    private List<SubtitleCue> cues = Collections.emptyList();
    private int activeCue = Integer.MIN_VALUE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        episode = Episode.of(getIntent().getIntExtra(SEASON, 0), getIntent().getIntExtra(EPISODE, 0));
        if (episode == null) {
            finish();
            return;
        }
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_player);
        applyInsets(findViewById(R.id.root));
        store = new LearningStore(this);
        playerView = findViewById(R.id.player_view);
        previousSubtitle = findViewById(R.id.subtitle_previous);
        activeSubtitle = findViewById(R.id.subtitle_active);
        nextSubtitle = findViewById(R.id.subtitle_next);
        mediaStatus = findViewById(R.id.media_status);
        ((TextView) findViewById(R.id.episode_title)).setText(episode.displayTitle() + " · " + episode.key);
        findViewById(R.id.back).setOnClickListener(view -> finish());
        findViewById(R.id.import_audio).setOnClickListener(view -> audioPicker.launch(
                new String[]{"audio/*", "application/ogg"}));
        findViewById(R.id.import_subtitle).setOnClickListener(view -> subtitlePicker.launch(
                new String[]{"application/x-subrip", "text/srt", "text/vtt", "text/plain"}));
        findViewById(R.id.previous_line).setOnClickListener(view -> seekRelativeCue(-1));
        findViewById(R.id.replay_line).setOnClickListener(view -> seekRelativeCue(0));
        findViewById(R.id.next_line).setOnClickListener(view -> seekRelativeCue(1));
        bindSpeedOptions();
        bindMediaStatus();
        loadSubtitles();
    }

    @Override
    protected void onStart() {
        super.onStart();
        initializePlayer();
        progressHandler.post(updateProgress);
    }

    @Override
    protected void onStop() {
        progressHandler.removeCallbacks(updateProgress);
        releasePlayer();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        subtitleLoader.shutdownNow();
        super.onDestroy();
    }

    private void initializePlayer() {
        Uri audio = store.audio(episode);
        if (player != null || audio == null) return;
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(audio));
        player.setPlaybackSpeed(store.speed());
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlayerActivity.this, "音频无法播放，请重新选择本集文件", Toast.LENGTH_LONG).show();
            }
        });
        long saved = store.progress(episode);
        if (saved > 0) player.seekTo(saved);
        player.prepare();
        player.play();
        bindMediaStatus();
    }

    private void releasePlayer() {
        if (player == null) return;
        store.saveProgress(episode, player.getCurrentPosition(), player.getDuration());
        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    private void loadSubtitles() {
        Uri subtitle = store.subtitle(episode);
        if (subtitle == null) {
            cues = Collections.emptyList();
            activeCue = Integer.MIN_VALUE;
            renderSubtitle(0);
            return;
        }
        subtitleLoader.execute(() -> {
            List<SubtitleCue> parsed = new ArrayList<>();
            String error = "";
            try (InputStream input = getContentResolver().openInputStream(subtitle)) {
                if (input == null) throw new IllegalStateException("subtitle unavailable");
                parsed = SubtitleParser.parse(input);
                if (parsed.isEmpty()) error = "字幕中没有识别到有效时间轴";
            } catch (Exception exception) {
                error = "字幕读取失败，请重新选择 SRT 或 VTT 文件";
            }
            List<SubtitleCue> result = parsed;
            String message = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                cues = result;
                activeCue = Integer.MIN_VALUE;
                renderSubtitle(player == null ? store.progress(episode) : player.getCurrentPosition());
                bindMediaStatus();
                if (!message.isEmpty()) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void renderSubtitle(long position) {
        int index = SubtitleParser.activeIndex(cues, position);
        int nextIndex = index < 0 ? firstAfter(position) : -1;
        int displayKey = index >= 0 ? index : -nextIndex - 2;
        if (displayKey == activeCue) return;
        activeCue = displayKey;
        if (cues.isEmpty()) {
            previousSubtitle.setText("");
            activeSubtitle.setText("导入字幕后，当前台词会随播放变成黄色");
            nextSubtitle.setText("");
            return;
        }
        if (index < 0) {
            previousSubtitle.setText(nextIndex > 0 ? cues.get(nextIndex - 1).text : "");
            activeSubtitle.setText("…");
            nextSubtitle.setText(nextIndex < cues.size() ? cues.get(nextIndex).text : "");
            return;
        }
        previousSubtitle.setText(index > 0 ? cues.get(index - 1).text : "");
        activeSubtitle.setText(cues.get(index).text);
        nextSubtitle.setText(index + 1 < cues.size() ? cues.get(index + 1).text : "");
    }

    private void seekRelativeCue(int offset) {
        if (player == null || cues.isEmpty()) return;
        int base = SubtitleParser.activeIndex(cues, player.getCurrentPosition());
        if (base < 0) base = Math.max(0, firstAfter(player.getCurrentPosition()) - 1);
        int target = Math.max(0, Math.min(cues.size() - 1, base + offset));
        player.seekTo(cues.get(target).startMs);
        player.play();
    }

    private int firstAfter(long position) {
        int low = 0;
        int high = cues.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cues.get(middle).startMs <= position) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private void bindSpeedOptions() {
        LinearLayout group = findViewById(R.id.speed_options);
        group.removeAllViews();
        float saved = store.speed();
        for (float speed : SPEEDS) {
            TextView chip = new TextView(this);
            chip.setText(String.format(Locale.ROOT, "%gx", speed));
            chip.setTextSize(13);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setSelected(Math.abs(speed - saved) < 0.01f);
            chip.setTextColor(getColor(chip.isSelected() ? R.color.ink : R.color.muted));
            chip.setBackgroundResource(R.drawable.bg_season_chip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(64), dp(40));
            params.setMarginEnd(dp(7));
            chip.setLayoutParams(params);
            chip.setOnClickListener(view -> {
                store.saveSpeed(speed);
                if (player != null) player.setPlaybackSpeed(speed);
                bindSpeedOptions();
            });
            group.addView(chip);
        }
    }

    private void savePicked(Uri uri, boolean audio) {
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Keep the provider grant available for the current installation.
        }
        if (audio) {
            store.saveAudio(episode, uri);
            releasePlayer();
            initializePlayer();
        } else {
            store.saveSubtitle(episode, uri);
            loadSubtitles();
        }
        bindMediaStatus();
    }

    private void bindMediaStatus() {
        boolean audio = store.audio(episode) != null;
        boolean subtitle = store.subtitle(episode) != null;
        String value = audio ? "音频已导入" : "请先选择音频";
        value += subtitle ? " · 字幕已导入" : " · 请再选择字幕";
        if (subtitle && !cues.isEmpty()) value += " · " + cues.size() + " 条台词";
        value += " · " + String.format(Locale.ROOT, "%gx", store.speed());
        mediaStatus.setText(value);
    }

    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left + dp(14), bars.top + dp(6), bars.right + dp(14), bars.bottom + dp(10));
            return insets;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
