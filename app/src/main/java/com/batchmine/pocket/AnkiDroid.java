package com.batchmine.pocket;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Adding cards to AnkiDroid through its own interface for other apps (the "AnkiDroid API": a content provider; the
 * permission is asked for once). Note types and decks are AnkiDroid's; media files are handed over by a link to this
 * app's FilesProvider.
 */
public final class AnkiDroid {
    public static final String PKG = "com.ichi2.anki", AUTH = "com.ichi2.anki.flashcards",
            PERM = "com.ichi2.anki.permission.READ_WRITE_DATABASE";
    private static final Uri NOTES = Uri.parse("content://" + AUTH + "/notes"), MODELS = Uri.parse("content://" + AUTH + "/models"),
            DECKS = Uri.parse("content://" + AUTH + "/decks"), MEDIA = Uri.parse("content://" + AUTH + "/media");

    private AnkiDroid() { }

    public static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    public static boolean allowed(Context c) { return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED; }

    public static final class Model {
        public final long id; public final String name; public final String[] fields;
        Model(long id, String name, String[] fields) { this.id = id; this.name = name; this.fields = fields; }
        @Override public String toString() { return name; }
    }

    public static final class Deck {
        public final long id; public final String name;
        Deck(long id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    public static List<Model> models(Context c) throws IOException {
        List<Model> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(MODELS, new String[]{"_id", "name", "field_names"}, null, null, null)) {
            if (cur == null) throw new IOException("AnkiDroid did not answer: open AnkiDroid once, then try again");
            while (cur.moveToNext()) {
                String f = cur.getString(2);
                out.add(new Model(cur.getLong(0), cur.getString(1), f == null ? new String[0] : f.split("\u001f", -1)));
            }
        } catch (SecurityException e) {
            throw new IOException("AnkiDroid's permission is missing: allow it (Settings → Cards)");
        }
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    public static List<Deck> decks(Context c) throws IOException {
        List<Deck> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(DECKS, new String[]{"deck_id", "deck_name"}, null, null, null)) {
            if (cur == null) throw new IOException("AnkiDroid did not answer: open AnkiDroid once, then try again");
            while (cur.moveToNext()) out.add(new Deck(cur.getLong(0), cur.getString(1)));
        } catch (SecurityException e) {
            throw new IOException("AnkiDroid's permission is missing: allow it (Settings → Cards)");
        }
        out.sort((x, y) -> x.name.compareToIgnoreCase(y.name));
        return out;
    }

    /** Copies FILE into AnkiDroid's media folder; returns what goes in the field: [sound:name] or <img src="name">. */
    public static String addMedia(Context c, File file, boolean audio) throws IOException {
        Uri uri = FilesProvider.uriFor(c, file);
        c.grantUriPermission(PKG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ContentValues v = new ContentValues();
        v.put("file_uri", uri.toString());
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        v.put("preferred_name", (dot > 0 ? name.substring(0, dot) : name).replace(' ', '_'));
        Uri res;
        try { res = c.getContentResolver().insert(MEDIA, v); }
        catch (RuntimeException e) { throw new IOException("AnkiDroid did not take the " + (audio ? "sound" : "picture") + ": " + e.getMessage()); }
        if (res == null || res.getLastPathSegment() == null) throw new IOException("AnkiDroid did not take the " + (audio ? "sound" : "picture"));
        String stored = res.getLastPathSegment();
        return audio ? "[sound:" + stored + "]" : "<img src=\"" + stored + "\">";
    }

    /** A new note of note type MID; its cards go to deck DID. Returns the note's id. */
    public static long addNote(Context c, long mid, long did, String[] fields, String tags) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put("mid", mid);
        v.put("flds", String.join("\u001f", fields));
        v.put("tags", tags);
        Uri note;
        try { note = cr.insert(NOTES, v); }
        catch (RuntimeException e) { throw new IOException("AnkiDroid did not add the card: " + e.getMessage()); }
        if (note == null) throw new IOException("AnkiDroid did not add the card (is the first field empty, or the note type changed?)");
        Uri cards = Uri.withAppendedPath(note, "cards");
        try (Cursor cur = cr.query(cards, new String[]{"ord"}, null, null, null)) {
            if (cur != null) {
                while (cur.moveToNext()) {
                    ContentValues dv = new ContentValues();
                    dv.put("deck_id", did);
                    cr.update(Uri.withAppendedPath(cards, cur.getString(0)), dv, null, null);
                }
            }
        } catch (RuntimeException ignored) { }           // the note is there; it may just be in the note type's deck
        try { return Long.parseLong(note.getLastPathSegment()); } catch (RuntimeException e) { return -1; }
    }

    public static void openAnkiDroid(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(PKG);
        if (i != null) c.startActivity(i);
    }
}
