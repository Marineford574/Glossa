package com.batchmine.pocket.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dict.cc on the phone: the PC's mine_dictcc.py in Java. A dict.cc vocabulary file (tab separated, two languages) is read once into a small
 * file of this app ("T" lines: a word and its translations; "P" lines: a word of a phrase, the phrase, its translation); a lookup reads that
 * file into memory the first time and then answers at once. Single words give the translations (to tick for the card), phrases the examples.
 */
public final class DictCc {
    static final int MAX_TRANS = 10, MAX_PHRASES = 10, MAX_PHRASE_WORDS = 6, SAMPLE = 20000;
    static final Set<String> CODES = new HashSet<>(Arrays.asList("de", "en", "it", "fr", "es", "pt", "nl", "la", "hr", "sv", "da", "no", "fi", "pl", "cs", "ru", "tr", "hu", "ro", "bg", "el", "is", "eo"));
    static final Set<String> MARKERS = new HashSet<>(Arrays.asList("jdn", "jdm", "jds", "jd", "etw", "sich", "sth", "sb", "akk", "dat", "gen", "qn", "qc", "qlcu", "qlco", "to"));
    static final Pattern NOTE = Pattern.compile("\\{[^}]*\\}|\\[[^\\]]*\\]|<[^>]*>");
    static final Pattern GENDER = Pattern.compile("\\{(?:m|f|n|pl)\\}");
    static final Pattern SBSTH = Pattern.compile("\\s*\\((?:sth|sb)\\.[^)]*\\)");
    static final Pattern WORD = Pattern.compile("[\\p{L}]+(?:['’-][\\p{L}]+)*");

    private DictCc() { }

    /** The language of the translations from the file name ("dict.cc de en vocab.txt" -> EN for German). */
    public static String otherCode(String fileName, String lang) {
        Matcher m = Pattern.compile("[a-z]{2}").matcher(fileName.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String c = m.group();
            if (CODES.contains(c) && !c.equals(lang)) return c.toUpperCase(Locale.ROOT);
        }
        return "EN";
    }

    static int column(List<String[]> rows, String lang, String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (lang.equals("it") || lang.equals("en") || lang.equals("hr")) {
            Matcher m = Pattern.compile("\\b([a-z]{2})\\s+([a-z]{2})\\b").matcher(name);
            if (m.find() && CODES.contains(m.group(1)) && CODES.contains(m.group(2))) return m.group(1).equals(lang) ? 0 : m.group(2).equals(lang) ? 1 : 0;
            return 0;
        }
        long[] sc = new long[2];
        for (String[] r : rows) for (int i = 0; i < 2; i++) {
            sc[i] += 5L * count(GENDER, r[i]);
            for (int k = 0; k < r[i].length(); k++) if ("äöüßÄÖÜ".indexOf(r[i].charAt(k)) >= 0) sc[i]++;
        }
        return sc[1] > sc[0] ? 1 : 0;
    }

    private static int count(Pattern p, String s) { Matcher m = p.matcher(s); int n = 0; while (m.find()) n++; return n; }

    private static String nfc(String s) { return Normalizer.normalize(s, Normalizer.Form.NFC); }

    private static String clean(String s) { return NOTE.matcher(s).replaceAll(" ").trim().replaceAll("\\s+", " "); }

