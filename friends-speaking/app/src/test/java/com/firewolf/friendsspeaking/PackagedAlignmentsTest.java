package com.firewolf.friendsspeaking;

import org.junit.Test;
import org.junit.Assume;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;

import static org.junit.Assert.*;

public class PackagedAlignmentsTest {
    @Test public void everyCatalogPdfHasItsOwnIndexBeforeRelease() {
        int expected = 0;
        for (Episode episode : Episode.all()) {
            if (!RemoteMediaCatalog.hasSubtitle(episode)) continue;
            expected++;
            assertTrue(episode.key, new File("src/main/assets/alignments/" + episode.key + ".tsv").isFile());
        }
        assertEquals(226, expected);
        assertEquals(expected, indexes().length);
    }

    private File[] indexes() {
        File[] files = new File("src/main/assets/alignments").listFiles(file -> file.getName().endsWith(".tsv"));
        assertNotNull(files);
        assertTrue(files.length > 0);
        return files;
    }

    @Test public void packagedTimesAreMonotonicAndInsideTheirOwnAudio() throws Exception {
        for (File file : indexes()) {
            String key = file.getName().replace(".tsv", "");
            try (FileInputStream input = new FileInputStream(file)) {
                AlignmentIndex index = AlignmentIndex.read(input, key);
                assertTrue(key, index.durationMs > 60_000);
            }
        }
    }

    /** Optional private-media integration check. No transcripts/audio enter Git or the APK. */
    @Test public void applicationParserBindsEveryAvailablePrivatePdfToItsExactIndex() throws Exception {
        String cache = System.getenv("FRIENDS_ALIGNMENT_CACHE");
        Assume.assumeTrue("Set FRIENDS_ALIGNMENT_CACHE for real-media verification", cache != null);
        for (File file : indexes()) {
            String key = file.getName().replace(".tsv", "");
            File pdf = new File(new File(cache, key), "transcript.pdf");
            assertTrue(key + " PDF", pdf.isFile());
            List<String> transcript;
            try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.pdmodel.PDDocument.load(pdf)) {
                org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                stripper.setSortByPosition(true);
                stripper.setPageEnd("\f");
                transcript = PdfTranscriptParser.extractDialogue(stripper.getText(document));
            }
            try (FileInputStream input = new FileInputStream(file)) {
                AlignmentIndex index = AlignmentIndex.read(input, key);
                try (FileInputStream media = new FileInputStream(new File(new File(cache, key), "audio.wma"));
                     FileInputStream source = new FileInputStream(pdf)) {
                    assertEquals(key + " audio fingerprint", index.audioSha256, AudioCache.fingerprint(media, () -> false));
                    assertEquals(key + " PDF fingerprint", index.pdfSha256, AudioCache.fingerprint(source, () -> false));
                }
                List<SubtitleCue> cues;
                try {
                    cues = index.bind(transcript);
                } catch (java.io.IOException exception) {
                    throw new AssertionError(key + " transcript does not match packaged index", exception);
                }
                assertFalse(key + " has no matching dialogue", cues.isEmpty());
                if (index.partialAudio()) {
                    assertTrue("Only verified short sources may be partial", key.equals("S07E22") || key.equals("S08E18"));
                    assertEquals(key.equals("S07E22") ? 101 : 282, index.sourceLineCount);
                    assertEquals(key.equals("S07E22") ? 446055 : 1009789, index.durationMs);
                }
                assertTrue(key + " unexpectedly poor coverage", cues.size() >= index.sourceLineCount * 0.75);
                for (SubtitleCue cue : cues) {
                    assertFalse(key, cue.text.contains("zhihu.com"));
                    assertFalse(key + " printed heading is not speech", AlignmentIndex.isEpisodeHeading(cue.text));
                    assertEquals(key, -1, SubtitleParser.activeIndex(cues, -1));
                }
            }
        }
    }
}
