package com.batchmine.pocket.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The connection to Glossa on the PC, through its phone access (Settings -> Phone access on the PC shows a link like
 * http://192.168.1.20:8780/?t=TOKEN; paste or scan it in the app). Plain HTTP on the home network or Tailscale, with
 * the token in a header.
 */
public final class PcLink {
    public final String base, token;

    public PcLink(String base, String token) { this.base = base.replaceAll("/+$", ""); this.token = token; }

    /** The link from the PC's phone-access box; null when it is not one. */
    public static PcLink parse(String link) {
        if (link == null) return null;
        link = link.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(https?://[^/?#\\s]+)[^?#\\s]*\\?(?:.*&)?t=([^&#\\s]+)").matcher(link);
        if (!m.find()) return null;
        return new PcLink(m.group(1), m.group(2));
    }

    public String link() { return base + "/?t=" + token; }

    public interface Bytes { void progress(long done, long total); boolean cancelled(); }

    public Map<String, Object> get(String path, int timeoutMs) throws IOException { return Json.obj(call("GET", path, null, timeoutMs)); }

    public Map<String, Object> post(String path, Map<String, Object> body, int timeoutMs) throws IOException {
        return Json.obj(call("POST", path, Json.write(body), timeoutMs));
    }

    private Object call(String method, String path, String body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(Math.min(timeoutMs, 4000));
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("X-Token", token);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) { o.write(b); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : new String(Book.readAll(in), StandardCharsets.UTF_8);
            if (code == 401 || code == 403) throw new IOException("the PC did not accept the link: copy it again from Glossa (Settings → Phone access)");
            Object parsed;
            try { parsed = text.isEmpty() ? null : Json.parse(text); } catch (IllegalArgumentException e) { parsed = null; }
            if (code >= 400) {
                String err = parsed instanceof Map ? Json.str(Json.obj(parsed).get("error"), "") : "";
                if (code == 404 && err.isEmpty()) err = "this Glossa is too old for the phone app: update it on the PC";
                throw new IOException(err.isEmpty() ? "the PC answered " + code : err);
            }
            return parsed;
        } finally {
            c.disconnect();
        }
    }

    /** A file from the PC into DEST (resumed when part of it is there already). */
    public void download(String path, File dest, Bytes cb) throws IOException {
        File part = new File(dest.getPath() + ".part");
        long have = part.isFile() ? part.length() : 0;
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(30000);
            c.setRequestProperty("X-Token", token);
            if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("the PC answered " + code);
            boolean resumed = code == 206;
            long total = c.getContentLengthLong() + (resumed ? have : 0);
            if (!resumed) have = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, resumed)) {
                byte[] buf = new byte[1 << 16];
                int r;
                long done = have, last = 0;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    done += r;
                    if (cb != null && done - last > (1 << 20)) { cb.progress(done, total); last = done; }
                    if (cb != null && cb.cancelled()) throw new IOException("cancelled");
                }
                if (cb != null) cb.progress(done, total);
            }
            if (dest.exists()) //noinspection ResultOfMethodCallIgnored
                dest.delete();
            if (!part.renameTo(dest)) throw new IOException("could not save " + dest.getName());
        } finally {
            c.disconnect();
        }
    }

    public static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    /** What the app adds to a sync besides words: where you stopped in videos and books (both ways, the later one wins). */
    public interface Positions {
        void collect(Map<String, Object> out);
        void apply(Map<String, Object> pulled);
    }

    public String sync(WordStore ws) throws IOException { return sync(ws, null); }

    /** Pull and push one language: the PC's lists come down, the phone's changes and waiting cards go up. */
    public String sync(WordStore ws, Positions pos) throws IOException {
        Map<String, Object> out = ws.outgoing();
        if (pos != null) pos.collect(out);
        Map<String, Object> res = post("/api/pocket/push", out, 60000);
        ws.sent(out, res);
        Map<String, Object> pc = get("/api/pocket/pull?lang=" + enc(ws.lang), 60000);
        ws.pulled(pc);
        if (pos != null) pos.apply(pc);
        int cards = Json.arr(res.get("cards")).size();
        StringBuilder msg = new StringBuilder();
        msg.append(ws.knownCount()).append(" known words");
        int ka = Json.arr(out.get("known_add")).size() + Json.arr(out.get("known_remove")).size();
        if (ka > 0) msg.append(", ").append(ka).append(" change(s) sent");
        if (cards > 0) msg.append(", ").append(cards).append(" card(s) sent to the PC");
        String err = Json.str(res.get("error"), "");
        if (!err.isEmpty()) msg.append(" (").append(err).append(")");
        return msg.toString();
    }
}
