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

/** The home screen: Books, Videos, Words and Settings, with the language and the sync button on top. */
public final class MainActivity extends Activity {
    static final int REQ_BOOK = 1, REQ_VIDEO = 2, REQ_DICT = 3;
    private App app;
    private FrameLayout body;
    private TextView langBtn, status;
    private String tab = "books";
    private static volatile boolean syncing;
    private final List<TextView> tabs = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = App.get();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 12);
        LinearLayout top = Ui.row(this);
        top.setPadding(p, p, p, 4);
        TextView name = Ui.text(this, "Pocket", 22, Ui.TEXT);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(Ui.weight(name, 1));
        langBtn = Ui.button(this, app.lang().toUpperCase(Locale.ROOT), v -> pickLang());
        top.addView(langBtn);
        top.addView(Ui.button(this, "Sync", v -> sync()));
        root.addView(top);
        status = Ui.hint(this, "");
        status.setPadding(p, 0, p, 4);
        root.addView(status);
        body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.CARD);
        for (String[] t : new String[][]{{"books", "Books"}, {"videos", "Videos"}, {"words", "Words"}, {"settings", "Settings"}}) {
            TextView v = Ui.text(this, t[1], 15, Ui.DIM);
            v.setGravity(Gravity.CENTER);
            v.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
            v.setTag(t[0]);
            v.setOnClickListener(x -> { tab = t[0]; show(); });
            bar.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            tabs.add(v);
        }
        root.addView(bar);
        setContentView(root);
        if (b != null) tab = b.getString("tab", "books");
        File crash = new File(getFilesDir(), "crash.txt");
        if (crash.isFile()) {
            File seen = new File(getFilesDir(), "crash-seen.txt");
            seen.delete();
            crash.renameTo(seen);
            problemReport("Something went wrong last time", seen);
        }
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

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); o.putString("tab", tab); }

    @Override protected void onResume() { super.onResume(); show(); }

    private void show() {
        for (TextView t : tabs) { boolean on = t.getTag().equals(tab); t.setTextColor(on ? Ui.ACCENT : Ui.DIM); }
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
            default: v = books();
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

    private void sync() {
        PcLink pc = app.pc();
        if (pc == null) { tab = "settings"; show(); Ui.toast(this, "First paste the link from Glossa on the PC (Settings → Phone access)"); return; }
        if (syncing) { Ui.toast(this, "Already syncing…"); return; }
        syncing = true;
        status.setText("syncing with the PC…");
        String lang = app.lang();
        app.bg(() -> {
            try {
                String msg = pc.sync(app.store(lang));
                app.post(() -> { Ui.toast(this, "Synced: " + msg); show(); });
            } catch (IOException e) {
                app.post(() -> { show(); Ui.alert(this, "Could not sync", explain(e)); });
            } finally {
                syncing = false;
            }
        });
    }

    static String explain(IOException e) {
        String m = String.valueOf(e.getMessage());
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.UnknownHostException || m.contains("timed out") || m.contains("Failed to connect"))
            return "The PC did not answer. Is it on, with Glossa running and Phone access switched on, and is the phone on the same Wi-Fi (or Tailscale)?\n\n" + m;
        return m;
    }

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
                LinearLayout it = Ui.item(this, Player.niceName(f), Ui.size(f.length()) + " · " + (subs == 0 ? "no subtitles" : subs + " subtitle file" + (subs > 1 ? "s" : "")) + (at > 5 ? " · at " + Ui.time(at) : ""),
                        v -> { Intent i = new Intent(this, Player.class); i.putExtra("path", f.getPath()); startActivity(i); });
                it.setOnLongClickListener(v -> {
                    Ui.dialog(this).setTitle(f.getName()).setItems(new String[]{"Delete this video and its subtitles"}, (d, w) -> {
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

        l.addView(Ui.title(this, "Dictionaries"));
        l.addView(Ui.hint(this, "Import the same wty zip files the PC uses (wty-it-it.zip, and the -ipa one for pronunciation). It takes a minute or two; the dictionary then works offline."));
        for (Dict d : app.dicts.list()) {
            Ui.add(l, Ui.item(this, d.title + " [" + d.lang + "]", d.summary(), v ->
                    Ui.dialog(this).setTitle(d.title).setItems(new String[]{"Delete this dictionary"}, (dl, i) -> { app.dicts.remove(d); show(); }).show()), 6);
        }
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
        l.addView(Ui.hint(this, "Sound and picture of a video line are cut on the phone from the video file (MP4 / MKV with AAC or Opus sound). Anything the phone cannot cut is left to the PC."));

        l.addView(Ui.title(this, "Problem report"));
        File last = new File(getFilesDir(), "crash-seen.txt");
        l.addView(Ui.hint(this, last.isFile() ? "The last time something went wrong, the app saved what happened. Copy it and send it to get it fixed." : "Nothing has gone wrong so far."));
        if (last.isFile()) Ui.add(l, Ui.button(this, "Show the report", v -> problemReport("Problem report", last)), 8);
        return Ui.scroll(this, l);
    }

    // ================================================================ picking files
    private void pick(int req, String[] types, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, types);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
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
