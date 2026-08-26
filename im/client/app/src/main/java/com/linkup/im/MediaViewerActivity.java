package com.linkup.im;

import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.MediaController;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.linkup.im.databinding.ActivityMediaViewerBinding;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MediaViewerActivity extends AppCompatActivity {
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_MIME = "mimeType";
    private ActivityMediaViewerBinding binding;
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private String mediaUrl;
    private String mediaType;
    private String mimeType;

    static void open(Context context, String url, String type, String mimeType) {
        Intent intent = new Intent(context, MediaViewerActivity.class)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_MIME, mimeType);
        context.startActivity(intent);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMediaViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.closeButton.setOnClickListener(view -> finish());
        binding.downloadButton.setOnClickListener(view -> downloadMedia());

        mediaUrl = getIntent().getStringExtra(EXTRA_URL);
        mediaType = getIntent().getStringExtra(EXTRA_TYPE);
        mimeType = getIntent().getStringExtra(EXTRA_MIME);
        if (mediaUrl == null || mediaUrl.isEmpty()) {
            finish();
            return;
        }
        if ("video".equals(mediaType)) showVideo(mediaUrl);
        else showImage(mediaUrl);
    }

    private void downloadMedia() {
        String filename = downloadFilename();
        if (mediaUrl.startsWith("content://")) {
            saveContentUri(filename);
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(mediaUrl))
                    .setTitle(filename)
                    .setDescription("正在下载语雀媒体")
                    .setMimeType(mimeType)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            getSystemService(DownloadManager.class).enqueue(request);
            Toast.makeText(this, "已加入下载任务", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "无法开始下载", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveContentUri(String filename) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "此系统版本暂不支持保存本地媒体", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.downloadButton.setEnabled(false);
        downloads.execute(() -> {
            Uri target = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new IllegalStateException("Cannot create download");
                try (InputStream input = getContentResolver().openInputStream(Uri.parse(mediaUrl));
                     OutputStream output = getContentResolver().openOutputStream(target)) {
                    if (input == null || output == null) throw new IllegalStateException("Cannot open media");
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(target, values, null, null);
                runOnUiThread(() -> {
                    binding.downloadButton.setEnabled(true);
                    Toast.makeText(this, "已保存到下载目录", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                if (target != null) getContentResolver().delete(target, null, null);
                runOnUiThread(() -> {
                    binding.downloadButton.setEnabled(true);
                    Toast.makeText(this, "下载失败", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private String downloadFilename() {
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null || extension.isEmpty()) extension = "video".equals(mediaType) ? "mp4" : "jpg";
        return "yuque_" + System.currentTimeMillis() + "." + extension;
    }

    private void showImage(String url) {
        binding.imageView.setVisibility(View.VISIBLE);
        Glide.with(this)
                .load(url)
                .error(android.R.drawable.stat_notify_error)
                .into(binding.imageView);
        binding.progress.setVisibility(View.GONE);
    }

    private void showVideo(String url) {
        binding.videoView.setVisibility(View.VISIBLE);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(binding.videoView);
        binding.videoView.setMediaController(controls);
        binding.videoView.setVideoURI(Uri.parse(url));
        binding.videoView.setOnPreparedListener(player -> {
            binding.progress.setVisibility(View.GONE);
            player.setLooping(false);
            binding.videoView.start();
            controls.show(2500);
        });
        binding.videoView.setOnErrorListener((player, what, extra) -> {
            binding.progress.setVisibility(View.GONE);
            Toast.makeText(this, "视频无法播放", Toast.LENGTH_SHORT).show();
            return true;
        });
        binding.videoView.requestFocus();
    }

    @Override protected void onStop() {
        if (binding != null && binding.videoView.isPlaying()) binding.videoView.pause();
        super.onStop();
    }

    @Override protected void onDestroy() {
        downloads.shutdown();
        super.onDestroy();
    }
}
