package com.inkriver.historyreader.data;

public final class Chapter {
    public final int index;
    public final String title;
    public final String original;
    public final String vernacular;
    public final String assetPath;

    public Chapter(int index, String title, String original, String vernacular) {
        this(index, title, original, vernacular, null);
    }

    public Chapter(int index, String title, String assetPath) {
        this(index, title, null, null, assetPath);
    }

    private Chapter(int index, String title, String original, String vernacular,
                    String assetPath) {
        this.index = index;
        this.title = title;
        this.original = original;
        this.vernacular = vernacular;
        this.assetPath = assetPath;
    }

    public String textFor(Book.Edition edition) {
        if (edition == Book.Edition.VERNACULAR) return vernacular;
        return original;
    }
}
