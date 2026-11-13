package com.batchmine.pocket;

import com.batchmine.pocket.core.Json;
import com.batchmine.pocket.core.PcLink;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** A word → value table that the PC owns and the phone keeps a copy of (levels, genders …): one file per language, taken at each sync from
 *  GET <endpoint>?lang=&have=<version> and only downloaded again when the PC's version differs. A feature that needs another table of this
 *  kind creates one PcTable (and an endpoint on the PC); nothing else. */
final class PcTable {
    private final String name, endpoint;
    private final Map<String, Map<String, String>> cache = new HashMap<>();

    PcTable(String name, String endpoint) { this.name = name; this.endpoint = endpoint; }

    private File file(String lang) { return new File(App.get().dir(name), lang + ".tsv"); }

    private synchronized Map<String, String> table(String lang) {
        Map<String, String> m = cache.get(lang);
        if (m != null) return m;
        m = new HashMap<>();
        File f = file(lang);
        if (f.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    int t = line.indexOf('\t');
                    if (t > 0) m.put(line.substring(0, t), line.substring(t + 1).trim());
                }
            } catch (IOException | RuntimeException ignored) { }
        }
        cache.put(lang, m);
        return m;
    }

    /** The value of WORD (lower-cased), or "". */
    String get(String lang, String word) {
        if (word == null) return "";
        String v = table(lang).get(word.trim().toLowerCase(Locale.ROOT));
        return v == null ? "" : v;
    }

    int count(String lang) { return table(lang).size(); }

    /** Takes the PC's table if its version differs (runs on the sync thread). */
    void pull() {
        App app = App.get();
        PcLink pc = app.pc();
        if (pc == null) return;
        String lang = app.lang(), vk = name + "_v_" + lang;
        String have = file(lang).isFile() ? app.prefs().getString(vk, "") : "";
        try {
            Map<String, Object> r = pc.get(endpoint + "?lang=" + PcLink.enc(lang) + "&have=" + PcLink.enc(have), 120000);
            String tsv = Json.str(r.get("tsv"), ""), v = Json.str(r.get("v"), "");
            if (tsv.isEmpty() || v.isEmpty()) return;                      // the phone has this version, or the PC has no table
            File f = file(lang), tmp = new File(f.getPath() + ".part");
            Files.write(tmp.toPath(), tsv.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
            app.prefs().edit().putString(vk, v).apply();
            synchronized (this) { cache.remove(lang); }
        } catch (IOException | RuntimeException e) { android.util.Log.w("Glossa", name + " not taken", e); }
    }
}
