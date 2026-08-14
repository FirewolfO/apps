package com.firewolf.lingosprout.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.firewolf.lingosprout.data.ProgressStore;
import com.firewolf.lingosprout.data.Topic;
import com.firewolf.lingosprout.data.Word;
import com.firewolf.lingosprout.data.WordRepository;
import com.firewolf.lingosprout.learning.PracticeEngine;
import com.firewolf.lingosprout.speech.WordSpeaker;

import java.util.List;
import java.util.Random;

@SuppressLint("ViewConstructor")
public final class LingoSproutView extends View {
    private static final float BASE_WIDTH = 390f;
    private static final int CANVAS = rgb("#F7FAF8");
    private static final int WHITE = Color.WHITE;
    private static final int INK = rgb("#20332C");
    private static final int MUTED = rgb("#6F7D77");
    private static final int GREEN = rgb("#42A978");
    private static final int GREEN_DARK = rgb("#237653");
    private static final int GREEN_PALE = rgb("#E7F5ED");
    private static final int CORAL = rgb("#F47B68");
    private static final int YELLOW = rgb("#FFC857");
    private static final int LINE = rgb("#DCE5E0");
    private static final int BLUE_PALE = rgb("#E8F5FC");

    private enum Screen { HOME, LEARN, QUIZ, PROGRESS }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface emoji = Typeface.create("sans-serif", Typeface.NORMAL);
    private final WordRepository repository;
    private final ProgressStore progress;
    private final WordSpeaker speaker;
    private final PracticeEngine practiceEngine = new PracticeEngine(new Random());
    private final List<Topic> topics;

    private Screen screen = Screen.HOME;
    private Topic currentTopic;
    private int currentWordIndex;
    private int learnedThisSession;
    private PracticeEngine.Round round;
    private Word selectedChoice;
    private boolean correctFeedback;
    private float homeScroll;
    private float progressScroll;
    private float touchDownX;
    private float touchDownY;
    private float lastTouchY;
    private boolean dragging;
    private long bounceStart;

    public LingoSproutView(Context context, WordRepository repository, ProgressStore progress,
                           WordSpeaker speaker) {
        super(context);
        this.repository = repository;
        this.progress = progress;
        this.speaker = speaker;
        this.topics = repository.getTopics();
        this.currentTopic = repository.findTopic(progress.getLastTopic());
        this.currentWordIndex = firstUnlearned(currentTopic);
        setBackgroundColor(CANVAS);
        setFocusable(true);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        setContentDescription("LingoSprout English learning app");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = getWidth() / BASE_WIDTH;
        float viewportHeight = getHeight() / scale;
        canvas.save();
        canvas.scale(scale, scale);
        canvas.drawColor(CANVAS);
        switch (screen) {
            case HOME: drawHome(canvas, viewportHeight); break;
            case LEARN: drawLearn(canvas, viewportHeight); break;
            case QUIZ: drawQuiz(canvas, viewportHeight); break;
            case PROGRESS: drawProgress(canvas, viewportHeight); break;
        }
        canvas.restore();
        if (screen == Screen.LEARN) postInvalidateOnAnimation();
    }

