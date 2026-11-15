package com.batchmine.pocket;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.batchmine.pocket.core.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The AI helpers of the PC (grammar, a simpler sentence, examples, "does this meaning fit?", a summary of a chapter, a quiz of
 * your words). The AI runs on the PC (its Claude key or Ollama, Settings → AI there); the phone asks through the link, so it
 * needs the PC to be reachable.
 */
final class Ai {
    private Ai() { }

    /** Asks the PC; KIND is grammar / simplify / examples / check / summary / quiz. */
    private static Map<String, Object> ask(Map<String, Object> body) throws IOException {
        if (App.get().pc() == null) throw new IOException("Link the PC first (Settings): the AI runs there.");
        Map<String, Object> r = App.get().pc().post("/api/pocket/ai", body, 150000);
        if (r.get("error") != null) throw new IOException(Json.str(r.get("error"), "The AI did not answer"));
        return r;
    }

    private static Dialog thinking(Activity a, String title, TextView body) {
        LinearLayout l = Ui.col(a);
        l.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 16));
        body.setTextIsSelectable(true);
        body.setLineSpacing(0, 1.2f);
        l.addView(body);
        return Ui.sheet(a, title, Ui.scroll(a, l), false);
    }

    /** One answer in a sheet: "asking the AI …" first, then the text and who wrote it. */
    static void study(Activity a, String title, String kind, String lang, String word, String text, String mine) {
        TextView t = Ui.text(a, "asking the AI …", 16, Ui.DIM);
        Dialog d = thinking(a, title, t);
        App.get().bg(() -> {
            try {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("kind", kind); b.put("lang", lang); b.put("word", word == null ? "" : word); b.put("text", text == null ? "" : text); b.put("mine", mine == null ? "" : mine);
                Map<String, Object> r = ask(b);
                String out = Json.str(r.get("text"), "") + "\n\n— " + Json.str(r.get("who"), "AI");
                App.get().post(() -> { t.setTextColor(Ui.TEXT); t.setText(out); });
            } catch (IOException | RuntimeException e) {
                App.get().post(() -> { t.setTextColor(Ui.TEXT); t.setText(e.getMessage() == null ? e.toString() : e.getMessage()); });
            }
        });
    }

    /** The summary of a chapter (or any text) before you read it. */
    static void summary(Activity a, String lang, String text, boolean spoil) {
        TextView t = Ui.text(a, "asking the AI …", 16, Ui.DIM);
        Dialog d = thinking(a, "Before you read", t);
        App.get().bg(() -> {
            try {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("kind", "summary"); b.put("lang", lang); b.put("text", text.length() > 12000 ? text.substring(0, 12000) : text); b.put("what", "chapter"); b.put("spoil", spoil);
                Map<String, Object> r = ask(b);
                String out = Json.str(r.get("text"), "") + "\n\n— " + Json.str(r.get("who"), "AI");
                App.get().post(() -> { t.setTextColor(Ui.TEXT); t.setText(out); });
            } catch (IOException | RuntimeException e) {
                App.get().post(() -> { t.setTextColor(Ui.TEXT); t.setText(e.getMessage() == null ? e.toString() : e.getMessage()); });
            }
        });
    }

    /** A quiz of WORDS (each {word, sentence}); the PC picks recently mined ones when the list is empty. */
    @SuppressWarnings("unchecked")
    static void quiz(Activity a, String lang, List<Map<String, Object>> words) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), Ui.dp(a, 16));
        TextView first = Ui.text(a, "the AI is writing your quiz …", 16, Ui.DIM);
        box.addView(first);
        Ui.sheet(a, "Quiz: your words", Ui.scroll(a, box), false);
        App.get().bg(() -> {
            try {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("kind", "quiz"); b.put("lang", lang);
                if (words != null && !words.isEmpty()) b.put("words", new ArrayList<Object>(words));
                Map<String, Object> r = ask(b);
                List<Object> qs = r.get("questions") instanceof List ? (List<Object>) r.get("questions") : new ArrayList<>();
                App.get().post(() -> drawQuiz(a, box, qs, 0, new int[]{0}, Json.str(r.get("who"), "AI")));
            } catch (IOException | RuntimeException e) {
                App.get().post(() -> first.setText(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static void drawQuiz(Activity a, LinearLayout box, List<Object> qs, int i, int[] right, String who) {
        box.removeAllViews();
        if (i >= qs.size()) {
            TextView t = Ui.text(a, right[0] + " of " + qs.size() + " right", 22, Ui.TEXT);
            t.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 4));
            box.addView(t);
            box.addView(Ui.text(a, "Written by " + who, 13, Ui.DIM));
            return;
        }
        Map<String, Object> q = (Map<String, Object>) qs.get(i);
        List<Object> opts = q.get("options") instanceof List ? (List<Object>) q.get("options") : new ArrayList<>();
        int answer = (int) Json.num(q.get("answer"), 0);
        box.addView(Ui.text(a, "Question " + (i + 1) + " of " + qs.size(), 13, Ui.DIM));
        TextView s = Ui.text(a, Json.str(q.get("sentence"), ""), 20, Ui.TEXT);
        s.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 12));
        box.addView(s);
        TextView tr = Ui.text(a, Json.str(q.get("translation"), ""), 14, Ui.DIM);
        tr.setVisibility(View.GONE);
        TextView next = Ui.button(a, i + 1 < qs.size() ? "Next" : "Score", v -> drawQuiz(a, box, qs, i + 1, right, who));
        next.setVisibility(View.GONE);
        List<TextView> btns = new ArrayList<>();
        for (int k = 0; k < opts.size(); k++) {
            final int kk = k;
            TextView o = Ui.button(a, String.valueOf(opts.get(k)), null);
            o.setOnClickListener(v -> {
                for (int j = 0; j < btns.size(); j++) {
                    btns.get(j).setEnabled(false);
                    if (j == answer) btns.get(j).setBackground(Ui.round(0x663FB950, Ui.dp(a, 12)));
                    else if (j == kk) btns.get(j).setBackground(Ui.round(0x66F85149, Ui.dp(a, 12)));
                }
                if (kk == answer) right[0]++;
                tr.setVisibility(View.VISIBLE);
                next.setVisibility(View.VISIBLE);
            });
            btns.add(o);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(a, 8);
            box.addView(o, lp);
        }
        box.addView(tr);
        box.addView(next);
    }
}
