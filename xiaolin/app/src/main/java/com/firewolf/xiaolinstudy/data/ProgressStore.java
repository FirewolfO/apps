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
import java.util.UUID;

public final class ProgressStore {
    private static final String FULL_PREFS = "xiaolin_learning_progress";
    private static final String COMPACT_PREFS = "xiaolin_learning_progress_compact";
    private static final String RECORDS = "records_v2";
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    private final SharedPreferences clockSettings;
    private String cachedJson;
    private JSONObject cachedRecords;

    public ProgressStore(Context context) { this(context, false); }
    public ProgressStore(Context context, boolean compactMode) {
        this(context.getSharedPreferences(preferencesName(compactMode), Context.MODE_PRIVATE),
                context.getSharedPreferences("xiaolin_progress_clock", Context.MODE_PRIVATE));
    }
    ProgressStore(SharedPreferences preferences, SharedPreferences clockSettings) {
        this.preferences = preferences;
        this.clockSettings = clockSettings;
        synchronized (LOCK) {
            if (!preferences.contains(RECORDS)) migrateLegacy();
            clockSettings.edit().putLong("clock", Math.max(clockSettings.getLong("clock", 0),
                    ProgressMerge.maxClock(read()))).apply();
        }
    }
    static String preferencesName(boolean compactMode) { return compactMode ? COMPACT_PREFS : FULL_PREFS; }

    public void recordVisit(String rawUrl, String rawTitle) {
        String url = UrlTools.normalize(rawUrl);
        if (!UrlTools.isWebUrl(url)) return;
        synchronized (LOCK) {
            try {
                writeField(url, "visit", new JSONObject().put("title", title(rawTitle, url))
                        .put("at", System.currentTimeMillis()));
            } catch (JSONException exception) { throw new IllegalStateException(exception); }
        }
    }

    public boolean setCompleted(String rawUrl, String rawTitle, boolean completed) {
        String url = UrlTools.normalize(rawUrl);
        if (!UrlTools.isCompletable(url)) return false;
        synchronized (LOCK) {
            try {
                if (ProgressMerge.value(read(), url, "visit").optString("title").isEmpty()) {
                    writeField(url, "visit", new JSONObject().put("title", title(rawTitle, url)).put("at", 0));
                }
                // Keep false as a tombstone so stale offline devices cannot resurrect completion.
                writeField(url, "completion", new JSONObject().put("done", completed)
                        .put("at", System.currentTimeMillis()));
                return true;
            } catch (JSONException exception) { throw new IllegalStateException(exception); }
        }
    }

    public boolean isCompleted(String rawUrl) {
        synchronized (LOCK) { return ProgressMerge.value(read(), UrlTools.normalize(rawUrl), "completion").optBoolean("done"); }
    }
    public int completedCount() { return getCompletedPages().size(); }
    public int visitedCount() { return getRecentPages(Integer.MAX_VALUE).size(); }
    public String getLastUrl() {
        List<PageRecord> recent = getRecentPages(1);
        return recent.isEmpty() ? null : recent.get(0).getUrl();
    }
    public String getLastTitle() {
        List<PageRecord> recent = getRecentPages(1);
        return recent.isEmpty() ? "学习内容" : recent.get(0).getTitle();
    }

    public void saveScrollPosition(String rawUrl, int scrollY) { saveScrollPosition(rawUrl, scrollY, -1); }
    public void saveScrollPosition(String rawUrl, int scrollY, double fraction) {
        String url = UrlTools.normalize(rawUrl);
        if (!UrlTools.isWebUrl(url)) return;
        synchronized (LOCK) {
            try {
                int y = Math.min(100_000_000, Math.max(0, scrollY));
                double ratio = Double.isNaN(fraction) ? -1 : Math.max(-1, Math.min(1, fraction));
                JSONObject prior = ProgressMerge.value(read(), url, "position");
                if (prior.optInt("y", -1) == y && Math.abs(prior.optDouble("fraction", -1) - ratio) < 0.0001) return;
                writeField(url, "position", new JSONObject().put("y", y).put("fraction", ratio));
            } catch (JSONException exception) { throw new IllegalStateException(exception); }
        }
    }

    public int getScrollPosition(String rawUrl) {
        synchronized (LOCK) { return ProgressMerge.value(read(), UrlTools.normalize(rawUrl), "position").optInt("y"); }
    }
    public int restoredScrollPosition(String rawUrl, int scrollRange) {
        synchronized (LOCK) {
            JSONObject value = ProgressMerge.value(read(), UrlTools.normalize(rawUrl), "position");
            double fraction = value.optDouble("fraction", -1);
            return fraction >= 0 ? (int) Math.round(fraction * Math.max(0, scrollRange)) : value.optInt("y");
        }
    }
    public List<PageRecord> getCompletedPages() { return buildRecords(true, Integer.MAX_VALUE); }
    public List<PageRecord> getRecentPages(int limit) { return buildRecords(false, Math.max(1, limit)); }

