package com.batchmine.pocket.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** A backup of the phone's own data in one zip: the settings (prefs.json, typed), and whole folders (the words, the books). Dictionaries, card media and videos are left out (big, and they can be fetched again). */
public final class Backup {
    private Backup() { }

    /** FOLDERS: name in the zip -> folder on the phone. Returns the number of files written. */
    public static int write(OutputStream os, Map<String, ?> prefs, Map<String, File> folders) throws IOException {
        int n = 0;
        try (ZipOutputStream z = new ZipOutputStream(os)) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, ?> e : prefs.entrySet()) {
                Object v = e.getValue();
                List<Object> t = new ArrayList<>();
                if (v instanceof Boolean) { t.add("b"); t.add(v); }
                else if (v instanceof Integer) { t.add("i"); t.add(((Integer) v).longValue()); }
                else if (v instanceof Long) { t.add("l"); t.add(v); }
                else if (v instanceof Float) { t.add("f"); t.add(((Float) v).doubleValue()); }
                else if (v instanceof Set) { t.add("ss"); t.add(new ArrayList<Object>((Set<?>) v)); }
                else if (v instanceof String) { t.add("s"); t.add(v); }
                else continue;
                out.put(e.getKey(), t);
            }
            put(z, "prefs.json", Json.write(out).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (Map.Entry<String, File> f : folders.entrySet()) n += tree(z, f.getKey(), f.getValue());
        }
        return n;
    }

    private static void put(ZipOutputStream z, String name, byte[] data) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(data);
        z.closeEntry();
    }

    private static int tree(ZipOutputStream z, String name, File dir) throws IOException {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            if (f.isDirectory()) n += tree(z, name + "/" + f.getName(), f);
            else if (f.isFile() && !f.getName().endsWith(".tmp")) {
                z.putNextEntry(new ZipEntry(name + "/" + f.getName()));
                try (InputStream in = new java.io.FileInputStream(f)) {
                    byte[] b = new byte[1 << 16];
                    int k;
                    while ((k = in.read(b)) > 0) z.write(b, 0, k);
                }
                z.closeEntry();
                n++;
            }
        }
        return n;
    }

    /** One setting from a backup: its type letter(s) and value. */
    public static final class Pref { public String type; public Object value; }

    public static final class Result {
        public final Map<String, Pref> prefs = new LinkedHashMap<>();
        public int files, skipped;
    }

    /** Reads a backup: the settings are returned (the caller stores them), the files are written into FOLDERS (a file that is already there is kept when KEEP contains its zip folder name, else replaced). */
    public static Result read(InputStream is, Map<String, File> folders, Set<String> keep) throws IOException {
        Result r = new Result();
        boolean any = false;
        try (ZipInputStream z = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                java.io.ByteArrayOutputStream bo = null;
                if (name.equals("prefs.json")) {
                    any = true;
                    bo = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[1 << 14];
                    int k;
                    while ((k = z.read(b)) > 0) bo.write(b, 0, k);
                    Map<String, Object> m = Json.obj(Json.parse(new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)));
                    for (Map.Entry<String, Object> x : m.entrySet()) {
                        List<Object> t = Json.arr(x.getValue());
                        if (t.size() != 2) continue;
                        Pref p = new Pref();
                        p.type = String.valueOf(t.get(0));
                        p.value = t.get(1);
                        r.prefs.put(x.getKey(), p);
                    }
                    continue;
                }
                int slash = name.indexOf('/');
                if (slash < 0 || name.contains("..")) { r.skipped++; continue; }
                File base = folders.get(name.substring(0, slash));
                if (base == null) { r.skipped++; continue; }
                File dest = new File(base, name.substring(slash + 1));
                if (dest.exists() && keep != null && keep.contains(name.substring(0, slash))) { r.skipped++; continue; }
                File parent = dest.getParentFile();
                if (parent != null) //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                File tmp = new File(dest.getPath() + ".tmp");
                try (OutputStream out = new java.io.FileOutputStream(tmp)) {
                    byte[] b = new byte[1 << 16];
                    int k;
                    while ((k = z.read(b)) > 0) out.write(b, 0, k);
                }
                if (dest.exists()) //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                if (!tmp.renameTo(dest)) throw new IOException("could not write " + dest.getName());
                r.files++;
            }
        }
        if (!any) throw new IOException("this is not a Glossa backup (no settings inside)");
        return r;
    }

    public static Set<String> names(String... s) { return new TreeSet<>(java.util.Arrays.asList(s)); }
}
