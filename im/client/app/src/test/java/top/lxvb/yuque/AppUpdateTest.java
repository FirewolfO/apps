package top.lxvb.yuque;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppUpdateTest {
    @Test
    public void comparesSemanticVersions() {
        assertTrue(AppUpdate.isNewer("1.10.0", "1.9.9"));
        assertTrue(AppUpdate.isNewer("2.0", "1.99.99"));
        assertFalse(AppUpdate.isNewer("1.5.0", "1.5.0"));
        assertFalse(AppUpdate.isNewer("1.4.9", "1.5.0"));
    }
}
