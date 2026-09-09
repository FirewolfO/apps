package com.firewolf.xiaolinstudy.data;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;

/** Independent registers let offline edits merge without overwriting unrelated fields. */
public final class ProgressMerge {
    public static final String[] FIELDS = {"visit", "completion", "position"};
    private ProgressMerge() {}

    public static JSONObject merge(JSONObject local, JSONObject incoming) throws JSONException {
        JSONObject result = new JSONObject(local.toString());
        Iterator<String> urls = incoming.keys();
        while (urls.hasNext()) {
            String url = urls.next();
            JSONObject source = incoming.getJSONObject(url);
            JSONObject target = result.optJSONObject(url);
            if (target == null) target = new JSONObject();
            for (String name : FIELDS) {
                JSONObject next = source.optJSONObject(name);
                JSONObject prior = target.optJSONObject(name);
                if (next != null && (prior == null || compare(next, prior) > 0)) {
                    target.put(name, new JSONObject(next.toString()));
                }
            }
            result.put(url, target);
        }
        return result;
    }

    public static int compare(JSONObject left, JSONObject right) {
        int clock = Long.compare(left.optLong("clock"), right.optLong("clock"));
        return clock != 0 ? clock : left.optString("device").compareTo(right.optString("device"));
    }

    public static long maxClock(JSONObject records) {
        long result = 0;
        Iterator<String> urls = records.keys();
        while (urls.hasNext()) {
            JSONObject record = records.optJSONObject(urls.next());
            if (record == null) continue;
            for (String name : FIELDS) {
                JSONObject field = record.optJSONObject(name);
                if (field != null) result = Math.max(result, field.optLong("clock"));
            }
        }
        return result;
    }

    public static JSONObject value(JSONObject records, String url, String field) {
        JSONObject record = records.optJSONObject(url);
        JSONObject register = record == null ? null : record.optJSONObject(field);
        JSONObject value = register == null ? null : register.optJSONObject("value");
        return value == null ? new JSONObject() : value;
    }
}
