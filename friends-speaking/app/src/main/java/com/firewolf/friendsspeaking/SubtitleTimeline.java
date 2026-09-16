package com.firewolf.friendsspeaking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds a seekable content-progress timeline for transcript PDFs that contain no time codes. */
final class SubtitleTimeline {
    private SubtitleTimeline() {}

    static List<SubtitleCue> distribute(List<String> lines, long durationMs) {
        if (lines == null || lines.isEmpty()) return Collections.emptyList();
        long duration = Math.max(30_000L, durationMs);
        long startMargin = Math.min(4_000L, duration / 50L);
        long endMargin = Math.min(6_000L, duration / 40L);
        long usable = Math.max(lines.size(), duration - startMargin - endMargin);

        double total = 0d;
        double[] weights = new double[lines.size()];
        for (int index = 0; index < lines.size(); index++) {
            weights[index] = weight(lines.get(index));
            total += weights[index];
        }

        List<SubtitleCue> result = new ArrayList<>(lines.size());
        double elapsed = 0d;
        for (int index = 0; index < lines.size(); index++) {
            long start = startMargin + Math.round(usable * elapsed / total);
            elapsed += weights[index];
            long end = startMargin + Math.round(usable * elapsed / total);
            if (end <= start) end = start + 1;
            result.add(new SubtitleCue(start, Math.min(duration, end), lines.get(index)));
        }
        return Collections.unmodifiableList(result);
    }

    private static double weight(String text) {
        int latinWords = 0;
        boolean inWord = false;
        int cjk = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            boolean latin = (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
            if (latin && !inWord) latinWords++;
            inWord = latin;
            Character.UnicodeBlock block = Character.UnicodeBlock.of(value);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) cjk++;
        }
        return Math.max(3d, latinWords + cjk * 0.22d);
    }
}
