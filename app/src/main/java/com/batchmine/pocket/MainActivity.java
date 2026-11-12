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
    static final int REQ_BOOK = 1, REQ_VIDEO = 2, REQ_DICT = 3, REQ_PHOTO = 4, REQ_VFOLDER = 5, REQ_BFOLDER = 6;
    private App app;
    private FrameLayout body;
    private TextView langBtn, status;
    private String tab = "home";
    private static volatile boolean syncing;
    private final List<View> tabs = new ArrayList<>();
    private android.widget.ImageView gear;
    private String before = "home";
    private String openShow, openBookId, openList, tagFilter = "", bookTagFilter = "";                       // a series page / a book page is open inside its tab                             // the tab to go back to from Settings

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
        String page = tab + "|" + openShow + "|" + openBookId + "|" + openList;
        android.widget.ScrollView was = firstScroll(body);
        int keepY = was != null && page.equals(shownPage) ? was.getScrollY() : -1;   // the same page drawn again (a sync, a change): stay where you were
        shownPage = page;
        body.removeAllViews();
        queueSrc = null;
        View v;
        switch (tab) {
            case "videos": v = videos(); break;
            case "words": v = words(); break;
            case "settings": v = settings(); break;
            case "books": v = books(); break;
            default: v = home();
        }
        body.addView(v);
        if (keepY > 0) {
            android.widget.ScrollView now = firstScroll(body);
            if (now != null) {
                now.scrollTo(0, keepY);                                  // before the first frame, so it does not flash at the top
                now.post(() -> now.scrollTo(0, keepY));                  // and again once its content is measured
            }
        }
    }

    private String shownPage = "";

    /** The scrolling list of a page (the first ScrollView inside it). */
    private static android.widget.ScrollView firstScroll(View v) {
        if (v instanceof android.widget.ScrollView) return (android.widget.ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) { android.widget.ScrollView s = firstScroll(g.getChildAt(i)); if (s != null) return s; }
        }
        return null;
    }

    // ================================================================ language, sync
    private void pickLang() {
        List<String> langs = new ArrayList<>(Arrays.asList("it", "de", "en", "fr", "es", "hr", "pt", "nl", "ru", "la", "grc"));
        File[] ds = app.dicts.root.listFiles();
        for (Dict d : app.dicts.list()) if (!d.lang.isEmpty() && !langs.contains(d.lang)) langs.add(0, d.lang);
        String[] items = langs.toArray(new String[0]);
        Ui.choose(this, "Language", items, -1, i -> { app.setLang(items[i]); show(); });
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
                Map<String, Object> ex = new java.util.LinkedHashMap<>(), mk = new java.util.LinkedHashMap<>(), bt = new java.util.LinkedHashMap<>();
                List<Object> done = new ArrayList<>();
                if (vs != null) for (File f : vs) {
                    String nm = Player.videoName(f).toLowerCase(Locale.ROOT);
                    if (sp.getBoolean("vdone_" + f.getName(), false)) done.add(nm);
                    List<Object> rows = new ArrayList<>();
                    for (Marks.Line ln : Marks.list(f.getName())) rows.add(new ArrayList<Object>(Arrays.asList(ln.start, ln.kind, ln.text)));
                    if (!rows.isEmpty()) mk.put(nm, rows);
                }
                if (bs != null) for (File f : bs) {
                    String id = f.getName().replaceAll("\\.json$", ""), own = sp.getString("btitle_" + id, "");
                    if (!own.isEmpty()) bt.put(id.substring(2), own);
                }
                ex.put("meta", Details.toPush());
                ex.put("video_done", done);
                ex.put("marks", mk);
                ex.put("book_titles", bt);
                out.put("extra", ex);
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
                Map<String, Object> ex = Json.obj(pulled.get("extra"));
                if (!Json.obj(ex.get("card_profile")).isEmpty()) app.prefs().edit().putString("card_profile_" + app.lang(), Json.write(Json.obj(ex.get("card_profile")))).apply();   // the PC's note type and field map
                Details.take(Json.arr(ex.get("meta")), System.currentTimeMillis());
                Details.pushed();
                Map<String, Object> hm = Json.obj(ex.get("home"));
                if (!hm.isEmpty()) {
                    Map<String, Object> gl = Json.obj(hm.get("goal"));
                    StringBuilder tw = new StringBuilder();
                    for (Object o : Json.arr(hm.get("trouble"))) { List<Object> r = Json.arr(o); if (r.size() >= 3) tw.append(r.get(0)).append('\t').append((int) Json.num(r.get(1), 0)).append('\n'); }
                    ed.putInt("pc_goal_cards", (int) Json.num(gl.get("cards"), 10)).putInt("pc_goal_min", (int) Json.num(gl.get("minutes"), 30))
                      .putInt("pc_cards_today", (int) Json.num(hm.get("cards_today"), 0)).putInt("pc_min_today", (int) Json.num(hm.get("minutes_today"), 0))
                      .putInt("pc_streak", (int) Json.num(hm.get("streak"), 0)).putString("pc_trouble", tw.toString())
                      .putString("pc_home_day", Stopwatch.day(System.currentTimeMillis()));
                }
                File[] mine = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
                if (mine != null) {
                    java.util.Set<String> pcDone = new java.util.HashSet<>();
                    for (Object o : Json.arr(ex.get("video_done"))) pcDone.add(String.valueOf(o).toLowerCase(Locale.ROOT));
                    Map<String, Object> pcLang = Json.obj(ex.get("video_lang")), pcMarks = Json.obj(ex.get("marks"));
                    for (File f : mine) {
                        String nm = Player.videoName(f).toLowerCase(Locale.ROOT);
                        if (pcDone.contains(nm) && !sp.getBoolean("vdone_" + f.getName(), false)) ed.putBoolean("vdone_" + f.getName(), true).putLong("vpost_" + f.getName(), Math.max(1, sp.getLong("vpost_" + f.getName(), 1)));
                        String lg = Json.str(pcLang.get(nm), "");
                        if (!lg.isEmpty() && sp.getString("vlang_" + f.getName(), "").isEmpty()) ed.putString("vlang_" + f.getName(), lg);
                        for (Object r : Json.arr(pcMarks.get(nm))) {
                            List<Object> row = Json.arr(r);
                            if (row.size() < 3) continue;
                            double st = Json.num(row.get(0), 0);
                            if (Marks.hasLine(f.getName(), st)) continue;
                            Marks.put(f.getName(), st, "m".equals(String.valueOf(row.get(1))), String.valueOf(row.get(2)));
                        }
                    }
                }
                for (Map.Entry<String, Object> e : Json.obj(ex.get("book_titles")).entrySet()) {
                    String id = "pc" + e.getKey();
                    if (sp.getString("btitle_" + id, "").isEmpty() && new File(app.dir("books"), id + ".json").isFile()) ed.putString("btitle_" + id, String.valueOf(e.getValue()));
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

        todayBox(l);
        weekBox(l);
        reviewBox(l);
        troubleBox(l);

        // continue: the videos and books touched last, newest first
        List<Object[]> recent = new ArrayList<>();          // {time, view}
        File[] vfs = myVideos();
        if (vfs != null) for (File f : vfs) {
            long t = sp.getLong("vpost_" + f.getName(), 0);
            float at = sp.getFloat("vpos_" + f.getName(), 0);
            if (t <= 0 || at < 5) continue;
            LinearLayout it = Ui.item(this, "▶  " + vtitle(f), (vepisode(f).isEmpty() ? "" : vepisode(f) + " · ") + "at " + Ui.time(at) + " · " + Ui.ago(t), v -> openVideo(f));
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
            for (BookInfo bk : books) out.add(cover(bk.title, Math.round(app.prefs().getFloat("rf_" + bk.id, 0) * 100) + "% · " + bk.chapters + " ch.", v -> openBook(bk), bookCover(bk.id, bk.title, 92, 124)));
            return out;
        });
        File[] vs = vfs == null ? new File[0] : vfs.clone();
        Arrays.sort(vs, (a, b) -> Long.compare(sp.getLong("vpost_" + b.getName(), b.lastModified()), sp.getLong("vpost_" + a.getName(), a.lastModified())));
        shelf(l, "Videos", vs.length, "videos", () -> {
            List<View> out = new ArrayList<>();
            for (File f : vs) {
                float at = sp.getFloat("vpos_" + f.getName(), 0);
                out.add(cover(vtitle(f), (vepisode(f).isEmpty() ? "" : vepisode(f).replace("Season ", "S").replace(" · Episode ", " · Ep ") + " · ") + (at > 5 ? "at " + Ui.time(at) : "not started"), v -> openVideo(f), videoTile(f, 120, 68)));
            }
            return out;
        });
        return Ui.scroll(this, l);
    }

    /** The time of the last 7 days (watching and reading on this phone), like the PC's week chart. */
    /** A ring that fills towards a goal, with the number inside (the PC's Home rings). */
    private View ring(int val, int goal, String label) {
        LinearLayout box = Ui.col(this);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        final float f = goal > 0 ? Math.min(1f, val / (float) goal) : 0f;
        final boolean done = goal > 0 && val >= goal;
        View v = new View(this) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(android.graphics.Canvas c) {
                float w = Ui.dp(MainActivity.this, 7), s = Math.min(getWidth(), getHeight()) - w;
                android.graphics.RectF r = new android.graphics.RectF(w / 2, w / 2, w / 2 + s, w / 2 + s);
                p.setStyle(android.graphics.Paint.Style.STROKE); p.setStrokeWidth(w); p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                p.setColor(Ui.mix(Ui.CARD, Ui.TEXT, 0.14f)); c.drawArc(r, 0, 360, false, p);
                if (f > 0) { p.setColor(done ? 0xFF3FB950 : Ui.ACCENT); c.drawArc(r, -90, 360 * f, false, p); }
                p.setStyle(android.graphics.Paint.Style.FILL); p.setStrokeWidth(0); p.setColor(Ui.TEXT); p.setTextAlign(android.graphics.Paint.Align.CENTER);
                p.setTextSize(Ui.dp(MainActivity.this, 20)); p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                c.drawText(String.valueOf(val), getWidth() / 2f, getHeight() / 2f + Ui.dp(MainActivity.this, 2), p);
                p.setTextSize(Ui.dp(MainActivity.this, 11)); p.setColor(Ui.DIM); p.setTypeface(android.graphics.Typeface.DEFAULT);
                c.drawText(goal > 0 ? "of " + goal : "", getWidth() / 2f, getHeight() / 2f + Ui.dp(MainActivity.this, 17), p);
            }
        };
        box.addView(v, new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88)));
        TextView t = Ui.text(this, label, 12, Ui.DIM);
        t.setPadding(0, Ui.dp(this, 4), 0, 0);
        box.addView(t);
        return box;
    }

    /** Today towards the goals set on the PC: cards made (here and there) and minutes watched / read, and the streak. */
    private void todayBox(LinearLayout l) {
        android.content.SharedPreferences sp = app.prefs();
        String today = Stopwatch.day(System.currentTimeMillis());
        boolean fresh = today.equals(sp.getString("pc_home_day", ""));
        int goalC = sp.getInt("pc_goal_cards", 0), goalM = sp.getInt("pc_goal_min", 0);
        if (goalC == 0 && goalM == 0 && !fresh) return;               // never synced with a PC: nothing to measure against
        int cards = sp.getInt("cards_" + today, 0) + (fresh ? sp.getInt("pc_cards_today", 0) : 0);
        int mins = Math.round((Stopwatch.seconds(today, "watch") + Stopwatch.seconds(today, "read")) / 60f) + (fresh ? sp.getInt("pc_min_today", 0) : 0);
        l.addView(Ui.title(this, "Today"));
        LinearLayout card = Ui.card(this);
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER);
        row.addView(Ui.weight(ring(cards, goalC, "cards made"), 1));
        row.addView(Ui.weight(ring(mins, goalM, "minutes"), 1));
        card.addView(row);
        int streak = sp.getInt("pc_streak", 0);
        if (streak > 0) {
            TextView st = Ui.text(this, streak + (streak == 1 ? " day" : " days") + " in a row with a card", 12, Ui.DIM);
            st.setGravity(Gravity.CENTER);
            st.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(st);
        }
        Ui.add(l, card, 8);
    }

    /** Words that keep coming up in the videos you watch and are still unknown (from the PC); tap one for its card. */
    private void troubleBox(LinearLayout l) {
        String raw = app.prefs().getString("pc_trouble", "");
        if (raw.isEmpty()) return;
        l.addView(Ui.title(this, "Words that keep coming up"));
        LinearLayout card = Ui.card(this);
        android.widget.HorizontalScrollView hs = null;
        LinearLayout wrap = Ui.col(this);
        LinearLayout cur = null;
        int width = 0, max = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 24 + 28);
        for (String line : raw.split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 2 || p[0].isEmpty()) continue;
            TextView chip = Ui.pill(this, p[0] + "  " + p[1] + "×", Ui.TEXT, Ui.CARD2);
            chip.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
            chip.setTextSize(14);
            chip.measure(0, 0);
            int cw = chip.getMeasuredWidth() + Ui.dp(this, 6);
            if (cur == null || width + cw > max) { cur = Ui.row(this); cur.setPadding(0, 0, 0, Ui.dp(this, 6)); wrap.addView(cur); width = 0; }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 6);
            cur.addView(chip, lp);
            width += cw;
            final String word = p[0];
            chip.setOnClickListener(v -> {
                Cards.Request r = new Cards.Request();
                r.lang = app.lang(); r.kind = "text"; r.sentence = ""; r.title = "";
                WordSheet.show(this, app.lang(), word, r, () -> show());
            });
        }
        card.addView(wrap);
        TextView h = Ui.text(this, "Unknown words seen in 2 or more videos", 12, Ui.DIM);
        card.addView(h);
        Ui.add(l, card, 8);
    }

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
        File[] fs = myVideos();
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
            TextView t = Ui.text(this, vtitle(f) + (vepisode(f).isEmpty() ? "" : " · " + vepisode(f).replace("Season ", "S").replace(" · Episode ", " E")), 15, Ui.TEXT);
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

    /** The lines of one video to review, in the app's colours: tap one to watch it there, ✕ takes it off the list, "Clear all" empties it. */
    private void reviewLines(File f) {
        List<Marks.Line> ls = Marks.list(f.getName());
        if (ls.isEmpty()) { show(); return; }
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        android.app.Dialog[] d = new android.app.Dialog[1];
        for (Marks.Line ln : ls) {
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10));
            r.setBackground(Ui.press(Ui.CARD, Ui.LINE, Ui.dp(this, Ui.R)));
            LinearLayout col = Ui.col(this);
            TextView meta = Ui.text(this, (ln.marked() ? "★ " : "") + Ui.time(ln.start) + (ln.marked() ? "" : "  ·  hard line"), 12, ln.marked() ? 0xFFF5C211 : Ui.DIM);
            col.addView(meta);
            TextView tx = Ui.text(this, ln.text, 16, Ui.TEXT);
            tx.setPadding(0, Ui.dp(this, 2), 0, 0);
            col.addView(tx);
            r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = Ui.text(this, "✕", 16, Ui.DIM);
            x.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            x.setContentDescription("Take it off the list");
            x.setOnClickListener(v -> { Marks.remove(f.getName(), ln.start); Ui.dismiss(d[0]); show(); reviewLines(f); });
            r.addView(x);
            r.setOnClickListener(v -> { Ui.dismiss(d[0]); Intent it = new Intent(this, Player.class); it.putExtra("path", f.getPath()); it.putExtra("at", ln.start); startActivity(it); });
            Ui.add(l, r, 6);
        }
        Ui.add(l, Ui.flat(this, "Clear all " + ls.size(), v -> { Marks.clear(f.getName()); Ui.dismiss(d[0]); show(); }), 6);
        String ep = vepisode(f);
        d[0] = Ui.sheet(this, vtitle(f) + (ep.isEmpty() ? "" : " · " + ep.replace("Season ", "S").replace(" · Episode ", " E")), Ui.scroll(this, l), true);
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

    /** A book cover: a coloured block with a darker spine and the title on it (the PC shows the cover picture where the book has one). */
    private final java.util.Set<String> coverTried = new java.util.HashSet<>();

    private File coverFile(String id) { return new File(app.dir("books"), id + ".jpg"); }

    /** A book's own cover picture (from the EPUB, or the PC's Reader); fetched in the background for PC books that have none yet. */
    private View bookCover(String id, String title, int wDp, int hDp) {
        File cf = coverFile(id);
        if (cf.length() > 200) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = 2;
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(cf.getPath(), o);
            if (bm != null) {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setImageBitmap(bm);
                iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.round(Ui.CARD2, Ui.dp(this, 6)));
                iv.setClipToOutline(true);
                iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, wDp), Ui.dp(this, hDp)));
                return iv;
            }
        }
        if (id.startsWith("pc") && app.pc() != null && coverTried.add(id)) {
            PcLink pc = app.pc();
            app.bg(() -> {
                try { pc.download("/api/reader/cover.jpg?key=" + id.substring(2), cf, null); if (cf.length() > 200) app.post(() -> { if (isFinishing()) return; show(); }); else cf.delete(); }
                catch (IOException | RuntimeException e) { cf.delete(); }
            });
        }
        return typeCover(title, wDp, hDp);
    }

    private View typeCover(String title, int wDp, int hDp) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(this);
        int h = Math.abs(title.hashCode());
        int base = Ui.mix(Ui.ACCENT, (h & 1) == 0 ? Ui.BG : Ui.TEXT, 0.15f + (h % 5) * 0.08f);
        f.setBackground(Ui.round(base, Ui.dp(this, 6)));
        f.setClipToOutline(true);
        View spine = new View(this);
        spine.setBackgroundColor(Ui.mix(base, 0xFF000000, 0.28f));
        f.addView(spine, new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 6), -1, Gravity.START));
        TextView t = Ui.text(this, title, wDp >= 100 ? 14 : 11, Ui.ON_ACC);
        t.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(wDp >= 100 ? 5 : 4);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        f.addView(t, new android.widget.FrameLayout.LayoutParams(-1, -1));
        f.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, wDp), Ui.dp(this, hDp)));
        return f;
    }

    /** A list row with a picture at its left: the row's own card background moves to the outside. */
    private View withPicture(LinearLayout item, View picture) {
        LinearLayout out = Ui.row(this);
        out.setGravity(Gravity.CENTER_VERTICAL);
        out.setBackground(item.getBackground());
        item.setBackground(null);
        out.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), 0, Ui.dp(this, 8));
        ((LinearLayout.LayoutParams) picture.getLayoutParams()).rightMargin = Ui.dp(this, 2);
        out.addView(picture);
        out.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (item.hasOnClickListeners()) out.setOnClickListener(v -> item.performClick());
        out.setOnLongClickListener(v -> item.performLongClick());
        item.setClickable(false); item.setLongClickable(false);
        return out;
    }

    /** A video's picture tile (a frame of it) at a size. */
    private View videoTile(File f, int wDp, int hDp) {
        float at = app.prefs().getFloat("vpos_" + f.getName(), 0), dur = app.prefs().getFloat("vdur_" + f.getName(), 0);
        View t = Thumbs.tile(this, f, vtitle(f), watched(f) ? 1f : dur > 30 ? at / dur : 0f);
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, wDp), Ui.dp(this, hDp)));
        return t;
    }

    /** A small cover: a coloured block with the first letters, the title and one line under it. */
    private View cover(String title, String sub, View.OnClickListener click, View art) {
        LinearLayout c = Ui.col(this);
        c.addView(art);
        TextView tt = Ui.text(this, title, 13, Ui.TEXT);
        tt.setMaxLines(2);
        tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tt.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(tt);
        c.addView(Ui.text(this, sub, 11, Ui.DIM));
        c.setOnClickListener(click);
        return c;
    }

    static String initialsOf(String s) { return initials(s); }

    private static String initials(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            b.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
            if (b.length() >= 2) break;
        }
        return b.length() == 0 ? "?" : b.toString();
    }

    private List<File> queueSrc;                           // the list a video was chosen from (a series, a playlist): the ones after it play next

    private void openVideo(File f) {
        Intent i = new Intent(this, Player.class);
        i.putExtra("path", f.getPath());
        if (queueSrc != null && app.prefs().getBoolean("autonext", true)) {
            int k = queueSrc.indexOf(f);
            if (k >= 0 && k + 1 < queueSrc.size()) {
                String[] q = new String[queueSrc.size() - k - 1];
                for (int j = 0; j < q.length; j++) q[j] = queueSrc.get(k + 1 + j).getPath();
                i.putExtra("queue", q);
            }
        }
        startActivity(i);
    }
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
        Ui.add(l, Ui.button(this, "📁  Add a folder of books", v -> foldersMenu("book")), 8);
        Ui.add(l, Ui.button(this, "📷  Photo of a page (the PC reads it)", v -> {
            if (app.pc() == null) { Ui.toast(this, "Link the PC first (Settings)"); return; }
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(i, "Photos of the pages"), REQ_PHOTO);
        }), 8);
        String lang = app.lang();
        List<BookInfo> books = bookList(lang);
        if (books.isEmpty()) l.addView(Ui.hint(this, "No " + lang.toUpperCase(Locale.ROOT) + " books yet. Add an EPUB or text file (or a whole folder of them) from the phone, or copy a book from the Reader on the PC."));
        else {
            List<String> bkeys = new ArrayList<>();
            for (BookInfo bk : books) bkeys.add(Details.bookKey(bk.id));
            List<String> used = Details.tagsUsed(bkeys);
            if (!bookTagFilter.isEmpty() && !used.contains(bookTagFilter)) bookTagFilter = "";
            if (!used.isEmpty()) l.addView(Details.filterBar(this, used, bookTagFilter, t -> { bookTagFilter = t; show(); }));
        }
        for (BookInfo bk : books) {
            if (!bookTagFilter.isEmpty() && !Details.hasTag(Details.bookKey(bk.id), bookTagFilter)) continue;
            int[] pos = Reader.position(bk.id);
            String sub = bk.chapters + " chapters" + (pos[0] > 0 || pos[1] > 0 ? " · at chapter " + (pos[0] + 1) : "");
            Map<String, Object> det = Details.get(Details.bookKey(bk.id));
            String by = com.batchmine.pocket.core.Meta.line(det);
            LinearLayout it = Ui.item(this, bk.title, by.isEmpty() ? sub : by + " · " + sub, v -> { openBookId = bk.id; show(); });
            String tl = Details.tagLine(Details.bookKey(bk.id));
            if (!tl.isEmpty()) { TextView tg = Ui.text(this, tl, 12, Ui.ACCENT); tg.setPadding(0, Ui.dp(this, 3), 0, 0); it.addView(tg); }
            float frac = app.prefs().getFloat("rf_" + bk.id, 0);
            if (frac > 0.005f) { View pr = Ui.progress(this, frac); ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = Ui.dp(this, 8); it.addView(pr); }
            it.setOnLongClickListener(v -> { bookMenu(bk); return true; });
            Ui.add(l, withPicture(it, bookCover(bk.id, bk.title, 54, 76)), 8);
        }
        return Ui.scroll(this, l);
    }

    private void bookMenu(BookInfo bk) {
        Ui.choose(this, bk.title, new String[]{"Rename…", "Delete this book"}, -1, w -> {
            if (w == 0) renameBook(bk);
            else Ui.dialog(this).setTitle("Delete “" + bk.title + "”?").setMessage("The book is removed from the phone (the PC keeps its copy).")
                    .setPositiveButton("Delete", (d2, i) -> { bk.file.delete(); openBookId = null; show(); }).setNegativeButton("Keep", null).show();
        });
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
        LinearLayout top = Ui.row(this);
        top.addView(bookCover(bk.id, bk.title, 84, 118));
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
        hero.addView(Details.block(this, Details.bookKey(bk.id), "book", bk.title, bk.chapters + " chapters", this::show));
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
                app.post(() -> Ui.choose(this, "Contents", names, -1, i -> {
                    app.prefs().edit().putInt("rc_" + bk.id, i).putInt("rp_" + bk.id, 0).apply();
                    openBook(bk);
                }));
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
                    Ui.choose(this, "Copy a book from the PC", names, -1, i -> {
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
                    });
                });
            } catch (IOException e) { app.post(() -> Ui.alert(this, "Could not reach the PC", explain(e))); }
        });
    }

    // ================================================================ videos
    private View videos() {
        if (openList != null) { View v = playlistPage(openList); if (v != null) return v; openList = null; }
        if (openShow != null) { View v = showPage(openShow); if (v != null) return v; openShow = null; }
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 12));
        LinearLayout acts = Ui.row(this);
        acts.addView(Ui.weight(Ui.primary(this, "Add a video", v -> pick(REQ_VIDEO, new String[]{"video/*", "application/x-subrip", "text/vtt", "text/x-ssa", "application/octet-stream", "text/*"}, true)), 1));
        acts.addView(Ui.weight(Ui.button(this, "From the PC", v -> pcVideos()), 1));
        Ui.add(l, acts, 8);
        LinearLayout acts2 = Ui.row(this);
        acts2.addView(Ui.weight(Ui.button(this, "📁  Add a folder", v -> foldersMenu("video")), 1));
        acts2.addView(Ui.weight(Ui.button(this, "☰  New playlist", v -> newPlaylist(null, null)), 1));
        Ui.add(l, acts2, 8);
        File[] fs = myVideos();
        if (fs == null || fs.length == 0) {
            l.addView(Ui.hint(this, "No videos yet. “Add a video” takes a video from the phone together with its subtitle files (.srt / .vtt / .ass): choose them all in one go. “From the PC” copies an episode and its subtitles over Wi-Fi."));
        } else {
            Map<String, List<File>> groups = showGroups(fs);
            List<File> singles = new ArrayList<>();
            for (File f : fs) { Shows.Ep e = Player.epOf(f); if (e.show.isEmpty() || !groups.containsKey(Shows.key(e.show))) singles.add(f); }
            Arrays.sort(fs);
            android.content.SharedPreferences sp = app.prefs();
            List<String> keys = new ArrayList<>(groups.keySet());
            keys.sort((x, y) -> Long.compare(lastTouched(groups.get(y)), lastTouched(groups.get(x))));
            List<Lists.PL> pls = Lists.all();
            if (!pls.isEmpty()) {
                l.addView(Ui.title(this, "Playlists"));
                for (Lists.PL pl : pls) Ui.add(l, playlistCard(pl), 8);
            }
            if (!keys.isEmpty()) {
                l.addView(Ui.title(this, "Series"));
                List<String> sk = new ArrayList<>();
                for (String k : keys) sk.add("pseries:" + k);
                List<String> used = Details.tagsUsed(sk);
                if (!tagFilter.isEmpty() && !used.contains(tagFilter)) tagFilter = "";
                if (!used.isEmpty()) l.addView(Details.filterBar(this, used, tagFilter, t -> { tagFilter = t; show(); }));
                if (!tagFilter.isEmpty()) keys.removeIf(k -> !Details.hasTag("pseries:" + k, tagFilter));
            }
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
            Shows.Ep e = Player.epOf(f);
            if (!e.show.isEmpty()) m.computeIfAbsent(Shows.key(e.show), k -> new ArrayList<>()).add(f);
        }
        m.values().removeIf(x -> x.size() < 2);
        for (List<File> x : m.values()) x.sort((p, q) -> {
            Shows.Ep a = Player.epOf(p), b = Player.epOf(q);
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


    // ---- which language a video is for, and how it is named
    /** The language a video belongs to: the one chosen when it was added, else from its subtitle files (the learning language, not English), else "" (unknown). */
    private String vlang(File f) {
        String own = app.prefs().getString("vlang_" + f.getName(), "");
        if (!own.isEmpty()) return own;
        String en = "";
        for (File sf : Player.subsOf(f)) {
            String g = Player.guessLang(sf.getName());
            if (g.equals("sub")) continue;
            if (g.equals("en")) en = "en"; else return g;
        }
        return en;
    }

    /** The videos for the language on show; videos whose language is not known yet appear everywhere (long-press → Set language). */
    private File[] myVideos() {
        File[] all = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
        if (all == null) return new File[0];
        List<File> out = new ArrayList<>();
        String cur = app.lang();
        for (File f : all) { String l = vlang(f); if (l.isEmpty() || l.equals(cur)) out.add(f); }
        return out.toArray(new File[0]);
    }

    /** Subtitles for a video from the internet: the PC does the searching (its sources and keys); the phone keeps the file next to the video. */
    private void findSubs(File f) {
        PcLink pc = app.pc();
        if (pc == null) { Ui.toast(this, "Paste the PC link in Settings first: the PC searches for the phone"); return; }
        Shows.Ep e = Player.epOf(f);
        String name = vtitle(f);
        String lang = vlang(f).isEmpty() ? app.lang() : vlang(f);
        if (lang.equals("en") && !app.lang().equals("en")) lang = app.lang();
        final String lg = lang;
        Ui.toast(this, "Searching for " + lg.toUpperCase(Locale.ROOT) + " subtitles…");
        app.bg(() -> {
            try {
                String q = "/api/pocket/subsearch?lang=" + PcLink.enc(lg) + "&q=" + PcLink.enc(name)
                        + (e.episode >= 0 ? "&season=" + Math.max(e.season, 0) + "&episode=" + e.episode : "");
                Map<String, Object> r = pc.get(q, 60000);
                List<Object> rs = Json.arr(r.get("results"));
                app.post(() -> {
                    if (rs.isEmpty()) {
                        List<Object> notes = Json.arr(r.get("notes"));
                        Ui.alert(this, "No subtitles found", "Nothing for “" + name + "” in " + lg.toUpperCase(Locale.ROOT) + (notes.isEmpty() ? "." : ".\n\n" + android.text.TextUtils.join("\n", notes))
                                + (Json.str(r.get("has_key"), "").equals("false") || Boolean.FALSE.equals(r.get("has_key")) ? "\n\nAdd a SubDL or OpenSubtitles key on the PC (Settings) for more sources." : ""));
                        return;
                    }
                    int n = Math.min(40, rs.size());
                    String[] items = new String[n];
                    for (int i = 0; i < n; i++) {
                        Map<String, Object> x = Json.obj(rs.get(i));
                        items[i] = Json.str(x.get("name"), "?") + (Boolean.TRUE.equals(x.get("full_season")) ? "  · whole season" : "") + "  · " + Json.str(x.get("source"), "");
                    }
                    Ui.choose(this, "Subtitles for " + name + (e.episode >= 0 ? " · " + e.label() : ""), items, -1, i -> getSub(f, lg, Json.obj(rs.get(i)), e));
                });
            } catch (IOException ex) { app.post(() -> Ui.alert(this, "Could not search", explain(ex))); }
        });
    }

    /** Transcripts: the phone cannot run Whisper, but the PC can. It is asked to make the subtitles of this video (when it has the video), else the
     *  phone sends only the SOUND, made small (mono, 16 kHz, about 24 kbit/s: Whisper needs no more), and the phone asks every 20 seconds
     *  until the subtitles are there, then keeps the file next to the video. */
    private void whisperOnPc(File f) {
        PcLink pc = app.pc();
        if (pc == null) { Ui.toast(this, "Paste the PC link in Settings first: Whisper runs on the PC"); return; }
        String lang = vlang(f).isEmpty() ? app.lang() : vlang(f);
        if (lang.equals("en") && !app.lang().equals("en")) lang = app.lang();
        final String lg = lang, name = Player.videoName(f);
        Ui.toast(this, "Asking the PC…");
        app.bg(() -> {
            try {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("lang", lg); body.put("video", name);
                Map<String, Object> r = pc.post("/api/pocket/transcribe", body, 60000);
                if (Json.str(r.get("state"), "").equals("missing")) {
                    app.post(() -> Ui.dialog(this).setTitle("The PC does not have this video")
                            .setMessage("Send only its sound? It is made small first (mono, a low bit rate: an hour is about 11 MB) and the PC listens to it with Whisper. The picture stays on the phone.")
                            .setPositiveButton("Send the sound", (d, w) -> sendSound(f, pc, lg, name)).setNegativeButton("Cancel", null).show());
                    return;
                }
                waitForWhisper(f, pc, lg, name, r);
            } catch (IOException ex) { app.post(() -> Ui.alert(this, "Could not ask the PC", explain(ex))); }
        });
    }

    /** Sound of the video, small, to the PC (which starts Whisper on it). */
    private void sendSound(File f, PcLink pc, String lg, String name) {
        Ui.toast(this, "Making the sound small…");
        app.bg(() -> {
            File small = null;
            try {
                small = MediaCut.smallAudio(this, Player.videoUri(f), getCacheDir(), "send_" + System.currentTimeMillis(), p -> app.post(() -> status.setText("preparing the sound… " + Math.round(p * 100) + " %")));
                final File sf = small;
                app.post(() -> Ui.toast(this, "Sending " + (sf.length() / 1024 / 1024 + 1) + " MB to the PC…"));
                Map<String, Object> r = pc.upload("/api/pocket/audio?lang=" + PcLink.enc(lg) + "&video=" + PcLink.enc(name), small, new PcLink.Bytes() {
                    @Override public void progress(long done, long total) { app.post(() -> status.setText("sending the sound… " + (total > 0 ? Math.round(100.0 * done / total) : 0) + " %")); }
                    @Override public boolean cancelled() { return false; }
                });
                app.post(() -> status.setText(""));
                waitForWhisper(f, pc, lg, name, r);
            } catch (IOException ex) { app.post(() -> Ui.alert(this, "Could not send the sound", explain(ex))); }
            finally { if (small != null) //noinspection ResultOfMethodCallIgnored
                small.delete(); }
        });
    }

    private void waitForWhisper(File f, PcLink pc, String lg, String name, Map<String, Object> first) throws IOException {
        Map<String, Object> r = first;
        boolean told = false;
        for (int i = 0; i < 270; i++) {                                    // up to 90 minutes
            String st = Json.str(r.get("state"), "none");
            if (st.equals("missing")) { String why = Json.str(r.get("error"), "The PC does not have this video."); app.post(() -> Ui.alert(this, "Not on the PC", why)); return; }
            if (st.equals("ready")) {
                List<Object> subs = Json.arr(r.get("subs"));
                if (subs.isEmpty()) break;
                Map<String, Object> x = Json.obj(subs.get(0));
                String ext = Json.str(x.get("name"), ".srt").replaceAll("^.*(\\.[A-Za-z0-9]+)$", "$1");
                String stem = Player.niceName(f);
                File dest = new File(app.videoDir(), stem + "." + lg + ext);
                for (int k = 2; dest.exists(); k++) dest = new File(app.videoDir(), stem + "." + lg + k + ext);
                pc.download("/api/pocket/file?id=" + PcLink.enc(Json.str(x.get("id"), "")), dest, null);
                if (vlang(f).isEmpty()) setVideoLang(f, lg);
                File saved = dest;
                app.post(() -> { Ui.toast(this, "Subtitles from the PC: " + saved.getName()); show(); });
                return;
            }
            if (!told) { told = true; app.post(() -> Ui.toast(this, "The PC is transcribing (a few minutes for an episode). The subtitles arrive by themselves while the app is open.")); }
            try { Thread.sleep(20000); } catch (InterruptedException ie) { return; }
            r = pc.get("/api/pocket/transcribe?lang=" + PcLink.enc(lg) + "&video=" + PcLink.enc(name), 30000);
        }
        app.post(() -> Ui.alert(this, "Still working", "The PC has not finished yet. Open this menu again later: the subtitles are fetched when they are there."));
    }

    private void getSub(File f, String lang, Map<String, Object> item, Shows.Ep e) {
        PcLink pc = app.pc();
        if (pc == null) return;
        Ui.toast(this, "Downloading…");
        app.bg(() -> {
            try {
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("item", item);
                body.put("lang", lang);
                if (e.episode >= 0) { body.put("season", e.season); body.put("episode", e.episode); }
                Map<String, Object> r = pc.post("/api/pocket/subget", body, 90000);
                String text = Json.str(r.get("text"), ""), ext = Json.str(r.get("ext"), ".srt");
                String stem = Player.niceName(f);
                File dest = new File(app.videoDir(), stem + "." + lang + ext);
                for (int k = 2; dest.exists(); k++) dest = new File(app.videoDir(), stem + "." + lang + k + ext);
                java.nio.file.Files.write(dest.toPath(), text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if (vlang(f).isEmpty()) setVideoLang(f, lang);
                File saved = dest;
                app.post(() -> { Ui.toast(this, "Saved " + saved.getName()); show(); });
            } catch (IOException ex) { app.post(() -> Ui.alert(this, "Could not download it", explain(ex))); }
        });
    }

    private void pickVideoLang(File f) {
        String[] codes = {"it", "de", "hr", "en", "fr", "es", "pt", "nl"};
        Ui.choose(this, "Which language is this video for?", codes, Arrays.asList(codes).indexOf(vlang(f)), i -> { setVideoLang(f, codes[i]); show(); });
    }

    private void setVideoLang(File f, String lang) { app.prefs().edit().putString("vlang_" + f.getName(), lang).apply(); }

    /** What to call a video: the series name for an episode, a cleaned-up name for a film. */
    private String vtitle(File f) {
        Shows.Ep e = Player.epOf(f);
        return !e.show.isEmpty() ? e.show : Shows.pretty(Player.videoName(f));
    }

    /** "Season 1 · Episode 3" for an episode, else "". */
    private String vepisode(File f) { return Player.epOf(f).longLabel(); }

    /** Subtitle languages as tags: "DE · EN", or "no subtitles". */
    private String subTags(File f) {
        java.util.LinkedHashSet<String> t = new java.util.LinkedHashSet<>();
        int n = 0;
        for (File sf : Player.subsOf(f)) { n++; String g = Player.guessLang(sf.getName()); t.add(g.equals("sub") ? "SUB" : g.toUpperCase(Locale.ROOT)); }
        if (n == 0) return "no subtitles";
        return android.text.TextUtils.join(" · ", t);
    }

    private String showName(List<File> eps) { return vtitle(eps.get(0)); }

    private View showCard(String key, List<File> eps) {
        File nx0 = nextEpisode(eps);
        int done = 0;
        for (File f : eps) if (watched(f)) done++;
        File nx = nextEpisode(eps);
        Shows.Ep ne = Player.epOf(nx);
        String sub = eps.size() + " episodes · " + done + " watched" + (done < eps.size() ? " · next: " + ne.label() : " · all watched");
        String tl = Details.tagLine("pseries:" + key);
        LinearLayout it = Ui.item(this, showName(eps), sub, v -> { openShow = key; show(); });
        if (!tl.isEmpty()) { TextView tg = Ui.text(this, tl, 12, Ui.ACCENT); tg.setPadding(0, Ui.dp(this, 3), 0, 0); it.addView(tg); }
        View pr = Ui.progress(this, done / (float) eps.size());
        ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = Ui.dp(this, 8);
        it.addView(pr);
        View t = Thumbs.tile(this, nx0, showName(eps), 0f);
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 58)));
        return withPicture(it, t);
    }

    /** The page of one show: its episodes in order, the one to continue with, which are watched (like the PC's series page). */
    private View showPage(String key) {
        File[] fs = myVideos();
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
        StringBuilder hn = new StringBuilder(eps.size() + " video files, e.g. ");
        for (int i = 0; i < Math.min(3, eps.size()); i++) hn.append(i > 0 ? "; " : "").append(Player.videoName(eps.get(i)));
        hero.addView(Details.block(this, "pseries:" + key, "series", showName(eps), hn.toString(), this::show));
        Ui.add(l, hero, 12);
        queueSrc = eps;
        File nx = nextEpisode(eps);
        float at = app.prefs().getFloat("vpos_" + nx.getName(), 0);
        String what = Player.epOf(nx).label();
        Ui.add(l, Ui.primary(this, done == eps.size() ? "Watch again · " + what : (at > 5 && !watched(nx) ? "Continue · " + what + " at " + Ui.time(at) : "Play · " + what), v -> openVideo(nx)), 12);
        Ui.add(l, Ui.button(this, "☰  Save these episodes as a playlist", v -> { List<String> nm = new ArrayList<>(); for (File f : eps) nm.add(f.getName()); newPlaylist(nm, showName(eps)); }), 12);
        for (File f : eps) Ui.add(l, videoItem(f, true), 8);
        return Ui.scroll(this, l);
    }

    // ---- playlists
    private File videoByName(String n) { File f = new File(app.videoDir(), n); return f.isFile() ? f : null; }

    private List<File> playlistFiles(Lists.PL pl) {
        List<File> out = new ArrayList<>();
        for (String n : pl.items) { File f = videoByName(n); if (f != null) out.add(f); }
        return out;
    }

    private View playlistCard(Lists.PL pl) {
        List<File> fs = playlistFiles(pl);
        int done = 0;
        for (File f : fs) if (watched(f)) done++;
        LinearLayout it = Ui.item(this, "☰  " + pl.name, fs.size() + " videos · " + done + " watched", v -> { openList = pl.id; show(); });
        if (!fs.isEmpty()) { View pr = Ui.progress(this, done / (float) fs.size()); ((LinearLayout.LayoutParams) pr.getLayoutParams()).topMargin = Ui.dp(this, 8); it.addView(pr); }
        it.setOnLongClickListener(v -> { playlistMenu(pl); return true; });
        if (fs.isEmpty()) return it;
        View t = Thumbs.tile(this, nextEpisode(fs), pl.name, 0f);
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 58)));
        return withPicture(it, t);
    }

    private View playlistPage(String id) {
        Lists.PL pl = Lists.get(id);
        if (pl == null) return null;
        List<File> fs = playlistFiles(pl);
        LinearLayout l = Ui.col(this);
        int pad = Ui.dp(this, 12);
        l.setPadding(pad, 0, pad, pad * 2);
        l.addView(backRow("Videos", () -> { openList = null; show(); }));
        int done0 = 0;
        for (File f : fs) if (watched(f)) done0++;
        final int done = done0;
        LinearLayout hero = Ui.card(this);
        TextView tt = Ui.text(this, pl.name, 20, Ui.TEXT);
        tt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hero.addView(tt);
        TextView st = Ui.text(this, fs.size() + " videos · " + done + " watched" + (fs.size() < pl.items.size() ? " · " + (pl.items.size() - fs.size()) + " not on the phone any more" : ""), 13, Ui.DIM);
        st.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        hero.addView(st);
        if (!fs.isEmpty()) hero.addView(Ui.progress(this, done / (float) fs.size()));
        Ui.add(l, hero, 12);
        queueSrc = fs;
        if (fs.isEmpty()) { l.addView(Ui.hint(this, "Nothing in this playlist yet: long-press a video → Add to a playlist.")); }
        else {
            File nx = nextEpisode(fs);
            Ui.add(l, Ui.primary(this, done == fs.size() ? "Play again from the start" : "Play · " + vtitle(nx), v -> openVideo(done == fs.size() ? fs.get(0) : nx)), 12);
        }
        Ui.add(l, Ui.buttons(this, Ui.button(this, "Edit the list", v -> editPlaylist(pl)), Ui.button(this, "Rename", v -> askName("Rename the playlist", pl.name, nm -> { pl.name = nm; Lists.update(pl); show(); })),
                Ui.button(this, "Delete", v -> playlistMenu(pl))), 12);
        for (File f : fs) Ui.add(l, videoItem(f, false), 8);
        return Ui.scroll(this, l);
    }

    private void playlistMenu(Lists.PL pl) {
        Ui.dialog(this).setTitle("Delete the playlist “" + pl.name + "”?").setMessage("Only the list: the videos stay.")
                .setPositiveButton("Delete", (d, i) -> { Lists.delete(pl.id); openList = null; show(); }).setNegativeButton("Keep", null).show();
    }

    /** The order of a playlist: tap a video to move it up or down or take it out. */
    private void editPlaylist(Lists.PL pl) {
        List<String> names = new ArrayList<>();
        for (String n : pl.items) { File f = videoByName(n); names.add(f == null ? "(gone) " + n : vtitle(f) + (vepisode(f).isEmpty() ? "" : " · " + vepisode(f))); }
        if (names.isEmpty()) return;
        Ui.choose(this, "Tap a video to move it", names.toArray(new String[0]), -1, i -> Ui.choose(this, names.get(i), new String[]{"Move up", "Move down", "Take out of the list"}, -1, w -> {
            if (w == 0 && i > 0) java.util.Collections.swap(pl.items, i, i - 1);
            else if (w == 1 && i + 1 < pl.items.size()) java.util.Collections.swap(pl.items, i, i + 1);
            else if (w == 2) pl.items.remove(i);
            Lists.update(pl);
            show();
            editPlaylist(pl);
        }));
    }

    private void askName(String title, String value, java.util.function.Consumer<String> then) {
        android.widget.EditText in = Ui.input(this, value, "Name");
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 16));
        box.addView(in);
        android.app.Dialog[] d = new android.app.Dialog[1];
        Ui.add(box, Ui.buttons(this, Ui.flat(this, "Cancel", v -> Ui.dismiss(d[0])), Ui.primary(this, "OK", v -> {
            String t = in.getText().toString().trim();
            if (t.isEmpty()) return;
            Ui.dismiss(d[0]);
            then.accept(t);
        })), 12);
        d[0] = Ui.sheet(this, title, box, false);
    }

    /** A new playlist with these videos (file names), asking for its name. */
    private void newPlaylist(List<String> items, String suggested) {
        askName("New playlist", suggested == null ? "" : suggested, nm -> { Lists.PL p = Lists.create(nm, items == null ? new ArrayList<>() : items); Ui.toast(this, "Playlist “" + p.name + "” made"); openList = p.id; tab = "videos"; show(); });
    }

    private void addToPlaylist(File f) {
        List<Lists.PL> ps = Lists.all();
        String[] items = new String[ps.size() + 1];
        for (int i = 0; i < ps.size(); i++) items[i] = ps.get(i).name;
        items[ps.size()] = "New playlist…";
        Ui.choose(this, "Add to a playlist", items, -1, i -> {
            if (i == ps.size()) newPlaylist(new ArrayList<>(java.util.Collections.singletonList(f.getName())), vtitle(f));
            else { Lists.add(ps.get(i).id, f.getName()); Ui.toast(this, "Added to “" + ps.get(i).name + "”"); }
        });
    }

    /** One video in a list. IN_SHOW: the episode number leads and watched ones are ticked. */
    private View videoItem(File f, boolean inShow) {
        int subs = Player.subsOf(f).size();
        double at = app.prefs().getFloat("vpos_" + f.getName(), 0);
        boolean linked = f.getName().endsWith(".vlink");
        long fsize = linked ? linkSize(f) : f.length();
        boolean done = watched(f);
        Shows.Ep e = Player.epOf(f);
        String head = inShow ? (done ? "✓  " : "") + (e.episode >= 0 ? e.longLabel() : vtitle(f)) : vtitle(f);
        List<String> bits = new ArrayList<>();
        if (!inShow && e.episode >= 0) bits.add(e.longLabel());
        if (inShow && e.episode >= 0 && !e.show.isEmpty() && Shows.pretty(Player.videoName(f)).length() > e.show.length() + 8) { /* a title after the numbers is not known: nothing to add */ }
        bits.add(subTags(f));
        if (fsize < 0) bits.add("video not found (add it again)");
        else if (linked) bits.add("stays where it is");
        if (done) bits.add("watched"); else if (at > 5) bits.add("at " + Ui.time(at));
        String sub = android.text.TextUtils.join(" · ", bits);
        LinearLayout it = Ui.item(this, head, sub, v -> openVideo(f));
        it.setOnLongClickListener(v -> {
            String[] acts = {done ? "Mark as not watched" : "Mark as watched", linked ? "Remove from the app (the video itself stays)" : "Delete this video and its subtitles", "Set language (now: " + (vlang(f).isEmpty() ? "not set" : vlang(f)) + ")", "Find subtitles on the internet (through the PC)", "Add to a playlist…", "Make subtitles with Whisper (on the PC)"};
            Ui.choose(this, vtitle(f), acts, -1, w -> {
                if (w == 2) { pickVideoLang(f); return; }
                if (w == 3) { findSubs(f); return; }
                if (w == 4) { addToPlaylist(f); return; }
                if (w == 5) { whisperOnPc(f); return; }
                if (w == 0) {
                    android.content.SharedPreferences.Editor ed = app.prefs().edit().putBoolean("vdone_" + f.getName(), !done).putLong("vpost_" + f.getName(), System.currentTimeMillis());
                    if (done) ed.putFloat("vpos_" + f.getName(), 0);
                    ed.apply();
                } else {
                    for (File s : Player.subsOf(f)) s.delete();
                    f.delete();
                }
                show();
            });
            return true;
        });
        return withPicture(it, videoTile(f, 104, 58));
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
                    pcLibrary(pc, vs);
                });
            } catch (IOException e) { app.post(() -> Ui.alert(this, "Could not reach the PC", explain(e))); }
        });
    }

    /** The PC's videos as its series (with how many episodes are already on the phone); tap a series for its episodes, tap an episode to copy it. */
    private void pcLibrary(PcLink pc, List<Object> vs) {
        Map<String, List<Map<String, Object>>> bySeries = new java.util.LinkedHashMap<>();
        for (Object o : vs) {
            Map<String, Object> v = Json.obj(o);
            String name = Json.str(v.get("name"), "?"), series = Json.str(v.get("series"), "");
            Shows.Ep e = Shows.of(name);
            String key = !e.show.isEmpty() ? e.show : (!series.isEmpty() ? Shows.pretty(series) : Shows.pretty(name));
            bySeries.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
        java.util.Set<String> have = new java.util.HashSet<>();
        File[] mine = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
        if (mine != null) for (File f : mine) have.add(Player.videoName(f).toLowerCase(Locale.ROOT));
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 14));
        android.app.Dialog[] d = new android.app.Dialog[1];
        for (Map.Entry<String, List<Map<String, Object>>> en : bySeries.entrySet()) {
            List<Map<String, Object>> eps = en.getValue();
            int got = 0;
            for (Map<String, Object> v : eps) if (have.contains(Json.str(v.get("name"), "").toLowerCase(Locale.ROOT))) got++;
            String sub = eps.size() == 1 ? Shows.of(Json.str(eps.get(0).get("name"), "")).longLabel() : eps.size() + " episodes";
            if (got > 0) sub += (sub.isEmpty() ? "" : " · ") + got + " on the phone";
            LinearLayout it = Ui.item(this, en.getKey(), sub, v -> { Ui.dismiss(d[0]); pcEpisodes(pc, en.getKey(), eps, vs); });
            Ui.add(l, it, 6);
        }
        d[0] = Ui.sheet(this, "Videos on the PC (" + app.lang().toUpperCase(Locale.ROOT) + ")", Ui.scroll(this, l), true);
    }

    private void pcEpisodes(PcLink pc, String series, List<Map<String, Object>> eps, List<Object> all) {
        eps.sort((a, b) -> Long.compare(Shows.of(Json.str(a.get("name"), "")).order(), Shows.of(Json.str(b.get("name"), "")).order()));
        java.util.Set<String> have = new java.util.HashSet<>();
        File[] mine = app.videoDir().listFiles((d, n) -> Player.isVideo(n));
        if (mine != null) for (File f : mine) have.add(Player.videoName(f).toLowerCase(Locale.ROOT));
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 14));
        android.app.Dialog[] d = new android.app.Dialog[1];
        l.addView(Ui.small(this, "‹ All series", v -> { Ui.dismiss(d[0]); pcLibrary(pc, all); }));
        for (Map<String, Object> v : eps) {
            String name = Json.str(v.get("name"), "?");
            Shows.Ep e = Shows.of(name);
            boolean there = have.contains(name.toLowerCase(Locale.ROOT));
            int subs = Json.arr(v.get("subs")).size();
            String head = e.episode >= 0 ? e.longLabel() : Shows.pretty(name);
            String sub = Ui.size((long) Json.num(v.get("size"), 0)) + " · " + (subs == 0 ? "no subtitles" : subs + " subtitle file" + (subs > 1 ? "s" : "")) + (there ? " · already on the phone" : "");
            LinearLayout it = Ui.item(this, (there ? "✓  " : "") + head, sub, x -> { Ui.dismiss(d[0]); copyVideo(pc, v); });
            Ui.add(l, it, 6);
        }
        d[0] = Ui.sheet(this, series, Ui.scroll(this, l), true);
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
                app.prefs().edit().putString("vlang_" + name, app.lang()).apply();
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
                        Ui.choose(this, Json.str(c.get("word"), ""), new String[]{"Remove this card"}, -1, i -> { ws.removePending(id); show(); })), 6);
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
        Ui.pickRow(g, "Theme", "pc".equals(cur) ? "Same as the PC" : Ui.themeName(cur), v -> Pickers.theme(this, this::recreate));
        String acc = app.prefs().getString("accent", "");
        Ui.pickRow(g, "Accent colour", acc.isEmpty() ? "The theme's own" : Ui.accentName(acc), v -> Pickers.accent(this, this::recreate));
        String ic = app.prefs().getString("icon", "original");
        Ui.pickRow(g, "App icon", "pc".equals(ic) ? "Same as the PC" : Icons.name(ic), v -> Pickers.icon(this, this::show));
        Ui.switchRow(g, "Coloured words", "Unknown, looked-up and card words coloured in videos and books, as on the PC", app.prefs().getBoolean("colours", true),
                (b, on) -> app.prefs().edit().putBoolean("colours", on).apply());
        Ui.add(l, g, 0);

        // ---- watching and reading
        l.addView(Ui.section(this, "Watching and reading"));
        g = Ui.group(this);
        Ui.pickRow(g, "Stopwatch: stops after", idleName(app.prefs().getInt("sw_idle_watch", 20)) + " (video)", v -> idlePick("sw_idle_watch"));
        Ui.pickRow(g, "Stopwatch: stops after", idleName(app.prefs().getInt("sw_idle_read", 90)) + " (book)", v -> idlePick("sw_idle_read"));
        Ui.switchRow(g, "Video player held upright: video on top, lines below", "The picture sits at the top, the subtitle lines scroll underneath (the one being said is highlighted; tap a line to go there, a word for its card). Off: the picture fills the screen. Also the ⬒ button in the player.", app.prefs().getBoolean("player_stack", true),
                (b, on) -> app.prefs().edit().putBoolean("player_stack", on).apply());
        Ui.switchRow(g, "Play the next episode by itself", "At the end of a video in a series or playlist, the next one starts.", app.prefs().getBoolean("autonext", true),
                (b, on) -> app.prefs().edit().putBoolean("autonext", on).apply());
        l.addView(Ui.hint(this, "Primed listening, hidden subtitles, “i+1” and the other ways to use the lines are chosen inside the player (≡ menu → How to use the lines)."));
        Ui.pickRow(g, "Repeat a line", app.prefs().getInt("loop_times", 1) == 0 ? "until you go on" : app.prefs().getInt("loop_times", 1) + "×", v -> {
            int[] n = {1, 2, 3, 5, 0};
            String[] names = {"once", "2 times", "3 times", "5 times", "until you go on"};
            Ui.choose(this, "↺ plays the line", names, -1, i -> { app.prefs().edit().putInt("loop_times", n[i]).apply(); show(); });
        });
        Ui.add(l, g, 0);
        l.addView(Ui.hint(this, "The stopwatch counts while a video plays (or is paused for a moment) and while you read. Its time goes to the PC at each sync and shows in Home → This week there."));

        // ---- dictionaries
        l.addView(Ui.section(this, "Dictionaries"));
        g = Ui.group(this);
        for (Dict d : app.dicts.list()) {
            Ui.srow(g, d.title + " [" + d.lang + "]", d.summary(), null, v ->
                    Ui.choose(this, d.title, new String[]{"Delete this dictionary"}, -1, i -> { app.dicts.remove(d); show(); }));
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
        Ui.choose(this, "The stopwatch stops", names, -1, i -> { app.prefs().edit().putInt(key, n[i]).apply(); show(); });
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

    // ================================================================ folders (with subfolders)
    private void pickFolder(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, req);
    }

    private static String safe(String s) { return s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim(); }

    /**
     * A folder of videos and all its subfolders: every video is linked (not copied, unless "copy videos" is on), the subtitle files beside it are copied
     * with it, and the folders give the series: Shows/Dark/Season 2/01.mkv is "Dark", season 2, episode 1 (a name that says the show itself wins).
     */
    private void addVideoFolder(Uri tree, boolean remember) {
        String lang = app.lang();
        Ui.toast(this, "Looking through the folder…");
        app.bg(() -> {
            Folders.Result r = Folders.walk(this, tree);
            if (remember) Folders.remember(this, tree, "video", r.rootName);
            java.util.Set<String> seen = Folders.seen(this, "video");
            android.content.SharedPreferences.Editor ed = app.prefs().edit();
            int added = 0, subs = 0, skipped = 0;
            // the subtitle files of each folder, to find the ones that belong to a video
            Map<String, List<Folders.Item>> subsBy = new java.util.HashMap<>();
            for (Folders.Item it : r.files) if (Player.isSub(it.name)) subsBy.computeIfAbsent(String.join("/", it.rel), k -> new ArrayList<>()).add(it);
            int directVideos = 0;
            for (Folders.Item it : r.files) if (Player.isVideo(it.name) && it.rel.length == 0) directVideos++;
            File dir = app.videoDir();
            for (Folders.Item it : r.files) {
                if (!Player.isVideo(it.name)) continue;
                String id = it.uri.toString();
                if (seen.contains(id)) { skipped++; continue; }
                String[] sh = com.batchmine.pocket.core.Meta.folderShow(r.rootName, it.rel);
                String stem = it.name.replaceAll("\\.[^.]+$", "");
                String prefix = it.rel.length == 0 ? (directVideos > 1 ? safe(r.rootName) + " - " : "") : safe(String.join(" - ", it.rel)) + " - ";
                String base = safe(prefix + stem);
                File link = new File(dir, base + ".vlink");
                for (int k = 2; link.exists(); k++) link = new File(dir, base + " (" + k + ").vlink");
                try {
                    boolean copy = app.prefs().getBoolean("copy_videos", false);
                    if (!copy) {
                        try { getContentResolver().takePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (RuntimeException ignored) { /* the folder's permission covers it */ }
                        java.nio.file.Files.write(link.toPath(), (it.uri.toString() + "\n" + it.name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    } else {
                        link = new File(dir, base + it.name.substring(it.name.lastIndexOf('.')));
                        try (InputStream in = getContentResolver().openInputStream(it.uri); OutputStream out = new FileOutputStream(link)) { byte[] b = new byte[1 << 16]; int n; while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n); }
                    }
                } catch (IOException | RuntimeException e) { continue; }
                added++;
                seen.add(id);
                String ln = link.getName();
                ed.putString("vlang_" + ln, lang);
                com.batchmine.pocket.core.Shows.Ep own = com.batchmine.pocket.core.Shows.of(it.name);
                if (own.show.isEmpty() && !sh[0].isEmpty()) ed.putString("vshow_" + ln, sh[0]);
                int season = Integer.parseInt(sh[1]);
                if (season > 0 && own.season <= 0) ed.putInt("vseason_" + ln, season);
                // its subtitle files: the same folder, the same name up to the language mark
                String linkStem = ln.replaceAll("\\.[^.]+$", "");
                for (Folders.Item sf : subsBy.getOrDefault(String.join("/", it.rel), new ArrayList<>())) {
                    String ss = sf.name.replaceAll("\\.[^.]+$", "");
                    if (!(ss.equalsIgnoreCase(stem) || ss.toLowerCase(Locale.ROOT).startsWith(stem.toLowerCase(Locale.ROOT) + "."))) continue;
                    String tag = ss.length() > stem.length() ? ss.substring(stem.length() + 1) : Player.guessLang(sf.name);
                    String nn = linkStem + "." + (tag.isEmpty() ? "sub" : safe(tag)) + sf.name.substring(sf.name.lastIndexOf('.'));
                    File dest = new File(dir, nn);
                    if (dest.exists()) continue;
                    try (InputStream in = getContentResolver().openInputStream(sf.uri); OutputStream out = new FileOutputStream(dest)) { byte[] b = new byte[1 << 14]; int n; while (in != null && (n = in.read(b)) > 0) out.write(b, 0, n); subs++; }
                    catch (IOException | RuntimeException e) { dest.delete(); }
                }
            }
            ed.apply();
            Folders.markSeen(this, "video", seen);
            int a = added, s2 = subs, sk = skipped;
            boolean cut = r.cut;
            app.post(() -> {
                Ui.toast(this, a == 0 ? (sk > 0 ? "Nothing new in “" + r.rootName + "”" : "No videos found in “" + r.rootName + "”") : a + " video(s) added from “" + r.rootName + "”" + (s2 > 0 ? " with " + s2 + " subtitle file(s)" : "") + (cut ? " (the folder is very big: look again for the rest)" : ""));
                show();
            });
        });
    }

    /** A folder of books (EPUB / text) and its subfolders: each book is read in; the subfolder it was in becomes a tag. */
    private void addBookFolder(Uri tree, boolean remember) {
        String lang = app.lang();
        Ui.toast(this, "Looking through the folder…");
        app.bg(() -> {
            Folders.Result r = Folders.walk(this, tree);
            if (remember) Folders.remember(this, tree, "book", r.rootName);
            java.util.Set<String> seen = Folders.seen(this, "book");
            int added = 0, failed = 0;
            for (Folders.Item it : r.files) {
                String low = it.name.toLowerCase(Locale.ROOT);
                if (!(low.endsWith(".epub") || low.endsWith(".txt"))) continue;
                String id0 = it.uri.toString();
                if (seen.contains(id0)) continue;
                try {
                    String bid = addBookFrom(it.uri, it.name, lang);
                    seen.add(id0);
                    added++;
                    if (it.rel.length > 0) {                                        // the folder it was in is a tag
                        String key = Details.bookKey(bid);
                        Map<String, Object> e = Details.get(key);
                        List<String> tags = com.batchmine.pocket.core.Meta.list(e, "tags");
                        String tg = com.batchmine.pocket.core.Meta.tag(it.rel[0]);
                        if (!tg.isEmpty() && !tags.contains(tg)) { tags.add(tg); Map<String, Object> f = new java.util.LinkedHashMap<>(); f.put("tags", tags); com.batchmine.pocket.core.Meta.update(e, f, System.currentTimeMillis()); Details.put(key, e); }
                    }
                } catch (IOException | RuntimeException e) { failed++; }
            }
            Folders.markSeen(this, "book", seen);
            int a = added, f2 = failed;
            app.post(() -> { Ui.toast(this, a == 0 ? (f2 > 0 ? f2 + " book(s) could not be read" : "Nothing new in “" + r.rootName + "”") : a + " book(s) added from “" + r.rootName + "”" + (f2 > 0 ? " (" + f2 + " could not be read)" : "")); show(); });
        });
    }

    /** Looks again through the folders you added for files that are new. */
    private void rescanFolders(String kind) {
        int n = 0;
        for (String[] f : Folders.saved(this)) if (f[1].equals(kind)) { n++; Uri u = Uri.parse(f[0]); if (kind.equals("video")) addVideoFolder(u, false); else addBookFolder(u, false); }
        if (n == 0) Ui.toast(this, "No folder added yet: “Add a folder” first");
    }

    private void foldersMenu(String kind) {
        List<String[]> fs = new ArrayList<>();
        for (String[] f : Folders.saved(this)) if (f[1].equals(kind)) fs.add(f);
        List<String> items = new ArrayList<>(Arrays.asList("Add a folder…", "Look for new files in my folders"));
        for (String[] f : fs) items.add("Stop watching “" + f[2] + "”");
        Ui.choose(this, kind.equals("video") ? "Folders of videos" : "Folders of books", items.toArray(new String[0]), -1, i -> {
            if (i == 0) pickFolder(kind.equals("video") ? REQ_VFOLDER : REQ_BFOLDER);
            else if (i == 1) rescanFolders(kind);
            else { Folders.forget(this, fs.get(i - 2)[0], kind); Ui.toast(this, "Not looked at any more (the videos or books already added stay)"); }
        });
    }

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
        if ((req == REQ_VFOLDER || req == REQ_BFOLDER) && data.getData() != null) {
            Uri tree = data.getData();
            try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (RuntimeException ignored) { /* not persistable: works this once */ }
            if (req == REQ_VFOLDER) addVideoFolder(tree, true); else addBookFolder(tree, true);
            return;
        }
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
            try {
                String id = addBookFrom(u, name, lang);
                String title = Book.read(new File(app.dir("books"), id + ".json")).title;
                app.post(() -> { Ui.toast(this, "“" + title + "” added"); show(); });
            } catch (IOException | RuntimeException e) { app.post(() -> Ui.alert(this, "Could not add the book", String.valueOf(e.getMessage()))); }
        });
    }

    /** Reads one EPUB or text file into the books (on a background thread); an EPUB's cover, author, year and description are kept too. Returns the book's id. */
    private String addBookFrom(Uri u, String name, String lang) throws IOException {
        boolean epub = name.toLowerCase(Locale.ROOT).endsWith(".epub");
        Book bk;
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("could not open " + name);
            String stem = name.replaceAll("\\.[^.]+$", "");
            bk = epub ? Book.fromEpub(in, stem) : Book.fromText(Book.decode(readAll(in)), stem);
        }
        if (bk.chapters.isEmpty()) throw new IOException("no text found in " + name);
        bk.id = "ph" + Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        if (bk.lang.isEmpty() || !bk.lang.equals(lang)) bk.lang = lang;
        bk.write(new File(app.dir("books"), bk.id + ".json"));
        if (epub) {
            try (InputStream again = getContentResolver().openInputStream(u)) {
                byte[] pic = com.batchmine.pocket.core.EpubCover.extract(again);
                if (pic != null) try (java.io.FileOutputStream fo = new java.io.FileOutputStream(coverFile(bk.id))) { fo.write(pic); }
            } catch (IOException | RuntimeException ignored) { /* no cover: the lettered one is used */ }
            try (InputStream again = getContentResolver().openInputStream(u)) {                  // the author, year and description the book says about itself
                Map<String, String> m = com.batchmine.pocket.core.EpubMeta.read(again);
                Map<String, Object> e = Details.get(Details.bookKey(bk.id));
                if (com.batchmine.pocket.core.Meta.fromEpub(e, m, System.currentTimeMillis())) Details.put(Details.bookKey(bk.id), e);
            } catch (IOException | RuntimeException ignored) { /* no details in the file */ }
        }
        return bk.id;
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
                for (String nm : used) if (Player.isVideo(nm)) app.prefs().edit().putString("vlang_" + nm, app.lang()).apply();
                for (int k = 0; k < uris.size(); k++) if (Player.isVideo(names.get(k)) && !app.prefs().getBoolean("copy_videos", false))
                    app.prefs().edit().putString("vlang_" + names.get(k).replaceAll("\\.[^.]+$", "") + ".vlink", app.lang()).apply();
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
