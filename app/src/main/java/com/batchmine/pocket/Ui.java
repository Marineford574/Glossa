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

    /** The accent colours that can be put over any theme (the same as on the PC); "" = the theme's own. */
    public static final String[][] ACCENTS = {{"Theme colour", ""}, {"Blue", "#4f8ff7"}, {"Violet", "#9b7bff"}, {"Pink", "#f0509a"}, {"Coral", "#ff6b5a"}, {"Amber", "#f2a516"}, {"Green", "#2fbf71"}, {"Teal", "#14b8c4"}};

    public static String accentName(String hex) {
        for (String[] a : ACCENTS) if (a[1].equalsIgnoreCase(hex == null ? "" : hex)) return a[0];
        return hex;
    }

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
            String a = sp.getString("accent", "");
            if (a.matches("#[0-9a-fA-F]{6}")) {                     // an accent of the phone's own over the theme
                ACCENT = col(a);
                double lum = (0.299 * Color.red(ACCENT) + 0.587 * Color.green(ACCENT) + 0.114 * Color.blue(ACCENT)) / 255;
                ON_ACC = lum > 0.6 ? 0xFF111111 : 0xFFFFFFFF;
            }
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

    /** A drawable icon tinted with one colour (the bottom bar and the gear use the PC's side-menu drawings). */
    public static android.widget.ImageView icon(Context c, int res, int sizeDp, int color) {
        android.widget.ImageView v = new android.widget.ImageView(c);
        v.setImageResource(res);
        v.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        v.setColorFilter(color);
        v.setAdjustViewBounds(false);
        v.setMinimumWidth(dp(c, sizeDp)); v.setMinimumHeight(dp(c, sizeDp));
        return v;
    }

    public static TextView title(Context c, CharSequence s) {
        TextView t = text(c, s, 19, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(c, 14), 0, dp(c, 8));
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

    // ---------------------------------------------------------------- the PC's shapes (mine_gui.css: --radius 12px, --radius-s 8px)
    public static final int R = 12, RS = 8;

    /** A shape with a pressed state: COLOR, a little darker / lighter while the finger is on it (like :hover on the PC). */
    public static android.graphics.drawable.StateListDrawable press(int color, int border, float radius) {
        android.graphics.drawable.StateListDrawable st = new android.graphics.drawable.StateListDrawable();
        GradientDrawable on = round(mix(color, TEXT, 0.10f), radius), off = round(color, radius);
        if (border != 0) { on.setStroke(1, border); off.setStroke(1, border); }
        st.addState(new int[]{android.R.attr.state_pressed}, on);
        st.addState(new int[]{}, off);
        return st;
    }

    /** A button like the PC's .btn: a soft fill of the text colour, 8 dp corners, medium weight. */
    public static Button button(Context c, CharSequence s, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(press(mix(BG, TEXT, 0.08f), 0, dp(c, RS)));
        b.setStateListAnimator(null);
        b.setPadding(dp(c, 14), dp(c, 4), dp(c, 14), dp(c, 4));
        b.setMinHeight(dp(c, 42));
        b.setMinimumHeight(dp(c, 42));
        b.setOnClickListener(l);
        return b;
    }

    /** The PC's .btn.suggested: the accent colour, bold. */
    public static Button primary(Context c, CharSequence s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setBackground(press(ACCENT, 0, dp(c, RS)));
        b.setTextColor(ON_ACC);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    /** The PC's .btn.flat: no fill until pressed. */
    public static Button flat(Context c, CharSequence s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setBackground(press(0x00000000, 0, dp(c, RS)));
        return b;
    }

    /** A small button (.btn.small). */
    public static Button small(Context c, CharSequence s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setTextSize(13);
        b.setMinHeight(dp(c, 34)); b.setMinimumHeight(dp(c, 34));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(c, 10), 0, dp(c, 10), 0);
        return b;
    }

    /** A card like the PC's .card / .lv: the card colour, a 1 px border, 12 dp corners. */
    public static GradientDrawable cardBg(Context c) {
        GradientDrawable g = round(CARD, dp(c, R));
        g.setStroke(Math.max(1, dp(c, 1) / 2 + 1), LINE);
        return g;
    }

    public static LinearLayout card(Context c) {
        LinearLayout v = col(c);
        v.setBackground(cardBg(c));
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return v;
    }

    /** A tappable row: a title with a line of detail under it. */
    public static LinearLayout item(Context c, CharSequence head, CharSequence sub, View.OnClickListener l) {
        LinearLayout v = col(c);
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        if (l != null) { android.graphics.drawable.StateListDrawable st = press(CARD, LINE, dp(c, R)); v.setBackground(st); }
        else v.setBackground(cardBg(c));
        TextView h = text(c, head, 16, TEXT);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.addView(h);
        if (sub != null && sub.length() > 0) { TextView t = text(c, sub, 13, DIM); t.setPadding(0, dp(c, 2), 0, 0); v.addView(t); }
        if (l != null) { v.setOnClickListener(l); v.setClickable(true); }
        return v;
    }

    /** A small label on a rounded fill (.pill). */
    public static TextView pill(Context c, CharSequence s, int fg, int bg) {
        TextView t = text(c, s, 12, fg);
        t.setBackground(round(bg, dp(c, 99)));
        t.setPadding(dp(c, 8), dp(c, 1), dp(c, 8), dp(c, 2));
        return t;
    }

    /** A section heading above a group of settings rows: small, bold, dim (the PC's settings group names). */
    public static TextView section(Context c, CharSequence s) {
        TextView t = text(c, s.toString().toUpperCase(java.util.Locale.ROOT), 12, DIM);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(c, 4), dp(c, 18), 0, dp(c, 6));
        t.setTag("section");                                    // MainActivity.settings() groups the page by these
        return t;
    }

    /** A group of settings rows in one card, with lines between them (.srow inside a card on the PC). */
    public static LinearLayout group(Context c) {
        LinearLayout v = col(c);
        v.setBackground(cardBg(c));
        v.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        return v;
    }

    /** One settings row: a bold name and a dim line under it on the left, the control on the right; a tap on the row runs TAP. */
    public static LinearLayout srow(LinearLayout group, CharSequence name, CharSequence hint, View control, View.OnClickListener tap) {
        Context c = group.getContext();
        if (group.getChildCount() > 0) {
            View line = new View(c);
            line.setBackgroundColor(LINE);
            group.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 11), 0, dp(c, 11));
        r.setMinimumHeight(dp(c, 52));
        LinearLayout tx = col(c);
        TextView n = text(c, name, 15, TEXT);
        n.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tx.addView(n);
        if (hint != null && hint.length() > 0) { TextView h = text(c, hint, 12.5f, DIM); h.setPadding(0, dp(c, 2), 0, 0); tx.addView(h); }
        r.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (control != null) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.leftMargin = dp(c, 12);
            r.addView(control, p);
        }
        if (tap != null) { r.setOnClickListener(tap); r.setBackground(press(0x00000000, 0, dp(c, RS))); }
        group.addView(r);
        return r;
    }

    /** A settings row whose control is a value and a chevron (opens a choice). */
    public static LinearLayout pickRow(LinearLayout group, CharSequence name, CharSequence value, View.OnClickListener tap) {
        TextView v = text(group.getContext(), value + "  ›", 14, DIM);
        v.setMaxWidth(dp(group.getContext(), 170));
        v.setGravity(Gravity.END);
        return srow(group, name, null, v, tap);
    }

    /** A switch like the PC's .switch (accent track when on); the row toggles it too. */
    public static android.widget.Switch toggle(Context c, boolean on, android.widget.CompoundButton.OnCheckedChangeListener l) {
        android.widget.Switch s = new android.widget.Switch(c);
        s.setChecked(on);
        int[][] st = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        s.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{ACCENT, mix(BG, TEXT, 0.35f)}));
        s.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC);
        s.setOnCheckedChangeListener(l);
        return s;
    }

    public static LinearLayout switchRow(LinearLayout group, CharSequence name, CharSequence hint, boolean on, android.widget.CompoundButton.OnCheckedChangeListener l) {
        android.widget.Switch sw = toggle(group.getContext(), on, l);
        return srow(group, name, hint, sw, v -> sw.toggle());
    }

    /** A row of buttons that share the width (gap 8 dp). */
    public static LinearLayout buttons(Context c, View... bs) {
        LinearLayout r = row(c);
        for (int i = 0; i < bs.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) p.leftMargin = dp(c, 8);
            r.addView(bs[i], p);
        }
        return r;
    }

    /** A thin bar showing how far along something is (0..1). */
    public static View progress(Context c, float f) {
        android.widget.FrameLayout fr = new android.widget.FrameLayout(c);
        fr.setBackground(round(mix(CARD, TEXT, 0.12f), dp(c, 2)));
        fr.setClipToOutline(true);
        View in = new View(c);
        in.setBackground(round(ACCENT, dp(c, 2)));
        fr.addView(in, new android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        fr.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.round((r - l) * Math.max(0, Math.min(1, f)));
            if (in.getLayoutParams().width != w) { in.getLayoutParams().width = w; in.post(in::requestLayout); }
        });
        fr.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 4)));
        return fr;
    }

    /** "5 min ago", "3 h ago", "yesterday", "4 days ago". */
    public static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 90) return "just now";
        if (s < 3600) return (s / 60) + " min ago";
        if (s < 86400) return (s / 3600) + " h ago";
        if (s < 2 * 86400) return "yesterday";
        return (s / 86400) + " days ago";
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
        p.rightMargin = dp(v.getContext(), 8);
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
        GradientDrawable g = round(mix(BG, TEXT, 0.05f), dp(c, RS));
        g.setStroke(1, mix(BG, TEXT, 0.18f));
        e.setBackground(g);
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
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
                try { d.show(); } catch (RuntimeException ignored) { return d; }       // the screen closed between the check and now
                try { style(d, c); } catch (RuntimeException ignored) { /* the grey one is fine too */ }
                return d;
            }
        };
    }

    /** An alert dialog in the app's colours: its card, title, text and buttons (the phone's own grey is only the fallback). */
    private static void style(AlertDialog d, Context c) {
        if (d.getWindow() != null) {
            android.graphics.drawable.GradientDrawable bg = round(BG, dp(c, 18));
            bg.setStroke(1, LINE);
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.InsetDrawable(bg, dp(c, 6)));
        }
        int tid = c.getResources().getIdentifier("alertTitle", "id", "android");
        View t = tid == 0 ? null : d.findViewById(tid);
        if (t instanceof TextView) { ((TextView) t).setTextColor(TEXT); ((TextView) t).setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); }
        View m = d.findViewById(android.R.id.message);
        if (m instanceof TextView) ((TextView) m).setTextColor(mix(TEXT, BG, 0.15f));
        int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int k = 0; k < ids.length; k++) {
            Button b = d.getButton(ids[k]);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setTextColor(k == 0 ? ACCENT : TEXT);
            b.setBackground(press(0x00000000, 0, dp(c, 99)));
        }
        android.widget.ListView lv = d.getListView();
        if (lv != null) lv.setDivider(new android.graphics.drawable.ColorDrawable(LINE));
        if (lv != null) lv.setDividerHeight(1);
    }

    // ---------------------------------------------------------------- sheets in the app's own colours (not the phone's grey dialogs)
    /** A panel that slides up from the bottom in the app's colours, with a title row (and ✕); FULL = the whole height. */
    public static android.app.Dialog sheet(Context c, CharSequence title, View content, boolean full) {
        android.app.Dialog d = new android.app.Dialog(c, android.R.style.Theme_DeviceDefault_NoActionBar);
        LinearLayout box = col(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        float r = dp(c, 18);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        LinearLayout head = row(c);
        head.setPadding(dp(c, 18), dp(c, 14), dp(c, 6), dp(c, 6));
        TextView t = text(c, title, 18, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView x = text(c, "✕", 18, DIM);
        x.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        x.setOnClickListener(v -> dismiss(d));
        head.addView(x);
        box.addView(head);
        box.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, full ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, full ? 1f : 0f));
        d.setContentView(box);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, full ? (int) (c.getResources().getDisplayMetrics().heightPixels * 0.92f) : ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.5f);
        }
        d.setCanceledOnTouchOutside(true);
        if (!gone(c)) try { d.show(); } catch (RuntimeException ignored) { }      // shown at once (callers used to forget it)
        return d;
    }

    public interface Chosen { void pick(int i); }

    /** A list to choose one from, in the app's colours: the current one ticked. */
    public static void choose(Context c, CharSequence title, String[] items, int current, Chosen then) {
        LinearLayout l = col(c);
        l.setPadding(dp(c, 10), 0, dp(c, 10), dp(c, 14));
        android.app.Dialog[] d = new android.app.Dialog[1];
        for (int i = 0; i < items.length; i++) {
            final int k = i;
            LinearLayout r = row(c);
            r.setPadding(dp(c, 12), dp(c, 13), dp(c, 12), dp(c, 13));
            TextView t = text(c, items[i], 16, i == current ? ACCENT : TEXT);
            if (i == current) t.setTypeface(Typeface.DEFAULT_BOLD);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i == current) r.addView(text(c, "✓", 16, ACCENT));
            r.setBackground(press(0x00000000, 0, dp(c, RS)));
            r.setOnClickListener(v -> { dismiss(d[0]); then.pick(k); });
            l.addView(r);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(c);
        sv.addView(l);
        d[0] = sheet(c, title, sv, items.length > 9);
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
