package com.firewolf.friendsspeaking;

import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Versioned HTTPS media; the APK contains timing indexes, not audio or dialogue. */
final class RemoteMediaCatalog {
    private static final Set<String> MISSING_AUDIO = new HashSet<>(Arrays.asList(
            // These eight files were not in the processed cache and the original
            // source was unavailable during migration. Do not advertise dead URLs.
            "S02E24", "S03E25", "S04E24", "S05E24", "S06E25", "S07E24", "S08E24", "S09E24",
            "S10E12", "S10E18"));
    private static final Set<String> MISSING_SUBTITLES = new HashSet<>(Arrays.asList(
            "S02E24", "S03E25", "S04E24", "S05E24", "S06E25", "S07E24",
            "S08E24", "S09E24", "S10E12", "S10E18"));

    private RemoteMediaCatalog() {}

    static boolean hasAudio(Episode episode) {
        return episode != null && !MISSING_AUDIO.contains(episode.key);
    }

    static boolean hasSubtitle(Episode episode) {
        return episode != null && !MISSING_SUBTITLES.contains(episode.key);
    }

    static Uri audio(Episode episode) {
        if (!hasAudio(episode)) return null;
        return build(episode.key, "audio.m4a");
    }

    static Uri subtitle(Episode episode) {
        if (!hasSubtitle(episode)) return null;
        return build(episode.key, "transcript.txt");
    }

    private static Uri build(String... segments) {
        Uri.Builder builder = Uri.parse(BuildConfig.MEDIA_BASE_URL).buildUpon();
        for (String segment : segments) builder.appendPath(segment);
        return builder.build();
    }
}
