package com.firewolf.friendsspeaking;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdateChecker {
    static final String PREFERENCES = "friends_updates";
    static final String DOWNLOAD_ID = "update_download_id";
    private static final String PENDING_URL = "pending_update_url";
    private static final String PENDING_FILENAME = "pending_update_filename";
    private static final String PENDING_VERSION = "pending_update_version";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private AppUpdateChecker() {}

    static void check(Activity activity, boolean force) {
        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
        String pending = preferences.getString(PENDING_URL, "");
        if (!force && (preferences.getLong(DOWNLOAD_ID, -1L) >= 0 || (pending != null && !pending.isEmpty()))) return;
        long last = preferences.getLong("last_update_check", 0L);
        if (!force && System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        preferences.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        BuildConfig.APP_CENTER_URL + "/api/apps/friends-speaking/latest").openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", browserUserAgent());
                try {
                    if (connection.getResponseCode() != 200) throw new IllegalStateException("HTTP " + connection.getResponseCode());
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    try (InputStream input = connection.getInputStream()) {
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) >= 0 && output.size() < 128 * 1024) output.write(buffer, 0, count);
                    }
                    JSONObject release = new JSONObject(output.toString(StandardCharsets.UTF_8.name())).getJSONObject("release");
                    if (release.optInt("versionCode") <= BuildConfig.VERSION_CODE) {
                        if (force) activity.runOnUiThread(() -> toast(activity, "当前已是最新版本"));
                        return;
                    }
                    Update update = Update.from(release);
                    if (!update.isValid()) throw new IllegalStateException("invalid release");
                    activity.runOnUiThread(() -> show(activity, update));
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                if (force) activity.runOnUiThread(() -> toast(activity, "暂时无法检查更新，请稍后重试"));
            }
        });
    }

    static void resumePending(Activity activity) {
        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
        String path = preferences.getString(PENDING_URL, "");
        if (path == null || path.isEmpty()) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) return;
        String url = absoluteDownloadUrl(path);
        if (url.isEmpty()) {
            clearPending(preferences);
            return;
        }
        String filename = safeFilename(preferences.getString(PENDING_FILENAME, "friends-speaking-update.apk"));
        String version = preferences.getString(PENDING_VERSION, "新版");
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setTitle("老友记口语伴侣 " + version)
                .setDescription("正在下载安装包")
                .setMimeType("application/vnd.android.package-archive")
                .addRequestHeader("User-Agent", browserUserAgent())
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE)
                .setAllowedOverMetered(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory != null) {
            File previous = new File(directory, filename);
            if (previous.exists() && !previous.delete()) {
                toast(activity, "无法替换旧安装包");
                return;
            }
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, filename);
        }
        try {
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            long id = manager.enqueue(request);
            preferences.edit().putLong(DOWNLOAD_ID, id)
                    .remove(PENDING_URL).remove(PENDING_FILENAME).remove(PENDING_VERSION).apply();
            toast(activity, "新版本开始下载，完成后会打开安装界面");
        } catch (RuntimeException error) {
            clearPending(preferences);
            toast(activity, "无法开始下载，请稍后重试");
        }
    }

    private static void show(Activity activity, Update update) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        String size = update.size > 0 ? "\n\n安装包大小：" + formatSize(update.size) : "";
        new AlertDialog.Builder(activity)
                .setTitle("发现新版 " + update.version)
                .setMessage(update.notes + size)
                .setNegativeButton("稍后", null)
                .setPositiveButton("立即更新", (dialog, which) -> prepare(activity, update))
                .show();
    }

    private static void prepare(Activity activity, Update update) {
        SharedPreferences preferences = activity.getSharedPreferences(PREFERENCES, Activity.MODE_PRIVATE);
        preferences.edit().putString(PENDING_URL, update.downloadUrl)
                .putString(PENDING_FILENAME, update.filename).putString(PENDING_VERSION, update.version).apply();
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            toast(activity, "请允许本应用安装更新，返回后会开始下载");
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        resumePending(activity);
    }

    private static String absoluteDownloadUrl(String path) {
        String value = path == null ? "" : path.trim();
        if (!value.startsWith("/downloads/friends-speaking/") || value.contains("..")) return "";
        return BuildConfig.APP_CENTER_URL + value;
    }

    private static String browserUserAgent() {
        return "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36 FriendsSpeaking/"
                + BuildConfig.VERSION_NAME;
    }

    private static String safeFilename(String value) {
        String filename = value == null ? "friends-speaking-update.apk" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".apk")) filename += ".apk";
        return filename;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024L) + " KB";
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024d / 1024d);
    }

    private static void clearPending(SharedPreferences preferences) {
        preferences.edit().remove(PENDING_URL).remove(PENDING_FILENAME).remove(PENDING_VERSION).apply();
    }

    private static void toast(Activity activity, String message) {
        if (!activity.isFinishing() && !activity.isDestroyed()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private static final class Update {
        final String version;
        final String filename;
        final String notes;
        final String downloadUrl;
        final long size;

        Update(String version, String filename, String notes, String downloadUrl, long size) {
            this.version = version;
            this.filename = filename;
            this.notes = notes;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }

        static Update from(JSONObject value) {
            return new Update(value.optString("version"), value.optString("filename"),
                    value.optString("notes", "包含学习体验改进"), value.optString("downloadUrl"), value.optLong("size"));
        }

        boolean isValid() {
            return !version.trim().isEmpty() && !filename.trim().isEmpty() && !absoluteDownloadUrl(downloadUrl).isEmpty();
        }
    }
}
