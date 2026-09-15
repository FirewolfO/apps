package com.firewolf.friendsspeaking;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SubtitleParserTest {
    @Test
    public void parsesSrtBilingualCuesAndFindsActiveLine() throws Exception {
        String srt = "1\n00:00:01,250 --> 00:00:03,500\n<i>How are you?</i>\n你好吗？\n\n"
                + "2\n00:00:04,000 --> 00:00:06,000\nI'm fine.\n";
        List<SubtitleCue> cues = SubtitleParser.parse(new ByteArrayInputStream(srt.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, cues.size());
        assertEquals("How are you?\n你好吗？", cues.get(0).text);
        assertEquals(0, SubtitleParser.activeIndex(cues, 2_000));
        assertEquals(-1, SubtitleParser.activeIndex(cues, 3_750));
        assertEquals(1, SubtitleParser.activeIndex(cues, 5_000));
    }

    @Test
    public void parsesWebVttWithoutNumericCueIds() throws Exception {
        String vtt = "WEBVTT\n\n00:01.000 --> 00:02.500 align:center\nHello!\n";
        List<SubtitleCue> cues = SubtitleParser.parse(new ByteArrayInputStream(vtt.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, cues.size());
        assertEquals(1_000, cues.get(0).startMs);
        assertEquals(2_500, cues.get(0).endMs);
    }
}
