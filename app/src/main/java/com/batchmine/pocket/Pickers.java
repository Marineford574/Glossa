package com.batchmine.pocket;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.batchmine.pocket.core.Json;

import java.util.Map;

/** Theme, accent colour and app icon chosen by looking at them: a small picture of each, the one in use marked. */
final class Pickers {
    private Pickers() { }

    /** A little screen in a theme's colours: background, a card with a title line and a text line, an accent button. */
    private static final class Mini extends View {
        final int bg, surf, card, text, dim, acc, onAcc;
        final boolean on;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Mini(Context c, int bg, int surf, int card, int text, int dim, int acc, int onAcc, boolean on) {
            super(c);
            this.bg = bg; this.surf = surf; this.card = card; this.text = text; this.dim = dim; this.acc = acc; this.onAcc = onAcc; this.on = on;
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Ui.dp(getContext(), 10), u = Ui.dp(getContext(), 1);
            p.setStyle(Paint.Style.FILL);
            p.setColor(bg);
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            p.setColor(surf);
            cv.drawRoundRect(new RectF(w * .08f, h * .14f, w * .92f, h * .58f), r * .6f, r * .6f, p);
            p.setColor(text);
            cv.drawRoundRect(new RectF(w * .15f, h * .22f, w * .55f, h * .22f + 4 * u), 2 * u, 2 * u, p);
            p.setColor(dim);
            cv.drawRoundRect(new RectF(w * .15f, h * .36f, w * .78f, h * .36f + 3 * u), 2 * u, 2 * u, p);
            cv.drawRoundRect(new RectF(w * .15f, h * .45f, w * .66f, h * .45f + 3 * u), 2 * u, 2 * u, p);
            p.setColor(card);
            cv.drawRoundRect(new RectF(w * .08f, h * .66f, w * .5f, h * .86f), r * .5f, r * .5f, p);
            p.setColor(acc);
            cv.drawRoundRect(new RectF(w * .58f, h * .66f, w * .92f, h * .86f), r * .5f, r * .5f, p);
            p.setColor(onAcc);
            cv.drawRoundRect(new RectF(w * .66f, h * .74f, w * .84f, h * .74f + 3 * u), 2 * u, 2 * u, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(on ? 3 * u : u);
            p.setColor(on ? Ui.ACCENT : Ui.mix(Ui.BG, Ui.TEXT, 0.25f));
            cv.drawRoundRect(new RectF(u, u, w - u, h - u), r, r, p);
        }
    }

    private static int col(String hex, int fallback) { try { return Color.parseColor(hex); } catch (RuntimeException e) { return fallback; } }

    private static View cell(Context c, View picture, String name, int widthDp, boolean on, View.OnClickListener l) { return cell(c, picture, name, widthDp, widthDp * 2 / 3, on, l); }

    private static View cell(Context c, View picture, String name, int widthDp, int heightDp, boolean on, View.OnClickListener l) {
        LinearLayout v = Ui.col(c);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.addView(picture, new LinearLayout.LayoutParams(Ui.dp(c, widthDp), Ui.dp(c, heightDp)));
        TextView t = Ui.text(c, name, 12, on ? Ui.ACCENT : Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 2));
        if (on) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        v.setOnClickListener(l);
        v.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        return v;
    }