    private void drawHome(Canvas canvas, float height) {
        float contentBottom = 278f + ((topics.size() - 1) / 2) * 108f + 94f;
        homeScroll = clamp(homeScroll, 0f, Math.max(0f, contentBottom - height + 84f));

        canvas.save();
        canvas.clipRect(0, 0, BASE_WIDTH, height - 66f);
        canvas.translate(0, -homeScroll);

        drawAvatar(canvas, 28, 22, 44);
        text(canvas, "Hello, explorer!", 84, 42, 14, MUTED, regular);
        text(canvas, "Let's learn something new", 84, 67, 20, INK, medium);
        roundRect(canvas, 324, 24, 44, 42, 8, rgb("#FFF4DA"));
        drawFlame(canvas, 334, 34, 17);
        text(canvas, String.valueOf(progress.getStreak()), 355, 51, 14, rgb("#A66A00"), medium);

        roundRect(canvas, 22, 91, 346, 108, 8, GREEN_DARK);
        text(canvas, "TODAY'S QUEST", 42, 118, 11, rgb("#BDE7D1"), medium);
        int remaining = Math.max(0, 5 - progress.getTodayWords());
        text(canvas, remaining == 0 ? "Quest complete!" : remaining + " new words to go", 42, 151, 24, WHITE, medium);
        text(canvas, Math.min(progress.getTodayWords(), 5) + " of 5 words", 42, 176, 13, rgb("#D7EEE2"), regular);
        drawQuestPath(canvas, 276, 113);

        text(canvas, "Pick a topic", 22, 235, 24, INK, medium);
        text(canvas, repository.getWordCount() + " words across " + topics.size() + " topics", 22, 258, 13, MUTED, regular);

        for (int i = 0; i < topics.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            drawTopicCard(canvas, topics.get(i), 22 + col * 178, 278 + row * 108, 168, 94);
        }
        canvas.restore();
        drawBottomNav(canvas, height, 0);
    }

    private void drawTopicCard(Canvas canvas, Topic topic, float x, float y, float w, float h) {
        roundRect(canvas, x, y, w, h, 8, topic.getPaleColor());
        text(canvas, topic.getVisual(), x + w - 48, y + 42, 34, topic.getAccentColor(), emoji);
        fittedText(canvas, topic.getTitle(), x + 13, y + 64, w - 70, 16, 13, INK, medium);
        int learned = progress.learnedInTopic(topic);
        text(canvas, learned + " / " + topic.getWords().size() + " words", x + 13, y + 82, 11, MUTED, regular);
    }

    private void drawLearn(Canvas canvas, float height) {
        Word word = currentWord();
        drawBackButton(canvas, 22, 18);
        text(canvas, currentTopic.getTitle(), 79, 44, 20, INK, medium);
        text(canvas, (currentWordIndex + 1) + " of " + currentTopic.getWords().size(), 79, 63, 11, MUTED, regular);
        drawHeart(canvas, 335, 25, 20, CORAL);
        text(canvas, "3", 360, 44, 13, CORAL, medium);

        roundRect(canvas, 22, 79, 346, 7, 4, LINE);
        float progressWidth = 346f * (currentWordIndex + 1) / currentTopic.getWords().size();
        roundRect(canvas, 22, 79, progressWidth, 7, 4, GREEN);

        float panelBottom = Math.min(height - 292f, 446f);
        panelBottom = Math.max(panelBottom, 346f);
        roundRect(canvas, 22, 106, 346, panelBottom - 106, 8, currentTopic.getPaleColor());
        drawCloud(canvas, 44, 129, 45);
        drawCloud(canvas, 296, 165, 52);
        drawSpeakerButton(canvas, 311, 121, 42);

        double now = SystemClock.uptimeMillis() / 1000.0;
        float bob = (float) Math.sin(now * 2.2) * 4f;
        float bounce = bounceScale();
        canvas.save();
        canvas.translate(195, (106 + panelBottom) / 2f + 22 + bob);
        canvas.scale(bounce, bounce);
        centeredText(canvas, word.getVisual(), 0, 32, 112, INK, emoji);
        canvas.restore();
        drawSparkle(canvas, 288, 165, 11, YELLOW);
        drawSparkle(canvas, 303, 196, 7, CORAL);

        float wordY = panelBottom + 54;
        centeredFittedText(canvas, word.getEnglish(), 195, wordY, 330, 36, 24, INK, medium);
        centeredText(canvas, word.getChinese(), 195, wordY + 29, 16, GREEN_DARK, medium);
        centeredText(canvas, currentTopic.getSubtitle(), 195, wordY + 53, 12, MUTED, regular);

        float exampleY = wordY + 82;
        roundRect(canvas, 22, exampleY, 346, 62, 8, WHITE);
        strokeRoundRect(canvas, 22, exampleY, 346, 62, 8, LINE, 1.5f);
        drawSpeaker(canvas, 38, exampleY + 19, 22, GREEN_DARK);
        fittedText(canvas, word.getExample(), 77, exampleY + 37, 270, 17, 13, INK, medium);

        float buttonY = height - 69;
        roundRect(canvas, 22, buttonY, 346, 52, 8, GREEN);
        centeredText(canvas, "NEXT", 184, buttonY + 33, 15, WHITE, medium);
        drawArrow(canvas, 236, buttonY + 25, WHITE);
    }

