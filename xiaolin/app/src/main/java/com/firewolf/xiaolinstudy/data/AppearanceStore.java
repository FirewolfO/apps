package com.firewolf.xiaolinstudy.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

public final class AppearanceStore {
    public static final int MODE_SYSTEM = 0;
    public static final int MODE_LIGHT = 1;
    public static final int MODE_DARK = 2;

    private static final String PREFERENCES = "xiaolin_appearance_settings";
    private static final String APPEARANCE_MODE = "appearance_mode";

    private final SharedPreferences preferences;

    public AppearanceStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public int getMode() {
        return normalize(preferences.getInt(APPEARANCE_MODE, MODE_SYSTEM));
    }

    public void setMode(int mode) {
        preferences.edit().putInt(APPEARANCE_MODE, normalize(mode)).apply();
    }

    public boolean isDarkMode(Resources resources) {
        int mode = getMode();
        if (mode == MODE_DARK) return true;
        if (mode == MODE_LIGHT) return false;
        int nightMode = resources.getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return nightMode == Configuration.UI_MODE_NIGHT_YES;
    }

    public String currentLabel() {
        return label(getMode());
    }

    static int normalize(int mode) {
        return mode == MODE_LIGHT || mode == MODE_DARK ? mode : MODE_SYSTEM;
    }

    static String label(int mode) {
        if (mode == MODE_LIGHT) return "浅色模式";
        if (mode == MODE_DARK) return "深色模式";
        return "跟随系统";
    }

    static String preferencesName() {
        return PREFERENCES;
    }
}
