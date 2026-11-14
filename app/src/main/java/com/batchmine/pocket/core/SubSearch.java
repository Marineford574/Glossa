package com.batchmine.pocket.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Subtitles from the internet, searched by the phone itself (no PC): Podnapisi (no key) and SubDL (a free key). Same results as the PC's search. */
public final class SubSearch {
    private SubSearch() { }

    public static String podnapisi = "https://www.podnapisi.net", subdl = "https://api.subdl.com", subdlDl = "https://dl.subdl.com";   // changed by tests
    private static final String UA = "Glossa/1.0 (language-learning app)";
    private static final int MAX = 15 * 1024 * 1024;

    public static final class Query {
        public String name = ""; public int season = -1, episode = -1;
    }

    public static byte[] get(String url, int limit) throws IOException { return get(url, limit, null); }

    public static byte[] get(String url, int limit, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, */*");
        if (auth != null && !auth.isEmpty()) c.setRequestProperty("Authorization", auth);
        try {
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException(new URL(url).getHost() + " answered " + code + (code == 401 || code == 403 ? " (is the key right?)" : ""));
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) { out.write(b, 0, n); if (out.size() > limit) throw new IOException("that file is too big"); }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    private static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { return s; } }

    /** The results of both sources (one failing does not hide the other); NOTES gets what went wrong. */
    public static String jimaku = "https://jimaku.cc/api", jimakuKey = "";     // changed by tests; the key is the one of your jimaku.cc account

    public static List<Map<String, Object>> search(Query q, String lang, String subdlKey, String jimakuKey, List<String> notes) {
        SubSearch.jimakuKey = jimakuKey == null ? "" : jimakuKey.trim();
        List<Map<String, Object>> all = new ArrayList<>();
        if (lang.equalsIgnoreCase("ja") && !SubSearch.jimakuKey.isEmpty()) {
            try { all.addAll(jimaku(q, SubSearch.jimakuKey)); } catch (IOException | RuntimeException e) { notes.add("Jimaku: " + e.getMessage()); }
        } else if (lang.equalsIgnoreCase("ja")) notes.add("Jimaku: add the API key of your jimaku.cc account in Settings for Japanese subtitles");
        all.addAll(search(q, lang, subdlKey, notes, true));
        all.sort((a, b) -> Integer.compare(rank(a, q), rank(b, q)));
        return all.size() > 40 ? new ArrayList<>(all.subList(0, 40)) : all;
    }

    /** Jimaku (jimaku.cc): the entry of that name, its files (the episode asked for first). */
    static List<Map<String, Object>> jimaku(Query q, String key) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> ents = Json.arr(Json.parse(new String(get(jimaku + "/entries/search?anime=true&query=" + enc(q.name), 2_000_000, key), java.nio.charset.StandardCharsets.UTF_8)));
        if (ents.isEmpty()) throw new IOException("no entry of that name (titles there are in romaji or Japanese)");
        for (int i = 0; i < Math.min(3, ents.size()); i++) {
            Map<String, Object> en = Json.obj(ents.get(i));
            Object id = en.get("id");
            if (!(id instanceof Number)) continue;
            List<Object> files = Json.arr(Json.parse(new String(get(jimaku + "/entries/" + ((Number) id).longValue() + "/files" + (q.episode >= 0 ? "?episode=" + q.episode : ""), 2_000_000, key),
                    java.nio.charset.StandardCharsets.UTF_8)));
            for (Object o : files) {
                Map<String, Object> f = Json.obj(o);
                String name = Json.str(f.get("name"), ""), low = name.toLowerCase(Locale.ROOT), url = Json.str(f.get("url"), "");
                if (!(low.endsWith(".srt") || low.endsWith(".ass") || low.endsWith(".vtt") || low.endsWith(".zip")) || !url.startsWith("https://")) continue;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("source", "Jimaku");
                r.put("name", Json.str(en.get("name"), "") + " / " + name);
                Matcher m = Pattern.compile("[Ss]0*(\\d+)[Ee]0*(\\d+)|[ ._-]0*(\\d{1,3})(?:v\\d)?[ ._\\[(-]").matcher(name);
                if (m.find()) {
                    if (m.group(1) != null) { r.put("season", Integer.parseInt(m.group(1))); r.put("episode", Integer.parseInt(m.group(2))); }
                    else r.put("episode", Integer.parseInt(m.group(3)));
                }
                r.put("full_season", !r.containsKey("episode"));
                r.put("hi", false);
                r.put("url", url);
                out.add(r);
            }
        }
        return out;
    }