    private void drawQuiz(Canvas canvas, float height) {
        if (round == null) createRound(null);
        drawBackButton(canvas, 22, 18);
        text(canvas, "Quick check", 79, 46, 20, INK, medium);
        roundRect(canvas, 326, 24, 42, 30, 8, GREEN_PALE);
        centeredText(canvas, "1/5", 347, 44, 12, GREEN_DARK, medium);

        text(canvas, "Which one is the", 22, 105, 25, INK, medium);
        fittedText(canvas, round.getAnswer().getEnglish() + "?", 22, 139, 275, 28, 21, GREEN_DARK, medium);
        drawSpeakerButton(canvas, 319, 91, 43);

        float top = 166f;
        float cardH = Math.min(174f, (height - 166f - 154f) / 2f - 10f);
        cardH = Math.max(132f, cardH);
        for (int i = 0; i < round.getChoices().size(); i++) {
            int col = i % 2;
            int row = i / 2;
            drawChoice(canvas, round.getChoices().get(i), 22 + col * 178, top + row * (cardH + 12), 168, cardH);
        }

        float feedbackY = top + 2 * (cardH + 12) + 2;
        if (selectedChoice != null) {
            int bg = correctFeedback ? GREEN_PALE : rgb("#FFF0ED");
            int color = correctFeedback ? GREEN_DARK : CORAL;
            roundRect(canvas, 22, feedbackY, 346, 58, 8, bg);
            drawBadge(canvas, 38, feedbackY + 12, 34, correctFeedback);
            text(canvas, correctFeedback ? "Great job!" : "Almost - try again", 87, feedbackY + 25, 16, color, medium);
            text(canvas, correctFeedback ? "You found it." : "Listen and choose once more.", 87, feedbackY + 44, 12, MUTED, regular);
        }

        if (correctFeedback) {
            float buttonY = height - 69;
            roundRect(canvas, 22, buttonY, 346, 52, 8, GREEN);
            centeredText(canvas, "CONTINUE", 178, buttonY + 33, 15, WHITE, medium);
            drawArrow(canvas, 248, buttonY + 25, WHITE);
        }
    }

    private void drawChoice(Canvas canvas, Word choice, float x, float y, float w, float h) {
        boolean selected = choice.equals(selectedChoice);
        boolean answer = choice.equals(round.getAnswer());
        int background = selected && answer ? GREEN_PALE : WHITE;
        int border = selected ? (answer ? GREEN : CORAL) : LINE;
        roundRect(canvas, x, y, w, h, 8, background);
        strokeRoundRect(canvas, x, y, w, h, 8, border, selected ? 3f : 1.5f);
        centeredText(canvas, choice.getVisual(), x + w / 2, y + h * .57f, Math.min(66, h * .43f), INK, emoji);
        centeredFittedText(canvas, choice.getEnglish(), x + w / 2, y + h - 17, w - 20, 15, 11, answer && selected ? GREEN_DARK : INK, medium);
        if (selected) drawCheckMark(canvas, x + w - 27, y + 17, answer ? GREEN : CORAL, answer);
    }

