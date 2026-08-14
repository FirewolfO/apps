package com.firewolf.lingosprout.data;

import android.content.Context;
import android.graphics.Color;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WordRepository {
    private final List<Topic> topics;

    private WordRepository(List<Topic> topics) {
        this.topics = topics;
    }

    public static WordRepository load(Context context) throws IOException {
        Map<String, List<Word>> parsed;
        try (InputStreamReader reader = new InputStreamReader(
                context.getAssets().open("words.tsv"), StandardCharsets.UTF_8)) {
            parsed = WordCatalogParser.parse(reader);
        }
        List<Topic> topics = topicDefinitions();
        for (Topic topic : topics) {
            List<Word> words = parsed.get(topic.getId());
            if (words == null || words.isEmpty()) {
                throw new IOException("Missing topic data: " + topic.getId());
            }
            for (Word word : words) topic.addWord(word);
        }
        if (parsed.size() != topics.size()) {
            throw new IOException("Unexpected topic count: " + parsed.size());
        }
        return new WordRepository(topics);
    }

    public List<Topic> getTopics() { return topics; }

    public int getWordCount() {
        int count = 0;
        for (Topic topic : topics) count += topic.getWords().size();
        return count;
    }

    public Topic findTopic(String id) {
        for (Topic topic : topics) if (topic.getId().equals(id)) return topic;
        return topics.get(0);
    }

    private static List<Topic> topicDefinitions() {
        List<Topic> topics = new ArrayList<>();
        topics.add(new Topic("animals", "Animals", "动物世界", Color.rgb(88, 169, 223), Color.rgb(232, 245, 252), "🐘"));
        topics.add(new Topic("food", "Food", "食物与饮品", Color.rgb(244, 123, 104), Color.rgb(255, 241, 232), "🍎"));
        topics.add(new Topic("home", "My Home", "我的家", Color.rgb(140, 115, 201), Color.rgb(241, 236, 250), "🏠"));
        topics.add(new Topic("transport", "On the Move", "交通出行", Color.rgb(218, 157, 38), Color.rgb(255, 245, 218), "🚌"));
        topics.add(new Topic("nature", "Nature", "自然与天气", Color.rgb(66, 169, 120), Color.rgb(231, 245, 237), "🌳"));
        topics.add(new Topic("actions", "Actions", "常用动作", Color.rgb(233, 106, 117), Color.rgb(251, 234, 236), "🏃"));
        topics.add(new Topic("people", "Family & People", "家人与人物", Color.rgb(85, 158, 180), Color.rgb(232, 244, 247), "👨‍👩‍👧"));
        topics.add(new Topic("school", "At School", "学校生活", Color.rgb(225, 154, 47), Color.rgb(255, 246, 225), "🎒"));
        topics.add(new Topic("body", "Body & Health", "身体与健康", Color.rgb(215, 104, 130), Color.rgb(251, 235, 240), "💪"));
        topics.add(new Topic("clothes", "Clothes", "衣物穿戴", Color.rgb(119, 112, 196), Color.rgb(239, 238, 251), "👕"));
        topics.add(new Topic("colors", "Colors & Shapes", "颜色与形状", Color.rgb(62, 147, 178), Color.rgb(230, 244, 248), "🎨"));
        topics.add(new Topic("feelings", "Feelings & Time", "感受与时间", Color.rgb(224, 121, 79), Color.rgb(252, 239, 232), "😊"));
        return topics;
    }
}
