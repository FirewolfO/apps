package com.firewolf.friendsspeaking;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SubtitleParser {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Pattern TIMING = Pattern.compile(
            "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*"
                    + "(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})");

    private SubtitleParser() {}

    public static List<SubtitleCue> parse(InputStream input) throws Exception {
        byte[] bytes = read(input);
        String raw = decode(bytes).replace("\r\n", "\n").replace('\r', '\n').replace("\uFEFF", "");
        String[] blocks = raw.split("\\n\\s*\\n");
        List<SubtitleCue> result = new ArrayList<>();
        for (String block : blocks) {
            String[] lines = block.trim().split("\\n");
            int timingLine = -1;
            Matcher timing = null;
            for (int index = 0; index < Math.min(lines.length, 3); index++) {
                Matcher candidate = TIMING.matcher(lines[index]);
                if (candidate.find()) {
                    timingLine = index;
                    timing = candidate;
                    break;
                }
            }
            if (timing == null || timingLine + 1 >= lines.length) continue;
            long start = time(timing, 1);
            long end = time(timing, 5);
            if (end <= start) continue;
            StringBuilder text = new StringBuilder();
            for (int index = timingLine + 1; index < lines.length; index++) {
                String line = clean(lines[index]);
                if (line.isEmpty()) continue;
                if (text.length() > 0) text.append('\n');
                text.append(line);
            }
            if (text.length() > 0) result.add(new SubtitleCue(start, end, text.toString()));
        }
        result.sort(Comparator.comparingLong(cue -> cue.startMs));
        return result;
    }

    public static int activeIndex(List<SubtitleCue> cues, long positionMs) {
        int candidate = indexAtOrBefore(cues, positionMs);
        if (candidate >= 0 && positionMs < cues.get(candidate).endMs) return candidate;
        return -1;
    }

    /** Returns the last cue that has started, so gaps never reveal the next line too early. */
    public static int indexAtOrBefore(List<SubtitleCue> cues, long positionMs) {
        int low = 0;
        int high = cues.size() - 1;
        int candidate = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (cues.get(middle).startMs <= positionMs) {
                candidate = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return candidate;
    }

    private static long time(Matcher value, int offset) {
        long hours = value.group(offset) == null ? 0 : Long.parseLong(value.group(offset));
        long minutes = Long.parseLong(value.group(offset + 1));
        long seconds = Long.parseLong(value.group(offset + 2));
        long millis = Long.parseLong(value.group(offset + 3));
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis;
    }

    private static String clean(String line) {
        return line.replace("\\N", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    private static byte[] read(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > MAX_BYTES) throw new IllegalArgumentException("字幕文件不能超过 8 MB");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) {
            return Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString();
        }
    }
}
