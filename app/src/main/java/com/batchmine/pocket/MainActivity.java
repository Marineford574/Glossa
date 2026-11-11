package com.batchmine.pocket;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.batchmine.pocket.core.Book;
import com.batchmine.pocket.core.Dict;
import com.batchmine.pocket.core.Json;
import com.batchmine.pocket.core.PcLink;
import com.batchmine.pocket.core.WordStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The main screen: Home (continue, shelves), Books, Videos, Words and Settings, with the language and the sync button on top. */
public final class MainActivity extends Activity {
    static final int REQ_BOOK = 1, REQ_VIDEO = 2, REQ_DICT = 3;
    private App app;
    private FrameLayout body;
    private TextView langBtn, status;
    private String tab = "home";
    private static volatile boolean syncing;
    private final List<View> tabs = new ArrayList<>();
    private android.widget.ImageView gear;
    private String before = "home";                             // the tab to go back to from Settings

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = App.get();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        Ui.bars(this);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 12);
        LinearLayout top = Ui.row(this);
        top.setPadding(p, p, p, 4);
        TextView name = Ui.text(this, "Glossa", 24, Ui.TEXT);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(Ui.weight(name, 1));
        langBtn = Ui.button(this, app.lang().toUpperCase(Locale.ROOT), v -> pickLang());
        top.addView(langBtn);
        top.addView(Ui.primary(this, "Sync", v -> sync()));
        // Settings is not a tab: a gear in the corner, like the gear in the PC's side-menu foot
        gear = Ui.icon(this, R.drawable.ic_nav_gear, 24, Ui.DIM);
        gear.setContentDescription("Settings");
        int gp = Ui.dp(this, 10);
        gear.setPadding(gp, gp, gp, gp);
        gear.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        glp.leftMargin = Ui.dp(this, 4);
        top.addView(gear, glp);
        root.addView(top);
        status = Ui.hint(this, "");
        status.setPadding(p, 0, p, 4);
        root.addView(status);
        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // bottom bar: the PC side-menu icons (ICON.home / film / book / letters in mine_gui.js) with their names under them
        View line = new View(this);
        line.setBackgroundColor(Ui.CARD2);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.CARD);
        int[] icons = {R.drawable.ic_nav_home, R.drawable.ic_nav_film, R.drawable.ic_nav_book, R.drawable.ic_nav_letters};
        String[][] names = {{"home", "Home"}, {"videos", "Videos"}, {"books", "Books"}, {"words", "Words"}};
        for (int k = 0; k < names.length; k++) {
            String[] t = names[k];
            LinearLayout v = Ui.col(this);
            v.setGravity(Gravity.CENTER);
            v.setPadding(0, Ui.dp(this, 7), 0, Ui.dp(this, 7));
            v.setTag(t[0]);
            v.setContentDescription(t[1]);
            android.widget.ImageView ic = Ui.icon(this, icons[k], 22, Ui.DIM);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 28));
            ic.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
            v.addView(ic, ilp);
            TextView lb = Ui.text(this, t[1], 12, Ui.DIM);
            lb.setGravity(Gravity.CENTER);
            lb.setPadding(0, Ui.dp(this, 2), 0, 0);
            v.addView(lb);
            v.setOnClickListener(x -> { tab = t[0]; show(); });
            bar.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabs.add(v);
        }
        root.addView(bar);
        setContentView(root);
        if (b != null) { tab = b.getString("tab", "home"); before = b.getString("before", "home"); }
        sharedText(getIntent());
        File crash = new File(getFilesDir(), "crash.txt");
        if (crash.isFile()) {
            File seen = new File(getFilesDir(), "crash-seen.txt");
            seen.delete();
            crash.renameTo(seen);
            problemReport("Something went wrong last time", seen);
        }
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); sharedText(i); }

    /** Text sent here from another app (selected text → Glossa, or Share): its first word is looked up, the text is the sentence. */
    private void sharedText(Intent i) {
        if (i == null) return;
        CharSequence t = null;
        if (Intent.ACTION_PROCESS_TEXT.equals(i.getAction())) t = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        else if (Intent.ACTION_SEND.equals(i.getAction())) t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        i.setAction(Intent.ACTION_MAIN);                           // handled once
        if (t == null) return;
        String text = t.toString().trim().replaceAll("\\s+", " ");
        if (text.isEmpty() || text.length() > 2000) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[\\p{L}][\\p{L}'’-]*").matcher(text);
        if (!m.find()) return;
        Cards.Request r = new Cards.Request();
        r.lang = app.lang();
        r.kind = "text";
        r.sentence = text;
        r.title = "shared text";
        String w = m.group();
        body.post(() -> WordSheet.show(this, app.lang(), w, r, this::show));
    }

    /** The saved report of a crash, to copy and send. */
    private void problemReport(String title, File f) {
        String text;
        try { text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
        catch (IOException e) { Ui.toast(this, "No problem report saved"); return; }
        TextView t = Ui.text(this, text, 11, Ui.TEXT);
        t.setTextIsSelectable(true);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(t);
        Ui.dialog(this).setTitle(title).setView(sv)
                .setPositiveButton("Copy", (d, i) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Glossa Pocket problem report", text));
                    Ui.toast(this, "Copied: paste it to Claude");
                })
                .setNegativeButton("Close", null).show();
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); o.putString("tab", tab); o.putString("before", before); }

    private void openSettings() {
        if ("settings".equals(tab)) { tab = before; show(); return; }     // the gear again closes it
        toSettings();
    }

    private void toSettings() {
        if (!"settings".equals(tab)) before = tab;
        tab = "settings"; show();
    }

    @Override public void onBackPressed() {
        if ("settings".equals(tab)) { tab = before; show(); return; }
        if (!"home".equals(tab)) { tab = "home"; show(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume(); show();
        if (!updateAsked) {
            updateAsked = true;
            if (System.currentTimeMillis() - app.prefs().getLong("update_checked", 0) > 20L * 3600 * 1000) checkUpdate(false);
        }
        // automatic sync: when the app is opened or comes back after a while (the PC is asked quietly; no PC, no message)
        if (app.prefs().getBoolean("auto_sync", true) && app.pc() != null && System.currentTimeMillis() - app.prefs().getLong("synced_at", 0) > 10L * 60 * 1000) sync(false);
    }

    private void show() {
        for (View t : tabs) {                   // the chosen tab: accent icon on a soft pill (as the PC side menu marks its page) and a bold name
            boolean on = t.getTag().equals(tab);
            LinearLayout c = (LinearLayout) t;
            android.widget.ImageView ic = (android.widget.ImageView) c.getChildAt(0);
            TextView lb = (TextView) c.getChildAt(1);
            ic.setColorFilter(on ? Ui.ACCENT : Ui.DIM);
            ic.setBackground(on ? Ui.round(Ui.mix(Ui.CARD, Ui.ACCENT, 0.18f), Ui.dp(this, 14)) : null);
            lb.setTextColor(on ? Ui.TEXT : Ui.DIM);
            lb.setTypeface(on ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        }
        boolean set = "settings".equals(tab);
        gear.setColorFilter(set ? Ui.ACCENT : Ui.DIM);
        gear.setBackground(set ? Ui.round(Ui.mix(Ui.BG, Ui.ACCENT, 0.18f), Ui.dp(this, 22)) : null);
        langBtn.setText(app.lang().toUpperCase(Locale.ROOT));
        WordStore ws = app.store();
        int n = ws.unsentCount();
        status.setText(ws.knownCount() + " known words" + (ws.pulledAt() == 0 ? " · not synced with the PC yet" : "") + (n > 0 ? " · " + n + " change(s) to send" : ""));
        body.removeAllViews();
        View v;
        switch (tab) {
            case "videos": v = videos(); break;
            case "words": v = words(); break;
            case "settings": v = settings(); break;
            case "books": v = books(); break;
            default: v = home();
        }
        body.addView(v);
    }

    // ================================================================ language, sync
    private void pickLang() {
        List<String> langs = new ArrayList<>(Arrays.asList("it", "de", "en", "fr", "es", "hr", "pt", "nl", "ru", "la", "grc"));
        File[] ds = app.dicts.root.listFiles();
        for (Dict d : app.dicts.list()) if (!d.lang.isEmpty() && !langs.contains(d.lang)) langs.add(0, d.lang);
        String[] items = langs.toArray(new String[0]);
        Ui.dialog(this).setTitle("Language").setItems(items, (d, i) -> { app.setLang(items[i]); show(); }).show();
    }

    private void sync() { sync(true); }

    /** quiet: from the start of the app - it says nothing if the PC is not there. */
    private void sync(boolean loud) {
        PcLink pc = app.pc();
        if (pc == null) { if (loud) { toSettings(); Ui.toast(this, "First paste the link from Glossa on the PC (Settings → Phone access)"); } return; }
        if (syncing) { if (loud) Ui.toast(this, "Already syncing…"); return; }
        syncing = true;
        status.setText("syncing with the PC…");
        String lang = app.lang();
        app.bg(() -> {
            try {
                String msg = pc.sync(app.store(lang), positions());
                app.prefs().edit().putLong("synced_at", System.currentTimeMillis()).apply();
                app.post(() -> { if (loud) Ui.toast(this, "Synced: " + msg); show(); });
            } catch (IOException e) {
                app.post(() -> { show(); if (loud) Ui.alert(this, "Could not sync", explain(e)); });
            } finally {
                syncing = false;
            }
        });
    }

    /** Where you stopped in videos and books, to and from the PC (the later change wins). */
    private PcLink.Positions positions() {
        return new PcLink.Positions() {
            @Override public void collect(Map<String, Object> out) {
                android.content.SharedPreferences sp = app.prefs();
                Map<String, Object> vp = new java.util.LinkedHashMap<>(), bp = new java.util.LinkedHashMap<>();
                File[] vs = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
                if (vs != null) for (File f : vs) {
                    long t = sp.getLong("vpost_" + f.getName(), 0);
                    if (t > 0) vp.put(Player.videoName(f).toLowerCase(Locale.ROOT), new ArrayList<Object>(Arrays.asList((double) sp.getFloat("vpos_" + f.getName(), 0), (double) t)));
                }
                File[] bs = app.dir("books").listFiles((d, n) -> n.startsWith("pc") && n.endsWith(".json"));
                if (bs != null) for (File f : bs) {
                    String id = f.getName().replaceAll("\\.json$", "");
                    long t = sp.getLong("rt_" + id, 0);
                    if (t > 0) bp.put(id.substring(2), new ArrayList<Object>(Arrays.asList((double) sp.getFloat("rf_" + id, 0), (double) t)));
                }
                out.put("video_pos", vp);
                out.put("book_pos", bp);
            }

            @Override public void apply(Map<String, Object> pulled) {
                if (Ui.setPcLook(MainActivity.this, Json.obj(pulled.get("look")))) runOnUiThread(() -> recreate());
                Icons.followPc(MainActivity.this, Json.str(Json.obj(pulled.get("look")).get("icon"), ""));      // the PC's theme changed: the screens are built again
                android.content.SharedPreferences sp = app.prefs();
                android.content.SharedPreferences.Editor ed = sp.edit();
                File[] vs = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
                for (Map.Entry<String, Object> e : Json.obj(pulled.get("video_pos")).entrySet()) {
                    List<Object> v = Json.arr(e.getValue());
                    if (v.size() < 2 || vs == null) continue;
                    long t = (long) Json.num(v.get(1), 0);
                    for (File f : vs) if (Player.videoName(f).equalsIgnoreCase(e.getKey()) && t > sp.getLong("vpost_" + f.getName(), 0))
                        ed.putFloat("vpos_" + f.getName(), (float) Json.num(v.get(0), 0)).putLong("vpost_" + f.getName(), t);
                }
                for (Map.Entry<String, Object> e : Json.obj(pulled.get("book_pos")).entrySet()) {
                    List<Object> v = Json.arr(e.getValue());
                    String id = "pc" + e.getKey();
                    File bf = new File(app.dir("books"), id + ".json");
                    long t = v.size() < 2 ? 0 : (long) Json.num(v.get(1), 0);
                    if (t <= sp.getLong("rt_" + id, 0) || !bf.isFile()) continue;
                    try {
                        Book bk = Book.read(bf);
                        int target = (int) Math.round(Math.max(0, Math.min(1, Json.num(v.get(0), 0))) * bk.paraCount()), c = 0;
                        while (c < bk.chapters.size() - 1 && target >= bk.chapters.get(c).paras.size()) { target -= bk.chapters.get(c).paras.size(); c++; }
                        ed.putInt("rc_" + id, c).putInt("rp_" + id, Math.max(0, Math.min(target, Math.max(0, bk.chapters.get(c).paras.size() - 1))))
                                .putFloat("rf_" + id, (float) Json.num(v.get(0), 0)).putLong("rt_" + id, t);
                    } catch (IOException | RuntimeException ignored) { }
                }
                ed.apply();
            }
        };
    }

    static String explain(IOException e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.UnknownHostException || m.contains("timed out") || m.contains("Failed to connect"))
            return "The PC did not answer. Is it on, with Glossa running and Phone access switched on, and is the phone on the same Wi-Fi (or Tailscale)?\n\n" + m;
        return m;
    }

    // ================================================================ home
    /** Like Home on the PC: what you were in the middle of, a few numbers, and the shelves of books and videos. */
    private View home() {
        LinearLayout l = Ui.col(this);
        int pad = Ui.dp(this, 12);
        l.setPadding(pad, 0, pad, pad);
        android.content.SharedPreferences sp = app.prefs();
        WordStore ws = app.store();

        // three numbers; a tap goes to Words
        LinearLayout nums = Ui.row(this);
        int[] vals = {ws.knownCount(), ws.pending().size(), ws.saved().size()};
        String[] labels = {"known words", "cards waiting", "saved words"};
        for (int i = 0; i < 3; i++) {
            LinearLayout t = Ui.col(this);
            t.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            t.setBackground(Ui.round(Ui.CARD, Ui.dp(this, 14)));
            TextView n = Ui.text(this, String.valueOf(vals[i]), 22, i == 1 && vals[i] > 0 ? Ui.ACCENT : Ui.TEXT);
            n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t.addView(n);
            t.addView(Ui.text(this, labels[i], 12, Ui.DIM));
            t.setOnClickListener(v -> { tab = "words"; show(); });
            nums.addView(Ui.weight(t, 1));
        }
        Ui.add(l, nums, 14);
        if (app.pc() == null) {
            Ui.add(l, Ui.item(this, "Link the PC", "Paste the link from Glossa on the PC (Settings → Phone access) to exchange words, cards and where you stopped.", v -> { toSettings(); }), 14);
        }

        // continue: the videos and books touched last, newest first
        List<Object[]> recent = new ArrayList<>();          // {time, view}
        File[] vfs = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
        if (vfs != null) for (File f : vfs) {
            long t = sp.getLong("vpost_" + f.getName(), 0);
            float at = sp.getFloat("vpos_" + f.getName(), 0);
            if (t <= 0 || at < 5) continue;
            LinearLayout it = Ui.item(this, "▶  " + Player.niceName(f), "at " + Ui.time(at) + " · " + Ui.ago(t), v -> openVideo(f));
            recent.add(new Object[]{t, it});
        }
        for (BookInfo bk : bookList(app.lang())) {
            long t = sp.getLong("rt_" + bk.id, 0);
            if (t <= 0) continue;
            float frac = sp.getFloat("rf_" + bk.id, 0);
            int[] pos = Reader.position(bk.id);
            LinearLayout it = Ui.item(this, "📖  " + bk.title, "chapter " + (pos[0] + 1) + " of " + bk.chapters + " · " + Math.round(frac * 100) + "% · " + Ui.ago(t), v -> openBook(bk));
            View pr = Ui.progress(this, frac);
            ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = Ui.dp(this, 8);
            it.addView(pr);
            recent.add(new Object[]{t, it});
        }
        recent.sort((a, b) -> Long.compare((Long) b[0], (Long) a[0]));
        l.addView(Ui.title(this, "Continue"));
        if (recent.isEmpty()) l.addView(Ui.hint(this, "Open a book or a video and it waits here, at the place you stopped."));
        for (int i = 0; i < Math.min(4, recent.size()); i++) {
            View it = (View) recent.get(i)[1];
            if (i == 0) it.setBackground(Ui.round(Ui.mix(Ui.CARD, Ui.ACCENT, 0.16f), Ui.dp(this, 14)));
            Ui.add(l, it, 8);
        }

        // shelves
        List<BookInfo> books = bookList(app.lang());
        shelf(l, "Books", books.size(), "books", () -> {
            List<View> out = new ArrayList<>();
            for (BookInfo bk : books) out.add(cover(bk.title, bk.chapters + " ch.", v -> openBook(bk)));
            return out;
        });
        File[] vs = vfs == null ? new File[0] : vfs.clone();
        Arrays.sort(vs, (a, b) -> Long.compare(sp.getLong("vpost_" + b.getName(), b.lastModified()), sp.getLong("vpost_" + a.getName(), a.lastModified())));
        shelf(l, "Videos", vs.length, "videos", () -> {
            List<View> out = new ArrayList<>();
            for (File f : vs) {
                float at = sp.getFloat("vpos_" + f.getName(), 0);
                out.add(cover(Player.niceName(f), at > 5 ? "at " + Ui.time(at) : "not started", v -> openVideo(f)));
            }
            return out;
        });
        return Ui.scroll(this, l);
    }

    private interface Covers { List<View> make(); }

    /** A row of covers that scrolls sideways, with "All →" going to the tab. */
    private void shelf(LinearLayout l, String name, int count, String toTab, Covers covers) {
        LinearLayout head = Ui.row(this);
        TextView t = Ui.title(this, name);
        head.addView(Ui.weight(t, 1));
        TextView all = Ui.text(this, count == 0 ? "Add →" : "All " + count + " →", 14, Ui.ACCENT);
        all.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        all.setOnClickListener(v -> { tab = toTab; show(); });
        head.addView(all);
        Ui.add(l, head, 0);
        if (count == 0) { l.addView(Ui.hint(this, "books".equals(toTab) ? "No books in this language yet." : "No videos yet.")); return; }
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.TOP);
        List<View> cs = covers.make();
        for (int i = 0; i < Math.min(10, cs.size()); i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 120), ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = Ui.dp(this, 10);
            row.addView(cs.get(i), p);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        Ui.add(l, hs, 10);
    }

    /** A small cover: a coloured block with the first letters, the title and one line under it. */
    private View cover(String title, String sub, View.OnClickListener click) {
        LinearLayout c = Ui.col(this);
        TextView art = Ui.text(this, initials(title), 26, Ui.ON_ACC);
        art.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        art.setGravity(Gravity.CENTER);
        int h = Math.abs(title.hashCode());
        art.setBackground(Ui.round(Ui.mix(Ui.ACCENT, (h & 1) == 0 ? Ui.BG : Ui.TEXT, 0.15f + (h % 5) * 0.08f), Ui.dp(this, 12)));
        c.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 92)));
        TextView tt = Ui.text(this, title, 13, Ui.TEXT);
        tt.setMaxLines(2);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tt.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(tt);
        c.addView(Ui.text(this, sub, 11, Ui.DIM));
        c.setOnClickListener(click);
        return c;
    }

    private static String initials(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            b.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
            if (b.length() >= 2) break;
        }
        return b.length() == 0 ? "?" : b.toString();
    }

    private void openVideo(File f) { Intent i = new Intent(this, Player.class); i.putExtra("path", f.getPath()); startActivity(i); }
    private void openBook(BookInfo bk) { Intent i = new Intent(this, Reader.class); i.putExtra("id", bk.id); startActivity(i); }

    // ================================================================ books
    private View books() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        LinearLayout acts = Ui.row(this);
        acts.addView(Ui.weight(Ui.primary(this, "Add a book", v -> pick(REQ_BOOK, new String[]{"application/epub+zip", "text/plain"}, false)), 1));
        acts.addView(Ui.weight(Ui.button(this, "From the PC", v -> pcBooks()), 1));
        Ui.add(l, acts, 8);
        String lang = app.lang();
        List<BookInfo> books = bookList(lang);
        if (books.isEmpty()) l.addView(Ui.hint(this, "No " + lang.toUpperCase(Locale.ROOT) + " books yet. Add an EPUB or text file from the phone, or copy a book from the Reader on the PC."));
        for (BookInfo bk : books) {
            int[] pos = Reader.position(bk.id);
            String sub = bk.chapters + " chapters" + (pos[0] > 0 || pos[1] > 0 ? " · at chapter " + (pos[0] + 1) : "");
            LinearLayout it = Ui.item(this, bk.title, sub, v -> { Intent i = new Intent(this, Reader.class); i.putExtra("id", bk.id); startActivity(i); });
            it.setOnLongClickListener(v -> {
                Ui.dialog(this).setTitle(bk.title).setItems(new String[]{"Delete this book"}, (d, w) -> { bk.file.delete(); show(); }).show();
                return true;
            });
            Ui.add(l, it, 8);
        }
        return Ui.scroll(this, l);
    }

    /** What the book list shows of a book; read once per file version (books can be megabytes). */
    static final class BookInfo {
        File file; long stamp; String id, title, lang; int chapters;
    }

    private static final Map<String, BookInfo> bookCache = new java.util.HashMap<>();

    List<BookInfo> bookList(String lang) {
        List<BookInfo> out = new ArrayList<>();
        File[] fs = app.dir("books").listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : fs) {
            BookInfo bi = bookCache.get(f.getPath());
            if (bi == null || bi.stamp != f.lastModified() + f.length()) {
                try {
                    Book bk = Book.read(f);
                    bi = new BookInfo();
                    bi.file = f; bi.stamp = f.lastModified() + f.length(); bi.id = bk.id; bi.title = bk.title; bi.lang = bk.lang; bi.chapters = bk.chapters.size();
                    bookCache.put(f.getPath(), bi);
                } catch (IOException | RuntimeException e) { continue; }
            }
            if (bi.lang.isEmpty() || bi.lang.equals(lang)) out.add(bi);
        }
        return out;
    }

    private void pcBooks() {
        PcLink pc = app.pc();
        if (pc == null) { Ui.toast(this, "Paste the PC link in Settings first"); return; }
        String lang = app.lang();
        app.bg(() -> {
            try {
                List<Object> bs = Json.arr(pc.get("/api/pocket/books?lang=" + PcLink.enc(lang), 15000).get("books"));
                app.post(() -> {
                    if (bs.isEmpty()) { Ui.alert(this, "Books on the PC", "The Reader on the PC has no " + lang.toUpperCase(Locale.ROOT) + " books."); return; }
                    String[] names = new String[bs.size()];
                    for (int i = 0; i < names.length; i++) names[i] = Json.str(Json.obj(bs.get(i)).get("title"), "?");
                    Ui.dialog(this).setTitle("Copy a book from the PC").setItems(names, (d, i) -> {
                        String key = Json.str(Json.obj(bs.get(i)).get("key"), "");
                        Ui.toast(this, "Copying…");
                        app.bg(() -> {
                            try {
                                Book bk = Book.fromJson(pc.get("/api/pocket/book?key=" + key, 120000));
                                bk.id = "pc" + key;
                                if (bk.lang.isEmpty()) bk.lang = lang;
                                bk.write(new File(app.dir("books"), bk.id + ".json"));
                                app.post(() -> { Ui.toast(this, "“" + bk.title + "” copied"); show(); });
                            } catch (IOException e) { app.post(() -> Ui.alert(this, "Could not copy", explain(e))); }
                        });
                    }).show();
                });
            } catch (IOException e) { app.post(() -> Ui.alert(this, "Could not reach the PC", explain(e))); }
        });
    }

    // ================================================================ videos
    private View videos() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        LinearLayout acts = Ui.row(this);
        acts.addView(Ui.weight(Ui.primary(this, "Add a video", v -> pick(REQ_VIDEO, new String[]{"video/*", "application/x-subrip", "text/vtt", "text/x-ssa", "application/octet-stream", "text/*"}, true)), 1));
        acts.addView(Ui.weight(Ui.button(this, "From the PC", v -> pcVideos()), 1));
        Ui.add(l, acts, 8);
        File[] fs = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
        if (fs == null || fs.length == 0) {
            l.addView(Ui.hint(this, "No videos yet. “Add a video” takes a video from the phone together with its subtitle files (.srt / .vtt / .ass): choose them all in one go. “From the PC” copies an episode and its subtitles over Wi-Fi."));
        } else {
            Arrays.sort(fs);
            for (File f : fs) {
                int subs = Player.subsOf(f).size();
                double at = app.prefs().getFloat("vpos_" + f.getName(), 0);
                boolean linked = f.getName().endsWith(".vlink");
                long fsize = linked ? linkSize(f) : f.length();
                LinearLayout it = Ui.item(this, Player.niceName(f), (fsize < 0 ? "video not found (add it again)" : Ui.size(fsize) + (linked ? " (stays where it is)" : "")) + " · " + (subs == 0 ? "no subtitles" : subs + " subtitle file" + (subs > 1 ? "s" : "")) + (at > 5 ? " · at " + Ui.time(at) : ""),
                        v -> { Intent i = new Intent(this, Player.class); i.putExtra("path", f.getPath()); startActivity(i); });
                it.setOnLongClickListener(v -> {
                    Ui.dialog(this).setTitle(f.getName()).setItems(new String[]{f.getName().endsWith(".vlink") ? "Remove from the app (the video itself stays)" : "Delete this video and its subtitles"}, (d, w) -> {
                        for (File s : Player.subsOf(f)) s.delete();
                        f.delete();
                        show();
                    }).show();
                    return true;
                });
                Ui.add(l, it, 8);
            }
        }
        return Ui.scroll(this, l);
    }

    /** Size of a video that is only linked; -1 when it is gone. */
    private long linkSize(File link) {
        try (android.content.res.AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(Player.videoUri(link), "r")) {
            return fd == null ? -1 : fd.getLength();
        } catch (Exception e) { return -1; }
    }

    private void pcVideos() {
        PcLink pc = app.pc();
        if (pc == null) { Ui.toast(this, "Paste the PC link in Settings first"); return; }
        String lang = app.lang();
        app.bg(() -> {
            try {
                List<Object> vs = Json.arr(pc.get("/api/pocket/videos?lang=" + PcLink.enc(lang), 30000).get("videos"));
                app.post(() -> {
                    if (vs.isEmpty()) { Ui.alert(this, "Videos on the PC", "Nothing yet: the PC lists the folders of videos you watched in " + lang.toUpperCase(Locale.ROOT) + "."); return; }
                    String[] names = new String[vs.size()];
                    for (int i = 0; i < names.length; i++) {
                        Map<String, Object> v = Json.obj(vs.get(i));
                        names[i] = Json.str(v.get("series"), "") + " / " + Json.str(v.get("name"), "?") + "  (" + Ui.size((long) Json.num(v.get("size"), 0)) + ")";
                    }
                    Ui.dialog(this).setTitle("Copy from the PC").setItems(names, (d, i) -> copyVideo(pc, Json.obj(vs.get(i)))).show();
                });
            } catch (IOException e) { app.post(() -> Ui.alert(this, "Could not reach the PC", explain(e))); }
        });
    }

    private void copyVideo(PcLink pc, Map<String, Object> v) {
        String name = Json.str(v.get("name"), "video.mp4").replaceAll("[\\\\/:*?\"<>|]", "_");
        File dest = new File(app.videoDir(), name);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView msg = Ui.text(this, "Copying " + name, 14, Ui.TEXT);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), 0);
        box.addView(msg);
        box.addView(bar);
        final boolean[] cancel = {false};
        AlertDialog dlg = Ui.dialog(this).setTitle("Copying from the PC").setView(box).setCancelable(false)
                .setNegativeButton("Stop (kept: it continues next time)", (d, w) -> cancel[0] = true).show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);      // a sleeping phone drops the Wi-Fi
        app.bg(() -> {
            try {
                for (Object s : Json.arr(v.get("subs"))) {
                    Map<String, Object> sm = Json.obj(s);
                    pc.download("/api/pocket/file?id=" + Json.str(sm.get("id"), ""), new File(app.videoDir(), Json.str(sm.get("name"), "sub.srt").replaceAll("[\\\\/:*?\"<>|]", "_")), null);
                }
                pc.download("/api/pocket/file?id=" + Json.str(v.get("id"), ""), dest, new PcLink.Bytes() {
                    @Override public void progress(long done, long total) { app.post(() -> bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0)); }
                    @Override public boolean cancelled() { return cancel[0]; }
                });
                app.post(() -> { Ui.dismiss(dlg); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Ui.toast(this, name + " copied"); show(); });
            } catch (IOException e) {
                app.post(() -> { Ui.dismiss(dlg); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); if (!cancel[0]) Ui.alert(this, "Could not copy", explain(e)); show(); });
            }
        });
    }

    // ================================================================ words
    private View words() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        WordStore ws = app.store();
        List<Map<String, Object>> pend = ws.pending();
        if (!pend.isEmpty()) {
            l.addView(Ui.title(this, pend.size() + " card(s) waiting for the PC"));
            for (Map<String, Object> c : pend) {
                String id = Json.str(c.get("id"), "");
                Ui.add(l, Ui.item(this, Json.str(c.get("word"), ""), Json.str(c.get("sentence"), ""), v ->
                        Ui.dialog(this).setTitle(Json.str(c.get("word"), "")).setItems(new String[]{"Remove this card"}, (d, i) -> { ws.removePending(id); show(); }).show()), 6);
            }
        }
        List<Map<String, Object>> saved = ws.saved();
        l.addView(Ui.title(this, "Saved words"));
        if (saved.isEmpty()) l.addView(Ui.hint(this, "Tap “Save” in a word card to keep a word for later."));
        for (Map<String, Object> s : saved) {
            String w = Json.str(s.get("word"), "");
            Ui.add(l, Ui.item(this, w, Json.str(s.get("sentence"), ""), v -> {
                Cards.Request r = new Cards.Request();
                r.lang = app.lang(); r.sentence = Json.str(s.get("sentence"), ""); r.title = Json.str(s.get("source"), "");
                WordSheet.show(this, app.lang(), w, r, this::show);
            }), 6);
        }
        return Ui.scroll(this, l);
    }

    // ================================================================ settings
    private View settings() {
        LinearLayout l = Ui.col(this);
        int pad = Ui.dp(this, 12);
        l.setPadding(pad, 0, pad, pad);
        l.addView(Ui.title(this, "The PC"));
        l.addView(Ui.hint(this, "On the PC: Glossa → Settings → Phone access → switch it on, then copy the link and paste it here. The phone and the PC need to be on the same Wi-Fi (or both on Tailscale)."));
        PcLink pc = app.pc();
        android.widget.EditText link = Ui.input(this, pc == null ? "" : pc.link(), "http://192.168.1.20:8780/?t=…");
        Ui.add(l, link, 6);
        LinearLayout r = Ui.row(this);
        r.addView(Ui.weight(Ui.primary(this, "Save and test", v -> {
            PcLink p = PcLink.parse(link.getText().toString());
            if (p == null) { Ui.toast(this, "That is not a Glossa link (it has ?t=… at the end)"); return; }
            app.prefs().edit().putString("pc_link", p.link()).apply();
            app.bg(() -> {
                try {
                    Map<String, Object> h = p.get("/api/pocket/hello", 6000);
                    app.post(() -> Ui.alert(this, "Connected", "Glossa on “" + Json.str(h.get("name"), "the PC") + "” answered." + (Boolean.TRUE.equals(h.get("anki")) ? "\nAnki is open there." : "\nAnki is not open there (cards wait until it is).")));
                } catch (IOException e) { app.post(() -> Ui.alert(this, "Not connected", explain(e))); }
            });
        }), 1));
        r.addView(Ui.weight(Ui.button(this, "Forget", v -> { app.prefs().edit().remove("pc_link").apply(); show(); }), 1));
        Ui.add(l, r, 12);

        android.widget.CheckBox au = new android.widget.CheckBox(this);
        au.setText("Sync by itself when the app opens");
        au.setTextColor(Ui.TEXT);
        au.setChecked(app.prefs().getBoolean("auto_sync", true));
        au.setOnCheckedChangeListener((b, on) -> app.prefs().edit().putBoolean("auto_sync", on).apply());
        Ui.add(l, au, 4);
        l.addView(Ui.hint(this, "Known words, looked-up words, waiting cards and where you stopped in videos and books are exchanged with the PC. If the PC is off nothing happens."));

        l.addView(Ui.title(this, "Look"));
        String cur = app.prefs().getString("theme", "pc");
        l.addView(Ui.hint(this, "The colours follow the theme you picked in Glossa on the PC (Look and window) after each sync. You can also pick one here."));
        Ui.add(l, Ui.item(this, "Theme", "pc".equals(cur) ? "Same as on the PC" : Ui.themeName(cur), v -> {
            String[] names = new String[Ui.THEMES.length + 1];
            names[0] = "Same as on the PC";
            for (int i = 0; i < Ui.THEMES.length; i++) names[i + 1] = Ui.THEMES[i][1];
            Ui.dialog(this).setTitle("Theme").setItems(names, (dl, i) -> {
                app.prefs().edit().putString("theme", i == 0 ? "pc" : Ui.THEMES[i - 1][0]).apply();
                Ui.load(this);
                recreate();
            }).show();
        }), 12);

        String acc = app.prefs().getString("accent", "");
        Ui.add(l, Ui.item(this, "Accent colour", acc.isEmpty() ? "The theme's own" : Ui.accentName(acc), v -> {
            String[] names = new String[Ui.ACCENTS.length];
            for (int i = 0; i < names.length; i++) names[i] = Ui.ACCENTS[i][0];
            Ui.dialog(this).setTitle("Accent colour").setItems(names, (dl, i) -> {
                app.prefs().edit().putString("accent", Ui.ACCENTS[i][1]).apply();
                Ui.load(this);
                recreate();
            }).show();
        }), 12);

        String ic = app.prefs().getString("icon", "original");
        Ui.add(l, Ui.item(this, "App icon", "pc".equals(ic) ? "Same as on the PC (now: " + Icons.name(Icons.current(this)) + ")" : Icons.name(ic), v -> {
            String[] names = new String[Icons.ALL.length + 1];
            names[0] = "Same as on the PC";
            for (int i = 0; i < Icons.ALL.length; i++) names[i + 1] = Icons.ALL[i][1];
            Ui.dialog(this).setTitle("App icon").setItems(names, (dl, i) -> {
                String id = i == 0 ? "pc" : Icons.ALL[i - 1][0];
                app.prefs().edit().putString("icon", id).apply();
                if (i == 0) Ui.toast(this, "The icon follows the PC from the next sync");
                else { Icons.apply(this, id); Ui.toast(this, "The launcher shows the new icon in a moment. A shortcut on the home screen may need adding again."); }
                show();
            }).show();
        }), 12);

        l.addView(Ui.title(this, "Dictionaries"));
        l.addView(Ui.hint(this, "Import the same wty zip files the PC uses (wty-it-it.zip, and the -ipa one for pronunciation). It takes a minute or two; the dictionary then works offline."));
        for (Dict d : app.dicts.list()) {
            Ui.add(l, Ui.item(this, d.title + " [" + d.lang + "]", d.summary(), v ->
                    Ui.dialog(this).setTitle(d.title).setItems(new String[]{"Delete this dictionary"}, (dl, i) -> { app.dicts.remove(d); show(); }).show()), 6);
        }
        l.addView(Ui.hint(this, "Frequency lists (for example the Leipzig lists) can be imported the same way: the rank shows in the word popup and goes on the card. A list without a language is made for the language that is selected now."));
        Ui.add(l, Ui.primary(this, "Import a dictionary (.zip)", v -> pick(REQ_DICT, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"}, false)), 12);

        l.addView(Ui.title(this, "Cards"));
        boolean anki = Cards.useAnki();
        l.addView(Ui.hint(this, anki ? "Cards are made on the phone and added to AnkiDroid at once." : "Cards are kept and made by the PC at the next sync."));
        LinearLayout cr = Ui.row(this);
        cr.addView(Ui.weight(Ui.button(this, anki ? "Make them on the PC instead" : "Make them in AnkiDroid", v -> {
            app.prefs().edit().putString("card_mode", anki ? "pc" : "anki").apply();
            show();
        }), 1));
        cr.addView(Ui.weight(Ui.button(this, "AnkiDroid fields…", v -> Cards.setup(this, app.lang(), this::show)), 1));
        Ui.add(l, cr, 8);
        android.widget.CheckBox cp = new android.widget.CheckBox(this);
        cp.setText("Copy videos into the app (uses more storage)");
        cp.setTextColor(Ui.TEXT);
        cp.setChecked(app.prefs().getBoolean("copy_videos", false));
        cp.setOnCheckedChangeListener((b, on) -> app.prefs().edit().putBoolean("copy_videos", on).apply());
        Ui.add(l, cp, 6);
        l.addView(Ui.hint(this, "By default a video you add stays where it is on the phone and the app only remembers the place (no second copy). Subtitles are copied (they are tiny). Videos from the PC are copied."));
        l.addView(Ui.hint(this, "Sound and picture of a video line are cut on the phone from the video file (MP4 / MKV). Sound in AAC, Opus or Vorbis is cut as it is; MP3, FLAC and similar are decoded (a small .wav). If the phone has no decoder for it (for example AC-3), the card is left to the PC."));

        l.addView(Ui.title(this, "Updates"));
        l.addView(Ui.hint(this, "This is version " + Updater.myName(app) + ". New versions are built on GitHub; “Check for a new version” downloads the newest one and installs it over this one (your words, books and dictionaries stay)."));
        if (Updater.repo(app).isEmpty() || !app.getString(R.string.update_repo).trim().matches("[\\w.-]+/[\\w.-]+")) {
            android.widget.EditText rp = Ui.input(this, app.prefs().getString("update_repo", ""), "yourname/glossa");
            Ui.add(l, rp, 6);
            Ui.add(l, Ui.button(this, "Save the GitHub project", v -> { app.prefs().edit().putString("update_repo", rp.getText().toString().trim()).apply(); show(); }), 6);
        }
        Ui.add(l, Ui.primary(this, "Check for a new version", v -> checkUpdate(true)), 12);

        l.addView(Ui.title(this, "Problem report"));
        File last = new File(getFilesDir(), "crash-seen.txt");
        l.addView(Ui.hint(this, last.isFile() ? "The last time something went wrong, the app saved what happened. Copy it and send it to get it fixed." : "Nothing has gone wrong so far."));
        if (last.isFile()) Ui.add(l, Ui.button(this, "Show the report", v -> problemReport("Problem report", last)), 8);
        return Ui.scroll(this, l);
    }

    // ================================================================ updates
    private boolean updateAsked;

    /** Asks GitHub whether a newer build exists. quiet: from the start of the app (says nothing unless there is one). */
    private void checkUpdate(boolean loud) {
        if (Updater.repo(app).isEmpty()) { if (loud) Ui.toast(this, "Type the GitHub project below first"); return; }
        if (loud) Ui.toast(this, "Looking for a new version…");
        app.bg(() -> {
            try {
                Updater.Found f = Updater.check(app);
                app.prefs().edit().putLong("update_checked", System.currentTimeMillis()).apply();
                app.post(() -> {
                    if (f == null) { if (loud) Ui.alert(this, "Up to date", "This is the newest version (" + Updater.myName(app) + ")."); return; }
                    Ui.dialog(this).setTitle("A new version is ready")
                            .setMessage("Install it now? Your words, books and dictionaries stay.")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Download and install", (d, w) -> downloadUpdate(f)).show();
                });
            } catch (IOException e) { if (loud) app.post(() -> Ui.alert(this, "Could not check", e.getMessage())); }
        });
    }

    private void downloadUpdate(Updater.Found f) {
        Ui.toast(this, "Downloading… the installer opens when it is done");
        app.bg(() -> {
            try {
                File apk = Updater.download(app, f, p -> { });
                app.post(() -> {
                    if (!Updater.install(this, apk)) Ui.alert(this, "One switch first", "Android asks you to allow this app to install apps. Switch it on, go back, and press “Check for a new version” again.");
                });
            } catch (IOException e) { app.post(() -> Ui.alert(this, "Download failed", String.valueOf(e.getMessage()))); }
        });
    }

    // ================================================================ picking files
    private void pick(int req, String[] types, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, types);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) { ClipData cd = data.getClipData(); for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri()); }
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        if (req == REQ_BOOK) addBook(uris.get(0));
        else if (req == REQ_VIDEO) addVideos(uris);
        else if (req == REQ_DICT) importDict(uris.get(0));
    }

    private String nameOf(Uri u) {
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (RuntimeException ignored) { }
        String s = u.getLastPathSegment();
        return s == null ? "file" : s.substring(s.lastIndexOf('/') + 1);
    }

    private void addBook(Uri u) {
        String name = nameOf(u), lang = app.lang();
        Ui.toast(this, "Reading the book…");
        app.bg(() -> {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                String stem = name.replaceAll("\\.[^.]+$", "");
                Book bk = name.toLowerCase(Locale.ROOT).endsWith(".epub") ? Book.fromEpub(in, stem) : Book.fromText(Book.decode(readAll(in)), stem);
                if (bk.chapters.isEmpty()) throw new IOException("no text found in the file");
                bk.id = "ph" + Long.toString(System.currentTimeMillis(), 36);
                if (bk.lang.isEmpty() || !bk.lang.equals(lang)) bk.lang = lang;
                bk.write(new File(app.dir("books"), bk.id + ".json"));
                app.post(() -> { Ui.toast(this, "“" + bk.title + "” added"); show(); });
            } catch (IOException | RuntimeException e) { app.post(() -> Ui.alert(this, "Could not add the book", String.valueOf(e.getMessage()))); }
        });
    }

    private void addVideos(List<Uri> uris) {
        Ui.toast(this, "Copying…");
        app.bg(() -> {
            List<String> names = new ArrayList<>();
            for (Uri u : uris) names.add(nameOf(u).replaceAll("[\\\\/:*?\"<>|]", "_"));
            // Subtitle files picked together with one video get its name (VIDEO.de.srt …), so they always belong to it.
            String video = null;
            int videos = 0;
            for (String n : names) if (Player.isVideo(n)) { video = n; videos++; }
            String stem = videos == 1 ? Player.niceName(new File(video)) : null;
            List<String> used = new ArrayList<>();
            int n = 0;
            try {
                for (int k = 0; k < uris.size(); k++) {
                    String name = names.get(k);
                    if (stem != null && Player.isSub(name) && !name.toLowerCase(Locale.ROOT).startsWith(stem.toLowerCase(Locale.ROOT) + ".")) {
                        String ext = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
                        String tag = Player.guessLang(name);
                        String nn = stem + "." + tag + ext;
                        for (int i = 2; used.contains(nn.toLowerCase(Locale.ROOT)); i++)
                            nn = stem + "." + tag + i + ext;
                        name = nn;
                    }
                    used.add(name.toLowerCase(Locale.ROOT));
                    if (Player.isVideo(name) && !app.prefs().getBoolean("copy_videos", false)) {      // not copied: the app remembers where it is
                        try {
                            getContentResolver().takePersistableUriPermission(uris.get(k), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            String link = name.replaceAll("\\.[^.]+$", "") + ".vlink";
                            java.nio.file.Files.write(new File(app.videoDir(), link).toPath(), (uris.get(k).toString() + "\n" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            n++;
                            continue;
                        } catch (SecurityException | IOException ignored) { /* this app of files cannot be linked: copy instead */ }
                    }
                    try (InputStream in = getContentResolver().openInputStream(uris.get(k)); OutputStream out = new FileOutputStream(new File(app.videoDir(), name))) {
                        if (in == null) throw new IOException("could not open " + names.get(k));
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                        n++;
                    }
                }
                int count = n;
                app.post(() -> { Ui.toast(this, count + " file(s) copied"); show(); });
            } catch (IOException e) { app.post(() -> Ui.alert(this, "Could not copy", String.valueOf(e.getMessage()))); }
        });
    }

    private void importDict(Uri u) {
        TextView msg = Ui.text(this, "Reading the dictionary…", 14, Ui.TEXT);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        box.addView(msg);
        AlertDialog dlg = Ui.dialog(this).setTitle("Importing").setView(box).setCancelable(false).show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        app.bg(() -> {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                File d = Dict.importZip(new java.io.BufferedInputStream(in, 1 << 16), app.dicts.root, (what, n) -> app.post(() -> msg.setText(what.equals("sorting") ? "Sorting… (nearly done)" : n + " entries read…")));
                try {                                       // a dictionary that does not say which language it is for (a frequency list) is for this one
                    File mf = new File(d, "meta.json");
                    Map<String, Object> mm = Json.obj(Json.parse(new String(java.nio.file.Files.readAllBytes(mf.toPath()), java.nio.charset.StandardCharsets.UTF_8)));
                    if (Json.str(mm.get("lang"), "").isEmpty()) { mm.put("lang", app.lang()); java.nio.file.Files.write(mf.toPath(), Json.write(mm).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                } catch (IOException | RuntimeException ignored) { }
                app.dicts.reload();
                app.post(() -> { Ui.dismiss(dlg); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Ui.toast(this, "Imported " + d.getName()); show(); });
            } catch (IOException | RuntimeException e) {
                app.post(() -> { Ui.dismiss(dlg); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Ui.alert(this, "Could not import", String.valueOf(e.getMessage())); });
            }
        });
    }

    static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int r;
        while ((r = in.read(buf)) > 0) o.write(buf, 0, r);
        return o.toByteArray();
    }
}
