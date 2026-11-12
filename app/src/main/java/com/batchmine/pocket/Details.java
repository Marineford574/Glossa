package com.batchmine.pocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.batchmine.pocket.core.Json;
import com.batchmine.pocket.core.Meta;
import com.batchmine.pocket.core.PcLink;
import com.batchmine.pocket.core.Shows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Description, tags, aired / published, author or creator, level, stars and notes of a series or a book, kept on the phone ("details" in the
 * preferences) and in step with the PC at each sync. The AI is the PC's (Settings → AI there, Claude or a local Ollama model): the phone asks
 * it through the PC and shows what comes back; suggested tags wait for a tap on ✓ or ✕.
 * Keys: "pseries:<show key>" for a series (the PC's entry for the same show is linked by its name), "pbook:<book id>" for a book.
 */
final class Details {
    private Details() { }

    static String seriesKey(String show) { return "pseries:" + Shows.key(show); }
    static String bookKey(String id) { return "pbook:" + id; }

    private static SharedPreferences sp() { return App.get().prefs(); }

    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> all() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, Object> e : Json.obj(Json.parse(sp().getString("details", "{}"))).entrySet())
                if (e.getValue() instanceof Map) out.put(e.getKey(), (Map<String, Object>) e.getValue());
        } catch (RuntimeException ignored) { /* damaged: start again */ }
        return out;
    }

    static Map<String, Object> get(String key) {
        Map<String, Object> e = all().get(key);
        return e == null ? new LinkedHashMap<>() : e;
    }

    static void put(String key, Map<String, Object> e) {
        Map<String, Map<String, Object>> a = all();
        if (e.isEmpty()) a.remove(key); else a.put(key, e);
        sp().edit().putString("details", Json.write(new LinkedHashMap<String, Object>(a))).apply();
    }

    /** Tags used by the given keys' entries, most used first. */
    static List<String> tagsUsed(List<String> keys) {
        Map<String, Integer> n = new LinkedHashMap<>();
        Map<String, Map<String, Object>> a = all();
        for (String k : keys) for (String t : Meta.list(a.getOrDefault(k, new LinkedHashMap<>()), "tags")) n.merge(t, 1, Integer::sum);
        List<Map.Entry<String, Integer>> es = new ArrayList<>(n.entrySet());
        es.sort((x, y) -> y.getValue() - x.getValue() != 0 ? y.getValue() - x.getValue() : x.getKey().compareTo(y.getKey()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : es) out.add(e.getKey());
        return out;
    }

    static boolean hasTag(String key, String tag) { return Meta.list(get(key), "tags").contains(tag); }

    /** One dim line for a list row: "anime · mystery · nature" (and how many are waiting to be checked). */
    static String tagLine(String key) {
        Map<String, Object> e = get(key);
        List<String> t = Meta.list(e, "tags");
        List<String> bits = new ArrayList<>(t.size() > 4 ? t.subList(0, 4) : t);
        int sug = Meta.list(e, "suggest").size();
        String s = String.join(" · ", bits);
        if (sug > 0) s += (s.isEmpty() ? "" : "  ") + "(+" + sug + " to check)";
        return s;
    }

    // ---------------------------------------------------------------- sync with the PC
    /** For a sync: the entries changed since the last one that the PC knows (they carry its key). */
    static List<Object> toPush() {
        long since = sp().getLong("details_pushed", 0);
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> e : all().values()) {
            Object k = e.get("pckey"), t = e.get("t");
            if (k == null || !(t instanceof Number) || ((Number) t).longValue() <= since) continue;
            Map<String, Object> c = new LinkedHashMap<>(e);
            c.put("key", k);
            out.add(c);
        }
        return out;
    }

    static void pushed() { sp().edit().putLong("details_pushed", System.currentTimeMillis()).apply(); }

    /**
     * What the PC sent: its entries for series (with the names the phone may know the show by) and books. An entry is taken when newer than
     * the phone's (the phone's own link to it stays). Series are matched by show name; books by their key ("pc" + key).
     */
    @SuppressWarnings("unchecked")
    static void take(List<Object> list, long now) {
        if (list == null || list.isEmpty()) return;
        Map<String, Map<String, Object>> a = all();
        boolean changed = false;
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> pc = new LinkedHashMap<>((Map<String, Object>) o);
            String key = Json.str(pc.get("key"), "");
            List<String> targets = new ArrayList<>();
            if (key.startsWith("book:")) targets.add(bookKey("pc" + key.substring(5)));
            else if (key.startsWith("series:") || key.startsWith("playlist:")) {
                if (key.startsWith("playlist:")) continue;
                if (pc.get("match") instanceof List) for (Object m : (List<?>) pc.get("match")) targets.add("pseries:" + m);
            }
            Object nm = pc.remove("name");
            pc.remove("match");
            pc.put("pckey", key);
            for (String t : targets) {
                Map<String, Object> mine = a.get(t);
                if (mine == null) { a.put(t, new LinkedHashMap<>(pc)); changed = true; }
                else if (Meta.takeNewer(mine, new LinkedHashMap<>(pc))) changed = true;
            }
        }
        if (changed) sp().edit().putString("details", Json.write(new LinkedHashMap<String, Object>(a))).apply();
    }

    // ---------------------------------------------------------------- the AI, through the PC
    interface Done { void done(String message); }

    /** Asks the PC's AI about this item; the answer (a whole entry) replaces the phone's, tags arrive as suggestions. */
    static void askAi(Context c, String key, String name, String hints, boolean overwrite, Done done) {
        PcLink pc = App.get().pc();
        if (pc == null) { Ui.alert(c, "AI details", "The AI is the one on the PC (Settings → AI there): link the PC first (Settings → The PC)."); return; }
        Ui.toast(c, "Asking the AI on the PC…");
        App.get().bg(() -> {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                String pcKey = Json.str(get(key).get("pckey"), "");
                body.put("key", pcKey.isEmpty() ? key : pcKey);
                body.put("name", name);
                body.put("hints", hints);
                body.put("lang", App.get().lang());
                body.put("overwrite", overwrite);
                body.put("meta", get(key));
                Map<String, Object> r = pc.post("/api/pocket/metaai", body, 240000);
                @SuppressWarnings("unchecked") Map<String, Object> e = r.get("meta") instanceof Map ? new LinkedHashMap<>((Map<String, Object>) r.get("meta")) : new LinkedHashMap<>();
                if (!pcKey.isEmpty()) e.put("pckey", pcKey);
                else if (get(key).get("pckey") != null) e.put("pckey", get(key).get("pckey"));
                put(key, e);
                int filled = r.get("filled") instanceof List ? ((List<?>) r.get("filled")).size() : 0;
                int sug = (r.get("suggested") instanceof List ? ((List<?>) r.get("suggested")).size() : 0) + (r.get("added") instanceof List ? ((List<?>) r.get("added")).size() : 0);
                boolean found = !Boolean.FALSE.equals(r.get("found"));
                String msg = !found && filled == 0 ? "The AI (" + Json.str(r.get("who"), "?") + ") does not know “" + name + "”" + (sug > 0 ? ": " + sug + " tag(s) guessed from the name" : "") + ". Write the details yourself."
                        : "The AI (" + Json.str(r.get("who"), "?") + ") filled " + filled + " field(s)" + (sug > 0 ? " and has " + sug + " tag(s) for you to check" : "") + ". Small models get facts wrong: check them.";
                App.get().post(() -> done.done(msg));
            } catch (IOException | RuntimeException ex) {
                App.get().post(() -> done.done("The AI could not answer: " + (ex.getMessage() == null ? ex : ex.getMessage())));
            }
        });
    }

    // ---------------------------------------------------------------- the block on a series / book page
    /** Facts, tags, suggestions (✓ / ✕), description, and the buttons Details… and Fill in with AI. */
    static View block(Context c, String key, String kindWord, String name, String hints, Runnable refresh) {
        Map<String, Object> e = get(key);
        LinearLayout l = Ui.col(c);
        boolean book = "book".equals(kindWord);
        String line = Meta.line(e);
        List<String> ai = Meta.list(e, "ai");
        if (!line.isEmpty()) {
            TextView t = Ui.text(c, line, 14, Ui.TEXT);
            t.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 2));
            l.addView(t);
        }
        String st = Json.str(e.get("status"), "");
        List<String> facts = new ArrayList<>();
        if (!Json.str(e.get("orig"), "").isEmpty()) facts.add(Json.str(e.get("orig"), ""));
        if (!Json.str(e.get("country"), "").isEmpty()) facts.add(Json.str(e.get("country"), ""));
        if (!Json.str(e.get("episodes"), "").isEmpty()) facts.add(Json.str(e.get("episodes"), "") + (book ? " pages" : " episodes"));
        if (!st.isEmpty()) facts.add(st.equals("want") ? (book ? "want to read" : "want to watch") : st.equals("now") ? (book ? "reading" : "watching") : st.equals("done") ? "finished" : "dropped");
        if (!facts.isEmpty()) l.addView(Ui.text(c, String.join(" · ", facts), 12, Ui.DIM));
        List<String> tags = Meta.list(e, "tags"), sug = Meta.list(e, "suggest");
        if (!tags.isEmpty()) {
            Flow f = new Flow(c, 6);
            f.setPadding(0, Ui.dp(c, 8), 0, 0);
            for (String t : tags) f.addView(Ui.pill(c, t, Ui.TEXT, Ui.mix(Ui.BG, Ui.TEXT, 0.12f)));
            l.addView(f);
        }
        if (!sug.isEmpty()) {
            l.addView(Ui.text(c, "Tags the AI suggests: tap ✓ to add, ✕ to turn down", 12, Ui.DIM));
            Flow f = new Flow(c, 6);
            f.setPadding(0, Ui.dp(c, 4), 0, 0);
            for (String t : sug) {
                LinearLayout chip = Ui.row(c);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                android.graphics.drawable.GradientDrawable g = Ui.round(0x00000000, Ui.dp(c, 99));
                g.setStroke(Ui.dp(c, 1), Ui.mix(Ui.BG, Ui.ACCENT, 0.7f));
                chip.setBackground(g);
                chip.setPadding(Ui.dp(c, 10), 0, 0, 0);
                chip.addView(Ui.text(c, t, 12, Ui.TEXT));
                TextView ok = Ui.text(c, "✓", 14, Ui.GOOD), no = Ui.text(c, "✕", 14, Ui.DIM);
                ok.setPadding(Ui.dp(c, 10), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
                no.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 10), Ui.dp(c, 6));
                ok.setContentDescription("Add the tag " + t);
                no.setContentDescription("Turn down the tag " + t);
                ok.setOnClickListener(v -> { decide(key, java.util.Arrays.asList(t), new ArrayList<>()); redraw(l, c, key, kindWord, name, hints, refresh); });
                no.setOnClickListener(v -> { decide(key, new ArrayList<>(), java.util.Arrays.asList(t)); redraw(l, c, key, kindWord, name, hints, refresh); });
                chip.addView(ok); chip.addView(no);
                f.addView(chip);
            }
            if (sug.size() > 1) {
                TextView all = Ui.text(c, "Add all", 12, Ui.ACCENT);
                all.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
                all.setOnClickListener(v -> { decide(key, sug, new ArrayList<>()); redraw(l, c, key, kindWord, name, hints, refresh); });
                f.addView(all);
            }
            l.addView(f);
        }
        String d = Json.str(e.get("desc"), "");
        if (!d.isEmpty()) {
            TextView t = Ui.text(c, d + (ai.contains("desc") ? "  (AI)" : ""), 14, Ui.mix(Ui.BG, Ui.TEXT, 0.85f));
            t.setPadding(0, Ui.dp(c, 10), 0, 0);
            t.setLineSpacing(0, 1.15f);
            l.addView(t);
        }
        if (!Json.str(e.get("notes"), "").isEmpty()) l.addView(Ui.text(c, "Notes: " + Json.str(e.get("notes"), ""), 12, Ui.DIM));
        LinearLayout bt = Ui.row(c);
        bt.setPadding(0, Ui.dp(c, 10), 0, 0);
        bt.addView(Ui.weight(Ui.small(c, "✎ Details…", v -> edit(c, key, name, kindWord, refresh)), 1));
        bt.addView(Ui.weight(Ui.small(c, "✨ Fill in with AI", v -> askAi(c, key, name, hints, false, msg -> { Ui.toast(c, msg); refresh.run(); })), 1));
        l.addView(bt);
        return l;
    }

    /** A tag taken or turned down: only this block is drawn again, so the page does not jump to the episode you are on. */
    static void redraw(LinearLayout old, Context c, String key, String kindWord, String name, String hints, Runnable refresh) {
        android.view.ViewParent p = old.getParent();
        if (!(p instanceof android.view.ViewGroup)) { refresh.run(); return; }
        android.view.ViewGroup g = (android.view.ViewGroup) p;
        int i = g.indexOfChild(old);
        View nb = block(c, key, kindWord, name, hints, refresh);
        nb.setLayoutParams(old.getLayoutParams());
        g.removeViewAt(i);
        g.addView(nb, i);
    }

    static void decide(String key, List<String> accept, List<String> reject) {
        Map<String, Object> e = get(key);
        Meta.decide(e, accept, reject, System.currentTimeMillis());
        put(key, e);
    }

    /** The editor: every field in a sheet. */
    static void edit(Context c, String key, String name, String kindWord, Runnable refresh) {
        Map<String, Object> e = get(key);
        boolean book = "book".equals(kindWord);
        LinearLayout l = Ui.col(c);
        l.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), Ui.dp(c, 16));
        final Map<String, android.widget.EditText> in = new LinkedHashMap<>();
        String[][] f = {{"creator", book ? "Author" : "Created by"}, {"aired", book ? "Published" : "Aired"}, {"orig", "Original title"}, {"country", "Country"},
                {"original_lang", "Original language"}, {"episodes", book ? "Pages" : "Episodes"}, {"level", "Level (A1–C2)"}, {"rating", "Your stars (1–5)"}};
        l.addView(label(c, "Description"));
        android.widget.EditText desc = field(c, Json.str(e.get("desc"), ""), "What it is about", true);
        l.addView(desc);
        l.addView(label(c, "Tags (separated by commas)"));
        android.widget.EditText tags = field(c, String.join(", ", Meta.list(e, "tags")), "comedy, 1990s, family…", false);
        l.addView(tags);
        for (String[] x : f) {
            l.addView(label(c, x[1]));
            Object v = e.get(x[0]);
            android.widget.EditText ed = field(c, v == null ? "" : (v instanceof Number ? String.valueOf(((Number) v).intValue()) : String.valueOf(v)), "", false);
            in.put(x[0], ed);
            l.addView(ed);
        }
        l.addView(label(c, "Your notes"));
        android.widget.EditText notes = field(c, Json.str(e.get("notes"), ""), "", true);
        l.addView(notes);
        String[] sts = {"", "want", "now", "done", "dropped"};
        String[] stn = {"–", book ? "Want to read" : "Want to watch", book ? "Reading" : "Watching", "Finished", "Dropped"};
        final String[] status = {Json.str(e.get("status"), "")};
        LinearLayout g = Ui.group(c);
        int cur = 0;
        for (int i = 0; i < sts.length; i++) if (sts[i].equals(status[0])) cur = i;
        final android.widget.TextView[] stv = new android.widget.TextView[1];
        Ui.pickRow(g, "Status", stn[cur], v -> Ui.choose(c, "Status", stn, -1, i -> { status[0] = sts[i]; ((TextView) ((ViewGroup) v).getChildAt(((ViewGroup) v).getChildCount() - 1)).setText(stn[i]); }));
        Ui.add(l, g, 12);
        android.app.Dialog[] dlg = new android.app.Dialog[1];
        Ui.add(l, Ui.buttons(c, Ui.flat(c, "Cancel", v -> Ui.dismiss(dlg[0])), Ui.primary(c, "Save", v -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("desc", desc.getText().toString());
            fields.put("tags", tags.getText().toString());
            fields.put("notes", notes.getText().toString());
            fields.put("status", status[0]);
            for (Map.Entry<String, android.widget.EditText> x : in.entrySet()) fields.put(x.getKey(), x.getValue().getText().toString());
            Map<String, Object> cur2 = get(key);
            Meta.update(cur2, fields, System.currentTimeMillis());
            put(key, cur2);
            Ui.dismiss(dlg[0]);
            refresh.run();
        })), 14);
        dlg[0] = Ui.sheet(c, "Details of “" + name + "”", Ui.scroll(c, l), true);
    }

    private static TextView label(Context c, String s) {
        TextView t = Ui.text(c, s, 12, Ui.DIM);
        t.setPadding(Ui.dp(c, 2), Ui.dp(c, 10), 0, Ui.dp(c, 3));
        return t;
    }

    private static android.widget.EditText field(Context c, String v, String hint, boolean multi) {
        android.widget.EditText e = Ui.input(c, v, hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (multi ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : 0));
        e.setSingleLine(!multi);
        if (multi) { e.setMinLines(3); e.setGravity(Gravity.TOP); }
        return e;
    }

    /** A row of tag chips above a list: tap one to show only those with it, tap again for all. */
    static View filterBar(Context c, List<String> tags, String current, java.util.function.Consumer<String> pick) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout r = Ui.row(c);
        r.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 8));
        for (String t : tags) {
            boolean on = t.equals(current);
            TextView p = Ui.pill(c, t, on ? Ui.ON_ACC : Ui.TEXT, on ? Ui.ACCENT : Ui.mix(Ui.BG, Ui.TEXT, 0.12f));
            p.setPadding(Ui.dp(c, 12), Ui.dp(c, 5), Ui.dp(c, 12), Ui.dp(c, 6));
            p.setTextSize(13);
            p.setOnClickListener(v -> pick.accept(on ? "" : t));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(c, 6);
            r.addView(p, lp);
        }
        hs.addView(r);
        return hs;
    }
}
