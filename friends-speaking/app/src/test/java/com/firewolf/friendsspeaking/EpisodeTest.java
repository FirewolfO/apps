package com.firewolf.friendsspeaking;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EpisodeTest {
    @Test
    public void containsAllTenSeasonsAnd236EpisodeSlots() {
        int[] expected = {24, 24, 25, 24, 24, 25, 24, 24, 24, 18};
        assertEquals(10, Episode.seasonCount());
        assertEquals(236, Episode.all().size());
        for (int season = 1; season <= expected.length; season++) {
            assertEquals(expected[season - 1], Episode.season(season).size());
        }
        assertEquals("S10E18", Episode.of(10, 18).key);
        assertNull(Episode.of(10, 19));
    }
}
