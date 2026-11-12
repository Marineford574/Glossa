package com.batchmine.pocket.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * One imported Yomitan dictionary (a wty zip): its folder holds terms.* (headword -> entry), forms.* (inflected form ->
 * dictionary form, from the "non-lemma" entries), ipa.* and freq.* (from term_meta banks), meta.json and styles.css.
 */
public final class Dict implements Closeable {
    public final File dir;
    public final String title, lang, revision;
    final Table terms, forms, ipa, freq;

    public Dict(File dir) throws IOException {
        this.dir = dir;
        Map<String, Object> m = Json.obj(Json.parse(new String(Files.readAllBytes(new File(dir, "meta.json").toPath()), StandardCharsets.UTF_8)));
        title = Json.str(m.get("title"), dir.getName());
        String byTitle = langOfTitle(title);
        lang = !byTitle.isEmpty() ? byTitle : Json.str(m.get("lang"), "");     // "Leipzig Italian …" is Italian, whatever its index.json says
        revision = Json.str(m.get("revision"), "");
        terms = Table.exists(dir, "terms") ? new Table(dir, "terms") : null;
        forms = Table.exists(dir, "forms") ? new Table(dir, "forms") : null;
        ipa = Table.exists(dir, "ipa") ? new Table(dir, "ipa") : null;
        freq = Table.exists(dir, "freq") ? new Table(dir, "freq") : null;
    }

