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

    public static final int BG = 0xFF151517, CARD = 0xFF222226, CARD2 = 0xFF2C2C31, TEXT = 0xFFECECEC, DIM = 0xFF9A9AA2,
            ACCENT = 0xFF3584E4, LINE = 0xFF34343A, BAD = 0xFFE5534B, GOOD = 0xFF33D17A;

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
        b.setBackground(round(CARD2, dp(c, 10)));
        b.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
        b.setMinHeight(dp(c, 44));
        b.setOnClickListener(l);
        return b;
    }

    public static Button primary(Context c, CharSequence s, View.OnClickListener l) {
        Button b = button(c, s, l);
        b.setBackground(round(ACCENT, dp(c, 10)));
        b.setTextColor(Color.WHITE);
        return b;
    }

    /** A tappable row: a title with a line of detail under it. */
    public static LinearLayout item(Context c, CharSequence head, CharSequence sub, View.OnClickListener l) {
        LinearLayout v = col(c);
        v.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        v.setBackground(round(CARD, dp(c, 10)));
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
        e.setBackground(round(CARD2, dp(c, 8)));
        e.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        return e;
    }

    public static void toast(Context c, CharSequence s) { Toast.makeText(c, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show(); }

    public static void alert(Context c, String title, String msg) {
        dialog(c).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    /** A dialog builder whose show() does nothing (instead of crashing) when the screen it belongs to has closed meanwhile. */
    public static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, android.R.style.Theme_DeviceDefault_Dialog_Alert) {
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
