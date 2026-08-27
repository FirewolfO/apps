package com.inkriver.historyreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import com.inkriver.historyreader.data.Book;
import com.inkriver.historyreader.data.CatalogRepository;
import com.inkriver.historyreader.data.Excerpt;
import com.inkriver.historyreader.data.ReaderStore;
import com.inkriver.historyreader.data.ReadingProgress;
import com.inkriver.historyreader.ui.BookGridAdapter;
import com.inkriver.historyreader.ui.Ui;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT = 200;
    private static final int REQUEST_EXPORT = 201;

    private ReaderStore store;
    private CatalogRepository catalog;
    private SharedPreferences preferences;
    private LinearLayout root;
    private FrameLayout content;
    private LinearLayout navigation;
    private final ArrayList<TextView> navItems = new ArrayList<>();
    private int selectedNavigation = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        store = new ReaderStore(this);
        catalog = new CatalogRepository(this, store);
        preferences = getSharedPreferences("reader_settings", MODE_PRIVATE);
        buildShell();
        showLibrary();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (selectedNavigation == 0) showLibrary();
    }

    @Override
    protected void onDestroy() {
        store.close();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.PAPER);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));
        navigation.setBackgroundColor(Ui.SURFACE);
        addNav("书架", android.R.drawable.ic_menu_agenda, this::showLibrary);
        addNav("摘记", android.R.drawable.ic_menu_edit, this::showExcerpts);
        addNav("设置", android.R.drawable.ic_menu_preferences, this::showSettings);
        root.addView(navigation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 60)));
        setContentView(root);
    }

    private void addNav(String label, int icon, Runnable action) {
        TextView item = Ui.text(this, label, 12, Ui.MUTED);
        item.setGravity(Gravity.CENTER);
        item.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        item.setCompoundDrawablePadding(Ui.dp(this, 2));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> action.run());
        navigation.addView(item, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        navItems.add(item);
    }

    private void selectNav(int index) {
        selectedNavigation = index;
        for (int i = 0; i < navItems.size(); i++) {
            TextView item = navItems.get(i);
            boolean selected = i == index;
            item.setTextColor(selected ? Ui.CINNABAR : Ui.MUTED);
            item.setSelected(selected);
            item.getCompoundDrawables()[1].setTint(selected ? Ui.CINNABAR : Ui.MUTED);
        }
    }

    private void showLibrary() {
        selectNav(0);
        content.removeAllViews();

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 8));
        TextView title = Ui.text(this, "廿四史", 28, Ui.INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1));
        TextView importButton = Ui.command(this, "导入");
        importButton.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_input_add, 0, 0, 0);
        importButton.setCompoundDrawablePadding(Ui.dp(this, 6));
        importButton.setOnClickListener(view -> chooseTextFile());
        titleRow.addView(importButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 42)));
        page.addView(titleRow);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索书名、作者、朝代");
        search.setTextSize(15);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(Ui.MUTED);
        search.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        search.setCompoundDrawablePadding(Ui.dp(this, 8));
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        search.setBackground(Ui.roundRect(Ui.SURFACE, Ui.LINE, 6, this));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        searchParams.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 12));
        page.addView(search, searchParams);

        Book recent = mostRecentBook();
        if (recent != null) {
            ReadingProgress progress = store.progressFor(recent);
            TextView resume = Ui.text(this,
                    "继续阅读  " + recent.displayTitle() + "  ·  第 "
                            + (progress.chapter + 1) + " 卷 · 全书 "
                            + progress.overallPercent(recent.volumeCount) + "%",
                    14, Ui.INK);
            resume.setSingleLine(true);
            resume.setEllipsize(android.text.TextUtils.TruncateAt.END);
            resume.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_play, 0, 0, 0);
            resume.setCompoundDrawablePadding(Ui.dp(this, 8));
            resume.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
            resume.setBackground(Ui.roundRect(0xFFF0E7D6, 0xFFE0D2B9, 6, this));
            resume.setOnClickListener(view -> openBook(recent, -1));
            LinearLayout.LayoutParams resumeParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
            resumeParams.setMargins(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 12));
            page.addView(resume, resumeParams);
        }

        BookGridAdapter adapter = new BookGridAdapter(this, store);
        adapter.setBooks(catalog.allBooks());

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 8));
        String[] labels = {"全部", "原文", "白话", "收藏"};
        Set<String> favorites = favoriteIds();
        for (String label : labels) {
            TextView chip = Ui.command(this, label);
            Ui.setSelectedCommand(chip, "全部".equals(label));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 38));
            params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            chips.addView(chip, params);
            chip.setOnClickListener(view -> {
                for (int i = 0; i < chips.getChildCount(); i++) {
                    Ui.setSelectedCommand((TextView) chips.getChildAt(i), chips.getChildAt(i) == view);
                }
                adapter.setFilter(label, favoriteIds());
            });
        }
        chipScroll.addView(chips);
        page.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));

        FrameLayout gridFrame = new FrameLayout(this);
        GridView grid = new GridView(this);
        int widthDp = getResources().getConfiguration().screenWidthDp;
        grid.setNumColumns(widthDp >= 840 ? 4 : widthDp >= 600 ? 3 : 2);
        grid.setHorizontalSpacing(Ui.dp(this, 8));
        grid.setVerticalSpacing(Ui.dp(this, 3));
        grid.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 22));
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> openBook(adapter.itemAt(position), -1));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            toggleFavorite(adapter.itemAt(position));
            adapter.setFilter(currentChip(chips), favoriteIds());
            return true;
        });
        TextView empty = Ui.text(this, "没有找到书籍", 15, Ui.MUTED);
        empty.setGravity(Gravity.CENTER);
        gridFrame.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gridFrame.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        grid.setEmptyView(empty);
        page.addView(gridFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.setQuery(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        content.addView(page);
    }

    private String currentChip(LinearLayout chips) {
        for (int i = 0; i < chips.getChildCount(); i++) {
            if (chips.getChildAt(i).isSelected()) return ((TextView) chips.getChildAt(i)).getText().toString();
        }
        return "全部";
    }

    private Book mostRecentBook() {
        Book recent = null;
        long newest = 0;
        for (Book book : catalog.allBooks()) {
            ReadingProgress progress = store.progressFor(book);
            if (!progress.bookId.isEmpty() && !progress.bookId.equals(book.id)) continue;
            if (progress.updatedAt > newest) {
                newest = progress.updatedAt;
                recent = book;
            }
        }
        return recent;
    }

    private void openBook(Book book, int chapter) {
        Intent intent = new Intent(this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id);
        if (chapter >= 0) intent.putExtra(ReaderActivity.EXTRA_CHAPTER, chapter);
        startActivity(intent);
    }

    private void showExcerpts() {
        selectNav(1);
        content.removeAllViews();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(pageTitle("摘记", "全部摘录与批注"));

        List<Excerpt> items = store.excerpts();
        if (items.isEmpty()) {
            TextView empty = Ui.text(this, "还没有摘录", 15, Ui.MUTED);
            empty.setGravity(Gravity.CENTER);
            page.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            ScrollView scroll = new ScrollView(this);
            LinearLayout list = new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 24));
            for (Excerpt excerpt : items) list.addView(excerptView(excerpt));
            scroll.addView(list);
            page.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        content.addView(page);
    }

    private View excerptView(Excerpt excerpt) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        card.setBackground(Ui.roundRect(Ui.SURFACE, Ui.LINE, 6, this));
        TextView source = Ui.text(this, excerpt.bookTitle + "  ·  " + excerpt.chapterTitle, 13, Ui.CINNABAR);
        source.setOnClickListener(view -> {
            Book sourceBook = catalog.find(excerpt.bookId);
            if (sourceBook != null) openBook(sourceBook, excerpt.chapter);
        });
        TextView quote = Ui.text(this, excerpt.text, 16, Ui.INK);
        quote.setLineSpacing(Ui.dp(this, 5), 1f);
        TextView note = Ui.text(this,
                excerpt.note.isEmpty() ? "添加笔记" : excerpt.note, 14,
                excerpt.note.isEmpty() ? Ui.MUTED : Ui.SAGE);
        note.setPadding(0, Ui.dp(this, 9), 0, 0);
        card.addView(source);
        card.addView(quote, topMargin(Ui.dp(this, 10)));
        card.addView(note);
        card.setOnClickListener(view -> editNote(excerpt));
        card.setOnLongClickListener(view -> {
            new AlertDialog.Builder(this)
                    .setTitle("删除这条摘记？")
                    .setMessage(excerpt.text)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> {
                        store.deleteExcerpt(excerpt.id);
                        showExcerpts();
                    }).show();
            return true;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 10);
        card.setLayoutParams(params);
        return card;
    }

    private void editNote(Excerpt excerpt) {
        EditText input = new EditText(this);
        input.setText(excerpt.note);
        input.setHint("写下批注");
        input.setMinLines(3);
        int padding = Ui.dp(this, 20);
        FrameLayout frame = new FrameLayout(this);
        frame.setPadding(padding, 0, padding, 0);
        frame.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(excerpt.bookTitle + " · " + excerpt.chapterTitle)
                .setView(frame)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    store.updateNote(excerpt.id, input.getText().toString());
                    showExcerpts();
                }).show();
    }

    private void showSettings() {
        selectNav(2);
        content.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), Ui.dp(this, 28));
        page.addView(pageTitle("设置", "离线阅读"));

        page.addView(sectionLabel("阅读"));
        TextView sizeValue = Ui.text(this,
                preferences.getInt("font_size", 20) + " sp", 14, Ui.MUTED);
        LinearLayout sizeRow = settingRow("正文字号", sizeValue);
        page.addView(sizeRow);
        SeekBar fontSize = new SeekBar(this);
        fontSize.setMax(16);
        fontSize.setProgress(preferences.getInt("font_size", 20) - 16);
        fontSize.setProgressTintList(Ui.tint(Ui.CINNABAR));
        fontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int size = value + 16;
                sizeValue.setText(getString(R.string.font_size_value, size));
                preferences.edit().putInt("font_size", size).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        page.addView(fontSize);

        CheckBox keepScreen = new CheckBox(this);
        keepScreen.setText("阅读时保持屏幕常亮");
        keepScreen.setTextSize(15);
        keepScreen.setTextColor(Ui.INK);
        keepScreen.setChecked(preferences.getBoolean("keep_screen_on", false));
        keepScreen.setButtonTintList(Ui.tint(Ui.CINNABAR));
        keepScreen.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean("keep_screen_on", checked).apply());
        page.addView(keepScreen, topMargin(Ui.dp(this, 4)));

        page.addView(sectionLabel("数据"));
        TextView export = settingCommand("导出阅读数据", "进度、摘录与笔记（JSON）");
        export.setOnClickListener(view -> chooseExportFile());
        page.addView(export);
        TextView appSettings = settingCommand("系统应用设置", "通知、存储与备份权限");
        appSettings.setOnClickListener(view -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        page.addView(appSettings);

        page.addView(sectionLabel("内容"));
        TextView corpus = Ui.text(this,
                "内置书目：24 部正史、48 个原文/白话版本入口。\n"
                        + "当前语料包：原文 3213 卷全本；白话 3213 卷机器辅助初译。白话稿优先采用逐句可靠对照，未匹配内容由文言专用模型翻译，并对专名、日期和数量等风险段二次复核。译稿未经逐句人工通校，仍可能存在误译，请与原文对照阅读。\n\n"
                        + "阅读进度、书签和笔记均只保存在本机；应用不上传正文或个人数据。",
                14, Ui.MUTED);
        corpus.setLineSpacing(Ui.dp(this, 5), 1f);
        corpus.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        page.addView(corpus);

        scroll.addView(page);
        content.addView(scroll);
    }

    private View pageTitle(String titleText, String subtitleText) {
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));
        if (title.getParent() == null) {
            title.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 18));
        }
        TextView heading = Ui.text(this, titleText, 27, Ui.INK);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView subtitle = Ui.text(this, subtitleText, 13, Ui.MUTED);
        title.addView(heading);
        title.addView(subtitle, topMargin(Ui.dp(this, 4)));
        return title;
    }

    private TextView sectionLabel(String value) {
        TextView label = Ui.text(this, value, 13, Ui.CINNABAR);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 8));
        return label;
    }

    private LinearLayout settingRow(String label, View detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(this, label, 16, Ui.INK);
        row.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1));
        row.addView(detail);
        return row;
    }

    private TextView settingCommand(String label, String detail) {
        TextView view = Ui.text(this, label + "\n" + detail, 16, Ui.INK);
        view.setLineSpacing(Ui.dp(this, 2), 1f);
        view.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_media_next, 0);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        view.setBackground(Ui.roundRect(Ui.SURFACE, Ui.LINE, 6, this));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 66));
        params.bottomMargin = Ui.dp(this, 8);
        view.setLayoutParams(params);
        return view;
    }

    private LinearLayout.LayoutParams topMargin(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        return params;
    }

    private void toggleFavorite(Book book) {
        Set<String> favorites = favoriteIds();
        boolean adding;
        if (favorites.contains(book.id)) {
            favorites.remove(book.id);
            adding = false;
        } else {
            favorites.add(book.id);
            adding = true;
        }
        preferences.edit().putStringSet("favorites", favorites).apply();
        Toast.makeText(this, adding ? "已加入收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
    }

    private Set<String> favoriteIds() {
        return new HashSet<>(preferences.getStringSet("favorites", new HashSet<>()));
    }

    private void chooseTextFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        startActivityForResult(intent, REQUEST_IMPORT);
    }

    private void chooseExportFile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,
                "廿四史阅读数据-" + new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date()) + ".json");
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_IMPORT) importText(data.getData());
        if (requestCode == REQUEST_EXPORT) exportData(data.getData());
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            importText(intent.getData());
        }
    }

    private void importText(Uri uri) {
        try {
            String displayName = queryDisplayName(uri);
            String title = displayName.replaceFirst("(?i)\\.txt$", "");
            String id = "import-" + Long.toHexString(System.currentTimeMillis());
            File directory = new File(getFilesDir(), "imports");
            if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("无法创建导入目录");
            File target = new File(directory, id + ".txt");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new IllegalStateException("无法打开文件");
                byte[] buffer = new byte[16 * 1024];
                int count;
                long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 20L * 1024 * 1024) throw new IllegalArgumentException("文件超过 20 MB");
                    output.write(buffer, 0, count);
                }
            }
            store.addImport(id, title, target.getAbsolutePath());
            catalog.invalidate();
            showLibrary();
            Toast.makeText(this, "已导入《" + title + "》", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            new AlertDialog.Builder(this)
                    .setTitle("导入失败")
                    .setMessage(error.getMessage())
                    .setPositiveButton("知道了", null)
                    .show();
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        }
        String last = uri.getLastPathSegment();
        return last == null ? "导入书籍" : last;
    }

    private void exportData(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri)) {
            if (output == null) throw new IllegalStateException("无法创建文件");
            output.write(store.exportData().toString(2).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "阅读数据已导出", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "导出失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
