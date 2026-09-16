package com.firewolf.friendsspeaking;

import android.net.Uri;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Maps the private media server's directory layout without publishing any media in the APK. */
final class RemoteMediaCatalog {
    private static final Set<String> MISSING_AUDIO = new HashSet<>(Arrays.asList(
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
        return build(
                "老友记.Friends.全10季音频",
                String.format(Locale.ROOT, "老友记.Friends.S%02d", episode.season),
                "老友记.friends." + episode.key + ".wma");
    }

    static Uri subtitle(Episode episode) {
        if (!hasSubtitle(episode)) return null;
        return build(
                "老友记.Friends.全10季字幕",
                String.format(Locale.ROOT, "老友记.Friends.S%02d.240806", episode.season),
                "老友记.friends." + episode.key + ".chs&eng.240806.pdf");
    }

    private static Uri build(String... segments) {
        Uri.Builder builder = Uri.parse(BuildConfig.MEDIA_BASE_URL).buildUpon();
        for (String segment : segments) builder.appendPath(segment);
        return builder.build();
    }
}
