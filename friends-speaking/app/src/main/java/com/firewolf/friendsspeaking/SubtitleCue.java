package com.firewolf.friendsspeaking;

public final class SubtitleCue {
    public final long startMs;
    public final long endMs;
    public final String text;

    SubtitleCue(long startMs, long endMs, String text) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
    }
}
