package com.firewolf.xiaolinstudy.data;

import org.junit.Test;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public final class ProgressStoreTest {
    @Test
    public void keepsLegacyFullProgressAndCompactProgressSeparate() {
        assertEquals("xiaolin_learning_progress", ProgressStore.preferencesName(false));
        assertEquals("xiaolin_learning_progress_compact", ProgressStore.preferencesName(true));
        assertNotEquals(ProgressStore.preferencesName(false), ProgressStore.preferencesName(true));
    }

    @Test
    public void migratesAll307RecordsOnceWithoutTruncationAndRepairsChineseKeys() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        MemoryPreferences clock = new MemoryPreferences();
        JSONObject visits = new JSONObject();
        JSONObject times = new JSONObject();
        Set<String> completed = new HashSet<>();
        for (int i = 0; i < 307; i++) {
            String url = "https://www.xiaolincoding.com/network/" + i;
            completed.add(url); visits.put(url, i + 1); times.put(url, i + 1);
        }
        String chinese = "https://www.xiaolincoding.com/os/%25E4%25B8%25AD.html";
        visits.put(chinese, 999);
        prefs.edit().putStringSet("completed_urls", completed).putString("visited_at", visits.toString())
                .putString("completed_at", times.toString())
                .putString("scroll_positions", new JSONObject().put(chinese, 500).toString()).apply();
        ProgressStore store = new ProgressStore(prefs, clock);
        assertEquals(307, store.completedCount());
        assertEquals(307, store.getCompletedPages().size());
        assertEquals(308, store.visitedCount());
        assertEquals("https://www.xiaolincoding.com/os/%E4%B8%AD.html", store.getLastUrl());
        assertEquals(500, store.getScrollPosition(store.getLastUrl()));
        store.setCompleted("https://www.xiaolincoding.com/network/0", "test", false);
        ProgressStore reopened = new ProgressStore(prefs, clock);
        assertEquals(306, reopened.completedCount());
        assertFalse(reopened.isCompleted("https://www.xiaolincoding.com/network/0"));
    }

    @Test
    public void staleResponsesKeepNewLocalEditsAndCompletionRevocations() throws Exception {
        String url = "https://compact.xiaolin/network/tcp";
        ProgressStore a = new ProgressStore(new MemoryPreferences(), new MemoryPreferences());
        ProgressStore b = new ProgressStore(new MemoryPreferences(), new MemoryPreferences());
        a.recordVisit(url, "TCP");
        a.setCompleted(url, "TCP", true);
        b.merge(a.snapshot());
        JSONObject old = b.snapshot();
        a.setCompleted(url, "TCP", false);
        b.saveScrollPosition(url, 600, 0.5);
        a.merge(b.snapshot());
        b.merge(a.snapshot());
        a.merge(old);
        assertFalse(a.isCompleted(url));
        assertFalse(b.isCompleted(url));
        assertEquals(600, a.getScrollPosition(url));
        assertEquals(1000, a.restoredScrollPosition(url, 2000));
        assertEquals(a.snapshot().toString(), b.snapshot().toString());
    }

    @Test
    public void restoringBackupUsesFreshDeviceAndAdvancesPastImportedClocks() throws Exception {
        String url = "https://compact.xiaolin/network/tcp";
        MemoryPreferences prefs = new MemoryPreferences();
        ProgressStore a = new ProgressStore(prefs, new MemoryPreferences());
        a.setCompleted(url, "TCP", true);
        JSONObject original = a.snapshot();
        ProgressStore restored = new ProgressStore(prefs, new MemoryPreferences());
        restored.setCompleted(url, "TCP", false);
        restored.merge(original);
        assertFalse(restored.isCompleted(url));
        assertTrue(ProgressMerge.maxClock(restored.snapshot()) > ProgressMerge.maxClock(original));
    }

    @Test
    public void concurrentStoresObserveLatestPersistedRecords() throws Exception {
        MemoryPreferences prefs = new MemoryPreferences();
        MemoryPreferences clock = new MemoryPreferences();
        ProgressStore a = new ProgressStore(prefs, clock);
        ProgressStore b = new ProgressStore(prefs, clock);
        a.recordVisit("https://compact.xiaolin/first", "First");
        b.recordVisit("https://compact.xiaolin/second", "Second");
        assertEquals(2, a.visitedCount());
        assertEquals("https://compact.xiaolin/second", a.getLastUrl());
    }
}
