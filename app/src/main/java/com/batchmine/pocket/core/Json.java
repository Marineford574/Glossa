package com.batchmine.pocket.core;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer with no dependencies (so the same code runs on the phone and in plain Java tests).
 * Objects become LinkedHashMap, arrays ArrayList, numbers Double or Long, plus String, Boolean and null.
 * The streaming part (beginArray / hasNext / readValue) reads a huge top-level array one element at a time,
 * which is how the dictionary banks (30 MB each) are imported without loading them whole.
 */
public final class Json {
    private final Reader in;
    private int peeked = -2;          // -2 = nothing peeked

    public Json(Reader in) { this.in = in; }

    public static Object parse(String s) {
        try {
            Json j = new Json(new StringReader(s));
            Object v = j.readValue();
            j.skipWs();
            if (j.peek() != -1) throw new IOException("extra text after the JSON value");
            return v;
        } catch (IOException e) {
            throw new IllegalArgumentException("bad JSON: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- streaming
    public void beginArray() throws IOException { skipWs(); expect('['); first = true; }

    private boolean first = true;

    /** Inside an array begun with beginArray: is there another element? (consumes the comma) */
    public boolean hasNext() throws IOException {
        skipWs();
        int c = peek();
        if (c == ']') { next(); return false; }
        if (c == -1) throw new IOException("the array is not closed");
        if (!first) { expect(','); skipWs(); }
        first = false;
        return true;
    }

    public Object readValue() throws IOException {
        skipWs();
        int c = peek();
        switch (c) {
            case '{': return readObject();
            case '[': return readArray();
            case '"': return readString();
            case 't': word("true"); return Boolean.TRUE;
            case 'f': word("false"); return Boolean.FALSE;
            case 'n': word("null"); return null;
            case -1: throw new IOException("unexpected end of the text");
            default: return readNumber();
        }
    }

    private Map<String, Object> readObject() throws IOException {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek() == '}') { next(); return m; }
        while (true) {
            skipWs();
            String k = readString();
            skipWs();
            expect(':');
            m.put(k, readValue());
            skipWs();
            int c = next();
            if (c == '}') return m;
            if (c != ',') throw new IOException("expected , or } in an object");
        }
    }

    private List<Object> readArray() throws IOException {
        expect('[');
        List<Object> a = new ArrayList<>();
        skipWs();
        if (peek() == ']') { next(); return a; }
        while (true) {
            a.add(readValue());
            skipWs();
            int c = next();
            if (c == ']') return a;
            if (c != ',') throw new IOException("expected , or ] in an array");
        }
    }

    private String readString() throws IOException {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = next();
            if (c == -1) throw new IOException("the text is not closed");
            if (c == '"') return sb.toString();
            if (c == '\\') {
                int e = next();
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': {
                        int v = 0;
                        for (int i = 0; i < 4; i++) v = v * 16 + Character.digit(next(), 16);
                        sb.append((char) v);
                        break;
                    }
                    default: sb.append((char) e);
                }
            } else sb.append((char) c);
        }
    }

    private Object readNumber() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peek();
            if (c == -1 || "+-0123456789.eE".indexOf(c) < 0) break;
            sb.append((char) next());
        }
        String s = sb.toString();
        if (s.isEmpty()) throw new IOException("unexpected character '" + (char) peek() + "'");
        if (s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { }
        }
        return Double.parseDouble(s);
    }

    private void word(String w) throws IOException {
        for (int i = 0; i < w.length(); i++) if (next() != w.charAt(i)) throw new IOException("expected " + w);
    }

    private void expect(char c) throws IOException {
        int n = next();
        if (n != c) throw new IOException("expected '" + c + "' but found '" + (n == -1 ? "end" : String.valueOf((char) n)) + "'");
    }

    private void skipWs() throws IOException {
        while (true) {
            int c = peek();
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == 0xFEFF) next(); else return;
        }
    }

    private int peek() throws IOException { if (peeked == -2) peeked = in.read(); return peeked; }

    private int next() throws IOException {
        if (peeked != -2) { int c = peeked; peeked = -2; return c; }
        return in.read();
    }

    // ---------------------------------------------------------------- writing
    public static String write(Object v) { StringBuilder sb = new StringBuilder(); write(v, sb); return sb.toString(); }

    @SuppressWarnings("unchecked")
    public static void write(Object v, StringBuilder sb) {
        if (v == null) sb.append("null");
        else if (v instanceof String) quote((String) v, sb);
        else if (v instanceof Boolean) sb.append(v.toString());
        else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d); else sb.append(d);
        } else if (v instanceof Number) sb.append(v.toString());
        else if (v instanceof Map) {
            sb.append('{');
            boolean f = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!f) sb.append(',');
                f = false;
                quote(e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean f = true;
            for (Iterator<?> it = ((Iterable<?>) v).iterator(); it.hasNext(); ) {
                if (!f) sb.append(',');
                f = false;
                write(it.next(), sb);
            }
            sb.append(']');
        } else quote(v.toString(), sb);
    }

    public static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- small helpers for reading parsed values
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) { return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>(); }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) { return v instanceof List ? (List<Object>) v : new ArrayList<>(); }

    public static String str(Object v, String def) { return v == null ? def : v instanceof String ? (String) v : v.toString(); }

    public static double num(Object v, double def) { return v instanceof Number ? ((Number) v).doubleValue() : def; }
}
