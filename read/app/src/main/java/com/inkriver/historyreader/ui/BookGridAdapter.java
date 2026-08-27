package com.inkriver.historyreader.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.inkriver.historyreader.data.Book;
import com.inkriver.historyreader.data.ReaderStore;
import com.inkriver.historyreader.data.ReadingProgress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class BookGridAdapter extends BaseAdapter {
    private final Context context;
    private final ReaderStore store;
    private final ArrayList<Book> visible = new ArrayList<>();
    private List<Book> all = Collections.emptyList();
    private String query = "";
    private String filter = "全部";
    private Set<String> favorites = Collections.emptySet();

    public BookGridAdapter(Context context, ReaderStore store) {
        this.context = context;
        this.store = store;
    }

    public void setBooks(List<Book> books) {
        all = books;
        applyFilter();
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query;
        applyFilter();
    }

    public void setFilter(String filter, Set<String> favorites) {
        this.filter = filter;
        this.favorites = favorites;
        applyFilter();
    }

    public Book itemAt(int position) {
        return visible.get(position);
    }

    @Override
    public int getCount() {
        return visible.size();
    }

    @Override
    public Object getItem(int position) {
        return visible.get(position);
    }

    @Override
    public long getItemId(int position) {
        return visible.get(position).id.hashCode();
    }

    @Override
    public View getView(int position, View recycled, ViewGroup parent) {
        Holder holder;
        if (recycled == null) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(Ui.dp(context, 7), Ui.dp(context, 5), Ui.dp(context, 7), Ui.dp(context, 13));

            BookCoverView cover = new BookCoverView(context);
            root.addView(cover, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView title = Ui.text(context, "", 16, Ui.INK);
            title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setGravity(Gravity.CENTER);
            title.setMaxLines(1);
            root.addView(title, marginTop(Ui.dp(context, 10)));

            TextView detail = Ui.text(context, "", 12, Ui.MUTED);
            detail.setGravity(Gravity.CENTER);
            detail.setMaxLines(1);
            root.addView(detail, marginTop(Ui.dp(context, 5)));

            holder = new Holder(cover, title, detail);
            root.setTag(holder);
            recycled = root;
        } else {
            holder = (Holder) recycled.getTag();
        }
        Book book = visible.get(position);
        holder.cover.setBook(book);
        holder.title.setText(book.title);
        String status;
        ReadingProgress progress = store.progressFor(book);
        if (progress.hasStarted()) {
            int chapter = Math.max(0, Math.min(book.volumeCount - 1, progress.chapter));
            status = "第 " + (chapter + 1) + "/" + book.volumeCount
                    + " 卷 · 全书 " + progress.overallPercent(book.volumeCount) + "%";
        } else if (book.complete && book.edition == Book.Edition.VERNACULAR) {
            status = book.volumeCount + " 卷 · 机器辅助初译";
        } else if (book.complete) {
            status = book.volumeCount + " 卷 · 全本";
        } else {
            status = "样章 · " + book.editionName();
        }
        holder.detail.setText(status);
        holder.detail.setTextColor(progress.hasStarted() ? Ui.CINNABAR
                : book.complete ? Ui.SAGE : Ui.MUTED);
        recycled.setContentDescription(book.displayTitle() + "，" + status);
        return recycled;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        return params;
    }

    private void applyFilter() {
        visible.clear();
        for (Book book : all) {
            boolean category = "全部".equals(filter)
                    || ("原文".equals(filter) && book.edition == Book.Edition.ORIGINAL)
                    || ("白话".equals(filter) && book.edition == Book.Edition.VERNACULAR)
                    || ("收藏".equals(filter) && favorites.contains(book.id));
            if (category && book.matches(query)) visible.add(book);
        }
        notifyDataSetChanged();
    }

    private static final class Holder {
        final BookCoverView cover;
        final TextView title;
        final TextView detail;

        Holder(BookCoverView cover, TextView title, TextView detail) {
            this.cover = cover;
            this.title = title;
            this.detail = detail;
        }
    }
}
