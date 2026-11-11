package com.batchmine.pocket;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.batchmine.pocket.core.Subs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Watching a video with its subtitles: tap a word for its card, repeat a line, step between lines. */
public final class Player extends Activity {
    private App app;
    private VideoView video;
    private TextView subText, trText, timeText;
    private SeekBar seek;
    private File file;
    private List<Subs.Cue> main = new ArrayList<>(), second = new ArrayList<>();
    private Colourer col;
    private int shown = -2;
    private final Handler h = new Handler();
    private boolean dragging, pausedForWord;
    private double loopUntil = -1, loopFrom = -1;
    private String lang;
    private android.media.MediaPlayer mpl;
    private float speed = 1f;
    private double delay;                                // seconds the subtitles are late (+) or early (-), saved for each video
    private boolean autoPause;
    private int pausedAt = -2;
    private android.widget.Button speedBtn, pauseBtn, markBtn;
    private android.widget.ImageView playIcon;
    // ---- the portrait layout: video on top, the lines of the subtitles scrolling below (Settings / the ⬒ button)
    private boolean stacked;
    private FrameLayout root, videoBox;
    private LinearLayout ctl, subsBox;
    private android.widget.ListView listView;
    private LineAdapter adapter;
    private int curLine = -1;
    private long userScrolled;                           // when you last scrolled the list yourself (the list then stays put for a while)
    private float listSp = 17;
    private final java.util.Map<Integer, CharSequence> spanCache = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean prime;                               // primed listening (Settings): a line's translation first, then the sound alone, then the text
    private int primeLine = -1;
    private String primePhase = "";                      // "read" (paused, translation) / "listen" (playing, no text) / ""
    private int loopLeft;                                // plays still to come of the line ↺ repeats (0 = until you go on)
    private final java.util.Map<Integer, Boolean> qual = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean isVideo(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".m4v") || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".3gp") || n.endsWith(".ts") || n.endsWith(".vlink");
    }

    /** The video's own file name (a link file keeps it on its second line). */
    static String videoName(File f) {
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".vlink")) {
            try { String[] p = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n"); if (p.length > 1 && !p[1].trim().isEmpty()) return p[1].trim(); }
            catch (IOException ignored) { }
        }
        return f.getName();
    }

    /** Where the video is: a copy in the app's folder, or (".vlink") a small file that points to the video where it already is. */
    static Uri videoUri(File f) {
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".vlink")) {
            try { return Uri.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n")[0].trim()); }
            catch (IOException | RuntimeException e) { App.get().report("Could not read the link " + f.getName(), e); }
        }
        return Uri.fromFile(f);
    }

    static String niceName(File f) { return f.getName().replaceAll("\\.[^.]+$", ""); }

    static boolean isSub(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass") || n.endsWith(".ssa");
    }

    /** A language code from a subtitle file name: "x.de.srt", "x.German.ass", "x_eng.srt" -> de / en; else "sub". */
    static String guessLang(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[][] known = {{"de", "german|deutsch|deu|ger"}, {"en", "english|eng"}, {"it", "italian|italiano|ita"}, {"hr", "croatian|hrvatski|hrv"},
                {"fr", "french|fran[cç]ais|fra|fre"}, {"es", "spanish|espa[nñ]ol|spa"}, {"ja", "japanese|jpn"}, {"pt", "portuguese|por"}, {"nl", "dutch|nld"}};
        for (String[] k : known) if (java.util.regex.Pattern.compile("(^|[^a-z])(" + k[0] + "|" + k[1] + ")([^a-z]|$)").matcher(n.replaceAll("\\.[^.]+$", "")).find()) return k[0];
        return "sub";
    }

    /** Subtitle files that belong to a video: its name, then a language tag, then .srt / .vtt / .ass / .ssa. */
    static List<File> subsOf(File v) {
        List<File> out = new ArrayList<>();
        String stem = niceName(v).toLowerCase(Locale.ROOT);
        File[] fs = v.getParentFile() == null ? null : v.getParentFile().listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!isSub(n)) continue;
            String s = n.substring(0, n.lastIndexOf('.'));
            if (s.equals(stem) || s.startsWith(stem + ".")) out.add(f);
        }
        return out;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = App.get();
        try { build(); }
        catch (RuntimeException e) {
            app.report("The player could not open " + getIntent().getStringExtra("path"), e);
            Ui.dialog(this).setTitle("Cannot open the video").setMessage(e + "\n\nThe details are saved: Settings → Problem report.")
                    .setPositiveButton("OK", (d, i) -> finish()).setOnCancelListener(d -> finish()).show();
        }
    }

    private void build() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        file = new File(getIntent().getStringExtra("path"));
        lang = app.lang();
        col = new Colourer(lang);
        col.colours = app.prefs().getBoolean("colours", true);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        videoBox = new FrameLayout(this);
        videoBox.setBackgroundColor(0xFF000000);
        videoBox.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(videoBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listView = new android.widget.ListView(this);
        listView.setDivider(null);
        listView.setVerticalScrollBarEnabled(false);
        listView.setSelector(android.R.color.transparent);
        listView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView v, int st) { if (st != SCROLL_STATE_IDLE) userScrolled = System.currentTimeMillis(); }
            @Override public void onScroll(android.widget.AbsListView v, int a, int b, int c) { }
        });
        adapter = new LineAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        video.setOnClickListener(v -> toggle());
        LinearLayout subs = Ui.col(this);
        subsBox = subs;
        subs.setGravity(Gravity.CENTER_HORIZONTAL);
        subs.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 96));
        subText = Ui.text(this, "", 22, Ui.TEXT);
        subText.setGravity(Gravity.CENTER);
        subText.setBackground(Ui.round(0xAA000000, Ui.dp(this, 8)));
        subText.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        subText.setMovementMethod(LinkMovementMethod.getInstance());
        subText.setHighlightColor(0x553584E4);
        subText.setVisibility(View.GONE);
        trText = Ui.text(this, "", 15, 0xFFCCCCCC);
        trText.setGravity(Gravity.CENTER);
        trText.setVisibility(View.GONE);
        trText.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), 0);
        trText.setShadowLayer(4, 0, 0, 0xFF000000);
        subs.addView(subText);
        subs.addView(trText);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(subs, sp);
        ctl = Ui.col(this);
        ctl.setBackgroundColor(0xCC000000);
        ctl.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 6));
        LinearLayout sr = Ui.row(this);
        timeText = Ui.text(this, "0:00", 13, Ui.TEXT);
        sr.addView(timeText);
        seek = new SeekBar(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.mix(Ui.BG, Ui.TEXT, 0.3f)));
        sr.addView(Ui.weight(seek, 1));
        ctl.addView(sr);
        // the PC player's own icons (mine_gui_player.js): previous line, repeat, play / pause, next line, subtitles, size, turn
        LinearLayout br = Ui.row(this);
        br.setGravity(Gravity.CENTER_VERTICAL);
        int[] ics = {R.drawable.ic_pl_prev, R.drawable.ic_pl_repeat, R.drawable.ic_pl_play, R.drawable.ic_pl_next, R.drawable.ic_pl_cc, 0, R.drawable.ic_pl_fs};
        String[] acts = {"prev", "repeat", "play", "next", "subs", "size", "rotate"};
        String[] descr = {"Previous line", "Repeat this line", "Play / pause", "Next line", "Subtitle files", "Text size", "Turn the picture"};
        for (int k = 0; k < acts.length; k++) {
            final String act = acts[k];
            View bb;
            if (ics[k] == 0) {
                TextView aa = Ui.text(this, "Aa", 17, Ui.TEXT);
                aa.setGravity(Gravity.CENTER);
                aa.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                bb = aa;
            } else {
                android.widget.ImageView iv = Ui.icon(this, ics[k], 24, k == 2 ? Ui.ON_ACC : Ui.TEXT);
                iv.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
                if (k == 2) { playIcon = iv; iv.setBackground(Ui.press(Ui.ACCENT, 0, Ui.dp(this, 99))); }
                bb = iv;
            }
            if (k != 2) bb.setBackground(Ui.press(0x00000000, 0, Ui.dp(this, 99)));
            bb.setContentDescription(descr[k]);
            bb.setOnClickListener(v -> action(act));
            br.addView(bb, new LinearLayout.LayoutParams(0, Ui.dp(this, k == 2 ? 50 : 44), 1f));
        }
        ctl.addView(br);
        LinearLayout br2 = Ui.row(this);
        speedBtn = Ui.small(this, "1×", v -> action("speed"));
        pauseBtn = Ui.small(this, "Stop after line: off", v -> action("autopause"));
        markBtn = Ui.small(this, "☆", v -> action("mark"));
        for (android.widget.Button bb : new android.widget.Button[]{speedBtn, markBtn, Ui.small(this, "Sync ±", v -> action("delay")), pauseBtn, Ui.small(this, "Lines", v -> action("lines")), Ui.small(this, "⬒", v -> action("layout"))}) {
            bb.setTextSize(12);
            bb.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, bb == pauseBtn ? 2.2f : 1f);
            lp.rightMargin = Ui.dp(this, 4);
            br2.addView(bb, lp);
        }
        ctl.addView(br2);
        root.addView(ctl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        // the subtitles sit just above the controls, whatever the height of those is (taller on a phone held upright)
        ctl.addOnLayoutChangeListener((vw, l, t, r, b, ol, ot, orr, ob) -> {
            int want = (b - t) + Ui.dp(this, 6);
            if (subs.getPaddingBottom() != want) subs.setPadding(subs.getPaddingLeft(), 0, subs.getPaddingRight(), want);
            if (stacked) {                                   // the list starts right under the controls
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) listView.getLayoutParams();
                if (lp.topMargin != b) { lp.topMargin = b; listView.setLayoutParams(lp); }
            }
        });

        setContentView(root);
        arrange();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) timeText.setText(Ui.time(p / 1000.0)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; video.seekTo(s.getProgress()); shown = -2; primeLine = -1; primePhase = ""; loopUntil = -1; }
        });
        delay = app.prefs().getFloat("delay_" + file.getName(), 0f);
        speed = app.prefs().getFloat("speed", 1f);
        autoPause = app.prefs().getBoolean("autopause", false);
        prime = app.prefs().getBoolean("prime", false);
        pauseBtn.setText(autoPause ? "Stop after line: on" : "Stop after line: off");
        speedBtn.setText(speedLabel(speed));
        loadSubs();
        video.setVideoURI(videoUri(file));
        video.setOnPreparedListener(mp -> {
            mpl = mp;
            applySpeed();
            seek.setMax(video.getDuration());
            int at = (int) (app.prefs().getFloat("vpos_" + file.getName(), 0) * 1000);
            double go = getIntent().getDoubleExtra("at", -1);                // from Home → Review: this line
            if (go >= 0) { video.seekTo((int) (Math.max(0, go - 1.5) * 1000)); getIntent().removeExtra("at"); }
            else if (at > 5000 && at < video.getDuration() - 8000) video.seekTo(at - 2000);
            video.start();
            h.removeCallbacksAndMessages(null);
            tick();
        });
        video.setOnCompletionListener(mp -> app.prefs().edit().putBoolean("vdone_" + file.getName(), true).putFloat("vpos_" + file.getName(), 0).putLong("vpost_" + file.getName(), System.currentTimeMillis()).apply());
        video.setOnErrorListener((mp, w, e) -> { Ui.alert(this, "Cannot play this video", "The phone could not play " + file.getName() + (file.getName().endsWith(".vlink") ? " (if you moved or deleted the video, add it again)" : "") + ". MP4 (H.264) works everywhere; other formats depend on the phone."); return true; });
    }

    private void loadSubs() {
        List<File> subs = subsOf(file);
        main.clear();
        second.clear();
        if (subs.isEmpty()) { Ui.toast(this, "No subtitle file next to this video (use CC to see which)"); return; }
        File a = null, t = null;
        String saved = app.prefs().getString("sub_" + file.getName(), "");
        for (File f : subs) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            String tag = n.substring(0, n.lastIndexOf('.'));
            tag = tag.substring(tag.lastIndexOf('.') + 1);
            if (!saved.isEmpty()) { if (f.getName().equals(saved)) a = f; continue; }
            if (a == null && tag.equals(lang)) a = f;
            else if (t == null && (tag.equals("en") && !lang.equals("en"))) t = f;
        }
        if (a == null) a = subs.get(0);
        if (t == null && saved.isEmpty() && subs.size() > 1) for (File f : subs) if (!f.equals(a)) { t = f; break; }
        String secName = app.prefs().getString("sub2_" + file.getName(), null);
        if (secName != null) { t = null; for (File f : subs) if (f.getName().equals(secName)) t = f; }
        main = read(a);
        second = t == null ? new ArrayList<>() : read(t);
        spanCache.clear();
        if (adapter != null) { arrange(); }
    }

    private List<Subs.Cue> read(File f) {
        try { return Subs.parse(Subs.decode(Files.readAllBytes(f.toPath()))); }
        catch (IOException | RuntimeException e) {
            app.report("Could not read " + f.getName(), e);
            Ui.toast(this, "Could not read " + f.getName());
            return new ArrayList<>();
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        arrange();
    }

    /** Portrait and the option on: video on top, controls under it, the lines below. Otherwise the picture fills the screen with the subtitles over it. */
    private void arrange() {
        boolean port = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        stacked = port && app.prefs().getBoolean("player_stack", true) && !main.isEmpty();
        int w = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams vb = (FrameLayout.LayoutParams) videoBox.getLayoutParams();
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) ctl.getLayoutParams();
        if (stacked) {
            root.setBackgroundColor(Ui.BG);
            vb.height = w * 9 / 16; vb.gravity = Gravity.TOP;
            cl.gravity = Gravity.TOP; cl.topMargin = vb.height;
            ctl.setBackgroundColor(Ui.CARD);
            FrameLayout.LayoutParams ll = (FrameLayout.LayoutParams) listView.getLayoutParams();
            ll.topMargin = vb.height + Ui.dp(this, 96);
            listView.setLayoutParams(ll);
            listView.setVisibility(View.VISIBLE);
            subsBox.setVisibility(View.GONE);
        } else {
            root.setBackgroundColor(0xFF000000);
            vb.height = ViewGroup.LayoutParams.MATCH_PARENT; vb.gravity = Gravity.NO_GRAVITY;
            cl.gravity = Gravity.BOTTOM; cl.topMargin = 0;
            ctl.setBackgroundColor(0xCC000000);
            listView.setVisibility(View.GONE);
            subsBox.setVisibility(View.VISIBLE);
        }
        videoBox.setLayoutParams(vb);
        ctl.setLayoutParams(cl);
        shown = -2;                                          // draw the line where it belongs now
        spanCache.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /** The lines of the subtitles as a list: the line being said is highlighted and followed; a tap on a line goes there, a tap on a word opens its card. */
    private final class LineAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return stacked ? main.size() : 0; }
        @Override public Object getItem(int i) { return main.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View v, ViewGroup parent) {
            LinearLayout row;
            TextView time, tx, tr;
            if (v == null) {
                row = Ui.col(Player.this);
                row.setPadding(Ui.dp(Player.this, 14), Ui.dp(Player.this, 9), Ui.dp(Player.this, 14), Ui.dp(Player.this, 9));
                time = Ui.text(Player.this, "", 11, Ui.DIM);
                tx = Ui.text(Player.this, "", listSp, Ui.TEXT);
                tx.setMovementMethod(LinkMovementMethod.getInstance());
                tx.setHighlightColor(0x553584E4);
                tr = Ui.text(Player.this, "", Math.max(12, listSp - 3), Ui.DIM);
                row.addView(time); row.addView(tx); row.addView(tr);
            } else {
                row = (LinearLayout) v;
                time = (TextView) row.getChildAt(0); tx = (TextView) row.getChildAt(1); tr = (TextView) row.getChildAt(2);
            }
            Subs.Cue c = main.get(pos);
            boolean cur = pos == curLine;
            boolean primed = cur && prime && pos == primeLine && ("read".equals(primePhase) || "listen".equals(primePhase));
            row.setBackground(cur ? Ui.round(Ui.mix(Ui.CARD, Ui.ACCENT, 0.22f), Ui.dp(Player.this, Ui.RS)) : null);
            time.setText((Marks.isMarked(file.getName(), c.start) ? "★ " : "") + Ui.time(c.start));
            time.setTextColor(cur ? Ui.ACCENT : Ui.DIM);
            tx.setTextSize(listSp);
            tx.setTextColor(cur ? Ui.TEXT : Ui.mix(Ui.BG, Ui.TEXT, 0.75f));
            tr.setTextSize(Math.max(12, listSp - 3));
            String t = trFor(c);
            if (primed) {                                       // primed listening: the translation to read, the sound alone, then the text
                tx.setText("· · ·");
                tr.setText(t);
                tr.setVisibility("read".equals(primePhase) && !t.isEmpty() ? View.VISIBLE : View.GONE);
            } else {
                CharSequence sp = spanCache.get(pos);
                if (sp == null) {
                    tx.setText(c.text);
                    final int p = pos;
                    app.bg(() -> {
                        CharSequence cs = col.spans(c.text, (word, start, end, text) -> tapped(word, start, text, c));
                        spanCache.put(p, cs);
                        app.post(() -> { if (stacked && !isFinishing()) adapter.notifyDataSetChanged(); });
                    });
                } else tx.setText(sp);
                tr.setText(t);
                tr.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
            }
            View.OnClickListener go = x -> jump(pos);
            row.setOnClickListener(go); time.setOnClickListener(go); tr.setOnClickListener(go); tx.setOnClickListener(go);
            return row;
        }
    }

    /** The list follows the line being said, unless you scrolled it yourself a moment ago. */
    private void followLine(int i) {
        curLine = i;
        adapter.notifyDataSetChanged();
        if (i >= 0 && System.currentTimeMillis() - userScrolled > 5000) listView.smoothScrollToPositionFromTop(i, Math.max(0, listView.getHeight() / 4), 250);
    }

    private void toggle() {
        if ("read".equals(primePhase)) { primePhase = "listen"; shown = -2; video.start(); return; }       // read enough: play the line
        if (video.isPlaying()) video.pause(); else video.start();
    }

    /** Does primed listening stop at this line? (Settings: every line, or only lines with an unknown word.) */
    private boolean primes(int i) {
        if (!"unk".equals(app.prefs().getString("prime_when", "all"))) return true;
        Boolean q = qual.get(i);
        return q != null && q;
    }

    /** Works out, off the screen's thread, which of the next lines have an unknown word (the dictionary is on disk). */
    private void qualify(int from) {
        if (!prime || !"unk".equals(app.prefs().getString("prime_when", "all"))) return;
        app.bg(() -> {
            for (int k = Math.max(0, from); k < Math.min(main.size(), from + 4); k++) {
                if (qual.containsKey(k)) continue;
                boolean unk = false;
                String text = main.get(k).text;
                for (int[] w : Subs.words(text)) if (col.state(text.substring(w[0], w[1])).equals("u")) { unk = true; break; }
                qual.put(k, unk);
            }
        });
    }

    /** Time to read a translation before the line plays: by its length, 1.5 to 8 s. */
    private long readMs(String tr) { return Math.max(1500, Math.min(8000, 700 + tr.length() * 55L)); }

    private String trFor(Subs.Cue c) {
        StringBuilder tr = new StringBuilder();
        for (Subs.Cue x : second) if (x.start < c.end && x.end > c.start) { if (tr.length() > 0) tr.append('\n'); tr.append(x.text); }
        return tr.toString();
    }

    private void action(String a) {
        double t = video.getCurrentPosition() / 1000.0;
        switch (a) {
            case "play": toggle(); break;
            case "prev": { double tt = t - delay; int i = Subs.around(main, tt); int j = Subs.at(main, tt) >= 0 ? i - 1 : i; jump(j); break; }
            case "next": { double tt = t - delay; int i = Subs.at(main, tt); int j = i >= 0 ? i + 1 : nextAfter(tt); jump(j); break; }
            case "speed": {
                float[] steps = {0.6f, 0.75f, 0.9f, 1f, 1.25f, 1.5f};
                int k = 0;
                for (int q = 0; q < steps.length; q++) if (Math.abs(steps[q] - speed) < 0.01f) k = q;
                speed = steps[(k + 1) % steps.length];
                app.prefs().edit().putFloat("speed", speed).apply();
                speedBtn.setText(speedLabel(speed));
                applySpeed();
                break;
            }
            case "autopause":
                autoPause = !autoPause;
                app.prefs().edit().putBoolean("autopause", autoPause).apply();
                pauseBtn.setText(autoPause ? "Stop after line: on" : "Stop after line: off");
                pausedAt = -2;
                Ui.toast(this, autoPause ? "The video stops at the end of every line: press play for the next" : "Plays on");
                break;
            case "delay": delayDialog(); break;
            case "mark": {                                         // ★ the line on screen: it comes back in Home → Review
                int i = Subs.around(main, t - delay);
                if (i < 0) { Ui.toast(this, "No line here"); break; }
                Marks.add(file.getName(), main.get(i).start, main.get(i).text, true);
                markBtn.setText(Marks.isMarked(file.getName(), main.get(i).start) ? "★" : "☆");
                Ui.toast(this, Marks.isMarked(file.getName(), main.get(i).start) ? "Marked: it waits in Home → Review" : "Mark taken off");
                break;
            }
            case "rotate":      // turn the picture by hand (the phone's own auto-rotate also works)
                boolean port = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
                setRequestedOrientation(port ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                break;
            case "lines": linesDialog(); break;
            case "layout": {                                 // portrait: video on top + lines below  <->  picture fills the screen
                boolean on = !app.prefs().getBoolean("player_stack", true);
                app.prefs().edit().putBoolean("player_stack", on).apply();
                arrange();
                Ui.toast(this, on ? "Video on top, the lines below (in portrait)" : "The picture fills the screen");
                break;
            }
            case "repeat": {
                int i = Subs.around(main, t - delay);
                if (i < 0) return;
                Marks.add(file.getName(), main.get(i).start, main.get(i).text, false);       // a line you replay is a hard one
                loopFrom = main.get(i).start + delay - 0.15;
                loopUntil = main.get(i).end + delay;
                int n = app.prefs().getInt("loop_times", 1);
                loopLeft = n;                                  // 0: round and round until you press next / prev / play on
                primeLine = i; primePhase = ""; shown = -2;
                video.seekTo((int) (Math.max(0, loopFrom) * 1000));
                video.start();
                break;
            }
            case "subs": subsDialog(); break;
            case "size": {
                if (stacked) { listSp = listSp >= 26 ? 14 : listSp + 3; adapter.notifyDataSetChanged(); break; }
                float s = subText.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
                s = s >= 30 ? 16 : s + 4;
                subText.setTextSize(s);
                trText.setTextSize(Math.max(12, s - 6));
                break;
            }
        }
    }

    static String speedLabel(float s) { return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "×"; }

    private void applySpeed() {
        if (mpl == null || android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            boolean playing = video.isPlaying();
            mpl.setPlaybackParams(mpl.getPlaybackParams().setSpeed(speed));
            if (!playing) video.pause();           // setting the speed can start the playback
        } catch (RuntimeException e) { Ui.toast(this, "This phone cannot change the speed of this video"); }
    }

    private void setDelay(double d) {
        delay = Math.round(d * 10) / 10.0;
        app.prefs().edit().putFloat("delay_" + file.getName(), (float) delay).apply();
        shown = -2;
    }

    private void delayDialog() {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        TextView info = Ui.text(this, "", 16, Ui.TEXT);
        Runnable draw = () -> info.setText(delay == 0 ? "The subtitles are on time" : String.format(Locale.ROOT, "Subtitles come %.1f s %s", Math.abs(delay), delay > 0 ? "later" : "earlier"));
        draw.run();
        box.addView(info);
        LinearLayout r = Ui.row(this);
        double[] steps = {-1, -0.1, 0.1, 1};
        for (double st : steps) r.addView(Ui.weight(Ui.button(this, (st > 0 ? "+" : "−") + Math.abs(st) + " s", v -> { setDelay(delay + st); draw.run(); }), 1));
        box.addView(r);
        box.addView(Ui.hint(this, "If the words come before the sound, use + ; if they come after, use −. The setting is kept for this video."));
        Ui.dialog(this).setTitle("Subtitle timing").setView(box).setPositiveButton("OK", null).setNeutralButton("Reset", (d, w) -> setDelay(0)).show();
    }

    private void linesDialog() {
        if (main.isEmpty()) { Ui.toast(this, "No subtitles to list"); return; }
        String[] items = new String[main.size()];
        for (int i = 0; i < items.length; i++) items[i] = Ui.time(main.get(i).start) + "  " + main.get(i).text.replace('\n', ' ');
        int now = Math.max(0, Subs.around(main, video.getCurrentPosition() / 1000.0 - delay));
        android.app.AlertDialog dlg = Ui.dialog(this).setTitle("All lines (" + items.length + ")").setItems(items, (d, i) -> jump(i))
                .setNeutralButton("Unknown words", (d, w) -> unknownWords()).create();
        dlg.getListView().setFastScrollEnabled(true);
        dlg.show();
        dlg.getListView().setSelection(now);
    }

    /** The words of this video you do not know yet, most frequent first (the PC's "Make cards" list): tap one for its card in the first line it is in. */
    private void unknownWords() {
        if (main.isEmpty()) return;
        Ui.toast(this, "Counting the unknown words…");
        app.bg(() -> {
            java.util.Map<String, int[]> found = new java.util.LinkedHashMap<>();       // word -> {count, first line}
            java.util.Map<String, Boolean> state = new java.util.HashMap<>();
            for (int i = 0; i < main.size(); i++) {
                String text = main.get(i).text;
                java.util.Set<String> inLine = new java.util.HashSet<>();
                for (int[] w : Subs.words(text)) {
                    String word = text.substring(w[0], w[1]).toLowerCase(Locale.ROOT);
                    if (word.length() < 2 || !inLine.add(word)) continue;
                    Boolean unk = state.get(word);
                    if (unk == null) { unk = col.state(word).equals("u"); state.put(word, unk); }
                    if (!unk) continue;
                    int[] c = found.get(word);
                    if (c == null) found.put(word, new int[]{1, i}); else c[0]++;
                }
            }
            List<java.util.Map.Entry<String, int[]>> top = new ArrayList<>(found.entrySet());
            top.sort((a, b) -> b.getValue()[0] != a.getValue()[0] ? Integer.compare(b.getValue()[0], a.getValue()[0]) : Integer.compare(a.getValue()[1], b.getValue()[1]));
            if (top.size() > 80) top = new ArrayList<>(top.subList(0, 80));
            final List<java.util.Map.Entry<String, int[]>> list = top;
            app.post(() -> {
                if (list.isEmpty()) { Ui.alert(this, "Unknown words", "No unknown words in these subtitles."); return; }
                String[] items = new String[list.size()];
                for (int i = 0; i < items.length; i++) items[i] = list.get(i).getKey() + "   ×" + list.get(i).getValue()[0];
                Ui.dialog(this).setTitle(found.size() + " unknown words, most frequent first").setItems(items, (d, i) -> {
                    int line = list.get(i).getValue()[1];
                    String w = list.get(i).getKey();
                    video.pause();
                    tapped(w, 0, main.get(line).text, main.get(line));
                }).show();
            });
        });
    }

    private int nextAfter(double t) { for (int i = 0; i < main.size(); i++) if (main.get(i).start > t) return i; return -1; }

    /** Primed listening, every 120 ms: a new line pauses for its translation, then plays without text, then the text shows. */
    private void primeStep(int i, double t) {
        if ("listen".equals(primePhase) && (i != primeLine || t - delay >= main.get(primeLine).end - 0.05)) { primePhase = ""; shown = -2; return; }   // the line is over: its text shows
        if (i >= 0 && i != primeLine && (t - delay) - main.get(i).start < 0.6 && video.isPlaying()) {
            primeLine = i;
            qualify(i + 1);
            if (!primes(i)) { primePhase = ""; return; }
            String tr = trFor(main.get(i));
            if (tr.isEmpty()) { primePhase = "listen"; shown = -2; return; }       // no translation to read: straight to listening
            primePhase = "read"; shown = -2;
            video.pause();
            final int line = i;
            h.postDelayed(() -> { if ("read".equals(primePhase) && primeLine == line && !isFinishing()) { primePhase = "listen"; shown = -2; video.start(); } }, readMs(tr));
        }
    }

    private void jump(int i) {
        if (i < 0 || i >= main.size()) return;
        loopUntil = -1; primeLine = -1; primePhase = "";
        video.seekTo((int) (Math.max(0, main.get(i).start + delay - 0.05) * 1000));
        shown = -2;
        video.start();
    }

    private void subsDialog() {
        List<File> subs = subsOf(file);
        if (subs.isEmpty()) { Ui.alert(this, "Subtitles", "There is no subtitle file (.srt, .vtt, .ass) with this video. Add the subtitle files with the video (Videos → Add a video, choose both), or copy it from the PC with its subtitles."); return; }
        String[] names = new String[subs.size()];
        for (int i = 0; i < names.length; i++) names[i] = subs.get(i).getName();
        Ui.dialog(this).setTitle("Subtitles to learn from").setItems(names, (d, i) -> {
            app.prefs().edit().putString("sub_" + file.getName(), names[i]).apply();
            String[] two = new String[names.length + 1];
            two[0] = "(no translation)";
            System.arraycopy(names, 0, two, 1, names.length);
            Ui.dialog(this).setTitle("Translation line (under it)").setItems(two, (d2, j) -> {
                app.prefs().edit().putString("sub2_" + file.getName(), j == 0 ? "-" : two[j]).apply();
                loadSubs();
                shown = -2;
            }).show();
        }).show();
    }

    private boolean tickFailed;

    private void tick() {
        if (isFinishing() || video == null) return;
        try {
            int pos = video.getCurrentPosition();
            double t = pos / 1000.0;
            if (!dragging) { seek.setProgress(pos); timeText.setText(Ui.time(t)); }
            if (playIcon != null) playIcon.setImageResource(video.isPlaying() ? R.drawable.ic_pl_pause : R.drawable.ic_pl_play);
            if (loopUntil > 0 && t >= loopUntil) {
                if (loopLeft == 0 || --loopLeft > 0) video.seekTo((int) (Math.max(0, loopFrom) * 1000));
                else loopUntil = -1;                           // played as often as asked: on with the film
            }
            int i = Subs.at(main, t - delay);
            if (prime && loopUntil < 0) primeStep(i, t);
            if (i != shown) { shown = i; showLine(i); }
            if (autoPause && i >= 0 && i != pausedAt && t - delay >= main.get(i).end - 0.08 && video.isPlaying()) { pausedAt = i; video.pause(); }
            if (i >= 0 && i != pausedAt && t - delay < main.get(i).end - 0.3) pausedAt = -2;
        } catch (RuntimeException e) {
            if (!tickFailed) { tickFailed = true; app.report("Player: showing a line", e); Ui.toast(this, "Something went wrong: " + e); }
        }
        h.postDelayed(this::tick, 120);
    }

    private void showLine(int i) {
        if (stacked) {
            if (i >= 0) markBtn.setText(Marks.isMarked(file.getName(), main.get(i).start) ? "★" : "☆");
            followLine(i);
            return;
        }
        if (i < 0) { subText.setVisibility(View.GONE); trText.setVisibility(View.GONE); return; }
        Subs.Cue c = main.get(i);
        markBtn.setText(Marks.isMarked(file.getName(), c.start) ? "★" : "☆");
        if (prime && i == primeLine && ("read".equals(primePhase) || "listen".equals(primePhase))) {
            subText.setVisibility(View.GONE);                              // no text while it is primed: the translation to read, then the sound alone
            String tr0 = trFor(c);
            trText.setText(tr0);
            trText.setVisibility("read".equals(primePhase) && tr0.length() > 0 ? View.VISIBLE : View.GONE);
            return;
        }
        final String text = c.text;
        app.bg(() -> {
            CharSequence s = col.spans(text, (word, start, end, tx) -> tapped(word, start, tx, c));
            app.post(() -> { if (shown == i) { subText.setText(s); subText.setVisibility(View.VISIBLE); } });
        });
        StringBuilder tr = new StringBuilder();
        for (Subs.Cue x : second) if (x.start < c.end && x.end > c.start) { if (tr.length() > 0) tr.append('\n'); tr.append(x.text); }
        trText.setText(tr);
        trText.setVisibility(tr.length() > 0 ? View.VISIBLE : View.GONE);
    }

    private void tapped(String word, int at, String text, Subs.Cue c) {
        video.pause();
        Marks.add(file.getName(), c.start, c.text, false);                  // a line you looked a word up in
        Cards.Request r = new Cards.Request();
        r.lang = lang;
        r.kind = "video";
        r.sentence = text.replace('\n', ' ');
        r.start = c.start + delay;
        r.end = c.end + delay;
        r.video = videoUri(file).toString();
        r.videoName = videoName(file);
        r.title = niceName(file);
        StringBuilder tr = new StringBuilder();
        for (Subs.Cue x : second) if (x.start < c.end && x.end > c.start) { if (tr.length() > 0) tr.append(' '); tr.append(x.text.replace('\n', ' ')); }
        r.translation = tr.toString();
        r.frameAt = (c.start + c.end) / 2 + delay;
        int ci = main.indexOf(c);
        for (int k = 1; k <= 3 && ci >= 0; k++) {
            if (ci - k >= 0) r.before.add(new Cards.Ctx(main.get(ci - k).text.replace('\n', ' '), main.get(ci - k).start + delay, main.get(ci - k).end + delay));
            if (ci + k < main.size()) r.after.add(new Cards.Ctx(main.get(ci + k).text.replace('\n', ' '), main.get(ci + k).start + delay, main.get(ci + k).end + delay));
        }
        WordSheet.show(this, lang, word, r, () -> { col.forget(); spanCache.clear(); shown = -2; });
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { Stopwatch.active(); return super.dispatchTouchEvent(e); }

    @Override protected void onResume() {
        super.onResume();
        Stopwatch.start("watch", () -> video != null && video.isPlaying());     // counts while the video plays, and for a while after a touch
    }

    @Override protected void onPause() {
        super.onPause();
        Stopwatch.stop();
        if (video != null && file != null) {
            float pos = video.getCurrentPosition() / 1000f, dur = Math.max(0, video.getDuration()) / 1000f;
            android.content.SharedPreferences.Editor ed = app.prefs().edit().putFloat("vpos_" + file.getName(), pos).putLong("vpost_" + file.getName(), System.currentTimeMillis());
            if (dur > 30) {
                ed.putFloat("vdur_" + file.getName(), dur);
                if (pos > dur - 45 || pos > dur * 0.93f) ed.putBoolean("vdone_" + file.getName(), true);      // watched to the end (credits do not count)
            }
            ed.apply();
            video.pause();
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null); }
}