    private void drawProgress(Canvas canvas, float height) {
        float contentBottom = 272f + topics.size() * 67f;
        progressScroll = clamp(progressScroll, 0f, Math.max(0f, contentBottom - height + 84f));
        canvas.save();
        canvas.clipRect(0, 0, BASE_WIDTH, height - 66f);
        canvas.translate(0, -progressScroll);

        text(canvas, "My progress", 22, 45, 26, INK, medium);
        text(canvas, "Small steps become big wins", 22, 69, 13, MUTED, regular);
        drawSprout(canvas, 328, 21, 38);

        roundRect(canvas, 22, 94, 346, 120, 8, GREEN_DARK);
        text(canvas, "WORDS LEARNED", 42, 121, 11, rgb("#BDE7D1"), medium);
        text(canvas, progress.learnedCount() + "", 42, 168, 42, WHITE, medium);
        text(canvas, "of " + repository.getWordCount(), 102, 167, 14, rgb("#D7EEE2"), regular);
        text(canvas, progress.getStreak() + " day streak", 241, 145, 14, WHITE, medium);
        text(canvas, progress.getCorrectAnswers() + " quiz stars", 241, 170, 12, rgb("#D7EEE2"), regular);

        text(canvas, "Topic progress", 22, 254, 22, INK, medium);
        for (int i = 0; i < topics.size(); i++) {
            Topic topic = topics.get(i);
            float y = 276 + i * 67f;
            text(canvas, topic.getVisual(), 24, y + 34, 26, INK, emoji);
            text(canvas, topic.getTitle(), 62, y + 22, 14, INK, medium);
            int learned = progress.learnedInTopic(topic);
            text(canvas, learned + "/" + topic.getWords().size(), 329, y + 22, 11, MUTED, regular);
            roundRect(canvas, 62, y + 34, 296, 7, 4, LINE);
            float ratio = topic.getWords().isEmpty() ? 0 : learned / (float) topic.getWords().size();
            if (ratio > 0) roundRect(canvas, 62, y + 34, 296 * ratio, 7, 4, topic.getAccentColor());
        }
        canvas.restore();
        drawBottomNav(canvas, height, 3);
    }

