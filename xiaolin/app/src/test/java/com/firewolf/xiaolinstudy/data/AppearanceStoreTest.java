package com.firewolf.xiaolinstudy.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class AppearanceStoreTest {
    @Test
    public void normalizesModesAndProvidesStableLabels() {
        assertEquals(AppearanceStore.MODE_SYSTEM, AppearanceStore.normalize(-1));
        assertEquals(AppearanceStore.MODE_SYSTEM, AppearanceStore.normalize(9));
        assertEquals(AppearanceStore.MODE_LIGHT,
                AppearanceStore.normalize(AppearanceStore.MODE_LIGHT));
        assertEquals(AppearanceStore.MODE_DARK,
                AppearanceStore.normalize(AppearanceStore.MODE_DARK));
        assertEquals("跟随系统", AppearanceStore.label(AppearanceStore.MODE_SYSTEM));
        assertEquals("浅色模式", AppearanceStore.label(AppearanceStore.MODE_LIGHT));
        assertEquals("深色模式", AppearanceStore.label(AppearanceStore.MODE_DARK));
        assertEquals("xiaolin_appearance_settings", AppearanceStore.preferencesName());
    }
}
