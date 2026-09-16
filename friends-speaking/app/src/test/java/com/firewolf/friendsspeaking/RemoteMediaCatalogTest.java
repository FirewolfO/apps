package com.firewolf.friendsspeaking;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteMediaCatalogTest {
    @Test public void releaseUsesThePublishedHttpsDomain() throws Exception {
        java.net.URI base = new java.net.URI(BuildConfig.MEDIA_BASE_URL);
        assertEquals("https", base.getScheme());
        assertEquals("apps.lxvb.top", base.getHost());
        assertTrue(base.getPath().startsWith("/media/friends/"));
    }
    @Test
    public void reflectsServerInventory() {
        int audio = 0;
        int subtitles = 0;
        for (Episode episode : Episode.all()) {
            if (RemoteMediaCatalog.hasAudio(episode)) audio++;
            if (RemoteMediaCatalog.hasSubtitle(episode)) subtitles++;
        }
        assertEquals(226, audio);
        assertEquals(226, subtitles);
        assertFalse(RemoteMediaCatalog.hasAudio(Episode.of(10, 12)));
        assertFalse(RemoteMediaCatalog.hasAudio(Episode.of(2, 24)));
        assertFalse(RemoteMediaCatalog.hasSubtitle(Episode.of(2, 24)));
    }
}
