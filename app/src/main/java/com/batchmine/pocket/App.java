package com.batchmine.pocket;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.batchmine.pocket.core.Dictionaries;
import com.batchmine.pocket.core.PcLink;
import com.batchmine.pocket.core.WordStore;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** What every screen shares: the dictionaries, the word store of each language, settings, a worker thread pool. */
public final class App extends Application {
    private static App me;
    public Dictionaries dicts;
    public final ExecutorService work = Executors.newFixedThreadPool(3);
    public final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, WordStore> stores = new HashMap<>();

    public static App get() { return me; }

    @Override public void onCreate() {
        super.onCreate();
        me = this;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            report("The app closed", e);
            if (prev != null) prev.uncaughtException(t, e); else System.exit(2);
        });
        dicts = new Dictionaries(new File(getFilesDir(), "dicts"));
        Ui.load(this);                                  // the colours of the theme (the PC's, or one picked here)
    }

    /** Writes what went wrong to crash.txt; the home screen shows it (with a Copy button) the next time it opens. */
    public void report(String what, Throwable e) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            String ver = "?";
            try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
            String head = what + " · " + new java.util.Date() + " · app " + ver + " · " + android.os.Build.MANUFACTURER + " "
                    + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE + " (" + android.os.Build.VERSION.SDK_INT + ")\n";
            File f = new File(getFilesDir(), "crash.txt");
            String old = f.isFile() && f.length() < 40000 ? new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8) + "\n" : "";
            java.nio.file.Files.write(f.toPath(), (old + head + sw).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Throwable ignored) { }
    }

    public SharedPreferences prefs() { return getSharedPreferences("pocket", MODE_PRIVATE); }

    public String lang() { return prefs().getString("lang", "it"); }

    public void setLang(String l) { prefs().edit().putString("lang", l).apply(); }

    public synchronized WordStore store(String lang) {
        WordStore s = stores.get(lang);
        if (s == null) { s = new WordStore(dir("words"), lang); stores.put(lang, s); }
        return s;
    }

    public WordStore store() { return store(lang()); }

    /** After a restore: the word files are read again from disk. */
    public synchronized void forgetStores() { stores.clear(); }

    public PcLink pc() { return PcLink.parse(prefs().getString("pc_link", "")); }

    public File dir(String name) { File f = new File(getFilesDir(), name); f.mkdirs(); return f; }

    /** Videos copied from the PC: in the app's own folder on the phone's storage (removed with the app). */
    public File videoDir() {
        File base = getExternalFilesDir("videos");
        if (base == null) base = new File(getFilesDir(), "videos");
        base.mkdirs();
        return base;
    }

    /** Work off the screen's thread; a mistake in it is reported instead of closing the app. */
    public void bg(Runnable r) {
        work.execute(() -> {
            try { r.run(); }
            catch (Throwable e) {
                report("Something went wrong (the app stayed open)", e);
                post(() -> android.widget.Toast.makeText(this, "Something went wrong: " + e + " (details in Settings → Problem report)", android.widget.Toast.LENGTH_LONG).show());
            }
        });
    }

    /** Work on the screen's thread; a mistake in it is reported instead of closing the app. */
    public void post(Runnable r) {
        ui.post(() -> {
            try { r.run(); }
            catch (RuntimeException e) {
                report("Something went wrong on the screen (the app stayed open)", e);
                android.widget.Toast.makeText(this, "Something went wrong: " + e + " (details in Settings → Problem report)", android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }
}
