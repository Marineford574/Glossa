package com.batchmine.pocket;

import com.batchmine.pocket.core.Json;

import java.util.Map;

/** Feature 3: the definition languages switched on / off on the PC (Setup → Files and Anki) are followed by the phone: an entry written in a
 *  switched-off language is not shown in the word sheet. The PC sends {"EN": true, "IT": false} with each sync. */
final class DefLangs {
    private DefLangs() { }

    private static String key(String lang) { return "def_langs_" + lang; }

    /** False when the PC has this definition language (e.g. "it") switched off for the language LANG. Unknown = on. */
    static boolean on(String lang, String defLang) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(App.get().prefs().getString(key(lang), "{}")));
            Object v = m.get(defLang.toUpperCase(java.util.Locale.ROOT));
            return v == null || Boolean.TRUE.equals(v);
        } catch (RuntimeException e) { return true; }
    }

    static void register() {
        Extras.syncPart("deflangs", new Extras.SyncPart() {
            @Override public void pulled(Map<String, Object> extra) {
                Map<String, Object> m = Json.obj(extra.get("def_langs"));
                if (m.isEmpty()) return;
                App.get().prefs().edit().putString(key(App.get().lang()), Json.write(m)).apply();
            }
        });
    }
}
