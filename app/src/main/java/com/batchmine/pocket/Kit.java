package com.batchmine.pocket;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** The redesign's building blocks (the "Glossa Pocket redesign" canvas): page headers, filter chips, the big Continue card, number tiles,
 *  action cards, grids of covers, rows with a picture, and the small fact chips of a word. Every screen builds from these, so a later change to
 *  the look is made here once; the colours always come from the theme (Ui.BG, Ui.CARD, Ui.ACCENT ...), so all 20 themes keep working. */
final class Kit {
    private Kit() { }

    static final int GAP = 16;            // between the blocks of a page (dp)
    static final int RADIUS = 16;         // the cards of the redesign are a little rounder than the PC's 12

    static int dp(Context c, float v) { return Ui.dp(c, v); }

    static int soft() { return Ui.mix(Ui.BG, Ui.TEXT, 0.08f); }            // the fill of quiet buttons and unselected chips

    // ---------------------------------------------------------------- page structure
    /** A page's own heading: a big bold title (an optional small line above it, like "Good evening") and icon buttons at the right. */
    static LinearLayout header(Context c, CharSequence kicker, CharSequence title, View... actions) {
        LinearLayout r = Ui.row(c);
        r.setPadding(0, dp(c, 6), 0, dp(c, 2));
        LinearLayout t = Ui.col(c);
        if (kicker != null && kicker.length() > 0) t.addView(Ui.text(c, kicker, 13, Ui.DIM));
        TextView h = Ui.text(c, title, 24, Ui.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        t.addView(h);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        for (View a : actions) {
            if (a == null) continue;
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.leftMargin = dp(c, 8);
            r.addView(a, p);
        }
        return r;
    }

    /** A 44 dp square button with one icon (accent fill when it is the page's main action). */
    static View iconButton(Context c, int res, String label, boolean primary, View.OnClickListener l) {
        android.widget.ImageView v = Ui.icon(c, res, 20, primary ? Ui.ON_ACC : Ui.DIM);
        int p = dp(c, 12);
        v.setPadding(p, p, p, p);
        v.setBackground(Ui.press(primary ? Ui.ACCENT : soft(), 0, dp(c, 12)));
        v.setContentDescription(label);
        v.setOnClickListener(l);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        return v;
    }

    /** A 44 dp button with a short text ("+", "Aa") where there is no drawing for it. */
    static TextView textButton(Context c, String s, String label, boolean primary, View.OnClickListener l) {
        TextView t = Ui.text(c, s, s.length() <= 2 ? 20 : 14, primary ? Ui.ON_ACC : Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(c, 44));
        t.setMinHeight(dp(c, 44));
        t.setPadding(dp(c, s.length() <= 2 ? 0 : 14), 0, dp(c, s.length() <= 2 ? 0 : 14), 0);
        t.setBackground(Ui.press(primary ? Ui.ACCENT : soft(), 0, dp(c, 12)));
        t.setContentDescription(label);
        t.setOnClickListener(l);
        return t;
    }

    /** A heading over a block, with an optional link at the right ("All 12"). */
    static LinearLayout sectionHead(Context c, CharSequence title, CharSequence action, View.OnClickListener l) {
        LinearLayout r = Ui.row(c);
        TextView t = Ui.text(c, title, 17, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null && l != null) {
            TextView a = Ui.text(c, action, 14, Ui.mix(Ui.ACCENT, Ui.TEXT, Ui.LIGHT ? 0f : 0.25f));
            a.setPadding(dp(c, 12), dp(c, 10), 0, dp(c, 10));
            a.setOnClickListener(l);
            r.addView(a);
        }
        return r;
    }

    /** Adds a block to a page with the page's gap under it. */
    static void add(LinearLayout page, View v) { Ui.add(page, v, GAP); }

    static void add(LinearLayout page, View v, int gapDp) { Ui.add(page, v, gapDp); }

    /** A card of the redesign: the card colour, 16 dp corners, no border (pressed: a little lighter). */
    static LinearLayout card(Context c, View.OnClickListener l) {
        LinearLayout v = Ui.col(c);
        v.setBackground(l != null ? Ui.press(Ui.CARD, 0, dp(c, RADIUS)) : Ui.round(Ui.CARD, dp(c, RADIUS)));
        v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 14));
        if (l != null) v.setOnClickListener(l);
        return v;
    }

    // ---------------------------------------------------------------- chips
    public interface Picked { void at(int i); }

    /** One choice of a row of chips: the chosen one is filled with the text colour (as on the canvas), the others soft. */
    static TextView chip(Context c, CharSequence s, boolean on, View.OnClickListener l) {
        TextView t = Ui.text(c, s, 14, on ? Ui.BG : Ui.TEXT);
        if (on) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(c, 40));
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        t.setBackground(Ui.press(on ? Ui.TEXT : soft(), 0, dp(c, 99)));
        t.setSelected(on);
        t.setOnClickListener(l);
        return t;
    }

    /** A wrapping row of chips; `then` is told which one was tapped. */
    static Flow chips(Context c, String[] labels, int chosen, Picked then) {
        Flow f = new Flow(c, 8);
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == null) continue;
            final int at = i;
            f.addView(chip(c, labels[i], i == chosen, v -> then.at(at)));
        }
        return f;
    }

    /** A small label chip (a word's level, gender, frequency …). kind "new" is the highlighted one. */
    static TextView fact(Context c, CharSequence s, String kind) {
        boolean hot = "new".equals(kind);
        TextView t = Ui.text(c, s, 12, hot ? Ui.mix(0xFFF5C211, Ui.TEXT, Ui.LIGHT ? 0.55f : 0.1f) : Ui.TEXT);
        if (hot) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setBackground(Ui.round(hot ? Ui.mix(Ui.CARD, 0xFFF5C211, 0.22f) : Ui.mix(Ui.CARD, Ui.TEXT, 0.08f), dp(c, 10)));
        t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        return t;
    }

    /** The fact chips of a word from all the registered sources (Extras.facts): an empty row when there are none. */
    static Flow facts(Context c, String lang, String word) {
        Flow f = new Flow(c, 6);
        for (Extras.Fact x : Extras.facts(lang, word)) f.addView(fact(c, x.text, x.kind));
        return f;
    }

    // ---------------------------------------------------------------- the big Continue card, number tiles, action cards
    /** The big card at the top of Home and Watch: a wide picture with a label on it and a round play button, then title, detail and progress. */
    static LinearLayout heroWide(Context c, View picture, String label, String title, String sub, float progress, View.OnClickListener l) {
        LinearLayout card = Ui.col(c);
        card.setBackground(Ui.press(Ui.CARD, 0, dp(c, RADIUS)));
        card.setClipToOutline(true);
        FrameLayout top = new FrameLayout(c);
        top.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (label != null) {
            TextView lb = Ui.text(c, label, 12, 0xFFF2F3F5);
            lb.setTypeface(Typeface.DEFAULT_BOLD);
            lb.setBackground(Ui.round(0xD00F1014, dp(c, 8)));
            lb.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
            p.leftMargin = dp(c, 12); p.topMargin = dp(c, 12);
            top.addView(lb, p);
        }
        TextView play = Ui.text(c, "▶", 20, Ui.ON_ACC);
        play.setGravity(Gravity.CENTER);
        play.setBackground(Ui.round(Ui.ACCENT, dp(c, 28)));
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        top.addView(play, new FrameLayout.LayoutParams(dp(c, 56), dp(c, 56), Gravity.CENTER));
        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 160)));
        LinearLayout txt = Ui.col(c);
        txt.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 14));
        TextView t = Ui.text(c, title, 16, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(t);
        if (sub != null && !sub.isEmpty()) { TextView s = Ui.text(c, sub, 13, Ui.DIM); s.setPadding(0, dp(c, 3), 0, 0); txt.addView(s); }
        if (progress > 0.003f) { View pr = Ui.progress(c, progress); ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = dp(c, 10); txt.addView(pr); }
        card.addView(txt);
        card.setOnClickListener(l);
        card.setContentDescription(title + (label == null ? "" : ", " + label));
        return card;
    }

    /** The big card of the Read page: the cover at the left, a coloured label, title, detail lines and the progress at the bottom. */
    static LinearLayout heroSide(Context c, View cover, String label, String title, String sub, String foot, float progress, View.OnClickListener l) {
        LinearLayout card = Ui.row(c);
        card.setGravity(Gravity.TOP);
        card.setBackground(Ui.press(Ui.CARD, 0, dp(c, RADIUS + 2)));
        card.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
        card.addView(cover);
        LinearLayout txt = Ui.col(c);
        txt.setPadding(dp(c, 14), 0, 0, 0);
        TextView lb = Ui.text(c, label, 12, Ui.mix(Ui.ACCENT, Ui.TEXT, Ui.LIGHT ? 0f : 0.25f));
        lb.setTypeface(Typeface.DEFAULT_BOLD);
        txt.addView(lb);
        TextView t = Ui.text(c, title, 17, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(3);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(0, dp(c, 4), 0, 0);
        txt.addView(t);
        if (sub != null && !sub.isEmpty()) { TextView s = Ui.text(c, sub, 13, Ui.DIM); s.setPadding(0, dp(c, 4), 0, 0); txt.addView(s); }
        View pr = Ui.progress(c, progress);
        ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = dp(c, 12);
        txt.addView(pr);
        if (foot != null && !foot.isEmpty()) { TextView f = Ui.text(c, foot, 12, Ui.DIM); f.setPadding(0, dp(c, 6), 0, 0); txt.addView(f); }
        card.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.setOnClickListener(l);
        return card;
    }

    /** A number tile: the big number, a small label under it (accent number when it asks for something). */
    static LinearLayout stat(Context c, String number, String label, boolean hot, View.OnClickListener l) {
        LinearLayout t = Ui.col(c);
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        t.setBackground(l != null ? Ui.press(Ui.CARD, 0, dp(c, 14)) : Ui.round(Ui.CARD, dp(c, 14)));
        TextView n = Ui.text(c, number, 22, hot ? Ui.ACCENT : Ui.TEXT);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        t.addView(n);
        TextView s = Ui.text(c, label, 12, Ui.DIM);
        s.setPadding(0, dp(c, 2), 0, 0);
        t.addView(s);
        if (l != null) t.setOnClickListener(l);
        t.setContentDescription(number + " " + label);
        return t;
    }

    /** Tiles side by side with an even gap. */
    static LinearLayout tiles(Context c, View... ts) {
        LinearLayout r = Ui.row(c);
        r.setGravity(Gravity.TOP);
        for (int i = 0; i < ts.length; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) p.leftMargin = dp(c, 10);
            r.addView(ts[i], p);
        }
        return r;
    }

    /** A card with a title, a line under it and one button at the right ("3 cards in the tray · Review"). */
    static LinearLayout actionCard(Context c, String title, String sub, String button, View.OnClickListener l) {
        LinearLayout card = Ui.row(c);
        card.setBackground(Ui.round(Ui.CARD, dp(c, 14)));
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 12), dp(c, 12));
        LinearLayout txt = Ui.col(c);
        TextView t = Ui.text(c, title, 15, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        txt.addView(t);
        if (sub != null && !sub.isEmpty()) { TextView s = Ui.text(c, sub, 13, Ui.DIM); s.setPadding(0, dp(c, 2), 0, 0); txt.addView(s); }
        card.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (button != null) {
            android.widget.Button b = Ui.primary(c, button, l);
            b.setMinHeight(dp(c, 44)); b.setMinimumHeight(dp(c, 44));
            b.setBackground(Ui.press(Ui.ACCENT, 0, dp(c, 12)));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.leftMargin = dp(c, 12);
            card.addView(b, p);
        } else if (l != null) {
            card.setBackground(Ui.press(Ui.CARD, 0, dp(c, 14)));
            card.setOnClickListener(l);
        }
        return card;
    }

    // ---------------------------------------------------------------- lists and grids
    /** A row: a picture at the left, a title and one line, an optional view at the right; the whole row is the button. */
    static LinearLayout pictureRow(Context c, View picture, String title, String sub, View end, View.OnClickListener l, View.OnLongClickListener hold) {
        LinearLayout r = Ui.row(c);
        r.setBackground(Ui.press(Ui.CARD, 0, dp(c, 14)));
        r.setPadding(dp(c, 10), dp(c, 10), dp(c, 12), dp(c, 10));
        if (picture != null) r.addView(picture);
        LinearLayout txt = Ui.col(c);
        txt.setPadding(dp(c, picture == null ? 4 : 12), 0, 0, 0);
        TextView t = Ui.text(c, title, 15, Ui.TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(t);
        if (sub != null && !sub.isEmpty()) { TextView s = Ui.text(c, sub, 13, Ui.DIM); s.setPadding(0, dp(c, 2), 0, 0); s.setMaxLines(2); txt.addView(s); }
        r.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (end != null) r.addView(end);
        if (l != null) r.setOnClickListener(l);
        if (hold != null) r.setOnLongClickListener(hold);
        return r;
    }

    /** Views laid out in COLS equal columns (the series and book grids). */
    static LinearLayout grid(Context c, List<View> items, int cols, int gapDp) {
        LinearLayout g = Ui.col(c);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % cols == 0) {
                row = Ui.row(c);
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = dp(c, gapDp);
                g.addView(row, rp);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % cols > 0) p.leftMargin = dp(c, gapDp);
            row.addView(items.get(i), p);
        }
        if (row != null) for (int k = items.size() % cols; k > 0 && k < cols; k++) {      // empty cells keep the last row's widths even
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.leftMargin = dp(c, gapDp);
            row.addView(new View(c), p);
        }
        return g;
    }

    /** A grid cell: a picture (any height), a title and one dim line under it; tap and long-press. */
    static LinearLayout tile(Context c, View picture, String title, String sub, View.OnClickListener l, View.OnLongClickListener hold, boolean boxed) {
        LinearLayout t = Ui.col(c);
        if (boxed) { t.setBackground(Ui.press(Ui.CARD, 0, dp(c, 14))); t.setClipToOutline(true); }
        picture.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, picture.getLayoutParams() != null ? picture.getLayoutParams().height : dp(c, 92)));
        t.addView(picture);
        LinearLayout txt = Ui.col(c);
        txt.setPadding(boxed ? dp(c, 12) : 0, dp(c, boxed ? 10 : 6), boxed ? dp(c, 12) : 0, boxed ? dp(c, 12) : 0);
        TextView tt = Ui.text(c, title, boxed ? 14 : 13, Ui.TEXT);
        tt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tt.setMaxLines(2);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(tt);
        if (sub != null && !sub.isEmpty()) { TextView s = Ui.text(c, sub, 12, Ui.DIM); s.setPadding(0, dp(c, 2), 0, 0); s.setMaxLines(2); txt.addView(s); }
        t.addView(txt);
        if (l != null) t.setOnClickListener(l);
        if (hold != null) t.setOnLongClickListener(hold);
        return t;
    }

    /** A view with a fixed height (pictures inside tiles and rows). */
    static View sized(View v, int wDp, int hDp) {
        Context c = v.getContext();
        v.setLayoutParams(new LinearLayout.LayoutParams(wDp <= 0 ? ViewGroup.LayoutParams.MATCH_PARENT : dp(c, wDp), dp(c, hDp)));
        return v;
    }

    /** "Good morning" / "Good afternoon" / "Good evening" for the Home heading. */
    static String greeting() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
    }

    /** Short text for the language chip in the top bar. */
    static String langName(String code) {
        if (code == null || code.isEmpty()) return "—";
        return code.length() <= 3 ? code.toUpperCase(java.util.Locale.ROOT) : code;
    }
}
