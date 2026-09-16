package com.firewolf.friendsspeaking;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class LearningStore {
    private static final String PREFERENCES = "friends_learning";
    private final SharedPreferences preferences;

    public LearningStore(Context context) {
        Context application = context.getApplicationContext();
        preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public Uri audio(Episode episode) {
        Uri custom = customAudio(episode);
        return custom != null ? custom : RemoteMediaCatalog.audio(episode);
    }

    public Uri subtitle(Episode episode) {
        Uri custom = customSubtitle(episode);
        return custom != null ? custom : RemoteMediaCatalog.subtitle(episode);
    }

    public Uri customAudio(Episode episode) {
        return uri("audio_" + episode.key);
    }

    public Uri customSubtitle(Episode episode) {
        return uri("subtitle_" + episode.key);
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

    public boolean isRemoteAudio(Episode episode) {
        return customAudio(episode) == null && RemoteMediaCatalog.hasAudio(episode);
    }

    public boolean isRemoteSubtitle(Episode episode) {
        return customSubtitle(episode) == null && RemoteMediaCatalog.hasSubtitle(episode);
    }

    public int readyCount(int season) {
        int count = 0;
        for (Episode episode : Episode.season(season)) if (ready(episode)) count++;
        return count;
    }

    public int readyCount() {
        int count = 0;
        for (Episode episode : Episode.all()) if (ready(episode)) count++;
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

    public boolean completed(Episode episode) {
        if (preferences.getBoolean("completed_" + episode.key, false)) return true;
        long duration = duration(episode);
        return duration > 0 && progress(episode) >= Math.max(0L, duration - 30_000L);
    }

    public void saveCompleted(Episode episode, boolean value) {
        preferences.edit().putBoolean("completed_" + episode.key, value).apply();
    }

    public long subtitleOffset(Episode episode) {
        return preferences.getLong("subtitle_offset_" + episode.key, 0L);
    }

    public void saveSubtitleOffset(Episode episode, long value) {
        preferences.edit().putLong("subtitle_offset_" + episode.key,
                Math.max(-120_000L, Math.min(120_000L, value))).apply();
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

}
