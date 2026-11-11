package com.batchmine.pocket;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.batchmine.pocket.core.Json;
import com.batchmine.pocket.core.WordStore;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Making a card from a word: straight into AnkiDroid (picture and sound cut on the phone), or kept for the PC, which makes
 * it at the next sync (cut from its own copy of the video, with its usual fields, audio and definitions).
 */
public final class Cards {
    private Cards() { }

    public static final class Ctx {
        public final String text; public final double start, end;
        public Ctx(String text, double start, double end) { this.text = text; this.start = start; this.end = end; }
    }

    public static final class Request {
        public String lang = "", kind = "text", word = "", surface = "", sentence = "", translation = "", ipa = "", title = "";
        public List<String> glosses = new ArrayList<>();
        /** The single meanings ticked in the word card, by the language of the definitions (the PC's short definition fields). */
        public Map<String, List<String>> picked = new LinkedHashMap<>();
        public String defHtml = "", freq = "", wordAudio = "";   // all dictionaries' meanings (HTML), frequency rank, "yes": add the word spoken
        public String video = "", videoName = "";          // the video (a content:// or file:// link) and its file name
        public double start, end, frameAt = -1;
        /** Lines around the sentence that the popup can add to it (nearest first); for a video each has its time. */
        public List<Ctx> before = new ArrayList<>(), after = new ArrayList<>();

        public Request copy() {
            Request r = new Request();
            r.lang = lang; r.kind = kind; r.word = word; r.surface = surface; r.sentence = sentence; r.translation = translation;
            r.ipa = ipa; r.title = title; r.glosses = new ArrayList<>(glosses); r.picked = new LinkedHashMap<>(picked); r.video = video; r.videoName = videoName; r.defHtml = defHtml; r.freq = freq; r.wordAudio = wordAudio;
            r.start = start; r.end = end; r.frameAt = frameAt; r.before = before; r.after = after;
            return r;
        }
    }

    /** What a card can carry, and the field names that usually hold it. */
    public static final String[][] ROLES = {
            {"word", "Word", "word|target|expression|vocab|front|lemma|parola|wort"},
            {"sentence", "Sentence", "sentence|context|example|frase|satz|line"},
            {"translation", "Sentence translation", "translation|english|meaning of sentence|traduzione|übersetzung"},
            {"definition", "Definition", "definition|meaning|glossary|gloss|back|significato|bedeutung"},
            {"short", "Short definition (the meanings you tick)", "short definition|definizione|definition 1|short|brief|kurz"},
            {"ipa", "Pronunciation (IPA)", "ipa|pronunciation|reading|pronuncia"},
            {"audio", "Sentence audio", "audio|sound|sentence audio|clip"},
            {"picture", "Picture", "picture|image|screenshot|immagine|bild"},
            {"wordaudio", "Word spoken (phone voice)", "word audio|pronunciation audio|audio word|wordaudio"},
            {"frequency", "Frequency", "frequency|freq|rank|häufigkeit|frequenza"},
            {"source", "Source", "source|from|fonte|quelle"}};

    public static boolean useAnki() {
        String m = App.get().prefs().getString("card_mode", "");
        return m.isEmpty() ? AnkiDroid.installed(App.get()) : m.equals("anki");
    }

