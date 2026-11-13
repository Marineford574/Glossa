package com.batchmine.pocket;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.batchmine.pocket.core.Dictionaries;
import com.batchmine.pocket.core.WordStore;

/** The quick word bar at the bottom of the Reader (the redesign): a tapped word shows its lemma and first meaning without covering the page.
 *  Card makes the card at once (first dictionary's meanings, as the sheet would by default); ⌃ or a tap on the word opens the whole word sheet. */
final class WordBar {
    interface Changed { void changed(); }

    private final Activity a;
    private final Changed onChange;
    private final LinearLayout bar;
    private final TextView head, gloss;
    private final LinearLayout facts;
    private String lang = "", word = "";
    private Cards.Request base;
    private Dictionaries.Result res;
    private int ask;                                      // which lookup is current (an older answer arriving late is dropped)

    WordBar(Activity a, Changed onChange) {
        this.a = a;
        this.onChange = onChange;
        bar = Ui.row(a);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(Ui.mix(Ui.BG, Ui.TEXT, 0.07f), Ui.dp(a, 16)));
        bar.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10));
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 8));
        bar.setLayoutParams(lp);

        LinearLayout text = Ui.col(a);
        LinearLayout top = Ui.row(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        head = Ui.text(a, "", 17, Ui.TEXT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setSingleLine(true);
        top.addView(head);
        facts = Ui.row(a);
        facts.setPadding(Ui.dp(a, 8), 0, 0, 0);
        top.addView(facts);
        text.addView(top);
        gloss = Ui.text(a, "", 13, Ui.DIM);
        gloss.setSingleLine(true);
        gloss.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(gloss);
        text.setOnClickListener(v -> open());
        bar.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View card = Kit.textButton(a, "Card", "Make a card", true, v -> card());
        bar.addView(card, gap());
        bar.addView(Kit.textButton(a, "⌃", "More about the word", false, v -> open()), gap());
        bar.addView(Kit.textButton(a, "✕", "Close", false, v -> hide()), gap());
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(a, 6);
        return lp;
    }

    View view() { return bar; }

    boolean showing() { return bar.getVisibility() == View.VISIBLE; }

    void hide() { bar.setVisibility(View.GONE); }

    /** Shows WORD (the same word tapped again opens the whole sheet). */
    void show(String lang, String w, Cards.Request base) {
        if (showing() && w.equalsIgnoreCase(word) && lang.equals(this.lang)) { this.base = base; open(); return; }
        this.lang = lang;
        this.word = w;
        this.base = base;
        this.res = null;
        head.setText(w);
        gloss.setText("…");
        facts.removeAllViews();
        bar.setVisibility(View.VISIBLE);
        final int me = ++ask;
        App app = App.get();
        app.bg(() -> {
            Dictionaries.Result r = app.dicts.lookup(w, lang);
            app.store(lang).lookedUp(r.found() ? r.lemma() : w);
            app.post(() -> {
                if (me != ask) return;
                res = r;
                head.setText(r.found() ? r.lemma() : w);
                String g = "";
                if (r.found()) for (Dictionaries.Hit h : r.hits) { for (String x : h.glosses) if (!x.trim().isEmpty()) { g = x.trim(); break; } if (!g.isEmpty()) break; }
                gloss.setText(!r.found() ? "Not in your dictionaries" : g.isEmpty() ? r.hits.get(0).dict : g);
                for (Extras.Fact f : Extras.facts(lang, r.found() ? r.lemma() : w)) {
                    TextView c = Kit.fact(a, f.text, f.kind);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = Ui.dp(a, 4);
                    facts.addView(c, lp);
                }
                if (onChange != null) onChange.changed();         // it now counts as looked up
            });
        });
    }

    private void open() {
        hide();
        WordSheet.show(a, lang, word, base, () -> { if (onChange != null) onChange.changed(); });
    }

    private void card() {
        if (base == null) { Ui.toast(a, "No sentence here to make a card from"); return; }
        Cards.Request r = base.copy();
        r.surface = word;
        r.word = word.toLowerCase();
        WordSheet.fill(r, res, null, lang);
        hide();
        Cards.make(a, r, () -> { if (onChange != null) onChange.changed(); });
    }
}
