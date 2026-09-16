package com.firewolf.friendsspeaking;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlayerActivity extends AppCompatActivity {
    public static final String SEASON = "season";
    public static final String EPISODE = "episode";
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final long DEFAULT_EPISODE_DURATION_MS = 23L * 60L * 1000L;

    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService playerLoader = Executors.newSingleThreadExecutor();
    private final ExecutorService subtitleLoader = Executors.newSingleThreadExecutor();
    private final Runnable updateProgress = new Runnable() {
        @Override public void run() {
            if (player != null) {
                long duration = currentDuration();
                long position = Math.max(0L, player.getTime());
                rebuildEstimatedTimeline(duration);
                renderSubtitle(position);
                renderProgress(position, duration);
            }
            progressHandler.postDelayed(this, 250);
        }
    };
    private final ActivityResultLauncher<String[]> audioPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> savePicked(uri, true));
    private final ActivityResultLauncher<String[]> subtitlePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> savePicked(uri, false));

    private Episode episode;
    private LearningStore store;
    private LibVLC libVLC;
    private MediaPlayer player;
    private ParcelFileDescriptor localAudioDescriptor;
    private RecyclerView subtitleList;
    private LinearLayoutManager subtitleLayout;
    private SubtitleAdapter subtitleAdapter;
    private TextView subtitleEmpty;
    private TextView mediaStatus;
    private TextView currentTime;
    private TextView durationTime;
    private Button playPause;
    private SeekBar playbackSeek;
    private List<SubtitleCue> cues = Collections.emptyList();
    private List<String> untimedLines = Collections.emptyList();
    private long timelineDuration;
    private int activeCue = Integer.MIN_VALUE;
    private boolean seekBarDragging;
    private boolean subtitleDragging;
    private boolean resumeAfterSubtitleDrag;
    private boolean restoredPosition;
    private boolean playerLoading;
    private boolean activityStarted;
    private int playerRequest;

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
        PDFBoxResourceLoader.init(getApplicationContext());

        mediaStatus = findViewById(R.id.media_status);
        currentTime = findViewById(R.id.current_time);
        durationTime = findViewById(R.id.duration_time);
        playPause = findViewById(R.id.play_pause);
        playbackSeek = findViewById(R.id.playback_seek);
        subtitleList = findViewById(R.id.subtitle_list);
        subtitleEmpty = findViewById(R.id.subtitle_empty);
        subtitleLayout = new LinearLayoutManager(this);
        subtitleAdapter = new SubtitleAdapter(this::seekToCue);
        subtitleList.setLayoutManager(subtitleLayout);
        subtitleList.setAdapter(subtitleAdapter);
        subtitleList.post(() -> subtitleList.setPadding(
                subtitleList.getPaddingLeft(), subtitleList.getHeight() / 2,
                subtitleList.getPaddingRight(), subtitleList.getHeight() / 2));
        bindSubtitleScrolling();

        ((TextView) findViewById(R.id.episode_title)).setText("老友记 · " + episode.displayTitle() + " · " + episode.key);
        findViewById(R.id.back).setOnClickListener(view -> finish());
        findViewById(R.id.import_audio).setOnClickListener(view -> audioPicker.launch(
                new String[]{"audio/*", "application/ogg", "video/x-ms-wmv", "audio/x-ms-wma"}));
        findViewById(R.id.import_subtitle).setOnClickListener(view -> subtitlePicker.launch(
                new String[]{"application/pdf", "application/x-subrip", "text/srt", "text/vtt", "text/plain"}));
        findViewById(R.id.previous_line).setOnClickListener(view -> seekRelativeCue(-1));
        findViewById(R.id.replay_line).setOnClickListener(view -> seekRelativeCue(0));
        findViewById(R.id.next_line).setOnClickListener(view -> seekRelativeCue(1));
        playPause.setOnClickListener(view -> togglePlayback());
        bindSeekBar();
        bindSpeedOptions();
        bindMediaStatus();
        loadSubtitles();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        initializePlayer();
        progressHandler.post(updateProgress);
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        playerRequest++;
        playerLoading = false;
        progressHandler.removeCallbacks(updateProgress);
        releasePlayer();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        playerLoader.shutdownNow();
        subtitleLoader.shutdownNow();
        super.onDestroy();
    }

    private void initializePlayer() {
        Uri audio = store.audio(episode);
        if (player != null || playerLoading || audio == null) return;
        playerLoading = true;
        playPause.setText("加载中");
        int request = ++playerRequest;
        playerLoader.execute(() -> {
            LibVLC candidate;
            try {
                candidate = new LibVLC(getApplicationContext(), new ArrayList<>(Arrays.asList(
                        "--audio-time-stretch", "--network-caching=1800")));
            } catch (RuntimeException error) {
                runOnUiThread(() -> playerLoadFailed(request));
                return;
            }
            runOnUiThread(() -> finishPlayerLoad(request, candidate, audio));
        });
    }

    private void finishPlayerLoad(int request, LibVLC candidate, Uri audio) {
        if (!activityStarted || request != playerRequest || isFinishing() || isDestroyed()) {
            candidate.release();
            return;
        }
        try {
            libVLC = candidate;
            player = new MediaPlayer(libVLC);
            player.setEventListener(event -> runOnUiThread(() -> onPlayerEvent(event.type)));
            Media media = openMedia(audio);
            media.addOption(":network-caching=1800");
            player.setMedia(media);
            media.release();
            player.play();
            playPause.setText("暂停");
            playerLoading = false;
            bindMediaStatus();
        } catch (Exception error) {
            releasePlayer();
            playerLoading = false;
            Toast.makeText(this, "音频无法打开，请检查内网连接或选择本地文件", Toast.LENGTH_LONG).show();
        }
    }

    private void playerLoadFailed(int request) {
        if (request != playerRequest || isFinishing() || isDestroyed()) return;
        playerLoading = false;
        playPause.setText("重试");
        Toast.makeText(this, "播放器初始化失败，请重新打开本集", Toast.LENGTH_LONG).show();
    }

    private Media openMedia(Uri uri) throws Exception {
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme) || "android.resource".equalsIgnoreCase(scheme)) {
            localAudioDescriptor = getContentResolver().openFileDescriptor(uri, "r");
            if (localAudioDescriptor == null) throw new IllegalStateException("audio unavailable");
            return new Media(libVLC, localAudioDescriptor.getFileDescriptor());
        }
        return new Media(libVLC, uri);
    }

    private void onPlayerEvent(int type) {
        if (player == null) return;
        if (type == MediaPlayer.Event.Playing) {
            player.setRate(store.speed());
            if (!restoredPosition) {
                restoredPosition = true;
                long saved = store.progress(episode);
                if (saved > 0) player.setTime(saved);
            }
            playPause.setText("暂停");
        } else if (type == MediaPlayer.Event.Paused || type == MediaPlayer.Event.Stopped
                || type == MediaPlayer.Event.EndReached) {
            playPause.setText("播放");
        } else if (type == MediaPlayer.Event.EncounteredError) {
            playPause.setText("重试");
            Toast.makeText(this, "音频播放失败，请检查是否连接到媒体服务器", Toast.LENGTH_LONG).show();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            long position = Math.max(0L, player.getTime());
            long duration = currentDuration();
            store.saveProgress(episode, position, duration);
            player.stop();
            player.release();
            player = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
        if (localAudioDescriptor != null) {
            try { localAudioDescriptor.close(); } catch (Exception ignored) {}
            localAudioDescriptor = null;
        }
        restoredPosition = false;
    }

    private void loadSubtitles() {
        Uri subtitle = store.subtitle(episode);
        if (subtitle == null) {
            untimedLines = Collections.emptyList();
            cues = Collections.emptyList();
            subtitleAdapter.submit(cues);
            subtitleEmpty.setText("本集台词稿缺失\n可在下方选择本地 PDF、SRT 或 VTT");
            subtitleEmpty.setVisibility(View.VISIBLE);
            bindMediaStatus();
            return;
        }
        subtitleEmpty.setText("正在加载双语台词…");
        subtitleEmpty.setVisibility(View.VISIBLE);
        subtitleLoader.execute(() -> {
            List<SubtitleCue> timed = Collections.emptyList();
            List<String> transcript = Collections.emptyList();
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
            } catch (Exception exception) {
                error = "字幕读取失败，请检查内网连接或重新选择文件";
            }
            List<SubtitleCue> timedResult = timed;
            List<String> transcriptResult = transcript;
            String message = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                untimedLines = transcriptResult;
                timelineDuration = 0L;
                cues = timedResult;
                if (!untimedLines.isEmpty()) rebuildEstimatedTimeline(currentDuration());
                else subtitleAdapter.submit(cues);
                activeCue = Integer.MIN_VALUE;
                subtitleEmpty.setVisibility(cues.isEmpty() ? View.VISIBLE : View.GONE);
                if (!message.isEmpty()) subtitleEmpty.setText(message);
                renderSubtitle(player == null ? store.progress(episode) : Math.max(0L, player.getTime()));
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

    private void rebuildEstimatedTimeline(long duration) {
        if (untimedLines.isEmpty()) return;
        long target = duration > 0 ? duration : DEFAULT_EPISODE_DURATION_MS;
        if (target == timelineDuration) return;
        timelineDuration = target;
        cues = SubtitleTimeline.distribute(untimedLines, target);
        subtitleAdapter.submit(cues);
        activeCue = Integer.MIN_VALUE;
        subtitleEmpty.setVisibility(cues.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void renderSubtitle(long position) {
        if (cues.isEmpty()) return;
        int index = SubtitleParser.activeIndex(cues, position);
        if (index < 0) index = Math.max(0, Math.min(cues.size() - 1, firstAfter(position)));
        if (subtitleDragging || index == activeCue) return;
        activeCue = index;
        subtitleAdapter.setActive(index);
        int offset = Math.max(0, subtitleList.getHeight() / 2 - dp(48));
        subtitleLayout.scrollToPositionWithOffset(index, offset);
    }

    private void bindSubtitleScrolling() {
        subtitleList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    subtitleDragging = true;
                    resumeAfterSubtitleDrag = player != null && player.isPlaying();
                    if (resumeAfterSubtitleDrag) player.pause();
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE && subtitleDragging) {
                    int target = centeredSubtitle();
                    SubtitleCue cue = subtitleAdapter.cue(target);
                    if (cue != null && player != null) {
                        player.setTime(cue.startMs);
                        if (resumeAfterSubtitleDrag) player.play();
                    }
                    activeCue = target;
                    subtitleAdapter.setActive(target);
                    subtitleDragging = false;
                }
            }

            @Override public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (subtitleDragging) subtitleAdapter.setActive(centeredSubtitle());
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
        if (player == null || cue == null) return;
        player.setTime(cue.startMs);
        player.play();
    }

    private void seekRelativeCue(int offset) {
        if (player == null || cues.isEmpty()) return;
        int base = SubtitleParser.activeIndex(cues, Math.max(0L, player.getTime()));
        if (base < 0) base = Math.max(0, firstAfter(Math.max(0L, player.getTime())) - 1);
        int target = Math.max(0, Math.min(cues.size() - 1, base + offset));
        seekToCue(cues.get(target));
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

    private void bindSeekBar() {
        playbackSeek.setMax(1000);
        playbackSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || player == null) return;
                long duration = currentDuration();
                if (duration > 0) currentTime.setText(time(duration * progress / seekBar.getMax()));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { seekBarDragging = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (player != null) {
                    long duration = currentDuration();
                    if (duration > 0) player.setTime(duration * seekBar.getProgress() / seekBar.getMax());
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
        playPause.setText(player != null && player.isPlaying() ? "暂停" : "播放");
    }

    private void togglePlayback() {
        if (player == null) {
            initializePlayer();
            return;
        }
        if (player.isPlaying()) player.pause();
        else player.play();
    }

    private void bindSpeedOptions() {
        LinearLayout group = findViewById(R.id.speed_options);
        group.removeAllViews();
        float saved = store.speed();
        for (float speed : SPEEDS) {
            TextView chip = new TextView(this);
            chip.setText(formatSpeed(speed));
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
                if (player != null) player.setRate(speed);
                bindSpeedOptions();
                bindMediaStatus();
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
        Uri audio = store.audio(episode);
        Uri subtitle = store.subtitle(episode);
        String value = audio == null ? "本集音频缺失" : store.isRemoteAudio(episode) ? "内网音频" : "本地音频";
        value += subtitle == null ? " · 台词稿缺失" : store.isRemoteSubtitle(episode) ? " · 双语 PDF 台词稿" : " · 本地字幕";
        if (!cues.isEmpty()) value += " · " + cues.size() + " 条";
        if (!untimedLines.isEmpty()) value += " · 内容进度同步";
        value += " · " + formatSpeed(store.speed());
        mediaStatus.setText(value);
    }

    private long currentDuration() {
        if (player != null && player.getLength() > 0) return player.getLength();
        long saved = store.duration(episode);
        return saved > 0 ? saved : 0L;
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
