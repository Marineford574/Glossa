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

    public static void show(Activity a, String lang, String word, Cards.Request base, Changed onChange) {
        App app = App.get();
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = Ui.col(a);
        box.setBackground(Ui.round(Ui.BG, Ui.dp(a, 14)));
        int p = Ui.dp(a, 14);
        box.setPadding(p, p, p, p);
        TextView head = Ui.text(a, word, 24, Ui.TEXT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = Ui.text(a, "looking up…", 14, Ui.DIM);
        box.addView(head);
        box.addView(sub);
        WebView web = null;
        TextView plain = null;
        try {
            web = new WebView(a);
            web.setBackgroundColor(Ui.BG);
            web.getSettings().setJavaScriptEnabled(false);
            box.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } catch (RuntimeException e) {                     // no WebView on this phone right now (being updated, switched off)
            web = null;
            plain = Ui.text(a, "", 15, Ui.TEXT);
            android.widget.ScrollView sv = new android.widget.ScrollView(a);
            sv.addView(plain);
            box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        final WebView webF = web;
        final TextView plainF = plain;
        LinearLayout btns = Ui.row(a);
        btns.setPadding(0, Ui.dp(a, 10), 0, 0);
        WordStore ws = app.store(lang);
        final Dictionaries.Result[] res = {null};
        Button known = Ui.button(a, "Known", null), save = Ui.button(a, "Save", null), card = Ui.primary(a, "Make card", null);
        btns.addView(Ui.weight(known, 1));
        btns.addView(Ui.weight(save, 1));
        btns.addView(Ui.weight(card, 1.3f));
        box.addView(btns);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (a.getResources().getDisplayMetrics().heightPixels * 0.72));
            w.setGravity(Gravity.BOTTOM);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        Runnable refreshKnown = () -> {
            String key = res[0] == null ? word.toLowerCase() : res[0].lemma().toLowerCase();
            boolean k = ws.isKnown(key) || ws.isKnown(word.toLowerCase());
            known.setText(k ? "Not known" : "Known");
        };
        known.setOnClickListener(v -> {
            String lemma = res[0] == null ? word : res[0].lemma();
            java.util.Set<String> both = new java.util.LinkedHashSet<>(java.util.Arrays.asList(word.toLowerCase(), lemma.toLowerCase()));
            if (ws.isKnown(lemma.toLowerCase()) || ws.isKnown(word.toLowerCase())) ws.markUnknown(both); else ws.markKnown(both);
            refreshKnown.run();
            if (onChange != null) onChange.changed();
            d.dismiss();
        });
        save.setOnClickListener(v -> {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("word", res[0] == null ? word : res[0].lemma());
            it.put("surface", word);
            it.put("sentence", base == null ? "" : base.sentence);
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
            r.surface = word;
            r.word = res[0] == null ? word.toLowerCase() : res[0].lemma();
            if (res[0] != null) {
                if (!res[0].hits.isEmpty()) r.glosses = res[0].hits.get(0).glosses;
                r.ipa = res[0].ipa.isEmpty() ? "" : res[0].ipa.values().iterator().next();
            }
            d.dismiss();
            Cards.make(a, r, () -> { if (onChange != null) onChange.changed(); });
        });
        if (Ui.gone(a)) return;
        d.show();
        d.setOnDismissListener(x -> { if (webF != null) try { webF.destroy(); } catch (RuntimeException ignored) { } });
        app.bg(() -> {
            Dictionaries.Result r = app.dicts.lookup(word, lang);
            ws.lookedUp(r.found() ? r.lemma() : word);
            String html = page(r, app.dicts.styles(lang), app.dicts.hasTerms(lang));
            app.post(() -> {
                res[0] = r;
                StringBuilder s = new StringBuilder();
                if (r.found() && !r.lemma().equalsIgnoreCase(word)) s.append("→ ").append(r.lemma());
                for (String ipa : r.ipa.values()) { if (s.length() > 0) s.append("   "); s.append(ipa); break; }
                sub.setText(s.length() > 0 ? s : r.found() ? "" : "not in your dictionaries");
                if (webF != null) webF.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                else if (plainF != null) plainF.setText(android.text.Html.fromHtml(html.replaceAll("(?s)<style>.*?</style>", ""), android.text.Html.FROM_HTML_MODE_COMPACT));
                refreshKnown.run();
                if (onChange != null) onChange.changed();      // it now counts as looked up
            });
        });
    }

    static String page(Dictionaries.Result r, String css, boolean haveDicts) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta name=viewport content='width=device-width, initial-scale=1'><style>")
          .append("body{background:#151517;color:#ececec;font:16px/1.5 sans-serif;margin:0;padding:0 2px 20px;--fg:#ececec;--text-color:#ececec}")
          .append("a{color:#78aeed}.hd{margin:14px 0 4px;font-weight:bold;font-size:17px}.hd small{color:#9a9aa2;font-weight:normal;font-size:13px}")
          .append(".via{color:#9a9aa2;font-size:13px;margin-bottom:4px}ol{padding-left:22px;margin:4px 0}summary{color:#9a9aa2;cursor:pointer}")
          .append("details{margin:2px 0}.none{color:#9a9aa2;margin-top:20px}")
          .append(css == null ? "" : css)
          .append("</style></head><body>");
        if (!r.found()) {
            sb.append("<div class=none>").append(haveDicts ? "No entry for this word in your dictionaries." :
                    "No dictionary yet: Settings → Dictionaries → Import a dictionary (the same wty zip files the PC uses).").append("</div>");
        }
        String last = null;
        for (Dictionaries.Hit h : r.hits) {
            String key = h.term + "|" + h.via;
            sb.append("<div class=hd>").append(esc(h.term)).append(" <small>").append(esc(h.pos)).append(" · ").append(esc(h.dict)).append("</small></div>");
            if (h.via != null && !key.equals(last)) sb.append("<div class=via>").append(esc(h.via)).append("</div>");
            last = key;
            sb.append(h.html);
        }
        return sb.append("</body></html>").toString();
    }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    static java.util.List<String> none() { return Collections.emptyList(); }
}
