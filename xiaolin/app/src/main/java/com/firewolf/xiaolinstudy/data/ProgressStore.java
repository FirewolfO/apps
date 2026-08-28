package com.firewolf.xiaolinstudy.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class ProgressStore {
    private static final String FULL_PREFS = "xiaolin_learning_progress";
    private static final String COMPACT_PREFS = "xiaolin_learning_progress_compact";
    private static final String COMPLETED = "completed_urls";
    private static final String TITLES = "page_titles";
    private static final String VISITED_AT = "visited_at";
    private static final String COMPLETED_AT = "completed_at";
    private static final String SCROLL_POSITIONS = "scroll_positions";
    private static final String LAST_URL = "last_url";
    private static final String LAST_TITLE = "last_title";

    private final SharedPreferences preferences;

    public ProgressStore(Context context) {
        this(context, false);
    }

    public ProgressStore(Context context, boolean compactMode) {
        preferences = context.getSharedPreferences(preferencesName(compactMode), Context.MODE_PRIVATE);
    }

    static String preferencesName(boolean compactMode) {
        return compactMode ? COMPACT_PREFS : FULL_PREFS;
    }

    public void recordVisit(String rawUrl, String rawTitle) {
        String url = UrlTools.normalize(rawUrl);
        if (!UrlTools.isWebUrl(url)) return;
        String title = UrlTools.displayTitle(rawTitle, url);
        JSONObject titles = readObject(TITLES);
        JSONObject visits = readObject(VISITED_AT);
        try {
            titles.put(url, title);
            visits.put(url, System.currentTimeMillis());
        } catch (JSONException ignored) {
            return;
        }
        preferences.edit()
                .putString(TITLES, titles.toString())
                .putString(VISITED_AT, visits.toString())
                .putString(LAST_URL, url)
                .putString(LAST_TITLE, title)
                .apply();
    }

    public boolean setCompleted(String rawUrl, String rawTitle, boolean completed) {
        String url = UrlTools.normalize(rawUrl);
        if (!UrlTools.isCompletable(url)) return false;
        Set<String> urls = new HashSet<>(preferences.getStringSet(COMPLETED,
                Collections.emptySet()));
        JSONObject completedTimes = readObject(COMPLETED_AT);
        JSONObject titles = readObject(TITLES);
        try {
            titles.put(url, UrlTools.displayTitle(rawTitle, url));
            if (completed) {
                urls.add(url);
                completedTimes.put(url, System.currentTimeMillis());
            } else {
                urls.remove(url);
                completedTimes.remove(url);
            }
        } catch (JSONException ignored) {
            return false;
        }
        preferences.edit()
                .putStringSet(COMPLETED, urls)
                .putString(COMPLETED_AT, completedTimes.toString())
                .putString(TITLES, titles.toString())
                .apply();
        return true;
    }

    public boolean isCompleted(String rawUrl) {
        String url = UrlTools.normalize(rawUrl);
        return preferences.getStringSet(COMPLETED, Collections.emptySet()).contains(url);
    }

    public int completedCount() {
        return preferences.getStringSet(COMPLETED, Collections.emptySet()).size();
    }

    public int visitedCount() {
        return readObject(VISITED_AT).length();
    }

    public String getLastUrl() {
        return preferences.getString(LAST_URL, null);
    }

    public String getLastTitle() {
        String url = getLastUrl();
        return UrlTools.displayTitle(preferences.getString(LAST_TITLE, null),
                url == null ? UrlTools.HOME_URL : url);
    }

    public void saveScrollPosition(String rawUrl, int scrollY) {
        String url = UrlTools.normalize(rawUrl);
        if (!UrlTools.isWebUrl(url)) return;
        JSONObject positions = readObject(SCROLL_POSITIONS);
        try {
            positions.put(url, Math.max(0, scrollY));
            preferences.edit().putString(SCROLL_POSITIONS, positions.toString()).apply();
        } catch (JSONException ignored) {
            // A failed position write must never interrupt reading.
        }
    }

    public int getScrollPosition(String rawUrl) {
        return readObject(SCROLL_POSITIONS).optInt(UrlTools.normalize(rawUrl), 0);
    }

    public List<PageRecord> getCompletedPages() {
        return buildRecords(true, 200);
    }

    public List<PageRecord> getRecentPages(int limit) {
        return buildRecords(false, Math.max(1, limit));
    }

    private List<PageRecord> buildRecords(boolean completedOnly, int limit) {
        Set<String> completed = preferences.getStringSet(COMPLETED, Collections.emptySet());
        JSONObject titles = readObject(TITLES);
        JSONObject visits = readObject(VISITED_AT);
        JSONObject completions = readObject(COMPLETED_AT);
        Set<String> urls = new HashSet<>();
        if (completedOnly) {
            urls.addAll(completed);
        } else {
            Iterator<String> keys = visits.keys();
            while (keys.hasNext()) urls.add(keys.next());
        }

        List<PageRecord> records = new ArrayList<>();
        for (String url : urls) {
            records.add(new PageRecord(url,
                    UrlTools.displayTitle(titles.optString(url, ""), url),
                    visits.optLong(url, 0L), completions.optLong(url, 0L)));
        }
        Comparator<PageRecord> comparator = completedOnly
                ? (left, right) -> Long.compare(right.getCompletedAt(), left.getCompletedAt())
                : (left, right) -> Long.compare(right.getVisitedAt(), left.getVisitedAt());
        Collections.sort(records, comparator);
        return records.size() <= limit ? records : new ArrayList<>(records.subList(0, limit));
    }

    private JSONObject readObject(String key) {
        try {
            return new JSONObject(preferences.getString(key, "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }
}