    private List<PageRecord> buildRecords(boolean completedOnly, int limit) {
        synchronized (LOCK) {
            JSONObject state = read();
            List<PageRecord> records = new ArrayList<>();
            Iterator<String> urls = state.keys();
            while (urls.hasNext()) {
                String url = urls.next();
                JSONObject visit = ProgressMerge.value(state, url, "visit");
                JSONObject completion = ProgressMerge.value(state, url, "completion");
                if (completedOnly ? !completion.optBoolean("done") : visit.optLong("at") <= 0) continue;
                records.add(new PageRecord(url, title(visit.optString("title"), url),
                        visit.optLong("at"), completion.optBoolean("done") ? completion.optLong("at") : 0));
            }
            Comparator<PageRecord> order = completedOnly
                    ? (left, right) -> Long.compare(right.getCompletedAt(), left.getCompletedAt())
                    : (left, right) -> ProgressMerge.compare(state.optJSONObject(right.getUrl()).optJSONObject("visit"),
                            state.optJSONObject(left.getUrl()).optJSONObject("visit"));
            Collections.sort(records, (left, right) -> {
                int comparison = order.compare(left, right);
                return comparison != 0 ? comparison : left.getUrl().compareTo(right.getUrl());
            });
            return records.size() <= limit ? records : new ArrayList<>(records.subList(0, limit));
        }
    }

    public JSONObject snapshot() {
        synchronized (LOCK) {
            try { return new JSONObject(read().toString()); }
            catch (JSONException exception) { throw new IllegalStateException(exception); }
        }
    }
    public boolean merge(JSONObject incoming) throws JSONException {
        synchronized (LOCK) {
            JSONObject merged = ProgressMerge.merge(read(), incoming);
            clockSettings.edit().putLong("clock", Math.max(clockSettings.getLong("clock", 0),
                    ProgressMerge.maxClock(merged))).apply();
            if (merged.toString().equals(read().toString())) return false;
            persist(merged);
            return true;
        }
    }
    private JSONObject stamp(JSONObject value) throws JSONException {
        long next = clockSettings.getLong("clock", 0) + 1;
        String device = clockSettings.getString("device", "");
        if (device.isEmpty()) device = UUID.randomUUID().toString().replace("-", "");
        clockSettings.edit().putLong("clock", next).putString("device", device).apply();
        return new JSONObject().put("clock", next).put("device", device).put("value", value);
    }
    private void writeField(String url, String field, JSONObject value) throws JSONException {
        JSONObject state = read();
        JSONObject record = state.optJSONObject(url);
        if (record == null) record = new JSONObject();
        record.put(field, stamp(value));
        state.put(url, record);
        persist(state);
    }
    private JSONObject read() {
        String encoded = preferences.getString(RECORDS, "{}");
        if (encoded.equals(cachedJson)) return cachedRecords;
        try { cachedRecords = new JSONObject(encoded); cachedJson = encoded; return cachedRecords; }
        catch (JSONException exception) { throw new IllegalStateException("学习记录损坏，保留原始数据", exception); }
    }
    private void persist(JSONObject records) {
        cachedJson = records.toString();
        cachedRecords = records;
        preferences.edit().putString(RECORDS, cachedJson).apply();
    }
    private JSONObject legacy(String key) {
        try { return new JSONObject(preferences.getString(key, "{}")); }
        catch (JSONException exception) { throw new IllegalStateException("旧版学习记录损坏，保留原始数据", exception); }
    }
    private void migrateLegacy() {
        JSONObject titles = legacy("page_titles");
        JSONObject visits = legacy("visited_at");
        JSONObject times = legacy("completed_at");
        JSONObject positions = legacy("scroll_positions");
        Set<String> completed = preferences.getStringSet("completed_urls", Collections.emptySet());
        Set<String> urls = new HashSet<>(completed);
        for (JSONObject source : new JSONObject[]{titles, visits, positions}) {
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) urls.add(keys.next());
        }
        List<String> ordered = new ArrayList<>(urls);
        Collections.sort(ordered, (left, right) -> Long.compare(visits.optLong(left), visits.optLong(right)));
        JSONObject state = new JSONObject();
        try {
            for (String oldUrl : ordered) {
                String url = UrlTools.normalizeLegacy(oldUrl);
                if (!UrlTools.isWebUrl(url)) continue;
                JSONObject record = state.optJSONObject(url);
                if (record == null) record = new JSONObject();
                record.put("visit", stamp(new JSONObject().put("title", title(titles.optString(oldUrl), url))
                        .put("at", visits.optLong(oldUrl))));
                if (completed.contains(oldUrl)) record.put("completion",
                        stamp(new JSONObject().put("done", true).put("at", times.optLong(oldUrl))));
                if (positions.has(oldUrl)) record.put("position",
                        stamp(new JSONObject().put("y", Math.min(100_000_000, Math.max(0, positions.optInt(oldUrl)))).put("fraction", -1)));
                state.put(url, record);
            }
            // A single preference write makes migration crash-safe; old keys remain recoverable.
            persist(state);
        } catch (JSONException exception) { throw new IllegalStateException(exception); }
    }
    private static String title(String raw, String url) {
        String result = UrlTools.displayTitle(raw, url);
        return result.length() <= 500 ? result : result.substring(0, 500);
    }
}
