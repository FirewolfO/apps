package com.firewolf.xiaolinstudy.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public final class ProgressStoreTest {
    @Test
    public void keepsLegacyFullProgressAndCompactProgressSeparate() {
        assertEquals("xiaolin_learning_progress", ProgressStore.preferencesName(false));
        assertEquals("xiaolin_learning_progress_compact", ProgressStore.preferencesName(true));
        assertNotEquals(ProgressStore.preferencesName(false), ProgressStore.preferencesName(true));
    }
}
