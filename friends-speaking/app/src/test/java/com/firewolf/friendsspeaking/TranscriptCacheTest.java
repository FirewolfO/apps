package com.firewolf.friendsspeaking;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.Assert.*;

public class TranscriptCacheTest {
    @Test public void publishedDialogueRetainsCueBoundariesAndBilingualLineBreaks() throws Exception {
        List<String> lines = TranscriptCache.parse(new ByteArrayInputStream(
                "Hello there.\n你好。\fBye!\n再见！".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("Hello there.\n你好。", "Bye!\n再见！"), lines);
    }

    @Test(expected = IOException.class) public void refusesOversizedResponse() throws Exception {
        TranscriptCache.parse(new ByteArrayInputStream(new byte[4 * 1024 * 1024 + 1]));
    }

    @Test(expected = IOException.class) public void refusesEmptyResponse() throws Exception {
        TranscriptCache.parse(new ByteArrayInputStream(new byte[0]));
    }
}
