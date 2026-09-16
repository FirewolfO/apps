package com.firewolf.friendsspeaking;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RemoteMediaCatalogTest {
    @Test
    public void reflectsServerInventory() {
        int audio = 0;
        int subtitles = 0;
        for (Episode episode : Episode.all()) {
            if (RemoteMediaCatalog.hasAudio(episode)) audio++;
            if (RemoteMediaCatalog.hasSubtitle(episode)) subtitles++;
        }
        assertEquals(234, audio);
        assertEquals(226, subtitles);
        assertFalse(RemoteMediaCatalog.hasAudio(Episode.of(10, 12)));
        assertFalse(RemoteMediaCatalog.hasSubtitle(Episode.of(2, 24)));
    }
}