    public static void make(Activity a, Request r, Runnable done) {
        App app = App.get();
        WordStore ws = app.store(r.lang);
        if (!useAnki()) { keepForPc(a, r, done, null); return; }
        if (!AnkiDroid.installed(a)) { keepForPc(a, r, done, "AnkiDroid is not installed"); return; }
        if (!AnkiDroid.allowed(a)) {
            a.requestPermissions(new String[]{AnkiDroid.PERM}, 7);
            Ui.toast(a, "Allow access to AnkiDroid, then tap Make card again");
            return;
        }
        SharedPreferences p = app.prefs();
        long mid = p.getLong("anki_model_" + r.lang, -1), did = p.getLong("anki_deck_" + r.lang, -1);
        if (mid < 0 || did < 0) { setup(a, r.lang, () -> make(a, r, done)); return; }
        Ui.toast(a, "Making the card…");
        app.bg(() -> {
            try {
                Map<String, Integer> map = fieldMap(r.lang);
                int nFields = p.getInt("anki_nfields_" + r.lang, 0);
                String[] f = new String[Math.max(nFields, 1)];
                java.util.Arrays.fill(f, "");
                String stem = (r.word.replaceAll("[^\\p{L}\\p{N}]+", "_") + "_" + System.currentTimeMillis()).toLowerCase(Locale.ROOT);
                File dir = app.dir("cards");
                Map<String, String> v = new LinkedHashMap<>();
                v.put("word", esc(r.word));
                v.put("sentence", bold(r.sentence, r.surface.isEmpty() ? r.word : r.surface));
                v.put("translation", esc(r.translation));
                List<String> chosen = new ArrayList<>();
                for (List<String> l : r.picked.values()) chosen.addAll(l);
                v.put("definition", !chosen.isEmpty() && !map.containsKey("short") ? glossHtml(chosen) : r.defHtml.isEmpty() ? glossHtml(r.glosses) : r.defHtml);
                v.put("short", glossHtml(chosen));
                v.put("frequency", esc(r.freq));
                v.put("ipa", esc(r.ipa));
                v.put("source", esc(r.title + (r.kind.equals("video") ? " " + Ui.time(r.start) : "")));
                if (map.containsKey("wordaudio")) {
                    try { File wa = Speak.toFile(a, r.lang, r.word, new File(dir, stem + "_w.wav")); v.put("wordaudio", AnkiDroid.addMedia(a, wa, true)); }
                    catch (java.io.IOException ignored) { /* no voice for this language on the phone: the card is made without it */ }
                }
                if (r.kind.equals("video") && !r.video.isEmpty()) {
                    Uri u = Uri.parse(r.video);
                    double pad = p.getInt("pad_ms", 250) / 1000.0;
                    if (map.containsKey("audio")) {
                        File au = MediaCut.audio(a, u, Math.max(0, r.start - pad), r.end + pad, dir, stem);
                        v.put("audio", AnkiDroid.addMedia(a, au, true));
                    }
                    if (map.containsKey("picture")) {
                        File im = MediaCut.frame(a, u, r.frameAt >= 0 ? r.frameAt : (r.start + r.end) / 2, new File(dir, stem + ".jpg"));
                        v.put("picture", AnkiDroid.addMedia(a, im, false));
                    }
                }
                for (Map.Entry<String, Integer> e : map.entrySet()) {
                    int i = e.getValue();
                    if (i >= 0 && i < f.length && v.containsKey(e.getKey())) f[i] = f[i].isEmpty() ? v.get(e.getKey()) : f[i] + "<br>" + v.get(e.getKey());
                }
                if (f[0].isEmpty()) f[0] = esc(r.word);
                AnkiDroid.addNote(a, mid, did, f, "pocket " + r.lang);
                ws.addMined(r.word);
                app.post(() -> { Ui.toast(a, "Card for “" + r.word + "” added to AnkiDroid"); if (done != null) done.run(); });
            } catch (Exception e) {
                app.post(() -> Ui.dialog(a).setTitle("The card was not made on the phone")
                        .setMessage(e.getMessage() + "\n\nKeep it for the PC instead? It is made there at the next sync.")
                        .setPositiveButton("Keep for the PC", (d, w) -> keepForPc(a, r, done, null))
                        .setNegativeButton("Cancel", null).show());
            }
        });
    }

