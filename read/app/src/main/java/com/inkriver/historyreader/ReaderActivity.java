package com.inkriver.historyreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.inkriver.historyreader.data.Book;
import com.inkriver.historyreader.data.CatalogRepository;
import com.inkriver.historyreader.data.Chapter;
import com.inkriver.historyreader.data.Excerpt;
import com.inkriver.historyreader.data.ReaderStore;
import com.inkriver.historyreader.data.ReadingProgress;
import com.inkriver.historyreader.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ReaderActivity extends Activity {
    public static final String EXTRA_BOOK_ID = "book_id";
    public static final String EXTRA_CHAPTER = "chapter";
    private static final int ACTION_EXCERPT = 7001;

    private ReaderStore store;
    private CatalogRepository catalog;
    private SharedPreferences preferences;
    private Book book;
    private List<Chapter> chapters = new ArrayList<>();
    private int chapterIndex;
    private int pendingScrollY;
    private int pendingScrollPosition;
    private boolean restoringPosition;

    private LinearLayout root;
    private LinearLayout topBar;
    private LinearLayout editionBar;
    private LinearLayout bottomBar;
    private ScrollView scroll;
    private TextView bookTitle;
    private TextView chapterTitle;
    private TextView body;
    private TextView pageStatus;
    private TextView originalButton;
    private TextView vernacularButton;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean speechActive;
    private final ArrayList<String> speechChunks = new ArrayList<>();
    private int speechChunkIndex;
    private int findOffset;
    private final Runnable persistScroll = this::savePosition;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        store = new ReaderStore(this);
        catalog = new CatalogRepository(this, store);
        preferences = getSharedPreferences("reader_settings", MODE_PRIVATE);
        String id = getIntent().getStringExtra(EXTRA_BOOK_ID);
        book = catalog.find(id);
        if (book == null) {
            finish();
            return;
        }
        chapters = catalog.chapters(book);
        ReadingProgress progress = store.progress(book.id);
        if (!progress.hasStarted()) progress = store.progressFor(book);
        chapterIndex = getIntent().hasExtra(EXTRA_CHAPTER)
                ? getIntent().getIntExtra(EXTRA_CHAPTER, 0) : progress.chapter;
        chapterIndex = clamp(chapterIndex, 0, Math.max(0, chapters.size() - 1));
        pendingScrollY = getIntent().hasExtra(EXTRA_CHAPTER) ? 0 : progress.scrollY;
        pendingScrollPosition = getIntent().hasExtra(EXTRA_CHAPTER)
                ? 0 : progress.scrollPosition;
        buildUi();
        displayChapter(false);
        initSpeech();
    }

    @Override
    protected void onPause() {
        savePosition();
        speechActive = false;
        if (tts != null) tts.stop();
        super.onPause();
    }

    @Override
    protected void onStop() {
        savePosition();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (scroll != null) scroll.removeCallbacks(persistScroll);
        if (tts != null) tts.shutdown();
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

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
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

        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 6), Ui.dp(this, 5), Ui.dp(this, 6), Ui.dp(this, 5));
        TextView back = iconCommand("返回", android.R.drawable.ic_media_previous);
        back.setOnClickListener(view -> finish());
        topBar.addView(back, iconParams());

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER);
        bookTitle = Ui.text(this, book.displayTitle(), 14, Ui.INK);
        bookTitle.setTypeface(null, Typeface.BOLD);
        bookTitle.setGravity(Gravity.CENTER);
        bookTitle.setSingleLine(true);
        bookTitle.setEllipsize(TextUtils.TruncateAt.END);
        chapterTitle = Ui.text(this, "", 11, Ui.MUTED);
        chapterTitle.setGravity(Gravity.CENTER);
        chapterTitle.setSingleLine(true);
        chapterTitle.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(bookTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        titles.addView(chapterTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        topBar.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        TextView search = iconCommand("页内查找", android.R.drawable.ic_menu_search);
        search.setOnClickListener(view -> showFind());
        topBar.addView(search, iconParams());
        TextView speech = iconCommand("朗读", android.R.drawable.ic_btn_speak_now);
        speech.setOnClickListener(view -> toggleSpeech());
        topBar.addView(speech, iconParams());
        TextView settings = iconCommand("阅读设置", android.R.drawable.ic_menu_manage);
        settings.setOnClickListener(view -> showReaderSettings());
        topBar.addView(settings, iconParams());
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)));

        editionBar = new LinearLayout(this);
        editionBar.setGravity(Gravity.CENTER);
        editionBar.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 8));
        originalButton = Ui.command(this, "原文");
        vernacularButton = Ui.command(this, "白话");
        originalButton.setOnClickListener(view -> switchEdition(Book.Edition.ORIGINAL));
        vernacularButton.setOnClickListener(view -> switchEdition(Book.Edition.VERNACULAR));
        editionBar.addView(originalButton, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1));
        LinearLayout.LayoutParams vernacularParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1);
        vernacularParams.leftMargin = Ui.dp(this, 6);
        editionBar.addView(vernacularButton, vernacularParams);
        if (book.edition == Book.Edition.IMPORTED) editionBar.setVisibility(View.GONE);
        root.addView(editionBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                book.edition == Book.Edition.IMPORTED ? 0 : Ui.dp(this, 50)));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = Ui.text(this, "", preferences.getInt("font_size", 20), Ui.INK);
        body.setGravity(Gravity.TOP);
        body.setTextIsSelectable(true);
        body.setLineSpacing(Ui.dp(this, 9), 1.15f);
        int horizontal = getResources().getConfiguration().screenWidthDp >= 600
                ? Ui.dp(this, 80) : Ui.dp(this, 24);
        body.setPadding(horizontal, Ui.dp(this, 28), horizontal, Ui.dp(this, 50));
        body.setCustomSelectionActionModeCallback(selectionActions());
        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            updatePageStatus();
            if (!restoringPosition) schedulePositionSave();
        });
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        TextView previous = Ui.command(this, "上一卷");
        previous.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_rew, 0, 0, 0);
        previous.setCompoundDrawablePadding(Ui.dp(this, 6));
        previous.setOnClickListener(view -> changeChapter(-1));
        pageStatus = Ui.text(this, "", 13, Ui.MUTED);
        pageStatus.setGravity(Gravity.CENTER);
        pageStatus.setContentDescription("打开目录");
        pageStatus.setTooltipText("目录");
        pageStatus.setOnClickListener(view -> showTableOfContents());
        TextView bookmark = iconCommand("添加书签", android.R.drawable.btn_star_big_off);
        bookmark.setOnClickListener(view -> bookmarkCurrent());
        TextView next = Ui.command(this, "下一卷");
        next.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_media_ff, 0);
        next.setCompoundDrawablePadding(Ui.dp(this, 6));
        next.setOnClickListener(view -> changeChapter(1));
        bottomBar.addView(previous, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)));
        bottomBar.addView(pageStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
        bottomBar.addView(bookmark, iconParams());
        bottomBar.addView(next, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44)));
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        setContentView(root);
        applyReaderTheme();
        if (preferences.getBoolean("keep_screen_on", false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private TextView iconCommand(String description, int drawable) {
        TextView view = Ui.text(this, "", 1, Ui.INK);
        view.setGravity(Gravity.CENTER);
        view.setCompoundDrawablesWithIntrinsicBounds(0, drawable, 0, 0);
        view.setContentDescription(description);
        view.setTooltipText(description);
        view.setClickable(true);
        view.setFocusable(true);
        view.setBackgroundResource(android.R.drawable.list_selector_background);
        return view;
    }

    private LinearLayout.LayoutParams iconParams() {
        return new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
    }

    private void displayChapter(boolean resetScroll) {
        if (chapters.isEmpty()) return;
        restoringPosition = true;
        chapterIndex = clamp(chapterIndex, 0, chapters.size() - 1);
        Chapter chapter = chapters.get(chapterIndex);
        bookTitle.setText(book.displayTitle());
        chapterTitle.setText(chapter.title);
        body.setText(catalog.textFor(book, chapter), TextView.BufferType.SPANNABLE);
        applyHighlights();
        body.setTextSize(preferences.getInt("font_size", 20));
        Ui.setSelectedCommand(originalButton, book.edition == Book.Edition.ORIGINAL);
        Ui.setSelectedCommand(vernacularButton, book.edition == Book.Edition.VERNACULAR);
        findOffset = 0;
        int targetY = resetScroll ? 0 : pendingScrollY;
        int targetPosition = resetScroll ? 0 : pendingScrollPosition;
        pendingScrollY = 0;
        pendingScrollPosition = 0;
        scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (scroll.getViewTreeObserver().isAlive()) {
                    scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                int range = scrollRange();
                int target = targetPosition > 0
                        ? Math.round(range * targetPosition
                        / (float) ReadingProgress.POSITION_SCALE)
                        : targetY;
                scroll.scrollTo(0, target);
                restoringPosition = false;
                updatePageStatus();
                schedulePositionSave();
                return true;
            }
        });
    }

    private void switchEdition(Book.Edition target) {
        if (book.edition == target || book.edition == Book.Edition.IMPORTED) return;
        int position = currentScrollPosition();
        savePosition();
        String id = book.historyKey + (target == Book.Edition.ORIGINAL ? "-original" : "-vernacular");
        Book paired = catalog.find(id);
        if (paired == null) return;
        ReadingProgress pairedProgress = store.progress(paired.id);
        book = paired;
        chapters = catalog.chapters(book);
        chapterIndex = clamp(chapterIndex, 0, Math.max(0, chapters.size() - 1));
        boolean restorePaired = pairedProgress.hasStarted()
                && pairedProgress.chapter == chapterIndex;
        pendingScrollY = restorePaired ? pairedProgress.scrollY : 0;
        pendingScrollPosition = restorePaired
                ? pairedProgress.scrollPosition : position;
        displayChapter(false);
    }

    private void changeChapter(int delta) {
        int next = chapterIndex + delta;
        if (next < 0 || next >= chapters.size()) {
            Toast.makeText(this, delta < 0 ? "已经是第一卷" : "已经是最后一卷", Toast.LENGTH_SHORT).show();
            return;
        }
        savePosition();
        chapterIndex = next;
        pendingScrollY = 0;
        pendingScrollPosition = 0;
        displayChapter(true);
    }

    private void savePosition() {
        if (book == null || scroll == null || restoringPosition) return;
        scroll.removeCallbacks(persistScroll);
        int scrollY = scroll.getScrollY();
        int position = currentScrollPosition();
        store.saveProgress(book.progressKey(), book.id, chapterIndex, scrollY, position);
        if (!book.progressKey().equals(book.id)) {
            store.saveProgress(book.id, book.id, chapterIndex, scrollY, position);
        }
    }

    private void schedulePositionSave() {
        if (scroll == null || restoringPosition) return;
        scroll.removeCallbacks(persistScroll);
        scroll.postDelayed(persistScroll, 700);
    }

    private int currentScrollPosition() {
        int range = scrollRange();
        if (range <= 0) return 0;
        return clamp(Math.round(scroll.getScrollY()
                * ReadingProgress.POSITION_SCALE / (float) range),
                0, ReadingProgress.POSITION_SCALE);
    }

    private int scrollRange() {
        if (scroll == null || scroll.getChildCount() == 0) return 0;
        return Math.max(0, scroll.getChildAt(0).getHeight() - scroll.getHeight());
    }

    private void updatePageStatus() {
        if (pageStatus == null || scroll == null) return;
        int range = Math.max(1, scrollRange());
        int percent = Math.min(100, Math.round(scroll.getScrollY() * 100f / range));
        pageStatus.setText(getString(R.string.page_status,
                chapterIndex + 1, chapters.size(), percent));
    }

    private void showTableOfContents() {
        String[] titles = new String[chapters.size()];
        for (int i = 0; i < chapters.size(); i++) {
            titles[i] = chapters.get(i).title;
        }
        ArrayAdapter<String> titleAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_single_choice, titles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, convertView, parent);
                item.setSingleLine(false);
                item.setMaxLines(3);
                item.setEllipsize(TextUtils.TruncateAt.END);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setMinHeight(Ui.dp(ReaderActivity.this, 56));
                item.setLineSpacing(Ui.dp(ReaderActivity.this, 2), 1f);
                return item;
            }
        };
        String status;
        if (book.complete && book.edition == Book.Edition.VERNACULAR) {
            status = "全卷机器辅助初译 · 未经人工通校 · " + book.volumeCount + " 卷";
        } else if (book.complete) {
            status = "全本原文已校验 · " + book.volumeCount + " 卷";
        } else {
            status = "语料未通过完整性校验";
        }
        new AlertDialog.Builder(this)
                .setTitle(book.displayTitle() + "\n" + status)
                .setSingleChoiceItems(titleAdapter, chapterIndex, (dialog, which) -> {
                    savePosition();
                    chapterIndex = which;
                    pendingScrollY = 0;
                    pendingScrollPosition = 0;
                    displayChapter(true);
                    dialog.dismiss();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private ActionMode.Callback selectionActions() {
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                menu.add(Menu.NONE, ACTION_EXCERPT, 0, "摘记")
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                return true;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() != ACTION_EXCERPT) return false;
                int start = Math.max(0, Math.min(body.getSelectionStart(), body.getSelectionEnd()));
                int end = Math.max(body.getSelectionStart(), body.getSelectionEnd());
                if (end <= start) return true;
                String excerpt = body.getText().subSequence(start, end).toString().trim();
                if (!excerpt.isEmpty()) promptForNote(excerpt, start, end);
                mode.finish();
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) {}
        };
    }

    private void promptForNote(String excerpt, int start, int end) {
        EditText note = new EditText(this);
        note.setHint("批注（可留空）");
        note.setMinLines(3);
        FrameLayout frame = new FrameLayout(this);
        int padding = Ui.dp(this, 20);
        frame.setPadding(padding, 0, padding, 0);
        frame.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("保存摘记")
                .setMessage(excerpt)
                .setView(frame)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    Chapter chapter = chapters.get(chapterIndex);
                    store.addExcerpt(book.id, book.displayTitle(), chapterIndex,
                            chapter.title, excerpt, note.getText().toString(), start, end);
                    applyHighlights();
                    Toast.makeText(this, "已保存到摘记", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void bookmarkCurrent() {
        int offset = currentTextOffset();
        String text = body.getText().toString();
        int start = Math.max(0, offset - 28);
        int end = Math.min(text.length(), offset + 52);
        String excerpt = text.substring(start, end).replace('\n', ' ').trim();
        Chapter chapter = chapters.get(chapterIndex);
        store.addExcerpt(book.id, book.displayTitle(), chapterIndex, chapter.title,
                excerpt.isEmpty() ? chapter.title : excerpt, "", start, end);
        applyHighlights();
        Toast.makeText(this, "书签已添加", Toast.LENGTH_SHORT).show();
    }

    private void applyHighlights() {
        if (!(body.getText() instanceof Spannable)) return;
        Spannable text = (Spannable) body.getText();
        BackgroundColorSpan[] old = text.getSpans(0, text.length(), BackgroundColorSpan.class);
        for (BackgroundColorSpan span : old) text.removeSpan(span);
        for (Excerpt excerpt : store.excerptsFor(book.id, chapterIndex)) {
            if (excerpt.startOffset < 0 || excerpt.endOffset <= excerpt.startOffset) continue;
            int start = clamp(excerpt.startOffset, 0, text.length());
            int end = clamp(excerpt.endOffset, start, text.length());
            if (end > start) {
                text.setSpan(new BackgroundColorSpan(0x66D7B56D),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private int currentTextOffset() {
        Layout layout = body.getLayout();
        if (layout == null) return 0;
        int localY = Math.max(0, scroll.getScrollY() - body.getTop() + body.getPaddingTop());
        int line = layout.getLineForVertical(localY);
        return clamp(layout.getLineStart(line), 0, body.length());
    }

    private void showFind() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("输入正文关键词");
        FrameLayout frame = new FrameLayout(this);
        int padding = Ui.dp(this, 20);
        frame.setPadding(padding, 0, padding, 0);
        frame.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("页内查找")
                .setView(frame)
                .setNegativeButton("取消", null)
                .setPositiveButton("查找", (dialog, which) -> findNext(input.getText().toString()))
                .show();
    }

    private void findNext(String query) {
        if (query.trim().isEmpty()) return;
        String text = body.getText().toString();
        int found = text.indexOf(query, findOffset);
        if (found < 0 && findOffset > 0) found = text.indexOf(query);
        if (found < 0) {
            Toast.makeText(this, "本卷未找到“" + query + "”", Toast.LENGTH_SHORT).show();
            return;
        }
        findOffset = found + query.length();
        body.requestFocus();
        Selection.setSelection((Spannable) body.getText(), found, found + query.length());
        Layout layout = body.getLayout();
        if (layout != null) {
            int line = layout.getLineForOffset(found);
            scroll.smoothScrollTo(0, Math.max(0, layout.getLineTop(line) - Ui.dp(this, 80)));
        }
    }

    private void initSpeech() {
        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) {}

                    @Override
                    public void onDone(String utteranceId) {
                        runOnUiThread(() -> {
                            if (!speechActive) return;
                            speechChunkIndex++;
                            speakNextChunk();
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        runOnUiThread(() -> {
                            speechActive = false;
                            Toast.makeText(ReaderActivity.this,
                                    "系统朗读中断", Toast.LENGTH_SHORT).show();
                        });
                    }
                });
            }
        });
    }

    private void toggleSpeech() {
        if (!ttsReady) {
            Toast.makeText(this, "系统朗读引擎尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        if (speechActive || tts.isSpeaking()) {
            speechActive = false;
            tts.stop();
            Toast.makeText(this, "朗读已暂停", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = body.getText().toString();
        int offset = currentTextOffset();
        String remaining = text.substring(Math.min(offset, text.length()));
        speechChunks.clear();
        speechChunks.addAll(splitForSpeech(remaining));
        speechChunkIndex = 0;
        speechActive = !speechChunks.isEmpty();
        speakNextChunk();
        Toast.makeText(this, "从当前位置开始朗读", Toast.LENGTH_SHORT).show();
    }

    private List<String> splitForSpeech(String text) {
        ArrayList<String> result = new ArrayList<>();
        int maximum = Math.min(3500, TextToSpeech.getMaxSpeechInputLength() - 200);
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(text.length(), start + maximum);
            int end = hardEnd;
            if (hardEnd < text.length()) {
                int searchStart = Math.max(start + maximum / 2, start);
                for (int cursor = hardEnd - 1; cursor >= searchStart; cursor--) {
                    char value = text.charAt(cursor);
                    if (value == '。' || value == '！' || value == '？'
                            || value == '；' || value == '\n') {
                        end = cursor + 1;
                        break;
                    }
                }
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) result.add(chunk);
            start = end;
        }
        return result;
    }

    private void speakNextChunk() {
        if (!speechActive) return;
        if (speechChunkIndex >= speechChunks.size()) {
            speechActive = false;
            Toast.makeText(this, "本卷朗读完成", Toast.LENGTH_SHORT).show();
            return;
        }
        Bundle options = new Bundle();
        options.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        tts.speak(speechChunks.get(speechChunkIndex), TextToSpeech.QUEUE_FLUSH, options,
                book.id + "-" + chapterIndex + "-" + speechChunkIndex);
    }

    private void showReaderSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = Ui.dp(this, 22);
        panel.setPadding(padding, Ui.dp(this, 6), padding, 0);

        TextView sizeValue = Ui.text(this,
                "正文字号  " + preferences.getInt("font_size", 20) + " sp", 15, Ui.INK);
        panel.addView(sizeValue);
        SeekBar size = new SeekBar(this);
        size.setMax(16);
        size.setProgress(preferences.getInt("font_size", 20) - 16);
        size.setProgressTintList(Ui.tint(Ui.CINNABAR));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                int actual = value + 16;
                preferences.edit().putInt("font_size", actual).apply();
                body.setTextSize(actual);
                sizeValue.setText(getString(R.string.reader_font_size_value, actual));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        panel.addView(size);

        LinearLayout themes = new LinearLayout(this);
        String[] names = {"宣纸", "护眼", "夜间"};
        String[] values = {"paper", "sage", "night"};
        String selected = preferences.getString("reader_theme", "paper");
        for (int i = 0; i < names.length; i++) {
            TextView theme = Ui.command(this, names[i]);
            Ui.setSelectedCommand(theme, values[i].equals(selected));
            int index = i;
            theme.setOnClickListener(view -> {
                preferences.edit().putString("reader_theme", values[index]).apply();
                applyReaderTheme();
                for (int child = 0; child < themes.getChildCount(); child++) {
                    Ui.setSelectedCommand((TextView) themes.getChildAt(child), themes.getChildAt(child) == view);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
            if (i > 0) params.leftMargin = Ui.dp(this, 6);
            themes.addView(theme, params);
        }
        panel.addView(themes);

        new AlertDialog.Builder(this)
                .setTitle("阅读设置")
                .setView(panel)
                .setPositiveButton("完成", null)
                .show();
    }

    private void applyReaderTheme() {
        String theme = preferences.getString("reader_theme", "paper");
        int background;
        int foreground;
        int muted;
        boolean light;
        if ("night".equals(theme)) {
            background = Color.rgb(24, 24, 22);
            foreground = Color.rgb(216, 212, 202);
            muted = Color.rgb(151, 147, 139);
            light = false;
        } else if ("sage".equals(theme)) {
            background = Color.rgb(226, 235, 222);
            foreground = Color.rgb(31, 39, 33);
            muted = Color.rgb(88, 102, 91);
            light = true;
        } else {
            background = Ui.PAPER;
            foreground = Ui.INK;
            muted = Ui.MUTED;
            light = true;
        }
        root.setBackgroundColor(background);
        topBar.setBackgroundColor(background);
        editionBar.setBackgroundColor(background);
        bottomBar.setBackgroundColor(background);
        body.setTextColor(foreground);
        bookTitle.setTextColor(foreground);
        chapterTitle.setTextColor(muted);
        pageStatus.setTextColor(muted);
        tintIcons(topBar, foreground);
        tintIcons(bottomBar, foreground);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().setSystemBarsAppearance(
                    light ? WindowInsetsControllerFlags.LIGHT_BARS : 0,
                    WindowInsetsControllerFlags.LIGHT_BARS);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(light
                    ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    : View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void tintIcons(View view, int color) {
        if (view instanceof TextView) {
            for (Drawable drawable : ((TextView) view).getCompoundDrawables()) {
                if (drawable != null) drawable.setTint(color);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                tintIcons(group.getChildAt(index), color);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @SuppressLint("InlinedApi")
    private static final class WindowInsetsControllerFlags {
        static final int LIGHT_BARS =
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
    }
}
