package com.firewolf.lingosprout.speech;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public final class WordSpeaker implements TextToSpeech.OnInitListener {
    private static final String LOG_TAG = "LingoSproutSpeech";

    private final Context context;
    private TextToSpeech textToSpeech;
    private boolean ready;
    private boolean closed;

    public WordSpeaker(Context context) {
        this.context = context.getApplicationContext();
    }

    public void initialize() {
        if (closed || textToSpeech != null) return;
        try {
            textToSpeech = new TextToSpeech(context, this);
        } catch (RuntimeException error) {
            ready = false;
            textToSpeech = null;
            Log.w(LOG_TAG, "Text-to-speech is unavailable", error);
        }
    }

    @Override
    public void onInit(int status) {
        if (closed) {
            if (textToSpeech != null) textToSpeech.shutdown();
            return;
        }
        ready = status == TextToSpeech.SUCCESS;
        if (ready) {
            textToSpeech.setLanguage(Locale.US);
            textToSpeech.setSpeechRate(0.82f);
            textToSpeech.setPitch(1.08f);
        }
    }

    public void speak(String text) {
        if (ready) textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lingosprout-word");
    }

    public void stop() {
        if (textToSpeech != null) textToSpeech.stop();
    }

    public void shutdown() {
        closed = true;
        ready = false;
        if (textToSpeech != null) textToSpeech.shutdown();
    }
}