    static void keepForPc(Activity a, Request r, Runnable done, String why) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", r.kind.equals("video") && !r.videoName.isEmpty() ? "video" : "text");
        c.put("word", r.word);
        c.put("sentence", r.sentence);
        c.put("translation", r.translation);
        c.put("title", r.title);
        if (!r.picked.isEmpty()) c.put("defs", new LinkedHashMap<String, Object>(r.picked));   // the PC puts them in its short definition fields
        if (r.kind.equals("video")) {
            c.put("video", r.videoName);
            c.put("start", r.start);
            c.put("end", r.end);
        }
        App.get().store(r.lang).addPending(c);
        Ui.toast(a, (why == null ? "" : why + ": ") + "the card for “" + r.word + "” waits for the PC (made at the next sync)");
        if (done != null) done.run();
    }

    static Map<String, Integer> fieldMap(String lang) {
        Map<String, Integer> m = new LinkedHashMap<>();
        Map<String, Object> j = Json.obj(Json.parse(App.get().prefs().getString("anki_fields_" + lang, "{}")));
        for (Map.Entry<String, Object> e : j.entrySet()) {
            int i = (int) Json.num(e.getValue(), -1);
            if (i >= 0) m.put(e.getKey(), i);
        }
        return m;
    }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>"); }

    static String bold(String sentence, String w) {
        String s = esc(sentence);
        if (w == null || w.isEmpty()) return s;
        int i = s.toLowerCase(Locale.ROOT).indexOf(w.toLowerCase(Locale.ROOT));
        return i < 0 ? s : s.substring(0, i) + "<b>" + s.substring(i, i + w.length()) + "</b>" + s.substring(i + w.length());
    }

    static String glossHtml(List<String> g) {
        if (g == null || g.isEmpty()) return "";
        if (g.size() == 1) return esc(g.get(0));
        StringBuilder sb = new StringBuilder("<ol>");
        for (String x : g.subList(0, Math.min(6, g.size()))) sb.append("<li>").append(esc(x)).append("</li>");
        return sb.append("</ol>").toString();
    }

    /** Choosing the note type, deck and which field gets what, for one language. */
    public static void setup(Activity a, String lang, Runnable then) {
        App app = App.get();
        if (!AnkiDroid.installed(a)) { Ui.alert(a, "AnkiDroid", "AnkiDroid is not installed. Install it from the Play Store or F-Droid, or let the PC make the cards (Settings → Cards)."); return; }
        if (!AnkiDroid.allowed(a)) { a.requestPermissions(new String[]{AnkiDroid.PERM}, 7); Ui.toast(a, "Allow access to AnkiDroid, then open this again"); return; }
        app.bg(() -> {
            try {
                List<AnkiDroid.Model> models = AnkiDroid.models(a);
                List<AnkiDroid.Deck> decks = AnkiDroid.decks(a);
                app.post(() -> setupDialog(a, lang, models, decks, then));
            } catch (Exception e) {
                app.post(() -> Ui.alert(a, "AnkiDroid", e.getMessage()));
            }
        });
    }

    private static void setupDialog(Activity a, String lang, List<AnkiDroid.Model> models, List<AnkiDroid.Deck> decks, Runnable then) {
        SharedPreferences p = App.get().prefs();
        LinearLayout box = Ui.col(a);
        int pad = Ui.dp(a, 16);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(Ui.hint(a, "Note type"));
        Spinner ms = spinner(a, models);
        box.addView(ms);
        box.addView(Ui.hint(a, "Deck"));
        Spinner ds = spinner(a, decks);
        box.addView(ds);
        long mid = p.getLong("anki_model_" + lang, -1), did = p.getLong("anki_deck_" + lang, -1);
        for (int i = 0; i < models.size(); i++) if (models.get(i).id == mid) ms.setSelection(i);
        for (int i = 0; i < decks.size(); i++) if (decks.get(i).id == did) ds.setSelection(i);
        LinearLayout fields = Ui.col(a);
        box.addView(Ui.hint(a, "Which field gets what"));
        box.addView(fields);
        List<Spinner> roleSpinners = new ArrayList<>();
        Runnable fill = () -> {
            fields.removeAllViews();
            roleSpinners.clear();
            if (models.isEmpty()) return;
            AnkiDroid.Model m = models.get(ms.getSelectedItemPosition());
            List<String> opts = new ArrayList<>();
            opts.add("—");
            for (String f : m.fields) opts.add(f);
            Map<String, Integer> saved = m.id == p.getLong("anki_model_" + lang, -1) ? fieldMap(lang) : new LinkedHashMap<>();
            for (String[] role : ROLES) {
                LinearLayout row = Ui.row(a);
                TextView t = Ui.text(a, role[1], 14, Ui.TEXT);
                row.addView(Ui.weight(t, 1));
                Spinner s = spinner(a, opts);
                row.addView(Ui.weight(s, 1.3f));
                int pick = saved.containsKey(role[0]) ? saved.get(role[0]) + 1 : guess(m.fields, role[2]) + 1;
                s.setSelection(Math.max(0, Math.min(pick, opts.size() - 1)));
                roleSpinners.add(s);
                fields.addView(row);
            }
        };
        ms.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int pos, long id) { fill.run(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        fill.run();
        Ui.dialog(a).setTitle("Cards in AnkiDroid (" + lang.toUpperCase(Locale.ROOT) + ")").setView(Ui.scroll(a, box))
                .setPositiveButton("Save", (d, w) -> {
                    if (models.isEmpty() || decks.isEmpty()) return;
                    AnkiDroid.Model m = models.get(ms.getSelectedItemPosition());
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < ROLES.length && i < roleSpinners.size(); i++) {
                        int sel = roleSpinners.get(i).getSelectedItemPosition() - 1;
                        if (sel >= 0) map.put(ROLES[i][0], sel);
                    }
                    p.edit().putLong("anki_model_" + lang, m.id).putLong("anki_deck_" + lang, decks.get(ds.getSelectedItemPosition()).id)
                            .putInt("anki_nfields_" + lang, m.fields.length).putString("anki_fields_" + lang, Json.write(map)).apply();
                    if (then != null) then.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    static int guess(String[] fields, String pattern) {
        for (String alt : pattern.split("\\|"))
            for (int i = 0; i < fields.length; i++) if (fields[i].toLowerCase(Locale.ROOT).equals(alt)) return i;
        for (String alt : pattern.split("\\|"))
            for (int i = 0; i < fields.length; i++) if (fields[i].toLowerCase(Locale.ROOT).contains(alt)) return i;
        return -1;
    }

    static <T> Spinner spinner(Activity a, List<T> items) {
        Spinner s = new Spinner(a, Spinner.MODE_DROPDOWN);
        ArrayAdapter<T> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        return s;
    }
}
