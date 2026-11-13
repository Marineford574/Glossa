package com.batchmine.pocket;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Calendar;

/** Feature 9: one reminder a day at a time you choose ("Time for some Italian"), skipped on a day you already opened the app.
 *  Inexact alarm (no special permission), set again each time it fires and each time the app starts. */
public final class Reminder extends BroadcastReceiver {
    private static final String CHANNEL = "reminder";

    static boolean on(SharedPreferences p) { return p.getBoolean("rem_on", false); }
    static int minutes(SharedPreferences p) { return p.getInt("rem_min", 19 * 60); }

    /** Called from MainActivity.onResume: today counts as done. */
    static void touch(Context c) {
        SharedPreferences p = c.getSharedPreferences("pocket", Context.MODE_PRIVATE);
        p.edit().putLong("rem_used", System.currentTimeMillis()).apply();
    }

    /** (Re)arm or cancel according to the settings. */
    static void schedule(Context c) {
        SharedPreferences p = c.getSharedPreferences("pocket", Context.MODE_PRIVATE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 77, new Intent(c, Reminder.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (am == null) return;
        am.cancel(pi);
        if (!on(p)) return;
        Calendar t = Calendar.getInstance();
        int m = minutes(p);
        t.set(Calendar.HOUR_OF_DAY, m / 60);
        t.set(Calendar.MINUTE, m % 60);
        t.set(Calendar.SECOND, 0);
        if (t.getTimeInMillis() <= System.currentTimeMillis() + 60000) t.add(Calendar.DAY_OF_YEAR, 1);
        am.setAndAllowWhileIdle(AlarmManager.RTC, t.getTimeInMillis(), pi);
    }

    /** Switching on: Android 13+ asks for permission to show notifications. */
    static void enable(Activity a, boolean on) {
        a.getSharedPreferences("pocket", Context.MODE_PRIVATE).edit().putBoolean("rem_on", on).apply();
        if (on && android.os.Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 77);
        schedule(a);
    }

    @Override public void onReceive(Context c, Intent in) {
        SharedPreferences p = c.getSharedPreferences("pocket", Context.MODE_PRIVATE);
        try {
            if (on(p)) {
                Calendar used = Calendar.getInstance(), now = Calendar.getInstance();
                used.setTimeInMillis(p.getLong("rem_used", 0));
                boolean today = used.get(Calendar.YEAR) == now.get(Calendar.YEAR) && used.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR);
                if (!today) post(c, p);
            }
        } catch (RuntimeException e) { android.util.Log.w("Glossa", "reminder", e); }
        schedule(c);
    }

    private static void post(Context c, SharedPreferences p) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT));
        String lang = p.getString("lang", "").toUpperCase(java.util.Locale.ROOT);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 78, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_nav_book)
                .setContentTitle("Glossa Pocket")
                .setContentText(lang.isEmpty() ? "Time for a little reading or watching." : "Time for a little " + lang + ": a video, a few pages.")
                .setContentIntent(pi).setAutoCancel(true).build();
        nm.notify(77, n);
    }
}
