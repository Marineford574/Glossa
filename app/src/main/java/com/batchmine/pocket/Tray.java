package com.batchmine.pocket;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The card tray: with it on, new cards are held here instead of being made; read them, correct them, drop the bad ones, then send the rest. */
final class Tray {
    private Tray() { }

    static boolean sending;                                    // true while the tray sends its cards (they are made, not held again)

    static SharedPreferences p() { return App.get().prefs(); }
    static boolean on() { return p().getBoolean("card_tray", false); }

    static JSONObject toJson(Cards.Request r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("lang", r.lang).put("kind", r.kind).put("word", r.word).put("surface", r.surface).put("sentence", r.sentence)
                .put("translation", r.translation).put("ipa", r.ipa).put("title", r.title).put("defHtml", r.defHtml).put("freq", r.freq)
                .put("wordAudio", r.wordAudio).put("video", r.video).put("videoName", r.videoName)
                .put("start", r.start).put("end", r.end).put("frameAt", r.frameAt);
        o.put("glosses", new JSONArray(r.glosses));
        JSONObject pk = new JSONObject();
        for (java.util.Map.Entry<String, List<String>> e : r.picked.entrySet()) pk.put(e.getKey(), new JSONArray(e.getValue()));
        o.put("picked", pk);
        JSONObject rb = new JSONObject();
        for (java.util.Map.Entry<String, String> e : r.richBy.entrySet()) rb.put(e.getKey(), e.getValue());
        o.put("richBy", rb);
        return o;
    }

    static Cards.Request fromJson(JSONObject o) {
        Cards.Request r = new Cards.Request();
        r.lang = o.optString("lang"); r.kind = o.optString("kind", "text"); r.word = o.optString("word"); r.surface = o.optString("surface");
        r.sentence = o.optString("sentence"); r.translation = o.optString("translation"); r.ipa = o.optString("ipa"); r.title = o.optString("title");
        r.defHtml = o.optString("defHtml"); r.freq = o.optString("freq"); r.wordAudio = o.optString("wordAudio");
        r.video = o.optString("video"); r.videoName = o.optString("videoName");
        r.start = o.optDouble("start"); r.end = o.optDouble("end"); r.frameAt = o.optDouble("frameAt", -1);
        JSONArray g = o.optJSONArray("glosses");
        if (g != null) for (int i = 0; i < g.length(); i++) r.glosses.add(g.optString(i));
        JSONObject pk = o.optJSONObject("picked");
        if (pk != null) for (java.util.Iterator<String> it = pk.keys(); it.hasNext(); ) {
            String k = it.next();
            JSONArray a = pk.optJSONArray(k);
            List<String> l = new ArrayList<>();
            if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
            r.picked.put(k, l);
        }
        JSONObject rb = o.optJSONObject("richBy");
        if (rb != null) for (java.util.Iterator<String> it = rb.keys(); it.hasNext(); ) { String k = it.next(); r.richBy.put(k, rb.optString(k)); }
        return r;
    }

