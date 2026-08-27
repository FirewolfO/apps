package com.inkriver.historyreader.data;

import java.util.Locale;

public final class Book {
    public enum Edition {
        ORIGINAL, VERNACULAR, IMPORTED
    }

    public final String id;
    public final String historyKey;
    public final String title;
    public final String author;
    public final String period;
    public final int volumeCount;
    public final String description;
    public final int accent;
    public final Edition edition;
    public final boolean complete;
    public final String contentFile;

    public Book(String id, String historyKey, String title, String author, String period,
                int volumeCount, String description, int accent, Edition edition,
                boolean complete, String contentFile) {
        this.id = id;
        this.historyKey = historyKey;
        this.title = title;
        this.author = author;
        this.period = period;
        this.volumeCount = volumeCount;
        this.description = description;
        this.accent = accent;
        this.edition = edition;
        this.complete = complete;
        this.contentFile = contentFile;
    }

    public String editionName() {
        if (edition == Edition.ORIGINAL) return "原文";
        if (edition == Edition.VERNACULAR) return "白话";
        return "导入";
    }

    public String displayTitle() {
        return title + " · " + editionName();
    }

    public String progressKey() {
        return historyKey;
    }

    public boolean matches(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return true;
        return (title + author + period + editionName() + description)
                .toLowerCase(Locale.ROOT).contains(needle);
    }
}
