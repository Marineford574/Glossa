package com.batchmine.pocket.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A book to read: chapters of paragraphs. Made from an EPUB (spine order, chapter titles from its table of contents), a
 * plain text file (chapters at lines that look like headings), or what the PC's Reader sends. Stored as JSON.
 */
public final class Book {
    public String id = "", title = "", lang = "", source = "";
    public final List<Chapter> chapters = new ArrayList<>();

    public static final class Chapter {
        public String title;
        public final List<String> paras = new ArrayList<>();
        public Chapter(String title) { this.title = title; }
    }

    public int paraCount() { int n = 0; for (Chapter c : chapters) n += c.paras.size(); return n; }

    // ---------------------------------------------------------------- storage
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("title", title); m.put("lang", lang); m.put("source", source);
        List<Object> cs = new ArrayList<>();
        for (Chapter c : chapters) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("title", c.title);
            cm.put("paras", new ArrayList<Object>(c.paras));
            cs.add(cm);
        }
        m.put("chapters", cs);
        return m;
    }

    public static Book fromJson(Map<String, Object> m) {
        Book b = new Book();
        b.id = Json.str(m.get("id"), ""); b.title = Json.str(m.get("title"), ""); b.lang = Json.str(m.get("lang"), "");
        b.source = Json.str(m.get("source"), "");
        for (Object c : Json.arr(m.get("chapters"))) {
            Map<String, Object> cm = Json.obj(c);
            Chapter ch = new Chapter(Json.str(cm.get("title"), ""));
            for (Object p : Json.arr(cm.get("paras"))) ch.paras.add(Json.str(p, ""));
            if (!ch.paras.isEmpty()) b.chapters.add(ch);
        }
        return b;
    }

    public void write(File f) throws IOException { Files.write(f.toPath(), Json.write(toJson()).getBytes(StandardCharsets.UTF_8)); }

    public static Book read(File f) throws IOException {
        return fromJson(Json.obj(Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8))));
    }

    // ---------------------------------------------------------------- plain text
    private static final Pattern HEADING = Pattern.compile(
            "^(?:(?:chapter|chapitre|capitolo|kapitel|poglavlje|capítulo|teil|parte|part|libro|book)\\b.{0,60}|[IVXLC]{1,7}\\.?|\\d{1,3}\\.?)$",
            Pattern.CASE_INSENSITIVE);

    public static Book fromText(String text, String title) {
        Book b = new Book();
        b.title = title;
        Chapter cur = new Chapter(title);
        String norm = text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');
        boolean blankLines = norm.contains("\n\n");
        String[] blocks = blankLines ? norm.split("\\n\\s*\\n") : norm.split("\\n");
        for (String bl : blocks) {
            String p = bl.replaceAll("\\s*\\n\\s*", " ").replaceAll("\\s+", " ").trim();
            if (p.isEmpty()) continue;
            if (p.length() < 70 && HEADING.matcher(p).matches()) {
                if (!cur.paras.isEmpty()) b.chapters.add(cur);
                cur = new Chapter(p);
                continue;
            }
            cur.paras.add(p);
            if (cur.paras.size() >= 400 && b.chapters.size() < 2000) {     // no headings: parts of 400 paragraphs
                b.chapters.add(cur);
                cur = new Chapter(title + " (" + (b.chapters.size() + 1) + ")");
            }
        }
        if (!cur.paras.isEmpty()) b.chapters.add(cur);
        return b;
    }

    public static String decode(byte[] raw) {
        String s = new String(raw, StandardCharsets.UTF_8);
        if (s.indexOf('\uFFFD') >= 0) s = new String(raw, Charset.forName("windows-1252"));
        return s;
    }

    // ---------------------------------------------------------------- EPUB
    public static Book fromEpub(InputStream in, String fallbackTitle) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            long total = 0;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                String low = n.toLowerCase();
                if (e.isDirectory() || !(low.endsWith(".xml") || low.endsWith(".opf") || low.endsWith(".ncx") || low.endsWith(".html")
                        || low.endsWith(".xhtml") || low.endsWith(".htm"))) continue;
                byte[] data = readAll(z);
                total += data.length;
                if (total > 200_000_000) throw new IOException("the EPUB is too big");
                files.put(n, data);
            }
        }
        String container = str(files.get("META-INF/container.xml"));
        Matcher rm = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(container);
        if (!rm.find()) throw new IOException("not an EPUB (no META-INF/container.xml)");
        String opfPath = rm.group(1);
        String opf = str(files.get(opfPath));
        String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        Book b = new Book();
        Matcher tm = Pattern.compile("<dc:title[^>]*>([\\s\\S]*?)</dc:title>").matcher(opf);
        b.title = tm.find() ? text(tm.group(1)) : fallbackTitle;
        if (b.title.isEmpty()) b.title = fallbackTitle;
        Matcher lm = Pattern.compile("<dc:language[^>]*>([\\s\\S]*?)</dc:language>").matcher(opf);
        if (lm.find()) b.lang = text(lm.group(1)).toLowerCase().split("[-_]")[0];
        Map<String, String> hrefById = new HashMap<>();
        String ncxHref = null, navHref = null;
        Matcher im = Pattern.compile("<item\\b[^>]*>").matcher(opf);
        while (im.find()) {
            String tag = im.group();
            String id = attr(tag, "id"), href = attr(tag, "href"), mt = attr(tag, "media-type"), props = attr(tag, "properties");
            if (id == null || href == null) continue;
            href = resolve(base, urlDecode(href));
            hrefById.put(id, href);
            if ("application/x-dtbncx+xml".equals(mt)) ncxHref = href;
            if (props != null && props.contains("nav")) navHref = href;
        }
        Map<String, String> tocTitles = new LinkedHashMap<>();     // file -> first title pointing at it
        if (navHref != null && files.containsKey(navHref)) {
            String nav = str(files.get(navHref));
            String nb = navHref.contains("/") ? navHref.substring(0, navHref.lastIndexOf('/') + 1) : "";
            Matcher am = Pattern.compile("<a\\b[^>]*href\\s*=\\s*\"([^\"#]*)[^\"]*\"[^>]*>([\\s\\S]*?)</a>").matcher(nav);
            while (am.find()) tocTitles.putIfAbsent(resolve(nb, urlDecode(am.group(1))), text(am.group(2)));
        } else if (ncxHref != null && files.containsKey(ncxHref)) {
            String ncx = str(files.get(ncxHref));
            String nb = ncxHref.contains("/") ? ncxHref.substring(0, ncxHref.lastIndexOf('/') + 1) : "";
            Matcher np = Pattern.compile("<navLabel>\\s*<text>([\\s\\S]*?)</text>\\s*</navLabel>\\s*<content\\s+src\\s*=\\s*\"([^\"#]*)").matcher(ncx);
            while (np.find()) tocTitles.putIfAbsent(resolve(nb, urlDecode(np.group(2))), text(np.group(1)));
        }
        Matcher sm = Pattern.compile("<itemref\\b[^>]*idref\\s*=\\s*\"([^\"]+)\"").matcher(opf);
        int n = 0;
        while (sm.find()) {
            String href = hrefById.get(sm.group(1));
            if (href == null || !files.containsKey(href) || href.equals(navHref)) continue;
            List<String> paras = htmlParas(str(files.get(href)));
            if (paras.isEmpty()) continue;
            n++;
            String title = tocTitles.get(href);
            if (title == null || title.isEmpty()) {
                Matcher hm = Pattern.compile("<h[1-3][^>]*>([\\s\\S]*?)</h[1-3]>", Pattern.CASE_INSENSITIVE).matcher(str(files.get(href)));
                title = hm.find() ? text(hm.group(1)) : "";
            }
            if (title.isEmpty()) title = b.title + " · " + n;
            Chapter c = new Chapter(title.length() > 120 ? title.substring(0, 120) + "…" : title);
            c.paras.addAll(paras);
            b.chapters.add(c);
        }
        if (b.chapters.isEmpty()) throw new IOException("no text found in the EPUB");
        return b;
    }

    /** The text blocks of an XHTML chapter: paragraphs, headings, list items, quotes. */
    static List<String> htmlParas(String html) {
        List<String> out = new ArrayList<>();
        Matcher body = Pattern.compile("<body[^>]*>([\\s\\S]*)</body>", Pattern.CASE_INSENSITIVE).matcher(html);
        String s = body.find() ? body.group(1) : html;
        s = s.replaceAll("(?is)<(script|style|head)[^>]*>.*?</\\1>", " ")
             .replaceAll("(?is)<rt[^>]*>.*?</rt>", "")
             .replaceAll("(?i)<br\\s*/?>", "\n")
             .replaceAll("(?i)</?(p|div|h[1-6]|li|blockquote|tr|section|article|dd|dt|figcaption|pre)\\b[^>]*>", "\n\n");
        for (String block : s.split("\\n\\s*\\n")) {
            String t = text(block);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static String text(String html) {
        String t = html.replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
             .replace("&#39;", "'").replace("&shy;", "").replace("\u00AD", "");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int cp;
            try { cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16); } catch (NumberFormatException e) { cp = '?'; }
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String resolve(String base, String href) {
        String p = href.startsWith("/") ? href.substring(1) : base + href;
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            if (s.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
            else if (!s.equals(".") && !s.isEmpty()) parts.add(s);
        }
        return String.join("/", parts);
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String str(byte[] b) { return b == null ? "" : new String(b, StandardCharsets.UTF_8); }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 15];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        return o.toByteArray();
    }
}
