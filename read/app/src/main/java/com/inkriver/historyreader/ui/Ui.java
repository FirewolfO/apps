package com.inkriver.historyreader.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

public final class Ui {
    public static final int INK = Color.rgb(33, 31, 27);
    public static final int MUTED = Color.rgb(105, 100, 91);
    public static final int PAPER = Color.rgb(245, 241, 232);
    public static final int SURFACE = Color.rgb(252, 250, 245);
    public static final int LINE = Color.rgb(220, 214, 202);
    public static final int CINNABAR = Color.rgb(163, 59, 43);
    public static final int SAGE = Color.rgb(86, 114, 103);

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context context, String value, float size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setLetterSpacing(0f);
        view.setIncludeFontPadding(false);
        return view;
    }

    public static TextView command(Context context, String label) {
        TextView view = text(context, label, 14, INK);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 44));
        view.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        view.setBackground(roundRect(SURFACE, LINE, 6, context));
        view.setClickable(true);
        view.setFocusable(true);
        view.setForeground(context.getDrawable(android.R.drawable.list_selector_background));
        return view;
    }

    public static void setSelectedCommand(TextView view, boolean selected) {
        view.setSelected(selected);
        view.setTextColor(selected ? Color.WHITE : INK);
        view.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        view.setBackground(roundRect(selected ? CINNABAR : SURFACE,
                selected ? CINNABAR : LINE, 6, view.getContext()));
    }

    public static GradientDrawable roundRect(int fill, int stroke, float radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    public static ColorStateList tint(int color) {
        return ColorStateList.valueOf(color);
    }

    public static void divider(View view, int color) {
        view.setBackgroundColor(color);
    }
}
