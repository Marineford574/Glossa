package com.batchmine.pocket;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Settings → Storage: what the app keeps on the phone, how big each part is, and clean-up for the parts that are only copies. */
final class Storage {
    private Storage() {}

    static final class Part {
        final String name, note;
        final File dir;
        final boolean cleanable;
        long bytes;
        Part(String name, String note, File dir, boolean cleanable) { this.name = name; this.note = note; this.dir = dir; this.cleanable = cleanable; }
    }

    static List<Part> parts(App app) {
        List<Part> l = new ArrayList<>();
        l.add(new Part("Dictionaries", "imported; import again to get them back", app.dir("dicts"), false));
        l.add(new Part("Videos", "copied from the PC or downloaded; the subtitles next to them too", app.videoDir(), false));
        l.add(new Part("Books", "", app.dir("books"), false));
        l.add(new Part("Card pictures and sound", "the media of the cards you made; never deleted from here", app.dir("cards"), false));
        l.add(new Part("Video thumbnails", "cut again when needed", app.dir("thumbs"), true));
        l.add(new Part("Temporary files", "cache; rebuilt when needed", app.getCacheDir(), true));
        return l;
    }

    /** Size of a folder with everything below it (0 when it is not there). */
    static long size(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        File[] k = f.listFiles();
        long n = 0;
        if (k != null) for (File c : k) n += size(c);
        return n;
    }

    /** Empties a folder (the folder stays). Returns the bytes freed. */
    static long empty(File dir) {
        long freed = 0;
        File[] k = dir == null ? null : dir.listFiles();
        if (k != null) for (File c : k) {
            long s = size(c);
            if (c.isDirectory()) empty(c);
            if (c.delete()) freed += s;
        }
        return freed;
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        double v = b / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.0f KB", v);
        v /= 1024;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        return String.format(Locale.US, "%.2f GB", v / 1024);
    }

    /** The screen: sizes are counted in the background, each clean-able part gets a button. */
    static void show(Activity a, App app) {
        LinearLayout box = Ui.col(a);
        TextView wait = Ui.hint(a, "Counting…");
        box.addView(wait);
        android.app.AlertDialog dlg = Ui.dialog(a).setTitle("Storage").setView(box).setPositiveButton("Close", null).show();
        app.bg(() -> {
            List<Part> ps = parts(app);
            long total = 0;
            for (Part p : ps) { p.bytes = size(p.dir); total += p.bytes; }
            final long all = total;
            app.post(() -> {
                box.removeAllViews();
                LinearLayout g = Ui.group(a);
                for (Part p : ps) {
                    Ui.srow(g, p.name + " · " + human(p.bytes), p.note, p.cleanable ? Ui.small(a, "Clear", v -> {
                        long freed = empty(p.dir);
                        dlg.dismiss();
                        Ui.alert(a, "Cleared", human(freed) + " freed.");
                    }) : null, null);
                }
                Ui.add(box, g, 0);
                box.addView(Ui.hint(a, "Together " + human(all) + ". To free more: delete videos in the video list, or books and dictionaries in their own settings."));
            });
        });
    }
}
