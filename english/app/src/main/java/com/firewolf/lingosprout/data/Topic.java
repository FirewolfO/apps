package com.firewolf.lingosprout.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Topic {
    private final String id;
    private final String title;
    private final String subtitle;
    private final int accentColor;
    private final int paleColor;
    private final String visual;
    private final List<Word> words = new ArrayList<>();

    public Topic(String id, String title, String subtitle, int accentColor, int paleColor, String visual) {
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.accentColor = accentColor;
        this.paleColor = paleColor;
        this.visual = visual;
    }

    void addWord(Word word) { words.add(word); }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getSubtitle() { return subtitle; }
    public int getAccentColor() { return accentColor; }
    public int getPaleColor() { return paleColor; }
    public String getVisual() { return visual; }
    public List<Word> getWords() { return Collections.unmodifiableList(words); }
}
