package com.batchmine.pocket.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Subtitles (.srt, .vtt, .ass / .ssa) as a list of lines with times, and the words in a line. */
public final class Subs {
    private Subs() { }

    public static final class Cue {
        public final double start, end;
        public final String text;
        public Cue(double start, double end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    private static final Pattern TIME = Pattern.compile(
            "(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})");

    /** The text of a subtitle file: UTF-8 or UTF-16 (with or without a BOM), else Windows-1252 (old German / Italian files). */
    public static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF)
            return new String(b, 3, b.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new String(b, 2, b.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new String(b, 2, b.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
        int n = Math.min(b.length, 4000), zeroOdd = 0, zeroEven = 0;
        for (int i = 0; i < n; i++) if (b[i] == 0) { if ((i & 1) == 1) zeroOdd++; else zeroEven++; }
        if (zeroOdd > n / 4) return new String(b, java.nio.charset.StandardCharsets.UTF_16LE);
        if (zeroEven > n / 4) return new String(b, java.nio.charset.StandardCharsets.UTF_16BE);
        java.nio.charset.CharsetDecoder d = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
        try { return d.decode(java.nio.ByteBuffer.wrap(b)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { return new String(b, java.nio.charset.Charset.forName("windows-1252")); }
    }

    private static final Pattern ASS_TIME = Pattern.compile("(\\d+):(\\d{1,2}):(\\d{1,2})[.:](\\d{1,3})");

    /** An .ass / .ssa file: the Dialogue lines of [Events], drawings and comments left out, duplicates (layers) merged. */
    public static List<Cue> parseAss(String text) {
        List<Cue> out = new ArrayList<>();
        int iStart = 1, iEnd = 2, iText = 9, fields = 10;
        boolean events = false;
        java.util.Set<String> had = new java.util.HashSet<>();
        for (String raw : text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String line = raw.trim();
            if (line.startsWith("[")) { events = line.equalsIgnoreCase("[Events]"); continue; }
            if (!events) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String kind = line.substring(0, colon).trim(), rest = line.substring(colon + 1).trim();
            if (kind.equalsIgnoreCase("Format")) {
                String[] f = rest.split(",");
                fields = f.length;
                for (int i = 0; i < f.length; i++) {
                    String k = f[i].trim().toLowerCase(java.util.Locale.ROOT);
                    if (k.equals("start")) iStart = i; else if (k.equals("end")) iEnd = i; else if (k.equals("text")) iText = i;
                }
                continue;
            }
            if (!kind.equalsIgnoreCase("Dialogue")) continue;
            String[] p = rest.split(",", fields);
            if (p.length <= Math.max(iText, Math.max(iStart, iEnd))) continue;
            String body = p[iText];
            if (body.matches("(?s).*\\{[^}]*\\\\p[1-9][^}]*\\}.*")) continue;                 // a drawing, not words
            Double a = assTime(p[iStart]), b = assTime(p[iEnd]);
            if (a == null || b == null || b <= a) continue;
            String clean = clean(body.replace("\\n", "\n").replace("\\h", " "));
            if (clean.isEmpty()) continue;
            if (!had.add(a + "|" + b + "|" + clean)) continue;
            out.add(new Cue(a, b, clean));
        }
        out.sort((x, y) -> Double.compare(x.start, y.start));
        return out;
    }

    private static Double assTime(String s) {
        Matcher m = ASS_TIME.matcher(s.trim());
        if (!m.find()) return null;
        String f = m.group(4);
        double frac = f.length() == 2 ? Integer.parseInt(f) / 100.0 : f.length() == 1 ? Integer.parseInt(f) / 10.0 : Integer.parseInt(f) / 1000.0;
        return Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3)) + frac;
    }

    public static List<Cue> parse(String text) {
        if (text.contains("[Events]") || text.contains("\nDialogue:")) return parseAss(text);
        List<Cue> out = new ArrayList<>();
        String[] lines = text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIME.matcher(lines[i]);
            if (!m.find()) continue;
            double a = t(m.group(1), m.group(2), m.group(3), m.group(4)), b = t(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder sb = new StringBuilder();
            int j = i + 1;
            for (; j < lines.length && !lines[j].trim().isEmpty(); j++) {
                if (TIME.matcher(lines[j]).find()) break;
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[j]);
            }
            i = j - 1;
            String clean = clean(sb.toString());
            if (!clean.isEmpty() && b > a) out.add(new Cue(a, b, clean));
        }
        out.sort((x, y) -> Double.compare(x.start, y.start));
        return out;
    }

    private static double t(String h, String m, String s, String ms) {
        String f = (ms + "00").substring(0, 3);
        return (h == null ? 0 : Integer.parseInt(h)) * 3600 + Integer.parseInt(m) * 60 + Integer.parseInt(s) + Integer.parseInt(f) / 1000.0;
    }

    /** Without formatting: <i>, {\an8}, ASS drawing codes, speaker dashes kept, spaces tidied. */
    public static String clean(String s) {
        s = s.replaceAll("\\{\\\\[^}]*\\}", "").replaceAll("</?[a-zA-Z][^>]*>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("\\N", "\n");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim().replaceAll("\\s+", " ");
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    /** The line shown at time T (seconds), or -1. */
    public static int at(List<Cue> cues, double t) {
        int lo = 0, hi = cues.size() - 1, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cues.get(mid).start <= t) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        for (int i = best; i >= 0 && i > best - 4; i--) if (cues.get(i).start <= t && t < cues.get(i).end) return i;
        return -1;
    }

    /** The line that is on or last ended before T (for "repeat the line"), or -1. */
    public static int around(List<Cue> cues, double t) {
        int i = at(cues, t);
        if (i >= 0) return i;
        int best = -1;
        for (int k = 0; k < cues.size(); k++) if (cues.get(k).start <= t) best = k; else break;
        return best;
    }

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+(?:['’][\\p{L}\\p{M}]+)*['’]?");

    /** The words of TEXT as [start, end) pairs. */
    public static List<int[]> words(String text) {
        java.util.function.Predicate<String> ja = Ja.LISTED;
        if (ja != null && Ja.isJa(text)) return Ja.segment(text, ja);        // Japanese has no spaces: cut by the dictionary
        List<int[]> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) out.add(new int[]{m.start(), m.end()});
        return out;
    }

    // ---------------------------------------------------------------- editing the lines (the PC player's line editor)
    /** The lines as an .srt file. */
    public static String toSrt(List<Cue> cues) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Cue c : cues) {
            if (c.text.trim().isEmpty()) continue;
            sb.append(n++).append('\n').append(srtTime(c.start)).append(" --> ").append(srtTime(c.end)).append('\n').append(c.text.trim()).append("\n\n");
        }
        return sb.toString();
    }

