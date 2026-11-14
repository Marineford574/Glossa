package com.batchmine.pocket;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.batchmine.pocket.core.Book;
import com.batchmine.pocket.core.PcLink;
import com.batchmine.pocket.core.Subs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reading a book, laid out like the PC's Reader: a bar with the title (tap to rename), the language and the tools (listen, contents,
 * options); the chapter's text with its words coloured and tappable; under it the book's progress, ‹ Back / Next › and how many words
 * of this chapter you don't know yet (tap: the word list). Position remembered.
 */
public final class Reader extends Activity {
    private App app;
    private Book book;
    private Colourer col;
    private int chapter, firstPara;
    private ListView list;
    private TextView chapLine, unkText, posText;
    private FrameLayout bookBar;
    private View bookFill;
    private android.widget.Button backBtn, nextBtn;
    private LinearLayout undoBar;
    private WordBar wordBar;
    private float size = 19;
    private int base, count;                     // the page on screen: paragraphs base .. base+count-1 of the chapter
    private View topBarV, topLine, navBarV, topBox, bottomBox;
    private boolean barsShown = true;
    private android.widget.ImageView mangaImg;      // a manga from the PC: the page's picture above the text of its bubbles
    private int mangaShown = -1;
    private Runnable recolour;                // colours the words of the open page again without first showing it plain (after a card / known)
    private android.widget.ImageView fsTop;
    private SharedPreferences sp;
    private TextToSpeech tts;
    private boolean speaking;
    private int speakAt = -1;
    private android.widget.ImageView listenIcon;
    private final Map<String, Integer> unknown = new LinkedHashMap<>();   // this chapter's unknown words, most frequent first

    private static final String[] FONT_KEYS = {"serif", "sans", "light", "condensed", "mono"};
    private static final String[] FONT_NAMES = {"Serif (like a book)", "Sans-serif", "Sans-serif light", "Condensed", "Monospace"};
    private static final int[] PAGE_CHARS = {0, 1200, 2500, 5000};
    private static final String[] PAGE_NAMES = {"The whole chapter", "A little (about 1 200 characters)", "Some (about 2 500)", "A lot (about 5 000)"};

    private int fontIndex() {
        String k = sp.getString("rd_font", sp.getBoolean("rd_serif", true) ? "serif" : "sans");
        for (int i = 0; i < FONT_KEYS.length; i++) if (FONT_KEYS[i].equals(k)) return i;
        return 0;
    }

