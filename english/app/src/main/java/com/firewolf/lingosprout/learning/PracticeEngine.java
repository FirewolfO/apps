package com.firewolf.lingosprout.learning;

import com.firewolf.lingosprout.data.Topic;
import com.firewolf.lingosprout.data.Word;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class PracticeEngine {
    private final Random random;

    public PracticeEngine(Random random) {
        this.random = random;
    }

    public Round createRound(Topic topic, Word preferredAnswer) {
        List<Word> pool = new ArrayList<>(topic.getWords());
        Word answer = preferredAnswer != null && pool.contains(preferredAnswer)
                ? preferredAnswer : pool.get(random.nextInt(pool.size()));
        pool.remove(answer);
        Collections.shuffle(pool, random);
        List<Word> choices = new ArrayList<>();
        choices.add(answer);
        choices.addAll(pool.subList(0, Math.min(3, pool.size())));
        Collections.shuffle(choices, random);
        return new Round(answer, choices);
    }

    public static final class Round {
        private final Word answer;
        private final List<Word> choices;

        Round(Word answer, List<Word> choices) {
            this.answer = answer;
            this.choices = choices;
        }

        public Word getAnswer() { return answer; }
        public List<Word> getChoices() { return choices; }
    }
}
