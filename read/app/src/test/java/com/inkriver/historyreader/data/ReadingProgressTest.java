package com.inkriver.historyreader.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReadingProgressTest {
    @Test
    public void reportsChapterAndOverallPercent() {
        ReadingProgress progress = new ReadingProgress(
                "shiji-vernacular", 64, 900, 5_000, 123L);

        assertTrue(progress.hasStarted());
        assertEquals(50, progress.chapterPercent());
        assertEquals(50, progress.overallPercent(130));
        assertEquals("shiji-vernacular", progress.bookId);
    }

    @Test
    public void clampsStoredPositionAndHandlesEmptyBooks() {
        ReadingProgress progress = new ReadingProgress("", 0, 0, 20_000, 0L);

        assertFalse(progress.hasStarted());
        assertEquals(100, progress.chapterPercent());
        assertEquals(0, progress.overallPercent(0));
    }
}