    public static List<Map<String, Object>> search(Query q, String lang, String subdlKey, List<String> notes) { return search(q, lang, subdlKey, notes, false); }

    private static List<Map<String, Object>> search(Query q, String lang, String subdlKey, List<String> notes, boolean quiet) {
        List<Map<String, Object>> all = new ArrayList<>();
        try { all.addAll(podnapisi(q, lang)); } catch (IOException | RuntimeException e) { notes.add("Podnapisi: " + e.getMessage()); }
        if (subdlKey == null || subdlKey.trim().isEmpty()) notes.add("SubDL: add its free key in Settings for more results");
        else try { all.addAll(subdl(q, lang, subdlKey.trim())); } catch (IOException | RuntimeException e) { notes.add("SubDL: " + e.getMessage()); }
        all.sort((a, b) -> Integer.compare(rank(a, q), rank(b, q)));
        return all.size() > 40 ? new ArrayList<>(all.subList(0, 40)) : all;
    }

    private static int rank(Map<String, Object> r, Query q) {
        int s = 0;
        Object ep = r.get("episode"), se = r.get("season");
        boolean full = Boolean.TRUE.equals(r.get("full_season"));
        boolean epOk = q.episode < 0 || ep == null || (ep instanceof Number && ((Number) ep).intValue() == q.episode) || full;
        boolean seOk = q.season < 0 || se == null || (se instanceof Number && ((Number) se).intValue() == q.season);
        if (!(epOk && seOk)) s += 100;
        if (full) s += 10;
        if (Boolean.TRUE.equals(r.get("hi"))) s += 1;
        return s;
    }