    static List<Cards.Request> all() {
        List<Cards.Request> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(p().getString("tray", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) { }
        return out;
    }

    static void save(List<Cards.Request> l) {
        JSONArray a = new JSONArray();
        try { for (Cards.Request r : l) a.put(toJson(r)); } catch (JSONException ignored) { }
        p().edit().putString("tray", a.toString()).apply();
    }

    static int count() { return all().size(); }

    static void add(Cards.Request r) {
        List<Cards.Request> l = all();
        l.add(r.copy());
        save(l);
    }

    /** The tray's list: tap a card to read and correct it. */
    static void show(Activity a, Runnable changed) {
        List<Cards.Request> l = all();
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 16));
        android.app.Dialog[] d = new android.app.Dialog[1];
        if (l.isEmpty()) box.addView(Ui.hint(a, "Nothing waits here. With “Hold new cards in the tray” on (Settings → Cards), every card you make lands here first."));
        else {
            LinearLayout g = Ui.group(a);
            for (int i = 0; i < l.size(); i++) {
                final int at = i;
                Cards.Request r = l.get(i);
                String sub = (r.sentence.isEmpty() ? "" : r.sentence.replaceAll("\\s+", " ")) + (r.lang.isEmpty() ? "" : "  [" + r.lang + "]");
                Ui.srow(g, r.word, sub, Ui.small(a, "Drop", v -> { List<Cards.Request> n = all(); if (at < n.size()) n.remove(at); save(n); Ui.dismiss(d[0]); show(a, changed); if (changed != null) changed.run(); }),
                        v -> { Ui.dismiss(d[0]); edit(a, at, changed); });
            }
            box.addView(g);
            box.addView(Ui.primary(a, "Make all " + l.size() + " cards", v -> { Ui.dismiss(d[0]); sendAll(a, changed); }));
            if (App.get().pc() != null)                           // 8  the PC makes them: with the audio and the picture cut from its own copy of the video
                box.addView(Ui.button(a, "Make them on the PC", v -> { Ui.dismiss(d[0]); sendToPc(a, changed); }));
            box.addView(Ui.button(a, "Drop them all", v -> Ui.dialog(a).setTitle("Drop all cards in the tray?").setMessage("They are not made.")
                    .setPositiveButton("Drop", (x, w) -> { save(new ArrayList<>()); Ui.dismiss(d[0]); show(a, changed); if (changed != null) changed.run(); })
                    .setNegativeButton("Cancel", null).show()));
        }
        d[0] = Ui.sheet(a, "Card tray", Ui.scroll(a, box), true);
    }

    private static void edit(Activity a, int at, Runnable changed) {
        List<Cards.Request> l = all();
        if (at >= l.size()) return;
        Cards.Request r = l.get(at);
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 16));
        android.app.Dialog[] d = new android.app.Dialog[1];
        box.addView(Ui.hint(a, "Word"));
        EditText w = Ui.input(a, r.word, "word");
        Ui.add(box, w, 6);
        box.addView(Ui.hint(a, "Sentence"));
        EditText s = Ui.input(a, r.sentence, "sentence");
        s.setMinLines(3); s.setSingleLine(false);
        Ui.add(box, s, 6);
        box.addView(Ui.hint(a, "Translation"));
        EditText t = Ui.input(a, r.translation, "translation");
        t.setMinLines(2); t.setSingleLine(false);
        Ui.add(box, t, 6);
        if (r.kind.equals("video")) box.addView(Ui.hint(a, "From " + r.title + " at " + Ui.time(r.start) + ". The sound and picture are cut when the card is made."));
        box.addView(Ui.primary(a, "Save", v -> {
            List<Cards.Request> n = all();
            if (at < n.size()) {
                Cards.Request x = n.get(at);
                String old = x.word;
                x.word = w.getText().toString().trim();
                if (x.surface.equalsIgnoreCase(old)) x.surface = x.word;
                x.sentence = s.getText().toString().trim();
                x.translation = t.getText().toString().trim();
                save(n);
            }
            Ui.dismiss(d[0]);
            show(a, changed);
        }));
        box.addView(Ui.button(a, "Drop this card", v -> { List<Cards.Request> n = all(); if (at < n.size()) n.remove(at); save(n); Ui.dismiss(d[0]); show(a, changed); if (changed != null) changed.run(); }));
        d[0] = Ui.sheet(a, r.word, Ui.scroll(a, box), true);
    }

    /** The whole tray goes to the PC to be made there (its audio and picture come from the PC's copy of the video, and it needs no AnkiDroid here);
     *  a sync starts at once so they are made now. */
    static void sendToPc(Activity a, Runnable changed) {
        List<Cards.Request> l = all();
        if (l.isEmpty()) return;
        save(new ArrayList<>());
        for (Cards.Request r : l) Cards.keepForPc(a, r, null, null, false);
        Ui.toast(a, l.size() + " card(s) sent to the PC");
        if (changed != null) changed.run();
        if (a instanceof MainActivity) ((MainActivity) a).syncNow();
    }

    /** Makes the cards one after the other; one that fails stays in the tray. */
    static void sendAll(Activity a, Runnable changed) {
        List<Cards.Request> l = all();
        if (l.isEmpty()) return;
        save(new ArrayList<>());
        sending = true;
        sendNext(a, l, 0, changed);
    }

    private static void sendNext(Activity a, List<Cards.Request> l, int k, Runnable changed) {
        if (k >= l.size()) {
            sending = false;
            Ui.toast(a, "The tray is empty: " + l.size() + " cards sent");
            if (changed != null) changed.run();
            return;
        }
        Cards.make(a, l.get(k), () -> sendNext(a, l, k + 1, changed));
    }
}
