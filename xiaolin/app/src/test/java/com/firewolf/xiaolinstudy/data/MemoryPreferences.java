package com.firewolf.xiaolinstudy.data;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class MemoryPreferences implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    public Map<String, ?> getAll() { return new HashMap<>(values); }
    public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> fallback) { return new HashSet<>((Set<String>) values.getOrDefault(key, fallback)); }
    public int getInt(String key, int fallback) { return (Integer) values.getOrDefault(key, fallback); }
    public long getLong(String key, long fallback) { return (Long) values.getOrDefault(key, fallback); }
    public float getFloat(String key, float fallback) { return (Float) values.getOrDefault(key, fallback); }
    public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
    public boolean contains(String key) { return values.containsKey(key); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    public Editor edit() {
        return new Editor() {
            private final Map<String, Object> changes = new HashMap<>();
            private boolean clear;
            public Editor putString(String key, String value) { changes.put(key, value); return this; }
            public Editor putStringSet(String key, Set<String> value) { changes.put(key, new HashSet<>(value)); return this; }
            public Editor putInt(String key, int value) { changes.put(key, value); return this; }
            public Editor putLong(String key, long value) { changes.put(key, value); return this; }
            public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
            public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
            public Editor remove(String key) { changes.put(key, null); return this; }
            public Editor clear() { clear = true; return this; }
            public boolean commit() { apply(); return true; }
            public void apply() {
                if (clear) values.clear();
                changes.forEach((key, value) -> { if (value == null) values.remove(key); else values.put(key, value); });
            }
        };
    }
}
