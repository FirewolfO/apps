package com.firewolf.xiaolinstudy.data;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VersionToolsTest {
    @Test
    public void comparesSemanticVersionsWithoutLosingPatchSegments() {
        assertTrue(VersionTools.isNewer("1.3.0", "1.2.9"));
        assertTrue(VersionTools.isNewer("2.0", "1.99.99"));
        assertFalse(VersionTools.isNewer("1.3.0", "1.3"));
        assertFalse(VersionTools.isNewer("1.2.9", "1.3.0"));
    }
}
