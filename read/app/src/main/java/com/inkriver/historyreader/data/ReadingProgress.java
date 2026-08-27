package com.inkriver.historyreader.data;

public final class ReadingProgress {
    public static final int POSITION_SCALE = 10_000;

    public final String bookId;
    public final int chapter;
    public final int scrollY;
    public final int scrollPosition;
    public final long updatedAt;

    public ReadingProgress(int chapter, int scrollY, long updatedAt) {
        this("", chapter, scrollY, 0, updatedAt);
    }

    public ReadingProgress(int chapter, int scrollY, int scrollPosition, long updatedAt) {
        this("", chapter, scrollY, scrollPosition, updatedAt);
    }

    public ReadingProgress(String bookId, int chapter, int scrollY,
                           int scrollPosition, long updatedAt) {
        this.bookId = bookId == null ? "" : bookId;
        this.chapter = chapter;
        this.scrollY = scrollY;
        this.scrollPosition = clamp(scrollPosition, 0, POSITION_SCALE);
        this.updatedAt = updatedAt;
    }

    public boolean hasStarted() {
        return updatedAt > 0;
    }

    public int chapterPercent() {
        return Math.round(scrollPosition * 100f / POSITION_SCALE);
    }

    public int overallPercent(int chapterCount) {
        if (chapterCount <= 0) return 0;
        float completed = clamp(chapter, 0, chapterCount - 1)
                + scrollPosition / (float) POSITION_SCALE;
        return clamp(Math.round(completed * 100f / chapterCount), 0, 100);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
