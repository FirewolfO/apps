package com.firewolf.lingosprout.data;

import java.util.Objects;

public final class Word {
    private final String id;
    private final String topicId;
    private final String english;
    private final String chinese;
    private final String visual;
    private final String example;

    public Word(String id, String topicId, String english, String chinese, String visual, String example) {
        this.id = id;
        this.topicId = topicId;
        this.english = english;
        this.chinese = chinese;
        this.visual = visual;
        this.example = example;
    }

    public String getId() { return id; }
    public String getTopicId() { return topicId; }
    public String getEnglish() { return english; }
    public String getChinese() { return chinese; }
    public String getVisual() { return visual; }
    public String getExample() { return example; }

    @Override
    public boolean equals(Object value) {
        return value instanceof Word && id.equals(((Word) value).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
