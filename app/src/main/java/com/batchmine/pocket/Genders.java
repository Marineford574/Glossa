package com.batchmine.pocket;

import java.util.Collections;
import java.util.Map;

/** Feature 4: the gender of a noun, from the wty / Wiktionary files on the PC (the same ones its cards use). "m", "f", "n", "mf" → a chip:
 *  masc / fem / neut (German: der / die / das). */
final class Genders {
    private Genders() { }

    static final PcTable TABLE = new PcTable("genders", "/api/pocket/genders");

    static String label(String lang, String g) {
        if (g.isEmpty()) return "";
        boolean de = "de".equals(lang);
        StringBuilder sb = new StringBuilder();
        for (char c : g.toCharArray()) {
            String x = c == 'm' ? (de ? "der" : "masc") : c == 'f' ? (de ? "die" : "fem") : c == 'n' ? (de ? "das" : "neut") : "";
            if (x.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(x);
        }
        return sb.toString();
    }

    static void register() {
        Extras.factSource("gender", 20, (lang, word) -> {
            String g = label(lang, TABLE.get(lang, word));
            return g.isEmpty() ? Collections.<Extras.Fact>emptyList() : Collections.singletonList(new Extras.Fact(g, "gender"));
        });
        Extras.syncPart("genders", new Extras.SyncPart() {
            @Override public void pulled(Map<String, Object> extra) { TABLE.pull(); }
        });
    }
}
