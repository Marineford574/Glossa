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
            if (on && !paused) { add(kind, 1); }
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

    /** A screen where the time counts (Player: "watch", Reader: "read") came to the front. BUSY says "counts without a touch" (a video playing). */
    static void start(String k, Busy b) {
        stop();
        kind = k; busy = b; paused = false; lastActive = System.currentTimeMillis();
        H.postDelayed(TICK, 1000);
    }

    static void stop() {
        H.removeCallbacks(TICK);
        kind = null; busy = null;
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
                t.setText((paused ? "⏸ " : "⏱ ") + clock(seconds(day(System.currentTimeMillis()), k)));
                H.removeCallbacks(this);
                if (t.isAttachedToWindow()) H.postDelayed(this, 1000);
            }
        };
        t.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) { r[0].run(); }
            @Override public void onViewDetachedFromWindow(android.view.View v) { H.removeCallbacks(r[0]); }
        });
        t.setOnClickListener(v -> { paused = !paused; r[0].run(); Ui.toast(c, paused ? "Stopwatch paused: tap again to go on" : "Stopwatch running"); });
        t.setContentDescription("Time today (" + ("watch".equals(k) ? "watching" : "reading") + "): tap to pause or resume");
        r[0].run();
        return t;
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
