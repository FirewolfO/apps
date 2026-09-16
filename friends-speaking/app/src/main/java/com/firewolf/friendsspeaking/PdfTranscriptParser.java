package com.firewolf.friendsspeaking;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Extracts the bilingual dialogue section from the supplied Friends PDF study notes. */
final class PdfTranscriptParser {
    private PdfTranscriptParser() {}

    static List<String> parse(InputStream input) throws Exception {
        try (PDDocument document = PDDocument.load(input)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setPageEnd("\f");
            return extractDialogue(stripper.getText(document));
        }
    }

    static List<String> extractDialogue(String extractedText) {
        List<String> result = new ArrayList<>();
        if (extractedText == null || extractedText.trim().isEmpty()) return result;
        boolean bodyStarted = false;
        for (String page : extractedText.split("\\f")) {
            String compact = page.replaceAll("\\s+", "");
            if (!bodyStarted && compact.contains("2.正文")) bodyStarted = true;
            if (!bodyStarted) continue;
            if (compact.contains("3.四级词汇")) break;
            parsePage(page, result);
        }
        if (!result.isEmpty()) return result;

        // Generic fallback for user-selected PDFs without the supplied document's section headings.
        for (String page : extractedText.split("\\f")) parsePage(page, result);
        return result;
    }

    private static void parsePage(String page, List<String> output) {
        List<String> chinese = new ArrayList<>();
        List<String> english = new ArrayList<>();
        for (String raw : page.split("\\R")) {
            String line = clean(raw);
            if (skip(line)) continue;
            if (containsCjk(line)) {
                if (!english.isEmpty()) emit(chinese, english, output);
                chinese.add(line);
            } else if (containsLatin(line)) {
                english.add(line);
            }
        }
        emit(chinese, english, output);
    }

    private static void emit(List<String> chinese, List<String> english, List<String> output) {
        if (chinese.isEmpty() && english.isEmpty()) return;
        StringBuilder value = new StringBuilder();
        if (!english.isEmpty()) value.append(String.join(" ", english));
        if (!chinese.isEmpty()) {
            if (value.length() > 0) value.append('\n');
            value.append(String.join("", chinese));
        }
        String text = value.toString().trim();
        if (!text.isEmpty() && text.length() <= 1000) output.add(text);
        chinese.clear();
        english.clear();
    }

    private static boolean skip(String line) {
        if (line.isEmpty() || line.matches("\\d{1,3}")) return true;
        String compact = line.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return compact.equals("2.正文") || compact.startsWith("http://") || compact.startsWith("https://")
                || compact.contains("zhihu.com/people/");
    }

    private static String clean(String line) {
        return line.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean containsCjk(String value) {
        for (int index = 0; index < value.length(); index++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value.charAt(index));
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) return true;
        }
        return false;
    }

    private static boolean containsLatin(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isLetter(value.charAt(index))) return true;
        return false;
    }
}