    /** Reads a dict.cc file and writes the app's small file; returns how many words and phrases it holds. */
    public static int convert(InputStream in, String fileName, String lang, OutputStream out) throws IOException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
        List<String[]> head = new ArrayList<>();
        String line;
        while (head.size() < SAMPLE && (line = rd.readLine()) != null) { String[] r = split(line); if (r != null) head.add(r); }
        if (head.isEmpty()) throw new IOException("no dict.cc lines (word <tab> word) in this file");
        int col = column(head, lang, fileName), other = 1 - col;
        Map<String, List<String>> trans = new HashMap<>();
        Map<String, List<String[]>> index = new HashMap<>();       // token -> rows [display, glosses joined by "; ", wordCount]
        Map<String, String[]> phraseRows = new HashMap<>();
        int n = 0;
        List<String[]> pending = head;
        while (true) {
            for (String[] r : pending) n += take(r, col, other, trans, index, phraseRows);
            pending = new ArrayList<>();
            while (pending.size() < 50000 && (line = rd.readLine()) != null) { String[] r = split(line); if (r != null) pending.add(r); }
            if (pending.isEmpty()) break;
        }
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 1 << 16)) {
            w.write("L\t" + otherCode(fileName, lang) + "\t" + lang + "\n");
            for (Map.Entry<String, List<String>> e : trans.entrySet()) {
                w.write("T\t" + e.getKey());
                for (String g : e.getValue()) w.write("\t" + g);
                w.write("\n");
            }
            for (Map.Entry<String, List<String[]>> e : index.entrySet()) {
                List<String[]> lst = e.getValue();
                lst.sort((a, b) -> { int c = Integer.compare(Integer.parseInt(a[2]), Integer.parseInt(b[2])); if (c != 0) return c; c = Integer.compare(a[0].length(), b[0].length()); return c != 0 ? c : a[0].compareTo(b[0]); });
                for (String[] r : lst) w.write("P\t" + e.getKey() + "\t" + r[0] + "\t" + r[1] + "\n");
            }
        }
        return n;
    }

    private static String[] split(String line) {
        if (line.isEmpty() || line.charAt(0) == '#' || line.indexOf('\t') < 0) return null;
        if (line.charAt(0) == '﻿') line = line.substring(1);
        String[] r = line.split("\t");
        return r.length >= 2 && !r[0].trim().isEmpty() ? r : null;
    }

    private static int take(String[] r, int col, int other, Map<String, List<String>> trans, Map<String, List<String[]>> index, Map<String, String[]> phraseRows) {
        String[] toks = NOTE.matcher(r[col]).replaceAll(" ").replace("(", " ").replace(")", " ").trim().split("\\s+");
        List<String> core = new ArrayList<>();
        for (String t : toks) if (!t.isEmpty() && !MARKERS.contains(t.toLowerCase(Locale.ROOT).replaceAll("^[.,;]+|[.,;]+$", ""))) core.add(t);
        if (core.isEmpty()) return 0;
        String gloss = SBSTH.matcher(clean(r[other])).replaceAll("").trim().replaceAll("^[ ;,]+|[ ;,]+$", "");
        if (gloss.isEmpty() || gloss.indexOf('\t') >= 0) return 0;
        if (core.size() == 1) {
            String key = nfc(core.get(0)).toLowerCase(Locale.ROOT);
            List<String> lst = trans.get(key);
            if (lst == null) trans.put(key, lst = new ArrayList<>());
            if (lst.size() < MAX_TRANS) { boolean dup = false; for (String x : lst) if (x.equalsIgnoreCase(gloss)) dup = true; if (!dup) lst.add(gloss); }
            return 1;
        }
        if (core.size() > MAX_PHRASE_WORDS) return 0;
        StringBuilder sb = new StringBuilder();
        for (String t : toks) if (!t.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append(t);
        String display = nfc(sb.toString());
        String[] row = phraseRows.get(display);
        if (row == null) {
            row = new String[]{display, gloss, String.valueOf(core.size())};
            phraseRows.put(display, row);
            Set<String> seen = new LinkedHashSet<>();
            for (String t : core) { Matcher m = WORD.matcher(nfc(t)); while (m.find()) if (m.group().length() > 1) seen.add(m.group().toLowerCase(Locale.ROOT)); }
            for (String tok : seen) {
                List<String[]> lst = index.get(tok);
                if (lst == null) index.put(tok, lst = new ArrayList<>());
                lst.add(row);
                if (lst.size() > MAX_PHRASES) {
                    String[] worst = lst.get(0);
                    for (String[] x : lst) if (Integer.parseInt(x[2]) > Integer.parseInt(worst[2]) || (Integer.parseInt(x[2]) == Integer.parseInt(worst[2]) && x[0].length() > worst[0].length())) worst = x;
                    lst.remove(worst);
                }
            }
            return 1;
        }
        String[] have = row[1].split("; ");
        boolean dup = false;
        for (String x : have) if (x.equalsIgnoreCase(gloss)) dup = true;
        if (have.length < 3 && !dup) row[1] = row[1] + "; " + gloss;
        return 0;
    }

    /** One file of the app, read into memory. */
    public static final class Table {
        public final String label, lang;
        final Map<String, List<String>> words = new HashMap<>();
        final Map<String, List<String[]>> phrases = new HashMap<>();
        Table(String label, String lang) { this.label = label; this.lang = lang; }
    }

    public static Table read(File f) throws IOException {
        try (BufferedReader rd = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8), 1 << 16)) {
            String h = rd.readLine();
            String[] hp = h == null ? new String[0] : h.split("\t");
            Table t = new Table(hp.length > 1 ? hp[1] : "EN", hp.length > 2 ? hp[2] : "");
            String line;
            while ((line = rd.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length >= 3 && p[0].equals("T")) t.words.put(p[1], new ArrayList<>(Arrays.asList(p).subList(2, p.length)));
                else if (p.length >= 4 && p[0].equals("P")) {
                    List<String[]> l = t.phrases.get(p[1]);
                    if (l == null) t.phrases.put(p[1], l = new ArrayList<>());
                    l.add(new String[]{p[2], p[3]});
                }
            }
            return t;
        }
    }

    /** The answer for one word: per file its label, the translations and the phrases. */
    public static final class Found {
        public final String label;
        public final List<String> words;
        public final List<String[]> phrases;     // [phrase, translation]
        Found(String label, List<String> words, List<String[]> phrases) { this.label = label; this.words = words; this.phrases = phrases; }
    }

    public static List<Found> lookup(List<Table> tables, Iterable<String> candidates) {
        List<Found> out = new ArrayList<>();
        for (Table t : tables) {
            for (String c : candidates) {
                String k = nfc(c).toLowerCase(Locale.ROOT);
                List<String> w = t.words.get(k);
                List<String[]> p = t.phrases.get(k);
                if (w != null || p != null) { out.add(new Found(t.label, w == null ? new ArrayList<String>() : w, p == null ? new ArrayList<String[]>() : p)); break; }
            }
        }
        return out;
    }

    /** The block for the word card: folded, the translations as tick boxes (the card's short definition), the phrases under them. */
    public static String html(List<Found> found, boolean open, String color) {
        if (found.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<details class=dcc").append(open ? " open" : "").append("><summary>dict.cc</summary>");
        for (Found f : found) {
            sb.append("<div class=dcch>").append(esc(f.label)).append("</div>");
            if (!f.words.isEmpty()) {
                sb.append("<div>");
                for (String w : f.words)
                    sb.append("<label class=dccw><input type=checkbox class=gpick data-e=-1 data-l='").append(esc(f.label)).append("' data-t='").append(esc(w).replace("'", "&#39;")).append("' onchange='defs()'>").append(esc(w)).append("</label> ");
                sb.append("</div>");
            }
            for (String[] p : f.phrases) sb.append("<div class=dccp><i>").append(esc(p[0])).append("</i> — ").append(esc(p[1])).append("</div>");
        }
        return sb.append("</details>").toString();
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
