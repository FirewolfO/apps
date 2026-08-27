package com.inkriver.historyreader.data;

public final class Excerpt {
    public final long id;
    public final String bookId;
    public final String bookTitle;
    public final int chapter;
    public final String chapterTitle;
    public final String text;
    public final String note;
    public final int startOffset;
    public final int endOffset;
    public final long createdAt;

    public Excerpt(long id, String bookId, String bookTitle, int chapter, String chapterTitle,
                   String text, String note, int startOffset, int endOffset, long createdAt) {
        this.id = id;
        this.bookId = bookId;
        this.bookTitle = bookTitle;
        this.chapter = chapter;
        this.chapterTitle = chapterTitle;
        this.text = text;
        this.note = note;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.createdAt = createdAt;
    }
}
