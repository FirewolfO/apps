package com.firewolf.lingosprout.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;

public final class ProgressStore {
    private static final String PREFS = "learning_progress";
    private final SharedPreferences preferences;

    public ProgressStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        touchToday();
    }

    public void markLearned(Word word) {
        Set<String> learned = new HashSet<>(preferences.getStringSet("learned", new HashSet<>()));
        boolean newlyLearned = learned.add(word.getId());
        SharedPreferences.Editor editor = preferences.edit()
                .putStringSet("learned", learned)
                .putString("last_topic", word.getTopicId());
        if (newlyLearned) editor.putInt("today_words", preferences.getInt("today_words", 0) + 1);
        editor.apply();
    }

    public boolean isLearned(Word word) {
        return preferences.getStringSet("learned", new HashSet<>()).contains(word.getId());
    }

    public int learnedCount() {
        return preferences.getStringSet("learned", new HashSet<>()).size();
    }

    public int learnedInTopic(Topic topic) {
        int count = 0;
        for (Word word : topic.getWords()) if (isLearned(word)) count++;
        return count;
    }

    public int getTodayWords() { return preferences.getInt("today_words", 0); }
    public int getStreak() { return preferences.getInt("streak", 1); }
    public String getLastTopic() { return preferences.getString("last_topic", "animals"); }

    public void recordAnswer(boolean correct) {
        SharedPreferences.Editor editor = preferences.edit()
                .putInt("attempted_answers", preferences.getInt("attempted_answers", 0) + 1);
        if (correct) editor.putInt("correct_answers", preferences.getInt("correct_answers", 0) + 1);
        editor.apply();
    }

    public int getCorrectAnswers() { return preferences.getInt("correct_answers", 0); }
    public int getAttemptedAnswers() { return preferences.getInt("attempted_answers", 0); }

    private void touchToday() {
        int today = dayKey(0);
        int lastDay = preferences.getInt("last_day", -1);
        if (lastDay == today) return;
        int streak = lastDay == dayKey(-1) ? preferences.getInt("streak", 0) + 1 : 1;
        preferences.edit().putInt("last_day", today).putInt("streak", streak).putInt("today_words", 0).apply();
    }

    private static int dayKey(int offset) {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_YEAR, offset);
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR);
    }
}
