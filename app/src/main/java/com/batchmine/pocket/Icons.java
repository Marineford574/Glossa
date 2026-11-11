package com.batchmine.pocket;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** The app icon on the phone's launcher: the same choice of icons as Glossa on the PC. Each is an activity-alias in the manifest; one is on. */
final class Icons {
    private Icons() { }

    /** id, name; "original" is the first one. */
    static final String[][] ALL = {{"original", "Original"}, {"illuminated", "Illuminated initial"}, {"oldgold", "Old English gold"}, {"swashred", "Red swash"}, {"cooknight", "Gothic night"}, {"gaira", "Gothic gaira"}, {"gairared", "Gaira, red"}, {"glagring", "Glagolitic ring"}, {"baska", "Baška stone"}, {"sahovnica", "Šahovnica"}, {"hrvatska", "Red, white, blue"}, {"gammaclassic", "Classical Γ"}, {"gammalambda", "γλ"}, {"georgian", "Georgian გ"}};

    static String name(String id) {
        for (String[] i : ALL) if (i[0].equals(id)) return i[1];
        return "Original";
    }

    /** The icon that is on now. */
    static String current(Context c) {
        return c.getSharedPreferences("pocket", Context.MODE_PRIVATE).getString("icon_now", "original");
    }

    /** Switches the launcher to this icon (the launcher may take a few seconds; a shortcut on the home screen may need adding again). */
    static void apply(Context c, String id) {
        boolean known = false;
        for (String[] i : ALL) if (i[0].equals(id)) known = true;
        if (!known) id = "original";
        if (id.equals(current(c))) return;
        PackageManager pm = c.getPackageManager();
        for (String[] i : ALL) {
            ComponentName cn = new ComponentName(c, "com.batchmine.pocket." + ("original".equals(i[0]) ? "Launch" : "Launch_" + i[0]));
            pm.setComponentEnabledSetting(cn, i[0].equals(id) ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
        c.getSharedPreferences("pocket", Context.MODE_PRIVATE).edit().putString("icon_now", id).apply();
    }

    /** After a sync: when "Same as on the PC" is chosen, the PC's icon. */
    static void followPc(Context c, String pcIcon) {
        if (!"pc".equals(c.getSharedPreferences("pocket", Context.MODE_PRIVATE).getString("icon", "original"))) return;
        if (pcIcon == null || pcIcon.isEmpty()) return;
        apply(c, pcIcon);
    }
}
