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
        for (int[] w : ws) {
            String word = text.substring(w[0], w[1]);
            String st = colours ? state(word) : "";
            s.setSpan(new WordSpan(word, st, w[0], w[1], text, cb, thick), w[0], w[1], SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
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
