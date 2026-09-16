package com.firewolf.friendsspeaking;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Keeps parsed remote PDFs so each episode only pays the extraction cost once. */
final class TranscriptCache {
    private static final String SEPARATOR = "\f";

    private TranscriptCache() {}

    static List<String> read(Context context, Episode episode) {
        File file = file(context, episode);
        if (!file.isFile() || file.length() <= 0 || file.length() > 4L * 1024L * 1024L) {
            return Collections.emptyList();
        }
        try {
            String value = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (value.trim().isEmpty()) return Collections.emptyList();
            return new ArrayList<>(Arrays.asList(value.split(SEPARATOR, -1)));
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    static void write(Context context, Episode episode, List<String> lines) {
        if (lines == null || lines.isEmpty()) return;
        File target = file(context, episode);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;
        File temporary = new File(parent, target.getName() + ".tmp");
        try {
            Files.write(temporary.toPath(), String.join(SEPARATOR, lines).getBytes(StandardCharsets.UTF_8));
            if (!temporary.renameTo(target)) Files.deleteIfExists(temporary.toPath());
        } catch (Exception ignored) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (Exception ignoredAgain) {}
        }
    }

    private static File file(Context context, Episode episode) {
        // v1 could contain contents/synopsis/vocabulary; never reuse that data.
        return new File(new File(context.getFilesDir(), "transcript-cache-v2"), episode.key + ".txt");
    }
}
