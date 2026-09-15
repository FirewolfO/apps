package com.firewolf.friendsspeaking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Episode {
    private static final int[] EPISODES_PER_SEASON = {24, 24, 25, 24, 24, 25, 24, 24, 24, 18};
    private static final List<Episode> ALL = buildAll();

    public final int season;
    public final int number;
    public final String key;

    private Episode(int season, int number) {
        this.season = season;
        this.number = number;
        this.key = String.format(Locale.ROOT, "S%02dE%02d", season, number);
    }

    public String displayTitle() {
        return "第 " + season + " 季 · 第 " + number + " 集";
    }

    public static Episode of(int season, int number) {
        if (season < 1 || season > EPISODES_PER_SEASON.length
                || number < 1 || number > EPISODES_PER_SEASON[season - 1]) return null;
        int offset = 0;
        for (int index = 0; index < season - 1; index++) offset += EPISODES_PER_SEASON[index];
        return ALL.get(offset + number - 1);
    }

    public static List<Episode> season(int season) {
        List<Episode> result = new ArrayList<>();
        for (Episode episode : ALL) if (episode.season == season) result.add(episode);
        return Collections.unmodifiableList(result);
    }

    public static List<Episode> all() {
        return ALL;
    }

    public static int seasonCount() {
        return EPISODES_PER_SEASON.length;
    }

    private static List<Episode> buildAll() {
        List<Episode> result = new ArrayList<>();
        for (int season = 1; season <= EPISODES_PER_SEASON.length; season++) {
            for (int episode = 1; episode <= EPISODES_PER_SEASON[season - 1]; episode++) {
                result.add(new Episode(season, episode));
            }
        }
        return Collections.unmodifiableList(result);
    }
}
