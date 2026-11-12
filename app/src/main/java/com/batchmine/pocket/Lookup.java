package com.batchmine.pocket;

import android.app.Activity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.batchmine.pocket.core.Book;
import com.batchmine.pocket.core.Subs;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** "Look up a sentence": type or paste one, tap a word (the word card opens: your dictionaries, Make card), and below it other sentences with that word from your books and subtitles to mine. */
final class Lookup {
    private Lookup() { }

    static final class Hit {
        String text, where; File video; Subs.Cue cue; int unknown;
    }

    static void open(Activity a, String start) {
        App app = App.get();
        String lang = app.lang();
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 16));
        EditText in = Ui.input(a, start == null ? "" : start, "Type or paste a sentence");
        in.setMinLines(2); in.setSingleLine(false);
        Ui.add(box, in, 8);
        LinearLayout words = Ui.col(a), found = Ui.col(a);
        Flow chips = new Flow(a, 8);
        box.addView(Ui.primary(a, "Show the words", v -> {
            chips.removeAllViews(); found.removeAllViews();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{L}][\\p{L}\\p{M}'’-]*").matcher(in.getText().toString());
            String sentence = in.getText().toString().trim().replaceAll("\\s+", " ");
            while (m.find()) {
                String w = m.group().replaceAll("^['’-]+|['’-]+$", "");
                if (w.isEmpty()) continue;
                TextView t = Ui.text(a, m.group(), 18, Ui.TEXT);
                t.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
                t.setBackground(Ui.round(Ui.mix(Ui.BG, Ui.TEXT, 0.10f), Ui.dp(a, 99)));
                t.setOnClickListener(x -> pick(a, lang, w, sentence, found));
                chips.addView(t);
            }
            if (chips.getChildCount() == 0) Ui.toast(a, "No words in that");
        }));
        words.addView(chips);
        box.addView(words);
        box.addView(found);
        Ui.sheet(a, "Look up a sentence", Ui.scroll(a, box), true);
        if (start != null && !start.isEmpty()) box.getChildAt(1).performClick();
    }

    private static void pick(Activity a, String lang, String w, String sentence, LinearLayout found) {
        App app = App.get();
        Cards.Request r = new Cards.Request();
        r.lang = lang; r.kind = "text"; r.sentence = sentence; r.title = "typed sentence";
        WordSheet.show(a, lang, w, r, null);
        found.removeAllViews();
        found.addView(Ui.section(a, "Your sentences with “" + w + "”"));
        TextView wait = Ui.hint(a, "looking through your books and subtitles…");
        found.addView(wait);
        Set<String> cands = app.dicts.candidates(w, lang);
        cands.add(w.toLowerCase(Locale.ROOT));
        app.bg(() -> {
            List<Hit> hits = find(a, lang, cands, 40);
            app.post(() -> {
                found.removeView(wait);
                if (hits.isEmpty()) { found.addView(Ui.hint(a, "None of the books or subtitle files on the phone has it. (Add more videos or books, or search on the PC.)")); return; }
                LinearLayout g = Ui.group(a);
                for (Hit h : hits) Ui.srow(g, h.text, (h.unknown == 0 ? "no other new word" : h.unknown + " other new") + " · " + h.where, null, v -> {
                    Cards.Request q = new Cards.Request();
                    q.lang = lang; q.sentence = h.text; q.title = h.where;
                    if (h.video != null) {
                        q.kind = "video"; q.video = Player.videoUri(h.video).toString(); q.videoName = h.video.getName();
                        q.start = h.cue.start; q.end = h.cue.end; q.title = Player.niceName(h.video);
                    } else q.kind = "text";
                    WordSheet.show(a, lang, w, q, null);
                });
                found.addView(g);
            });
        });
    }

    /** Sentences of the books and subtitle files here that hold one of the forms in CANDS (shortest sensible first). */
    static List<Hit> find(Activity a, String lang, Set<String> cands, int max) {
        App app = App.get();
        Map<String, Boolean> memo = new HashMap<>();
        List<Hit> out = new ArrayList<>();
        File[] books = app.dir("books").listFiles((d, n) -> n.endsWith(".json"));
        if (books != null) for (File f : books) {
            if (out.size() >= max * 3) break;
            try {
                Book b = Book.read(f);
                if (!b.lang.isEmpty() && !b.lang.equals(lang)) continue;
                String title = app.prefs().getString("btitle_" + b.id, b.title);
                for (Book.Chapter c : b.chapters) for (String p : c.paras) {
                    if (!mayHave(p, cands)) continue;
                    for (String s : p.split("(?<=[.!?…»”\"])\\s+")) {
                        if (matches(s, lang, cands, memo)) { Hit h = new Hit(); h.text = s.trim(); h.where = title + " · " + c.title; out.add(h); }
                    }
                }
            } catch (java.io.IOException | RuntimeException ignored) { /* an unreadable book is skipped */ }
        }
        File[] all = app.videoDir().listFiles();
        if (all != null) for (File v : all) {
            if (!Player.isVideo(v.getName()) || out.size() >= max * 3) continue;
            String vl = app.prefs().getString("vlang_" + v.getName(), "");
            if (!vl.isEmpty() && !vl.equals(lang)) continue;
            String stem = v.getName().replaceAll("\\.[^.]+$", "");
            for (File s : all) {
                String n = s.getName().toLowerCase(Locale.ROOT);
                if (!(n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass") || n.endsWith(".ssa")) || !s.getName().startsWith(stem)) continue;
                try {
                    for (Subs.Cue c : Subs.parse(Subs.decode(java.nio.file.Files.readAllBytes(s.toPath())))) {
                        if (mayHave(c.text, cands) && matches(c.text, lang, cands, memo)) {
                            Hit h = new Hit(); h.text = c.text.replaceAll("\\s+", " ").trim(); h.where = "🎬 " + Player.niceName(v) + " · " + Ui.time(c.start); h.video = v; h.cue = c; out.add(h);
                        }
                    }
                } catch (java.io.IOException | RuntimeException ignored) { /* skipped */ }
                break;                                           // the first subtitle file of a video is enough
            }
        }
        // best first, as on the PC: fewest other words you do not know yet, then a good length
        com.batchmine.pocket.core.WordStore ws = app.store(lang);
        Map<String, Boolean> unk = new HashMap<>();
        for (Hit h : out) {
            int n = 0;
            for (int[] r : Subs.words(h.text)) {
                String t = h.text.substring(r[0], r[1]).toLowerCase(Locale.ROOT);
                if (cands.contains(t)) continue;
                Boolean u = unk.get(t);
                if (u == null) {
                    u = !ws.isKnown(t);
                    if (u) {
                        Set<String> c = app.dicts.candidates(t, lang);
                        if (!app.dicts.listed(c, lang)) u = false;          // a name or junk: does not count
                        else for (String x : c) if (ws.isKnown(x)) { u = false; break; }
                    }
                    unk.put(t, u);
                }
                if (u) n++;
            }
            h.unknown = n;
        }
        out.sort((x, y) -> x.unknown != y.unknown ? Integer.compare(x.unknown, y.unknown) : Integer.compare(Math.abs(x.text.length() - 60), Math.abs(y.text.length() - 60)));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** A cheap test before the real one: does the text hold the first few letters of some form? */
    private static boolean mayHave(String text, Set<String> cands) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String c : cands) if (c.length() >= 3 ? t.contains(c.substring(0, Math.max(3, c.length() - 3))) : t.contains(c)) return true;
        return false;
    }

    private static boolean matches(String sentence, String lang, Set<String> cands, Map<String, Boolean> memo) {
        if (sentence.length() < 12 || sentence.length() > 240) return false;
        List<int[]> ws = Subs.words(sentence);
        if (ws.size() < 3) return false;
        for (int[] r : ws) {
            String t = sentence.substring(r[0], r[1]).toLowerCase(Locale.ROOT);
            Boolean m = memo.get(t);
            if (m == null) {
                m = cands.contains(t);
                if (!m) for (String c : App.get().dicts.candidates(t, lang)) if (cands.contains(c)) { m = true; break; }
                memo.put(t, m);
            }
            if (m) return true;
        }
        return false;
    }
}
