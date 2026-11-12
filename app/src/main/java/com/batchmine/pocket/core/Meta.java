package com.batchmine.pocket.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The details of a series or a book on the phone (the same as on the PC, mine_meta.py): description, tags, aired / published, who made it,
 * level, stars, status, notes. An entry is a Map; the tags the AI suggests wait in "suggest" until they are accepted. Kept as JSON by the app.
 */
public final class Meta {
    private Meta() { }

    public static final String[] TEXT = {"desc", "aired", "orig", "creator", "country", "level", "notes", "episodes", "original_lang"};
    public static final String[] LEVELS = {"", "A1", "A2", "B1", "B2", "C1", "C2"};

    public static String tag(String s) {
        String t = s == null ? "" : s.replaceAll("[#,;\\n\\r\\t]+", " ").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return t.length() > 32 ? t.substring(0, 32).trim() : t;
    }

    /** Tags tidied: lower case, no duplicates, at most 40. Accepts "a, b; c". */
    public static List<String> tags(Object o) {
        List<String> out = new ArrayList<>();
        List<String> raw = new ArrayList<>();
        if (o instanceof List) for (Object x : (List<?>) o) raw.add(String.valueOf(x));
        else if (o != null) for (String x : String.valueOf(o).split("[,;\\n]")) raw.add(x);
        for (String x : raw) { String t = tag(x); if (!t.isEmpty() && !out.contains(t)) out.add(t); }
        return out.size() > 40 ? out.subList(0, 40) : out;
    }

    @SuppressWarnings("unchecked")
    public static List<String> list(Map<String, Object> e, String k) {
        Object v = e.get(k);
        return v instanceof List ? tags(v) : new ArrayList<>();
    }

    /** Sets some fields (text fields; "tags" as a list or comma text; "rating" 0–5; "status"); empty removes. Stamps the time. */
    public static void update(Map<String, Object> e, Map<String, Object> fields, long now) {
        List<String> ai = new ArrayList<>();
        for (Object x : e.get("ai") instanceof List ? (List<?>) e.get("ai") : new ArrayList<>()) ai.add(String.valueOf(x));
        for (Map.Entry<String, Object> f : fields.entrySet()) {
            String k = f.getKey();
            if (k.equals("tags")) {
                e.put("tags", tags(f.getValue()));
                List<String> sug = list(e, "suggest"); sug.removeAll(list(e, "tags")); e.put("suggest", sug);
            } else if (k.equals("rating")) {
                int r = 0; try { r = Math.max(0, Math.min(5, Integer.parseInt(String.valueOf(f.getValue()).trim()))); } catch (RuntimeException ignored) { /* 0 */ }
                if (r > 0) e.put("rating", (long) r); else e.remove("rating");
            } else if (k.equals("status")) {
                String v = String.valueOf(f.getValue());
                if (v.equals("want") || v.equals("now") || v.equals("done") || v.equals("dropped")) e.put("status", v); else e.remove("status");
            } else if (java.util.Arrays.asList(TEXT).contains(k)) {
                String v = f.getValue() == null ? "" : String.valueOf(f.getValue()).replaceAll("[ \\t]+", " ").trim();
                if (k.equals("level") && !java.util.Arrays.asList(LEVELS).contains(v.toUpperCase(Locale.ROOT))) v = "";
                if (k.equals("level")) v = v.toUpperCase(Locale.ROOT);
                if (v.isEmpty()) e.remove(k); else e.put(k, v.length() > 4000 ? v.substring(0, 4000) : v);
                ai.remove(k);                                   // what you write is no longer "the AI's"
            }
        }
        if (ai.isEmpty()) e.remove("ai"); else e.put("ai", ai);
        for (String k : new String[]{"suggest", "rejected"}) if (e.get(k) instanceof List && ((List<?>) e.get(k)).isEmpty()) e.remove(k);
        e.put("t", now);
    }

    /** Takes suggested tags in (ACCEPT) or turns them down (REJECT: not offered again). */
    public static void decide(Map<String, Object> e, List<String> accept, List<String> reject, long now) {
        List<String> tags = list(e, "tags"), sug = list(e, "suggest"), rej = list(e, "rejected");
        for (String a : tags(accept)) { if (!tags.contains(a)) tags.add(a); sug.remove(a); }
        for (String r : tags(reject)) { sug.remove(r); if (!rej.contains(r)) rej.add(r); }
        e.put("tags", tags); e.put("suggest", sug); e.put("rejected", rej);
        for (String k : new String[]{"tags", "suggest", "rejected"}) if (((List<?>) e.get(k)).isEmpty()) e.remove(k);
        e.put("t", now);
    }

    /** What the book's file said (EpubMeta) goes into the empty fields only. Returns true when something was added. */
    public static boolean fromEpub(Map<String, Object> e, Map<String, String> m, long now) {
        boolean any = false;
        Map<String, String> map = new LinkedHashMap<>();
        map.put("creator", "creator"); map.put("aired", "aired"); map.put("desc", "desc"); map.put("original_lang", "language");
        for (Map.Entry<String, String> x : map.entrySet()) {
            String v = m.get(x.getValue());
            if (v != null && !v.isEmpty() && !e.containsKey(x.getKey())) { e.put(x.getKey(), v); any = true; }
        }
        if (any) e.put("t", now);
        return any;
    }

    /** An entry from the PC replaces the phone's when it is newer; the phone keeps what it knows of its own link. Returns true when taken. */
    public static boolean takeNewer(Map<String, Object> mine, Map<String, Object> theirs) {
        long a = mine.get("t") instanceof Number ? ((Number) mine.get("t")).longValue() : 0, b = theirs.get("t") instanceof Number ? ((Number) theirs.get("t")).longValue() : 0;
        if (b <= a) return false;
        Object link = mine.get("pckey");
        mine.clear();
        mine.putAll(theirs);
        if (link != null && !mine.containsKey("pckey")) mine.put("pckey", link);
        return true;
    }

    /** "Miroslav Krleža · 1932 · B2 · ★★★★☆" */
    public static String line(Map<String, Object> e) {
        List<String> bits = new ArrayList<>();
        for (String k : new String[]{"creator", "aired", "level"}) if (e.get(k) != null && !String.valueOf(e.get(k)).isEmpty()) bits.add(String.valueOf(e.get(k)));
        if (e.get("rating") instanceof Number) {
            int r = ((Number) e.get("rating")).intValue();
            StringBuilder s = new StringBuilder();
            for (int i = 1; i <= 5; i++) s.append(i <= r ? '★' : '☆');
            bits.add(s.toString());
        }
        return String.join(" · ", bits);
    }

    /** The folder rules when a folder of videos is added: [series, season] of a file at REL (the path below the chosen folder, folders only), ROOT = the chosen folder's name. */
    public static String[] folderShow(String root, String[] rel) {
        if (rel.length == 0) return new String[]{root, "0"};
        String series = rel[0];
        int season = 0;
        for (int i = rel.length - 1; i >= 0 && season == 0; i--) season = Shows.seasonOfFolder(rel[i]);
        if (Shows.seasonOfFolder(series) > 0) { series = root; }       // Root/Season 1/…: the chosen folder is the series
        return new String[]{series, String.valueOf(season)};
    }
}