    public static String srtTime(double t) {
        long ms = Math.max(0, Math.round(t * 1000));
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    /** Line I split at character AT of its text: the time is shared by the length of the two parts. */
    public static List<Cue> split(List<Cue> cues, int i, int at) {
        Cue c = cues.get(i);
        String a = c.text.substring(0, Math.max(0, Math.min(at, c.text.length()))).trim(), b = c.text.substring(Math.max(0, Math.min(at, c.text.length()))).trim();
        if (a.isEmpty() || b.isEmpty()) return cues;
        double mid = c.start + (c.end - c.start) * a.length() / (double) (a.length() + b.length());
        List<Cue> out = new java.util.ArrayList<>(cues);
        out.set(i, new Cue(c.start, mid, a));
        out.add(i + 1, new Cue(mid, c.end, b));
        return out;
    }

    /** Line I and the one after it as one line. */
    public static List<Cue> mergeNext(List<Cue> cues, int i) {
        if (i < 0 || i + 1 >= cues.size()) return cues;
        Cue a = cues.get(i), b = cues.get(i + 1);
        List<Cue> out = new java.util.ArrayList<>(cues);
        out.set(i, new Cue(a.start, Math.max(a.end, b.end), a.text.trim() + "\n" + b.text.trim()));
        out.remove(i + 1);
        return out;
    }

    /** Every line D seconds later (− earlier); nothing before 0. */
    public static List<Cue> shift(List<Cue> cues, double d) {
        List<Cue> out = new java.util.ArrayList<>();
        for (Cue c : cues) out.add(new Cue(Math.max(0, c.start + d), Math.max(0, c.end + d), c.text));
        return out;
    }
}
