package com.batchmine.pocket;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.widget.LinearLayout;

import com.batchmine.pocket.core.Json;
import com.batchmine.pocket.core.PcLink;

import java.util.Locale;
import java.util.Map;

/** Feature 5: the conjugation table of an Italian verb, made by the PC (the same table its cards get) and shown in the word sheet. Needs the PC
 *  to be reachable; the answer is kept for the session. */
final class Conj {
    private Conj() { }

    private static final Map<String, String> KEEP = new java.util.HashMap<>();

    /** True for a word that can have a table here: Italian, PC linked, an infinitive (-are, -ere, -ire, -rre, or reflexive -si). */
    static boolean offered(String lang, String lemma) {
        return "it".equals(lang) && App.get().pc() != null && lemma != null && lemma.toLowerCase(Locale.ROOT).matches(".*(are|ere|ire|rre|arsi|ersi|irsi|rsi)");
    }

    static void show(Activity a, String lang, String verb) {
        String v = verb.toLowerCase(Locale.ROOT);
        String have = KEEP.get(v);
        if (have != null) { dialog(a, verb, have); return; }
        PcLink pc = App.get().pc();
        if (pc == null) { Ui.toast(a, "Link the PC first (Settings)"); return; }
        Ui.toast(a, "Asking the PC …");
        App.get().bg(() -> {
            try {
                Map<String, Object> r = pc.get("/api/pocket/conj?lang=" + PcLink.enc(lang) + "&word=" + PcLink.enc(v), 180000);
                String html = Json.str(r.get("html"), "");
                App.get().post(() -> {
                    if (html.isEmpty()) { Ui.toast(a, "No table for “" + verb + "”"); return; }
                    KEEP.put(v, html);
                    if (!Ui.gone(a)) dialog(a, verb, html);
                });
            } catch (java.io.IOException | RuntimeException e) {
                App.get().post(() -> { if (!Ui.gone(a)) Ui.toast(a, "The PC did not answer"); });
            }
        });
    }

    private static void dialog(Activity a, String verb, String html) {
        Dialog d = new Dialog(a, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = Ui.col(a);
        box.setBackground(Ui.round(Ui.BG, Ui.dp(a, 18)));
        int p = Ui.dp(a, 12);
        box.setPadding(p, p, p, p);
        LinearLayout top = Ui.row(a);
        top.addView(Ui.weight(Ui.title(a, verb), 1));
        top.addView(Ui.flat(a, "✕", v -> d.dismiss()));
        box.addView(top);
        WebView web = new WebView(a);
        web.setBackgroundColor(Ui.BG);
        web.getSettings().setBlockNetworkLoads(true);
        String css = "body{background:" + Ui.hex(Ui.BG) + ";color:" + Ui.hex(Ui.TEXT) + ";font:15px/1.5 sans-serif;margin:0;padding:0 2px 16px}"
                + ".conjHead{margin-bottom:10px;color:" + Ui.hex(Ui.DIM) + "}.conjGrid{display:block}.conjT{margin:0 0 14px;padding:8px 10px;background:" + Ui.hex(Ui.CARD)
                + ";border-radius:10px}.conjName{font-weight:bold;margin-bottom:4px;text-transform:uppercase;font-size:12px;letter-spacing:.06em;color:" + Ui.hex(Ui.DIM)
                + "}table{width:100%;border-collapse:collapse}td{padding:2px 0}.who{color:" + Ui.hex(Ui.DIM) + ";display:inline-block;min-width:3.2em}";
        web.loadDataWithBaseURL(null, "<html><head><meta name=viewport content='width=device-width, initial-scale=1'><style>" + css + "</style></head><body>" + html + "</body></html>", "text/html", "utf-8", null);
        box.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (a.getResources().getDisplayMetrics().heightPixels * 0.86));
            w.setGravity(Gravity.BOTTOM);
        }
        d.setOnDismissListener(x -> { try { web.destroy(); } catch (RuntimeException ignored) { } });
        d.show();
    }
}
