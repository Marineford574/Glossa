package com.batchmine.pocket;

import android.os.Build;
import android.text.SpannableString;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.View;

import com.batchmine.pocket.core.Dictionaries;
import com.batchmine.pocket.core.Subs;
import com.batchmine.pocket.core.WordStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Text with each word coloured by what you know (the PC's colours) and tappable. Run off the main thread: the first time
 * a word is seen its dictionary forms are read from disk (then they are cached).
 */
public final class Colourer {
    public interface OnWord { void tapped(String word, int start, int end, String text); }

    private final App app = App.get();
    private final String lang;
    private final Map<String, Boolean> listed = new java.util.concurrent.ConcurrentHashMap<>();
    public boolean colours = true;

    public Colourer(String lang) { this.lang = lang; }

    public static final class WordSpan extends ClickableSpan {
        public final String word, state;
        final int start, end;
        final String text;
        final OnWord cb;
        final float thick;

        WordSpan(String word, String state, int start, int end, String text, OnWord cb, float thick) {
            this.word = word; this.state = state; this.start = start; this.end = end; this.text = text; this.cb = cb; this.thick = thick;
        }

        @Override public void onClick(View widget) { if (cb != null) cb.tapped(word, start, end, text); }

        @Override public void updateDrawState(TextPaint tp) {
            tp.setColor(Ui.TEXT);
            int bg = Ui.stateBg(state), line = Ui.stateLine(state);
            tp.bgColor = bg;
            tp.setUnderlineText(false);
            if (line != 0) {
                if (Build.VERSION.SDK_INT >= 29) { tp.underlineColor = line; tp.underlineThickness = thick; }
                else if (bg == 0) tp.setColor(line);                         // older Android: no coloured underline
            }
        }
    }

    /** Furigana over the kanji of a word: the reading is drawn small above them (the line grows to make room). */
    public static final class RubySpan extends android.text.style.ReplacementSpan {
        final List<String[]> ruby;          // {start, end, reading} inside the word
        final int line;                     // underline colour of the state (0 none), drawn here because the text is
        final float thick;                  // and its thickness
        final int back;                     // background colour of the state (0 none)

        RubySpan(List<String[]> ruby, int line, float thick, int back) { this.ruby = ruby; this.line = line; this.thick = thick; this.back = back; }

        private static float rubySize(android.graphics.Paint p) { return p.getTextSize() * 0.5f; }

        @Override public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            if (fm != null) {
                int room = Math.round(rubySize(paint) * 1.2f);
                fm.ascent -= room; fm.top -= room;
            }
            return Math.round(paint.measureText(text, start, end));
        }

        @Override public void draw(android.graphics.Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, android.graphics.Paint paint) {
            float w = paint.measureText(text, start, end);
            if (back != 0) {
                int keep = paint.getColor();
                paint.setColor(back);
                android.graphics.Paint.FontMetrics fmt = paint.getFontMetrics();
                c.drawRect(x, y + fmt.ascent, x + w, y + fmt.descent, paint);
                paint.setColor(keep);
            }
            c.drawText(text, start, end, x, y, paint);
            if (line != 0) {
                int keep = paint.getColor();
                paint.setColor(line);
                c.drawRect(x, y + thick, x + w, y + thick * 2, paint);
                paint.setColor(keep);
            }
            android.graphics.Paint rp = new android.graphics.Paint(paint);
            rp.setTextSize(rubySize(paint));
            rp.setColor(Ui.DIM);
            android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
            float base = y + fm.ascent - rp.getFontMetrics().descent * 0.2f;
            for (String[] r : ruby) {
                int a = start + Integer.parseInt(r[0]), b = start + Integer.parseInt(r[1]);
                float x0 = x + paint.measureText(text, start, a), w0 = paint.measureText(text, a, b);
                float rw = rp.measureText(r[2]);
                c.drawText(r[2], x0 + (w0 - rw) / 2f, base, rp);
            }
        }
    }

    public String state(String surface) {
        WordStore ws = app.store(lang);
        Set<String> cands = app.dicts.candidates(surface, lang);
        Boolean l = listed.get(surface);
        if (l == null) {
            l = !app.dicts.hasTerms(lang) || app.dicts.listed(cands, lang);
            listed.put(surface, l);
        }
        return ws.state(cands, surface, l);
    }

    /** TEXT as tappable coloured words. */
    public SpannableString spans(String text, OnWord cb) {
        SpannableString s = new SpannableString(text);
        float thick = Ui.dp(app, 2);
        List<int[]> ws = Subs.words(text);
        String rubyMode = lang.equals("ja") ? app.prefs().getString("ja_ruby", "unknown") : "off";      // off / unknown (words not known yet) / all
        for (int[] w : ws) {
            String word = text.substring(w[0], w[1]);
            String st = colours ? state(word) : "";
            s.setSpan(new WordSpan(word, st, w[0], w[1], text, cb, thick), w[0], w[1], SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (!rubyMode.equals("off") && (rubyMode.equals("all") || st.equals("u") || st.equals("e") || st.isEmpty())) {
                boolean hasHan = false;
                for (int k = 0; k < word.length() && !hasHan; k++) hasHan = com.batchmine.pocket.core.Ja.isHan(word.charAt(k));
                String[] lr = hasHan ? app.dicts.readingOf(word) : null;
                if (lr != null) {
                    List<String[]> ru = com.batchmine.pocket.core.Ja.rubyFor(word, lr[0], lr[1]);
                    if (!ru.isEmpty()) s.setSpan(new RubySpan(ru, Ui.stateLine(st), thick, Ui.stateBg(st)), w[0], w[1], SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return s;
    }

    public void forget() { listed.clear(); }

    /** The sentence of TEXT that holds position AT (for the card). */
    public static String sentenceAt(String text, int at) {
        int a = at, b = at;
        while (a > 0 && ".!?…\n".indexOf(text.charAt(a - 1)) < 0) a--;
        while (b < text.length() && ".!?…\n".indexOf(text.charAt(b)) < 0) b++;
        while (b < text.length() && ".!?…»\"”’)".indexOf(text.charAt(b)) >= 0) b++;
        return text.substring(a, b).trim();
    }
}
