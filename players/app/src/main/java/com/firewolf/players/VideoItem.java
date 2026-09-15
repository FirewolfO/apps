package com.firewolf.players;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.net.URI;

public final class VideoItem {
    public static final class Stream {
        public final String label;
        public final String url;
        public final String mimeType;
        public final String subtitleUrl;

        public Stream(String label, String url, String mimeType, String subtitleUrl) {
            this.label = clean(label, "高清");
            this.url = secureUrl(url);
            this.mimeType = clean(mimeType, "");
            this.subtitleUrl = secureUrl(subtitleUrl);
        }

        JSONObject toJson() throws JSONException {
            JSONObject value = new JSONObject();
            value.put("label", label);
            value.put("url", url);
            if (!mimeType.isEmpty()) value.put("mimeType", mimeType);
            if (!subtitleUrl.isEmpty()) value.put("subtitleUrl", subtitleUrl);
            return value;
        }
    }

    public final String id;
    public final String title;
    public final String summary;
    public final String posterUrl;
    public final String source;
    public final String category;
    public final String year;
    public final String badge;
    public final String licenseName;
    public final String licenseUrl;
    public final String addedAt;
    public final List<Stream> streams;

    public VideoItem(String id, String title, String summary, String posterUrl, String source,
                     String category, String year, String badge, String licenseName,
                     String licenseUrl, String addedAt, List<Stream> streams) {
        this.id = clean(id, "");
        this.title = clean(title, "未命名视频");
        this.summary = clean(summary, "暂无简介");
        this.posterUrl = secureUrl(posterUrl);
        this.source = clean(source, "开放片源");
        this.category = clean(category, "视频");
        this.year = clean(year, "");
        this.badge = clean(badge, "高清");
        this.licenseName = clean(licenseName, "来源方授权内容");
        this.licenseUrl = secureUrl(licenseUrl);
        this.addedAt = clean(addedAt, "");
        List<Stream> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (streams != null) {
            for (Stream stream : streams) {
                if (stream != null && !stream.url.isEmpty() && seen.add(stream.url)) valid.add(stream);
            }
        }
        this.streams = Collections.unmodifiableList(valid);
    }

    public boolean isPlayable() {
        return !id.isEmpty() && !streams.isEmpty();
    }

    public Stream primaryStream() {
        return streams.get(0);
    }

    public boolean matches(String query) {
        String needle = clean(query, "").toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return true;
        return (title + " " + summary + " " + source + " " + category + " " + year)
                .toLowerCase(Locale.ROOT).contains(needle);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject value = new JSONObject();
        value.put("id", id);
        value.put("title", title);
        value.put("summary", summary);
        value.put("posterUrl", posterUrl);
        value.put("source", source);
        value.put("category", category);
        value.put("year", year);
        value.put("badge", badge);
        value.put("addedAt", addedAt);
        value.put("license", new JSONObject().put("name", licenseName).put("url", licenseUrl));
        JSONArray sources = new JSONArray();
        for (Stream stream : streams) sources.put(stream.toJson());
        value.put("streams", sources);
        return value;
    }

    private static String clean(String value, String fallback) {
        String result = value == null ? "" : value.trim();
        return result.isEmpty() ? fallback : result;
    }

    static String secureUrl(String value) {
        String result = value == null ? "" : value.trim();
        if (result.startsWith("http://images-assets.nasa.gov/")) {
            result = "https://" + result.substring("http://".length());
        }
        if (!result.startsWith("https://")) return "";
        try {
            return new URI(result.replace(" ", "%20")).toASCIIString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
