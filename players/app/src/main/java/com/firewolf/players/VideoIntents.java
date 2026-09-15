package com.firewolf.players;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class VideoIntents {
    static final String ID = "video_id";
    static final String TITLE = "video_title";
    static final String SUMMARY = "video_summary";
    static final String POSTER = "video_poster";
    static final String SOURCE = "video_source";
    static final String CATEGORY = "video_category";
    static final String YEAR = "video_year";
    static final String BADGE = "video_badge";
    static final String LICENSE_NAME = "video_license_name";
    static final String LICENSE_URL = "video_license_url";
    static final String ADDED_AT = "video_added_at";
    static final String STREAMS = "video_streams";
    static final String STREAM_INDEX = "stream_index";

    private VideoIntents() {}

    static void put(Intent intent, VideoItem item) {
        intent.putExtra(ID, item.id);
        intent.putExtra(TITLE, item.title);
        intent.putExtra(SUMMARY, item.summary);
        intent.putExtra(POSTER, item.posterUrl);
        intent.putExtra(SOURCE, item.source);
        intent.putExtra(CATEGORY, item.category);
        intent.putExtra(YEAR, item.year);
        intent.putExtra(BADGE, item.badge);
        intent.putExtra(LICENSE_NAME, item.licenseName);
        intent.putExtra(LICENSE_URL, item.licenseUrl);
        intent.putExtra(ADDED_AT, item.addedAt);
        JSONArray streams = new JSONArray();
        try {
            for (VideoItem.Stream stream : item.streams) streams.put(stream.toJson());
        } catch (Exception ignored) {
            // Each stream was validated before this point.
        }
        intent.putExtra(STREAMS, streams.toString());
    }

    static VideoItem get(Intent intent) {
        List<VideoItem.Stream> streams = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(intent.getStringExtra(STREAMS));
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.getJSONObject(index);
                streams.add(new VideoItem.Stream(value.optString("label"), value.optString("url"),
                        value.optString("mimeType"), value.optString("subtitleUrl")));
            }
        } catch (Exception ignored) {
            // The caller will show an unavailable state if no valid stream remains.
        }
        return new VideoItem(
                intent.getStringExtra(ID), intent.getStringExtra(TITLE), intent.getStringExtra(SUMMARY),
                intent.getStringExtra(POSTER), intent.getStringExtra(SOURCE), intent.getStringExtra(CATEGORY),
                intent.getStringExtra(YEAR), intent.getStringExtra(BADGE),
                intent.getStringExtra(LICENSE_NAME), intent.getStringExtra(LICENSE_URL),
                intent.getStringExtra(ADDED_AT), streams);
    }
}
