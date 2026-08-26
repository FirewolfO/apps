package com.linkup.im;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Ui {
    private Ui() {}

    static void edgeToEdge(Activity activity, View root) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
        activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(view.getPaddingLeft(), system.top, view.getPaddingRight(), system.bottom);
            return insets;
        });
    }

    static String initials(String name) {
        if (name == null || name.isEmpty()) return "?";
        int end = name.offsetByCodePoints(0, 1);
        return name.substring(0, end).toUpperCase(Locale.getDefault());
    }

    static String shortTime(long time) {
        if (time <= 0) return "";
        Date now = new Date();
        Date value = new Date(time);
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String pattern = day.format(now).equals(day.format(value)) ? "HH:mm" : "M月d日";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(value);
    }
}
