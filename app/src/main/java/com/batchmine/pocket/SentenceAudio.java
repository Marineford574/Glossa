package com.batchmine.pocket;

import android.app.Activity;
import android.media.MediaPlayer;

import com.batchmine.pocket.core.PcLink;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Feature 10: the sentence of a saved word, spoken by the PC's voice (Piper), kept on the phone so it plays with no connection. A sentence is
 *  fetched once (GET /api/tts on the PC) and stored by a hash of language + text; with "Keep the audio of saved sentences" on, each sync fetches
 *  the missing ones. */
final class SentenceAudio {
    private SentenceAudio() { }

    private static MediaPlayer player;

    static File file(String lang, String sentence) {
        String h;
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest((lang + "|" + sentence.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            h = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { h = Integer.toHexString((lang + sentence).hashCode()); }
        return new File(App.get().dir("sentence_audio"), h + ".ogg");
    }

    static boolean has(String lang, String sentence) { return file(lang, sentence).length() > 200; }

    static boolean auto() { return App.get().prefs().getBoolean("sa_auto", false); }

    /** The saved words of the language whose sentence has no audio here yet. */
    static List<String> missing(String lang) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> s : App.get().store(lang).saved()) {
            String sn = com.batchmine.pocket.core.Json.str(s.get("sentence"), "").trim();
            if (!sn.isEmpty() && sn.length() < 600 && !has(lang, sn) && !out.contains(sn)) out.add(sn);
        }
        return out;
    }

    /** Fetches ONE sentence (off the screen's thread); false when the PC did not make it. */
    static boolean fetch(PcLink pc, String lang, String sentence) {
        File f = file(lang, sentence), tmp = new File(f.getPath() + ".part");
        try {
            pc.download("/api/tts?lang=" + PcLink.enc(lang) + "&text=" + PcLink.enc(sentence), tmp, null);
            if (tmp.length() > 200 && (tmp.renameTo(f) || (f.delete() && tmp.renameTo(f)))) return true;
        } catch (IOException | RuntimeException e) { android.util.Log.w("Glossa", "sentence audio", e); }
        tmp.delete();
        return false;
    }

    /** Fetches the missing ones (at most LIMIT per call), then runs DONE on the screen's thread with how many arrived. */
    static void fetchMissing(String lang, int limit, java.util.function.IntConsumer done) {
        App app = App.get();
        PcLink pc = app.pc();
        if (pc == null) { if (done != null) app.post(() -> done.accept(0)); return; }
        app.bg(() -> {
            int n = 0, fails = 0;
            for (String sn : missing(lang)) {
                if (n >= limit || fails >= 3) break;                 // three in a row failing: the PC is not there, stop
                if (fetch(pc, lang, sn)) { n++; fails = 0; } else fails++;
            }
            final int got = n;
            if (done != null) app.post(() -> done.accept(got));
        });
    }

    /** Plays the sentence: from the file when it is here, else asks the PC (and keeps the answer). */
    static void play(Activity a, String lang, String sentence) {
        File f = file(lang, sentence);
        if (f.length() > 200) { start(a, f); return; }
        PcLink pc = App.get().pc();
        if (pc == null) { Ui.toast(a, "No audio kept for this sentence yet: link the PC and download it"); return; }
        Ui.toast(a, "Asking the PC …");
        App.get().bg(() -> {
            boolean ok = fetch(pc, lang, sentence);
            App.get().post(() -> { if (a.isFinishing()) return; if (ok) start(a, f); else Ui.toast(a, "The PC could not make this audio"); });
        });
    }

    private static void start(Activity a, File f) {
        try {
            if (player != null) { try { player.stop(); } catch (RuntimeException ignored) { } player.release(); }
            player = new MediaPlayer();
            player.setDataSource(f.getPath());
            player.setOnCompletionListener(m -> { m.release(); if (player == m) player = null; });
            player.prepare();
            player.start();
        } catch (IOException | RuntimeException e) { Ui.toast(a, "This audio does not play"); f.delete(); player = null; }
    }

    static void register() {
        Extras.syncPart("sentence_audio", new Extras.SyncPart() {
            @Override public void pulled(Map<String, Object> extra) {
                if (!auto()) return;
                String lang = App.get().lang();
                PcLink pc = App.get().pc();
                if (pc == null) return;
                int n = 0;
                for (String sn : missing(lang)) {                 // on the sync thread: a few per sync, the rest next time
                    if (n >= 40) break;
                    if (fetch(pc, lang, sn)) n++; else break;
                }
            }
        });
    }
}
