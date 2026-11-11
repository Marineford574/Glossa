package com.batchmine.pocket;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * Lines of videos to come back to, like the PC's Review list: lines you marked (★) and lines you stumbled on (replayed,
 * or tapped a word in). Kept on the phone, per video file name, as "start \t kind \t text" in one string set.
 */
final class Marks {
    private Marks() { }

    static final class Line {
        final double start; final String kind, text;
        Line(double start, String kind, String text) { this.start = start; this.kind = kind; this.text = text; }
        boolean marked() { return "m".equals(kind); }
    }

    private static SharedPreferences sp() { return App.get().prefs(); }
    private static String key(String video) { return "mk_" + video; }

    static synchronized void add(String video, double start, String text, boolean marked) {
        if (text == null || text.trim().isEmpty()) return;
        TreeMap<Long, Line> m = load(video);
        long k = Math.round(start * 10);
        Line old = m.get(k);
        if (old != null && old.marked() && !marked) return;            // a mark stays a mark
        if (old != null && old.marked() && marked) { m.remove(k); save(video, m); return; }     // ★ again takes it off
        m.put(k, new Line(start, marked ? "m" : "h", text.replace('\n', ' ').replace('\t', ' ').trim()));
        save(video, m);
    }

    static synchronized boolean isMarked(String video, double start) {
        Line l = load(video).get(Math.round(start * 10));
        return l != null && l.marked();
    }

    static synchronized List<Line> list(String video) { return new ArrayList<>(load(video).values()); }

    static synchronized void remove(String video, double start) {
        TreeMap<Long, Line> m = load(video);
        m.remove(Math.round(start * 10));
        save(video, m);
    }

    static synchronized void clear(String video) { sp().edit().remove(key(video)).apply(); }

    /** {marked, hard} counts. */
    static synchronized int[] counts(String video) {
        int mk = 0, hd = 0;
        for (Line l : load(video).values()) if (l.marked()) mk++; else hd++;
        return new int[]{mk, hd};
    }

    private static TreeMap<Long, Line> load(String video) {
        TreeMap<Long, Line> m = new TreeMap<>();
        Set<String> raw = sp().getStringSet(key(video), null);
        if (raw == null) return m;
        for (String r : raw) {
            String[] p = r.split("\t", 3);
            if (p.length < 3) continue;
            try { double s = Double.parseDouble(p[0]); m.put(Math.round(s * 10), new Line(s, p[1], p[2])); } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    private static void save(String video, TreeMap<Long, Line> m) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (Line l : m.values()) out.add(String.format(Locale.ROOT, "%.2f", l.start) + "\t" + l.kind + "\t" + l.text);
        SharedPreferences.Editor ed = sp().edit();
        if (out.isEmpty()) ed.remove(key(video)); else ed.putStringSet(key(video), out);
        ed.apply();
    }
}
