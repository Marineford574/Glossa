package com.batchmine.pocket;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.batchmine.pocket.core.Dictionaries;
import com.batchmine.pocket.core.WordStore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** The word card: dictionary entries (as Yomitan shows them), pronunciation, and Known / Save / Make card. */
public final class WordSheet {
    public interface Changed { void changed(); }

    private WordSheet() { }

    /** A rounded box in the popup (like the boxes of the PC popup). */
    private static LinearLayout boxed(Activity a) {
        LinearLayout l = Ui.col(a);
        l.setBackground(Ui.round(Ui.CARD, Ui.dp(a, 10)));
        int q = Ui.dp(a, 10);
        l.setPadding(q, Ui.dp(a, 8), q, Ui.dp(a, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, 8);
        l.setLayoutParams(lp);
        return l;
    }

    private static TextView chip(Activity a, String s) {
        TextView t = Ui.text(a, s, 13, Ui.TEXT);
        t.setBackground(Ui.round(Ui.CARD2, Ui.dp(a, 12)));
        t.setPadding(Ui.dp(a, 10), Ui.dp(a, 3), Ui.dp(a, 10), Ui.dp(a, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(a, 6);
        t.setLayoutParams(lp);
        return t;
    }

    /** What the web page's tick boxes report: the numbers of the entries to put on the card. */
    private static final class Picks {
        volatile java.util.Set<Integer> set = new java.util.TreeSet<>();
        @android.webkit.JavascriptInterface public void picks(String csv) {
            java.util.Set<Integer> n = new java.util.TreeSet<>();
            for (String x : csv.split(",")) try { n.add(Integer.parseInt(x.trim())); } catch (NumberFormatException ignored) { }
            set = n;
        }
    }

    public static void show(Activity a, String lang, String word0, Cards.Request base, Changed onChange) {
        App app = App.get();
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = Ui.col(a);
        box.setBackground(Ui.round(Ui.BG, Ui.dp(a, 14)));
        int p = Ui.dp(a, 12);
        box.setPadding(p, p, p, p);
        final String[] word = {word0};
        final Dictionaries.Result[] res = {null};
        final Picks picks = new Picks();
        // ---- the word (can be changed), spoken, and what is known about it
        LinearLayout top = Ui.row(a);
        android.widget.EditText wIn = Ui.input(a, word0, "word");
        wIn.setSingleLine(true);
        wIn.setTextSize(22);
        wIn.setTypeface(Typeface.DEFAULT_BOLD);
        wIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        top.addView(Ui.weight(wIn, 1));
        Button say = Ui.button(a, "🔊", v -> Speak.say(a, lang, wIn.getText().toString().trim()));
        say.setMinWidth(0);
        top.addView(say);
        box.addView(top);
        LinearLayout meta = Ui.row(a);
        meta.setPadding(0, Ui.dp(a, 6), 0, 0);
        android.widget.HorizontalScrollView metaScroll = new android.widget.HorizontalScrollView(a);
        metaScroll.setHorizontalScrollBarEnabled(false);
        metaScroll.addView(meta);
        box.addView(metaScroll);
        // ---- the sentence (tap to change) with the lines before / after
        final String[] edited = {null};
        final int[] cb = {0}, ca = {0};
        LinearLayout senBox = boxed(a);
        TextView senT = Ui.text(a, "", 15, Ui.TEXT);
        senT.setPadding(0, 0, 0, Ui.dp(a, 2));
        android.widget.EditText senE = Ui.input(a, "", "sentence for the card");
        senE.setMinLines(2);
        senE.setVisibility(android.view.View.GONE);
        LinearLayout ctxRow = Ui.row(a);
        senBox.addView(Ui.text(a, "Sentence (tap to change)", 12, Ui.DIM));
        senBox.addView(senT);
        senBox.addView(senE);
        senBox.addView(ctxRow);
        final boolean hasSentence = base != null && !base.sentence.isEmpty();
        if (hasSentence) box.addView(senBox);
        Runnable[] drawSen = {null};
        java.util.function.Supplier<String> sentence = () -> {
            if (edited[0] != null) return edited[0];
            StringBuilder sb = new StringBuilder();
            for (int k = Math.min(cb[0], base.before.size()) - 1; k >= 0; k--) sb.append(base.before.get(k).text).append(' ');
            sb.append(base.sentence);
            for (int k = 0; k < Math.min(ca[0], base.after.size()); k++) sb.append(' ').append(base.after.get(k).text);
            return sb.toString();
        };
        drawSen[0] = () -> {
            if (!hasSentence) return;
            String t = sentence.get();
            android.text.SpannableString sp = new android.text.SpannableString(t);
            int k = t.toLowerCase().indexOf(wIn.getText().toString().trim().toLowerCase());
            if (k >= 0 && !wIn.getText().toString().trim().isEmpty()) {
                sp.setSpan(new android.text.style.BackgroundColorSpan(0x55F5C211), k, k + wIn.getText().toString().trim().length(), 0);
                sp.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), k, k + wIn.getText().toString().trim().length(), 0);
            }
            senT.setText(sp);
            ctxRow.removeAllViews();
            if (edited[0] != null) {
                Button undo = Ui.button(a, "↺ original", v -> { edited[0] = null; senE.setVisibility(android.view.View.GONE); senT.setVisibility(android.view.View.VISIBLE); drawSen[0].run(); });
                ctxRow.addView(undo);
                return;
            }
            Button b1 = Ui.button(a, "+ before" + (cb[0] > 0 ? " (" + cb[0] + ")" : ""), v -> { cb[0]++; drawSen[0].run(); });
            Button b2 = Ui.button(a, "+ after" + (ca[0] > 0 ? " (" + ca[0] + ")" : ""), v -> { ca[0]++; drawSen[0].run(); });
            b1.setEnabled(cb[0] < base.before.size());
            b2.setEnabled(ca[0] < base.after.size());
            for (Button bb : new Button[]{b1, b2}) { bb.setMinWidth(0); bb.setTextSize(12); }
            if (!base.before.isEmpty()) ctxRow.addView(b1);
            if (!base.after.isEmpty()) ctxRow.addView(b2);
            if (cb[0] + ca[0] > 0) ctxRow.addView(Ui.button(a, "only this", v -> { cb[0] = ca[0] = 0; drawSen[0].run(); }));
        };
        senT.setOnClickListener(v -> {
            senE.setText(sentence.get());
            edited[0] = sentence.get();
            senT.setVisibility(android.view.View.GONE);
            senE.setVisibility(android.view.View.VISIBLE);
            senE.requestFocus();
            drawSen[0].run();
        });
        senE.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            @Override public void onTextChanged(CharSequence s, int x, int y, int z) { edited[0] = s.toString(); }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        // ---- the dictionary entries, each with a tick box
        WebView web = null;
        TextView plain = null;
        LinearLayout defBox = boxed(a);
        defBox.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        defBox.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        ((LinearLayout.LayoutParams) defBox.getLayoutParams()).topMargin = Ui.dp(a, 8);
        try {
            web = new WebView(a);
            web.setBackgroundColor(Ui.CARD);
            web.getSettings().setJavaScriptEnabled(true);            // only for the tick boxes of our own page; nothing is loaded from the web
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setBlockNetworkLoads(true);
            web.addJavascriptInterface(picks, "Android");
            defBox.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (RuntimeException e) {                     // no WebView on this phone right now (being updated, switched off)
            web = null;
            plain = Ui.text(a, "", 15, Ui.TEXT);
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(plain);
            defBox.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        box.addView(defBox);
        final WebView webF = web;
        final TextView plainF = plain;
        LinearLayout btns = Ui.row(a);
        btns.setPadding(0, Ui.dp(a, 10), 0, 0);
        WordStore ws = app.store(lang);
        Button known = Ui.button(a, "Known", null), save = Ui.button(a, "Save", null), card = Ui.primary(a, "Make card", null);
        btns.addView(Ui.weight(known, 1));
        btns.addView(Ui.weight(save, 1));
        btns.addView(Ui.weight(card, 1.3f));
        box.addView(btns);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (a.getResources().getDisplayMetrics().heightPixels * (a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 0.95 : 0.86)));
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        Runnable refreshKnown = () -> {
            String key = res[0] == null ? word[0].toLowerCase() : res[0].lemma().toLowerCase();
            boolean k = ws.isKnown(key) || ws.isKnown(word[0].toLowerCase());
            known.setText(k ? "Not known" : "Known");
        };
        known.setOnClickListener(v -> {
            String lemma = res[0] == null ? word[0] : res[0].lemma();
            java.util.Set<String> both = new java.util.LinkedHashSet<>(java.util.Arrays.asList(word[0].toLowerCase(), lemma.toLowerCase()));
            if (ws.isKnown(lemma.toLowerCase()) || ws.isKnown(word[0].toLowerCase())) ws.markUnknown(both); else ws.markKnown(both);
            refreshKnown.run();
            if (onChange != null) onChange.changed();
            d.dismiss();
        });
        save.setOnClickListener(v -> {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("word", res[0] == null ? word[0] : res[0].lemma());
            it.put("surface", word[0]);
            it.put("sentence", base == null ? "" : sentence.get());
            it.put("source", base == null ? "" : base.title);
            it.put("t", System.currentTimeMillis());
            ws.saveWord(it);
            Ui.toast(a, "Saved for later: Words tab");
            if (onChange != null) onChange.changed();
            d.dismiss();
        });
        card.setOnClickListener(v -> {
            if (base == null) { Ui.toast(a, "No sentence here to make a card from"); return; }
            Cards.Request r = base.copy();
            r.surface = word[0];
            r.sentence = sentence.get();
            // a video card spans every line that was added
            for (int k = 0; k < Math.min(cb[0], base.before.size()) && edited[0] == null; k++) r.start = Math.min(r.start, base.before.get(k).start);
            for (int k = 0; k < Math.min(ca[0], base.after.size()) && edited[0] == null; k++) r.end = Math.max(r.end, base.after.get(k).end);
            r.word = res[0] == null ? word[0].toLowerCase() : res[0].lemma();
            if (res[0] != null) {
                java.util.Set<Integer> only = picks.set;
                for (int k = 0; k < res[0].hits.size(); k++) if (only.contains(k)) { r.glosses = res[0].hits.get(k).glosses; break; }
                r.defHtml = Dictionaries.allMeanings(res[0], only);
                if (res[0].freq > 0) r.freq = String.valueOf(res[0].freq);
                r.ipa = res[0].ipa.isEmpty() ? "" : res[0].ipa.values().iterator().next();
            }
            d.dismiss();
            Cards.make(a, r, () -> { if (onChange != null) onChange.changed(); });
        });
        if (Ui.gone(a)) return;
        d.show();
        d.setOnDismissListener(x -> { if (webF != null) try { webF.destroy(); } catch (RuntimeException ignored) { } });
        Runnable[] lookup = {null};
        lookup[0] = () -> {
            final String wd = wIn.getText().toString().trim();
            if (wd.isEmpty()) return;
            word[0] = wd;
            drawSen[0].run();
            app.bg(() -> {
                Dictionaries.Result r = app.dicts.lookup(wd, lang);
                ws.lookedUp(r.found() ? r.lemma() : wd);
                String html = page(r, app.dicts.styles(lang), app.dicts.hasTerms(lang));
                app.post(() -> {
                    if (!wd.equals(word[0])) return;                 // another word was typed meanwhile
                    res[0] = r;
                    picks.set = firstDictionary(r);
                    meta.removeAllViews();
                    if (r.found() && !r.lemma().equalsIgnoreCase(wd)) meta.addView(chip(a, "→ " + r.lemma()));
                    for (String ipa : r.ipa.values()) { meta.addView(chip(a, ipa)); break; }
                    if (r.freq > 0) meta.addView(chip(a, "#" + r.freq));
                    if (!r.found()) meta.addView(chip(a, "not in your dictionaries"));
                    if (webF != null) webF.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                    else if (plainF != null) plainF.setText(android.text.Html.fromHtml(html.replaceAll("(?s)<style>.*?</style>", ""), android.text.Html.FROM_HTML_MODE_COMPACT));
                    refreshKnown.run();
                    if (onChange != null) onChange.changed();      // it now counts as looked up
                });
            });
        };
        wIn.setOnEditorActionListener((tv, id, ev) -> { lookup[0].run(); return false; });
        drawSen[0].run();
        lookup[0].run();
    }

    /** The entries of the first dictionary that has the word: ticked to begin with (the page ticks them the same way). */
    static java.util.Set<Integer> firstDictionary(Dictionaries.Result r) {
        java.util.Set<Integer> s = new java.util.TreeSet<>();
        String first = null;
        for (int i = 0; i < r.hits.size(); i++) {
            if (first == null) first = r.hits.get(i).dict;
            if (first.equals(r.hits.get(i).dict)) s.add(i);
        }
        return s;
    }

    static String page(Dictionaries.Result r, String css, boolean haveDicts) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Integer> on = firstDictionary(r);
        sb.append("<html><head><meta name=viewport content='width=device-width, initial-scale=1'><style>")
          .append("body{background:#222226;color:#ececec;font:16px/1.5 sans-serif;margin:0;padding:0 2px 20px;--fg:#ececec;--text-color:#ececec}")
          .append("a{color:#78aeed}.hd{margin:10px 0 4px;font-weight:bold;font-size:17px;display:flex;align-items:center;gap:8px}.hd small{color:#9a9aa2;font-weight:normal;font-size:13px}")
          .append(".hd input{width:22px;height:22px;flex:none;accent-color:#3584e4}.blk{border-top:1px solid #34343a;margin-top:6px}")
          .append(".via{color:#9a9aa2;font-size:13px;margin-bottom:4px}ol{padding-left:22px;margin:4px 0}summary{color:#9a9aa2;cursor:pointer}")
          .append("details{margin:2px 0}.none{color:#9a9aa2;margin-top:20px}")
          .append(css == null ? "" : css)
          .append("</style><script>function pick(){var a=[];document.querySelectorAll('input.pk').forEach(function(x){if(x.checked)a.push(x.getAttribute('data-i'))});Android.picks(a.join(','))}</script></head><body>");
        if (!r.found()) {
            sb.append("<div class=none>").append(haveDicts ? "No entry for this word in your dictionaries." :
                    "No dictionary yet: Settings → Dictionaries → Import a dictionary (the same wty zip files the PC uses).").append("</div>");
        }
        String last = null;
        int i = 0;
        for (Dictionaries.Hit h : r.hits) {
            String key = h.term + "|" + h.via;
            sb.append("<div class=blk><div class=hd><input class=pk type=checkbox data-i=").append(i).append(on.contains(i) ? " checked" : "")
              .append(" onchange='pick()'><span>").append(esc(h.term)).append(" <small>").append(esc(h.pos)).append(" · ").append(esc(h.dict)).append("</small></span></div>");
            if (h.via != null && !key.equals(last)) sb.append("<div class=via>").append(esc(h.via)).append("</div>");
            last = key;
            sb.append(h.html).append("</div>");
            i++;
        }
        return sb.append("</body></html>").toString();
    }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    static java.util.List<String> none() { return Collections.emptyList(); }
}