    static List<Map<String, Object>> podnapisi(Query q, String lang) throws IOException {
        StringBuilder u = new StringBuilder(podnapisi + "/subtitles/search/advanced?keywords=" + enc(q.name) + "&language=" + enc(lang) + "&sort=stats.downloads&sort_direction=desc");
        if (q.season >= 0) u.append("&seasons=").append(q.season).append("&movie_type=tv-series");
        if (q.episode >= 0) u.append("&episodes=").append(q.episode);
        Object d = Json.parse(new String(get(u.toString(), 2_000_000), java.nio.charset.StandardCharsets.UTF_8));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(d).get("data"))) {
            Map<String, Object> s = Json.obj(o);
            String dl = Json.str(s.get("download"), "");
            if (dl.isEmpty() && s.get("id") != null) dl = "/subtitles/" + Json.str(s.get("id"), "") + "/download";
            if (dl.isEmpty()) continue;
            List<Object> rel = Json.arr(s.get("releases"));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("source", "Podnapisi");
            r.put("name", !rel.isEmpty() ? Json.str(rel.get(0), "?") : Json.str(s.get("title"), "?"));
            r.put("season", s.get("season")); r.put("episode", s.get("episode")); r.put("full_season", false);
            r.put("hi", Json.write(s.get("flags") == null ? "" : s.get("flags")).toLowerCase(Locale.ROOT).contains("hearing"));
            r.put("url", dl.startsWith("http") ? dl : podnapisi + dl);
            out.add(r);
        }
        return out;
    }

    static List<Map<String, Object>> subdl(Query q, String lang, String key) throws IOException {
        StringBuilder u = new StringBuilder(subdl + "/api/v1/subtitles?api_key=" + enc(key) + "&film_name=" + enc(q.name) + "&languages=" + enc(lang.toUpperCase(Locale.ROOT))
                + "&subs_per_page=30&type=" + (q.season >= 0 ? "tv" : "movie"));
        if (q.season >= 0) u.append("&season_number=").append(q.season);
        if (q.episode >= 0) u.append("&episode_number=").append(q.episode);
        Map<String, Object> d = Json.obj(Json.parse(new String(get(u.toString(), 2_000_000), java.nio.charset.StandardCharsets.UTF_8)));
        if (Boolean.FALSE.equals(d.get("status"))) throw new IOException(Json.str(d.get("error"), "no answer"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : Json.arr(d.get("subtitles"))) {
            Map<String, Object> s = Json.obj(o);
            if (Json.str(s.get("url"), "").isEmpty()) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("source", "SubDL");
            r.put("name", !Json.str(s.get("release_name"), "").isEmpty() ? Json.str(s.get("release_name"), "?") : Json.str(s.get("name"), "?"));
            r.put("season", s.get("season")); r.put("episode", s.get("episode")); r.put("full_season", Boolean.TRUE.equals(s.get("full_season")));
            r.put("hi", Boolean.TRUE.equals(s.get("hi")));
            r.put("url", subdlDl + Json.str(s.get("url"), ""));
            out.add(r);
        }
        return out;
    }

    /** The subtitle text of one result (a zip is opened and the right episode picked); EXT[0] gets the extension. */
    public static String fetch(String url, String lang, int season, int episode, String[] ext) throws IOException {
        if (!url.startsWith("https://") && !url.startsWith("http://127.0.0.1")) throw new IOException("not a download address");
        byte[] data = get(url, MAX, url.startsWith(jimaku.substring(0, jimaku.indexOf("/api"))) ? jimakuKey : null);
        byte[] raw = data;
        ext[0] = ".srt";
        if (data.length > 2 && data[0] == 'P' && data[1] == 'K') {
            try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
                String best = null; byte[] bestData = null; int bestScore = Integer.MAX_VALUE;
                java.util.zip.ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String n = e.getName().toLowerCase(Locale.ROOT);
                    int kind = n.endsWith(".srt") ? 0 : n.endsWith(".vtt") ? 1 : n.endsWith(".ass") ? 2 : n.endsWith(".ssa") ? 3 : -1;
                    if (e.isDirectory() || kind < 0) continue;
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int k;
                    while ((k = z.read(b)) > 0) { bo.write(b, 0, k); if (bo.size() > 5_000_000) throw new IOException("that subtitle file is too big"); }
                    String base = n.substring(n.lastIndexOf('/') + 1);
                    int score = kind * 10 + (episode >= 0 && !matchesEpisode(base, season, episode) ? 1000 : 0) + Math.min(9, base.length() / 20);
                    if (score < bestScore) { bestScore = score; best = n; bestData = bo.toByteArray(); }
                }
                if (best == null) throw new IOException("the download holds no subtitle file");
                raw = bestData;
                ext[0] = best.substring(best.lastIndexOf('.'));
            }
        }
        String text = lang.equals("hr") || lang.equals("cs") || lang.equals("pl") ? decodeLegacy(raw, "windows-1250") : Subs.decode(raw);
        if (!Pattern.compile("\\d\\d:\\d\\d:\\d\\d|\\[Events\\]").matcher(text).find()) throw new IOException("the download is not a subtitle file");
        return text;
    }

    private static String decodeLegacy(byte[] raw, String cs) {
        java.nio.charset.CharsetDecoder d = java.nio.charset.StandardCharsets.UTF_8.newDecoder();
        try { return d.decode(java.nio.ByteBuffer.wrap(raw)).toString().replace("\uFEFF", ""); }
        catch (java.nio.charset.CharacterCodingException e) { return new String(raw, java.nio.charset.Charset.forName(cs)); }
    }

    public static boolean matchesEpisode(String base, int season, int episode) {
        int s = Math.max(season, 1);
        Matcher m = Pattern.compile("s0*" + s + "[ ._-]*e0*" + episode + "\\b|\\b0*" + s + "x0*" + episode + "\\b|\\be0*" + episode + "\\b|[ ._-]0*" + episode + "[ ._-]").matcher(base);
        return m.find();
    }
}
