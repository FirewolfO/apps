package com.firewolf.friendsspeaking;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SubtitleTimelineTest {
    @Test
    public void distributesUntimedTranscriptAcrossPlaybackDuration() {
        List<SubtitleCue> cues = SubtitleTimeline.distribute(Arrays.asList(
                "Hi.\n你好。", "This is a substantially longer sentence.\n这是更长的一句话。", "Bye.\n再见。"), 60_000);

        assertEquals(3, cues.size());
        assertTrue(cues.get(0).startMs >= 0);
        assertTrue(cues.get(0).endMs < cues.get(1).endMs);
        assertTrue(cues.get(1).endMs < cues.get(2).endMs);
        assertTrue(cues.get(2).endMs <= 60_000);
        assertEquals(1, SubtitleParser.activeIndex(cues, cues.get(1).startMs));
    }
}
