package com.batchmine.pocket;

import java.util.Collections;
import java.util.Map;

/** Feature 1: CEFR levels (A1–C2). The PC owns the tables (levels_xx.tsv); shown as a chip by the word sheet, the Reader bar and the Words
 *  list through Extras.factSource, so no screen knows about it. */
final class Levels {
    private Levels() { }

    static final PcTable TABLE = new PcTable("levels", "/api/pocket/levels");

    static String of(String lang, String word) { return TABLE.get(lang, word); }

    static void register() {
        Extras.factSource("level", 10, (lang, word) -> {
            String lv = of(lang, word);
            return lv.isEmpty() ? Collections.<Extras.Fact>emptyList() : Collections.singletonList(new Extras.Fact(lv, "level"));
        });
        Extras.syncPart("levels", new Extras.SyncPart() {
            @Override public void pulled(Map<String, Object> extra) { TABLE.pull(); }
        });
    }
}
