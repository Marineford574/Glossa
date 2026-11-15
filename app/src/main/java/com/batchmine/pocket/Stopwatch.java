package com.batchmine.pocket;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The time you spend watching and reading, counted per day like the PC's stopwatch. It counts while the screen is in
 * use: a video that plays always counts; otherwise it counts while something happens (a touch) and stops after the idle
 * time set in Settings (0 = never stops). The seconds of each day are kept in the app's preferences ("sw_<day>_watch" /
 * "_read") and go to the PC at a sync, which adds them to its own for the week chart.
 */
final class Stopwatch {
    private Stopwatch() { }

    interface Busy { boolean now(); }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static String kind;                        // "watch" / "read" while one runs
    private static String item = "";                   // the video ("v:<file name>") or book ("b:<id>") open now: its own time is kept too
    private static boolean paused;                     // paused by a tap on the clock in the bar (until the screen is left)
    private static Busy busy;
    private static long lastActive;
    private static int unsaved;
    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (kind == null) return;
            long now = System.currentTimeMillis();
            int idle = App.get().prefs().getInt("watch".equals(kind) ? "sw_idle_watch" : "sw_idle_read", "watch".equals(kind) ? 20 : 90);
            boolean on = (busy != null && busy.now()) || idle <= 0 || now - lastActive <= idle * 1000L;
            if (on && !paused) { add(kind, 1); if (!item.isEmpty()) addItem(item, 1); }
            H.postDelayed(this, 1000);
        }
    };

    static String day(long t) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(t)); }

    static int seconds(String day, String k) { return App.get().prefs().getInt("sw_" + day + "_" + k, 0); }

    private static void add(String k, int s) {
        SharedPreferences sp = App.get().prefs();
        String key = "sw_" + day(System.currentTimeMillis()) + "_" + k;
        sp.edit().putInt(key, sp.getInt(key, 0) + s).apply();
    }

    static int itemSeconds(String key) { return key == null || key.isEmpty() ? 0 : App.get().prefs().getInt("swi_" + key, 0); }

    private static void addItem(String key, int s) {
        SharedPreferences sp = App.get().prefs();
        sp.edit().putInt("swi_" + key, sp.getInt("swi_" + key, 0) + s).apply();
    }

    /** The same, for the video or book ITEM ("v:<file>" / "b:<id>"). */
    static void start(String k, String itemKey, Busy b) { start(k, b); item = itemKey == null ? "" : itemKey; }

    /** A screen where the time counts (Player: "watch", Reader: "read") came to the front. BUSY says "counts without a touch" (a video playing). */
    static void start(String k, Busy b) {
        stop();
        kind = k; busy = b; paused = false; lastActive = System.currentTimeMillis();
        H.postDelayed(TICK, 1000);
    }

    static void stop() {
        H.removeCallbacks(TICK);
        kind = null; busy = null; item = "";
    }

    /** 12:34, or 1:02:03 from an hour on. */
    static String clock(int s) {
        s = Math.max(0, s);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /**
     * A small clock for a bar: today's time of this kind ("watch" or "read"), counting up while the stopwatch runs. A tap
     * pauses and resumes it (a coffee break with the video paused is counted anyway otherwise); it starts again when the
     * screen is left and opened again.
     */
    static android.widget.TextView chip(android.content.Context c, final String k, int fg, int bg) {
        final android.widget.TextView t = Ui.pill(c, "", fg, bg);
        final Runnable[] r = new Runnable[1];
        r[0] = new Runnable() {
            @Override public void run() {
                t.setText((paused ? "⏸ " : "⏱ ") + clock(item.isEmpty() ? seconds(day(System.currentTimeMillis()), k) : itemSeconds(item)));
                H.removeCallbacks(this);
                if (t.isAttachedToWindow()) H.postDelayed(this, 1000);
            }
        };
        t.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) { r[0].run(); }
            @Override public void onViewDetachedFromWindow(android.view.View v) { H.removeCallbacks(r[0]); }
        });
        t.setOnClickListener(v -> { paused = !paused; r[0].run(); Ui.toast(c, paused ? "Stopwatch paused: tap again to go on" : "Stopwatch running"); });
        t.setOnLongClickListener(v -> { editMenu(c, k, () -> r[0].run()); return true; });
        t.setContentDescription("Time on this " + ("watch".equals(k) ? "video" : "book") + ": tap to pause or resume, hold for today's total, to edit or reset");
        r[0].run();
        return t;
    }

    /** "12:34", "1:02:03" or plain minutes ("45") to seconds; -1 when it is none of these. */
    static int parse(String s) {
        s = s.trim();
        if (s.isEmpty()) return -1;
        try {
            String[] p = s.split(":");
            if (p.length == 1) return Integer.parseInt(p[0].trim()) * 60;
            if (p.length == 2) return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim());
            if (p.length == 3) return Integer.parseInt(p[0].trim()) * 3600 + Integer.parseInt(p[1].trim()) * 60 + Integer.parseInt(p[2].trim());
        } catch (NumberFormatException e) { return -1; }
        return -1;
    }

    /** Long press on the clock: change today's time (it goes to the PC at the next sync as set) or start it again from zero. */
    static void editMenu(android.content.Context c, String k, Runnable done) {
        final String today = day(System.currentTimeMillis());
        final String dayKey = "sw_" + today + "_" + k;
        final String it = item;
        final String what = "watch".equals(k) ? "this video" : "this book";
        String title = (it.isEmpty() ? "" : "On " + what + ": " + clock(itemSeconds(it)) + "\n") + ("watch".equals(k) ? "Watching" : "Reading") + " today: " + clock(seconds(today, k));
        String[] opts = it.isEmpty() ? new String[]{"Set today's time…", "Reset today to 0:00"}
                : new String[]{"Set the time on " + what + "…", "Reset " + what + " to 0:00", "Set today's time…", "Reset today to 0:00"};
        Ui.choose(c, title, opts, -1, i -> {
            boolean onItem = !it.isEmpty() && i < 2;
            boolean reset = it.isEmpty() ? i == 1 : (i == 1 || i == 3);
            String key = onItem ? "swi_" + it : dayKey;
            int cur = onItem ? itemSeconds(it) : seconds(today, k);
            String name = onItem ? what : "today";
            if (reset) {
                Ui.dialog(c).setTitle("Reset " + name + "?").setMessage("The time of " + name + " goes back to 0:00.")
                        .setPositiveButton("Reset", (d, w) -> { App.get().prefs().edit().putInt(key, 0).apply(); done.run(); })
                        .setNegativeButton("Cancel", null).show();
                return;
            }
            android.widget.EditText in = Ui.input(c, clock(cur), "m:ss or h:mm:ss");
            Ui.dialog(c).setTitle("Time on " + name).setMessage("Minutes (45), m:ss (12:30) or h:mm:ss (1:02:03).").setView(in)
                    .setPositiveButton("Set", (d, w) -> {
                        int v = parse(in.getText().toString());
                        if (v < 0) { Ui.toast(c, "That is not a time"); return; }
                        App.get().prefs().edit().putInt(key, v).apply();
                        done.run();
                    }).setNegativeButton("Cancel", null).show();
        });
    }

    /** Something happened on screen (a touch). */
    static void active() { lastActive = System.currentTimeMillis(); }

    /** The last DAYS days with time on them, for the sync: {day: {watch, read}} (older days are forgotten there anyway). */
    static Map<String, Object> forSync(int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < days; i++) {
            String d = day(c.getTimeInMillis());
            int w = seconds(d, "watch"), r = seconds(d, "read");
            if (w > 0 || r > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("watch", (double) w); m.put("read", (double) r);
                out.put(d, m);
            }
            c.add(Calendar.DAY_OF_MONTH, -1);
        }
        return out;
    }

    /** Old days are dropped from the preferences after a month. */
    static void tidy() {
        SharedPreferences sp = App.get().prefs();
        String cut = day(System.currentTimeMillis() - 40L * 86400000L);
        SharedPreferences.Editor ed = sp.edit();
        for (String k : sp.getAll().keySet()) if (k.startsWith("sw_20") && k.length() > 13 && k.substring(3, 13).compareTo(cut) < 0) ed.remove(k);
        ed.apply();
    }
}
