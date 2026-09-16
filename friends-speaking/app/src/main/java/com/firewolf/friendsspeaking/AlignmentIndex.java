package com.firewolf.friendsspeaking;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Audio-derived timestamps; contains no dialogue or audio. Never guesses missing timings. */
final class AlignmentIndex {
    private static final java.util.regex.Pattern EPISODE_HEADING = java.util.regex.Pattern.compile(
            "^Friends\\s+S\\d{2}E\\d{2}\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    final String episodeKey;
    final String audioSha256;
    final String remoteAudioSha256;
    final String pdfSha256;
    final long durationMs;
    final int sourceLineCount;
    private final List<Entry> entries;

    private AlignmentIndex(String episodeKey, String audioSha256, String remoteAudioSha256, String pdfSha256,
                           long durationMs, List<Entry> entries, int sourceLineCount) {
        this.episodeKey = episodeKey;
        this.audioSha256 = audioSha256;
        this.remoteAudioSha256 = remoteAudioSha256;
        this.pdfSha256 = pdfSha256;
        this.durationMs = durationMs;
        this.entries = entries;
        this.sourceLineCount = sourceLineCount;
    }

    static AlignmentIndex read(InputStream input, String episodeKey) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        try {
            String header = reader.readLine();
            String[] fields = header == null ? new String[0] : header.split("\t");
            if (fields.length < 5 || fields.length > 7 || !fields[0].equals("FA1") || !fields[1].equals(episodeKey)
                    || !hash(fields[2]) || !hash(fields[3])) throw new IOException("Invalid alignment header");
            long duration = Long.parseLong(fields[4]);
            int prefix = -1;
            String remoteHash = fields[2];
            boolean hasRemote = false;
            for (int i = 5; i < fields.length; i++) {
                if (fields[i].matches("prefix=[1-9][0-9]*") && prefix < 0) {
                    prefix = Integer.parseInt(fields[i].substring(7));
                } else if (fields[i].startsWith("remote=") && hash(fields[i].substring(7)) && !hasRemote) {
                    remoteHash = fields[i].substring(7);
                    hasRemote = true;
                } else throw new IOException("Invalid alignment metadata");
            }
            if (duration <= 0) throw new IOException("Invalid duration");
            List<Entry> entries = new ArrayList<>();
            long previous = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split("\t");
                if (values.length != 4 || !hash(values[0])) throw new IOException("Invalid cue");
                long start = Long.parseLong(values[1]);
                long end = Long.parseLong(values[2]);
                boolean missing = start == -1 && end == -1;
                if (prefix >= 0 && entries.size() >= prefix && !missing) throw new IOException("Cue outside source scope");
                if (!missing && (start < 0 || start < previous || end <= start || end > duration)) {
                    throw new IOException("Invalid cue timing");
                }
                if (!missing) previous = start;
                entries.add(new Entry(values[0], start, end));
            }
            if (entries.isEmpty()) throw new IOException("Empty alignment");
            if (prefix > entries.size()) throw new IOException("Invalid source scope");
            return new AlignmentIndex(episodeKey, fields[2], remoteHash, fields[3], duration, entries,
                    prefix < 0 ? entries.size() : prefix);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid alignment number", exception);
        }
    }

    List<SubtitleCue> bind(List<String> transcript) throws IOException {
        if (transcript.size() != entries.size()) throw new IOException("Transcript changed");
        List<SubtitleCue> cues = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.textHash.equals(textHash(transcript.get(i)))) throw new IOException("Transcript changed");
            if (entry.start >= 0 && !isEpisodeHeading(transcript.get(i))) {
                cues.add(new SubtitleCue(entry.start, entry.end, transcript.get(i)));
            }
        }
        // Adjacent speakers may overlap acoustically; the next row owns its start.
        for (int i = 0; i + 1 < cues.size(); i++) {
            SubtitleCue cue = cues.get(i);
            long end = Math.min(cue.endMs, cues.get(i + 1).startMs);
            if (end <= cue.startMs) throw new IOException("Ambiguous cue timing");
            cues.set(i, new SubtitleCue(cue.startMs, end, cue.text));
        }
        return Collections.unmodifiableList(cues);
    }

    boolean matchesAudio(String sha256, long duration) {
        return (audioSha256.equals(sha256) || remoteAudioSha256.equals(sha256))
                && (duration <= 0 || Math.abs(durationMs - duration) < 1_000L);
    }

    boolean partialAudio() { return sourceLineCount < entries.size(); }

    static boolean isEpisodeHeading(String text) {
        return EPISODE_HEADING.matcher(text).find();
    }

    static String textHash(String value) {
        try {
            byte[] bytes = value.replaceAll("\\s+", "").getBytes(StandardCharsets.UTF_8);
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(Character.forDigit((value & 0xff) >>> 4, 16));
            output.append(Character.forDigit(value & 15, 16));
        }
        return output.toString();
    }

    private static boolean hash(String value) { return value.matches("[a-f0-9]{64}"); }

    private static final class Entry {
        final String textHash;
        final long start;
        final long end;
        Entry(String textHash, long start, long end) {
            this.textHash = textHash;
            this.start = start;
            this.end = end;
        }
    }
}
