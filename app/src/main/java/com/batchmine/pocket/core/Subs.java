package com.batchmine.pocket.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Subtitles (.srt and .vtt) as a list of lines with times, and the words in a line. */
public final class Subs {
    private Subs() { }

    public static final class Cue {
        public final double start, end;
        public final String text;
        public Cue(double start, double end, String text) { this.start = start; this.end = end; this.text = text; }
    }

    private static final Pattern TIME = Pattern.compile(
            "(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(?:(\\d+):)?(\\d{1,2}):(\\d{2})[,.](\\d{1,3})");

    public static List<Cue> parse(String text) {
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
        s = s.replaceAll("\\{\\\\[^}]*}", "").replaceAll("</?[a-zA-Z][^>]*>", "")
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
        List<int[]> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) out.add(new int[]{m.start(), m.end()});
        return out;
    }
}
