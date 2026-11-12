package com.batchmine.pocket.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Finds an intro (title song, recap) or credits by comparing the subtitles of two episodes of the same series: lines that say the same thing in both, close together in time. */
public final class IntroFinder {
    private IntroFinder() { }

    private static String norm(String t) { return t.toLowerCase(Locale.ROOT).replaceAll("<[^>]*>|[^\\p{L}]+", ""); }

    /**
     * The stretch of B (seconds, {from, to}) made of lines that also appear in A, both inside the windows [aLo, aHi] and [bLo, bHi].
     * At least MIN matching lines, each less than GAP seconds after the one before. Null when there is no such stretch.
     */
    public static double[] common(List<Subs.Cue> a, List<Subs.Cue> b, double aLo, double aHi, double bLo, double bHi, int min, double gap) {
        Set<String> inA = new HashSet<>();
        for (Subs.Cue c : a) if (c.start >= aLo && c.start <= aHi) { String n = norm(c.text); if (n.length() >= 8) inA.add(n); }
        List<Subs.Cue> hits = new ArrayList<>();
        for (Subs.Cue c : b) if (c.start >= bLo && c.start <= bHi && inA.contains(norm(c.text))) hits.add(c);
        double[] best = null;
        int bestN = 0, from = 0;
        for (int i = 1; i <= hits.size(); i++) {
            if (i == hits.size() || hits.get(i).start - hits.get(i - 1).end > gap) {
                int n = i - from;
                if (n >= min && n > bestN) { bestN = n; best = new double[]{hits.get(from).start, hits.get(i - 1).end}; }
                from = i;
            }
        }
        return best;
    }

    /** The intro of B (its end is where the show starts): the common stretch in the first MAX seconds of both. */
    public static double[] intro(List<Subs.Cue> a, List<Subs.Cue> b) { return common(a, b, 0, 420, 0, 420, 3, 40); }

    /** The credits of B: the common stretch in the last MAX seconds; the durations are those of the videos. */
    public static double[] credits(List<Subs.Cue> a, double durA, List<Subs.Cue> b, double durB) {
        return common(a, b, Math.max(0, durA - 420), durA, Math.max(0, durB - 420), durB, 3, 40);
    }
}
