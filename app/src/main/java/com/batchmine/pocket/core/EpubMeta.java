package com.batchmine.pocket.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * What an EPUB says about itself, from its package file (OPF): author(s), the year, the description, the publisher, the language.
 * Names keep their letters (Krleža, Šenoa, Dürrenmatt, Gorčić): the XML is read in the encoding it declares (UTF-8 by default),
 * and character references (&#269; &#x10D; &eacute;) are turned into letters.
 */
public final class EpubMeta {
    private EpubMeta() { }

    /** Keys: creator, aired, desc, publisher, language (only those the book has). */
    public static Map<String, String> read(InputStream in) throws IOException {
        byte[] container = null, opf = null;
        String opfPath = null;
        Map<String, byte[]> opfs = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName(), low = n.toLowerCase();
                if (e.isDirectory() || !(low.endsWith(".opf") || low.equals("meta-inf/container.xml"))) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                int r;
                while ((r = z.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > 3_000_000) break; }
                if (low.equals("meta-inf/container.xml")) container = b.toByteArray(); else opfs.put(n, b.toByteArray());
            }
        }
        if (container != null) {
            Matcher m = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(new String(container, StandardCharsets.UTF_8));
            if (m.find()) opfPath = m.group(1);
        }
        opf = opfPath != null ? opfs.get(opfPath) : null;
        if (opf == null && !opfs.isEmpty()) opf = opfs.values().iterator().next();
        return opf == null ? new LinkedHashMap<>() : parse(opf);
    }

    public static Map<String, String> parse(byte[] opf) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = decode(opf);
        List<String> cr = new ArrayList<>();
        for (String v : values(text, "creator")) { String f = firstLast(v); if (!cr.contains(f)) cr.add(f); if (cr.size() >= 4) break; }
        if (!cr.isEmpty()) out.put("creator", String.join(", ", cr));
        List<String> dt = values(text, "date");
        if (!dt.isEmpty()) { Matcher y = Pattern.compile("\\b(1\\d{3}|20\\d{2})\\b").matcher(dt.get(0)); if (y.find()) out.put("aired", y.group(1)); }
        List<String> de = values(text, "description");
        if (!de.isEmpty()) out.put("desc", de.get(0).length() > 2500 ? de.get(0).substring(0, 2500) : de.get(0));
        List<String> pu = values(text, "publisher");
        if (!pu.isEmpty()) out.put("publisher", pu.get(0));
        List<String> lg = values(text, "language");
        if (!lg.isEmpty()) out.put("language", lg.get(0));
        return out;
    }

    static String decode(byte[] raw) {
        String head = new String(raw, 0, Math.min(raw.length, 200), StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("encoding\\s*=\\s*[\"']([\\w-]+)").matcher(head);
        Charset cs = StandardCharsets.UTF_8;
        if (m.find()) { try { cs = Charset.forName(m.group(1)); } catch (RuntimeException ignored) { /* UTF-8 */ } }
        String s = new String(raw, cs);
        return s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /** The text of every <dc:TAG>…</dc:TAG> (any prefix), tags stripped, entities decoded, white space tidied. */
    static List<String> values(String xml, String tag) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<(?:[\\w-]+:)?" + tag + "\\b[^>]*>(.*?)</(?:[\\w-]+:)?" + tag + "\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(xml);
        while (m.find()) {
            String v = m.group(1).replaceAll("(?s)<!\\[CDATA\\[(.*?)\\]\\]>", "$1");
            v = unescape(v.replaceAll("<[^>]+>", " "));
            if (v.indexOf('<') >= 0) v = unescape(v.replaceAll("<[^>]+>", " "));       // a description is often HTML written as text
            v = v.replaceAll("\\s+", " ").trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    private static final Map<String, String> NAMED = new LinkedHashMap<>();
    static {
        String[][] n = {{"amp", "&"}, {"lt", "<"}, {"gt", ">"}, {"quot", "\""}, {"apos", "'"}, {"nbsp", " "}, {"hellip", "…"}, {"ndash", "–"}, {"mdash", "—"},
                {"lsquo", "‘"}, {"rsquo", "’"}, {"ldquo", "“"}, {"rdquo", "”"}, {"laquo", "«"}, {"raquo", "»"}, {"eacute", "é"}, {"egrave", "è"}, {"agrave", "à"},
                {"aacute", "á"}, {"uuml", "ü"}, {"ouml", "ö"}, {"auml", "ä"}, {"Uuml", "Ü"}, {"Ouml", "Ö"}, {"Auml", "Ä"}, {"szlig", "ß"}, {"ccedil", "ç"},
                {"iacute", "í"}, {"oacute", "ó"}, {"uacute", "ú"}, {"ntilde", "ñ"}, {"ecirc", "ê"}, {"acirc", "â"}, {"ocirc", "ô"}, {"icirc", "î"}, {"ucirc", "û"}};
        for (String[] x : n) NAMED.put(x[0], x[1]);
    }

    /** &amp; &#269; &#x10D; &eacute; → the letters. */
    public static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|[A-Za-z][A-Za-z0-9]*);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String e = m.group(1), r = null;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) r = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) r = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else r = NAMED.get(e);
            } catch (RuntimeException ex) { r = null; }
            m.appendReplacement(b, Matcher.quoteReplacement(r == null ? m.group() : r));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** "Krleža, Miroslav" → "Miroslav Krleža"; anything else as it is. */
    public static String firstLast(String n) {
        int c = n.indexOf(',');
        if (c > 0 && n.indexOf(',', c + 1) < 0 && n.length() < 60 && !n.matches(".*\\d.*")) {
            String a = n.substring(0, c).trim(), b = n.substring(c + 1).trim();
            if (!a.isEmpty() && !b.isEmpty()) return b + " " + a;
        }
        return n;
    }
}
