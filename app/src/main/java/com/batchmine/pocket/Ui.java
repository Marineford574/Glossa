package com.batchmine.pocket;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Building the screens in code (no layout files): colours, sizes and the few kinds of views every screen uses. */
public final class Ui {
    private Ui() { }

    /** The colours of the screens: the theme picked on the PC (Glossa → Look and window), or one picked here. Set by load(). */
    public static int BG = 0xFF0F1014, CARD = 0xFF181A20, CARD2 = 0xFF22252E, TEXT = 0xFFF2F3F5, DIM = 0xFFA3A7B3,
            ACCENT = 0xFF5B9CF0, ON_ACC = 0xFF0F1014, LINE = 0xFF2A2C33;
    public static final int BAD = 0xFFE5534B, GOOD = 0xFF33D17A;
    public static boolean LIGHT = false;

    /** id, name, background, surface, card, text, dim text, accent, text on the accent, light (1/0): the same themes as the PC. */
    public static final String[][] THEMES = {
            {"midnight", "Midnight", "#0f1014", "#181a20", "#22252e", "#f2f3f5", "#a3a7b3", "#5b9cf0", "#0f1014", "0"},
            {"daylight", "Daylight", "#f5f6f8", "#ffffff", "#eceef2", "#16181d", "#575c69", "#2563d9", "#ffffff", "1"},
            {"paper", "Paper", "#f3ead6", "#fbf5e7", "#eadcbd", "#3a2e21", "#6a5843", "#a8501a", "#fff8ee", "1"},
            {"arctic", "Arctic", "#2e3440", "#353c4a", "#3f4757", "#eceff4", "#b3bccc", "#88c0d0", "#1f242d", "0"},
            {"forest", "Forest", "#0e1915", "#14231d", "#1c3029", "#e7f1ec", "#9db3a8", "#4cc38a", "#0b1511", "0"},
            {"oled", "Pure black", "#000000", "#0c0c0e", "#18181c", "#ffffff", "#a0a0aa", "#a78bfa", "#0c0a14", "0"},
            {"mocha", "Mocha", "#1a1512", "#231c18", "#30261f", "#f3ebe3", "#b9a898", "#e0b07a", "#1c130b", "0"},
            {"mist", "Mist", "#e9eef3", "#f7f9fb", "#d9e1ea", "#1b2430", "#52606f", "#3d7a9c", "#ffffff", "1"},
            {"synthwave", "Synthwave", "#130a22", "#1c1033", "#2a1748", "#fbefff", "#c3aedd", "#ff4fd8", "#1a0820", "0"},
            {"ember", "Ember", "#1a0f0b", "#25150f", "#361f16", "#fff1e8", "#d1ad9b", "#ff7a3d", "#1c0d06", "0"},
            {"aurora", "Aurora", "#061a24", "#0b2532", "#123341", "#e6f8fb", "#a0c6cf", "#2ee6c5", "#03140f", "0"},
            {"sakura", "Sakura", "#fff4f6", "#ffffff", "#fbe3ea", "#2b1820", "#6f5260", "#d63b73", "#ffffff", "1"},
            {"matcha", "Matcha", "#eef2e4", "#f8faf2", "#dde5cb", "#1f2a17", "#55623f", "#4f7a28", "#ffffff", "1"},
            {"arcade", "Arcade", "#0b0f0b", "#111811", "#1a251a", "#d9ffd4", "#8fbf88", "#39ff6a", "#031006", "0"},
            {"cosmic", "Cosmic", "#0a0e24", "#111735", "#1b2349", "#f1f0ff", "#a9add6", "#ffc94d", "#1a1203", "0"},
            {"tropic", "Tropic", "#06211f", "#0b2e2b", "#123f3a", "#effbf6", "#a3cdc3", "#ff6f61", "#210806", "0"},
            {"dark", "Classic dark", "#0f0f11", "#1a1a1e", "#26262b", "#e7e7ea", "#9a9aa3", "#3d7bd6", "#ffffff", "0"},
            {"black", "Classic OLED", "#000000", "#121214", "#1e1e22", "#e7e7ea", "#9a9aa3", "#3d7bd6", "#ffffff", "0"},
            {"dim", "Classic dim", "#1c1c1f", "#26262b", "#303036", "#e7e7ea", "#9a9aa3", "#3d7bd6", "#ffffff", "0"},
            {"light", "Classic light", "#fbfbfc", "#ffffff", "#ececef", "#1e1f24", "#5d5f66", "#2f6fcb", "#ffffff", "1"}};

