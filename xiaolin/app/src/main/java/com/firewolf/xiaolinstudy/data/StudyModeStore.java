package com.firewolf.xiaolinstudy.data;

import android.content.Context;
import android.content.SharedPreferences;

public final class StudyModeStore {
    private static final String PREFERENCES = "xiaolin_study_settings";
    private static final String COMPACT_MODE = "compact_mode";

    private final SharedPreferences preferences;

    public StudyModeStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public boolean isCompactMode() {
        return preferences.getBoolean(COMPACT_MODE, false);
    }

    public void setCompactMode(boolean compactMode) {
        preferences.edit().putBoolean(COMPACT_MODE, compactMode).apply();
    }
}
