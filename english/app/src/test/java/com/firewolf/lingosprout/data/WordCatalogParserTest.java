package com.firewolf.lingosprout.data;

import org.junit.Test;

import java.io.FileReader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WordCatalogParserTest {
    @Test
    public void fullCatalogHasExpectedTopicsAndCounts() throws Exception {
        Map<String, List<Word>> catalog = WordCatalogParser.parse(
                new FileReader("src/main/assets/words.tsv"));
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("animals", 64);
        expected.put("food", 58);
        expected.put("home", 52);
        expected.put("transport", 46);
        expected.put("nature", 61);
        expected.put("actions", 72);
        expected.put("people", 45);
        expected.put("school", 48);
        expected.put("body", 47);
        expected.put("clothes", 42);
        expected.put("colors", 39);
        expected.put("feelings", 46);

        assertEquals(expected.keySet(), catalog.keySet());
        int total = 0;
        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            List<Word> words = catalog.get(entry.getKey());
            assertEquals(entry.getValue().intValue(), words.size());
            total += words.size();
            for (Word word : words) {
                assertTrue(ids.add(word.getId()));
                assertFalse(word.getChinese().isEmpty());
                assertFalse(word.getVisual().isEmpty());
                assertTrue(word.getExample().endsWith("."));
            }
        }
        assertEquals(620, total);
    }

    @Test
    public void examplesUseSimpleChildFriendlyPatterns() {
        assertEquals("I can see an elephant.", WordCatalogParser.exampleFor("animals", "elephant"));
        assertEquals("I can run.", WordCatalogParser.exampleFor("actions", "run"));
        assertEquals("I wear my jacket.", WordCatalogParser.exampleFor("clothes", "jacket"));
    }
}