    public static String themeName(String id) {
        for (String[] t : THEMES) if (t[0].equals(id)) return t[1];
        return id;
    }

    /** Reads the chosen theme ("pc" = the one picked on the PC, kept from the last sync) and sets the colours. */
    public static void load(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("pocket", Context.MODE_PRIVATE);
        String want = sp.getString("theme", "pc");
        String[] t = null;
        if ("pc".equals(want)) {
            java.util.Map<String, Object> l = com.batchmine.pocket.core.Json.obj(com.batchmine.pocket.core.Json.parse(sp.getString("pc_look", "{}")));
            java.util.Map<String, Object> k = com.batchmine.pocket.core.Json.obj(l.get("colors"));
            if (!k.isEmpty()) t = new String[]{"pc", "", s(k, "bg"), s(k, "surface"), s(k, "card"), s(k, "fg"), s(k, "dim"), s(k, "acc"), s(k, "on"), Boolean.TRUE.equals(l.get("light")) ? "1" : "0"};
            else want = "midnight";
        }
        if (t == null) for (String[] x : THEMES) if (x[0].equals(want)) t = x;
        if (t == null) t = THEMES[0];
        try {
            BG = col(t[2]); CARD = col(t[3]); CARD2 = col(t[4]); TEXT = col(t[5]); DIM = col(t[6]); ACCENT = col(t[7]); ON_ACC = col(t[8]); LIGHT = "1".equals(t[9]);
            LINE = mix(TEXT, BG, 0.86f);
        } catch (RuntimeException ignored) { }
    }

    private static String s(java.util.Map<String, Object> k, String n) { return com.batchmine.pocket.core.Json.str(k.get(n), "#000000"); }
    private static int col(String hex) { return Color.parseColor(hex); }

