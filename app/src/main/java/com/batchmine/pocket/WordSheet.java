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
import com.batchmine.pocket.core.Json;
import com.batchmine.pocket.core.WordStore;

import java.util.Collections;
import java.util.Locale;
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
        /** The single meanings ticked (as on the PC): [{e: entry, l: language of the definitions, t: text}]. */
        volatile java.util.List<Object> defs = new java.util.ArrayList<>();
        @android.webkit.JavascriptInterface public void picks(String csv) {
            java.util.Set<Integer> n = new java.util.TreeSet<>();
            for (String x : csv.split(",")) try { n.add(Integer.parseInt(x.trim())); } catch (NumberFormatException ignored) { }
            set = n;
        }
        @android.webkit.JavascriptInterface public void defs(String json) {
            try { defs = Json.arr(Json.parse(json)); } catch (RuntimeException ignored) { }
        }
    }

    /** The language the definitions of a dictionary are written in, from its name ("wty-it-en", "kaikki it-de"): the PC files the
     *  ticked meanings under it (the short definition field of that language). Same language as the words if the name says nothing. */
    static String glossLang(String dict, String lang) {
        String[] t = (dict == null ? "" : dict).split("[^A-Za-z]+");
        for (int i = 0; i + 1 < t.length; i++)
            if (t[i].equalsIgnoreCase(lang) && t[i + 1].length() >= 2 && t[i + 1].length() <= 3) return t[i + 1].toLowerCase(java.util.Locale.ROOT);
        return lang;
    }

    public static void show(Activity a, String lang, String word0, Cards.Request base, Changed onChange) { show(a, lang, word0, base, onChange, null); }

    /** CLOSED runs when the sheet goes away (the player keeps the video paused until then). */
    public static void show(Activity a, String lang, String word0, Cards.Request base, Changed onChange, Runnable closed) {
        App app = App.get();
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = Ui.col(a);
        float rr = Ui.dp(a, 24);
        android.graphics.drawable.GradientDrawable sheetBg = new android.graphics.drawable.GradientDrawable();
        sheetBg.setColor(Ui.BG);
        sheetBg.setCornerRadii(new float[]{rr, rr, rr, rr, 0, 0, 0, 0});          // rounded at the top only: it rises from the bottom edge
        box.setBackground(sheetBg);
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
        Button close = Ui.flat(a, "✕", v -> d.dismiss());
        close.setMinWidth(0);
        close.setContentDescription("Close");
        top.addView(close);
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
        senE.setSingleLine(false);                           // the whole sentence, wrapped over several lines, not one long line to scroll through
        senE.setHorizontallyScrolling(false);
        senE.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        senE.setMinLines(3);
        senE.setMaxLines(8);
        senE.setGravity(Gravity.TOP | Gravity.START);
        senE.setTextSize(16);
        senE.setLineSpacing(0, 1.15f);
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
        // ---- one tab per language the definitions are written in (shown when there are two or more)
        final LinearLayout langTabs = Ui.col(a);
        langTabs.setPadding(0, Ui.dp(a, 8), 0, 0);
        box.addView(langTabs);
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
        android.widget.HorizontalScrollView sitesScroll = new android.widget.HorizontalScrollView(a);
        sitesScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sites = Ui.row(a);
        sites.setPadding(0, Ui.dp(a, 8), 0, 0);
        sitesScroll.addView(sites);
        Runnable drawSites = () -> {
            sites.removeAllViews();
            TextView lbl = Ui.text(a, "Look up on", 12, Ui.DIM);
            lbl.setPadding(0, 0, Ui.dp(a, 8), 0);
            sites.addView(lbl);
            String w = wIn.getText().toString().trim();
            if (w.isEmpty()) return;
            String lem = res[0] != null && res[0].found() ? res[0].lemma() : w;
            if (Conj.offered(lang, lem)) {                 // 5  the PC's conjugation table
                TextView cj = Ui.pill(a, "Conjugation", Ui.ACCENT, Ui.CARD2);
                cj.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4));
                cj.setTextSize(13);
                cj.setOnClickListener(v -> Conj.show(a, lang, lem));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.rightMargin = Ui.dp(a, 6);
                sites.addView(cj, clp);
            }
            for (String[] site : com.batchmine.pocket.core.Sites.urls(lang, w)) {
                TextView chip = Ui.pill(a, site[0], Ui.ACCENT, Ui.CARD2);
                chip.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4));
                chip.setTextSize(13);
                chip.setOnClickListener(v -> { try { a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(site[1]))); } catch (RuntimeException e) { Ui.toast(a, "No browser to open it"); } });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = Ui.dp(a, 6);
                sites.addView(chip, lp);
            }
        };
        drawSites.run();
        wIn.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence x, int y, int z, int q) { }
            @Override public void onTextChanged(CharSequence x, int y, int z, int q) { }
            @Override public void afterTextChanged(android.text.Editable e) { drawSites.run(); }
        });
        box.addView(sitesScroll);
        LinearLayout btns = Ui.row(a);
        btns.setPadding(0, Ui.dp(a, 10), 0, 0);
        WordStore ws = app.store(lang);
        Button known = Ui.button(a, "Add to known", null), save = Ui.button(a, "Save", null), card = Ui.primary(a, "Make card", null);
        btns.addView(Ui.weight(known, 1));
        btns.addView(Ui.weight(save, 1));
        btns.addView(Ui.weight(card, 1.8f));
        box.addView(btns);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));      // the dialog theme's own frame left a strip free on each side
            w.getDecorView().setPadding(0, 0, 0, 0);
            int sw = a.getResources().getDisplayMetrics().widthPixels, cap = Ui.dp(a, 640);    // the whole width, unless that is very wide (a tablet, landscape)
            w.setLayout(sw > cap * 1.15 ? cap : ViewGroup.LayoutParams.MATCH_PARENT, (int) (a.getResources().getDisplayMetrics().heightPixels * (a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 0.95 : 0.86)));
            w.setGravity(Gravity.BOTTOM);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        Runnable refreshKnown = () -> {
            String key = res[0] == null ? word[0].toLowerCase() : res[0].lemma().toLowerCase();
            boolean k = ws.isKnown(key) || ws.isKnown(word[0].toLowerCase());
            known.setText(k ? "Not known" : "Add to known");
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
                fill(r, res[0], only, lang);
                r.picked = new java.util.LinkedHashMap<>();                 // the single meanings ticked: the short definition
                for (Object o : picks.defs) {
                    Map<String, Object> x = Json.obj(o);
                    String t = Json.str(x.get("t"), "").trim(), l = Json.str(x.get("l"), lang);
                    if (t.isEmpty()) continue;
                    java.util.List<String> lst = r.picked.get(l);
                    if (lst == null) r.picked.put(l, lst = new java.util.ArrayList<>());
                    if (!lst.contains(t) && lst.size() < 12) lst.add(t);
                }
            }
            d.dismiss();
            Cards.make(a, r, () -> { if (onChange != null) onChange.changed(); });
        });
        if (Ui.gone(a)) { if (closed != null) closed.run(); return; }
        // game controller: D-pad up / down frame a definition, A ticks it (no definition framed: A makes the card), Menu makes the card, X known, Y save, B close
        final Pad.Target padAct = act -> {
            switch (act) {
                case "defUp": case "defDown": if (webF != null) webF.evaluateJavascript("padDef(" + (act.equals("defDown") ? 1 : -1) + ")", null); return true;
                case "select":
                    if (webF == null) { card.performClick(); return true; }
                    webF.evaluateJavascript("padSel()", val -> { if (val != null && val.contains("none")) card.performClick(); });
                    return true;
                case "scrollUp": case "scrollDown": if (webF != null) { if (act.equals("scrollUp")) webF.pageUp(false); else webF.pageDown(false); } return true;
                case "card": card.performClick(); return true;
                case "known": known.performClick(); return true;
                case "save": save.performClick(); return true;
                case "close": d.dismiss(); return true;
                default: return false;
            }
        };
        d.setOnKeyListener((dd, code, ev) -> {
            if (!Pad.fromPad(ev)) return false;
            if (ev.getAction() == android.view.KeyEvent.ACTION_DOWN && (ev.getRepeatCount() == 0 || code >= android.view.KeyEvent.KEYCODE_DPAD_UP && code <= android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                String act = Pad.actionFor("word", Pad.button(code));
                if (!act.isEmpty()) padAct.act(act);
            }
            return true;
        });
        if (d.getWindow() != null) d.getWindow().getDecorView().setOnGenericMotionListener((v, e) -> Pad.motion(a, e, "word", padAct));
        d.show();
        d.setOnDismissListener(x -> { if (webF != null) try { webF.destroy(); } catch (RuntimeException ignored) { } if (closed != null) closed.run(); });
        Runnable[] lookup = {null};
        lookup[0] = () -> {
            final String wd = wIn.getText().toString().trim();
            if (wd.isEmpty()) return;
            word[0] = wd;
            drawSen[0].run();
            app.bg(() -> {
                Dictionaries.Result r = app.dicts.lookup(wd, lang);
                ws.lookedUp(r.found() ? r.lemma() : wd);
                String htmlBase = page(r, app.dicts.styles(lang), app.dicts.hasTerms(lang), lang);
                if (app.hasDictCc()) {                           // dict.cc, folded under the dictionaries; open when they had nothing
                    try {
                        java.util.Set<String> cands = new java.util.LinkedHashSet<>();
                        cands.add(wd);
                        cands.addAll(app.dicts.candidates(wd, lang));
                        if (r.found()) cands.add(r.lemma());
                        String blk = com.batchmine.pocket.core.DictCc.html(com.batchmine.pocket.core.DictCc.lookup(app.dictCc(), cands), !r.found(), "");
                        if (!blk.isEmpty()) htmlBase = htmlBase.replace("</body></html>", "<style>details.dcc{margin-top:10px;border-top:1px solid " + Ui.hex(Ui.LINE) + "}details.dcc summary{padding:8px 0;font-weight:bold}.dcch{color:" + Ui.hex(Ui.DIM) + ";font-size:13px;margin-top:4px}.dccw{display:inline-block;margin:2px 10px 2px 0}.dccw input{width:19px;height:19px;margin:0 6px 0 0;vertical-align:-4px;accent-color:" + Ui.hex(Ui.ACCENT) + "}.dccp{font-size:14px;margin:3px 0}</style>" + blk + "</body></html>");
                    } catch (RuntimeException ignored) { /* a damaged dict.cc file must not spoil the card */ }
                }
                final String html = htmlBase;
                app.post(() -> {
                    if (!wd.equals(word[0])) return;                 // another word was typed meanwhile
                    res[0] = r;
                    picks.set = firstDictionary(r);
                    picks.defs = new java.util.ArrayList<>();
                    meta.removeAllViews();
                    if (r.found() && !r.lemma().equalsIgnoreCase(wd)) meta.addView(chip(a, "→ " + r.lemma()));
                    for (String ipa : r.ipa.values()) { meta.addView(chip(a, ipa)); break; }
                    if (r.freq > 0) meta.addView(chip(a, "#" + r.freq));
                    if (!r.found()) meta.addView(chip(a, "not in your dictionaries"));
                    // the chips features add (level, gender …): Extras.factSource
                    for (Extras.Fact f : Extras.facts(lang, r.found() ? r.lemma() : wd)) {
                        TextView fc = Kit.fact(a, f.text, f.kind);
                        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        flp.rightMargin = Ui.dp(a, 6);
                        meta.addView(fc, flp);
                    }
                    drawLangTabs(a, langTabs, r, lang, webF);
                    drawSites.run();
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

    /** Puts what a dictionary lookup knows onto a card: the meanings of the dictionaries in ONLY (all when null), glosses, frequency, IPA.
     *  Used by this sheet and by the quick word bars (Reader, later the Player) so a card looks the same wherever it is made. */
    static void fill(Cards.Request r, Dictionaries.Result res, java.util.Set<Integer> only, String lang) {
        if (res == null || !res.found()) return;
        if (only == null) only = firstDictionary(res);
        r.word = res.lemma();
        for (int k = 0; k < res.hits.size(); k++) if (only.contains(k)) { r.glosses = res.hits.get(k).glosses; break; }
        r.defHtml = Dictionaries.allMeanings(res, only);
        r.richBy = Dictionaries.meaningsByLabel(res, only, Cards.dictLabels(lang));
        if (res.freq > 0) r.freq = String.valueOf(res.freq);
        r.ipa = res.ipa.isEmpty() ? "" : res.ipa.values().iterator().next();
    }

    /** The row of definition-language tabs ("All", "EN", "IT" …): a tap shows only the entries written in that language. */
    private static void drawLangTabs(Activity a, LinearLayout holder, Dictionaries.Result r, String lang, WebView web) {
        holder.removeAllViews();
        java.util.List<String> ls = new java.util.ArrayList<>();
        for (Dictionaries.Hit h : r.hits) { String g = glossLang(h.dict, lang); if (DefLangs.on(lang, g) && !ls.contains(g)) ls.add(g); }
        if (ls.size() < 2 || web == null) return;
        String[] names = new String[ls.size() + 1];
        names[0] = "All";
        for (int i = 0; i < ls.size(); i++) names[i + 1] = ls.get(i).toUpperCase(java.util.Locale.ROOT);
        final Flow[] row = {null};
        final int[] chosen = {0};
        Runnable[] redraw = {null};
        redraw[0] = () -> {
            if (row[0] != null) holder.removeView(row[0]);
            row[0] = Kit.chips(a, names, chosen[0], i -> {
                chosen[0] = i;
                web.evaluateJavascript("showLang(" + (i == 0 ? "''" : "'" + ls.get(i - 1).replace("'", "") + "'") + ")", null);
                redraw[0].run();
            });
            holder.addView(row[0]);
        };
        redraw[0].run();
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

    static String page(Dictionaries.Result r, String css, boolean haveDicts) { return page(r, css, haveDicts, ""); }

    static String page(Dictionaries.Result r, String css, boolean haveDicts, String lang) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Integer> on = firstDictionary(r);
        sb.append("<html><head><meta name=viewport content='width=device-width, initial-scale=1'><style>")
          .append("body{background:").append(Ui.hex(Ui.CARD)).append(";color:").append(Ui.hex(Ui.TEXT)).append(";font:16px/1.5 sans-serif;margin:0;padding:0 2px 20px;--fg:").append(Ui.hex(Ui.TEXT)).append(";--text-color:").append(Ui.hex(Ui.TEXT)).append("}")
          // the look of the PC's word card (.gx in mine_gui.css): entries split by a dashed line, a head with the part of speech in the accent
          // colour and the definition language as a small framed label, faint numbers, tags as small pills, examples in italics behind a bar
          .append("a{color:").append(Ui.hex(Ui.ACCENT)).append("}")
          .append(".hd{margin:2px 0 2px;display:flex;align-items:center;gap:8px;flex-wrap:wrap}.hd .tm{font-weight:600;font-size:16px}")
          .append(".hd .pos{font-size:13px;color:").append(Ui.hex(Ui.ACCENT)).append(";font-weight:700}")
          .append(".hd .glang{font-size:11px;font-weight:700;letter-spacing:.05em;color:").append(Ui.hex(Ui.DIM)).append(";border:1px solid ").append(Ui.hex(Ui.LINE)).append(";border-radius:4px;padding:0 4px}")
          .append(".hd small{color:").append(Ui.hex(Ui.DIM)).append(";font-size:12px;margin-left:auto}")
          .append(".hd input{width:20px;height:20px;flex:none;margin:0;accent-color:").append(Ui.hex(Ui.ACCENT)).append("}")
          .append(".blk{line-height:1.45;font-size:15px}.blk+.blk{margin-top:10px;padding-top:8px;border-top:1px dashed ").append(Ui.hex(Ui.LINE)).append("}")
          .append(".via{color:").append(Ui.hex(Ui.DIM)).append(";font-size:12.5px;margin-bottom:2px}")
          .append("ol{padding-left:1.4em;margin:2px 0 0}ol>li{margin:3px 0;padding-left:2px}li::marker{color:").append(Ui.hex(Ui.DIM)).append(";font-size:12px}ol ol{list-style:lower-alpha}ul{padding-left:1.2em;margin:2px 0}")
          .append("[data-sc-content*=example],[data-sc-class*=example],.gexa{font-style:italic;font-size:13.5px;color:").append(Ui.hex(Ui.DIM)).append(";margin:1px 0 0 2px;padding-left:8px;border-left:2px solid ").append(Ui.hex(Ui.LINE)).append(";list-style:none}")
          .append("[data-sc-content=tag],[data-sc-class=tag],[data-sc-content*=tags]>span,.gtag{display:inline-block;font-size:11px;line-height:16px;padding:0 6px;margin-right:5px;border-radius:99px;background:rgba(127,127,127,.18);color:").append(Ui.hex(Ui.DIM)).append(";vertical-align:1px;font-style:normal}")
          .append("summary{color:").append(Ui.hex(Ui.DIM)).append(";font-size:13px;cursor:pointer}details{margin:3px 0;font-size:13.5px}.none{color:").append(Ui.hex(Ui.DIM)).append(";margin-top:20px}")
          // the single meanings, each with its own tick box (the PC's .gpick): tap a meaning to put it on the card
          .append("input.gpick{width:19px;height:19px;margin:0 7px 0 0;vertical-align:-4px;accent-color:").append(Ui.hex(Ui.ACCENT)).append("}")
          .append("li.gli{border-radius:7px;padding:2px 4px;margin:1px -4px}li.gli.picked{background:").append(Ui.hex(Ui.mix(Ui.CARD, Ui.ACCENT, 0.2f))).append("}")
          .append(".padrow{outline:2px solid ").append(Ui.hex(Ui.ACCENT)).append(";outline-offset:1px;border-radius:7px;background:").append(Ui.hex(Ui.mix(Ui.CARD, Ui.ACCENT, 0.18f))).append("}")
          .append(".gnote{font-size:12.5px;color:").append(Ui.hex(Ui.DIM)).append(";margin:6px 0 2px}")
          .append(css == null ? "" : css)
          .append("</style><script>")
          .append("function pick(){var a=[];document.querySelectorAll('input.pk').forEach(function(x){if(x.checked)a.push(x.getAttribute('data-i'))});Android.picks(a.join(','))}")
          // every meaning (a list item with text of its own, examples and sub-lists left out) gets a tick box, as in the PC's word card
          .append("function own(li){var c=li.cloneNode(true);c.querySelectorAll('ol,ul,details,input,[data-sc-content*=example],[data-sc-content*=notes],[data-sc-content*=info]').forEach(function(x){x.remove()});return c.textContent.replace(/\\s+/g,' ').trim()}")
          .append("function defs(){var a=[];document.querySelectorAll('input.gpick:checked').forEach(function(x){a.push({e:+x.dataset.e,l:x.dataset.l,t:x.dataset.t})});Android.defs(JSON.stringify(a))}")
          .append("function boxes(){var n=0;document.querySelectorAll('.blk').forEach(function(b){var e=b.getAttribute('data-i'),l=b.getAttribute('data-l');b.querySelectorAll('li').forEach(function(li){var t=own(li);if(!t||t.length>600)return;")
          .append("var x=document.createElement('input');x.type='checkbox';x.className='gpick';x.dataset.e=e;x.dataset.l=l;x.dataset.t=t;li.classList.add('gli');li.insertBefore(x,li.firstChild);n++;")
          .append("x.addEventListener('change',function(){li.classList.toggle('picked',x.checked);if(x.checked){var pk=b.querySelector('input.pk');if(pk&&!pk.checked){pk.checked=true;pick()}}defs()});")
          .append("li.addEventListener('click',function(ev){if(ev.target.closest('a,summary,input,details>:not(summary)'))return;if(ev.target.closest('li')!==li)return;x.checked=!x.checked;x.dispatchEvent(new Event('change'))})})});")
          .append("if(n){var g=document.createElement('div');g.className='gnote';g.textContent='Tick the meanings you want: they go into the short definition field; the ticked dictionaries still give the full definition.';document.body.insertBefore(g,document.body.firstChild)}}")
          // game controller: the framed definition (D-pad up / down), A ticks it
          .append("function padDef(d){var xs=Array.prototype.slice.call(document.querySelectorAll('input.gpick'));if(!xs.length)return 0;var cur=document.querySelector('input.gpick.padcur'),i=cur?xs.indexOf(cur):-1;if(cur){cur.classList.remove('padcur');(cur.closest('li')||cur.parentNode).classList.remove('padrow')}i=i<0?(d>0?0:xs.length-1):Math.max(0,Math.min(xs.length-1,i+d));var x=xs[i];x.classList.add('padcur');var r=x.closest('li')||x.parentNode;r.classList.add('padrow');r.scrollIntoView({block:'nearest'});return xs.length}")
          .append("function padSel(){var c=document.querySelector('input.gpick.padcur');if(!c)return 'none';c.click();return 'ok'}")
          .append("function showLang(l){document.querySelectorAll('.blk').forEach(function(b){b.style.display=((!l||b.getAttribute('data-l')===l)&&!b.getAttribute('data-off'))?'':'none'})}")
          .append("document.addEventListener('DOMContentLoaded',boxes)")
          .append("</script></head><body>");
        if (!r.found()) {
            sb.append("<div class=none>").append(haveDicts ? "No entry for this word in your dictionaries." :
                    "No dictionary yet: Settings → Dictionaries → Import a dictionary (the same wty zip files the PC uses).").append("</div>");
        }
        String last = null;
        int i = 0;
        for (Dictionaries.Hit h : r.hits) {
            String key = h.term + "|" + h.via;
            String gl = glossLang(h.dict, lang);
            sb.append("<div class=blk data-i=").append(i).append(DefLangs.on(lang, gl) ? "" : " data-off=1 style='display:none'").append(" data-l='").append(esc(gl)).append("'><div class=hd><input class=pk type=checkbox data-i=").append(i).append(on.contains(i) ? " checked" : "")
              .append(" onchange='pick()'><span class=tm>").append(esc(h.term)).append("</span>")
              .append(h.pos == null || h.pos.isEmpty() ? "" : "<span class=pos>" + esc(h.pos) + "</span>")
              .append("<span class=glang>").append(esc(gl.toUpperCase(Locale.ROOT))).append("</span><small>").append(esc(h.dict)).append("</small></div>");
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
