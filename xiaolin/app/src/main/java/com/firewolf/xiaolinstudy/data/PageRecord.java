package com.firewolf.xiaolinstudy.data;

public final class PageRecord {
    private final String url;
    private final String title;
    private final long visitedAt;
    private final long completedAt;

    public PageRecord(String url, String title, long visitedAt, long completedAt) {
        this.url = url;
        this.title = title;
        this.visitedAt = visitedAt;
        this.completedAt = completedAt;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public long getVisitedAt() {
        return visitedAt;
    }

    public long getCompletedAt() {
        return completedAt;
    }
}
