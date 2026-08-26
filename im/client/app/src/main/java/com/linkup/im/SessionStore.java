package com.linkup.im;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

final class SessionStore {
    private static final String FILE = "session";
    private final SharedPreferences preferences;

    SessionStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String token() {
        return preferences.getString("token", "");
    }

    Models.User user() {
        try {
            String raw = preferences.getString("user", "");
            return raw.isEmpty() ? null : Models.User.from(new JSONObject(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    void save(String token, Models.User user) {
        preferences.edit().putString("token", token).putString("user", user.toJson().toString()).apply();
    }

    String serverUrl() {
        return preferences.getString("server_url", "");
    }

    void saveServerUrl(String serverUrl) {
        preferences.edit().putString("server_url", serverUrl).apply();
    }

    void saveUpdateDownload(long downloadId) {
        preferences.edit().putLong("update_download_id", downloadId).apply();
    }

    long updateDownloadId() {
        return preferences.getLong("update_download_id", -1);
    }

    void clearUpdateDownload() {
        preferences.edit().remove("update_download_id").apply();
    }

    void clear() {
        preferences.edit().remove("token").remove("user").apply();
    }
}
