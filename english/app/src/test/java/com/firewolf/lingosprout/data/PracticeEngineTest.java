package com.firewolf.lingosprout.data;

import com.firewolf.lingosprout.learning.PracticeEngine;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class PracticeEngineTest {
    @Test
    public void roundContainsAnswerAndFourUniqueChoices() {
        Topic topic = new Topic("animals", "Animals", "动物", 0, 0, "A");
        for (int i = 0; i < 8; i++) {
            topic.addWord(new Word("animal-" + i, "animals", "animal" + i, "动物", "A", "Example."));
        }
        Word answer = topic.getWords().get(3);

        PracticeEngine.Round round = new PracticeEngine(new Random(7)).createRound(topic, answer);

        assertEquals(answer, round.getAnswer());
        assertEquals(4, round.getChoices().size());
        assertEquals(4, new HashSet<>(round.getChoices()).size());
        assertTrue(round.getChoices().contains(answer));
    }
}
