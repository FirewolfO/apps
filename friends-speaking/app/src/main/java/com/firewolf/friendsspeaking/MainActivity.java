package com.firewolf.friendsspeaking;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

/** Product catalog. Individual films and series are opened from here. */
public final class MainActivity extends AppCompatActivity {
    private LearningStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_main);
        applyInsets(findViewById(R.id.root));
        store = new LearningStore(this);

        findViewById(R.id.friends_card).setOnClickListener(view ->
                startActivity(new Intent(this, SeriesActivity.class)));
        findViewById(R.id.resume_card).setOnClickListener(view -> {
            Episode episode = store.lastEpisode();
            if (episode != null) open(episode);
        });
        findViewById(R.id.check_update).setOnClickListener(view -> AppUpdateChecker.check(this, true));
        bindCatalog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) bindCatalog();
        AppUpdateChecker.resumePending(this);
        AppUpdateChecker.check(this, false);
    }

    private void bindCatalog() {
        int ready = store.readyCount();
        ((TextView) findViewById(R.id.friends_availability)).setText(
                ready + " / " + Episode.all().size() + " 集音频与台词稿可直接播放");
        Episode episode = store.lastEpisode();
        View card = findViewById(R.id.resume_card);
        if (episode == null || store.audio(episode) == null) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.resume_title)).setText("老友记 · " + episode.displayTitle());
        long position = store.progress(episode);
        long duration = store.duration(episode);
        String detail = "从 " + time(position) + " 继续";
        if (duration > 0) detail += " · 已播放 " + Math.min(100, position * 100 / duration) + "%";
        ((TextView) findViewById(R.id.resume_progress)).setText(detail);
    }

    private void open(Episode episode) {
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra(PlayerActivity.SEASON, episode.season)
                .putExtra(PlayerActivity.EPISODE, episode.number));
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
}
