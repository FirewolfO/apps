package com.firewolf.players;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class AppUpdateChecker {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private AppUpdateChecker() {}

    static void check(Activity activity, boolean force) {
        long last = activity.getSharedPreferences("players_settings", Activity.MODE_PRIVATE)
                .getLong("last_update_check", 0L);
        if (!force && System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return;
        activity.getSharedPreferences("players_settings", Activity.MODE_PRIVATE)
                .edit().putLong("last_update_check", System.currentTimeMillis()).apply();
        EXECUTOR.execute(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(
                        BuildConfig.APP_CENTER_URL + "/api/apps/players/latest").openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(10_000);
                connection.setRequestProperty("Accept", "application/json");
                try {
                    if (connection.getResponseCode() != 200) return;
                    String raw;
                    try (InputStream input = connection.getInputStream()) {
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[4096];
                        int count;
                        while ((count = input.read(buffer)) >= 0 && output.size() < 128 * 1024) output.write(buffer, 0, count);
                        raw = output.toString(StandardCharsets.UTF_8.name());
                    }
                    JSONObject release = new JSONObject(raw).getJSONObject("release");
                    if (release.optInt("versionCode") <= BuildConfig.VERSION_CODE) return;
                    String version = release.optString("version");
                    String notes = release.optString("notes", "包含功能和资源更新");
                    activity.runOnUiThread(() -> show(activity, version, notes));
                } finally {
                    connection.disconnect();
                }
            } catch (Exception ignored) {
                // Update checks must never interrupt browsing or playback.
            }
        });
    }

    private static void show(Activity activity, String version, String notes) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity)
                .setTitle("发现新版 " + version)
                .setMessage(notes)
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往更新", (dialog, which) -> activity.startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.APP_CENTER_URL + "/players"))))
                .show();
    }
}
