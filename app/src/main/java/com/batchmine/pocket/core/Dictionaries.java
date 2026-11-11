package com.batchmine.pocket.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * All imported dictionaries (each in its own folder under ROOT), and word lookup across them: the word as written,
 * in lower case and capitalised, the part after an elision (l'acqua -> acqua), and through the inflected-form table to the
 * dictionary form (mangiato -> mangiare).
 */
public final class Dictionaries {
    public final File root;
    private final List<Dict> dicts = new ArrayList<>();
    private final Map<String, Set<String>> lemmaCache = Collections.synchronizedMap(new LinkedHashMap<String, Set<String>>(4096, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Set<String>> e) { return size() > 20000; }
    });

    public Dictionaries(File root) { this.root = root; root.mkdirs(); reload(); }

    public synchronized void reload() {
        for (Dict d : dicts) try { d.close(); } catch (IOException ignored) { }
        dicts.clear();
        lemmaCache.clear();
        File[] kids = root.listFiles();
        if (kids == null) return;
        java.util.Arrays.sort(kids);
        for (File k : kids) {
            if (!k.isDirectory() || k.getName().startsWith(".")) {
                if (k.getName().startsWith(".import-")) Dict.deleteTree(k);   // an import that was cut off
                continue;
            }
            if (!new File(k, "meta.json").isFile()) continue;
            try { dicts.add(new Dict(k)); } catch (IOException | RuntimeException ignored) { }
        }
    }

    public synchronized List<Dict> list() { return new ArrayList<>(dicts); }

    public synchronized List<Dict> forLang(String lang) {
        List<Dict> out = new ArrayList<>();
        for (Dict d : dicts) if (lang == null || lang.isEmpty() || d.lang.isEmpty() || d.lang.equals(lang)) out.add(d);
        return out;
    }

    public synchronized void remove(Dict d) {
        try { d.close(); } catch (IOException ignored) { }
        Dict.deleteTree(d.dir);
        reload();
    }

    /** The CSS of the dictionaries of LANG together (for showing their entries). */
    public String styles(String lang) {
        StringBuilder sb = new StringBuilder();
        for (Dict d : forLang(lang)) sb.append(d.styles()).append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- lookup
    public static final class Hit {
        public final String dict, term, pos, html, via;
        public final List<String> glosses;
        Hit(String dict, String term, String pos, String html, List<String> glosses, String via) {
            this.dict = dict; this.term = term; this.pos = pos; this.html = html; this.glosses = glosses; this.via = via;
        }
    }

    public static final class Result {
        public final String word;
        public final List<Hit> hits = new ArrayList<>();
        public final Map<String, String> ipa = new LinkedHashMap<>();   // term -> "/ipa/  /ipa/"
        public long freq = -1;                                           // best (lowest) frequency rank over the dictionaries, -1: none
        Result(String word) { this.word = word; }
        public boolean found() { return !hits.isEmpty(); }
        /** The dictionary form to make a card for: the first headword found, else the word itself. */
        public String lemma() { return hits.isEmpty() ? word : hits.get(0).term; }
    }

    /** The meanings of every dictionary that has the word, one block per dictionary: "Dictionary: 1. … 2. …" (HTML). */
    public static String allMeanings(Result r) { return allMeanings(r, null); }

    /** Same, but only the entries whose number (position in r.hits) is in ONLY; null: all. */
    public static String allMeanings(Result r, Set<Integer> only) {
        java.util.Map<String, List<String>> by = new LinkedHashMap<>();
        for (int hi = 0; hi < r.hits.size(); hi++) {
            if (only != null && !only.contains(hi)) continue;
            Hit h = r.hits.get(hi);
            List<String> l = by.get(h.dict);
            if (l == null) by.put(h.dict, l = new ArrayList<>());
            for (String g : h.glosses) if (!g.isEmpty() && !l.contains(g)) l.add(g);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : by.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append("<br>");
            if (by.size() > 1) sb.append("<i>").append(e.getKey().replace("&", "&amp;").replace("<", "&lt;")).append("</i>");
            sb.append("<ol>");
            int n = 0;
            for (String g : e.getValue()) { if (++n > 8) break; sb.append("<li>").append(g.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</li>"); }
            sb.append("</ol>");
        }
        return sb.toString();
    }

    /** The spellings to look a word up by. */
    public static List<String> variants(String w) {
        LinkedHashSet<String> v = new LinkedHashSet<>();
        w = w.trim().replace('’', '\'');
        if (w.isEmpty()) return new ArrayList<>(v);
        v.add(w);
        String low = w.toLowerCase();
        v.add(low);
        v.add(Character.toUpperCase(low.charAt(0)) + low.substring(1));
        int ap = w.indexOf('\'');
        if (ap > 0 && ap < w.length() - 1) {                           // l'acqua, dell'anno, c'era -> the part after it
            String after = w.substring(ap + 1);
            v.add(after);
            v.add(after.toLowerCase());
        }
        if (w.endsWith("'")) v.add(low.substring(0, low.length() - 1));
        return new ArrayList<>(v);
    }

    /** Italian verbs with pronouns joined on (sbrigiamoci, dammelo, farlo, vederla): the verb alone. */
    private static final String[] IT_CLITICS = {"glielo", "gliela", "glieli", "gliele", "gliene", "gli", "ci", "vi", "si", "mi", "ti", "ne",
            "lo", "la", "li", "le", "me", "te", "ce", "ve", "se"};

    public static List<String> withoutClitics(String w, String lang) {
        List<String> out = new ArrayList<>();
        if (!"it".equals(lang)) return out;
        String low = w.toLowerCase();
        for (int round = 0; round < 2; round++) {
            boolean cut = false;
            for (String c : IT_CLITICS) {
                if (low.length() > c.length() + 2 && low.endsWith(c)) {
                    low = low.substring(0, low.length() - c.length());
                    cut = true;
                    break;
                }
            }
            if (!cut) break;
            if (low.endsWith("ar") || low.endsWith("er") || low.endsWith("ir")) out.add(low + "e");   // farlo -> fare
            if (low.length() > 2 && low.charAt(low.length() - 1) == low.charAt(low.length() - 2)) out.add(low.substring(0, low.length() - 1)); // dammi -> da
            out.add(low);
        }
        return out;
    }

    public Result lookup(String word, String lang) {
        Result r = lookupOnce(word, lang);
        if (!r.found()) {
            for (String v : withoutClitics(word, lang)) {
                Result r2 = lookupOnce(v, lang);
                if (r2.found()) { Result o = new Result(word); o.hits.addAll(r2.hits); o.ipa.putAll(r2.ipa); o.freq = r2.freq; return o; }
            }
        }
        return r;
    }

    private Result lookupOnce(String word, String lang) {
        Result r = new Result(word);
        Set<String> seen = new LinkedHashSet<>();
        List<Dict> ds = forLang(lang);
        try {
            for (String v : variants(word)) {
                for (Dict d : ds) if (d.terms != null) for (String s : d.terms.get(v)) addHit(r, d, s, null, seen);
            }
            for (String v : variants(word)) {
                for (Dict d : ds) {
                    if (d.forms == null) continue;
                    for (String f : d.forms.get(v)) {
                        int i = f.indexOf('\u0001');
                        String lemma = i < 0 ? f : f.substring(0, i), tags = i < 0 ? "" : f.substring(i + 1);
                        String via = v + " → " + lemma + (tags.isEmpty() ? "" : " (" + tags + ")");
                        for (Dict d2 : ds) if (d2.terms != null) for (String s : d2.terms.get(lemma)) addHit(r, d2, s, via, seen);
                    }
                }
            }
            Set<String> terms = new LinkedHashSet<>();
            for (Hit h : r.hits) terms.add(h.term);
            if (terms.isEmpty()) terms.addAll(variants(word));
            for (String t : terms) {
                for (Dict d : ds) {
                    if (d.ipa == null) continue;
                    List<String> p = d.ipa.get(t);
                    if (!p.isEmpty() && !r.ipa.containsKey(t)) r.ipa.put(t, p.get(0));
                }
                for (Dict d : ds) {
                    if (d.freq == null) continue;
                    for (String fv : d.freq.get(t)) {
                        try { long n = Long.parseLong(fv.trim()); if (n > 0 && (r.freq < 0 || n < r.freq)) r.freq = n; } catch (NumberFormatException ignored) { }
                    }
                }
            }
        } catch (IOException ignored) { }
        return r;
    }

    private static void addHit(Result r, Dict d, String json, String via, Set<String> seen) {
        if (!seen.add(d.title + "\u0000" + json.hashCode())) return;
        Map<String, Object> m = Json.obj(Json.parse(json));
        List<String> gl = new ArrayList<>();
        for (Object g : Json.arr(m.get("g"))) gl.add(Json.str(g, ""));
        r.hits.add(new Hit(d.title, Json.str(m.get("t"), ""), Json.str(m.get("p"), ""), Json.str(m.get("h"), ""), gl, via));
    }

    /** The forms a word can stand for when colouring it: itself in lower case and its dictionary forms (no definitions read). */
    public Set<String> candidates(String word, String lang) {
        String key = lang + "\u0000" + word;
        Set<String> hit = lemmaCache.get(key);
        if (hit != null) return hit;
        Set<String> out = new LinkedHashSet<>();
        List<Dict> ds = forLang(lang);
        try {
            for (String v : variants(word)) {
                out.add(v.toLowerCase());
                for (Dict d : ds) {
                    if (d.forms == null) continue;
                    for (String f : d.forms.get(v)) {
                        int i = f.indexOf('\u0001');
                        out.add((i < 0 ? f : f.substring(0, i)).toLowerCase());
                    }
                }
            }
            if (out.size() == 1) for (String v : withoutClitics(word, lang)) {
                for (Dict d : ds) {
                    if (d.forms == null) continue;
                    for (String f : d.forms.get(v)) {
                        int i = f.indexOf('\u0001');
                        out.add((i < 0 ? f : f.substring(0, i)).toLowerCase());
                    }
                    if (d.terms != null && !d.terms.get(v).isEmpty()) out.add(v);
                }
            }
        } catch (IOException ignored) { }
        lemmaCache.put(key, out);
        return out;
    }

    /** Is the word (or one of its dictionary forms) a headword in any dictionary of LANG? */
    public boolean listed(Set<String> cands, String lang) {
        try {
            for (Dict d : forLang(lang)) {
                if (d.terms == null) continue;
                for (String c : cands) {
                    if (!d.terms.get(c).isEmpty()) return true;
                    String cap = c.isEmpty() ? c : Character.toUpperCase(c.charAt(0)) + c.substring(1);
                    if (!d.terms.get(cap).isEmpty()) return true;
                }
            }
        } catch (IOException ignored) { }
        return false;
    }

    public boolean hasTerms(String lang) {
        for (Dict d : forLang(lang)) if (d.terms != null) return true;
        return false;
    }

    /** Headwords starting with PREFIX, for the search box. */
    public List<String> suggest(String prefix, String lang, int limit) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            for (Dict d : forLang(lang)) if (d.terms != null) out.addAll(d.terms.keysWithPrefix(prefix, limit));
        } catch (IOException ignored) { }
        List<String> l = new ArrayList<>(out);
        return l.size() > limit ? l.subList(0, limit) : l;
    }
}
