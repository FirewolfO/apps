package com.firewolf.lingosprout.speech;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

public final class WordSpeaker implements TextToSpeech.OnInitListener {
    private final TextToSpeech textToSpeech;
    private boolean ready;

    public WordSpeaker(Context context) {
        textToSpeech = new TextToSpeech(context.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
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

    public void stop() { textToSpeech.stop(); }
    public void shutdown() { textToSpeech.shutdown(); }
}
