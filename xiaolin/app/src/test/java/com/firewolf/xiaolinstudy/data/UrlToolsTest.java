package com.firewolf.xiaolinstudy.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UrlToolsTest {
    @Test
    public void normalizeUnifiesSiteUrlsAndDropsFragmentAndQuery() {
        assertEquals("https://www.xiaolincoding.com/network/2_http/http2.html",
                UrlTools.normalize("http://xiaolincoding.com/network/2_http/http2.html?from=app#http2"));
    }

    @Test
    public void normalizeRemovesNonRootTrailingSlash() {
        assertEquals("https://www.xiaolincoding.com/network",
                UrlTools.normalize("https://www.xiaolincoding.com/network/"));
        assertEquals(UrlTools.HOME_URL, UrlTools.normalize("https://xiaolincoding.com/"));
    }

    @Test
    public void completableRejectsHomeAndDownloadAssets() {
        assertFalse(UrlTools.isCompletable(UrlTools.HOME_URL));
        assertFalse(UrlTools.isCompletable("https://www.xiaolincoding.com/book.pdf"));
        assertTrue(UrlTools.isCompletable("https://www.xiaolincoding.com/os/process.html"));
    }

    @Test
    public void displayTitleRemovesSiteSuffix() {
        assertEquals("HTTP/2 牛逼在哪？",
                UrlTools.displayTitle("HTTP/2 牛逼在哪？ - 小林coding", "https://example.com/page"));
    }
}
