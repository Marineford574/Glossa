package com.batchmine.pocket.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which show a video file belongs to and which episode it is, from its name alone (the phone keeps every video in one
 * folder, so the series are found from the names): "Il commissario Montalbano S02E03.mkv" -> show "Il commissario
 * Montalbano", season 2, episode 3. Also "2x03", "Ep 3", "Episodio 3", "Folge 3", "Epizoda 3", "- 03", "_03".
 */
public final class Shows {
    private Shows() { }

    public static final class Ep {
        public final String show;       // "" = not part of a series (a film)
        public final int season, episode;
        Ep(String show, int season, int episode) { this.show = show; this.season = season; this.episode = episode; }
        /** For sorting: season, then episode. */
        public long order() { return season * 100000L + episode; }
        public String label() { return episode < 0 ? "" : (season > 0 ? "S" + season + " · " : "") + "Ep " + episode; }
    }

    private static final Pattern SXE = Pattern.compile("(?i)(?:^|[\\s._\\-\\[(])s(\\d{1,2})[\\s._-]*e(\\d{1,3})(?!\\d)");
    private static final Pattern NXN = Pattern.compile("(?i)(?:^|[\\s._\\-\\[(])(\\d{1,2})x(\\d{2,3})(?!\\d)");
    private static final Pattern WORD = Pattern.compile("(?i)(?:^|[\\s._\\-\\[(])(?:ep|eps|episode|episodio|ep\\.|folge|epizoda|épisode|episodio n\\.?)[\\s._]*(\\d{1,3})(?!\\d)");
    private static final Pattern TAIL = Pattern.compile("(?:[\\s._]+-[\\s._]*|[\\s._]+|_)(\\d{1,3})(?:[\\s._]*(?:\\[[^\\]]*\\]|\\([^)]*\\)))*$");

    public static Ep of(String fileName) {
        String n = fileName.replaceAll("\\.[A-Za-z0-9]{2,5}$", "");          // extension
        n = n.replaceAll("\\.vlink$", "");
        n = n.replaceAll("^\\s*\\[[^\\]]*\\]\\s*", "");                          // [Group] at the start
        Matcher m = SXE.matcher(n);
        if (m.find()) return new Ep(clean(n.substring(0, m.start())), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        m = NXN.matcher(n);
        if (m.find()) return new Ep(clean(n.substring(0, m.start())), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        m = WORD.matcher(n);
        if (m.find()) return new Ep(clean(n.substring(0, m.start())), 0, Integer.parseInt(m.group(1)));
        m = TAIL.matcher(n);
        if (m.find() && m.start() > 0) {
            int e = Integer.parseInt(m.group(1));
            String head = n.substring(0, m.start());
            if (!head.matches(".*\\b(19|20)\\d\\d$") || e < 100) return new Ep(clean(head), 0, e);
        }
        return new Ep("", 0, -1);
    }

    static String clean(String s) {
        s = s.replaceAll("[._]+", " ").replaceAll("\\s*[-–—:]+\\s*$", "").replaceAll("\\s+", " ").trim();
        s = s.replaceAll("(?i)\\s*(season|stagione|staffel|sezona)\\s*\\d+$", "").trim();
        return s;
    }

    /** The key two names of the same show share (case and punctuation do not count). */
    public static String key(String show) { return show.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }
}
