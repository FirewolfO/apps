package com.firewolf.players;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class CatalogParser {
    private static final int MAX_ITEMS = 500;

    private CatalogParser() {}

    public static List<VideoItem> parse(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        if (root.optInt("version", 0) != 1) throw new JSONException("不支持的片单版本");
        JSONArray items = root.getJSONArray("items");
        if (items.length() > MAX_ITEMS) throw new JSONException("片单条目过多");
        List<VideoItem> result = new ArrayList<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject value = items.optJSONObject(index);
            if (value == null) continue;
            VideoItem item = parseItem(value);
            if (item.isPlayable()) result.add(item);
        }
        return result;
    }

    static VideoItem parseItem(JSONObject value) {
        JSONObject license = value.optJSONObject("license");
        JSONArray sourceValues = value.optJSONArray("streams");
        List<VideoItem.Stream> streams = new ArrayList<>();
        if (sourceValues != null) {
            for (int index = 0; index < sourceValues.length(); index++) {
                JSONObject stream = sourceValues.optJSONObject(index);
                if (stream == null) continue;
                streams.add(new VideoItem.Stream(
                        stream.optString("label"), stream.optString("url"),
                        stream.optString("mimeType"), stream.optString("subtitleUrl")));
            }
        }
        return new VideoItem(
                value.optString("id"), value.optString("title"), value.optString("summary"),
                value.optString("posterUrl"), value.optString("source"), value.optString("category"),
                value.optString("year"), value.optString("badge"),
                license == null ? "" : license.optString("name"),
                license == null ? "" : license.optString("url"), value.optString("addedAt"), streams);
    }

    public static String encode(List<VideoItem> items, String updatedAt) throws JSONException {
        JSONArray values = new JSONArray();
        for (VideoItem item : items) if (item.isPlayable()) values.put(item.toJson());
        return new JSONObject()
                .put("version", 1)
                .put("updatedAt", updatedAt)
                .put("items", values)
                .toString(2);
    }
}
