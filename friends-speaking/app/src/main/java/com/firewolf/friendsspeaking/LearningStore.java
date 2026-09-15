package com.firewolf.friendsspeaking;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class LearningStore {
    private static final String PREFERENCES = "friends_learning";
    private final Context context;
    private final SharedPreferences preferences;

    public LearningStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public Uri audio(Episode episode) {
        Uri custom = uri("audio_" + episode.key);
        if (custom != null) return custom;
        return isBundledDemo(episode) ? resource(R.raw.demo_s01e01) : null;
    }

    public Uri subtitle(Episode episode) {
        Uri custom = uri("subtitle_" + episode.key);
        if (custom != null) return custom;
        return isBundledDemo(episode) ? resource(R.raw.demo_s01e01_subtitle) : null;
    }

    public void saveAudio(Episode episode, Uri uri) {
        saveUri("audio_" + episode.key, uri);
    }

    public void saveSubtitle(Episode episode, Uri uri) {
        saveUri("subtitle_" + episode.key, uri);
    }

    public boolean ready(Episode episode) {
        return audio(episode) != null && subtitle(episode) != null;
    }

    public boolean isBundledDemo(Episode episode) {
        return episode.season == 1 && episode.number == 1
                && uri("audio_" + episode.key) == null
                && uri("subtitle_" + episode.key) == null;
    }

    public int readyCount(int season) {
        int count = 0;
        for (Episode episode : Episode.season(season)) if (ready(episode)) count++;
        return count;
    }

    public long progress(Episode episode) {
        return preferences.getLong("position_" + episode.key, 0L);
    }

    public long duration(Episode episode) {
        return preferences.getLong("duration_" + episode.key, 0L);
    }

    public void saveProgress(Episode episode, long position, long duration) {
        if (position < 0) position = 0;
        if (duration < 0) duration = 0;
        preferences.edit()
                .putLong("position_" + episode.key, position)
                .putLong("duration_" + episode.key, duration)
                .putString("last_episode", episode.key)
                .apply();
    }

    public Episode lastEpisode() {
        String key = preferences.getString("last_episode", "");
        if (key == null || !key.matches("S\\d{2}E\\d{2}")) return null;
        return Episode.of(Integer.parseInt(key.substring(1, 3)), Integer.parseInt(key.substring(4, 6)));
    }

    public float speed() {
        return Math.max(0.5f, Math.min(2f, preferences.getFloat("playback_speed", 1f)));
    }

    public void saveSpeed(float value) {
        preferences.edit().putFloat("playback_speed", value).apply();
    }

    private Uri uri(String key) {
        String value = preferences.getString(key, "");
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    private void saveUri(String key, Uri value) {
        if (value == null) preferences.edit().remove(key).apply();
        else preferences.edit().putString(key, value.toString()).apply();
    }

    private Uri resource(int id) {
        return Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName() + "/" + id);
    }
}
