package com.firewolf.players;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class PlaybackStore {
    private static final String NAME = "players_library";
    private static final String FAVORITES = "favorites";
    private final SharedPreferences preferences;

    public PlaybackStore(Context context) {
        preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public boolean isFavorite(String id) {
        return preferences.getStringSet(FAVORITES, Collections.emptySet()).contains(id);
    }

    public boolean toggleFavorite(String id) {
        Set<String> values = new HashSet<>(preferences.getStringSet(FAVORITES, Collections.emptySet()));
        boolean selected;
        if (values.contains(id)) {
            values.remove(id);
            selected = false;
        } else {
            values.add(id);
            selected = true;
        }
        preferences.edit().putStringSet(FAVORITES, values).apply();
        return selected;
    }

    public long progress(String id) {
        return preferences.getLong("position_" + id, 0L);
    }

    public void saveProgress(String id, long positionMs, long durationMs) {
        if (id == null || id.isEmpty()) return;
        long value = durationMs > 0 && positionMs >= durationMs - 10_000 ? 0 : Math.max(0, positionMs);
        preferences.edit().putLong("position_" + id, value).apply();
    }
}