    public String styles() {
        try { return new String(Files.readAllBytes(new File(dir, "styles.css").toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { return ""; }
    }

    public String summary() {
        List<String> p = new ArrayList<>();
        if (terms != null) p.add(terms.size() + " entries");
        if (forms != null) p.add(forms.size() + " forms");
        if (ipa != null) p.add(ipa.size() + " pronunciations");
        if (freq != null) p.add(freq.size() + " frequencies");
        return String.join(", ", p);
    }

    @Override public void close() throws IOException {
        for (Table t : new Table[]{terms, forms, ipa, freq}) if (t != null) t.close();
    }

    // ================================================================ import
    public interface Progress { void step(String what, int done); }

    /** Reads a dictionary zip (as a stream, entry by entry) into a new folder under ROOT; returns that folder. */
    public static File importZip(InputStream zip, File root, Progress prog) throws IOException {
        File tmp = new File(root, ".import-" + System.currentTimeMillis());
        tmp.mkdirs();
        Map<String, Object> index = null;
        try (ZipInputStream zin = new ZipInputStream(zip);
             Table.Builder tb = new Table.Builder(tmp, "terms"); Table.Builder fb = new Table.Builder(tmp, "forms");
             Table.Builder ib = new Table.Builder(tmp, "ipa"); Table.Builder qb = new Table.Builder(tmp, "freq")) {
            ZipEntry e;
            int n = 0;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                name = name.substring(name.lastIndexOf('/') + 1);
                InputStream keep = new FilterInputStream(zin) { @Override public void close() { } };   // the zip stays open
                if (name.equals("index.json")) {
                    index = Json.obj(Json.parse(readAll(keep)));
                } else if (name.equals("styles.css")) {
                    try (OutputStream o = new FileOutputStream(new File(tmp, "styles.css"))) { copy(keep, o); }
                } else if (name.startsWith("term_bank_") && name.endsWith(".json")) {
                    Json j = new Json(new BufferedReader(new InputStreamReader(keep, StandardCharsets.UTF_8), 1 << 16));
                    j.beginArray();
                    while (j.hasNext()) {
                        addTerm(Json.arr(j.readValue()), tb, fb);
                        if (++n % 5000 == 0 && prog != null) prog.step(name, n);
                    }
                } else if (name.startsWith("term_meta_bank_") && name.endsWith(".json")) {
                    Json j = new Json(new BufferedReader(new InputStreamReader(keep, StandardCharsets.UTF_8), 1 << 16));
                    j.beginArray();
                    while (j.hasNext()) {
                        addMeta(Json.arr(j.readValue()), ib, qb);
                        if (++n % 5000 == 0 && prog != null) prog.step(name, n);
                    }
                }
                zin.closeEntry();
            }
            if (index == null) throw new IOException("this zip is not a Yomitan dictionary (it has no index.json)");
            if (prog != null) prog.step("sorting", n);
            for (Table.Builder b : new Table.Builder[]{tb, fb, ib, qb}) if (b.count() > 0) b.finish();
        } catch (IOException | RuntimeException err) {
            deleteTree(tmp);
            throw err;
        }
        String title = Json.str(index.get("title"), "dictionary");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", title);
        meta.put("lang", !langOfTitle(title).isEmpty() ? langOfTitle(title) : Json.str(index.get("sourceLanguage"), ""));
        meta.put("target", Json.str(index.get("targetLanguage"), ""));
        meta.put("revision", Json.str(index.get("revision"), ""));
        meta.put("imported", System.currentTimeMillis());
        Files.write(new File(tmp, "meta.json").toPath(), Json.write(meta).getBytes(StandardCharsets.UTF_8));
        File dest = new File(root, title.replaceAll("[^\\w.-]+", "_"));
        if (dest.exists()) deleteTree(dest);              // a newer revision of the same dictionary replaces the old one
        if (!tmp.renameTo(dest)) throw new IOException("could not store the dictionary in " + dest);
        return dest;
    }

    static final String[][] LANG_NAMES = {
        {"it", "italian", "italiano"}, {"de", "german", "deutsch"}, {"hr", "croatian", "hrvatski"}, {"en", "english"}, {"fr", "french", "français", "francais"},
        {"es", "spanish", "español", "espanol"}, {"pt", "portuguese", "português", "portugues"}, {"nl", "dutch", "nederlands"}, {"ru", "russian"},
        {"pl", "polish", "polski"}, {"sr", "serbian"}, {"bs", "bosnian"}, {"sl", "slovenian", "slovene"}, {"cs", "czech"}, {"sv", "swedish"},
        {"ja", "japanese"}, {"zh", "chinese"}, {"ko", "korean"}, {"la", "latin"}, {"el", "greek"}};

    /** The language a dictionary's title names: "Leipzig Italian Mixed-Typical" → it, "wty-de-en" → de (its first code); "" when it names none. */
    public static String langOfTitle(String title) {
        String t = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        java.util.regex.Matcher w = java.util.regex.Pattern.compile("^(?:wty|kty)[-_]([a-z]{2,3})[-_]").matcher(t);
        if (w.find()) return w.group(1);
        for (String[] l : LANG_NAMES)
            for (int i = 1; i < l.length; i++)
                if (java.util.regex.Pattern.compile("(^|[^\\p{L}])" + java.util.regex.Pattern.quote(l[i]) + "($|[^\\p{L}])").matcher(t).find()) return l[0];
        return "";
    }

    /** [term, reading, defTags, rules, score, glossary, sequence, termTags] */
    static void addTerm(List<Object> e, Table.Builder terms, Table.Builder forms) throws IOException {
        if (e.size() < 6) return;
        String term = Json.str(e.get(0), "").trim();
        if (term.isEmpty()) return;
        String pos = Json.str(e.get(2), "");
        List<Object> gl = Json.arr(e.get(5));
        if (!gl.isEmpty() && gl.get(0) instanceof List) {            // non-lemma: [[lemma, [tags]]]
            for (Object g : gl) {
                List<Object> x = Json.arr(g);
                if (x.isEmpty()) continue;
                String lemma = Json.str(x.get(0), "");
                if (lemma.isEmpty() || lemma.equals(term)) continue;
                List<String> tags = new ArrayList<>();
                for (Object t : Json.arr(x.size() > 1 ? x.get(1) : null)) {
                    String s = Json.str(t, "");
                    if (!s.isEmpty() && !s.startsWith("redirected from")) tags.add(s);
                }
                forms.add(term, lemma + "\u0001" + String.join(", ", tags));
            }
            return;
        }
        StringBuilder html = new StringBuilder();
        List<String> glosses = new ArrayList<>();
        for (Object g : gl) {
            html.append(StructuredContent.toHtml(g));
            if (g instanceof String) { if (!((String) g).trim().isEmpty()) glosses.add(((String) g).trim()); }   // plain-text glossaries
            else collectGlosses(g, glosses);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("t", term);
        v.put("p", pos);
        v.put("h", html.toString());
        v.put("g", glosses);
        terms.add(term, Json.write(v));
    }

    /** [term, "ipa", {reading, transcriptions:[{ipa, tags}]}] or [term, "freq", value] */
    static void addMeta(List<Object> e, Table.Builder ipa, Table.Builder freq) throws IOException {
        if (e.size() < 3) return;
        String term = Json.str(e.get(0), "").trim(), kind = Json.str(e.get(1), "");
        if (term.isEmpty()) return;
        if (kind.equals("ipa")) {
            List<String> out = new ArrayList<>();
            for (Object t : Json.arr(Json.obj(e.get(2)).get("transcriptions"))) {
                String s = Json.str(Json.obj(t).get("ipa"), "");
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
            if (!out.isEmpty()) ipa.add(term, String.join("  ", out));
        } else if (kind.equals("freq")) {
            Object f = e.get(2);
            if (f instanceof Map) {
                Map<String, Object> fm = Json.obj(f);
                f = fm.containsKey("frequency") ? fm.get("frequency") : fm.get("value");
                if (f instanceof Map) f = Json.obj(f).get("value");
            }
            if (f instanceof Number) freq.add(term, String.valueOf(((Number) f).longValue()));
        }
    }

    /** The meanings: the items of the "glosses" list, without their examples and notes. */
    @SuppressWarnings("unchecked")
    static void collectGlosses(Object n, List<String> out) {
        if (n instanceof List) { for (Object x : (List<Object>) n) collectGlosses(x, out); return; }
        if (!(n instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) n;
        if ("glosses".equals(Json.obj(m.get("data")).get("content"))) {
            Object c = m.get("content");
            for (Object li : c instanceof List ? (List<Object>) c : java.util.Collections.singletonList(c)) {
                String t = StructuredContent.toText(stripDetails(li)).replace('\n', ' ').replaceAll("\\s+", " ").trim();
                if (!t.isEmpty()) out.add(t);
            }
            return;
        }
        collectGlosses(m.get("content"), out);
    }

    @SuppressWarnings("unchecked")
    private static Object stripDetails(Object n) {
        if (n instanceof List) {
            List<Object> o = new ArrayList<>();
            for (Object x : (List<Object>) n) o.add(stripDetails(x));
            return o;
        }
        if (!(n instanceof Map)) return n;
        Map<String, Object> m = (Map<String, Object>) n;
        String tag = Json.str(m.get("tag"), "");
        if (tag.equals("details") || tag.equals("ol") || tag.equals("ul")) return null;
        Map<String, Object> c = new LinkedHashMap<>(m);
        c.put("content", stripDetails(m.get("content")));
        return c;
    }

    static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        copy(in, b);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    public static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
