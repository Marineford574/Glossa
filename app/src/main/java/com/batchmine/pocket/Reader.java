package com.batchmine.pocket;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.batchmine.pocket.core.Book;
import com.batchmine.pocket.core.Subs;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Reading a book: one chapter at a time, words coloured and tappable, position remembered. */
public final class Reader extends Activity {
    private App app;
    private Book book;
    private Colourer col;
    private int chapter, firstPara;
    private ListView list;
    private TextView head;
    private float size = 19;
    private SharedPreferences sp;

    static int[] position(String id) {
        SharedPreferences p = App.get().prefs();
        return new int[]{p.getInt("rc_" + id, 0), p.getInt("rp_" + id, 0)};
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = App.get();
        sp = app.prefs();
        String id = getIntent().getStringExtra("id");
        try {
            book = Book.read(new File(app.dir("books"), id + ".json"));
        } catch (IOException | RuntimeException e) { app.report("Could not open book " + id, e); Ui.toast(this, "Could not open the book"); finish(); return; }
        if (book.chapters.isEmpty()) { Ui.toast(this, "This book has no text"); finish(); return; }
        col = new Colourer(app.lang().equals(book.lang) || book.lang.isEmpty() ? app.lang() : book.lang);
        col.colours = sp.getBoolean("colours", true);
        size = sp.getFloat("fs", 19);
        chapter = Math.max(0, Math.min(sp.getInt("rc_" + id, 0), book.chapters.size() - 1));
        firstPara = Math.max(0, sp.getInt("rp_" + id, 0));
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout bar = Ui.row(this);
        int p = Ui.dp(this, 8);
        bar.setPadding(p, p, p, p);
        bar.addView(Ui.button(this, "‹", v -> go(chapter - 1)));
        head = Ui.text(this, "", 15, Ui.TEXT);
        head.setGravity(Gravity.CENTER);
        head.setOnClickListener(v -> contents());
        bar.addView(Ui.weight(head, 1));
        bar.addView(Ui.button(this, "›", v -> go(chapter + 1)));
        root.addView(bar);
        list = new ListView(this);
        list.setDividerHeight(0);
        list.setBackgroundColor(Ui.BG);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout foot = Ui.row(this);
        foot.setPadding(p, p, p, p);
        foot.addView(Ui.weight(Ui.button(this, "A−", v -> font(-1)), 1));
        foot.addView(Ui.weight(Ui.button(this, "A+", v -> font(1)), 1));
        foot.addView(Ui.weight(Ui.button(this, "Colours", v -> { col.colours = !col.colours; sp.edit().putBoolean("colours", col.colours).apply(); draw(true); }), 1));
        foot.addView(Ui.weight(Ui.button(this, "Contents", v -> contents()), 1));
        foot.addView(Ui.weight(Ui.button(this, "Known…", v -> chapterKnown()), 1));
        root.addView(foot);
        setContentView(root);
        draw(false);
    }

    private void font(int d) {
        size = Math.max(13, Math.min(34, size + d * 2));
        sp.edit().putFloat("fs", size).apply();
        draw(true);
    }

    private void go(int c) {
        if (c < 0 || c >= book.chapters.size()) return;
        chapter = c;
        firstPara = 0;
        draw(false);
    }

    private void contents() {
        String[] names = new String[book.chapters.size()];
        for (int i = 0; i < names.length; i++) names[i] = (i == chapter ? "▶ " : "") + (i + 1) + ". " + book.chapters.get(i).title;
        Ui.dialog(this).setTitle("Contents").setItems(names, (d, i) -> go(i)).show();
    }

    private void save() {
        int pos = list == null ? 0 : list.getFirstVisiblePosition();
        sp.edit().putInt("rc_" + book.id, chapter).putInt("rp_" + book.id, pos).apply();
    }

    @Override protected void onPause() { super.onPause(); if (book != null && list != null) save(); }

    private void draw(boolean keep) {
        int at = keep ? list.getFirstVisiblePosition() : firstPara;
        Book.Chapter ch = book.chapters.get(chapter);
        head.setText((chapter + 1) + "/" + book.chapters.size() + " · " + ch.title);
        col.forget();
        list.setAdapter(new BaseAdapter() {
            private final java.util.Map<Integer, CharSequence> cache = new java.util.HashMap<>();
            @Override public int getCount() { return ch.paras.size(); }
            @Override public Object getItem(int i) { return ch.paras.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(Reader.this);
                t.setTextSize(size);
                t.setLineSpacing(0, 1.3f);
                t.setTextColor(Ui.TEXT);
                t.setTypeface(android.graphics.Typeface.SERIF);
                int pad = Ui.dp(Reader.this, 14);
                t.setPadding(pad, Ui.dp(Reader.this, 6), pad, Ui.dp(Reader.this, 6));
                t.setMovementMethod(LinkMovementMethod.getInstance());
                t.setHighlightColor(0x553584E4);
                CharSequence s = cache.get(i);
                if (s == null) {
                    s = col.spans(ch.paras.get(i), (word, start, end, text) -> tapped(word, start, text));
                    cache.put(i, s);
                }
                t.setText(s);
                return t;
            }
        });
        list.setSelection(Math.min(at, Math.max(0, ch.paras.size() - 1)));
        save();
    }

    private void tapped(String word, int at, String text) {
        Cards.Request r = new Cards.Request();
        r.lang = book.lang.isEmpty() ? app.lang() : book.lang;
        r.kind = "text";
        r.sentence = Colourer.sentenceAt(text, at);
        r.title = book.title;
        WordSheet.show(this, book.lang.isEmpty() ? app.lang() : book.lang, word, r, () -> draw(true));
    }

    /** Marks the unknown words of this chapter as known (after you have read it), with the list to untick first. */
    private void chapterKnown() {
        String lang = book.lang.isEmpty() ? app.lang() : book.lang;
        Ui.toast(this, "Counting the unknown words…");
        app.bg(() -> {
            Set<String> unk = new LinkedHashSet<>();
            for (String para : book.chapters.get(chapter).paras) {
                for (int[] w : Subs.words(para)) {
                    String word = para.substring(w[0], w[1]);
                    if (col.state(word).equals("u")) unk.add(word.toLowerCase(Locale.ROOT));
                }
            }
            app.post(() -> {
                if (unk.isEmpty()) { Ui.alert(this, "Known words", "No unknown words in this chapter."); return; }
                Ui.dialog(this).setTitle(unk.size() + " unknown words in this chapter")
                        .setMessage("Mark all of them as known? (Looked-up words stay as they are; you can undo each one in its word card.)")
                        .setPositiveButton("Mark as known", (d, i) -> { app.store(lang).markKnown(new HashSet<>(unk)); draw(true); })
                        .setNegativeButton("Cancel", null).show();
            });
        });
    }
}
