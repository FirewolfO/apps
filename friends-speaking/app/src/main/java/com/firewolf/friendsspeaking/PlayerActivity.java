package com.firewolf.friendsspeaking;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Build;
import android.provider.OpenableColumns;
import android.view.Menu;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends AppCompatActivity implements PlaybackService.Listener {
    public static final String SEASON = "season";
    public static final String EPISODE = "episode";
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService subtitleLoader = Executors.newSingleThreadExecutor();
    private final Runnable updateProgress = new Runnable() {
        @Override public void run() {
            long duration = currentDuration();
            long position = currentPosition();
            renderSubtitle(position);
            renderProgress(position, duration);
            progressHandler.postDelayed(this, 150L);
        }
    };
    private final Runnable resumeSubtitleFollowing = () -> {
        subtitleDragging = false;
        activeCue = Integer.MIN_VALUE;
        renderSubtitle(currentPosition());
    };
    private final ActivityResultLauncher<String[]> audioPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> savePicked(uri, true));
    private final ActivityResultLauncher<String[]> subtitlePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> savePicked(uri, false));
    private final ServiceConnection playbackConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playbackBound = true;
            playback.addListener(PlayerActivity.this);
            playback.load(episode, false);
            onPlaybackChanged();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            playbackBound = false;
            playback = null;
            renderProgress(store.progress(episode), store.duration(episode));
        }
    };

    private Episode episode;
    private LearningStore store;
    private PlaybackService playback;
    private boolean playbackBound;
    private RecyclerView subtitleList;
    private LinearLayoutManager subtitleLayout;
    private SubtitleAdapter subtitleAdapter;
    private TextView subtitleEmpty;
    private TextView mediaStatus;
    private TextView currentTime;
    private TextView durationTime;
    private Button playPause;
    private Button speedButton;
    private SeekBar playbackSeek;
    private List<SubtitleCue> baseCues = Collections.emptyList();
    private List<SubtitleCue> cues = Collections.emptyList();
    private List<String> untimedLines = Collections.emptyList();
    private AlignmentIndex alignment;
    private List<SubtitleCue> alignedCues = Collections.emptyList();
    private boolean audioAlignmentVerified;
    private String subtitleTimingStatus = "";
    private int subtitleRequest;
    private int activeCue = Integer.MIN_VALUE;
    private boolean seekBarDragging;
    private boolean subtitleDragging;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        episode = Episode.of(getIntent().getIntExtra(SEASON, 0), getIntent().getIntExtra(EPISODE, 0));
        if (episode == null) {
            finish();
            return;
        }
        WindowCompat.enableEdgeToEdge(getWindow());
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);
        applyInsets(findViewById(R.id.root));
        store = new LearningStore(this);
        PDFBoxResourceLoader.init(getApplicationContext());
        requestPlaybackNotificationPermission();

        mediaStatus = findViewById(R.id.media_status);
        currentTime = findViewById(R.id.current_time);
        durationTime = findViewById(R.id.duration_time);
        playPause = findViewById(R.id.play_pause);
        speedButton = findViewById(R.id.playback_speed);
        playbackSeek = findViewById(R.id.playback_seek);
        subtitleList = findViewById(R.id.subtitle_list);
        subtitleEmpty = findViewById(R.id.subtitle_empty);
        subtitleLayout = new LinearLayoutManager(this);
        subtitleAdapter = new SubtitleAdapter(this::seekToCue);
        subtitleList.setLayoutManager(subtitleLayout);
        subtitleList.setAdapter(subtitleAdapter);
        subtitleList.post(() -> {
            int vertical = Math.max(dp(24), subtitleList.getHeight() / 2);
            subtitleList.setPadding(subtitleList.getPaddingLeft(), vertical,
                    subtitleList.getPaddingRight(), vertical);
            activeCue = Integer.MIN_VALUE;
            renderSubtitle(currentPosition());
        });
        bindSubtitleScrolling();

        ((TextView) findViewById(R.id.episode_title)).setText("老友记 · " + episode.key);
        findViewById(R.id.back).setOnClickListener(view -> finish());
        findViewById(R.id.import_audio).setOnClickListener(view -> chooseAudio());
        findViewById(R.id.import_subtitle).setOnClickListener(this::showSubtitleMenu);
        findViewById(R.id.previous_line).setOnClickListener(view -> seekRelativeCue(-1));
        findViewById(R.id.replay_line).setOnClickListener(view -> seekRelativeCue(0));
        findViewById(R.id.next_line).setOnClickListener(view -> seekRelativeCue(1));
        playPause.setOnClickListener(view -> togglePlayback());
        speedButton.setOnClickListener(this::showSpeedMenu);
        bindSeekBar();
        bindMediaStatus();
        loadSubtitles();
    }

    @Override
    protected void onStart() {
        super.onStart();
        subtitleDragging = false;
        Intent intent = PlaybackService.loadIntent(this, episode);
        ContextCompat.startForegroundService(this, intent);
        bindService(new Intent(this, PlaybackService.class), playbackConnection, Context.BIND_AUTO_CREATE);
        progressHandler.removeCallbacks(updateProgress);
        progressHandler.post(updateProgress);
    }

    @Override
    protected void onStop() {
        progressHandler.removeCallbacks(updateProgress);
        progressHandler.removeCallbacks(resumeSubtitleFollowing);
        if (playbackBound) {
            playback.removeListener(this);
            unbindService(playbackConnection);
            playbackBound = false;
            playback = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        subtitleLoader.shutdownNow();
        super.onDestroy();
    }

    @Override public void onPlaybackChanged() {
        if (isFinishing() || isDestroyed()) return;
        verifyAlignedAudio();
        renderProgress(currentPosition(), currentDuration());
        bindMediaStatus();
    }

    @Override public void onPlaybackError(String message) {
        if (!isFinishing() && !isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void loadSubtitles() {
        int request = ++subtitleRequest;
        Uri subtitle = store.subtitle(episode);
        boolean useAlignment = RemoteMediaCatalog.hasSubtitle(episode);
        alignment = null;
        alignedCues = Collections.emptyList();
        audioAlignmentVerified = false;
        subtitleTimingStatus = "";
        untimedLines = Collections.emptyList();
        baseCues = Collections.emptyList();
        applySubtitleOffset();
        if (subtitle == null) {
            untimedLines = Collections.emptyList();
            baseCues = Collections.emptyList();
            applySubtitleOffset();
            subtitleEmpty.setText("本集台词稿缺失\n可从右上角选择 PDF、SRT 或 VTT");
            subtitleEmpty.setVisibility(View.VISIBLE);
            bindMediaStatus();
            return;
        }
        subtitleEmpty.setText("正在加载双语台词…");
        subtitleEmpty.setVisibility(View.VISIBLE);
        subtitleLoader.execute(() -> {
            List<SubtitleCue> timed = Collections.emptyList();
            List<String> transcript = Collections.emptyList();
            AlignmentIndex index = null;
            List<SubtitleCue> aligned = Collections.emptyList();
            String timingStatus = "";
            String error = "";
            try {
                boolean pdf = isPdf(subtitle);
                if (pdf && store.isRemoteSubtitle(episode)) transcript = TranscriptCache.read(this, episode);
                if (transcript.isEmpty()) {
                    try (InputStream input = openInput(subtitle)) {
                        if (input == null) throw new IllegalStateException("subtitle unavailable");
                        if (pdf) transcript = PdfTranscriptParser.parse(input);
                        else timed = SubtitleParser.parse(input);
                    }
                    if (pdf && store.isRemoteSubtitle(episode)) TranscriptCache.write(this, episode, transcript);
                }
                if (timed.isEmpty() && transcript.isEmpty()) error = "字幕中没有识别到有效台词";
                if (!transcript.isEmpty()) {
                    timingStatus = "台词稿无时间码，仅供浏览";
                    if (useAlignment) {
                        try (InputStream input = getAssets().open("alignments/" + episode.key + ".tsv")) {
                            index = AlignmentIndex.read(input, episode.key);
                            aligned = index.bind(transcript);
                            timingStatus = "正在核对音频版本…";
                        } catch (Exception unavailable) {
                            index = null;
                            timingStatus = "暂无匹配时间码，仅供浏览";
                        }
                    }
                } else if (!timed.isEmpty()) timingStatus = "文件时间码";
            } catch (Exception exception) {
                error = "字幕读取失败，请检查内网连接或重新选择文件";
            }
            List<SubtitleCue> timedResult = timed;
            List<String> transcriptResult = transcript;
            String message = error;
            AlignmentIndex indexResult = index;
            List<SubtitleCue> alignedResult = aligned;
            String timingResult = timingStatus;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || request != subtitleRequest) return;
                untimedLines = transcriptResult;
                baseCues = timedResult;
                alignment = indexResult;
                alignedCues = alignedResult;
                subtitleTimingStatus = timingResult;
                applySubtitleOffset();
                verifyAlignedAudio();
                activeCue = Integer.MIN_VALUE;
                subtitleEmpty.setVisibility(cues.isEmpty() && untimedLines.isEmpty() ? View.VISIBLE : View.GONE);
                if (!message.isEmpty()) subtitleEmpty.setText(message);
                renderSubtitle(currentPosition());
                bindMediaStatus();
            });
        });
    }

    private InputStream openInput(Uri uri) throws Exception {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            URLConnection connection = new URL(uri.toString()).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "FilmAudio/" + BuildConfig.VERSION_NAME);
            return new BufferedInputStream(connection.getInputStream());
        }
        return getContentResolver().openInputStream(uri);
    }

    private boolean isPdf(Uri uri) {
        String path = uri.getPath();
        if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".pdf")) return true;
        String type = getContentResolver().getType(uri);
        if (type != null && type.toLowerCase(Locale.ROOT).contains("pdf")) return true;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    String name = cursor.getString(0);
                    return name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf");
                }
            } catch (RuntimeException ignored) {}
        }
        return false;
    }

    private void verifyAlignedAudio() {
        if (alignment == null || playback == null || !playback.isCurrent(episode)) return;
        String fingerprint = playback.audioFingerprint();
        if (fingerprint.isEmpty() || playback.isLoading()) return;
        boolean matches = alignment.matchesAudio(fingerprint, currentDuration());
        subtitleTimingStatus = matches ? (alignment.partialAudio() ? "原音片段对齐 · 后续音频缺失" : "原音逐句对齐")
                : "音频版本不匹配，仅供浏览";
        if (matches == audioAlignmentVerified) return;
        audioAlignmentVerified = matches;
        baseCues = matches ? alignedCues : Collections.emptyList();
        if (matches) store.useAlignmentRevision(episode, "audio-ctc-v1-" + alignment.audioSha256);
        applySubtitleOffset();
        renderSubtitle(currentPosition());
    }

    private void applySubtitleOffset() {
        long offset = store.subtitleOffset(episode);
        List<SubtitleCue> adjusted = new ArrayList<>(baseCues.size());
        for (SubtitleCue cue : baseCues) {
            long start = Math.max(0L, cue.startMs + offset);
            long end = Math.max(start + 1L, cue.endMs + offset);
            adjusted.add(new SubtitleCue(start, end, cue.text));
        }
        cues = Collections.unmodifiableList(adjusted);
        if (!cues.isEmpty()) subtitleAdapter.submit(cues);
        else {
            List<SubtitleCue> browseOnly = new ArrayList<>();
            for (String text : untimedLines) browseOnly.add(new SubtitleCue(-1L, -1L, text));
            subtitleAdapter.submit(browseOnly);
        }
        activeCue = Integer.MIN_VALUE;
        subtitleEmpty.setVisibility(cues.isEmpty() && untimedLines.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void renderSubtitle(long position) {
        if (cues.isEmpty() || subtitleDragging) return;
        int index = SubtitleParser.activeIndex(cues, position);
        if (index == activeCue) return;
        activeCue = index;
        subtitleAdapter.setActive(index);
        // Silence, scene changes and rejected alignment intervals have no active
        // speech. Keep the scroll position but do not highlight the preceding line.
        if (index >= 0) centerSubtitle(index);
    }

    private void centerSubtitle(int index) {
        if (index < 0 || subtitleList.getHeight() <= 0) return;
        subtitleList.stopScroll();
        // Highlighting changes row height. Center only AFTER RecyclerView has
        // laid out that height; posting immediately can measure the old row.
        subtitleList.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                subtitleList.getViewTreeObserver().removeOnPreDrawListener(this);
                if (subtitleDragging || activeCue != index) return true;
                View child = subtitleLayout.findViewByPosition(index);
                if (child != null) {
                    int childCenter = (child.getTop() + child.getBottom()) / 2;
                    subtitleList.scrollBy(0, childCenter - subtitleList.getHeight() / 2);
                }
                return true;
            }
        });
        subtitleLayout.scrollToPositionWithOffset(index, 0);
    }

    private void bindSubtitleScrolling() {
        subtitleList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    progressHandler.removeCallbacks(resumeSubtitleFollowing);
                    subtitleDragging = true;
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && subtitleDragging) {
                    progressHandler.removeCallbacks(resumeSubtitleFollowing);
                    progressHandler.postDelayed(resumeSubtitleFollowing, 4_000L);
                }
            }
        });
    }

    private int centeredSubtitle() {
        int center = subtitleList.getHeight() / 2;
        int best = -1;
        int distance = Integer.MAX_VALUE;
        for (int index = 0; index < subtitleList.getChildCount(); index++) {
            View child = subtitleList.getChildAt(index);
            int candidate = Math.abs((child.getTop() + child.getBottom()) / 2 - center);
            if (candidate < distance) {
                distance = candidate;
                best = subtitleList.getChildAdapterPosition(child);
            }
        }
        return best;
    }

    private void seekToCue(SubtitleCue cue) {
        if (playback == null || cue == null) return;
        if (cue.startMs < 0L || cues.isEmpty()) {
            Toast.makeText(this, "这份台词没有匹配时间码，请选择对应音频的 SRT 或 VTT", Toast.LENGTH_LONG).show();
            return;
        }
        subtitleDragging = false;
        progressHandler.removeCallbacks(resumeSubtitleFollowing);
        playback.seekTo(cue.startMs, true);
        activeCue = Integer.MIN_VALUE;
        renderSubtitle(cue.startMs);
    }

    private void seekRelativeCue(int offset) {
        if (playback == null || cues.isEmpty()) return;
        long position = currentPosition();
        int base = SubtitleParser.activeIndex(cues, position);
        if (base < 0) base = Math.max(0, SubtitleParser.indexAtOrBefore(cues, position));
        int target = Math.max(0, Math.min(cues.size() - 1, base + offset));
        seekToCue(cues.get(target));
    }

    private void bindSeekBar() {
        playbackSeek.setMax(1000);
        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long duration = currentDuration();
                if (duration > 0) currentTime.setText(time(duration * progress / seekBar.getMax()));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { seekBarDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (playback != null) {
                    long duration = currentDuration();
                    if (duration > 0) playback.seekTo(duration * seekBar.getProgress() / seekBar.getMax(), false);
                }
                seekBarDragging = false;
            }
        });
    }

    private void renderProgress(long position, long duration) {
        if (!seekBarDragging) {
            int progress = duration > 0 ? (int) Math.min(1000, position * 1000 / duration) : 0;
            playbackSeek.setProgress(progress);
            currentTime.setText(time(position));
        }
        durationTime.setText(duration > 0 ? time(duration) : "--:--");
        if (playback != null && playback.isLoading()) playPause.setText("加载中");
        else playPause.setText(playback != null && playback.isPlaying() ? "暂停" : "播放");
        speedButton.setText(formatSpeed(store.speed()));
    }

    private void togglePlayback() {
        if (playback != null) playback.toggle();
        else ContextCompat.startForegroundService(this, PlaybackService.loadIntent(this, episode));
    }

    private void showSpeedMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        float selected = store.speed();
        for (int index = 0; index < SPEEDS.length; index++) {
            float speed = SPEEDS[index];
            popup.getMenu().add(Menu.NONE, index, index,
                    (Math.abs(speed - selected) < 0.01f ? "✓ " : "") + formatSpeed(speed));
        }
        popup.setOnMenuItemClickListener(item -> {
            float speed = SPEEDS[item.getItemId()];
            if (playback != null) playback.setRate(speed);
            else store.saveSpeed(speed);
            speedButton.setText(formatSpeed(speed));
            bindMediaStatus();
            return true;
        });
        popup.show();
    }

    private void showSubtitleMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(Menu.NONE, 1, 1, "选择字幕文件");
        if (!cues.isEmpty()) {
            popup.getMenu().add(Menu.NONE, 2, 2, "将中间字幕对齐当前声音");
            popup.getMenu().add(Menu.NONE, 3, 3, "字幕提前 0.5 秒");
            popup.getMenu().add(Menu.NONE, 4, 4, "字幕延后 0.5 秒");
            if (store.subtitleOffset(episode) != 0L) popup.getMenu().add(Menu.NONE, 5, 5, "重置字幕同步");
        }
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) chooseSubtitle();
            else if (item.getItemId() == 2) alignCenteredSubtitle();
            else if (item.getItemId() == 3) adjustSubtitleOffset(-500L);
            else if (item.getItemId() == 4) adjustSubtitleOffset(500L);
            else if (item.getItemId() == 5) setSubtitleOffset(0L, "字幕同步已重置");
            return true;
        });
        popup.show();
    }

    private void alignCenteredSubtitle() {
        int index = centeredSubtitle();
        SubtitleCue cue = subtitleAdapter.cue(index);
        if (cue == null) {
            Toast.makeText(this, "请先把正在听到的字幕拖到中间", Toast.LENGTH_SHORT).show();
            return;
        }
        long updated = store.subtitleOffset(episode) + currentPosition() - cue.startMs;
        setSubtitleOffset(updated, "已将中间字幕与当前声音对齐");
    }

    private void adjustSubtitleOffset(long delta) {
        String message = delta < 0 ? "字幕已提前 0.5 秒" : "字幕已延后 0.5 秒";
        setSubtitleOffset(store.subtitleOffset(episode) + delta, message);
    }

    private void setSubtitleOffset(long value, String message) {
        store.saveSubtitleOffset(episode, value);
        subtitleDragging = false;
        progressHandler.removeCallbacks(resumeSubtitleFollowing);
        applySubtitleOffset();
        renderSubtitle(currentPosition());
        bindMediaStatus();
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void chooseAudio() {
        audioPicker.launch(new String[]{"audio/*", "application/ogg", "video/x-ms-wmv", "audio/x-ms-wma"});
    }

    private void chooseSubtitle() {
        subtitlePicker.launch(new String[]{"application/pdf", "application/x-subrip", "text/srt", "text/vtt", "text/plain"});
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
            store.saveSubtitleOffset(episode, 0L);
            loadSubtitles();
            if (playback != null) playback.load(episode, true);
            else ContextCompat.startForegroundService(this, PlaybackService.loadIntent(this, episode));
        } else {
            store.saveSubtitle(episode, uri);
            store.saveSubtitleOffset(episode, 0L);
            loadSubtitles();
        }
        bindMediaStatus();
    }

    private void bindMediaStatus() {
        Uri audio = store.audio(episode);
        Uri subtitle = store.subtitle(episode);
        String value = audio == null ? "本集音频缺失" : store.isRemoteAudio(episode) ? "内网音频" : "本地音频";
        value += subtitle == null ? " · 台词稿缺失" : store.isRemoteSubtitle(episode) ? " · 双语 PDF 台词稿" : " · 本地字幕";
        if (!cues.isEmpty()) value += " · " + cues.size() + " 条";
        if (!subtitleTimingStatus.isEmpty()) value += " · " + subtitleTimingStatus;
        if (audioAlignmentVerified && alignment != null && baseCues.size() < alignment.sourceLineCount) {
            value += " · " + (alignment.sourceLineCount - baseCues.size()) + " 句未确认";
        }
        long offset = store.subtitleOffset(episode);
        if (offset != 0L) value += String.format(Locale.CHINA, " · 同步%+.1f秒", offset / 1000d);
        mediaStatus.setText(value);
    }

    private long currentPosition() {
        return playback != null && playback.isCurrent(episode) ? playback.position() : store.progress(episode);
    }

    private long currentDuration() {
        long duration = playback != null && playback.isCurrent(episode) ? playback.duration() : store.duration(episode);
        return Math.max(0L, duration);
    }

    private void applyInsets(View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left + dp(14), bars.top + dp(6), bars.right + dp(14), bars.bottom + dp(10));
            return insets;
        });
    }

    private void requestPlaybackNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String time(long millis) {
        long seconds = Math.max(0, millis / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private static String formatSpeed(float speed) {
        String value = Float.toString(speed);
        if (value.endsWith(".0")) value = value.substring(0, value.length() - 2);
        return value + "x";
    }
}
