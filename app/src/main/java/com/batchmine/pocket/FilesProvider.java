package com.batchmine.pocket;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands card sounds and pictures (in the app's "cards" folder, nothing else) to AnkiDroid by a content:// link, which
 * AnkiDroid is given permission to read.
 */
public final class FilesProvider extends ContentProvider {
    public static String authority(Context c) { return c.getPackageName() + ".files"; }

    public static Uri uriFor(Context c, File f) throws IOException {
        File dir = App.get().dir("cards").getCanonicalFile();
        File cf = f.getCanonicalFile();
        if (!cf.getPath().startsWith(dir.getPath() + File.separator)) throw new IOException("not a card file");
        return new Uri.Builder().scheme("content").authority(authority(c)).appendPath(cf.getName()).build();
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        boolean upd = uri.getPathSegments().size() == 2 && "update".equals(uri.getPathSegments().get(0));
        File f = upd ? new File(new File(getContext().getCacheDir(), "update"), name) : new File(new File(getContext().getFilesDir(), "cards"), name);
        if (upd && !name.endsWith(".apk")) throw new FileNotFoundException(name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        try {
            File f = file(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public String getType(Uri uri) {
        String n = String.valueOf(uri.getLastPathSegment()).toLowerCase();
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".jpg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".webm")) return "audio/webm";
        if (n.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
}
