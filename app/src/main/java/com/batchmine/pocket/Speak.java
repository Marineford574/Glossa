package com.batchmine.pocket;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The word spoken by the phone's own voice, saved as a sound file for the card. Call it off the screen's thread. */
final class Speak {
    private Speak() { }

    static Locale locale(String lang) {
        switch (lang) {
            case "it": return Locale.ITALIAN;
            case "de": return Locale.GERMAN;
            case "hr": return new Locale("hr", "HR");
            default: return new Locale(lang);
        }
    }

    static File toFile(Context c, String lang, String text, File out) throws IOException {
        final CountDownLatch ready = new CountDownLatch(1), done = new CountDownLatch(1);
        final int[] state = {-1, 0};          // init status, 1 = made
        final TextToSpeech[] tts = new TextToSpeech[1];
        tts[0] = new TextToSpeech(c.getApplicationContext(), st -> { state[0] = st; ready.countDown(); });
        try {
            if (!ready.await(8, TimeUnit.SECONDS) || state[0] != TextToSpeech.SUCCESS) throw new IOException("the phone has no speech voice");
            int av = tts[0].isLanguageAvailable(locale(lang));
            if (av < TextToSpeech.LANG_AVAILABLE) throw new IOException("the phone has no " + lang + " voice installed");
            tts[0].setLanguage(locale(lang));
            tts[0].setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { }
                @Override public void onDone(String id) { state[1] = 1; done.countDown(); }
                @Override public void onError(String id) { done.countDown(); }
            });
            if (tts[0].synthesizeToFile(text, null, out, "w") != TextToSpeech.SUCCESS) throw new IOException("the voice did not start");
            if (!done.await(15, TimeUnit.SECONDS) || state[1] != 1 || !out.isFile() || out.length() < 200) throw new IOException("the voice made no sound");
            return out;
        } catch (InterruptedException e) {
            throw new IOException("interrupted");
        } finally {
            tts[0].shutdown();
        }
    }

    private static TextToSpeech live;
    private static boolean liveReady;
    private static String pendingText, pendingLang;

    /** Says the text aloud now (the phone's voice); one voice engine is kept for the next time. */
    static void say(Context c, String lang, String text) {
        pendingText = text; pendingLang = lang;
        if (live == null) {
            live = new TextToSpeech(c.getApplicationContext(), st -> { liveReady = st == TextToSpeech.SUCCESS; if (liveReady) go(); });
        } else if (liveReady) go();
    }

    private static void go() {
        if (live.isLanguageAvailable(locale(pendingLang)) < TextToSpeech.LANG_AVAILABLE) return;
        live.setLanguage(locale(pendingLang));
        live.speak(pendingText, TextToSpeech.QUEUE_FLUSH, null, "say");
    }
}
