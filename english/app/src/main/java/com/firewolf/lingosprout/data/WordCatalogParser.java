package com.firewolf.lingosprout.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WordCatalogParser {
    private WordCatalogParser() {}

    public static Map<String, List<Word>> parse(Reader source) throws IOException {
        Map<String, List<Word>> wordsByTopic = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] columns = line.split("\\t", -1);
                if (columns.length != 4) {
                    throw new IOException("Invalid word row at line " + lineNumber);
                }
                String topicId = columns[0].trim();
                String english = columns[1].trim();
                String chinese = columns[2].trim();
                String visual = columns[3].trim();
                if (topicId.isEmpty() || english.isEmpty() || chinese.isEmpty() || visual.isEmpty()) {
                    throw new IOException("Empty value at line " + lineNumber);
                }
                String normalized = english.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
                Word word = new Word(topicId + "-" + normalized, topicId, english, chinese, visual,
                        exampleFor(topicId, english));
                List<Word> topicWords = wordsByTopic.get(topicId);
                if (topicWords == null) {
                    topicWords = new ArrayList<>();
                    wordsByTopic.put(topicId, topicWords);
                }
                topicWords.add(word);
            }
        }
        return wordsByTopic;
    }

    static String exampleFor(String topicId, String english) {
        switch (topicId) {
            case "animals": return articleSentence("I can see", english);
            case "food": return "I like " + english + ".";
            case "home": return articleSentence("This is", english);
            case "transport": return articleSentence("Here comes", english);
            case "nature": return "Look at the " + english + ".";
            case "actions": return "I can " + english + ".";
            case "people": return "This is my " + english + ".";
            case "school": return articleSentence("I use", english);
            case "body": return "This is my " + english + ".";
            case "clothes": return "I wear my " + english + ".";
            case "colors": return "Let's find " + english + ".";
            default: return "Today I learned " + english + ".";
        }
    }

    private static String articleSentence(String prefix, String noun) {
        char first = Character.toLowerCase(noun.charAt(0));
        String article = "aeiou".indexOf(first) >= 0 ? "an " : "a ";
        return prefix + " " + article + noun + ".";
    }
}
