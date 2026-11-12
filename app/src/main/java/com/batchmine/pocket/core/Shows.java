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
        /** The same episode with a show name (and season) taken from its folder, where the file name has none. */
        public Ep with(String showName, int seasonNo) {
            return new Ep(show.isEmpty() && showName != null ? showName : show, season <= 0 && seasonNo > 0 ? seasonNo : season, episode);
        }
        public Ep withEpisode(int n) { return new Ep(show, season, n); }
        /** For sorting: season, then episode. */
        public long order() { return season * 100000L + episode; }
        public String label() { return episode < 0 ? "" : (season > 0 ? "S" + season + " · " : "") + "Ep " + episode; }
        /** "Season 1 · Episode 3" (for the line under a title). */
        public String longLabel() { return episode < 0 ? "" : (season > 0 ? "Season " + season + " · " : "") + "Episode " + episode; }
    }

    // "Staffel 1 Folge 3", "Season 1 Episode 3", "Stagione 1 Episodio 3", "Sezona 1 Epizoda 3"
    private static final Pattern LONG = Pattern.compile("(?i)(?:^|[\\s._\\-\\[(])(?:staffel|season|stagione|sezona|saison|temporada)[\\s._-]*(\\d{1,2})[\\s._-]*(?:folge|episode|episodio|epizoda|épisode|ep|e)[\\s._-]*(\\d{1,3})(?!\\d)");
    // "S1_01", "S1 - 01", "S01.03" (a season mark, then the episode number on its own)
    private static final Pattern S_N = Pattern.compile("(?i)(?:^|[\\s._\\-\\[(])s(\\d{1,2})[\\s._-]+(\\d{1,3})(?!\\d)");
    private static final Pattern SXE = Pattern.compile("(?i)(?:^|[\\s._\\-\\[(])s(\\d{1,2})[\\s._-]*e(\\d{1,3})(?!\\d)");
    private static final Pattern NXN = Pattern.compile("(?i)(?:^|[\\s._\\-\\[(])(\\d{1,2})x(\\d{2,3})(?!\\d)");
    private static final Pattern WORD = Pattern.compile("(?i)(?:^|[\\s._\\-\\[(])(?:ep|eps|episode|episodio|ep\\.|folge|epizoda|épisode|episodio n\\.?)[\\s._]*(\\d{1,3})(?!\\d)");
    private static final Pattern TAIL = Pattern.compile("(?:[\\s._]+-[\\s._]*|[\\s._]+|_)(\\d{1,3})(?:[\\s._]*(?:\\[[^\\]]*\\]|\\([^)]*\\)))*$");

    public static Ep of(String fileName) {
        String n = fileName.replaceAll("\\.[A-Za-z0-9]{2,5}$", "");          // extension
        n = n.replaceAll("\\.vlink$", "");
        n = n.replaceAll("^\\s*\\[[^\\]]*\\]\\s*", "");                          // [Group] at the start
        Matcher m = LONG.matcher(n);
        if (m.find()) return new Ep(clean(n.substring(0, m.start())), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        m = SXE.matcher(n);
        if (m.find()) return new Ep(clean(n.substring(0, m.start())), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        m = S_N.matcher(n);
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

    /** A file name made readable: no extension, no [group] tags, "_" and "." as spaces ("Mushi-Shi__S1_01" -> "Mushi-Shi S1 01"). */
    public static String pretty(String fileName) {
        String n = fileName.replaceAll("\\.vlink$", "").replaceAll("\\.[A-Za-z0-9]{2,5}$", "");
        n = n.replaceAll("^\\s*\\[[^\\]]*\\]\\s*", "").replaceAll("\\[[^\\]]*\\]|\\((?:1080|720|480|2160)p?[^)]*\\)", " ");
        n = n.replaceAll("(?i)\\b(1080p|720p|480p|2160p|x264|x265|h264|h265|hevc|web-?dl|webrip|bluray|brrip|aac|ac3|10bit)\\b", " ");
        return n.replaceAll("[._]+", " ").replaceAll("\\s+", " ").replaceAll("\\s*-\\s*$", "").trim();
    }

    static String clean(String s) {
        s = s.replaceAll("[._]+", " ").replaceAll("\\s*[-–—:]+\\s*$", "").replaceAll("\\s+", " ").trim();
        s = s.replaceAll("(?i)\\s*(season|stagione|staffel|sezona)\\s*\\d+$", "").trim();
        return s;
    }

    /** The key two names of the same show share (case and punctuation do not count). */
    public static String key(String show) { return show.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", ""); }

    private static final Pattern SEASON_DIR = Pattern.compile("(?i)^(?:season|staffel|stagione|sezona|saison|temporada|s)[\\s._-]*(\\d{1,2})$");

    /** The season number a folder name says ("Season 2", "Staffel 02", "S3"), or 0. */
    public static int seasonOfFolder(String folder) {
        Matcher m = SEASON_DIR.matcher(folder == null ? "" : folder.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }
}
