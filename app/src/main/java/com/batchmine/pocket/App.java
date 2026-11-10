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
        dicts = new Dictionaries(new File(getFilesDir(), "dicts"));
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

    public PcLink pc() { return PcLink.parse(prefs().getString("pc_link", "")); }

    public File dir(String name) { File f = new File(getFilesDir(), name); f.mkdirs(); return f; }

    /** Videos copied from the PC: in the app's own folder on the phone's storage (removed with the app). */
    public File videoDir() {
        File base = getExternalFilesDir("videos");
        if (base == null) base = new File(getFilesDir(), "videos");
        base.mkdirs();
        return base;
    }

    public void bg(Runnable r) { work.execute(r); }

    public void post(Runnable r) { ui.post(r); }
}
