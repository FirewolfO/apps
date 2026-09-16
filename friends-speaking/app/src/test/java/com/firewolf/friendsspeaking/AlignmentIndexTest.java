package com.firewolf.friendsspeaking;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class AlignmentIndexTest {
    private static final String AUDIO = "a".repeat(64);
    private static final String PDF = "b".repeat(64);

    private AlignmentIndex read(String rows) throws Exception {
        return read(rows, "");
    }

    private AlignmentIndex read(String rows, String scope) throws Exception {
        return AlignmentIndex.read(new ByteArrayInputStream(("FA1\tS01E01\t" + AUDIO + "\t" + PDF
                + "\t120000" + scope + "\n" + rows).getBytes(StandardCharsets.UTF_8)), "S01E01");
    }

    private String row(String text, long start, long end) {
        return AlignmentIndex.textHash(text) + "\t" + start + "\t" + end + "\t-0.2\n";
    }

    @Test public void usesMeasuredTimesAndLeavesMusicAndRejectedLinesUnhighlighted() throws Exception {
        AlignmentIndex index = read(row("Hello\n你好", 50000, 52500)
                + row("Uncertain", -1, -1) + row("Bye\n再见", 100000, 101000));
        List<SubtitleCue> cues = index.bind(Arrays.asList("Hello\n你好", "Uncertain", "Bye\n再见"));
        assertEquals(2, cues.size());
        assertEquals(50000, cues.get(0).startMs);
        assertEquals(100000, cues.get(1).startMs);
        assertEquals(-1, SubtitleParser.activeIndex(cues, 49000));
        assertEquals(0, SubtitleParser.activeIndex(cues, 50000));
        assertEquals(-1, SubtitleParser.activeIndex(cues, 52500));
        assertEquals(-1, SubtitleParser.activeIndex(cues, 90000));
        assertEquals(1, SubtitleParser.activeIndex(cues, 100000));
        assertEquals(-1, SubtitleParser.activeIndex(cues, 101000));
    }

    @Test public void rejectsOtherAudioVersionsEvenWithSameLength() throws Exception {
        AlignmentIndex index = read(row("Hi", 1000, 2000));
        assertTrue(index.matchesAudio(AUDIO, 120000));
        assertFalse(index.matchesAudio(PDF, 120000));
        assertFalse(index.matchesAudio(AUDIO, 122000));
    }

    @Test public void whitespaceDoesNotChangeTranscriptIdentity() throws Exception {
        AlignmentIndex index = read(row("Hello there\n你好", 1000, 2000));
        assertEquals(1, index.bind(List.of("Hello\nthere\n你 好")).size());
    }

    @Test public void repeatedUtterancesKeepTheirOwnTimes() throws Exception {
        AlignmentIndex index = read(row("Yes", 1000, 2000) + row("Yes", 5000, 6000));
        List<SubtitleCue> cues = index.bind(Arrays.asList("Yes", "Yes"));
        assertEquals(5000, cues.get(1).startMs);
    }

    @Test public void printedEpisodeTitlesAreNeverHighlightedAsSpeech() throws Exception {
        String title = "Friends S01E01: A printed episode title\n剧集标题";
        AlignmentIndex index = read(row("Hello", 1000, 2000) + row(title, 3000, 6000)
                + row("Bye", 7000, 8000));
        List<SubtitleCue> cues = index.bind(Arrays.asList("Hello", title, "Bye"));
        assertEquals(2, cues.size());
        assertEquals(-1, SubtitleParser.activeIndex(cues, 4000));
        assertFalse(AlignmentIndex.isEpisodeHeading("Friends are important."));
    }

    @Test public void partialAudioDoesNotCompressMissingDialogueIntoTheExistingClip() throws Exception {
        AlignmentIndex index = read(row("First", 1000, 2000) + row("Second", 5000, 6000)
                + row("Missing from audio", -1, -1), "\tprefix=2");
        assertTrue(index.partialAudio());
        assertEquals(2, index.sourceLineCount);
        List<SubtitleCue> cues = index.bind(Arrays.asList("First", "Second", "Missing from audio"));
        assertEquals(2, cues.size());
        assertEquals(5000, cues.get(1).startMs);
    }

    @Test(expected = IOException.class) public void rejectsTimingsForAbsentAudioContent() throws Exception {
        read(row("First", 1000, 2000) + row("Absent", 5000, 6000), "\tprefix=1");
    }

    @Test public void clipsOverlapAtNextSpeakerWithoutMovingStarts() throws Exception {
        AlignmentIndex index = read(row("First", 1000, 2100) + row("Second", 2000, 3000));
        List<SubtitleCue> cues = index.bind(Arrays.asList("First", "Second"));
        assertEquals(2000, cues.get(0).endMs);
        assertEquals(2000, cues.get(1).startMs);
        assertEquals(1, SubtitleParser.activeIndex(cues, 2000));
    }

    @Test(expected = IOException.class) public void refusesChangedTranscript() throws Exception {
        read(row("Original", 1000, 2000)).bind(List.of("Replacement"));
    }

    @Test(expected = IOException.class) public void refusesMissingTranscriptLines() throws Exception {
        read(row("Original", 1000, 2000)).bind(List.of());
    }

    @Test(expected = IOException.class) public void refusesBackwardsTimings() throws Exception {
        read(row("First", 2000, 3000) + row("Second", 1000, 1500));
    }

    @Test(expected = IOException.class) public void refusesTimingsOutsideAudio() throws Exception {
        read(row("First", 119000, 121000));
    }
}
