package com.batchmine.pocket.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The cover picture of an EPUB (JPEG/PNG bytes), or null when it has none. */
public final class EpubCover {
    private EpubCover() { }

    public static byte[] extract(InputStream in) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            long total = 0;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName(), low = n.toLowerCase();
                boolean text = low.endsWith(".opf") || low.endsWith("container.xml");
                boolean img = low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png");
                if (e.isDirectory() || !(text || img)) continue;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 14];
                int r;
                while ((r = z.read(buf)) > 0) { b.write(buf, 0, r); if (b.size() > 12_000_000) break; }
                total += b.size();
                if (total > 120_000_000) break;
                files.put(n, b.toByteArray());
            }
        }
        byte[] c = files.get("META-INF/container.xml");
        if (c == null) return null;
        Matcher rm = Pattern.compile("full-path\\s*=\\s*\"([^\"]+)\"").matcher(new String(c, StandardCharsets.UTF_8));
        if (!rm.find()) return null;
        String opfPath = rm.group(1);
        byte[] opfB = files.get(opfPath);
        if (opfB == null) return null;
        String opf = new String(opfB, StandardCharsets.UTF_8);
        String dir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        String href = null;
        // <meta name="cover" content="ID"/> then <item id="ID" href="..."/>
        Matcher mm = Pattern.compile("<meta\\b[^>]*>").matcher(opf);
        String coverId = null;
        while (mm.find()) {
            String t = mm.group();
            if (attr(t, "name").equalsIgnoreCase("cover") && !attr(t, "content").isEmpty()) coverId = attr(t, "content");
        }
        Matcher im = Pattern.compile("<item\\b[^>]*>").matcher(opf);
        String guess = null;
        while (im.find()) {
            String t = im.group(), h = attr(t, "href"), type = attr(t, "media-type");
            if (!type.startsWith("image/")) continue;
            if (attr(t, "properties").contains("cover-image")) { href = h; break; }
            if (coverId != null && attr(t, "id").equals(coverId)) href = h;
            if (guess == null && (attr(t, "id") + h).toLowerCase().contains("cover")) guess = h;
        }
        if (href == null) href = guess;
        if (href == null) return null;
        try { href = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8"); } catch (Exception ignored) { /* keep as is */ }
        byte[] pic = files.get(normalise(dir + href));
        if (pic == null) pic = files.get(href);
        return pic != null && pic.length > 200 ? pic : null;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    private static String normalise(String p) {
        java.util.LinkedList<String> out = new java.util.LinkedList<>();
        for (String part : p.split("/")) {
            if (part.equals("..")) { if (!out.isEmpty()) out.removeLast(); }
            else if (!part.equals(".") && !part.isEmpty()) out.add(part);
        }
        return String.join("/", out);
    }
}