    /** All the themes as small screens; the first is "Same as the PC" (in the PC's colours from the last sync). */
    static void theme(Activity a, Runnable changed) {
        android.content.SharedPreferences sp = a.getSharedPreferences("pocket", Context.MODE_PRIVATE);
        String cur = sp.getString("theme", "pc");
        Flow f = new Flow(a, 6);
        f.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 16));
        android.app.Dialog[] d = new android.app.Dialog[1];
        Map<String, Object> look = Json.obj(Json.parse(sp.getString("pc_look", "{}")));
        Map<String, Object> k = Json.obj(look.get("colors"));
        if (!k.isEmpty()) {
            Mini m = new Mini(a, col(Json.str(k.get("bg"), "#000000"), 0), col(Json.str(k.get("surface"), "#111111"), 0), col(Json.str(k.get("card"), "#222222"), 0), col(Json.str(k.get("fg"), "#ffffff"), 0),
                    col(Json.str(k.get("dim"), "#999999"), 0), col(Json.str(k.get("acc"), "#5b9cf0"), 0), col(Json.str(k.get("on"), "#000000"), 0), "pc".equals(cur));
            f.addView(cell(a, m, "Same as the PC", 104, "pc".equals(cur), v -> { sp.edit().putString("theme", "pc").apply(); Ui.dismiss(d[0]); Ui.load(a); changed.run(); }));
        } else {
            TextView n = Ui.text(a, "Same as the PC\n(after the first sync)", 12, Ui.DIM);
            n.setGravity(Gravity.CENTER);
            n.setBackground(Ui.round(Ui.CARD, Ui.dp(a, 10)));
            f.addView(cell(a, n, "Same as the PC", 104, "pc".equals(cur), v -> { sp.edit().putString("theme", "pc").apply(); Ui.dismiss(d[0]); Ui.load(a); changed.run(); }));
        }
        for (String[] t : Ui.THEMES) {
            boolean on = t[0].equals(cur);
            Mini m = new Mini(a, col(t[2], 0), col(t[3], 0), col(t[4], 0), col(t[5], 0), col(t[6], 0), col(t[7], 0), col(t[8], 0), on);
            f.addView(cell(a, m, t[1], 104, on, v -> { sp.edit().putString("theme", t[0]).apply(); Ui.dismiss(d[0]); Ui.load(a); changed.run(); }));
        }
        d[0] = Ui.sheet(a, "Theme", Ui.scroll(a, f), true);
    }

    /** The accent colours as round swatches over the colours of the current theme. */
    static void accent(Activity a, Runnable changed) {
        android.content.SharedPreferences sp = a.getSharedPreferences("pocket", Context.MODE_PRIVATE);
        String cur = sp.getString("accent", "");
        Flow f = new Flow(a, 8);
        f.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 20));
        android.app.Dialog[] d = new android.app.Dialog[1];
        for (String[] ac : Ui.ACCENTS) {
            boolean on = ac[1].equalsIgnoreCase(cur);
            LinearLayout v = Ui.col(a);
            v.setGravity(Gravity.CENTER_HORIZONTAL);
            int colr = ac[1].isEmpty() ? Ui.ACCENT : col(ac[1], Ui.ACCENT);
            TextView dot = Ui.text(a, ac[1].isEmpty() ? "A" : "", 16, Ui.ON_ACC);
            dot.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = Ui.round(ac[1].isEmpty() ? sp.getString("accent", "").isEmpty() ? Ui.ACCENT : themeAccent(sp) : colr, Ui.dp(a, 99));
            if (on) g.setStroke(Ui.dp(a, 3), Ui.TEXT);
            dot.setBackground(g);
            v.addView(dot, new LinearLayout.LayoutParams(Ui.dp(a, 52), Ui.dp(a, 52)));
            TextView t = Ui.text(a, ac[0], 12, on ? Ui.ACCENT : Ui.TEXT);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(a, 4), 0, 0);
            v.addView(t, new LinearLayout.LayoutParams(Ui.dp(a, 70), ViewGroup.LayoutParams.WRAP_CONTENT));
            v.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
            v.setOnClickListener(x -> { sp.edit().putString("accent", ac[1]).apply(); Ui.dismiss(d[0]); Ui.load(a); changed.run(); });
            f.addView(v);
        }
        d[0] = Ui.sheet(a, "Accent colour", f, false);
    }

    /** The accent the current theme itself has (not the phone's own over it). */
    private static int themeAccent(android.content.SharedPreferences sp) {
        String t = sp.getString("theme", "pc");
        for (String[] x : Ui.THEMES) if (x[0].equals(t)) return col(x[7], Ui.ACCENT);
        Map<String, Object> k = Json.obj(Json.obj(Json.parse(sp.getString("pc_look", "{}"))).get("colors"));
        return col(Json.str(k.get("acc"), "#5b9cf0"), Ui.ACCENT);
    }

    /** The app icons as pictures: tap one to switch the launcher to it (the first, "Same as the PC", follows the PC's choice). */
    static void icon(Activity a, Runnable changed) {
        android.content.SharedPreferences sp = a.getSharedPreferences("pocket", Context.MODE_PRIVATE);
        String cur = sp.getString("icon", "original");
        Flow f = new Flow(a, 6);
        f.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), Ui.dp(a, 16));
        android.app.Dialog[] d = new android.app.Dialog[1];
        // "same as the PC": shown with the icon the PC has now (as far as the last sync said)
        String pcIcon = Json.str(Json.obj(Json.parse(sp.getString("pc_look", "{}"))).get("theme"), "");
        f.addView(cell(a, iconView(a, "pc".equals(cur) ? Icons.current(a) : "original", "pc".equals(cur)), "Same as the PC", 76, 76, "pc".equals(cur), v -> {
            sp.edit().putString("icon", "pc").apply();
            Ui.toast(a, "The icon follows the PC from the next sync");
            Ui.dismiss(d[0]); changed.run();
        }));
        for (String[] ic : Icons.ALL) {
            boolean on = ic[0].equals(cur);
            f.addView(cell(a, iconView(a, ic[0], on), ic[1], 76, 76, on, v -> {
                sp.edit().putString("icon", ic[0]).apply();
                Icons.apply(a, ic[0]);
                Ui.toast(a, "The launcher shows the new icon in a moment. A shortcut on the home screen may need adding again.");
                Ui.dismiss(d[0]); changed.run();
            }));
        }
        d[0] = Ui.sheet(a, "App icon", Ui.scroll(a, f), true);
    }

    private static View iconView(Context c, String id, boolean on) {
        android.widget.ImageView iv = new android.widget.ImageView(c);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.Drawable dr = null;
        try {
            int res = "original".equals(id) ? c.getResources().getIdentifier("ic_launcher", "mipmap", c.getPackageName()) : c.getResources().getIdentifier("ic_g_" + id, "drawable", c.getPackageName());
            if (res != 0) dr = c.getResources().getDrawable(res, c.getTheme());
        } catch (RuntimeException ignored) { /* no picture: the tile stays plain */ }
        if (dr != null) iv.setImageDrawable(dr);
        iv.setBackground(Ui.round(Ui.CARD, Ui.dp(c, 16)));
        iv.setClipToOutline(true);
        iv.setPadding(on ? Ui.dp(c, 3) : 0, on ? Ui.dp(c, 3) : 0, on ? Ui.dp(c, 3) : 0, on ? Ui.dp(c, 3) : 0);
        if (on) { android.graphics.drawable.GradientDrawable g = Ui.round(Ui.CARD, Ui.dp(c, 16)); g.setStroke(Ui.dp(c, 3), Ui.ACCENT); iv.setBackground(g); }
        return new SquareHolder(c, iv);
    }

    /** Keeps the icon square whatever width the cell gives it. */
    private static final class SquareHolder extends android.widget.FrameLayout {
        SquareHolder(Context c, View v) { super(c); addView(v, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)); }
    }
}
