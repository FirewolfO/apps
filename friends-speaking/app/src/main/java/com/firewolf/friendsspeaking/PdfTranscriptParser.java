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
        StringBuilder body = new StringBuilder();
        for (String page : extractedText.split("\\f")) {
            String heading = firstLine(page).replaceAll("\\s+", "");
            // A table of contents mentions BOTH headings. Only an actual page
            // heading starts the dialogue; otherwise the fallback included the
            // synopsis and vocabulary pages as if they were spoken dialogue.
            if (!bodyStarted && heading.equals("2.正文")) bodyStarted = true;
            if (!bodyStarted) continue;
            if (heading.startsWith("3.四级词汇") || heading.startsWith("4.六级词汇")) break;
            body.append(page).append('\n');
        }
        // A sentence can continue on the next page; page breaks are not cue breaks.
        parsePage(body.toString(), result);
        if (!result.isEmpty()) return removeRepeatedTranscript(result);

        // Generic fallback for user-selected PDFs without the supplied document's section headings.
        for (String page : extractedText.split("\\f")) parsePage(page, result);
        return result;
    }

    private static List<String> removeRepeatedTranscript(List<String> lines) {
        List<String> canonical = new ArrayList<>();
        for (String line : lines) canonical.add(line.replaceAll("\\s+", ""));
        for (int period = 10; period <= lines.size() / 2; period++) {
            if (lines.size() % period != 0) continue;
            boolean repeated = true;
            for (int i = period; i < lines.size(); i++) {
                if (!canonical.get(i).equals(canonical.get(i % period))) { repeated = false; break; }
            }
            if (repeated) return new ArrayList<>(lines.subList(0, period));
        }
        // A few study notes truncate the repeated copy by one to three final
        // lines. Keep the longer copy only when the WHOLE shared prefix matches.
        for (int split = Math.max(10, lines.size() / 2 - 2);
             split < Math.min(lines.size() - 9, lines.size() / 2 + 3); split++) {
            int left = split;
            int right = lines.size() - split;
            if (Math.abs(left - right) > 3) continue;
            int matched = 0;
            while (matched < Math.min(left, right)
                    && canonical.get(matched).equals(canonical.get(split + matched))) {
                matched++;
            }
            if (matched < Math.min(left, right) - 3) continue;
            String leftTail = String.join("", canonical.subList(matched, split));
            String rightTail = String.join("", canonical.subList(split + matched, lines.size()));
            // PDFBox can recover the Chinese part of a truncated final cue while
            // its English text is outside the page. Require exact containment.
            if (leftTail.contains(rightTail) || rightTail.contains(leftTail)) {
                return removeRepeatedTranscript(new ArrayList<>(leftTail.length() >= rightTail.length() ? lines.subList(0, split)
                        : lines.subList(split, lines.size())));
            }
            String leftChinese = chineseText(lines.subList(matched, split));
            if (!leftChinese.isEmpty() && leftChinese.equals(chineseText(lines.subList(split + matched, lines.size())))) {
                // The final English line of the repeated copy sometimes overlaps
                // the footer URL. Its Chinese tail and whole preceding body match.
                return removeRepeatedTranscript(new ArrayList<>(lines.subList(0, split)));
            }
        }
        return lines;
    }

    private static String chineseText(List<String> cues) {
        StringBuilder result = new StringBuilder();
        for (String cue : cues) for (int i = 0; i < cue.length(); i++) {
            char value = cue.charAt(i);
            if ((value >= '\u3400' && value <= '\u9fff') || (value >= '\uf900' && value <= '\ufaff')) {
                result.append(value);
            }
        }
        return result.toString();
    }

    private static String firstLine(String page) {
        for (String line : page.split("\\R")) if (!line.trim().isEmpty()) return line.trim();
        return "";
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
