package com.firewolf.friendsspeaking;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns audio playback independently from the player screen so locking the phone cannot stop it. */
public final class PlaybackService extends Service {
    public interface Listener {
        void onPlaybackChanged();
        void onPlaybackError(String message);
    }

    public static final String ACTION_LOAD = "com.firewolf.friendsspeaking.LOAD";
    private static final String ACTION_TOGGLE = "com.firewolf.friendsspeaking.TOGGLE";
    private static final String ACTION_STOP = "com.firewolf.friendsspeaking.STOP";
    private static final String EXTRA_SEASON = "playback_season";
    private static final String EXTRA_EPISODE = "playback_episode";
    private static final String CHANNEL_ID = "audio_playback";
    private static final int NOTIFICATION_ID = 31;

    private final LocalBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (++progressTicks % 5 == 0) saveProgress(false);
            updateSessionState();
            notifyChanged();
            handler.postDelayed(this, 1_000L);
        }
    };

    private LearningStore store;
    private LibVLC libVLC;
    private MediaPlayer player;
    private ParcelFileDescriptor localAudioDescriptor;
    private Episode episode;
    private MediaSession mediaSession;
    private PowerManager.WakeLock playbackWakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean resumeAfterFocusGain;
    private boolean restoredPosition;
    private boolean loading;
    private boolean foreground;
    private int loadRequest;
    private int progressTicks;

    public static Intent loadIntent(Context context, Episode episode) {
        return new Intent(context, PlaybackService.class)
                .setAction(ACTION_LOAD)
                .putExtra(EXTRA_SEASON, episode.season)
                .putExtra(EXTRA_EPISODE, episode.number);
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new LearningStore(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        playbackWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":audio-playback");
        playbackWakeLock.setReferenceCounted(false);
        createNotificationChannel();
        createMediaSession();
        handler.post(progressTick);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopPlayback();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            toggle();
            return START_NOT_STICKY;
        }
        if (ACTION_LOAD.equals(action)) {
            Episode requested = Episode.of(intent.getIntExtra(EXTRA_SEASON, 0),
                    intent.getIntExtra(EXTRA_EPISODE, 0));
            if (requested != null) {
                ensureForeground();
                load(requested, false);
            }
        }
        return START_NOT_STICKY;
    }

    @Nullable
    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveProgress(false);
        releasePlayer();
        releaseWakeLock();
        abandonAudioFocus();
        if (mediaSession != null) mediaSession.release();
        loader.shutdownNow();
        super.onDestroy();
    }

    public final class LocalBinder extends Binder {
        PlaybackService service() { return PlaybackService.this; }
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isCurrent(Episode value) {
        return episode != null && value != null && episode.key.equals(value.key);
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public boolean isLoading() {
        return loading;
    }

    public long position() {
        return player == null ? (episode == null ? 0L : store.progress(episode))
                : Math.max(0L, player.getTime());
    }

    public long duration() {
        if (player != null && player.getLength() > 0) return player.getLength();
        return episode == null ? 0L : store.duration(episode);
    }

    public void load(Episode requested, boolean force) {
        if (requested == null) return;
        if (!force && isCurrent(requested) && (player != null || loading)) return;
        saveProgress(false);
        releasePlayer();
        episode = requested;
        restoredPosition = false;
        if (mediaSession != null) mediaSession.setActive(true);
        ensureForeground();
        updateSessionMetadata();
        Uri audio = store.audio(requested);
        if (audio == null) {
            notifyError("本集音频缺失，请先选择音频文件");
            return;
        }
        loading = true;
        notifyChanged();
        updateNotification();
        int request = ++loadRequest;
        loader.execute(() -> {
            LibVLC candidate;
            try {
                candidate = new LibVLC(getApplicationContext(), new ArrayList<>(Arrays.asList(
                        "--audio-time-stretch", "--network-caching=450")));
            } catch (RuntimeException error) {
                handler.post(() -> loadFailed(request, "播放器初始化失败，请重新打开本集"));
                return;
            }
            handler.post(() -> finishLoad(request, candidate, audio));
        });
    }

    public void toggle() {
        if (player == null) {
            if (episode != null) ContextCompat.startForegroundService(this, loadIntent(this, episode));
            return;
        }
        if (player.isPlaying()) pause();
        else play();
    }

    public void play() {
        if (player == null) {
            if (episode != null) ContextCompat.startForegroundService(this, loadIntent(this, episode));
            return;
        }
        ensureForeground();
        requestAudioFocus();
        long duration = duration();
        if (duration > 0 && position() >= duration - 1_000L) player.setTime(0L);
        player.play();
        updateNotification();
        updateSessionState();
        notifyChanged();
    }

    public void pause() {
        if (player != null && player.isPlaying()) player.pause();
        saveProgress(false);
        releaseWakeLock();
        updateNotification();
        updateSessionState();
        notifyChanged();
    }

    public void seekTo(long millis, boolean playAfterSeek) {
        if (player == null) return;
        long duration = duration();
        long target = Math.max(0L, duration > 0 ? Math.min(duration, millis) : millis);
        player.setTime(target);
        if (playAfterSeek && !player.isPlaying()) play();
        saveProgress(false);
        updateSessionState();
        notifyChanged();
    }

    public void setRate(float rate) {
        store.saveSpeed(rate);
        if (player != null) player.setRate(rate);
        updateNotification();
        notifyChanged();
    }

    private void finishLoad(int request, LibVLC candidate, Uri audio) {
        if (request != loadRequest || episode == null) {
            candidate.release();
            return;
        }
        try {
            libVLC = candidate;
            player = new MediaPlayer(libVLC);
            player.setEventListener(event -> handler.post(() -> onPlayerEvent(event.type)));
            Media media = openMedia(audio);
            media.addOption(":network-caching=450");
            player.setMedia(media);
            media.release();
            loading = false;
            requestAudioFocus();
            player.play();
            updateNotification();
            notifyChanged();
        } catch (Exception error) {
            releasePlayer();
            loading = false;
            notifyError("音频无法打开，请检查内网连接或重新选择音频");
        }
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

    private void loadFailed(int request, String message) {
        if (request != loadRequest) return;
        loading = false;
        updateNotification();
        notifyError(message);
    }

    private void onPlayerEvent(int type) {
        if (player == null) return;
        if (type == MediaPlayer.Event.Playing) {
            player.setRate(store.speed());
            if (!restoredPosition && episode != null) {
                restoredPosition = true;
                long saved = store.progress(episode);
                long duration = duration();
                if (saved > 0 && (duration <= 0 || saved < duration - 1_000L)) player.setTime(saved);
            }
            acquireWakeLock();
            updateSessionMetadata();
        } else if (type == MediaPlayer.Event.Paused || type == MediaPlayer.Event.Stopped) {
            saveProgress(false);
            releaseWakeLock();
        } else if (type == MediaPlayer.Event.EndReached) {
            saveProgress(true);
            releaseWakeLock();
            abandonAudioFocus();
        } else if (type == MediaPlayer.Event.EncounteredError) {
            releaseWakeLock();
            notifyError("音频播放失败，请检查是否连接到媒体服务器");
        }
        updateNotification();
        updateSessionState();
        notifyChanged();
    }

    private void saveProgress(boolean completed) {
        if (episode == null) return;
        long position = position();
        long duration = duration();
        if (completed && duration > 0) position = duration;
        store.saveProgress(episode, position, duration);
        if (completed) store.saveCompleted(episode, true);
    }

    private void releasePlayer() {
        loadRequest++;
        loading = false;
        if (player != null) {
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
        releaseWakeLock();
    }

    private void stopPlayback() {
        saveProgress(false);
        releasePlayer();
        abandonAudioFocus();
        if (mediaSession != null) mediaSession.setActive(false);
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "音频播放",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("锁屏和切换应用时继续播放影视音频");
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "FilmAudioPlayback");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSeekTo(long pos) { seekTo(pos, false); }
        });
        mediaSession.setActive(true);
    }

    private void ensureForeground() {
        Notification notification = notification();
        if (!foreground) {
            startForeground(NOTIFICATION_ID, notification);
            foreground = true;
        } else if (canPostNotifications()) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        if (foreground && canPostNotifications()) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private Notification notification() {
        String title = episode == null ? "影视音频" : "老友记 · " + episode.displayTitle();
        String detail = loading ? "正在加载…" : isPlaying() ? "正在播放 · " + speedText(store.speed())
                : "已暂停 · " + speedText(store.speed());
        Intent open = episode == null ? new Intent(this, MainActivity.class)
                : new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerActivity.SEASON, episode.season)
                    .putExtra(PlayerActivity.EPISODE, episode.number);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = PendingIntent.getService(this, 2,
                new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int toggleIcon = isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String toggleTitle = isPlaying() ? "暂停" : "播放";
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying())
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(toggleIcon, toggleTitle, toggle).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                        "停止", stop).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1))
                .build();
    }

    private void updateSessionState() {
        if (mediaSession == null) return;
        int state = loading ? PlaybackState.STATE_BUFFERING
                : isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, position(), store.speed()).build());
    }

    private void updateSessionMetadata() {
        if (mediaSession == null || episode == null) return;
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, episode.displayTitle())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "老友记 · 影视音频")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.max(0L, duration()))
                .build());
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(this::onAudioFocusChanged, handler).build();
            }
            audioManager.requestAudioFocus(audioFocusRequest);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager != null && audioFocusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        resumeAfterFocusGain = false;
    }

    private void onAudioFocusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeAfterFocusGain = isPlaying();
            pause();
        } else if (change == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocusGain) {
            resumeAfterFocusGain = false;
            play();
        }
    }

    private void acquireWakeLock() {
        if (playbackWakeLock != null && !playbackWakeLock.isHeld()) playbackWakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (playbackWakeLock != null && playbackWakeLock.isHeld()) playbackWakeLock.release();
    }

    private void notifyChanged() {
        for (Listener listener : listeners) listener.onPlaybackChanged();
    }

    private void notifyError(String message) {
        for (Listener listener : listeners) listener.onPlaybackError(message);
        notifyChanged();
    }

    private static String speedText(float speed) {
        String value = Float.toString(speed);
        if (value.endsWith(".0")) value = value.substring(0, value.length() - 2);
        return value + "x";
    }
}
