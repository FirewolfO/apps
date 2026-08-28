package com.firewolf.xiaolinstudy;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.DownloadListener;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.firewolf.xiaolinstudy.data.PageRecord;
import com.firewolf.xiaolinstudy.data.ProgressStore;
import com.firewolf.xiaolinstudy.data.CompactHtmlRenderer;
import com.firewolf.xiaolinstudy.data.StudyModeStore;
import com.firewolf.xiaolinstudy.data.UrlTools;
import com.firewolf.xiaolinstudy.data.VersionTools;
import com.firewolf.xiaolinstudy.data.CatalogRepository;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogArticle;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogBook;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogGroup;
import com.firewolf.xiaolinstudy.data.CatalogRepository.CatalogSection;
import com.firewolf.xiaolinstudy.data.CatalogNavigator;
import com.firewolf.xiaolinstudy.web.StudyWebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int TAB_HOME = 0;
    private static final int TAB_CATALOG = 1;
    private static final int TAB_RECORDS = 2;
    private static final int INSTALL_PERMISSION_REQUEST = 4103;
    private static final String COMPACT_ORIGIN = "https://compact.xiaolin/";
    private static final long UPDATE_CHECK_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long UPDATE_DOWNLOAD_POLL_MS = 750L;
    static final String UPDATE_PREFERENCES = "xiaolin_app_update";
    static final String UPDATE_DOWNLOAD_ID = "update_download_id";
    static final String UPDATE_READY_ID = "update_ready_id";

    private static final int COLOR_BG = Color.rgb(246, 247, 245);
    private static final int COLOR_SURFACE = Color.WHITE;
    private static final int COLOR_INK = Color.rgb(23, 26, 28);
    private static final int COLOR_MUTED = Color.rgb(102, 112, 120);
    private static final int COLOR_BRAND = Color.rgb(22, 118, 90);
    private static final int COLOR_BRAND_DARK = Color.rgb(13, 86, 63);
    private static final int COLOR_ACCENT = Color.rgb(227, 91, 67);
    private static final int COLOR_DIVIDER = Color.rgb(227, 231, 228);

    private ProgressStore progressStore;
    private StudyModeStore studyModeStore;
    private boolean compactMode;
    private List<CatalogGroup> catalogGroups;
    private CatalogNavigator catalogNavigator;
    private CatalogGroup selectedCatalogGroup;
    private CatalogBook selectedCatalogBook;
    private FrameLayout contentContainer;
    private LinearLayout bottomNavigation;
    private final LinearLayout[] navItems = new LinearLayout[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private int activeTab = TAB_HOME;
    private boolean showingRecentRecords;

    private View readerScreen;
    private StudyWebView webView;
    private ProgressBar webProgress;
    private TextView readerTitle;
    private TextView readerSource;
    private TextView pageStatus;
    private ImageButton previousLessonButton;
    private ImageButton nextLessonButton;
    private ImageButton refreshButton;
    private ImageButton externalButton;
    private Button completionButton;
    private CatalogNavigator.Position currentCatalogPosition;
    private CatalogArticle currentArticle;
    private String currentUrl;
    private String currentTitle;
    private boolean legacyWebFallback;

    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Handler updateHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateDownloadPoll = this::resumeDownloadedUpdate;
    private AppUpdate pendingUpdate;
    private AppUpdate availableUpdate;
    private boolean checkingForUpdate;
    private boolean updateDownloadAuthorized;
    private long lastUpdateCheckAt;
    private String promptedVersion = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        studyModeStore = new StudyModeStore(this);
        compactMode = studyModeStore.isCompactMode();
        loadCatalogForMode();
        createShell();
        configureWindow();
        showNativeTab(TAB_HOME);
        checkForUpdate(false);
    }

    private void loadCatalogForMode() {
        progressStore = new ProgressStore(this, compactMode);
        catalogGroups = CatalogRepository.load(this, compactMode);
        catalogNavigator = new CatalogNavigator(catalogGroups);
    }

    private void createShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        contentContainer = new FrameLayout(this);
        root.addView(contentContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        bottomNavigation = createBottomNavigation();
        root.addView(bottomNavigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        setContentView(root);
    }

    private LinearLayout createBottomNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(4), dp(8), dp(4));
        nav.setBackgroundColor(COLOR_SURFACE);
        nav.setElevation(dp(10));

        addNavItem(nav, TAB_HOME, "首页", R.drawable.ic_home);
        addNavItem(nav, TAB_CATALOG, compactMode ? "重点目录" : "全部内容", R.drawable.ic_library);
        addNavItem(nav, TAB_RECORDS, "学习记录", R.drawable.ic_check);
        return nav;
    }

    private void addNavItem(LinearLayout parent, int index, String label, int iconResource) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setContentDescription(label);
        item.setOnClickListener(view -> showNativeTab(index));

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconResource);
        item.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView text = text(label, 11, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams textParams = wrapParams();
        textParams.topMargin = dp(2);
        item.addView(text, textParams);

        navItems[index] = item;
        navIcons[index] = icon;
        navLabels[index] = text;
        parent.addView(item, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    private void showNativeTab(int tab) {
        saveCurrentReadingPosition();
        activeTab = tab;
        if (activeTab == TAB_CATALOG) {
            selectedCatalogGroup = null;
            selectedCatalogBook = null;
        }
        renderNativeScreen();
    }

    private void renderNativeScreen() {
        bottomNavigation.setVisibility(View.VISIBLE);
        contentContainer.removeAllViews();
        View screen;
        if (activeTab == TAB_CATALOG) {
            if (selectedCatalogBook != null) {
                screen = createCatalogBookScreen(selectedCatalogBook);
            } else if (selectedCatalogGroup != null) {
                screen = createCatalogGroupScreen(selectedCatalogGroup);
            } else {
                screen = createCatalogScreen();
            }
        } else if (activeTab == TAB_RECORDS) {
            screen = createRecordsScreen();
        } else {
            screen = createHomeScreen();
        }
        contentContainer.addView(screen, matchParams());
        updateBottomNavigation();
    }

    private void showCatalogGroup(CatalogGroup group) {
        saveCurrentReadingPosition();
        activeTab = TAB_CATALOG;
        selectedCatalogGroup = group;
        selectedCatalogBook = null;
        renderNativeScreen();
    }

    private void showCatalogBook(CatalogBook book) {
        saveCurrentReadingPosition();
        activeTab = TAB_CATALOG;
        selectedCatalogBook = book;
        renderNativeScreen();
    }

    private void updateBottomNavigation() {
        String catalogLabel = compactMode ? "重点目录" : "全部内容";
        navLabels[TAB_CATALOG].setText(catalogLabel);
        navItems[TAB_CATALOG].setContentDescription(catalogLabel);
        for (int index = 0; index < navItems.length; index++) {
            boolean selected = index == activeTab;
            int color = selected ? COLOR_BRAND : COLOR_MUTED;
            navIcons[index].setColorFilter(color);
            navLabels[index].setTextColor(color);
            navLabels[index].setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            navItems[index].setBackground(selected
                    ? rounded(Color.rgb(234, 245, 240), 8, 0, Color.TRANSPARENT)
                    : null);
        }
    }

    private View createHomeScreen() {
        LinearLayout page = pageWithToolbar("小林学习",
                compactMode ? "精简版 · 77 个面试重点 · 离线速记" : "完整版 · 后端图解 · 面试八股 · AI Agent");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(18), dp(20), dp(28));

        body.addView(text(compactMode ? "快速准备，抓住重点" : "今天学到哪了？",
                25, COLOR_INK, Typeface.BOLD), wrapParams());
        TextView intro = text(compactMode
                        ? "30 秒回答 · 核心要点 · 常见追问 · 易错提醒"
                        : "把零散阅读变成看得见的积累",
                14, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams introParams = wrapParams();
        introParams.topMargin = dp(5);
        body.addView(intro, introParams);

        body.addView(createStudyModeSwitcher(), topMargin(dp(18)));
        body.addView(createStatsPanel(), topMargin(dp(12)));

        String lastUrl = progressStore.getLastUrl();
        if (lastUrl != null) {
            body.addView(sectionTitle("继续学习", null), topMargin(dp(24)));
            body.addView(createContinueCard(lastUrl, progressStore.getLastTitle()), topMargin(dp(10)));
        }

        body.addView(sectionTitle(compactMode ? "重点系列" : "学习系列", "查看全部"), topMargin(dp(24)));
        for (int index = 0; index < catalogGroups.size(); index++) {
            body.addView(createCatalogGroupRow(catalogGroups.get(index), index),
                    topMargin(index == 0 ? dp(10) : dp(8)));
        }

        TextView source = text(compactMode
                        ? "精简摘要离线可读 · 深入学习可跳转小林原文"
                        : "内容来源  xiaolincoding.com · xiaolinnote.com",
                12, COLOR_MUTED, Typeface.NORMAL);
        source.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sourceParams = matchWrapParams();
        sourceParams.topMargin = dp(26);
        body.addView(source, sourceParams);

        TextView version = text("当前版本 " + BuildConfig.VERSION_NAME + " · 检查更新",
                12, COLOR_BRAND, Typeface.BOLD);
        version.setGravity(Gravity.CENTER);
        version.setPadding(dp(12), dp(12), dp(12), dp(12));
        version.setOnClickListener(view -> checkForUpdate(true));
        body.addView(version, topMargin(dp(6)));

        scroll.addView(body, matchWrapParams());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View createStudyModeSwitcher() {
        LinearLayout card = vertical();
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(rounded(COLOR_SURFACE, 10, 1, COLOR_DIVIDER));

        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        choices.addView(studyModeButton("完整版", false),
                new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams compactParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        compactParams.leftMargin = dp(8);
        choices.addView(studyModeButton("精简版", true), compactParams);
        card.addView(choices, matchWrapParams());

        TextView hint = text(compactMode
                        ? "当前为精简版：77 个重点与 28 张关键图示，进度单独保存"
                        : "当前为完整版：307 篇原站目录，保留原有阅读进度",
                12, COLOR_MUTED, Typeface.NORMAL);
        hint.setGravity(Gravity.CENTER);
        card.addView(hint, topMargin(dp(9)));
        return card;
    }

    private Button studyModeButton(String label, boolean targetCompactMode) {
        boolean selected = compactMode == targetCompactMode;
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(selected ? Color.WHITE : COLOR_MUTED);
        button.setBackground(rounded(selected ? COLOR_BRAND : Color.rgb(240, 243, 241),
                9, selected ? 0 : 1, COLOR_DIVIDER));
        button.setOnClickListener(view -> switchStudyMode(targetCompactMode));
        return button;
    }

    private void switchStudyMode(boolean targetCompactMode) {
        if (compactMode == targetCompactMode) return;
        saveCurrentReadingPosition();
        destroyReader();
        compactMode = targetCompactMode;
        studyModeStore.setCompactMode(compactMode);
        loadCatalogForMode();
        selectedCatalogGroup = null;
        selectedCatalogBook = null;
        showingRecentRecords = false;
        activeTab = TAB_HOME;
        renderNativeScreen();
        Toast.makeText(this, compactMode
                ? "已切换精简版，进度与完整版互不影响"
                : "已切换完整版，原有进度已保留", Toast.LENGTH_SHORT).show();
    }

    private void showStudyModeChooser() {
        new AlertDialog.Builder(this)
                .setTitle("切换学习版本")
                .setSingleChoiceItems(new String[]{"完整版 · 307 篇原文目录", "精简版 · 77 个面试重点"},
                        compactMode ? 1 : 0, (dialog, which) -> {
                            dialog.dismiss();
                            switchStudyMode(which == 1);
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private View createStatsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(18), dp(17), dp(18), dp(17));
        panel.setBackground(rounded(COLOR_BRAND_DARK, 8, 0, Color.TRANSPARENT));

        LinearLayout completed = vertical();
        completed.addView(text(String.valueOf(progressStore.completedCount()), 30,
                Color.WHITE, Typeface.BOLD), wrapParams());
        completed.addView(text(compactMode ? "重点已掌握" : "已完成", 12,
                Color.rgb(210, 235, 226), Typeface.NORMAL), wrapParams());
        panel.addView(completed, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(80, 255, 255, 255));
        panel.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(38)));

        LinearLayout visited = vertical();
        visited.setPadding(dp(22), 0, 0, 0);
        visited.addView(text(String.valueOf(progressStore.visitedCount()), 30,
                Color.WHITE, Typeface.BOLD), wrapParams());
        visited.addView(text(compactMode ? "重点已浏览" : "已浏览", 12,
                Color.rgb(210, 235, 226), Typeface.NORMAL), wrapParams());
        panel.addView(visited, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_check);
        icon.setColorFilter(Color.WHITE);
        icon.setBackground(rounded(Color.argb(34, 255, 255, 255), 8, 0, Color.TRANSPARENT));
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        panel.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
        return panel;
    }

    private View createContinueCard(String url, String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(15), dp(12), dp(15));
        card.setBackground(rounded(COLOR_SURFACE, 8, 1, COLOR_DIVIDER));
        card.setOnClickListener(view -> openReader(url));

        View marker = new View(this);
        marker.setBackground(rounded(COLOR_ACCENT, 8, 0, Color.TRANSPARENT));
        card.addView(marker, new LinearLayout.LayoutParams(dp(4), dp(45)));

        LinearLayout copy = vertical();
        copy.setPadding(dp(12), 0, dp(8), 0);
        TextView eyebrow = text("上次读到", 12, COLOR_ACCENT, Typeface.BOLD);
        copy.addView(eyebrow, wrapParams());
        TextView pageTitle = text(title, 16, COLOR_INK, Typeface.BOLD);
        pageTitle.setMaxLines(2);
        pageTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = matchWrapParams();
        titleParams.topMargin = dp(4);
        copy.addView(pageTitle, titleParams);
        card.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_forward);
        arrow.setColorFilter(COLOR_BRAND);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
        return card;
    }

    private View createCatalogScreen() {
        LinearLayout page = pageWithToolbar(compactMode ? "重点目录" : "全部内容",
                catalogGroups.size() + " 个系列 · " + totalCatalogArticles()
                        + (compactMode ? " 个重点" : " 篇"));
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(18), dp(20), dp(30));

        body.addView(sectionTitle("按系列学习", null), wrapParams());
        if (catalogGroups.isEmpty()) {
            TextView error = text("目录加载失败，请重新安装应用", 15, COLOR_MUTED, Typeface.NORMAL);
            body.addView(error, topMargin(dp(18)));
        } else {
            for (int index = 0; index < catalogGroups.size(); index++) {
                body.addView(createCatalogGroupRow(catalogGroups.get(index), index),
                        topMargin(index == 0 ? dp(10) : dp(8)));
            }
        }

        scroll.addView(body, matchWrapParams());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View createCatalogGroupScreen(CatalogGroup group) {
        LinearLayout page = pageWithBackToolbar(group.getTitle(),
                group.getBooks().size() + " 个专题 · " + group.articleCount()
                        + (compactMode ? " 个重点" : " 篇"),
                () -> {
                    selectedCatalogGroup = null;
                    renderNativeScreen();
                });
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(18), dp(20), dp(30));
        body.addView(text(group.getDescription(), 14, COLOR_MUTED, Typeface.NORMAL), matchWrapParams());
        body.addView(sectionTitle("专题目录", null), topMargin(dp(22)));
        for (int index = 0; index < group.getBooks().size(); index++) {
            body.addView(createCatalogBookRow(group.getBooks().get(index), index),
                    topMargin(index == 0 ? dp(10) : dp(8)));
        }
        scroll.addView(body, matchWrapParams());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View createCatalogBookScreen(CatalogBook book) {
        LinearLayout page = pageWithBackToolbar(book.getTitle(),
                book.getSections().size() + " 个章节 · " + book.articleCount()
                        + (compactMode ? " 个重点" : " 篇"),
                () -> {
                    selectedCatalogBook = null;
                    renderNativeScreen();
                });
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(18), dp(20), dp(30));

        int completed = completedCount(book);
        TextView progress = text("学习进度  " + completed + " / " + book.articleCount(),
                14, completed == book.articleCount() ? COLOR_BRAND_DARK : COLOR_MUTED, Typeface.BOLD);
        progress.setPadding(dp(14), dp(12), dp(14), dp(12));
        progress.setBackground(rounded(Color.rgb(234, 245, 240), 8, 0, Color.TRANSPARENT));
        body.addView(progress, matchWrapParams());

        for (int sectionIndex = 0; sectionIndex < book.getSections().size(); sectionIndex++) {
            CatalogSection section = book.getSections().get(sectionIndex);
            body.addView(sectionTitle(section.getTitle(), null),
                    topMargin(sectionIndex == 0 ? dp(24) : dp(28)));
            for (int articleIndex = 0; articleIndex < section.getArticles().size(); articleIndex++) {
                body.addView(createCatalogArticleRow(section.getArticles().get(articleIndex)),
                        topMargin(articleIndex == 0 ? dp(10) : dp(7)));
            }
        }
        scroll.addView(body, matchWrapParams());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private View createCatalogGroupRow(CatalogGroup group, int index) {
        int completed = completedCount(group);
        String progress = group.getBooks().size() + " 个专题 · " + group.articleCount()
                + (compactMode ? " 个重点" : " 篇") + " · 已完成 " + completed;
        return createCatalogNavigationRow(group.getTitle(), group.getDescription(), progress,
                catalogColor(index), () -> showCatalogGroup(group));
    }

    private View createCatalogBookRow(CatalogBook book, int index) {
        int completed = completedCount(book);
        String progress = book.getSections().size() + " 个章节 · " + book.articleCount()
                + (compactMode ? " 个重点" : " 篇") + " · 已完成 " + completed;
        return createCatalogNavigationRow(book.getTitle(), book.getDescription(), progress,
                catalogColor(index), () -> showCatalogBook(book));
    }

    private View createCatalogNavigationRow(String title, String description, String progress,
                                            int color, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(10), dp(12));
        row.setBackground(rounded(COLOR_SURFACE, 8, 1, COLOR_DIVIDER));
        row.setOnClickListener(view -> action.run());

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_library);
        icon.setColorFilter(color);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        icon.setBackground(rounded(withAlpha(color, 22), 8, 0, Color.TRANSPARENT));
        row.addView(icon, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout copy = vertical();
        copy.setPadding(dp(13), 0, dp(8), 0);
        copy.addView(text(title, 16, COLOR_INK, Typeface.BOLD), wrapParams());
        TextView descriptionView = text(description, 13, COLOR_MUTED, Typeface.NORMAL);
        descriptionView.setMaxLines(2);
        descriptionView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams descriptionParams = matchWrapParams();
        descriptionParams.topMargin = dp(3);
        copy.addView(descriptionView, descriptionParams);
        TextView progressView = text(progress, 12, color, Typeface.BOLD);
        LinearLayout.LayoutParams progressParams = matchWrapParams();
        progressParams.topMargin = dp(5);
        copy.addView(progressView, progressParams);
        row.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron);
        arrow.setColorFilter(COLOR_MUTED);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return row;
    }

    private View createCatalogArticleRow(CatalogArticle article) {
        boolean completed = progressStore.isCompleted(article.getUrl());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(10), dp(12));
        row.setBackground(rounded(COLOR_SURFACE, 8, 1,
                completed ? Color.rgb(179, 218, 202) : COLOR_DIVIDER));
        row.setOnClickListener(view -> openReader(article.getUrl()));

        ImageView icon = new ImageView(this);
        icon.setImageResource(completed ? R.drawable.ic_check
                : compactMode ? R.drawable.ic_library : R.drawable.ic_globe);
        int iconColor = completed ? COLOR_BRAND : Color.rgb(54, 101, 166);
        icon.setColorFilter(iconColor);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(rounded(withAlpha(iconColor, 22), 8, 0, Color.TRANSPARENT));
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout copy = vertical();
        copy.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(article.getTitle(), 14, COLOR_INK, Typeface.BOLD);
        title.setMaxLines(3);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(title, matchWrapParams());
        if (compactMode && !article.getSummary().isEmpty()) {
            TextView summary = text(article.getSummary(), 12, COLOR_MUTED, Typeface.NORMAL);
            summary.setMaxLines(2);
            summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams summaryParams = matchWrapParams();
            summaryParams.topMargin = dp(4);
            copy.addView(summary, summaryParams);
        }
        TextView status = text(completed ? "已完成" : "未完成", 12,
                completed ? COLOR_BRAND_DARK : COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams statusParams = wrapParams();
        statusParams.topMargin = dp(4);
        copy.addView(status, statusParams);
        row.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron);
        arrow.setColorFilter(COLOR_MUTED);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return row;
    }

    private View createRecordsScreen() {
        LinearLayout page = pageWithToolbar("学习记录", progressStore.completedCount()
                + (compactMode ? " 个重点已完成" : " 篇已完成"));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(20), dp(14), dp(20), dp(6));
        controls.setBackgroundColor(COLOR_BG);
        controls.addView(recordModeButton("已完成", !showingRecentRecords),
                new LinearLayout.LayoutParams(0, dp(42), 1f));
        LinearLayout.LayoutParams recentParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        recentParams.leftMargin = dp(8);
        controls.addView(recordModeButton("最近浏览", showingRecentRecords), recentParams);
        page.addView(controls, matchWrapParams());

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(10), dp(20), dp(30));
        List<PageRecord> records = showingRecentRecords
                ? progressStore.getRecentPages(50)
                : progressStore.getCompletedPages();
        if (records.isEmpty()) {
            body.addView(createEmptyRecords(), topMargin(dp(64)));
        } else {
            for (int index = 0; index < records.size(); index++) {
                body.addView(createRecordRow(records.get(index), !showingRecentRecords),
                        topMargin(index == 0 ? 0 : dp(8)));
            }
        }
        scroll.addView(body, matchWrapParams());
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private Button recordModeButton(String label, boolean selected) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(selected ? Color.WHITE : COLOR_MUTED);
        button.setBackground(rounded(selected ? COLOR_BRAND : COLOR_SURFACE,
                8, selected ? 0 : 1, COLOR_DIVIDER));
        button.setOnClickListener(view -> {
            showingRecentRecords = label.equals("最近浏览");
            showNativeTab(TAB_RECORDS);
        });
        return button;
    }

    private View createEmptyRecords() {
        LinearLayout empty = vertical();
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_check);
        icon.setColorFilter(COLOR_MUTED);
        icon.setPadding(dp(16), dp(16), dp(16), dp(16));
        icon.setBackground(rounded(Color.rgb(235, 238, 236), 8, 0, Color.TRANSPARENT));
        empty.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = text(showingRecentRecords ? "还没有浏览记录" : "还没有完成的内容",
                17, COLOR_INK, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = wrapParams();
        titleParams.topMargin = dp(16);
        empty.addView(title, titleParams);
        TextView subtitle = text(showingRecentRecords ? "从全部内容开始阅读" : "读完一页后确认完成",
                13, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams subtitleParams = wrapParams();
        subtitleParams.topMargin = dp(5);
        empty.addView(subtitle, subtitleParams);
        return empty;
    }

    private View createRecordRow(PageRecord record, boolean completedRecord) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(12), dp(10), dp(12));
        row.setBackground(rounded(COLOR_SURFACE, 8, 1, COLOR_DIVIDER));
        row.setOnClickListener(view -> openReader(record.getUrl()));

        ImageView icon = new ImageView(this);
        icon.setImageResource(completedRecord ? R.drawable.ic_check
                : compactMode ? R.drawable.ic_library : R.drawable.ic_globe);
        int iconColor = completedRecord ? COLOR_BRAND : Color.rgb(54, 101, 166);
        icon.setColorFilter(iconColor);
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        icon.setBackground(rounded(withAlpha(iconColor, 22), 8, 0, Color.TRANSPARENT));
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout copy = vertical();
        copy.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(record.getTitle(), 15, COLOR_INK, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(title, matchWrapParams());
        long timestamp = completedRecord ? record.getCompletedAt() : record.getVisitedAt();
        String prefix = completedRecord ? "完成于 " : "浏览于 ";
        TextView date = text(prefix + formatTime(timestamp), 12, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams dateParams = wrapParams();
        dateParams.topMargin = dp(4);
        copy.addView(date, dateParams);
        row.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_chevron);
        arrow.setColorFilter(COLOR_MUTED);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(20), dp(20)));
        return row;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureReaderScreen() {
        if (readerScreen != null) return;
        LinearLayout screen = vertical();
        screen.setBackgroundColor(COLOR_SURFACE);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), 0, dp(4), 0);
        toolbar.setBackgroundColor(COLOR_SURFACE);

        ImageButton back = iconButton(R.drawable.ic_arrow_back, "返回");
        back.setOnClickListener(view -> closeReader());
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(52)));

        LinearLayout titleBlock = vertical();
        titleBlock.setGravity(Gravity.CENTER_VERTICAL);
        readerTitle = text("正在打开", 15, COLOR_INK, Typeface.BOLD);
        readerTitle.setSingleLine(true);
        readerTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleBlock.addView(readerTitle, matchWrapParams());
        readerSource = text("xiaolincoding.com", 11, COLOR_MUTED, Typeface.NORMAL);
        readerSource.setSingleLine(true);
        LinearLayout.LayoutParams sourceParams = matchWrapParams();
        sourceParams.topMargin = dp(1);
        titleBlock.addView(readerSource, sourceParams);
        toolbar.addView(titleBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        refreshButton = iconButton(R.drawable.ic_refresh, "刷新");
        refreshButton.setOnClickListener(view -> {
            if (currentArticle != null && currentArticle.isCompact()) loadArticle(currentArticle);
            else webView.reload();
        });
        toolbar.addView(refreshButton, new LinearLayout.LayoutParams(dp(48), dp(52)));
        externalButton = iconButton(R.drawable.ic_open_external, "在浏览器中打开");
        externalButton.setOnClickListener(view -> openExternal(currentArticle != null
                && currentArticle.isCompact() ? currentArticle.getSourceUrl() : currentUrl));
        toolbar.addView(externalButton, new LinearLayout.LayoutParams(dp(48), dp(52)));
        screen.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        webProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        webProgress.setMax(100);
        webProgress.setProgressTintList(ColorStateList.valueOf(COLOR_BRAND));
        webProgress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(231, 235, 232)));
        screen.addView(webProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        webView = new StudyWebView(this);
        configureWebView();
        screen.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout readerFooter = vertical();
        readerFooter.setBackground(rounded(COLOR_SURFACE, 0, 1, COLOR_DIVIDER));

        LinearLayout lessonNavigation = new LinearLayout(this);
        lessonNavigation.setOrientation(LinearLayout.HORIZONTAL);
        lessonNavigation.setGravity(Gravity.CENTER_VERTICAL);
        lessonNavigation.setPadding(dp(14), dp(4), dp(14), 0);

        previousLessonButton = iconButton(R.drawable.ic_arrow_back, "上一节");
        previousLessonButton.setOnClickListener(view -> navigateToAdjacent(false));
        lessonNavigation.addView(previousLessonButton,
                new LinearLayout.LayoutParams(dp(48), dp(48)));

        pageStatus = text("本页学习状态", 13, COLOR_MUTED, Typeface.NORMAL);
        pageStatus.setGravity(Gravity.CENTER);
        pageStatus.setMaxLines(2);
        lessonNavigation.addView(pageStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        nextLessonButton = iconButton(R.drawable.ic_arrow_forward, "下一节");
        nextLessonButton.setOnClickListener(view -> navigateToAdjacent(true));
        lessonNavigation.addView(nextLessonButton,
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        readerFooter.addView(lessonNavigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        completionButton = new Button(this);
        completionButton.setAllCaps(false);
        completionButton.setTextSize(14);
        completionButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        completionButton.setCompoundDrawablePadding(dp(6));
        completionButton.setOnClickListener(view -> toggleCompletion());
        LinearLayout.LayoutParams completionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        completionParams.leftMargin = dp(14);
        completionParams.rightMargin = dp(14);
        readerFooter.addView(completionButton, completionParams);
        screen.addView(readerFooter, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(106)));

        readerScreen = screen;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        webView.setScrollListener(scrollY -> {
            // Position is persisted on navigation and lifecycle boundaries.
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                webProgress.setProgress(progress);
                webProgress.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                String text = message == null ? "" : message.message();
                if (!compactMode && !legacyWebFallback && text.contains("Unexpected token =>")) {
                    legacyWebFallback = true;
                    webView.getSettings().setJavaScriptEnabled(false);
                    webView.post(() -> {
                        Toast.makeText(MainActivity.this, "已切换兼容阅读模式", Toast.LENGTH_SHORT).show();
                        webView.reload();
                    });
                }
                return super.onConsoleMessage(message);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleRequestedUri(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleRequestedUri(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (currentUrl != null && !UrlTools.normalize(currentUrl).equals(UrlTools.normalize(url))) {
                    saveCurrentReadingPosition();
                }
                webProgress.setVisibility(View.VISIBLE);
                readerSource.setText(hostFor(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                handlePageReady(url, view.getTitle(), true);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                view.postDelayed(() -> handlePageReady(view.getUrl(), view.getTitle(), false), 220);
            }
        });
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                openExternal(url));
    }

    private boolean handleRequestedUri(Uri uri) {
        if (uri == null) return true;
        String url = uri.toString();
        if (compactMode && !url.startsWith(COMPACT_ORIGIN)) {
            openExternal(url);
            return true;
        }
        if (UrlTools.isWebUrl(url)) {
            saveCurrentReadingPosition();
            return false;
        }
        openExternal(url);
        return true;
    }

    private void handlePageReady(String url, String title, boolean restorePosition) {
        if (!UrlTools.isWebUrl(url)) return;
        String normalized = UrlTools.normalize(url);
        boolean changed = currentUrl == null || !UrlTools.normalize(currentUrl).equals(normalized);
        currentUrl = url;
        currentArticle = catalogNavigator.find(url) == null
                ? currentArticle : catalogNavigator.find(url).getArticle();
        currentTitle = currentArticle != null && currentArticle.isCompact()
                ? currentArticle.getTitle() : UrlTools.displayTitle(title, url);
        readerTitle.setText(currentTitle);
        readerSource.setText(currentArticle != null && currentArticle.isCompact()
                ? "精简版 · 离线速记" : hostFor(url));
        progressStore.recordVisit(url, currentTitle);
        updateCompletionButton();
        if (restorePosition && changed) {
            int savedY = progressStore.getScrollPosition(url);
            if (savedY > 0) webView.postDelayed(() -> webView.scrollTo(0, savedY), 180);
        }
    }

    private void openReader(String url) {
        ensureReaderScreen();
        bottomNavigation.setVisibility(View.GONE);
        contentContainer.removeAllViews();
        if (readerScreen.getParent() != null) {
            ((ViewGroup) readerScreen.getParent()).removeView(readerScreen);
        }
        contentContainer.addView(readerScreen, matchParams());
        String target = url == null ? UrlTools.HOME_URL : url;
        CatalogNavigator.Position targetPosition = catalogNavigator.find(target);
        CatalogArticle targetArticle = targetPosition == null ? null : targetPosition.getArticle();
        if (targetArticle != null && targetArticle.isCompact()) {
            loadArticle(targetArticle);
            webView.onResume();
            webView.requestFocus();
            return;
        }
        currentArticle = targetArticle;
        if (webView.getUrl() == null || !UrlTools.normalize(webView.getUrl()).equals(UrlTools.normalize(target))) {
            currentUrl = null;
            webView.loadUrl(target);
        } else {
            currentUrl = webView.getUrl();
            currentTitle = UrlTools.displayTitle(webView.getTitle(), currentUrl);
            updateCompletionButton();
        }
        webView.onResume();
        webView.requestFocus();
    }

    private void loadArticle(CatalogArticle article) {
        currentArticle = article;
        currentUrl = article.getUrl();
        currentTitle = article.getTitle();
        readerTitle.setText(currentTitle);
        readerSource.setText("精简版 · 离线速记");
        webProgress.setVisibility(View.VISIBLE);
        webView.loadDataWithBaseURL(article.getUrl(), CompactHtmlRenderer.render(article),
                "text/html", "UTF-8", null);
        int savedY = progressStore.getScrollPosition(article.getUrl());
        if (savedY > 0) webView.postDelayed(() -> webView.scrollTo(0, savedY), 220);
    }

    private void closeReader() {
        saveCurrentReadingPosition();
        if (webView != null) webView.onPause();
        renderNativeScreen();
    }

    private void toggleCompletion() {
        if (!UrlTools.isCompletable(currentUrl)) return;
        boolean completed = progressStore.isCompleted(currentUrl);
        if (completed) {
            progressStore.setCompleted(currentUrl, currentTitle, false);
            updateCompletionButton();
            Toast.makeText(this, "已撤销完成状态", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("确认完成本页学习？")
                .setMessage(currentTitle)
                .setNegativeButton("再看一会", null)
                .setPositiveButton("确认完成", (prompt, which) -> {
                    markCurrentCompleted();
                });
        if (currentCatalogPosition != null && currentCatalogPosition.getNext() != null) {
            dialog.setNeutralButton("完成并下一节", (prompt, which) -> {
                markCurrentCompleted();
                navigateToAdjacent(true);
            });
        }
        dialog.show();
    }

    private void markCurrentCompleted() {
        progressStore.setCompleted(currentUrl, currentTitle, true);
        updateCompletionButton();
        Toast.makeText(this, "已加入学习记录", Toast.LENGTH_SHORT).show();
    }

    private void navigateToAdjacent(boolean forward) {
        CatalogArticle target = currentCatalogPosition == null ? null
                : forward ? currentCatalogPosition.getNext()
                : currentCatalogPosition.getPrevious();
        if (target == null) {
            Toast.makeText(this, forward ? "已经是本专题最后一节" : "已经是本专题第一节",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentReadingPosition();
        readerTitle.setText(target.getTitle());
        readerSource.setText(target.isCompact() ? "精简版 · 离线速记" : hostFor(target.getUrl()));
        completionButton.setEnabled(false);
        completionButton.setAlpha(0.55f);
        configureLessonButton(previousLessonButton, null, "上一节");
        configureLessonButton(nextLessonButton, null, "下一节");
        webProgress.setVisibility(View.VISIBLE);
        if (target.isCompact()) loadArticle(target);
        else {
            currentArticle = target;
            webView.loadUrl(target.getUrl());
        }
    }

    private void updateCompletionButton() {
        if (completionButton == null) return;
        boolean completable = UrlTools.isCompletable(currentUrl);
        boolean completed = completable && progressStore.isCompleted(currentUrl);
        updateLessonNavigation(completed);
        completionButton.setEnabled(completable);
        completionButton.setAlpha(1f);
        completionButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0);
        if (!completable) {
            pageStatus.setText("全站导航");
            completionButton.setText("无需标记");
            completionButton.setTextColor(COLOR_MUTED);
            completionButton.setCompoundDrawableTintList(ColorStateList.valueOf(COLOR_MUTED));
            completionButton.setBackground(rounded(Color.rgb(235, 238, 236), 8, 0, Color.TRANSPARENT));
        } else if (completed) {
            completionButton.setText("已完成 · 撤销");
            completionButton.setTextColor(COLOR_BRAND_DARK);
            completionButton.setCompoundDrawableTintList(ColorStateList.valueOf(COLOR_BRAND_DARK));
            completionButton.setBackground(rounded(Color.rgb(225, 241, 234), 8, 1, Color.rgb(179, 218, 202)));
        } else {
            completionButton.setText("确认学完本页");
            completionButton.setTextColor(Color.WHITE);
            completionButton.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
            completionButton.setBackground(rounded(COLOR_BRAND, 8, 0, Color.TRANSPARENT));
        }
    }

    private void updateLessonNavigation(boolean completed) {
        currentCatalogPosition = catalogNavigator.find(currentUrl);
        CatalogArticle previous = currentCatalogPosition == null
                ? null : currentCatalogPosition.getPrevious();
        CatalogArticle next = currentCatalogPosition == null
                ? null : currentCatalogPosition.getNext();
        configureLessonButton(previousLessonButton, previous, "上一节");
        configureLessonButton(nextLessonButton, next, "下一节");
        if (currentCatalogPosition == null) {
            pageStatus.setText(UrlTools.isCompletable(currentUrl) ? "目录外页面" : "全站导航");
        } else {
            pageStatus.setText(getString(R.string.lesson_status,
                    currentCatalogPosition.getIndex() + 1,
                    currentCatalogPosition.getTotal(),
                    completed ? "本页已完成" : "本页未完成"));
        }
    }

    private void configureLessonButton(ImageButton button, CatalogArticle article, String label) {
        if (button == null) return;
        boolean enabled = article != null;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.28f);
        button.setContentDescription(enabled ? label + "：" + article.getTitle() : label + "不可用");
    }

    private void saveCurrentReadingPosition() {
        if (webView != null && UrlTools.isWebUrl(currentUrl)) {
            progressStore.saveScrollPosition(currentUrl, webView.getScrollY());
        }
    }

    private void checkForUpdate(boolean userInitiated) {
        if (checkingForUpdate) {
            if (userInitiated) Toast.makeText(this, "正在检查新版本", Toast.LENGTH_SHORT).show();
            return;
        }
        checkingForUpdate = true;
        lastUpdateCheckAt = System.currentTimeMillis();
        networkExecutor.execute(() -> {
            AppUpdate update = null;
            try {
                URL endpoint = new URL(BuildConfig.APP_CENTER_URL + "/api/apps/xiaolin/latest");
                HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "XiaolinStudyAndroid/" + BuildConfig.VERSION_NAME);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream body = connection.getInputStream()) {
                        JSONObject release = new JSONObject(readBody(body)).optJSONObject("release");
                        if (release != null) update = AppUpdate.from(release);
                    }
                }
                connection.disconnect();
            } catch (Exception ignored) {
                // Update checks are best-effort and never block local study.
            }
            AppUpdate result = update;
            runOnUiThread(() -> {
                checkingForUpdate = false;
                if (result == null || !result.isValid()) {
                    if (userInitiated) Toast.makeText(this, "暂时无法获取版本信息", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (VersionTools.isNewer(result.version, BuildConfig.VERSION_NAME)) {
                    availableUpdate = result;
                    if (userInitiated || !result.version.equals(promptedVersion)) {
                        promptedVersion = result.version;
                        showUpdatePrompt(result);
                    }
                } else if (userInitiated) {
                    Toast.makeText(this, "当前已是最新版本 " + BuildConfig.VERSION_NAME,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private static String readBody(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private void showUpdatePrompt(AppUpdate update) {
        if (isFinishing() || isDestroyed()) return;
        String size = update.size > 0 ? "\n安装包大小：" + formatSize(update.size) : "";
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + update.version)
                .setMessage("是否下载并覆盖安装新版小林学习？只有点击“同意并下载”后才开始下载。"
                        + "\n完整版和精简版进度均保存在本机，覆盖升级不会清除；请不要先卸载旧版。" + size
                        + (update.notes.isEmpty() ? "" : "\n\n更新内容：\n" + update.notes))
                .setNegativeButton("稍后", null)
                .setPositiveButton("同意并下载", (dialog, which) -> prepareUpdate(update))
                .show();
    }

    private void prepareUpdate(AppUpdate update) {
        pendingUpdate = update;
        updateDownloadAuthorized = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())), INSTALL_PERMISSION_REQUEST);
            return;
        }
        downloadPendingUpdate();
    }

    private void downloadPendingUpdate() {
        if (!updateDownloadAuthorized || pendingUpdate == null) return;
        AppUpdate update = pendingUpdate;
        pendingUpdate = null;
        updateDownloadAuthorized = false;
        String filename = update.filename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".apk")) filename += ".apk";
        String downloadUrl = update.url.startsWith("http://") || update.url.startsWith("https://")
                ? update.url : BuildConfig.APP_CENTER_URL + (update.url.startsWith("/") ? "" : "/") + update.url;

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("小林学习 " + update.version)
                .setDescription("正在下载安装包")
                .setMimeType("application/vnd.android.package-archive")
                .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI
                        | DownloadManager.Request.NETWORK_MOBILE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        File downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloadDir != null) {
            File previous = new File(downloadDir, filename);
            if (previous.exists() && !previous.delete()) {
                Toast.makeText(this, "无法替换旧安装包", Toast.LENGTH_LONG).show();
                return;
            }
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename);
        }
        try {
            long id = ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
            getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE).edit()
                    .putLong(UPDATE_DOWNLOAD_ID, id).remove(UPDATE_READY_ID).apply();
            updateHandler.removeCallbacks(updateDownloadPoll);
            updateHandler.postDelayed(updateDownloadPoll, UPDATE_DOWNLOAD_POLL_MS);
            Toast.makeText(this, "新版本开始下载", Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法开始下载", Toast.LENGTH_LONG).show();
        }
    }

    private boolean resumeDownloadedUpdate() {
        updateHandler.removeCallbacks(updateDownloadPoll);
        long readyID = getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE)
                .getLong(UPDATE_READY_ID, -1);
        if (readyID >= 0) {
            openDownloadedUpdate(readyID);
            return true;
        }
        long downloadID = getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE)
                .getLong(UPDATE_DOWNLOAD_ID, -1);
        if (downloadID < 0) return false;

        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        int status = DownloadManager.STATUS_FAILED;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(downloadID))) {
            if (cursor.moveToFirst()) {
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            }
        } catch (RuntimeException error) {
            updateHandler.postDelayed(updateDownloadPoll, UPDATE_DOWNLOAD_POLL_MS);
            return true;
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL) {
            getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE).edit()
                    .remove(UPDATE_DOWNLOAD_ID).putLong(UPDATE_READY_ID, downloadID).apply();
            openDownloadedUpdate(downloadID);
        } else if (status == DownloadManager.STATUS_FAILED) {
            getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE).edit()
                    .remove(UPDATE_DOWNLOAD_ID).remove(UPDATE_READY_ID).apply();
            Toast.makeText(this, "新版本下载失败，请稍后重试", Toast.LENGTH_LONG).show();
        } else {
            updateHandler.postDelayed(updateDownloadPoll, UPDATE_DOWNLOAD_POLL_MS);
        }
        return true;
    }

    private void openDownloadedUpdate(long downloadID) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        Uri apk = manager.getUriForDownloadedFile(downloadID);
        if (apk == null) {
            getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE).edit().remove(UPDATE_READY_ID).apply();
            Toast.makeText(this, "无法打开安装包", Toast.LENGTH_LONG).show();
            return;
        }
        getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE).edit().remove(UPDATE_READY_ID).apply();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(apk, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (RuntimeException error) {
            getSharedPreferences(UPDATE_PREFERENCES, MODE_PRIVATE).edit()
                    .putLong(UPDATE_READY_ID, downloadID).apply();
            Toast.makeText(this, "无法打开安装包", Toast.LENGTH_LONG).show();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024L) + " KB";
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024d / 1024d);
    }

    private void openExternal(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "没有可打开此链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != INSTALL_PERMISSION_REQUEST) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls()) {
            downloadPendingUpdate();
        } else {
            updateDownloadAuthorized = false;
            pendingUpdate = null;
            Toast.makeText(this, "需要允许小林学习安装应用后才能更新", Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout pageWithToolbar(String title, String subtitle) {
        LinearLayout page = vertical();
        page.setBackgroundColor(COLOR_BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), 0, dp(18), 0);
        bar.setBackgroundColor(COLOR_SURFACE);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.xiaolin_logo);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout copy = vertical();
        copy.setPadding(dp(11), 0, 0, 0);
        copy.addView(text(title, 18, COLOR_INK, Typeface.BOLD), wrapParams());
        TextView sub = text(subtitle, 11, COLOR_MUTED, Typeface.NORMAL);
        LinearLayout.LayoutParams subParams = wrapParams();
        subParams.topMargin = dp(1);
        copy.addView(sub, subParams);
        bar.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(studyModeChip(), wrapParams());

        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        return page;
    }

    private LinearLayout pageWithBackToolbar(String title, String subtitle, Runnable backAction) {
        LinearLayout page = vertical();
        page.setBackgroundColor(COLOR_BG);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(18), 0);
        bar.setBackgroundColor(COLOR_SURFACE);

        ImageButton back = iconButton(R.drawable.ic_arrow_back, "返回上一级");
        back.setOnClickListener(view -> backAction.run());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56)));

        LinearLayout copy = vertical();
        copy.setPadding(dp(5), 0, 0, 0);
        TextView titleView = text(title, 17, COLOR_INK, Typeface.BOLD);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        copy.addView(titleView, matchWrapParams());
        TextView sub = text(subtitle, 11, COLOR_MUTED, Typeface.NORMAL);
        sub.setSingleLine(true);
        LinearLayout.LayoutParams subParams = matchWrapParams();
        subParams.topMargin = dp(1);
        copy.addView(sub, subParams);
        bar.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(studyModeChip(), wrapParams());

        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        return page;
    }

    private View sectionTitle(String title, String action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 18, COLOR_INK, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            TextView actionView = text(action, 13, COLOR_BRAND, Typeface.BOLD);
            actionView.setPadding(dp(8), dp(6), 0, dp(6));
            actionView.setOnClickListener(view -> showNativeTab(TAB_CATALOG));
            row.addView(actionView, wrapParams());
        }
        return row;
    }

    private TextView studyModeChip() {
        TextView chip = text(compactMode ? "精简版" : "完整版", 12,
                compactMode ? Color.WHITE : COLOR_BRAND_DARK, Typeface.BOLD);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(11), dp(7), dp(11), dp(7));
        chip.setBackground(rounded(compactMode ? COLOR_BRAND : Color.rgb(234, 245, 240),
                20, compactMode ? 0 : 1, Color.rgb(179, 218, 202)));
        chip.setContentDescription("当前为" + (compactMode ? "精简版" : "完整版") + "，点击切换");
        chip.setOnClickListener(view -> showStudyModeChooser());
        return chip;
    }

    private ImageButton iconButton(int iconResource, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResource);
        button.setColorFilter(COLOR_INK);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(description);
        return button;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private String hostFor(String url) {
        if (url == null) return "xiaolincoding.com";
        String host = Uri.parse(url).getHost();
        if (host == null) return "网页内容";
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private String formatTime(long timestamp) {
        if (timestamp <= 0) return "刚刚";
        return new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(timestamp));
    }

    private int totalCatalogArticles() {
        int total = 0;
        for (CatalogGroup group : catalogGroups) total += group.articleCount();
        return total;
    }

    private int completedCount(CatalogGroup group) {
        int completed = 0;
        for (CatalogBook book : group.getBooks()) completed += completedCount(book);
        return completed;
    }

    private int completedCount(CatalogBook book) {
        int completed = 0;
        for (CatalogSection section : book.getSections()) {
            for (CatalogArticle article : section.getArticles()) {
                if (progressStore.isCompleted(article.getUrl())) completed++;
            }
        }
        return completed;
    }

    private int catalogColor(int index) {
        int[] colors = {
                COLOR_BRAND,
                Color.rgb(54, 101, 166),
                COLOR_ACCENT,
                Color.rgb(151, 80, 150),
                Color.rgb(181, 126, 28),
                COLOR_BRAND_DARK
        };
        return colors[Math.abs(index) % colors.length];
    }

    private FrameLayout.LayoutParams matchParams() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int margin) {
        LinearLayout.LayoutParams params = matchWrapParams();
        params.topMargin = margin;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(COLOR_BG);
        window.setNavigationBarColor(COLOR_SURFACE);
        if (Build.VERSION.SDK_INT >= 30) {
            Api30WindowAppearance.apply(window);
        } else if (Build.VERSION.SDK_INT >= 26) {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    @TargetApi(30)
    private static final class Api30WindowAppearance {
        private Api30WindowAppearance() {}

        static void apply(Window window) {
            View decorView = window.getDecorView();
            WindowInsetsController controller = decorView.getWindowInsetsController();
            if (controller == null) return;
            int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(lightBars, lightBars);
        }
    }

    @Override
    public void onBackPressed() {
        if (readerScreen != null && readerScreen.getParent() == contentContainer) {
            if (webView.canGoBack()) {
                saveCurrentReadingPosition();
                webView.goBack();
            } else {
                closeReader();
            }
            return;
        }
        if (activeTab == TAB_CATALOG && selectedCatalogBook != null) {
            selectedCatalogBook = null;
            renderNativeScreen();
            return;
        }
        if (activeTab == TAB_CATALOG && selectedCatalogGroup != null) {
            selectedCatalogGroup = null;
            renderNativeScreen();
            return;
        }
        if (activeTab != TAB_HOME) {
            showNativeTab(TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumeDownloadedUpdate()) return;
        if (System.currentTimeMillis() - lastUpdateCheckAt >= UPDATE_CHECK_INTERVAL_MS) {
            checkForUpdate(false);
        }
    }

    @Override
    protected void onPause() {
        saveCurrentReadingPosition();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        updateHandler.removeCallbacks(updateDownloadPoll);
        networkExecutor.shutdownNow();
        destroyReader();
        super.onDestroy();
    }

    private void destroyReader() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        webView = null;
        readerScreen = null;
        webProgress = null;
        readerTitle = null;
        readerSource = null;
        pageStatus = null;
        previousLessonButton = null;
        nextLessonButton = null;
        refreshButton = null;
        externalButton = null;
        completionButton = null;
        currentCatalogPosition = null;
        currentArticle = null;
        currentUrl = null;
        currentTitle = null;
    }

    private static final class AppUpdate {
        final String version;
        final String filename;
        final String url;
        final String notes;
        final long size;

        AppUpdate(String version, String filename, String url, String notes, long size) {
            this.version = version;
            this.filename = filename;
            this.url = url;
            this.notes = notes;
            this.size = size;
        }

        static AppUpdate from(JSONObject release) {
            return new AppUpdate(release.optString("version", "").trim(),
                    release.optString("filename", "xiaolin-update.apk").trim(),
                    release.optString("downloadUrl", "").trim(),
                    release.optString("notes", "").trim(), release.optLong("size", 0));
        }

        boolean isValid() {
            return !version.isEmpty() && !filename.isEmpty() && !url.isEmpty()
                    && url.length() <= 2048 && filename.length() <= 180;
        }
    }

}
