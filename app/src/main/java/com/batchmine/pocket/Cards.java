package com.batchmine.pocket;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.ViewGroup;
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
        public Map<String, String> richBy = new LinkedHashMap<>();   // the meanings of the ticked entries by definition language (IT / DE / EN …)
        public String defHtml = "", freq = "", wordAudio = "";   // all dictionaries' meanings (HTML), frequency rank, "yes": add the word spoken
        public String video = "", videoName = "";          // the video (a content:// or file:// link) and its file name
        public double start, end, frameAt = -1;
        /** Lines around the sentence that the popup can add to it (nearest first); for a video each has its time. */
        public List<Ctx> before = new ArrayList<>(), after = new ArrayList<>();

        public Request copy() {
            Request r = new Request();
            r.lang = lang; r.kind = kind; r.word = word; r.surface = surface; r.sentence = sentence; r.translation = translation;
            r.ipa = ipa; r.title = title; r.glosses = new ArrayList<>(glosses); r.picked = new LinkedHashMap<>(picked); r.video = video; r.videoName = videoName; r.defHtml = defHtml; r.richBy = new LinkedHashMap<>(richBy); r.freq = freq; r.wordAudio = wordAudio;
            r.start = start; r.end = end; r.frameAt = frameAt; r.before = before; r.after = after;
            return r;
        }
    }

    /** What a card can carry, and the field names that usually hold it. */
    public static final String[][] ROLES = {
            {"word", "Word", "word|target|expression|vocab|front|lemma|parola|wort|vokabel|riječ"},
            {"sentence", "Sentence", "sentence|context|example|frase|satz|line|beispielsatz|example sentence|rečenica"},
            {"translation", "Sentence translation", "translation|english|meaning of sentence|traduzione|übersetzung|satzübersetzung|sentence translation|prijevod"},
            {"definition", "Definition", "definition|meaning|glossary|gloss|back|significato|bedeutung"},
            {"short", "Short definition (the meanings you tick)", "short definition|definizione|definition 1|short|brief|kurz"},
            {"ipa", "Pronunciation (IPA)", "ipa|pronunciation|reading|pronuncia"},
            {"audio", "Sentence audio", "audio|sound|sentence audio|satz audio|satzaudio|clip"},
            {"picture", "Picture", "picture|image|screenshot|immagine|bild|slika"},
            {"wordaudio", "Word spoken (phone voice)", "word audio|pronunciation audio|audio word|wordaudio|vokabel_audio|vokabel audio|wort audio|wortaudio"},
            {"frequency", "Frequency", "frequency|freq|rank|häufigkeit|frequenza"},
            {"source", "Source", "source|from|fonte|quelle"},
            // per definition language, as the PC's note types have them (Italian: Definizioni IT + Definizione IT, German: Definitionen 1 / 2 …)
            {"rich_it", "Definitions, Italian (full)", "definizioni it"}, {"rich_de", "Definitions, German (full)", "definizioni de"}, {"rich_en", "Definitions, English (full)", "definizioni en"},
            {"short_it", "Short definition, Italian (ticked)", "definizione it"}, {"short_de", "Short definition, German (ticked)", "definizione de|definitionen 1"}, {"short_en", "Short definition, English (ticked)", "definizione en|definitionen 2"},
            {"short_hr", "Short definition, Croatian (ticked)", "definicija"}};

    /** The PC's profile of a language (note type, deck, field names, definition fields), as the last sync brought it. */
    static Map<String, Object> profile(String lang) {
        return Json.obj(Json.parse(App.get().prefs().getString("card_profile_" + lang, "{}")));
    }

    /** Dictionary name -> definition language label (IT / DE / EN), from the PC's YOMITAN_DICTS. */
    static Map<String, String> dictLabels(String lang) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : Json.obj(profile(lang).get("dicts")).entrySet()) m.put(e.getKey(), Json.str(e.getValue(), ""));
        return m;
    }

    /** Role -> field name the PC uses for it. */
    static Map<String, String> pcFields(String lang) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Object> pr = profile(lang), f = Json.obj(pr.get("fields"));
        String[][] same = {{"sentence", "sentence"}, {"translation", "translation"}, {"image", "picture"}, {"audio", "audio"}, {"word_audio", "wordaudio"},
                {"word", "word"}, {"gloss", "definition"}, {"freq", "frequency"}, {"ipa", "ipa"}, {"source", "source"}};
        for (String[] x : same) if (!Json.str(f.get(x[0]), "").isEmpty()) out.put(x[1], Json.str(f.get(x[0]), ""));
        for (Map.Entry<String, Object> e : Json.obj(pr.get("pick")).entrySet()) out.put("short_" + labelCode(e.getKey()), Json.str(e.getValue(), ""));
        for (Map.Entry<String, Object> e : Json.obj(pr.get("rich")).entrySet()) out.put("rich_" + labelCode(e.getKey()), Json.str(e.getValue(), ""));
        return out;
    }

    static String labelCode(String label) {
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return l.equals("croatian") ? "hr" : l.equals("german") ? "de" : l.equals("english") ? "en" : l.equals("italian") ? "it" : l;
    }

    public static boolean useAnki() {
        String m = App.get().prefs().getString("card_mode", "");
        return m.isEmpty() ? AnkiDroid.installed(App.get()) : m.equals("anki");
    }

    public static void make(Activity a, Request r, Runnable done) {
        App app = App.get();
        if (Tray.on() && !Tray.sending) {
            Tray.add(r);
            Ui.toast(a, "Card for “" + r.word + "” is in the tray (" + Tray.count() + ")");
            if (done != null) done.run();
            return;
        }
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
                boolean perLang = false;
                for (String k : map.keySet()) if (k.startsWith("rich_")) perLang = true;
                if (!perLang) v.put("definition", !chosen.isEmpty() && !map.containsKey("short") ? glossHtml(chosen) : r.defHtml.isEmpty() ? glossHtml(r.glosses) : r.defHtml);
                v.put("short", glossHtml(chosen));
                for (Map.Entry<String, List<String>> e : r.picked.entrySet()) {          // the ticked meanings, in the short field of their language (the PC's layout)
                    List<String> items = new ArrayList<>(e.getValue());
                    if (items.contains("-")) items = java.util.Arrays.asList("-");
                    if (!items.isEmpty()) v.put("short_" + labelCode(e.getKey()), items.size() == 1 ? esc(items.get(0)) : glossHtml(items));
                }
                for (Map.Entry<String, String> e : r.richBy.entrySet()) v.put("rich_" + labelCode(e.getKey()), e.getValue());
                v.put("frequency", esc(r.freq));
                v.put("ipa", esc(r.ipa));
                v.put("source", esc(r.title + (r.kind.equals("video") ? " " + Ui.time(r.start) : "")));
                if (map.containsKey("wordaudio")) {
                    try { File wa = Speak.toFile(a, r.lang, r.word, new File(dir, stem + "_w.wav")); v.put("wordaudio", AnkiDroid.addMedia(a, wa, true)); }
                    catch (java.io.IOException ignored) { /* no voice for this language on the phone: the card is made without it */ }
                }
                if (r.kind.equals("video") && !r.video.isEmpty()) {
                    Uri u = Uri.parse(r.video);
                    double padB = p.getInt("padb_ms", p.getInt("pad_ms", 250)) / 1000.0, padA = p.getInt("pada_ms", p.getInt("pad_ms", 250)) / 1000.0;
                    if (map.containsKey("audio")) {
                        com.batchmine.pocket.core.SoundTools.Opts so = soundOpts(p);
                        File au = null;
                        if (so.any()) {
                            try { au = MediaCut.audioTuned(a, u, Math.max(0, r.start - padB), r.end + padA, dir, stem, so); }
                            catch (java.io.IOException ignored) { /* the phone cannot decode this sound: the line is copied as it is */ }
                        }
                        if (au == null) au = MediaCut.audio(a, u, Math.max(0, r.start - padB), r.end + padA, dir, stem);
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

    static com.batchmine.pocket.core.SoundTools.Opts soundOpts(SharedPreferences p) {
        com.batchmine.pocket.core.SoundTools.Opts o = new com.batchmine.pocket.core.SoundTools.Opts();
        o.normalise = p.getBoolean("snd_norm", false);
        o.trim = p.getBoolean("snd_trim", false);
        o.fadeMs = p.getInt("snd_fade", 0);
        return o;
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
        String day = Stopwatch.day(System.currentTimeMillis());
        App.get().prefs().edit().putInt("cards_" + day, App.get().prefs().getInt("cards_" + day, 0) + 1).apply();
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

    /** The AnkiDroid set-up as a panel in the app's colours: note type, deck, and which field gets what (one row each, tap to change). */
    private static void setupDialog(Activity a, String lang, List<AnkiDroid.Model> models, List<AnkiDroid.Deck> decks, Runnable then) {
        if (models.isEmpty() || decks.isEmpty()) { Ui.alert(a, "AnkiDroid", "AnkiDroid has no note types or decks yet. Make one there first."); return; }
        SharedPreferences p = App.get().prefs();
        long mid = p.getLong("anki_model_" + lang, -1), did = p.getLong("anki_deck_" + lang, -1);
        int[] mi = {0}, di = {0};
        for (int i = 0; i < models.size(); i++) if (models.get(i).id == mid) mi[0] = i;
        if (mid < 0) for (int i = 0; i < models.size(); i++) if (models.get(i).name.toLowerCase(Locale.ROOT).contains(lang)) { mi[0] = i; break; }
        String pcModel = Json.str(profile(lang).get("model"), ""), pcDeck = Json.str(profile(lang).get("deck"), "");
        if (mid < 0 && !pcModel.isEmpty()) for (int i = 0; i < models.size(); i++) if (models.get(i).name.equalsIgnoreCase(pcModel)) { mi[0] = i; break; }
        for (int i = 0; i < decks.size(); i++) if (decks.get(i).id == did) di[0] = i;
        if (did < 0 && !pcDeck.isEmpty()) for (int i = 0; i < decks.size(); i++) if (decks.get(i).name.equalsIgnoreCase(pcDeck)) { di[0] = i; break; }
        int[][] sel = {choices(models.get(mi[0]), lang, p, false)};

        LinearLayout body = Ui.col(a);
        body.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 14), Ui.dp(a, 12));
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(body);
        LinearLayout page = Ui.col(a);
        page.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        android.app.Dialog[] dlg = new android.app.Dialog[1];
        Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            body.removeAllViews();
            AnkiDroid.Model m = models.get(mi[0]);
            body.addView(Ui.hint(a, "Cards made on the phone for " + lang.toUpperCase(Locale.ROOT) + " go into this note type and deck. Tap a row to change it."));
            body.addView(Ui.section(a, "Where the cards go"));
            LinearLayout g = Ui.group(a);
            Ui.pickRow(g, "Note type", m.name, v -> {
                String[] names = new String[models.size()];
                for (int i = 0; i < names.length; i++) names[i] = models.get(i).name;
                Ui.choose(a, "Note type", names, mi[0], i -> { mi[0] = i; sel[0] = choices(models.get(i), lang, p, false); draw[0].run(); });
            });
            Ui.pickRow(g, "Deck", decks.get(di[0]).name, v -> {
                String[] names = new String[decks.size()];
                for (int i = 0; i < names.length; i++) names[i] = decks.get(i).name;
                Ui.choose(a, "Deck", names, di[0], i -> { di[0] = i; draw[0].run(); });
            });
            Ui.add(body, g, 0);
            body.addView(Ui.section(a, "What goes into which field"));
            g = Ui.group(a);
            for (int k = 0; k < ROLES.length; k++) {
                final int role = k;
                int f = sel[0][k];
                Ui.pickRow(g, ROLES[k][1], f < 0 ? "nothing" : m.fields[f], v -> {
                    String[] opts = new String[m.fields.length + 1];
                    opts[0] = "— nothing";
                    for (int i = 0; i < m.fields.length; i++) {
                        String taken = "";
                        for (int r = 0; r < ROLES.length; r++) if (r != role && sel[0][r] == i) taken = "  (now: " + ROLES[r][1].replaceAll(" \\(.*", "") + ")";
                        opts[i + 1] = m.fields[i] + taken;
                    }
                    Ui.choose(a, ROLES[role][1], opts, sel[0][role] + 1, i -> { sel[0][role] = i - 1; draw[0].run(); });
                });
            }
            Ui.add(body, g, 0);
            Ui.add(body, Ui.flat(a, "Guess the fields again from their names", v -> { sel[0] = choices(models.get(mi[0]), lang, p, true); draw[0].run(); }), 10);
        };
        draw[0].run();
        LinearLayout bar = Ui.buttons(a, Ui.button(a, "Cancel", v -> Ui.dismiss(dlg[0])), Ui.primary(a, "Save", v -> {
            AnkiDroid.Model m = models.get(mi[0]);
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < ROLES.length; i++) if (sel[0][i] >= 0) map.put(ROLES[i][0], sel[0][i]);
            p.edit().putLong("anki_model_" + lang, m.id).putLong("anki_deck_" + lang, decks.get(di[0]).id)
                    .putInt("anki_nfields_" + lang, m.fields.length).putString("anki_fields_" + lang, Json.write(map)).apply();
            Ui.dismiss(dlg[0]);
            if (then != null) then.run();
        }));
        bar.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 14), Ui.dp(a, 14));
        page.addView(bar);
        dlg[0] = Ui.sheet(a, "Cards in AnkiDroid · " + lang.toUpperCase(Locale.ROOT), page, true);
    }

    /** Field of each role (index, -1 = none): the saved ones for this note type, else guessed from the field names. */
    static int[] choices(AnkiDroid.Model m, String lang, SharedPreferences p, boolean fresh) {
        int[] out = assign(m.fields, lang);
        if (!fresh && m.id == p.getLong("anki_model_" + lang, -1)) {
            Map<String, Integer> saved = fieldMap(lang);
            for (int i = 0; i < ROLES.length; i++) {
                Integer f = saved.get(ROLES[i][0]);
                out[i] = f != null && f < m.fields.length ? f : -1;
            }
        }
        return out;
    }

    /** Guesses which field is which from the names, each field used once: the most specific matches are placed first
     *  ("Worthäufigkeit" is the frequency, not the word, because "häufigkeit" says more than "wort"). */
    static int[] assign(String[] fields, String lang) {
        int[] out = new int[ROLES.length];
        java.util.Arrays.fill(out, -1);
        boolean[] used = new boolean[fields.length];
        Map<String, String> pc = pcFields(lang);                  // first what the PC does: the same field names, the same roles
        for (int r = 0; r < ROLES.length; r++) {
            String want = pc.get(ROLES[r][0]);
            if (want == null || want.isEmpty()) continue;
            for (int f = 0; f < fields.length; f++) if (!used[f] && fields[f].equalsIgnoreCase(want)) { out[r] = f; used[f] = true; break; }
        }
        List<int[]> cands = new ArrayList<>();                   // {score, role, field}
        for (int r = 0; r < ROLES.length; r++)
            for (int f = 0; f < fields.length; f++) {
                if (out[r] >= 0 || used[f]) continue;
                String n = fields[f].toLowerCase(Locale.ROOT).trim();
                int best = 0;
                for (String alt : ROLES[r][2].split("\\|")) {
                    int sc = n.equals(alt) ? 100 + alt.length() : n.startsWith(alt) ? 40 + alt.length() : n.contains(alt) ? alt.length() : 0;
                    best = Math.max(best, sc);
                }
                if (best > 0) cands.add(new int[]{best, r, f});
            }
        cands.sort((x, y) -> y[0] - x[0]);
        for (int[] c : cands) if (out[c[1]] < 0 && !used[c[2]]) { out[c[1]] = c[2]; used[c[2]] = true; }
        return out;
    }

    static <T> Spinner spinner(Activity a, List<T> items) {
        Spinner s = new Spinner(a, Spinner.MODE_DROPDOWN);
        ArrayAdapter<T> ad = new ArrayAdapter<>(a, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(ad);
        return s;
    }
}
