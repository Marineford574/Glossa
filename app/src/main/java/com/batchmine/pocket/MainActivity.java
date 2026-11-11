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
import com.batchmine.pocket.core.Shows;
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
    static final int REQ_BOOK = 1, REQ_VIDEO = 2, REQ_DICT = 3, REQ_PHOTO = 4;
    private App app;
    private FrameLayout body;
    private TextView langBtn, status;
    private String tab = "home";
    private static volatile boolean syncing;
    private final List<View> tabs = new ArrayList<>();
    private android.widget.ImageView gear;
    private String before = "home";
    private String openShow, openBookId;                       // a series page / a book page is open inside its tab                             // the tab to go back to from Settings

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
            v.setOnClickListener(x -> { if (t[0].equals(tab)) { openShow = null; openBookId = null; } tab = t[0]; show(); });   // the tab again: back to its list
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
        if (openShow != null || openBookId != null) { openShow = null; openBookId = null; show(); return; }
        if (!"home".equals(tab)) { tab = "home"; show(); return; }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume(); show();
        Stopwatch.tidy();
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
                out.put("stopwatch", Stopwatch.forSync(14));
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
            t.setBackground(Ui.press(Ui.CARD, Ui.LINE, Ui.dp(this, Ui.R)));
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

        weekBox(l);
        reviewBox(l);

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
            if (i == 0) it.setBackground(Ui.press(Ui.mix(Ui.CARD, Ui.ACCENT, 0.16f), Ui.mix(Ui.LINE, Ui.ACCENT, 0.4f), Ui.dp(this, Ui.R)));
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

    /** The time of the last 7 days (watching and reading on this phone), like the PC's week chart. */
    private void weekBox(LinearLayout l) {
        int[] w = new int[7], r = new int[7];
        String[] names = new String[7];
        int max = 1, tw = 0, tr = 0;
        java.util.Calendar c = java.util.Calendar.getInstance();
        for (int i = 6; i >= 0; i--) {
            String d = Stopwatch.day(c.getTimeInMillis());
            names[i] = c.getDisplayName(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.SHORT, Locale.getDefault());
            w[i] = Math.round(Stopwatch.seconds(d, "watch") / 60f); r[i] = Math.round(Stopwatch.seconds(d, "read") / 60f);
            max = Math.max(max, w[i] + r[i]); tw += w[i]; tr += r[i];
            c.add(java.util.Calendar.DAY_OF_MONTH, -1);
        }
        if (tw + tr == 0) return;
        l.addView(Ui.title(this, "This week"));
        LinearLayout card = Ui.card(this);
        LinearLayout bars = Ui.row(this);
        bars.setGravity(Gravity.BOTTOM);
        int bar = Ui.dp(this, 70);
        for (int i = 0; i < 7; i++) {          // oldest day first, today last
            int k = i;
            LinearLayout col = Ui.col(this);
            col.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            TextView mn = Ui.text(this, w[k] + r[k] > 0 ? String.valueOf(w[k] + r[k]) : "", 10, Ui.DIM);
            mn.setGravity(Gravity.CENTER);
            col.addView(mn);
            View rd = new View(this); rd.setBackground(Ui.round(Ui.mix(Ui.ACCENT, Ui.BG, 0.45f), 3));
            View wt = new View(this); wt.setBackground(Ui.round(Ui.ACCENT, 3));
            if (r[k] > 0) col.addView(rd, new LinearLayout.LayoutParams(Ui.dp(this, 18), Math.max(3, bar * r[k] / max)));
            if (w[k] > 0) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(this, 18), Math.max(3, bar * w[k] / max)); p.topMargin = r[k] > 0 ? 2 : 0; col.addView(wt, p); }
            TextView nm = Ui.text(this, names[k], 11, k == 6 ? Ui.TEXT : Ui.DIM);
            nm.setGravity(Gravity.CENTER);
            nm.setPadding(0, Ui.dp(this, 4), 0, 0);
            col.addView(nm);
            bars.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(bars);
        TextView sum = Ui.text(this, tw + " min watched · " + tr + " min read  (on this phone; the PC adds it to its own chart at each sync)", 12, Ui.DIM);
        sum.setPadding(0, Ui.dp(this, 10), 0, 0);
        card.addView(sum);
        Ui.add(l, card, 8);
    }

    /** Lines to come back to (marked ★ or stumbled on) per video, like the PC's Home → Review. */
    private void reviewBox(LinearLayout l) {
        File[] fs = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
        List<File> with = new ArrayList<>();
        if (fs != null) for (File f : fs) { int[] c = Marks.counts(f.getName()); if (c[0] + c[1] > 0) with.add(f); }
        if (with.isEmpty()) return;
        with.sort((a, b) -> Long.compare(app.prefs().getLong("vpost_" + b.getName(), 0), app.prefs().getLong("vpost_" + a.getName(), 0)));
        int total = 0;
        for (File f : with) { int[] c = Marks.counts(f.getName()); total += c[0] + c[1]; }
        LinearLayout head = Ui.row(this);
        head.addView(Ui.weight(Ui.title(this, "Review"), 1));
        TextView n = Ui.text(this, total + (total == 1 ? " line" : " lines"), 13, Ui.DIM);
        head.addView(n);
        Ui.add(l, head, 0);
        l.addView(Ui.hint(this, "Lines you marked ★ or stumbled on (replayed, looked a word up in) and made no card of yet."));
        LinearLayout g = Ui.card(this);
        g.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 4));
        for (int i = 0; i < Math.min(5, with.size()); i++) {
            File f = with.get(i);
            int[] c = Marks.counts(f.getName());
            if (i > 0) { View line = new View(this); line.setBackgroundColor(Ui.LINE); g.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
            LinearLayout r = Ui.row(this);
            r.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            TextView t = Ui.text(this, Player.niceName(f), 15, Ui.TEXT);
            t.setSingleLine(true); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView ct = Ui.text(this, (c[0] > 0 ? c[0] + " ★" : "") + (c[0] > 0 && c[1] > 0 ? " · " : "") + (c[1] > 0 ? c[1] + " hard" : ""), 13, Ui.DIM);
            ct.setPadding(Ui.dp(this, 10), 0, 0, 0);
            r.addView(ct);
            r.setOnClickListener(v -> reviewLines(f));
            g.addView(r);
        }
        Ui.add(l, g, 8);
    }

    /** The lines of one video to review: tap one to watch it there; long-press to take it off the list. */
    private void reviewLines(File f) {
        List<Marks.Line> ls = Marks.list(f.getName());
        if (ls.isEmpty()) { show(); return; }
        String[] items = new String[ls.size()];
        for (int i = 0; i < items.length; i++) items[i] = (ls.get(i).marked() ? "★ " : "") + Ui.time(ls.get(i).start) + "  " + ls.get(i).text;
        android.app.AlertDialog dlg = Ui.dialog(this).setTitle(Player.niceName(f) + " (" + items.length + ")")
                .setItems(items, (d, i) -> { Intent it = new Intent(this, Player.class); it.putExtra("path", f.getPath()); it.putExtra("at", ls.get(i).start); startActivity(it); })
                .setNeutralButton("Clear all", (d, w) -> { Marks.clear(f.getName()); show(); })
                .setNegativeButton("Close", null).create();
        dlg.setOnShowListener(x -> dlg.getListView().setOnItemLongClickListener((a, v, pos, id) -> {
            Marks.remove(f.getName(), ls.get(pos).start);
            Ui.dismiss(dlg);
            show();
            reviewLines(f);
            return true;
        }));
        if (!Ui.gone(this)) dlg.show();
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
        art.setBackground(Ui.round(Ui.mix(Ui.ACCENT, (h & 1) == 0 ? Ui.BG : Ui.TEXT, 0.15f + (h % 5) * 0.08f), Ui.dp(this, Ui.RS)));
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
        if (openBookId != null) for (BookInfo bk : bookList(app.lang())) if (bk.id.equals(openBookId)) return bookPage(bk);
        openBookId = null;
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        LinearLayout acts = Ui.row(this);
        acts.addView(Ui.weight(Ui.primary(this, "Add a book", v -> pick(REQ_BOOK, new String[]{"application/epub+zip", "text/plain"}, false)), 1));
        acts.addView(Ui.weight(Ui.button(this, "From the PC", v -> pcBooks()), 1));
        Ui.add(l, acts, 8);
        Ui.add(l, Ui.button(this, "📷  Photo of a page (the PC reads it)", v -> {
            if (app.pc() == null) { Ui.toast(this, "Link the PC first (Settings)"); return; }
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "Photos of the pages"), REQ_PHOTO);
        }), 8);
        String lang = app.lang();
        List<BookInfo> books = bookList(lang);
        if (books.isEmpty()) l.addView(Ui.hint(this, "No " + lang.toUpperCase(Locale.ROOT) + " books yet. Add an EPUB or text file from the phone, or copy a book from the Reader on the PC."));
        for (BookInfo bk : books) {
            int[] pos = Reader.position(bk.id);
            String sub = bk.chapters + " chapters" + (pos[0] > 0 || pos[1] > 0 ? " · at chapter " + (pos[0] + 1) : "");
            LinearLayout it = Ui.item(this, bk.title, sub, v -> { openBookId = bk.id; show(); });
            float frac = app.prefs().getFloat("rf_" + bk.id, 0);
            if (frac > 0.005f) { View pr = Ui.progress(this, frac); ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = Ui.dp(this, 8); it.addView(pr); }
            it.setOnLongClickListener(v -> { bookMenu(bk); return true; });
            Ui.add(l, it, 8);
        }
        return Ui.scroll(this, l);
    }

    private void bookMenu(BookInfo bk) {
        Ui.dialog(this).setTitle(bk.title).setItems(new String[]{"Rename…", "Delete this book"}, (d, w) -> {
            if (w == 0) renameBook(bk);
            else Ui.dialog(this).setTitle("Delete “" + bk.title + "”?").setMessage("The book is removed from the phone (the PC keeps its copy).")
                    .setPositiveButton("Delete", (d2, i) -> { bk.file.delete(); openBookId = null; show(); }).setNegativeButton("Keep", null).show();
        }).show();
    }

    private void renameBook(BookInfo bk) {
        android.widget.EditText in = Ui.input(this, bk.title, "Title");
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(in);
        Ui.dialog(this).setTitle("Rename the book").setView(box)
                .setPositiveButton("Rename", (d, i) -> {
                    String t = in.getText().toString().trim();
                    app.prefs().edit().putString("btitle_" + bk.id, t).apply();     // "" = back to the book's own title
                    show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** The page of one book: where you are, Continue, contents, rename, delete (like the PC's Library). */
    private View bookPage(BookInfo bk) {
        LinearLayout l = Ui.col(this);
        int pad = Ui.dp(this, 12);
        l.setPadding(pad, 0, pad, pad * 2);
        l.addView(backRow("Books", () -> { openBookId = null; show(); }));
        LinearLayout hero = Ui.card(this);
        TextView art = Ui.text(this, initials(bk.title), 30, Ui.ON_ACC);
        art.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        art.setGravity(Gravity.CENTER);
        int h = Math.abs(bk.title.hashCode());
        art.setBackground(Ui.round(Ui.mix(Ui.ACCENT, (h & 1) == 0 ? Ui.BG : Ui.TEXT, 0.15f + (h % 5) * 0.08f), Ui.dp(this, Ui.RS)));
        LinearLayout top = Ui.row(this);
        top.addView(art, new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 100)));
        LinearLayout tx = Ui.col(this);
        tx.setPadding(Ui.dp(this, 14), 0, 0, 0);
        TextView tt = Ui.text(this, bk.title, 19, Ui.TEXT);
        tt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tx.addView(tt);
        float frac = app.prefs().getFloat("rf_" + bk.id, 0);
        int[] pos = Reader.position(bk.id);
        long t = app.prefs().getLong("rt_" + bk.id, 0);
        TextView st = Ui.text(this, bk.chapters + " chapters · " + Math.round(frac * 100) + "% read" + (t > 0 ? " · " + Ui.ago(t) : ""), 13, Ui.DIM);
        st.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        tx.addView(st);
        tx.addView(Ui.progress(this, frac));
        if (bk.id.startsWith("pc")) tx.addView(Ui.hint(this, "From the Reader on the PC: your place is kept in step."));
        top.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hero.addView(top);
        Ui.add(l, hero, 12);
        Ui.add(l, Ui.primary(this, frac > 0.005f ? "Continue · chapter " + (pos[0] + 1) : "Start reading", v -> openBook(bk)), 8);
        Ui.add(l, Ui.buttons(this, Ui.button(this, "Contents", v -> bookContents(bk)), Ui.button(this, "Rename", v -> renameBook(bk)),
                Ui.button(this, "Delete", v -> bookMenu(bk))), 8);
        return Ui.scroll(this, l);
    }

    private void bookContents(BookInfo bk) {
        app.bg(() -> {
            try {
                Book b = Book.read(bk.file);
                String[] names = new String[b.chapters.size()];
                for (int i = 0; i < names.length; i++) names[i] = (i + 1) + ". " + b.chapters.get(i).title;
                app.post(() -> Ui.dialog(this).setTitle("Contents").setItems(names, (d, i) -> {
                    app.prefs().edit().putInt("rc_" + bk.id, i).putInt("rp_" + bk.id, 0).apply();
                    openBook(bk);
                }).show());
            } catch (IOException | RuntimeException e) { app.post(() -> Ui.toast(this, "Could not open the book")); }
        });
    }

    /** "‹ Videos": the way back from a series or book page. */
    private View backRow(String name, Runnable back) {
        TextView b = Ui.text(this, "‹  " + name, 15, Ui.ACCENT);
        b.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        b.setOnClickListener(v -> back.run());
        return b;
    }

    /** What the book list shows of a book; read once per file version (books can be megabytes). */
    static final class BookInfo {
        File file; long stamp; String id, title, orig, lang; int chapters;
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
            String own = app.prefs().getString("btitle_" + bi.id, "");           // a name you gave it here
            if (bi.orig == null) bi.orig = bi.title;
            bi.title = own.isEmpty() ? bi.orig : own;
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
        if (openShow != null) { View v = showPage(openShow); if (v != null) return v; openShow = null; }
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
            Map<String, List<File>> groups = showGroups(fs);
            List<File> singles = new ArrayList<>();
            for (File f : fs) { Shows.Ep e = Shows.of(Player.videoName(f)); if (e.show.isEmpty() || !groups.containsKey(Shows.key(e.show))) singles.add(f); }
            Arrays.sort(fs);
            android.content.SharedPreferences sp = app.prefs();
            List<String> keys = new ArrayList<>(groups.keySet());
            keys.sort((x, y) -> Long.compare(lastTouched(groups.get(y)), lastTouched(groups.get(x))));
            if (!keys.isEmpty()) l.addView(Ui.title(this, "Series"));
            for (String k : keys) Ui.add(l, showCard(k, groups.get(k)), 8);
            if (!singles.isEmpty()) l.addView(Ui.title(this, keys.isEmpty() ? "Videos" : "Single videos"));
            for (File f : singles) Ui.add(l, videoItem(f, false), 8);
        }
        return Ui.scroll(this, l);
    }

    /** The videos that belong to one show (two or more files with the same show name), episodes in order. */
    private Map<String, List<File>> showGroups(File[] fs) {
        Map<String, List<File>> m = new java.util.LinkedHashMap<>();
        for (File f : fs) {
            Shows.Ep e = Shows.of(Player.videoName(f));
            if (!e.show.isEmpty()) m.computeIfAbsent(Shows.key(e.show), k -> new ArrayList<>()).add(f);
        }
        m.values().removeIf(x -> x.size() < 2);
        for (List<File> x : m.values()) x.sort((p, q) -> {
            Shows.Ep a = Shows.of(Player.videoName(p)), b = Shows.of(Player.videoName(q));
            return a.order() != b.order() ? Long.compare(a.order(), b.order()) : p.getName().compareToIgnoreCase(q.getName());
        });
        return m;
    }

    private long lastTouched(List<File> eps) {
        long t = 0;
        for (File f : eps) t = Math.max(t, app.prefs().getLong("vpost_" + f.getName(), 0));
        return t;
    }

    private boolean watched(File f) { return app.prefs().getBoolean("vdone_" + f.getName(), false); }

    /** The episode to go on with: the one you left in the middle, else the first not watched after the last watched one. */
    private File nextEpisode(List<File> eps) {
        File mid = null; long best = -1;
        for (File f : eps) {
            float at = app.prefs().getFloat("vpos_" + f.getName(), 0);
            long t = app.prefs().getLong("vpost_" + f.getName(), 0);
            if (!watched(f) && at > 5 && t > best) { mid = f; best = t; }
        }
        if (mid != null) return mid;
        int last = -1;
        for (int i = 0; i < eps.size(); i++) if (watched(eps.get(i))) last = i;
        for (int i = last + 1; i < eps.size(); i++) if (!watched(eps.get(i))) return eps.get(i);
        for (File f : eps) if (!watched(f)) return f;
        return eps.get(0);
    }

    private String showName(List<File> eps) { return Shows.of(Player.videoName(eps.get(0))).show; }

    private View showCard(String key, List<File> eps) {
        int done = 0;
        for (File f : eps) if (watched(f)) done++;
        File nx = nextEpisode(eps);
        Shows.Ep ne = Shows.of(Player.videoName(nx));
        String sub = eps.size() + " episodes · " + done + " watched" + (done < eps.size() ? " · next: " + ne.label() : " · all watched");
        LinearLayout it = Ui.item(this, showName(eps), sub, v -> { openShow = key; show(); });
        View pr = Ui.progress(this, done / (float) eps.size());
        ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = Ui.dp(this, 8);
        it.addView(pr);
        return it;
    }

    /** The page of one show: its episodes in order, the one to continue with, which are watched (like the PC's series page). */
    private View showPage(String key) {
        File[] fs = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
        List<File> eps = fs == null ? null : showGroups(fs).get(key);
        if (eps == null) return null;
        LinearLayout l = Ui.col(this);
        int pad = Ui.dp(this, 12);
        l.setPadding(pad, 0, pad, pad * 2);
        l.addView(backRow("Videos", () -> { openShow = null; show(); }));
        int done = 0;
        for (File f : eps) if (watched(f)) done++;
        LinearLayout hero = Ui.card(this);
        TextView tt = Ui.text(this, showName(eps), 20, Ui.TEXT);
        tt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hero.addView(tt);
        TextView st = Ui.text(this, eps.size() + " episodes · " + done + " watched · " + Math.round(done * 100f / eps.size()) + "%", 13, Ui.DIM);
        st.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        hero.addView(st);
        hero.addView(Ui.progress(this, done / (float) eps.size()));
        Ui.add(l, hero, 12);
        File nx = nextEpisode(eps);
        float at = app.prefs().getFloat("vpos_" + nx.getName(), 0);
        String what = Shows.of(Player.videoName(nx)).label();
        Ui.add(l, Ui.primary(this, done == eps.size() ? "Watch again · " + what : (at > 5 && !watched(nx) ? "Continue · " + what + " at " + Ui.time(at) : "Play · " + what), v -> openVideo(nx)), 12);
        for (File f : eps) Ui.add(l, videoItem(f, true), 8);
        return Ui.scroll(this, l);
    }

    /** One video in a list. IN_SHOW: the episode number leads and watched ones are ticked. */
    private LinearLayout videoItem(File f, boolean inShow) {
        int subs = Player.subsOf(f).size();
        double at = app.prefs().getFloat("vpos_" + f.getName(), 0);
        boolean linked = f.getName().endsWith(".vlink");
        long fsize = linked ? linkSize(f) : f.length();
        boolean done = watched(f);
        Shows.Ep e = Shows.of(Player.videoName(f));
        String head = inShow ? (done ? "✓  " : "") + (e.episode >= 0 ? e.label() : Player.niceName(f)) : Player.niceName(f);
        String sub = (fsize < 0 ? "video not found (add it again)" : Ui.size(fsize) + (linked ? " (stays where it is)" : "")) + " · " + (subs == 0 ? "no subtitles" : subs + " subtitle file" + (subs > 1 ? "s" : ""))
                + (done ? " · watched" : at > 5 ? " · at " + Ui.time(at) : "");
        LinearLayout it = Ui.item(this, head, sub, v -> openVideo(f));
        float dur = app.prefs().getFloat("vdur_" + f.getName(), 0);
        if (!done && at > 5 && dur > 30) { View pr = Ui.progress(this, (float) (at / dur)); ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = Ui.dp(this, 8); it.addView(pr); }
        it.setOnLongClickListener(v -> {
            String[] acts = {done ? "Mark as not watched" : "Mark as watched", linked ? "Remove from the app (the video itself stays)" : "Delete this video and its subtitles"};
            Ui.dialog(this).setTitle(Player.niceName(f)).setItems(acts, (d, w) -> {
                if (w == 0) {
                    android.content.SharedPreferences.Editor ed = app.prefs().edit().putBoolean("vdone_" + f.getName(), !done).putLong("vpost_" + f.getName(), System.currentTimeMillis());
                    if (done) ed.putFloat("vpos_" + f.getName(), 0);
                    ed.apply();
                } else {
                    for (File s : Player.subsOf(f)) s.delete();
                    f.delete();
                }
                show();
            }).show();
            return true;
        });
        return it;
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
    private String wordFilter = "";

    private View words() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        WordStore ws = app.store();
        List<Map<String, Object>> pend = ws.pending();
        if (!pend.isEmpty()) {
            LinearLayout head = Ui.row(this);
            head.addView(Ui.weight(Ui.title(this, pend.size() + " card(s) waiting"), 1));
            head.addView(Ui.small(this, "Send now", v -> sync()));
            Ui.add(l, head, 0);
            for (Map<String, Object> c : pend) {
                String id = Json.str(c.get("id"), "");
                Ui.add(l, Ui.item(this, Json.str(c.get("word"), ""), Json.str(c.get("sentence"), ""), v ->
                        Ui.dialog(this).setTitle(Json.str(c.get("word"), "")).setItems(new String[]{"Remove this card"}, (d, i) -> { ws.removePending(id); show(); }).show()), 6);
            }
        }
        List<Map<String, Object>> saved = ws.saved();
        l.addView(Ui.title(this, "Saved words" + (saved.isEmpty() ? "" : " (" + saved.size() + ")")));
        if (saved.isEmpty()) l.addView(Ui.hint(this, "Tap “Save” in a word card to keep a word for later."));
        if (saved.size() > 6) {
            android.widget.EditText q = Ui.input(this, wordFilter, "Search words, sentences, sources");
            q.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            q.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void afterTextChanged(android.text.Editable e) { wordFilter = e.toString(); fillSaved(l, saved, q); }
            });
            Ui.add(l, q, 8);
        }
        fillSaved(l, saved, null);
        return Ui.scroll(this, l);
    }

    /** The saved words that match the search; redrawn in place while you type (the search box keeps its focus). */
    private void fillSaved(LinearLayout l, List<Map<String, Object>> saved, android.widget.EditText box) {
        for (int i = l.getChildCount() - 1; i >= 0; i--) if ("saved".equals(l.getChildAt(i).getTag())) l.removeViewAt(i);
        String f = wordFilter.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (Map<String, Object> s : saved) {
            String w = Json.str(s.get("word"), ""), sn = Json.str(s.get("sentence"), ""), src = Json.str(s.get("source"), "");
            if (!f.isEmpty() && !(w + " " + sn + " " + src).toLowerCase(Locale.ROOT).contains(f)) continue;
            LinearLayout it = Ui.item(this, w, sn + (src.isEmpty() ? "" : "\n" + src), v -> {
                Cards.Request r = new Cards.Request();
                r.lang = app.lang(); r.sentence = sn; r.title = src;
                WordSheet.show(this, app.lang(), w, r, this::show);
            });
            it.setTag("saved");
            Ui.add(l, it, 6);
            if (++shown >= 150) break;
        }
        if (shown == 0 && !saved.isEmpty()) { TextView t = Ui.hint(this, "Nothing matches “" + wordFilter + "”."); t.setTag("saved"); l.addView(t); }
    }

    // ================================================================ settings
    private View settings() {
        LinearLayout l = Ui.col(this);
        int pad = Ui.dp(this, 12);
        l.setPadding(pad, 0, pad, pad * 2);
        TextView head = Ui.title(this, "Settings");
        l.addView(head);

        // ---- the PC
        l.addView(Ui.section(this, "The PC"));
        LinearLayout g = Ui.group(this);
        PcLink pc = app.pc();
        LinearLayout lk = Ui.col(this);
        lk.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        TextView lt = Ui.text(this, pc == null ? "Not linked" : "Linked to " + pc.base.replaceFirst("^https?://", ""), 15, Ui.TEXT);
        lt.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        lk.addView(lt);
        TextView lh = Ui.hint(this, "On the PC: Glossa → Phone (gear menu) → switch Phone access on, copy the link and paste it here. Same Wi-Fi, or both on Tailscale.");
        lk.addView(lh);
        android.widget.EditText link = Ui.input(this, pc == null ? "" : pc.link(), "http://192.168.1.20:8780/?t=…");
        Ui.add(lk, link, 8);
        lk.addView(Ui.buttons(this, Ui.primary(this, "Save and test", v -> {
            PcLink p = PcLink.parse(link.getText().toString());
            if (p == null) { Ui.toast(this, "That is not a Glossa link (it has ?t=… at the end)"); return; }
            app.prefs().edit().putString("pc_link", p.link()).apply();
            app.bg(() -> {
                try {
                    Map<String, Object> h = p.get("/api/pocket/hello", 6000);
                    app.post(() -> { Ui.alert(this, "Connected", "Glossa on “" + Json.str(h.get("name"), "the PC") + "” answered." + (Boolean.TRUE.equals(h.get("anki")) ? "\nAnki is open there." : "\nAnki is not open there (cards wait until it is).")); show(); });
                } catch (IOException e) { app.post(() -> Ui.alert(this, "Not connected", explain(e))); }
            });
        }), Ui.button(this, "Forget", v -> { app.prefs().edit().remove("pc_link").apply(); show(); })));
        g.addView(lk);
        Ui.switchRow(g, "Sync by itself", "When the app opens (at most every 10 minutes). If the PC is off nothing happens.", app.prefs().getBoolean("auto_sync", true),
                (b, on) -> app.prefs().edit().putBoolean("auto_sync", on).apply());
        Ui.add(l, g, 0);
        l.addView(Ui.hint(this, "Exchanged: known and looked-up words, waiting cards, where you stopped in videos and books, and the time you spent watching and reading."));

        // ---- look
        l.addView(Ui.section(this, "Look"));
        g = Ui.group(this);
        String cur = app.prefs().getString("theme", "pc");
        Ui.pickRow(g, "Theme", "pc".equals(cur) ? "Same as the PC" : Ui.themeName(cur), v -> {
            String[] names = new String[Ui.THEMES.length + 1];
            names[0] = "Same as on the PC";
            for (int i = 0; i < Ui.THEMES.length; i++) names[i + 1] = Ui.THEMES[i][1];
            Ui.dialog(this).setTitle("Theme").setItems(names, (dl, i) -> {
                app.prefs().edit().putString("theme", i == 0 ? "pc" : Ui.THEMES[i - 1][0]).apply();
                Ui.load(this);
                recreate();
            }).show();
        });
        String acc = app.prefs().getString("accent", "");
        Ui.pickRow(g, "Accent colour", acc.isEmpty() ? "The theme's own" : Ui.accentName(acc), v -> {
            String[] names = new String[Ui.ACCENTS.length];
            for (int i = 0; i < names.length; i++) names[i] = Ui.ACCENTS[i][0];
            Ui.dialog(this).setTitle("Accent colour").setItems(names, (dl, i) -> {
                app.prefs().edit().putString("accent", Ui.ACCENTS[i][1]).apply();
                Ui.load(this);
                recreate();
            }).show();
        });
        String ic = app.prefs().getString("icon", "original");
        Ui.pickRow(g, "App icon", "pc".equals(ic) ? "Same as the PC" : Icons.name(ic), v -> {
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
        });
        Ui.switchRow(g, "Coloured words", "Unknown, looked-up and card words coloured in videos and books, as on the PC", app.prefs().getBoolean("colours", true),
                (b, on) -> app.prefs().edit().putBoolean("colours", on).apply());
        Ui.add(l, g, 0);

        // ---- watching and reading
        l.addView(Ui.section(this, "Watching and reading"));
        g = Ui.group(this);
        Ui.pickRow(g, "Stopwatch: stops after", idleName(app.prefs().getInt("sw_idle_watch", 20)) + " (video)", v -> idlePick("sw_idle_watch"));
        Ui.pickRow(g, "Stopwatch: stops after", idleName(app.prefs().getInt("sw_idle_read", 90)) + " (book)", v -> idlePick("sw_idle_read"));
        Ui.switchRow(g, "Primed listening", "Each line: its translation first (paused), then you hear it without text, then the text shows", app.prefs().getBoolean("prime", false),
                (b, on) -> app.prefs().edit().putBoolean("prime", on).apply());
        Ui.switchRow(g, "Primed listening: only lines with an unknown word", "The other lines just play", "unk".equals(app.prefs().getString("prime_when", "all")),
                (b, on) -> app.prefs().edit().putString("prime_when", on ? "unk" : "all").apply());
        Ui.pickRow(g, "Repeat a line", app.prefs().getInt("loop_times", 1) == 0 ? "until you go on" : app.prefs().getInt("loop_times", 1) + "×", v -> {
            int[] n = {1, 2, 3, 5, 0};
            String[] names = {"once", "2 times", "3 times", "5 times", "until you go on"};
            Ui.dialog(this).setTitle("↺ plays the line").setItems(names, (d, i) -> { app.prefs().edit().putInt("loop_times", n[i]).apply(); show(); }).show();
        });
        Ui.add(l, g, 0);
        l.addView(Ui.hint(this, "The stopwatch counts while a video plays (or is paused for a moment) and while you read. Its time goes to the PC at each sync and shows in Home → This week there."));

        // ---- dictionaries
        l.addView(Ui.section(this, "Dictionaries"));
        g = Ui.group(this);
        for (Dict d : app.dicts.list()) {
            Ui.srow(g, d.title + " [" + d.lang + "]", d.summary(), null, v ->
                    Ui.dialog(this).setTitle(d.title).setItems(new String[]{"Delete this dictionary"}, (dl, i) -> { app.dicts.remove(d); show(); }).show());
        }
        LinearLayout imp = Ui.col(this);
        imp.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        imp.addView(Ui.hint(this, "The same wty zip files the PC uses (wty-it-it.zip, and the -ipa one for pronunciation), or a frequency list (Leipzig). A minute or two; then it works offline."));
        imp.addView(Ui.primary(this, "Import a dictionary (.zip)", v -> pick(REQ_DICT, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"}, false)));
        if (g.getChildCount() > 0) { View line = new View(this); line.setBackgroundColor(Ui.LINE); g.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)); }
        g.addView(imp);
        Ui.add(l, g, 0);

        // ---- cards
        l.addView(Ui.section(this, "Cards"));
        g = Ui.group(this);
        boolean anki = Cards.useAnki();
        Ui.switchRow(g, "Make cards in AnkiDroid", anki ? "Cards go into AnkiDroid at once" : "Off: cards wait and the PC makes them at the next sync", anki, (b, on) -> {
            app.prefs().edit().putString("card_mode", on ? "anki" : "pc").apply();
            show();
        });
        Ui.pickRow(g, "AnkiDroid fields", "Set up", v -> Cards.setup(this, app.lang(), this::show));
        Ui.switchRow(g, "Copy videos into the app", "Off: a video stays where it is and the app remembers the place (no second copy)", app.prefs().getBoolean("copy_videos", false),
                (b, on) -> app.prefs().edit().putBoolean("copy_videos", on).apply());
        Ui.add(l, g, 0);
        l.addView(Ui.hint(this, "Sound and picture of a video line are cut on the phone (MP4 / MKV). AAC, Opus and Vorbis are cut as they are; MP3, FLAC and similar become a small .wav. With no decoder (AC-3, often) the card is left to the PC."));

        // ---- updates, problems
        l.addView(Ui.section(this, "This app"));
        g = Ui.group(this);
        Ui.srow(g, "Version " + Updater.myName(app), "New versions are built on GitHub and install over this one (words, books and dictionaries stay)", Ui.small(this, "Check", v -> checkUpdate(true)), null);
        if (Updater.repo(app).isEmpty() || !app.getString(R.string.update_repo).trim().matches("[\\w.-]+/[\\w.-]+")) {
            LinearLayout rr = Ui.col(this);
            rr.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 12));
            android.widget.EditText rp = Ui.input(this, app.prefs().getString("update_repo", ""), "yourname/glossa");
            Ui.add(rr, rp, 6);
            rr.addView(Ui.button(this, "Save the GitHub project", v -> { app.prefs().edit().putString("update_repo", rp.getText().toString().trim()).apply(); show(); }));
            View line = new View(this); line.setBackgroundColor(Ui.LINE); g.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            g.addView(rr);
        }
        File last = new File(getFilesDir(), "crash-seen.txt");
        Ui.srow(g, "Problem report", last.isFile() ? "Something went wrong last time: copy the report and send it" : "Nothing has gone wrong so far",
                last.isFile() ? Ui.small(this, "Show", v -> problemReport("Problem report", last)) : null, null);
        Ui.add(l, g, 0);
        return Ui.scroll(this, l);
    }

    static String idleName(int s) { return s <= 0 ? "never" : s < 60 ? s + " s" : (s / 60) + " min"; }

    private void idlePick(String key) {
        int[] n = {10, 20, 30, 60, 90, 180, 300, 0};
        String[] names = new String[n.length];
        for (int i = 0; i < n.length; i++) names[i] = n[i] == 0 ? "never (counts while open)" : "after " + idleName(n[i]) + " with nothing happening";
        Ui.dialog(this).setTitle("The stopwatch stops").setItems(names, (d, i) -> { app.prefs().edit().putInt(key, n[i]).apply(); show(); }).show();
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
        else if (req == REQ_PHOTO) photoBook(uris);
    }

    /** Pictures of book pages: sent to the PC (tesseract reads the text), the new book is then copied here. */
    private void photoBook(List<Uri> uris) {
        PcLink pc = app.pc();
        if (pc == null) return;
        String lang = app.lang();
        Ui.toast(this, "Sending " + uris.size() + " picture(s) to the PC to be read…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        app.bg(() -> {
            try {
                List<Object> images = new ArrayList<>();
                for (Uri u : uris.subList(0, Math.min(10, uris.size()))) images.add(jpegDataUrl(u));
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("lang", lang); body.put("images", images);
                Map<String, Object> r = pc.post("/api/reader/photo", body, 300000);
                String key = Json.str(r.get("key"), "");
                if (key.isEmpty()) throw new IOException("The PC could not read the text from the picture");
                Book bk = null;
                for (int tries = 0; tries < 45 && bk == null; tries++) {           // the PC builds the book from the text: a few seconds
                    try { bk = Book.fromJson(pc.get("/api/pocket/book?key=" + key, 60000)); }
                    catch (IOException e) { if (tries == 44) throw e; try { Thread.sleep(2000); } catch (InterruptedException ie) { return; } }
                }
                bk.id = "pc" + key;
                if (bk.lang.isEmpty()) bk.lang = lang;
                bk.write(new File(app.dir("books"), bk.id + ".json"));
                Book done = bk;
                app.post(() -> { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Ui.toast(this, "“" + done.title + "” is in your books"); show(); });
            } catch (IOException | RuntimeException e) {
                app.post(() -> { getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); Ui.alert(this, "Could not read the page", e instanceof IOException ? explain((IOException) e) : String.valueOf(e)); });
            }
        });
    }

    /** A picture as a JPEG data URL, at most 2600 px on the long side (the text stays readable, the upload stays small). */
    private String jpegDataUrl(Uri u) throws IOException {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(u)) { android.graphics.BitmapFactory.decodeStream(in, null, o); }
        int big = Math.max(o.outWidth, o.outHeight), k = 1;
        while (big / (k * 2) >= 2600) k *= 2;
        o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = k;
        android.graphics.Bitmap b;
        try (InputStream in = getContentResolver().openInputStream(u)) { b = android.graphics.BitmapFactory.decodeStream(in, null, o); }
        if (b == null) throw new IOException("That is not a picture");
        try {
            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                int ori = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = ori == 6 ? 90 : ori == 3 ? 180 : ori == 8 ? 270 : 0;
            } catch (IOException | RuntimeException ignored) { }
            if (rot != 0) { android.graphics.Matrix m = new android.graphics.Matrix(); m.postRotate(rot); b = android.graphics.Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, bo);
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
        } finally { b.recycle(); }
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