    /** a blended with b (t = share of b) */
    public static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t), Math.round(Color.green(a) * (1 - t) + Color.green(b) * t), Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    /** The colour for CSS (#rrggbb). */
    public static String hex(int c) { return String.format(java.util.Locale.ROOT, "#%06x", c & 0xFFFFFF); }

    /** The look sent by the PC at a sync: kept; true when it changes what the screens look like now (the screens are built again). */
    public static boolean setPcLook(Context c, java.util.Map<String, Object> look) {
        if (look == null || look.isEmpty()) return false;
        android.content.SharedPreferences sp = c.getSharedPreferences("pocket", Context.MODE_PRIVATE);
        String js = com.batchmine.pocket.core.Json.write(look.get("colors") == null ? look : new java.util.LinkedHashMap<String, Object>() {{ put("colors", look.get("colors")); put("light", look.get("light")); put("theme", look.get("theme")); }});
        if (js.equals(sp.getString("pc_look", ""))) return false;
        sp.edit().putString("pc_look", js).apply();
        if (!"pc".equals(sp.getString("theme", "pc"))) return false;
        load(c);
        return true;
    }

    /** The bars of the phone (status and navigation) in the colours of the screen. */
    public static void bars(android.app.Activity a) {
        android.view.Window w = a.getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(CARD);
        View d = w.getDecorView();
        int f = d.getSystemUiVisibility() & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x10);
        if (LIGHT) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (android.os.Build.VERSION.SDK_INT >= 26 ? 0x10 : 0);     // 0x10: light navigation bar
        d.setSystemUiVisibility(f);
    }

    /** The PC's word colours ("cards" mode): background and underline per state. */
    public static int stateBg(String s) {
        switch (s) {
            case "u": return 0x33F5C211;
            case "e": return 0x33FF9F43;
            case "n": return 0x333584E4;
            case "s": return 0x3300B4C8;
            default: return 0;
        }
    }

    public static int stateLine(String s) {
        switch (s) {
            case "u": return 0xFFF5C211;
            case "e": return 0xFFFF9F43;
            case "y": return 0xFFDC8ADD;
            case "n": return 0xFF78AEED;
            case "c": return 0xBF78AEED;
            case "s": return 0xFF00B4C8;
            case "m": return 0xFF33D17A;
            default: return 0;
        }
    }

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static LinearLayout col(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    public static TextView title(Context c, CharSequence s) {
        TextView t = text(c, s, 20, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 8), 0, dp(c, 8));
        return t;
    }

    public static TextView hint(Context c, CharSequence s) {
        TextView t = text(c, s, 13, DIM);
        t.setPadding(0, dp(c, 2), 0, dp(c, 6));
        return t;
    }

    public static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    public static Button button(Context c, CharSequence s, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(15);
        b.setBackground(round(CARD2, dp(c, 12)));
        b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        b.setMinHeight(dp(c, 44));
        b.setOnClickListener(l);
        return b;
    }

    public static Button primary(Context c, CharSequence s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setBackground(round(ACCENT, dp(c, 12)));
        b.setTextColor(ON_ACC);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    /** A tappable row: a title with a line of detail under it. */
    public static LinearLayout item(Context c, CharSequence head, CharSequence sub, View.OnClickListener l) {
        LinearLayout v = col(c);
        v.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        v.setBackground(round(CARD, dp(c, 14)));
        TextView h = text(c, head, 16, TEXT);
        v.addView(h);
        if (sub != null && sub.length() > 0) v.addView(text(c, sub, 13, DIM));
        if (l != null) { v.setOnClickListener(l); v.setClickable(true); }
        return v;
    }

    public static LinearLayout.LayoutParams lp(Context c, int w, int h, int marginBottomDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.bottomMargin = dp(c, marginBottomDp);
        return p;
    }

    public static void add(LinearLayout parent, View v, int gapDp) {
        parent.addView(v, lp(parent.getContext(), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, gapDp));
    }

    public static View weight(View v, float w) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        p.rightMargin = 6;
        v.setLayoutParams(p);
        return v;
    }

    public static ScrollView scroll(Context c, View inner) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.addView(inner);
        return s;
    }

    public static EditText input(Context c, String value, String hint) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(DIM);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setBackground(round(CARD2, dp(c, 10)));
        e.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        return e;
    }

    public static void toast(Context c, CharSequence s) { Toast.makeText(c, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show(); }

    public static void alert(Context c, String title, String msg) {
        dialog(c).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    /** A dialog builder whose show() does nothing (instead of crashing) when the screen it belongs to has closed meanwhile. */
    public static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, LIGHT ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert) {
            @Override public AlertDialog show() {
                AlertDialog d = create();
                if (gone(c)) return d;
                try { d.show(); } catch (RuntimeException ignored) { }       // the screen closed between the check and now
                return d;
            }
        };
    }

    /** Is this screen closed (or closing)? */
    public static boolean gone(Context c) {
        return c instanceof android.app.Activity && (((android.app.Activity) c).isFinishing() || ((android.app.Activity) c).isDestroyed());
    }

    /** Closes a dialog, even if its screen has already gone. */
    public static void dismiss(android.app.Dialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (RuntimeException ignored) { }
    }

    public static String size(long b) {
        if (b >= 1L << 30) return String.format(java.util.Locale.ROOT, "%.1f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(java.util.Locale.ROOT, "%.0f MB", b / (double) (1L << 20));
        return Math.max(1, b / 1024) + " KB";
    }

    public static String time(double s) {
        int t = (int) Math.max(0, s);
        return t >= 3600 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
                : String.format(java.util.Locale.ROOT, "%d:%02d", t / 60, t % 60);
    }
}
