package com.batchmine.pocket.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/**
 * Yomitan "structured-content" glossaries (what the wty dictionaries use) turned into HTML, the way Yomitan shows them:
 * {"tag":"div","data":{"content":"glosses"}} becomes <div data-sc-content="glosses">, so the dictionary's own styles.css
 * applies unchanged.
 */
public final class StructuredContent {
    private StructuredContent() { }

    private static final Set<String> TAGS = new HashSet<>(Arrays.asList(
            "div", "span", "ol", "ul", "li", "details", "summary", "table", "thead", "tbody", "tfoot", "tr", "td", "th",
            "ruby", "rt", "rp", "br", "a", "img", "b", "i", "em", "strong", "sub", "sup", "small", "p"));

    /** One glossary item of a term entry (a string, a {"type":...} object, or a list of them). */
    public static String toHtml(Object node) {
        StringBuilder sb = new StringBuilder();
        render(node, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void render(Object n, StringBuilder sb) {
        if (n == null) return;
        if (n instanceof String) { esc((String) n, sb, true); return; }
        if (n instanceof List) { for (Object x : (List<Object>) n) render(x, sb); return; }
        if (!(n instanceof Map)) { esc(String.valueOf(n), sb, true); return; }
        Map<String, Object> m = (Map<String, Object>) n;
        Object type = m.get("type");
        if ("structured-content".equals(type)) { render(m.get("content"), sb); return; }
        if ("text".equals(type)) { esc(Json.str(m.get("text"), ""), sb, true); return; }
        if ("image".equals(type)) return;              // pictures inside dictionaries are not shown on the phone
        String tag = Json.str(m.get("tag"), "");
        if (tag.isEmpty()) { render(m.get("content"), sb); return; }
        if (!TAGS.contains(tag)) tag = "span";
        if (tag.equals("img")) return;
        sb.append('<').append(tag);
        Object data = m.get("data");
        if (data instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) data).entrySet()) {
                sb.append(" data-sc-").append(attrName(e.getKey())).append("=\"");
                esc(Json.str(e.getValue(), ""), sb, false);
                sb.append('"');
            }
        }
        if (tag.equals("a") && m.get("href") instanceof String) {
            String href = (String) m.get("href");
            if (href.startsWith("http://") || href.startsWith("https://")) {
                sb.append(" href=\"");
                esc(href, sb, false);
                sb.append('"');
            }
        }
        if (m.get("title") instanceof String) { sb.append(" title=\""); esc((String) m.get("title"), sb, false); sb.append('"'); }
        if (m.get("lang") instanceof String) { sb.append(" lang=\""); esc((String) m.get("lang"), sb, false); sb.append('"'); }
        if (tag.equals("br")) { sb.append('>'); return; }
        sb.append('>');
        render(m.get("content"), sb);
        sb.append("</").append(tag).append('>');
    }

    private static String attrName(String k) {
        StringBuilder sb = new StringBuilder();
        for (char c : k.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('-').append(Character.toLowerCase(c));
            else if (Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
        }
        return sb.toString();
    }

    static void esc(String s, StringBuilder sb, boolean text) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append(text ? "\"" : "&quot;"); break;
                case '\n': sb.append(text ? "<br>" : " "); break;
                default: sb.append(c);
            }
        }
    }

    /** Plain text of a glossary (for card fields that want no HTML, and for search). */
    public static String toText(Object node) {
        StringBuilder sb = new StringBuilder();
        text(node, sb);
        return sb.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    @SuppressWarnings("unchecked")
    private static void text(Object n, StringBuilder sb) {
        if (n == null) return;
        if (n instanceof String) { sb.append((String) n); return; }
        if (n instanceof List) { for (Object x : (List<Object>) n) text(x, sb); return; }
        if (!(n instanceof Map)) return;
        Map<String, Object> m = (Map<String, Object>) n;
        if ("text".equals(m.get("type"))) { sb.append(Json.str(m.get("text"), "")); return; }
        String tag = Json.str(m.get("tag"), "");
        boolean block = tag.equals("div") || tag.equals("li") || tag.equals("p") || tag.equals("details") || tag.equals("br");
        if (block) sb.append('\n');
        text(m.get("content"), sb);
        if (block) sb.append('\n');
    }
}
