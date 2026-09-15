package com.firewolf.players;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

public final class DetailActivity extends AppCompatActivity {
    private VideoItem item;
    private PlaybackStore store;
    private TextView favorite;
    private TextView resumeHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.enableEdgeToEdge(getWindow());
        setContentView(R.layout.activity_detail);
        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        item = VideoIntents.get(getIntent());
        store = new PlaybackStore(this);
        favorite = findViewById(R.id.favorite);
        resumeHint = findViewById(R.id.resume_hint);
        findViewById(R.id.back).setOnClickListener(view -> finish());

        ((TextView) findViewById(R.id.detail_title)).setText(item.title);
        ((TextView) findViewById(R.id.detail_meta)).setText(meta(item));
        ((TextView) findViewById(R.id.detail_summary)).setText(item.summary);
        TextView license = findViewById(R.id.detail_license);
        license.setText("来源：" + item.source + "\n许可：" + item.licenseName + "\n点击查看来源与使用条款");
        license.setOnClickListener(view -> openUrl(item.licenseUrl));
        ImageLoader.get(this).load(item.posterUrl, (ImageView) findViewById(R.id.detail_poster));

        updateFavorite();
        favorite.setOnClickListener(view -> {
            store.toggleFavorite(item.id);
            updateFavorite();
        });
        findViewById(R.id.play).setOnClickListener(view -> chooseQuality());
        updateResumeHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store != null) updateResumeHint();
    }

    private void chooseQuality() {
        if (item.streams.isEmpty()) {
            Toast.makeText(this, "当前播放地址不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        if (item.streams.size() == 1) {
            play(0);
            return;
        }
        String[] labels = new String[item.streams.size()];
        for (int index = 0; index < labels.length; index++) labels[index] = item.streams.get(index).label;
        new AlertDialog.Builder(this)
                .setTitle("选择清晰度")
                .setItems(labels, (dialog, which) -> play(which))
                .show();
    }

    private void play(int streamIndex) {
        Intent intent = new Intent(this, PlayerActivity.class);
        VideoIntents.put(intent, item);
        intent.putExtra(VideoIntents.STREAM_INDEX, streamIndex);
        startActivity(intent);
    }

    private void updateFavorite() {
        boolean selected = store.isFavorite(item.id);
        favorite.setSelected(selected);
        favorite.setText(selected ? R.string.favorited : R.string.favorite);
        favorite.setTextColor(getColor(selected ? R.color.surface : R.color.text_primary));
    }

    private void updateResumeHint() {
        long progress = store.progress(item.id);
        if (progress < 5_000) {
            resumeHint.setVisibility(View.GONE);
            return;
        }
        resumeHint.setVisibility(View.VISIBLE);
        resumeHint.setText("将从 " + formatDuration(progress) + " 继续播放");
    }

    private static String meta(VideoItem item) {
        StringBuilder value = new StringBuilder(item.badge).append(" · ").append(item.category);
        if (!item.year.isEmpty()) value.append(" · ").append(item.year);
        if (item.streams.size() > 1) value.append(" · ").append(item.streams.size()).append(" 档清晰度");
        return value.toString();
    }

    private static String formatDuration(long milliseconds) {
        long seconds = milliseconds / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void openUrl(String url) {
        if (url == null || !url.startsWith("https://")) return;
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