    private void drawBottomNav(Canvas canvas, float height, int selected) {
        float y = height - 66f;
        paint.setColor(WHITE);
        canvas.drawRect(0, y, BASE_WIDTH, height, paint);
        line(canvas, 0, y, BASE_WIDTH, y, LINE, 1);
        float[] centers = {49f, 146f, 244f, 341f};
        for (int i = 0; i < centers.length; i++) {
            int color = i == selected ? GREEN_DARK : rgb("#A3AEA9");
            switch (i) {
                case 0: drawHomeIcon(canvas, centers[i], y + 28, color); break;
                case 1: drawBookIcon(canvas, centers[i], y + 28, color); break;
                case 2: drawTrophyIcon(canvas, centers[i], y + 28, color); break;
                default: drawPersonIcon(canvas, centers[i], y + 28, color); break;
            }
            if (i == selected) roundRect(canvas, centers[i] - 11, y + 53, 22, 3, 2, GREEN);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float scale = getWidth() / BASE_WIDTH;
        float x = event.getX() / scale;
        float y = event.getY() / scale;
        float height = getHeight() / scale;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = x;
                touchDownY = y;
                lastTouchY = y;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float delta = lastTouchY - y;
                if (Math.abs(y - touchDownY) > 5) dragging = true;
                if (screen == Screen.HOME) homeScroll += delta;
                if (screen == Screen.PROGRESS) progressScroll += delta;
                lastTouchY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    performClick();
                    handleTap(x, y, height);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void handleTap(float x, float y, float height) {
        if ((screen == Screen.HOME || screen == Screen.PROGRESS) && y >= height - 72) {
            int index = Math.min(3, Math.max(0, (int) (x / (BASE_WIDTH / 4))));
            navigateFromBottom(index);
            return;
        }
        switch (screen) {
            case HOME: handleHomeTap(x, y + homeScroll); break;
            case LEARN: handleLearnTap(x, y, height); break;
            case QUIZ: handleQuizTap(x, y, height); break;
            case PROGRESS: break;
        }
    }

    private void handleHomeTap(float x, float y) {
        if (y >= 91 && y <= 199) {
            openTopic(repository.findTopic(progress.getLastTopic()));
            return;
        }
        if (y < 278) return;
        int col = x >= 200 ? 1 : 0;
        int row = (int) ((y - 278) / 108f);
        int index = row * 2 + col;
        float cardLeft = 22 + col * 178f;
        float cardTop = 278 + row * 108f;
        if (index >= 0 && index < topics.size()
                && x >= cardLeft && x <= cardLeft + 168
                && y <= cardTop + 94) {
            openTopic(topics.get(index));
        }
    }

    private void handleLearnTap(float x, float y, float height) {
        if (x <= 70 && y <= 75) {
            screen = Screen.HOME;
        } else if ((x >= 300 && y <= 180) || (y >= 106 && y <= Math.max(346, height - 292))) {
            bounceStart = SystemClock.uptimeMillis();
            speaker.speak(currentWord().getEnglish());
        } else if (y >= height - 85) {
            Word completed = currentWord();
            progress.markLearned(completed);
            learnedThisSession++;
            currentWordIndex = (currentWordIndex + 1) % currentTopic.getWords().size();
            if (learnedThisSession % 5 == 0) {
                createRound(completed);
                screen = Screen.QUIZ;
            } else {
                speaker.speak(currentWord().getEnglish());
                bounceStart = SystemClock.uptimeMillis();
            }
        }
        announceScreen();
        invalidate();
    }

    private void handleQuizTap(float x, float y, float height) {
        if (x <= 70 && y <= 75) {
            screen = Screen.LEARN;
            invalidate();
            return;
        }
        if (x >= 300 && y >= 75 && y <= 155) {
            speaker.speak(round.getAnswer().getEnglish());
            return;
        }
        float top = 166f;
        float cardH = Math.min(174f, (height - 166f - 154f) / 2f - 10f);
        cardH = Math.max(132f, cardH);
        for (int i = 0; i < round.getChoices().size(); i++) {
            int col = i % 2;
            int row = i / 2;
            RectF card = new RectF(22 + col * 178, top + row * (cardH + 12),
                    190 + col * 178, top + row * (cardH + 12) + cardH);
            if (card.contains(x, y) && !correctFeedback) {
                selectedChoice = round.getChoices().get(i);
                correctFeedback = selectedChoice.equals(round.getAnswer());
                progress.recordAnswer(correctFeedback);
                if (correctFeedback) {
                    speaker.speak("Great job! " + round.getAnswer().getEnglish());
                } else {
                    speaker.speak(round.getAnswer().getEnglish());
                }
                invalidate();
                return;
            }
        }
        if (correctFeedback && y >= height - 85) {
            screen = Screen.LEARN;
            selectedChoice = null;
            correctFeedback = false;
            round = null;
            invalidate();
        }
    }

    private void navigateFromBottom(int index) {
        if (index == 0) screen = Screen.HOME;
        if (index == 1) openTopic(repository.findTopic(progress.getLastTopic()));
        if (index == 2) {
            currentTopic = repository.findTopic(progress.getLastTopic());
            createRound(null);
            screen = Screen.QUIZ;
        }
        if (index == 3) screen = Screen.PROGRESS;
        announceScreen();
        invalidate();
    }

    private void openTopic(Topic topic) {
        currentTopic = topic;
        currentWordIndex = firstUnlearned(topic);
        screen = Screen.LEARN;
        speaker.speak(currentWord().getEnglish());
        bounceStart = SystemClock.uptimeMillis();
    }

    private int firstUnlearned(Topic topic) {
        for (int i = 0; i < topic.getWords().size(); i++) {
            if (!progress.isLearned(topic.getWords().get(i))) return i;
        }
        return 0;
    }

    private Word currentWord() {
        return currentTopic.getWords().get(currentWordIndex);
    }

    private void createRound(Word answer) {
        round = practiceEngine.createRound(currentTopic, answer);
        selectedChoice = null;
        correctFeedback = false;
    }

    public boolean handleBack() {
        if (screen == Screen.HOME) return false;
        screen = Screen.HOME;
        announceScreen();
        invalidate();
        return true;
    }

    private void announceScreen() {
        String label = screen == Screen.HOME ? "Topics" : screen == Screen.LEARN ? currentWord().getEnglish()
                : screen == Screen.QUIZ ? "Quick check" : "My progress";
        setContentDescription(label);
        announceForAccessibility(label);
    }

    private float bounceScale() {
        long elapsed = SystemClock.uptimeMillis() - bounceStart;
        if (elapsed < 0 || elapsed > 600) return 1f;
        return 1f + (float) Math.sin(elapsed / 600f * Math.PI) * .16f;
    }

    private void drawBackButton(Canvas canvas, float x, float y) {
        roundRect(canvas, x, y, 42, 42, 8, WHITE);
        strokeRoundRect(canvas, x, y, 42, 42, 8, LINE, 1.5f);
        strokePaint.setColor(INK);
        strokePaint.setStrokeWidth(2.3f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x + 25, y + 12, x + 15, y + 21, strokePaint);
        canvas.drawLine(x + 15, y + 21, x + 25, y + 30, strokePaint);
    }

    private void drawSpeakerButton(Canvas canvas, float x, float y, float size) {
        roundRect(canvas, x, y, size, size, 8, WHITE);
        drawSpeaker(canvas, x + 11, y + 11, size - 22, GREEN_DARK);
    }

    private void drawSpeaker(Canvas canvas, float x, float y, float size, int color) {
        paint.setColor(color);
        Path path = new Path();
        path.moveTo(x, y + size * .36f);
        path.lineTo(x + size * .28f, y + size * .36f);
        path.lineTo(x + size * .57f, y + size * .12f);
        path.lineTo(x + size * .57f, y + size * .88f);
        path.lineTo(x + size * .28f, y + size * .64f);
        path.lineTo(x, y + size * .64f);
        path.close();
        canvas.drawPath(path, paint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(2);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawArc(x + size * .5f, y + size * .27f, x + size * .87f, y + size * .73f,
                -55, 110, false, strokePaint);
    }

    private void drawAvatar(Canvas canvas, float x, float y, float size) {
        paint.setColor(YELLOW);
        canvas.drawCircle(x + size / 2, y + size / 2, size / 2, paint);
        paint.setColor(rgb("#563D35"));
        canvas.drawCircle(x + size / 2, y + 21, 14, paint);
        paint.setColor(rgb("#F1B894"));
        canvas.drawCircle(x + size / 2, y + 23, 11, paint);
        paint.setColor(INK);
        canvas.drawCircle(x + 18, y + 22, 1.5f, paint);
        canvas.drawCircle(x + 26, y + 22, 1.5f, paint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1.3f);
        strokePaint.setColor(INK);
        canvas.drawArc(x + 17, y + 23, x + 27, y + 31, 10, 160, false, strokePaint);
    }

    private void drawQuestPath(Canvas canvas, float x, float y) {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(3f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setColor(rgb("#65BE91"));
        Path path = new Path();
        path.moveTo(x, y + 48);
        path.cubicTo(x + 18, y + 7, x + 48, y + 62, x + 83, y + 18);
        canvas.drawPath(path, strokePaint);
        drawSparkle(canvas, x + 4, y + 49, 8, rgb("#65BE91"));
        drawSparkle(canvas, x + 47, y + 33, 10, rgb("#65BE91"));
        drawSparkle(canvas, x + 86, y + 18, 12, rgb("#65BE91"));
    }

    private void drawCloud(Canvas canvas, float x, float y, float size) {
        paint.setColor(Color.argb(225, 255, 255, 255));
        canvas.drawCircle(x + size * .25f, y + size * .55f, size * .22f, paint);
        canvas.drawCircle(x + size * .5f, y + size * .38f, size * .28f, paint);
        canvas.drawCircle(x + size * .75f, y + size * .57f, size * .22f, paint);
        canvas.drawRoundRect(x + size * .18f, y + size * .52f, x + size * .82f, y + size * .75f, 7, 7, paint);
    }

    private void drawFlame(Canvas canvas, float x, float y, float size) {
        paint.setColor(CORAL);
        Path path = new Path();
        path.moveTo(x + size / 2, y);
        path.cubicTo(x + size, y + size / 3, x + size, y + size, x + size / 2, y + size);
        path.cubicTo(x, y + size, x, y + size / 2, x + size / 2, y);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void drawHeart(Canvas canvas, float x, float y, float size, int color) {
        paint.setColor(color);
        Path path = new Path();
        path.moveTo(x + size / 2, y + size);
        path.cubicTo(x - 2, y + size / 2, x, y + 2, x + size / 3, y + size / 5);
        path.cubicTo(x + size / 2, y, x + size, y, x + size * 2 / 3, y + size / 5);
        path.cubicTo(x + size + 2, y + size / 2, x + size / 2, y + size, x + size / 2, y + size);
        canvas.drawPath(path, paint);
    }

    private void drawBadge(Canvas canvas, float x, float y, float size, boolean good) {
        paint.setColor(good ? YELLOW : CORAL);
        canvas.drawCircle(x + size / 2, y + size / 2, size / 2, paint);
        if (good) drawSparkle(canvas, x + size / 2, y + size / 2, 8, WHITE);
        else {
            centeredText(canvas, "!", x + size / 2, y + 23, 19, WHITE, medium);
        }
    }

    private void drawCheckMark(Canvas canvas, float cx, float cy, int color, boolean check) {
        paint.setColor(color);
        canvas.drawCircle(cx, cy, 11, paint);
        strokePaint.setColor(WHITE);
        strokePaint.setStrokeWidth(2.4f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        if (check) {
            canvas.drawLine(cx - 5, cy, cx - 1, cy + 4, strokePaint);
            canvas.drawLine(cx - 1, cy + 4, cx + 6, cy - 5, strokePaint);
        } else {
            canvas.drawLine(cx - 4, cy - 4, cx + 4, cy + 4, strokePaint);
            canvas.drawLine(cx + 4, cy - 4, cx - 4, cy + 4, strokePaint);
        }
    }

    private void drawSprout(Canvas canvas, float x, float y, float size) {
        strokePaint.setColor(GREEN_DARK);
        strokePaint.setStrokeWidth(4);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x + size / 2, y + size, x + size / 2, y + size / 3, strokePaint);
        paint.setColor(GREEN);
        canvas.drawOval(x, y + 3, x + size * .58f, y + size * .5f, paint);
        paint.setColor(YELLOW);
        canvas.drawOval(x + size * .45f, y, x + size, y + size * .5f, paint);
    }

    private void drawHomeIcon(Canvas canvas, float cx, float cy, int color) {
        Path path = new Path();
        path.moveTo(cx - 11, cy);
        path.lineTo(cx, cy - 9);
        path.lineTo(cx + 11, cy);
        path.lineTo(cx + 9, cy + 12);
        path.lineTo(cx - 9, cy + 12);
        path.close();
        strokePath(canvas, path, color, 2.5f);
    }

    private void drawBookIcon(Canvas canvas, float cx, float cy, int color) {
        strokeRoundRect(canvas, cx - 14, cy - 10, 12, 21, 3, color, 2.4f);
        strokeRoundRect(canvas, cx + 2, cy - 10, 12, 21, 3, color, 2.4f);
        line(canvas, cx, cy - 7, cx, cy + 12, color, 2.4f);
    }

    private void drawTrophyIcon(Canvas canvas, float cx, float cy, int color) {
        strokeRoundRect(canvas, cx - 8, cy - 11, 16, 15, 3, color, 2.4f);
        strokePaint.setColor(color);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2.4f);
        canvas.drawArc(cx - 16, cy - 8, cx - 5, cy + 5, 90, 180, false, strokePaint);
        canvas.drawArc(cx + 5, cy - 8, cx + 16, cy + 5, -90, 180, false, strokePaint);
        line(canvas, cx, cy + 4, cx, cy + 11, color, 2.4f);
        line(canvas, cx - 7, cy + 12, cx + 7, cy + 12, color, 2.4f);
    }

    private void drawPersonIcon(Canvas canvas, float cx, float cy, int color) {
        strokePaint.setColor(color);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2.4f);
        canvas.drawCircle(cx, cy - 7, 5, strokePaint);
        canvas.drawArc(cx - 11, cy + 1, cx + 11, cy + 18, 180, 180, false, strokePaint);
    }

    private void drawArrow(Canvas canvas, float x, float y, int color) {
        line(canvas, x, y, x + 21, y, color, 2.5f);
        line(canvas, x + 14, y - 6, x + 21, y, color, 2.5f);
        line(canvas, x + 14, y + 6, x + 21, y, color, 2.5f);
    }

    private void drawSparkle(Canvas canvas, float cx, float cy, float outer, int color) {
        paint.setColor(color);
        Path path = new Path();
        for (int i = 0; i < 10; i++) {
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            float radius = i % 2 == 0 ? outer : outer * .42f;
            float x = cx + (float) Math.cos(angle) * radius;
            float y = cy + (float) Math.sin(angle) * radius;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        canvas.drawPath(path, paint);
    }

    private void roundRect(Canvas canvas, float x, float y, float w, float h, float radius, int color) {
        paint.setColor(color);
        canvas.drawRoundRect(x, y, x + w, y + h, radius, radius, paint);
    }

    private void strokeRoundRect(Canvas canvas, float x, float y, float w, float h, float radius,
                                 int color, float width) {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(width);
        strokePaint.setColor(color);
        canvas.drawRoundRect(x, y, x + w, y + h, radius, radius, strokePaint);
    }

    private void strokePath(Canvas canvas, Path path, int color, float width) {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        strokePaint.setStrokeWidth(width);
        strokePaint.setColor(color);
        canvas.drawPath(path, strokePaint);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
        strokePaint.setColor(color);
        strokePaint.setStrokeWidth(width);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(x1, y1, x2, y2, strokePaint);
    }

    private void text(Canvas canvas, String value, float x, float baseline, float size, int color, Typeface typeface) {
        paint.setTypeface(typeface);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, x, baseline, paint);
    }

    private void centeredText(Canvas canvas, String value, float cx, float baseline, float size,
                              int color, Typeface typeface) {
        paint.setTypeface(typeface);
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, cx, baseline, paint);
    }

    private void fittedText(Canvas canvas, String value, float x, float baseline, float maxWidth,
                            float preferredSize, float minSize, int color, Typeface typeface) {
        float size = fitTextSize(value, maxWidth, preferredSize, minSize, typeface);
        text(canvas, value, x, baseline, size, color, typeface);
    }

    private void centeredFittedText(Canvas canvas, String value, float cx, float baseline, float maxWidth,
                                    float preferredSize, float minSize, int color, Typeface typeface) {
        float size = fitTextSize(value, maxWidth, preferredSize, minSize, typeface);
        centeredText(canvas, value, cx, baseline, size, color, typeface);
    }

    private float fitTextSize(String value, float maxWidth, float preferredSize, float minSize,
                              Typeface typeface) {
        paint.setTypeface(typeface);
        float size = preferredSize;
        paint.setTextSize(size);
        while (paint.measureText(value) > maxWidth && size > minSize) {
            size -= 1;
            paint.setTextSize(size);
        }
        return size;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int rgb(String value) {
        return Color.parseColor(value);
    }
}
