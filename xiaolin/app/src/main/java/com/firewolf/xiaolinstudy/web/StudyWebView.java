package com.firewolf.xiaolinstudy.web;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

public final class StudyWebView extends WebView {
    public interface ScrollListener {
        void onScrollPositionChanged(int scrollY);
    }

    private ScrollListener scrollListener;

    public StudyWebView(Context context) {
        super(context);
        configure();
    }

    public StudyWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        configure();
    }

    public void setScrollListener(ScrollListener listener) {
        scrollListener = listener;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure() {
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(settings.getUserAgentString() + " XiaolinStudy/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true);
        setVerticalScrollBarEnabled(true);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
    }

    @Override
    protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        if (scrollListener != null && Math.abs(top - oldTop) > 32) {
            scrollListener.onScrollPositionChanged(top);
        }
    }
}
