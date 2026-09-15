package com.firewolf.players;

import android.app.PictureInPictureParams;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.Collections;
import java.util.Locale;

public final class PlayerActivity extends AppCompatActivity {
    private PlayerView playerView;
    private ExoPlayer player;
    private VideoItem item;
    private VideoItem.Stream stream;
    private PlaybackStore store;
    private boolean restoredPosition;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_player);
        item = VideoIntents.get(getIntent());
        int streamIndex = Math.max(0, Math.min(getIntent().getIntExtra(VideoIntents.STREAM_INDEX, 0), item.streams.size() - 1));
        if (item.streams.isEmpty()) {
            Toast.makeText(this, "播放地址不可用", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        stream = item.streams.get(streamIndex);
        store = new PlaybackStore(this);
        playerView = findViewById(R.id.player_view);
        ((TextView) findViewById(R.id.player_title)).setText(item.title + " · " + stream.label);
        findViewById(R.id.player_back).setOnClickListener(view -> finish());
        updatePip(16, 9);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ImageButton back = findViewById(R.id.player_back);
            TextView title = findViewById(R.id.player_title);
            back.setTranslationX(bars.left);
            back.setTranslationY(bars.top);
            title.setTranslationX(bars.left);
            title.setTranslationY(bars.top);
            return insets;
        });
        hideSystemBars();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player == null && stream != null) initializePlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isInPictureInPictureMode()) releasePlayer();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, android.content.res.Configuration configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, configuration);
        findViewById(R.id.player_back).setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        findViewById(R.id.player_title).setVisibility(isInPictureInPictureMode ? View.GONE : View.VISIBLE);
        playerView.setUseController(!isInPictureInPictureMode);
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return;
        if (player != null && player.isPlaying() && getPackageManager().hasSystemFeature("android.software.picture_in_picture")) {
            enterPip();
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void initializePlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("PlayersAndroid/1.0")
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, http);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .build();
        player.setHandleAudioBecomingNoisy(true);
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Toast.makeText(PlayerActivity.this, "播放失败，请检查网络或切换清晰度", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                updatePip(videoSize.width, videoSize.height);
            }
        });
        player.setMediaItem(buildMediaItem());
        long position = restoredPosition ? 0 : store.progress(item.id);
        restoredPosition = true;
        if (position > 0) player.seekTo(position);
        player.prepare();
        player.play();
    }

    private MediaItem buildMediaItem() {
        MediaItem.Builder builder = new MediaItem.Builder()
                .setUri(Uri.parse(stream.url))
                .setMediaId(item.id)
                .setMediaMetadata(new androidx.media3.common.MediaMetadata.Builder().setTitle(item.title).build());
        if (!stream.mimeType.isEmpty()) builder.setMimeType(stream.mimeType);
        if (!stream.subtitleUrl.isEmpty()) {
            String mime = stream.subtitleUrl.toLowerCase(Locale.ROOT).endsWith(".vtt")
                    ? MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP;
            MediaItem.SubtitleConfiguration subtitles = new MediaItem.SubtitleConfiguration.Builder(Uri.parse(stream.subtitleUrl))
                    .setMimeType(mime)
                    .setLanguage("zh")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build();
            builder.setSubtitleConfigurations(Collections.singletonList(subtitles));
        }
        return builder.build();
    }

    private void releasePlayer() {
        if (player == null) return;
        store.saveProgress(item.id, player.getCurrentPosition(), player.getDuration());
        playerView.setPlayer(null);
        player.release();
        player = null;
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    private void updatePip(int width, int height) {
        if (width <= 0 || height <= 0) return;
        Rational ratio = boundedRatio(width, height);
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder().setAspectRatio(ratio);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(true).setSeamlessResizeEnabled(true);
        setPictureInPictureParams(builder.build());
    }

    private void enterPip() {
        if (isInPictureInPictureMode()) return;
        int width = player == null ? 16 : player.getVideoSize().width;
        int height = player == null ? 9 : player.getVideoSize().height;
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(boundedRatio(width, height));
        Rect visible = new Rect();
        playerView.getGlobalVisibleRect(visible);
        if (!visible.isEmpty()) builder.setSourceRectHint(visible);
        enterPictureInPictureMode(builder.build());
    }

    private static Rational boundedRatio(int width, int height) {
        if (width <= 0 || height <= 0) return new Rational(16, 9);
        float ratio = (float) width / height;
        if (ratio > 2.39f) return new Rational(239, 100);
        if (ratio < 1f / 2.39f) return new Rational(100, 239);
        return new Rational(width, height);
    }
}
