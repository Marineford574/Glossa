package com.batchmine.pocket;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.batchmine.pocket.core.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A folder of videos or books chosen with the system's folder picker, with all its subfolders. The files are listed (nothing is copied here:
 * the caller links videos and reads books); the folders you added are remembered ("folders" in the preferences) so "Look for new files"
 * can go through them again.
 */
final class Folders {
    private Folders() { }

    static final class Item {
        Uri uri;
        String name, mime;
        String[] rel;          // the folders between the chosen one and the file
    }

    static final class Result {
        String rootName = "";
        final List<Item> files = new ArrayList<>();
        boolean cut;           // too many files: the list was stopped
    }

    static final int MAX_FILES = 3000, MAX_DEPTH = 8;

    static Result walk(Context c, Uri tree) {
        Result r = new Result();
        ContentResolver cr = c.getContentResolver();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        try (Cursor q = cr.query(rootDoc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) r.rootName = q.getString(0);
        } catch (RuntimeException ignored) { /* the name stays empty */ }
        if (r.rootName == null || r.rootName.isEmpty()) { String t = rootId.replaceAll("^.*[:/]", ""); r.rootName = t.isEmpty() ? "Folder" : t; }
        walk(cr, tree, rootId, new ArrayList<>(), r, 0);
        return r;
    }

    private static void walk(ContentResolver cr, Uri tree, String id, List<String> rel, Result r, int depth) {
        if (depth > MAX_DEPTH || r.files.size() >= MAX_FILES) { if (r.files.size() >= MAX_FILES) r.cut = true; return; }
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        List<String[]> dirs = new ArrayList<>();
        List<Item> here = new ArrayList<>();
        try (Cursor q = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (q == null) return;
            while (q.moveToNext()) {
                String did = q.getString(0), name = q.getString(1), mime = q.getString(2);
                if (name == null || name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) dirs.add(new String[]{did, name});
                else {
                    Item it = new Item();
                    it.uri = DocumentsContract.buildDocumentUriUsingTree(tree, did);
                    it.name = name; it.mime = mime == null ? "" : mime; it.rel = rel.toArray(new String[0]);
                    here.add(it);
                }
            }
        } catch (RuntimeException e) { return; }
        here.sort((a, b) -> naturalCompare(a.name, b.name));
        r.files.addAll(here);
        dirs.sort((a, b) -> naturalCompare(a[1], b[1]));
        for (String[] d : dirs) {
            rel.add(d[1]);
            walk(cr, tree, d[0], rel, r, depth + 1);
            rel.remove(rel.size() - 1);
        }
    }

    /** "Ep 2" before "Ep 10". */
    static int naturalCompare(String a, String b) {
        java.util.regex.Matcher x = java.util.regex.Pattern.compile("\\d+|\\D+").matcher(a.toLowerCase()), y = java.util.regex.Pattern.compile("\\d+|\\D+").matcher(b.toLowerCase());
        while (x.find() && y.find()) {
            String p = x.group(), q = y.group();
            int c = Character.isDigit(p.charAt(0)) && Character.isDigit(q.charAt(0)) ? Long.compare(Long.parseLong(p.length() > 17 ? p.substring(0, 17) : p), Long.parseLong(q.length() > 17 ? q.substring(0, 17) : q)) : p.compareTo(q);
            if (c != 0) return c;
        }
        return a.compareToIgnoreCase(b);
    }

    // ---- the folders you added
    static List<String[]> saved(Context c) {          // [uri, kind ("video" | "book"), name]
        List<String[]> out = new ArrayList<>();
        try {
            for (Object o : Json.arr(Json.parse(c.getSharedPreferences("pocket", Context.MODE_PRIVATE).getString("folders", "[]")))) {
                List<Object> l = Json.arr(o);
                if (l.size() >= 3) out.add(new String[]{String.valueOf(l.get(0)), String.valueOf(l.get(1)), String.valueOf(l.get(2))});
            }
        } catch (RuntimeException ignored) { /* none */ }
        return out;
    }

    static void remember(Context c, Uri tree, String kind, String name) {
        List<String[]> s = saved(c);
        for (String[] x : s) if (x[0].equals(tree.toString()) && x[1].equals(kind)) return;
        s.add(new String[]{tree.toString(), kind, name});
        List<Object> arr = new ArrayList<>();
        for (String[] x : s) arr.add(new ArrayList<Object>(java.util.Arrays.asList((Object[]) x)));
        c.getSharedPreferences("pocket", Context.MODE_PRIVATE).edit().putString("folders", Json.write(arr)).apply();
    }

    static void forget(Context c, String uri, String kind) {
        List<Object> arr = new ArrayList<>();
        for (String[] x : saved(c)) if (!(x[0].equals(uri) && x[1].equals(kind))) arr.add(new ArrayList<Object>(java.util.Arrays.asList((Object[]) x)));
        c.getSharedPreferences("pocket", Context.MODE_PRIVATE).edit().putString("folders", Json.write(arr)).apply();
    }

    /** Files of a book folder or video folder already taken in (by document address), so a second look adds only the new ones. */
    static Set<String> seen(Context c, String kind) {
        Set<String> s = new LinkedHashSet<>();
        try { for (Object o : Json.arr(Json.parse(c.getSharedPreferences("pocket", Context.MODE_PRIVATE).getString("seen_" + kind, "[]")))) s.add(String.valueOf(o)); } catch (RuntimeException ignored) { /* none */ }
        return s;
    }

    static void markSeen(Context c, String kind, Set<String> s) {
        c.getSharedPreferences("pocket", Context.MODE_PRIVATE).edit().putString("seen_" + kind, Json.write(new ArrayList<Object>(s))).apply();
    }
}