    private android.graphics.Typeface typeface() {
        switch (FONT_KEYS[fontIndex()]) {
            case "sans": return android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL);
            case "light": return android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL);
            case "condensed": return android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL);
            case "mono": return android.graphics.Typeface.MONOSPACE;
            default: return android.graphics.Typeface.SERIF;
        }
    }

    static int[] position(String id) {
        SharedPreferences p = App.get().prefs();
        return new int[]{p.getInt("rc_" + id, 0), p.getInt("rp_" + id, 0)};
    }

    private String lang() { return book.lang.isEmpty() ? app.lang() : book.lang; }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = App.get();
        sp = app.prefs();
        String id = getIntent().getStringExtra("id");
        try {
            book = Book.read(new File(app.dir("books"), id + ".json"));
        } catch (IOException | RuntimeException e) { app.report("Could not open book " + id, e); Ui.toast(this, "Could not open the book"); finish(); return; }
        String own = sp.getString("btitle_" + id, "");
        if (!own.isEmpty()) book.title = own;
        if (book.chapters.isEmpty()) { Ui.toast(this, "This book has no text"); finish(); return; }
        col = new Colourer(app.lang().equals(book.lang) || book.lang.isEmpty() ? app.lang() : book.lang);
        col.colours = app.flag("colours", true);
        size = sp.getFloat(app.lk("fs"), sp.getFloat("fs", 19));
        chapter = Math.max(0, Math.min(sp.getInt("rc_" + id, 0), book.chapters.size() - 1));
        firstPara = Math.max(0, sp.getInt("rp_" + id, 0));
        if (sp.getBoolean("rd_awake", false)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Ui.bars(this);
        // the text fills the screen; the bars lie over it and slide away while you scroll down, back while you scroll up
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Ui.BG);
        list = new ListView(this);
        list.setDividerHeight(0);
        list.setBackgroundColor(Ui.BG);
        list.setClipToPadding(false);
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            private int state, lastFirst = -1, lastTop, moved;
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int s) { state = s; moved = 0; }
            @Override public void onScroll(android.widget.AbsListView v, int first, int vis, int total) {
                progress();
                View c0 = v.getChildAt(0);
                int top = c0 == null ? 0 : c0.getTop();
                if (state != SCROLL_STATE_IDLE && lastFirst >= 0) {
                    int d = first == lastFirst ? lastTop - top : (first > lastFirst ? Ui.dp(Reader.this, 30) : -Ui.dp(Reader.this, 30));
                    moved = (moved > 0) == (d > 0) ? moved + d : d;          // the same direction adds up; a turn starts again
                    if (moved > Ui.dp(Reader.this, 24)) bars(false);
                    else if (moved < -Ui.dp(Reader.this, 16)) bars(true);
                }
                if (first + vis >= total && total > 0) {                    // the end of the page: Next must be reachable
                    View last = v.getChildAt(vis - 1);
                    if (last != null && last.getBottom() <= v.getHeight() - v.getPaddingBottom() + Ui.dp(Reader.this, 4) && state != SCROLL_STATE_IDLE) bars(true);
                }
                lastFirst = first;
                lastTop = top;
            }
        });
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (book.manga) {
            int h = getResources().getDisplayMetrics().heightPixels, split = Math.round(h * 0.6f);
            mangaImg = new android.widget.ImageView(this);
            mangaImg.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            mangaImg.setBackgroundColor(Ui.BG);
            mangaImg.setOnClickListener(v -> bars(!barsShown));                      // a tap on the picture shows or hides the bars
            frame.addView(mangaImg, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, split, Gravity.TOP));
            listLp.topMargin = split;
        }
        frame.addView(list, listLp);

        LinearLayout top = Ui.col(this);
        top.setBackgroundColor(Ui.BG);
        top.setClickable(true);
        topBarV = topBar();
        top.addView(topBarV);
        chapLine = Ui.text(this, "", 13, Ui.DIM);
        chapLine.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 6));
        chapLine.setSingleLine(true);
        chapLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chapLine.setOnClickListener(v -> contents());
        top.addView(chapLine);
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        top.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        topLine = line;
        topBox = top;
        frame.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = Ui.col(this);
        bottom.setClickable(true);
        undoBar = Ui.row(this);
        undoBar.setVisibility(View.GONE);
        undoBar.setBackgroundColor(Ui.BG);
        bottom.addView(undoBar);
        wordBar = new WordBar(this, this::soft);
        bottom.addView(wordBar.view());
        navBarV = navBar();
        navBarV.setBackgroundColor(Ui.BG);
        bottom.addView(navBarV);
        bottomBox = bottom;
        frame.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        // the text starts under the top bar and ends above the bottom one (it scrolls under them once they slide away)
        View.OnLayoutChangeListener pads = (v, l1, t1, r1, b1, l0, t0, r0, b0) -> {
            int pt = (book.manga ? 0 : topBox.getHeight()) + Ui.dp(this, 8), pb = bottomBox.getHeight() + Ui.dp(this, 24);
            if (list.getPaddingTop() != pt || list.getPaddingBottom() != pb) list.post(() -> list.setPadding(0, pt, 0, pb));
        };
        top.addOnLayoutChangeListener(pads);
        bottom.addOnLayoutChangeListener(pads);
        setContentView(frame);
        draw(false);
    }

    // ---------------------------------------------------------------- the bars
    private View iconBtn(int res, String what, View.OnClickListener l) {
        android.widget.ImageView iv = Ui.icon(this, res, 22, Ui.TEXT);
        iv.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        iv.setBackground(Ui.press(0x00000000, 0, Ui.dp(this, 99)));
        iv.setContentDescription(what);
        iv.setOnClickListener(l);
        iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        return iv;
    }

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 2));
        TextView back = Ui.text(this, "‹", 26, Ui.TEXT);
        back.setGravity(Gravity.CENTER);
        back.setBackground(Ui.press(0x00000000, 0, Ui.dp(this, 99)));
        back.setContentDescription("Back to the books");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 44)));
        TextView t = Ui.text(this, book.title, 17, Ui.TEXT);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setOnClickListener(v -> rename(t));
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pill = Ui.pill(this, lang().toUpperCase(Locale.ROOT), Ui.DIM, Ui.CARD2);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.leftMargin = Ui.dp(this, 6);
        pl.rightMargin = Ui.dp(this, 2);
        bar.addView(pill, pl);
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swl.rightMargin = Ui.dp(this, 4);
        bar.addView(Stopwatch.chip(this, "read", Ui.DIM, Ui.CARD2), swl);
        View ls = iconBtn(R.drawable.ic_rd_listen, "Listen", v -> listen());
        listenIcon = (android.widget.ImageView) ls;
        bar.addView(ls);
        bar.addView(iconBtn(R.drawable.ic_pl_items, "Contents", v -> contents()));
        bar.addView(iconBtn(R.drawable.ic_pl_tune, "Reader options", v -> options()));
        return bar;
    }

    private View navBar() {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 10));
        // the book's progress (the PC's .rprog line)
        bookBar = new FrameLayout(this);
        bookBar.setBackground(Ui.round(Ui.mix(Ui.CARD, Ui.TEXT, 0.12f), Ui.dp(this, 2)));
        bookBar.setClipToOutline(true);
        bookFill = new View(this);
        bookFill.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 2)));
        bookBar.addView(bookFill, new FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(bookBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 8), 0, 0);
        backBtn = Ui.button(this, "‹ Back", v -> step(false));
        nextBtn = Ui.primary(this, "Next ›", v -> step(true));
        LinearLayout mid = Ui.col(this);
        mid.setGravity(Gravity.CENTER);
        posText = Ui.text(this, "", 13, Ui.TEXT);
        posText.setGravity(Gravity.CENTER);
        unkText = Ui.text(this, "", 12, Ui.DIM);
        unkText.setGravity(Gravity.CENTER);
        mid.addView(posText);
        mid.addView(unkText);
        mid.setBackground(Ui.press(0x00000000, 0, Ui.dp(this, Ui.RS)));
        mid.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        mid.setOnClickListener(v -> wordList());
        r.addView(backBtn);
        r.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(nextBtn);
        box.addView(r);
        return box;
    }

    private void progress() {
        if (bookBar == null || list == null) return;
        int before = 0, all = Math.max(1, book.paraCount());
        for (int i = 0; i < chapter && i < book.chapters.size(); i++) before += book.chapters.get(i).paras.size();
        float f = (before + base + list.getFirstVisiblePosition()) / (float) all;
        int w = Math.round(bookBar.getWidth() * Math.max(0, Math.min(1, f)));
        if (bookFill.getLayoutParams().width != w) { bookFill.getLayoutParams().width = w; bookFill.requestLayout(); }
        List<int[]> pg = pageChars() > 0 ? pages() : null;
        posText.setText((chapter + 1) + " / " + book.chapters.size() + (pg == null ? "" : " · page " + (pageOf(pg, base) + 1) + "/" + pg.size()) + " · " + Math.round(f * 100) + " %");
    }

    // ---------------------------------------------------------------- the bars slide away
    /** Shows or hides the bars over the text (scrolling down hides them, scrolling up or the end of the page brings them back). */
    private void bars(boolean show) {
        if (show == barsShown || topBox == null) return;
        barsShown = show;
        topBox.animate().translationY(show ? 0 : -topBox.getHeight()).setDuration(180).start();
        bottomBox.animate().translationY(show ? 0 : bottomBox.getHeight()).setDuration(180).start();
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) { return Pad.key(this, e, "reader", this::pad) || super.dispatchKeyEvent(e); }

    @Override public boolean dispatchGenericMotionEvent(android.view.MotionEvent e) { return Pad.motion(this, e, "reader", this::pad) || super.dispatchGenericMotionEvent(e); }

    /** The controller: pages, scrolling, picking a word of the page. */
    private boolean pad(String a) {
        switch (a) {
            case "next": step(true); return true;
            case "prev": step(false); return true;
            case "scrollUp": case "scrollDown": list.smoothScrollBy((int) (list.getHeight() * 0.6f) * (a.equals("scrollUp") ? -1 : 1), 250); return true;
            case "pick": {
                List<TextView> tvs = new ArrayList<>();
                for (int c = 0; c < list.getChildCount(); c++) if (list.getChildAt(c) instanceof TextView) tvs.add((TextView) list.getChildAt(c));
                if (!Pad.Picker.start(this, tvs, null)) Ui.toast(this, "No words on this page yet");
                return true;
            }
            case "back": onBackPressed(); return true;
            default: return false;
        }
    }

    @Override public void onBackPressed() { if (wordBar != null && wordBar.showing()) wordBar.hide(); else if (!barsShown) bars(true); else super.onBackPressed(); }

    // ---------------------------------------------------------------- pages
    /** How much of a chapter is on one page: 0 = the whole chapter, otherwise about this many characters. */
    private int pageChars() { return sp.getInt("rd_page", 0); }

    /** The chapter cut into pages of whole paragraphs, each at least pageChars() long (the last may be shorter). */
    private List<int[]> pages() {
        List<String> ps = book.chapters.get(chapter).paras;
        List<int[]> out = new ArrayList<>();
        int n = pageChars();
        if (n <= 0 || ps.isEmpty()) { out.add(new int[]{0, ps.size()}); return out; }
        int from = 0, len = 0;
        for (int i = 0; i < ps.size(); i++) {
            len += ps.get(i).length();
            if (len >= n) { out.add(new int[]{from, i + 1}); from = i + 1; len = 0; }
        }
        if (from < ps.size()) {
            if (!out.isEmpty() && len < n / 3) out.get(out.size() - 1)[1] = ps.size();   // a tiny tail joins the page before
            else out.add(new int[]{from, ps.size()});
        }
        return out;
    }

    private int pageOf(List<int[]> pg, int para) {
        for (int i = 0; i < pg.size(); i++) if (para < pg.get(i)[1]) return i;
        return pg.size() - 1;
    }

    /** Next / Back: a page when pages are on, otherwise a chapter; at the end of a chapter on to the next one. */
    private void step(boolean forward) {
        List<int[]> pg = pages();
        int p = pageOf(pg, base);
        if (forward) {
            if (p + 1 < pg.size()) { stopListening(); firstPara = pg.get(p + 1)[0]; draw(false); list.setSelection(0); }
            else go(chapter + 1, true);
        } else {
            if (p > 0) { stopListening(); firstPara = pg.get(p - 1)[0]; draw(false); }
            else if (chapter > 0) { go(chapter - 1, false); if (pageChars() > 0) { firstPara = -1; draw(false); } }
        }
    }

    // ---------------------------------------------------------------- moving
    private void go(int c, boolean forward) {
        if (c < 0 || c >= book.chapters.size()) return;
        stopListening();
        if (forward && sp.getBoolean("rd_pageknown", false)) markChapterKnownQuietly();
        chapter = c;
        firstPara = 0;
        draw(false);
    }

    /** "Turning the page marks the rest as known" (the PC's option): the unknown words of the chapter you leave, with Undo. */
    private void markChapterKnownQuietly() {
        Set<String> words = new HashSet<>(unknown.keySet());
        if (words.isEmpty()) return;
        app.store(lang()).markKnown(words);
        int left = chapter;
        undoBar.removeAllViews();
        undoBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 12), 0);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.addView(Ui.weight(Ui.text(this, words.size() + " word(s) of chapter " + (left + 1) + " marked known", 13, Ui.DIM), 1));
        undoBar.addView(Ui.small(this, "Undo", v -> { app.store(lang()).markUnknown(words); undoBar.setVisibility(View.GONE); soft(); }));
        undoBar.setVisibility(View.VISIBLE);
        undoBar.postDelayed(() -> undoBar.setVisibility(View.GONE), 12000);
    }

    private void rename(TextView t) {
        android.widget.EditText in = Ui.input(this, book.title, "Title");
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16));
        box.addView(in);
        android.app.Dialog[] d = new android.app.Dialog[1];
        Ui.add(box, Ui.buttons(this, Ui.flat(this, "Cancel", v -> Ui.dismiss(d[0])), Ui.primary(this, "Rename", v -> {
            String s = in.getText().toString().trim();
            if (s.isEmpty()) return;
            book.title = s;
            sp.edit().putString("btitle_" + book.id, s).apply();
            t.setText(s);
            Ui.dismiss(d[0]);
        })), 12);
        d[0] = Ui.sheet(this, "Rename the book", box, false);
    }

    /** The chapters, the one you are in marked; tap one to go there (the PC's ☰ Contents). */
    private void contents() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), Ui.dp(this, 12));
        android.app.Dialog[] d = new android.app.Dialog[1];
        View cur = null;
        for (int i = 0; i < book.chapters.size(); i++) {
            final int k = i;
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
            boolean here = i == chapter;
            r.setBackground(Ui.press(here ? Ui.mix(Ui.BG, Ui.ACCENT, 0.16f) : 0x00000000, 0, Ui.dp(this, Ui.RS)));
            TextView t = Ui.text(this, book.chapters.get(i).title, 15, here ? Ui.ACCENT : Ui.TEXT);
            if (here) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.addView(Ui.text(this, String.valueOf(i + 1), 12, Ui.DIM));
            r.setOnClickListener(v -> { Ui.dismiss(d[0]); go(k, false); });
            l.addView(r);
            if (here) cur = r;
        }
        android.widget.ScrollView sv = Ui.scroll(this, l);
        d[0] = Ui.sheet(this, "Contents", sv, true);
        View c0 = cur;
        if (c0 != null) sv.post(() -> sv.scrollTo(0, Math.max(0, c0.getTop() - sv.getHeight() / 3)));
    }

    /** Text size, font, colours and the page-turn marking: the PC's Reader options, as a sheet. */
    private void options() {
        android.app.Dialog[] opts = new android.app.Dialog[1];
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        l.addView(Ui.section(this, "Text"));
        LinearLayout g = Ui.group(this);
        LinearLayout sz = Ui.row(this);
        TextView val = Ui.text(this, Math.round(size) + "", 15, Ui.TEXT);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(Ui.dp(this, 36));
        sz.addView(Ui.small(this, "A−", v -> { font(-1); val.setText(Math.round(size) + ""); }));
        sz.addView(val);
        sz.addView(Ui.small(this, "A+", v -> { font(1); val.setText(Math.round(size) + ""); }));
        Ui.srow(g, "Text size", null, sz, null);
        int fi = fontIndex();
        Ui.pickRow(g, "Font", FONT_NAMES[fi], v -> Ui.choose(this, "Font", FONT_NAMES, fontIndex(), i -> {
            sp.edit().putString("rd_font", FONT_KEYS[i]).apply();
            Ui.dismiss(opts[0]);
            draw(true);
            options();
        }));
        int pc = pageChars();
        int pi = 0;
        for (int i = 0; i < PAGE_CHARS.length; i++) if (PAGE_CHARS[i] == pc) pi = i;
        final int pcur = pi;
        Ui.pickRow(g, "On one page", PAGE_NAMES[pi], v -> Ui.choose(this, "How much is on one page", PAGE_NAMES, pcur, i -> {
            sp.edit().putInt("rd_page", PAGE_CHARS[i]).apply();
            Ui.dismiss(opts[0]);
            firstPara = base + (list == null ? 0 : list.getFirstVisiblePosition());
            draw(false);
            options();
        }));
        l.addView(g);
        l.addView(Ui.section(this, "Words"));
        LinearLayout g2 = Ui.group(this);
        Ui.switchRow(g2, "Colour the unknown words", "Off: plain text; tapping a word still opens its card.", col.colours, (b, on) -> {
            col.colours = on;
            sp.edit().putBoolean(app.lk("colours"), on).apply();
            draw(true);
        });
        Ui.switchRow(g2, "Next › marks the rest as known", "Going to the next chapter adds its still-coloured words to your known words (the ones you looked up or made a card of stay). Undo appears for a while.",
                sp.getBoolean("rd_pageknown", false), (b, on) -> sp.edit().putBoolean("rd_pageknown", on).apply());
        Ui.pickRow(g2, "Mark this chapter's words as known…", "", v -> chapterKnown());
        l.addView(g2);
        l.addView(Ui.section(this, "Screen"));
        LinearLayout g3 = Ui.group(this);
        Ui.switchRow(g3, "Open the word card at once", "Tapping a word opens its whole card (as on the PC) instead of the small bar at the bottom.", app.flag("rd_popup", false), (b, on) -> sp.edit().putBoolean("rd_popup", on).apply());
        Ui.switchRow(g3, "Keep the screen on", "While a book is open.", sp.getBoolean("rd_awake", false), (b, on) -> {
            sp.edit().putBoolean("rd_awake", on).apply();
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        l.addView(g3);
        opts[0] = Ui.sheet(this, "Reader options", Ui.scroll(this, l), true);
    }

    private void font(int d) {
        size = Math.max(13, Math.min(34, size + d * 2));
        sp.edit().putFloat(app.lk("fs"), size).apply();
        draw(true);
    }

    // ---------------------------------------------------------------- the word list (the PC's side panel)
    private void wordList() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        android.app.Dialog[] d = new android.app.Dialog[1];
        if (unknown.isEmpty()) l.addView(Ui.hint(this, "No unknown words in this chapter."));
        else {
            l.addView(Ui.hint(this, "This chapter's words you don't know yet, the most frequent first. Tap one to go to it."));
            LinearLayout g = Ui.group(this);
            int n = 0;
            for (Map.Entry<String, Integer> e : unknown.entrySet()) {
                if (++n > 300) break;
                String w = e.getKey();
                Ui.srow(g, w, null, Ui.text(this, e.getValue() + "×", 13, Ui.DIM), v -> { Ui.dismiss(d[0]); findWord(w); });
            }
            l.addView(g);
            Ui.add(l, Ui.button(this, "Mark all " + unknown.size() + " as known…", v -> { Ui.dismiss(d[0]); chapterKnown(); }), 12);
        }
        d[0] = Ui.sheet(this, "Unknown words · chapter " + (chapter + 1), Ui.scroll(this, l), !unknown.isEmpty());
    }

    private void findWord(String w) {
        List<String> ps = book.chapters.get(chapter).paras;
        for (int i = 0; i < ps.size(); i++) {
            for (int[] t : Subs.words(ps.get(i))) {
                if (ps.get(i).substring(t[0], t[1]).equalsIgnoreCase(w)) {
                    if (i < base || i >= base + count) { firstPara = i; draw(false); }
                    else list.setSelection(i - base);
                    return;
                }
            }
        }
    }

    /** Counts this chapter's unknown words (off the screen's thread), for the line under the text and the word list. */
    private void countUnknown() {
        int ch = chapter;
        List<String> paras = book.chapters.get(ch).paras;
        app.bg(() -> {
            Map<String, Integer> c = new LinkedHashMap<>();
            for (String para : paras) {
                for (int[] w : Subs.words(para)) {
                    String word = para.substring(w[0], w[1]);
                    if (word.length() < 2 || !col.state(word).equals("u")) continue;
                    c.merge(word.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
            }
            List<Map.Entry<String, Integer>> es = new ArrayList<>(c.entrySet());
            es.sort((a, b) -> b.getValue() - a.getValue());
            app.post(() -> {
                if (ch != chapter) return;
                unknown.clear();
                for (Map.Entry<String, Integer> e : es) unknown.put(e.getKey(), e.getValue());
                unkText.setText(unknown.isEmpty() ? "nothing new in this chapter" : unknown.size() + " unknown in this chapter");
            });
        });
    }

    // ---------------------------------------------------------------- listening (the phone's voice reads from where you are)
    private void listen() {
        if (speaking) { stopListening(); return; }
        if (tts == null) {
            Ui.toast(this, "Starting the voice…");
            tts = new TextToSpeech(this, st -> {
                if (st != TextToSpeech.SUCCESS) { app.post(() -> Ui.alert(this, "Listen", "The phone has no text-to-speech voice. Install one in the phone's settings (Accessibility → Text-to-speech).")); tts = null; return; }
                int r = tts.setLanguage(new Locale(lang()));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                    app.post(() -> Ui.toast(this, "No " + lang().toUpperCase(Locale.ROOT) + " voice on this phone: install one in the text-to-speech settings"));
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) { }
                    @Override public void onDone(String id) { app.post(() -> { if (speaking) speak(speakAt + 1); }); }
                    @Override public void onError(String id) { app.post(Reader.this::stopListening); }
                });
                app.post(() -> speak(base + list.getFirstVisiblePosition()));
            });
            return;
        }
        speak(base + list.getFirstVisiblePosition());
    }

    private void speak(int para) {
        List<String> ps = book.chapters.get(chapter).paras;
        if (para >= ps.size()) {
            if (chapter + 1 < book.chapters.size()) { chapter++; firstPara = 0; draw(false); speaking = true; listenOn(true); speak(0); }
            else stopListening();
            return;
        }
        if (para < base || para >= base + count) { firstPara = para; draw(false); }   // on to the next page
        speaking = true;
        listenOn(true);
        speakAt = para;
        int rel = para - base;
        if (rel < list.getFirstVisiblePosition() || rel > list.getLastVisiblePosition() - 1) list.smoothScrollToPositionFromTop(rel, Ui.dp(this, 24));
        tts.speak(ps.get(para), TextToSpeech.QUEUE_FLUSH, null, "p" + para);
        if (list.getAdapter() instanceof BaseAdapter) ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    private void stopListening() {
        boolean was = speaking;
        speaking = false;
        speakAt = -1;
        if (tts != null) tts.stop();
        listenOn(false);
        if (was && list.getAdapter() instanceof BaseAdapter) ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
    }

    private void listenOn(boolean on) {
        if (listenIcon == null) return;
        listenIcon.setColorFilter(on ? Ui.ON_ACC : Ui.TEXT);
        listenIcon.setBackground(on ? Ui.round(Ui.ACCENT, Ui.dp(this, 99)) : Ui.press(0x00000000, 0, Ui.dp(this, 99)));
    }

    // ---------------------------------------------------------------- the text
    private void save() {
        int pos = list == null ? 0 : base + list.getFirstVisiblePosition();
        int before = 0, all = Math.max(1, book.paraCount());
        for (int i = 0; i < chapter && i < book.chapters.size(); i++) before += book.chapters.get(i).paras.size();
        sp.edit().putInt("rc_" + book.id, chapter).putInt("rp_" + book.id, pos)
                .putFloat("rf_" + book.id, (before + pos) / (float) all).putLong("rt_" + book.id, System.currentTimeMillis()).apply();
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { Stopwatch.active(); return super.dispatchTouchEvent(e); }

    @Override protected void onResume() { super.onResume(); Stopwatch.start("read", null); }

    @Override protected void onPause() { super.onPause(); Stopwatch.stop(); if (book != null && list != null) save(); }

    @Override protected void onDestroy() { if (tts != null) { tts.stop(); tts.shutdown(); tts = null; } super.onDestroy(); }

    private void draw(boolean keep) {
        Book.Chapter ch = book.chapters.get(chapter);
        List<int[]> pg = pages();
        int at = keep ? base + list.getFirstVisiblePosition() : firstPara;
        boolean lastTop = at < 0;
        at = Math.max(0, Math.min(at < 0 ? Integer.MAX_VALUE / 2 : at, Math.max(0, ch.paras.size() - 1)));
        int pgi = pageOf(pg, at);
        base = pg.get(pgi)[0];
        count = pg.get(pgi)[1] - base;
        if (lastTop) at = base;
        showMangaPage(ch);
        chapLine.setText("Chapter " + (chapter + 1) + " of " + book.chapters.size() + " · " + ch.title);
        boolean canBack = chapter > 0 || pgi > 0, canNext = chapter < book.chapters.size() - 1 || pgi + 1 < pg.size();
        backBtn.setEnabled(canBack);
        backBtn.setAlpha(canBack ? 1f : 0.4f);
        nextBtn.setEnabled(canNext);
        nextBtn.setAlpha(canNext ? 1f : 0.4f);
        unkText.setText("counting…");
        col.forget();
        final android.graphics.Typeface face = typeface();
        final java.util.Map<Integer, CharSequence> cache = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Set<Integer> asked = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        BaseAdapter ad = new BaseAdapter() {

            /** Colours paragraphs FROM..TO off the screen's thread (the dictionary is read from disk), then updates the lines on screen. */
            private void colour(int from, int to) {
                for (int k = from; k <= to && k < base + count; k++) if (k >= 0) asked.add(k);
                final BaseAdapter me = this;
                app.bg(() -> {
                    for (int k = Math.max(0, from); k <= to && k < base + count; k++) {
                        if (list.getAdapter() != me) return;                 // another chapter was opened meanwhile
                        if (cache.containsKey(k)) continue;
                        CharSequence cs = col.spans(ch.paras.get(k), (word, start, end, text) -> tapped(word, start, text));
                        cache.put(k, cs);
                        final int kk = k;
                        app.post(() -> {
                            if (list.getAdapter() != me) return;
                            for (int c = 0; c < list.getChildCount(); c++) {
                                View child = list.getChildAt(c);
                                if (child instanceof TextView && Integer.valueOf(kk).equals(child.getTag())) ((TextView) child).setText(cache.get(kk));
                            }
                        });
                    }
                });
            }

            @Override public int getCount() { return count; }
            @Override public Object getItem(int i) { return ch.paras.get(base + i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                TextView t = v instanceof TextView ? (TextView) v : new TextView(Reader.this);
                t.setTextSize(size);
                t.setLineSpacing(0, 1.35f);
                t.setTextColor(Ui.TEXT);
                t.setTypeface(face);
                int pad = Ui.dp(Reader.this, 18);
                t.setPadding(pad, Ui.dp(Reader.this, 6), pad, Ui.dp(Reader.this, 6));
                t.setMovementMethod(LinkMovementMethod.getInstance());
                t.setHighlightColor(0x553584E4);
                final int k = base + i;
                t.setBackgroundColor(k == speakAt ? Ui.mix(Ui.BG, Ui.ACCENT, 0.12f) : 0x00000000);
                t.setTag(k);
                CharSequence s = cache.get(k);
                if (s != null) t.setText(s);
                else {
                    t.setText(ch.paras.get(k));                              // at once, plain; the colours follow a moment later
                    if (!asked.contains(k)) colour(k, k + 12);                // and the next paragraphs are got ready as you read on
                }
                return t;
            }
        };
        list.setAdapter(ad);
        // after a card or "known": the page keeps its colours; the paragraphs are coloured again off screen and only then swapped in, so
        // only the words that changed change
        recolour = () -> app.bg(() -> {
            if (list.getAdapter() != ad) return;
            col.forget();
            java.util.Map<Integer, CharSequence> fresh = new java.util.HashMap<>();
            for (Integer k : new ArrayList<>(cache.keySet())) {
                if (list.getAdapter() != ad) return;
                fresh.put(k, col.spans(ch.paras.get(k), (word, start, end, text) -> tapped(word, start, text)));
            }
            app.post(() -> {
                if (list.getAdapter() != ad) return;
                cache.putAll(fresh);
                for (int c = 0; c < list.getChildCount(); c++) {
                    View child = list.getChildAt(c);
                    Object tag = child.getTag();
                    if (child instanceof TextView && tag instanceof Integer && fresh.containsKey(tag)) ((TextView) child).setText(fresh.get(tag));
                }
                countUnknown();
            });
        });
        list.setSelection(Math.max(0, Math.min(at - base, count - 1)));
        list.post(this::progress);
        countUnknown();
        save();
    }

    /** A manga from the PC: the picture of the page (chapter) being read, from a copy kept on the phone, else asked of the PC (the next page too). */
    private void showMangaPage(Book.Chapter ch) {
        if (mangaImg == null || ch.page <= 0) return;
        mangaShown = ch.page;
        mangaPicture(ch.page, true);
        mangaPicture(ch.page + 1, false);
    }

    private void mangaPicture(int page, boolean show) {
        File f = new File(app.dir("manga"), book.id + "_" + page + ".jpg");
        Runnable put = () -> {
            if (!show || mangaShown != page || mangaImg == null) return;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(f.getPath());
            if (bm != null) mangaImg.setImageBitmap(bm);
        };
        if (f.length() > 500) { put.run(); return; }
        if (show) mangaImg.setImageDrawable(null);
        PcLink pc = app.pc();
        if (pc == null || !book.id.startsWith("pc")) { if (show) Ui.toast(this, "Link the PC to see the picture of this page"); return; }
        app.bg(() -> {
            try { pc.download("/api/pocket/mangapage?key=" + book.id.substring(2) + "&n=" + page + "&w=900", f, null); }
            catch (IOException | RuntimeException e) { f.delete(); return; }
            if (f.length() > 500) app.post(() -> { if (!isFinishing()) put.run(); });
        });
    }

    /** After a card or a word marked known: the colours stay; a moment later only the words that changed change. */
    private void soft() {
        if (recolour == null || list == null) { draw(true); return; }
        list.removeCallbacks(recolour);
        list.postDelayed(recolour, 700);
    }

    private void tapped(String word, int at, String text) {
        Cards.Request r = new Cards.Request();
        r.lang = lang();
        r.kind = "text";
        r.sentence = Colourer.sentenceAt(text, at);
        String[] sents = text.split("(?<=[.!?…])\\s+");
        int si = -1, pos = 0;
        for (int k = 0; k < sents.length; k++) {
            int idx = text.indexOf(sents[k], pos);
            if (idx < 0) continue;
            pos = idx + sents[k].length();
            if (at < pos && si < 0) si = k;
        }
        for (int k = 1; k <= 3 && si >= 0; k++) {
            if (si - k >= 0) r.before.add(new Cards.Ctx(sents[si - k], 0, 0));
            if (si + k < sents.length) r.after.add(new Cards.Ctx(sents[si + k], 0, 0));
        }
        r.title = book.title;
        if (!app.flag("rd_popup", false)) { bars(true); wordBar.show(lang(), word, r); }   // a bar at the bottom first; its ⌃ opens the whole sheet
        else WordSheet.show(this, lang(), word, r, this::soft);                          // Settings: the word card opens at once
    }

    /** Marks the unknown words of this chapter as known (after you have read it), with the list to untick first. */
    private void chapterKnown() {
        if (unknown.isEmpty()) { Ui.alert(this, "Known words", "No unknown words in this chapter."); return; }
        Set<String> unk = new LinkedHashSet<>(unknown.keySet());
        Ui.dialog(this).setTitle(unk.size() + " unknown words in this chapter")
                .setMessage("Mark all of them as known? (Looked-up words stay as they are; you can undo each one in its word card.)")
                .setPositiveButton("Mark as known", (d, i) -> { app.store(lang()).markKnown(new HashSet<>(unk)); soft(); })
                .setNegativeButton("Cancel", null).show();
    }
}
