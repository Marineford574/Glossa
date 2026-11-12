package com.batchmine.pocket;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private TextView subText, trText;
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
    /** How the lines are used (the PC's modes): normal, primed, hidden, paused, i1 (subtitles only on i+1 lines), pi1 / punk (pause on i+1 / on new-word lines). */
    private String pmode = "normal";
    private int lastLine = -1;                           // the newest line that has started: the list masks the ones after it
    private boolean wasPlaying = true;
    private int pausedAt = -2;
    private TextView speedLbl, statusText;
    private android.widget.ImageView playIcon, markBtn, fsIcon;
    private Timeline tl;
    private LinearLayout topBar;
    private boolean hideSub, hideTr, uiOn = true;
    private long lastTouch = System.currentTimeMillis();
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
    private final java.util.Map<Integer, Integer> unkN = new java.util.concurrent.ConcurrentHashMap<>();   // line → how many words in it you don't know

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

    /** Show, season and episode of a video: from its name, and where the name says no show, from the folder it was added from (prefs vshow_ / vseason_). */
    static com.batchmine.pocket.core.Shows.Ep epOf(File f) {
        String name = videoName(f);
        com.batchmine.pocket.core.Shows.Ep e = com.batchmine.pocket.core.Shows.of(name);
        android.content.SharedPreferences sp = App.get().prefs();
        String vs = sp.getString("vshow_" + f.getName(), "");
        if (!vs.isEmpty()) {
            e = e.with(vs, sp.getInt("vseason_" + f.getName(), 0));
            if (e.episode < 0) {                                  // "01.mkv", "Episode.mkv": the first number in the name
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,3})").matcher(name.replaceAll("\\.[^.]+$", ""));
                if (m.find()) e = e.withEpisode(Integer.parseInt(m.group(1)));
            }
        }
        return e;
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

    // ---------------------------------------------------------------- headphones and earbuds: play / pause (one press), next / previous line
    private android.media.session.MediaSession session;
    private int lastState = -1;
    private final android.content.BroadcastReceiver noisy = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, Intent i) { if (video != null && video.isPlaying()) video.pause(); }     // headphones pulled out
    };

    private void mediaButtons() {
        session = new android.media.session.MediaSession(this, "Glossa player");
        session.setCallback(new android.media.session.MediaSession.Callback() {
            @Override public void onPlay() { if (video != null && !video.isPlaying()) toggle(); }
            @Override public void onPause() { if (video != null && video.isPlaying()) video.pause(); }
            @Override public void onSkipToNext() { if (video != null) action("next"); }
            @Override public void onSkipToPrevious() { if (video != null) action("prev"); }
        });
        session.setActive(true);
        playState(false, 0);
        try { registerReceiver(noisy, new android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)); } catch (RuntimeException ignored) { }
    }

    /** Tells the phone (and so the headphones) whether the video plays: a press then pauses or plays it. */
    private void playState(boolean playing, int posMs) {
        int st = playing ? android.media.session.PlaybackState.STATE_PLAYING : android.media.session.PlaybackState.STATE_PAUSED;
        if (session == null || st == lastState) return;
        lastState = st;
        session.setPlaybackState(new android.media.session.PlaybackState.Builder()
                .setActions(android.media.session.PlaybackState.ACTION_PLAY | android.media.session.PlaybackState.ACTION_PAUSE | android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                        | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(st, posMs, playing ? speed : 0f).build());
    }

    /** The status bar and the navigation bar stay hidden while you watch; a swipe from the edge shows them for a moment. */
    @SuppressWarnings("deprecation")
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }

    private void build() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        mediaButtons();
        file = new File(getIntent().getStringExtra("path"));
        lang = app.lang();
        col = new Colourer(lang);
        col.colours = app.flag("colours", true);
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
        video.setOnClickListener(v -> { if (!stacked && !uiOn) setUi(true); else toggle(); });
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
        // ---- the top bar: back, the title (series name, "Season 1 · Episode 3" under it), what is switched on, ★ for the line (the PC's title bar)
        topBar = Ui.row(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB0000000, 0x00000000}));
        topBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 22));
        android.widget.ImageView backIc = barIcon(R.drawable.ic_pl_back, "Back");
        backIc.setOnClickListener(v -> finish());
        topBar.addView(backIc, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        LinearLayout tcol = Ui.col(this);
        com.batchmine.pocket.core.Shows.Ep ep = epOf(file);
        TextView ttl = Ui.text(this, !ep.show.isEmpty() ? ep.show : com.batchmine.pocket.core.Shows.pretty(videoName(file)), 16, 0xFFFFFFFF);
        ttl.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        ttl.setSingleLine(true); ttl.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ttl.setShadowLayer(3, 0, 1, 0xFF000000);
        tcol.addView(ttl);
        if (ep.episode >= 0) { TextView es = Ui.text(this, ep.longLabel(), 12, 0xFFDDDDDD); es.setShadowLayer(3, 0, 1, 0xFF000000); tcol.addView(es); }
        topBar.addView(tcol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusText = Ui.pill(this, "", 0xFFFFFFFF, 0x66000000);
        statusText.setVisibility(View.GONE);
        topBar.addView(statusText);
        markBtn = barIcon(R.drawable.ic_pl_star_o, "Mark the line for a card");
        markBtn.setOnClickListener(v -> action("mark"));
        topBar.addView(markBtn, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        // ---- the controls: one row of the PC's icons above the timeline strip (uosc)
        ctl = Ui.col(this);
        LinearLayout br = Ui.row(this);
        br.setGravity(Gravity.CENTER_VERTICAL);
        br.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 2));
        int[] ics = {R.drawable.ic_pl_menu, R.drawable.ic_pl_cc, R.drawable.ic_pl_prev, R.drawable.ic_pl_play, R.drawable.ic_pl_next, R.drawable.ic_pl_repeat, 0, R.drawable.ic_pl_fs};
        String[] acts = {"menu", "subs", "prev", "play", "next", "repeat", "speed", "rotate"};
        String[] descr = {"Menu", "Subtitles", "Previous line", "Play / pause", "Next line", "Say this line again", "Speed", "Turn the picture"};
        for (int k = 0; k < acts.length; k++) {
            final String act = acts[k];
            View bb;
            if (ics[k] == 0) {
                speedLbl = Ui.text(this, "1×", 14, 0xFFFFFFFF);
                speedLbl.setGravity(Gravity.CENTER);
                speedLbl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                speedLbl.setShadowLayer(2, 0, 0, 0xFF000000);
                bb = speedLbl;
            } else {
                android.widget.ImageView iv = Ui.icon(this, ics[k], 24, k == 3 ? Ui.ON_ACC : 0xFFFFFFFF);
                iv.setPadding(Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9), Ui.dp(this, 9));
                if (k == 3) { playIcon = iv; iv.setBackground(Ui.press(Ui.ACCENT, 0, Ui.dp(this, 99))); }
                else if (k == 7) fsIcon = iv;
                bb = iv;
            }
            if (k != 3) bb.setBackground(Ui.press(0x00000000, 0x00000000, Ui.dp(this, 99)));
            bb.setContentDescription(descr[k]);
            bb.setOnClickListener(v -> action(act));
            if (k == 1) {                                        // the subtitle button with the number of subtitle files on it (the PC's badge)
                FrameLayout fl = new FrameLayout(this);
                fl.addView(bb);
                int n = subsOf(file).size();
                if (n > 0) {
                    TextView bd = Ui.text(this, String.valueOf(n), 9, 0xFF000000);
                    bd.setGravity(Gravity.CENTER);
                    bd.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    bd.setBackground(Ui.round(0xFFFFFFFF, Ui.dp(this, 99)));
                    fl.addView(bd, new FrameLayout.LayoutParams(Ui.dp(this, 15), Ui.dp(this, 15), Gravity.END | Gravity.BOTTOM));
                    ((FrameLayout.LayoutParams) bd.getLayoutParams()).setMargins(0, 0, Ui.dp(this, 6), Ui.dp(this, 5));
                }
                br.addView(fl, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
            } else br.addView(bb, new LinearLayout.LayoutParams(0, Ui.dp(this, k == 3 ? 50 : 44), 1f));
        }
        ctl.addView(br);
        tl = new Timeline(this);
        tl.onSeek(ms -> { dragging = false; video.seekTo(ms); shown = -2; primeLine = -1; primePhase = ""; loopUntil = -1; }, () -> dragging = true);
        tl.onScrub(this::scrub);
        ctl.addView(tl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40)));
        prev = new ImageView(this);
        prev.setScaleType(ImageView.ScaleType.CENTER_CROP);
        prev.setBackground(Ui.round(0xFF000000, Ui.dp(this, 8)));
        prev.setClipToOutline(true);
        prev.setVisibility(View.GONE);
        root.addView(ctl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        root.addView(prev, new FrameLayout.LayoutParams(Ui.dp(this, 176), Ui.dp(this, 99), Gravity.BOTTOM | Gravity.START));
        // the subtitles sit just above the controls, whatever the height of those is (taller on a phone held upright)
        ctl.addOnLayoutChangeListener((vw, l, t, r, b, ol, ot, orr, ob) -> {
            if (!uiOn && !stacked) return;
            int want = (b - t) + Ui.dp(this, 6);
            if (subs.getPaddingBottom() != want) subs.setPadding(subs.getPaddingLeft(), 0, subs.getPaddingRight(), want);
            if (stacked) {                                   // the list starts right under the controls
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) listView.getLayoutParams();
                if (lp.topMargin != b) { lp.topMargin = b; listView.setLayoutParams(lp); }
            }
        });

        setContentView(root);
        arrange();
        delay = app.prefs().getFloat("delay_" + file.getName(), 0f);
        speed = app.prefs().getFloat(lk("speed"), app.prefs().getFloat("speed", 1f));
        pmode = app.prefs().getString(lk("pmode"), app.prefs().getString("pmode", app.prefs().getBoolean("prime", false) ? "primed" : "normal"));
        prime = "primed".equals(pmode);
        speedLbl.setText(speedLabel(speed));
        loadSubs();
        video.setVideoURI(videoUri(file));
        video.setOnPreparedListener(mp -> {
            mpl = mp;
            applySpeed();
            int at = (int) (app.prefs().getFloat("vpos_" + file.getName(), 0) * 1000);
            double go = getIntent().getDoubleExtra("at", -1);                // from Home → Review: this line
            if (go >= 0) { video.seekTo((int) (Math.max(0, go - 1.5) * 1000)); getIntent().removeExtra("at"); }
            else if (at > 5000 && at < video.getDuration() - 8000) video.seekTo(at - 2000);
            video.start();
            h.removeCallbacksAndMessages(null);
            tick();
        });
        queue = getIntent().getStringArrayExtra("queue");
        video.setOnCompletionListener(mp -> finishVideo());
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
        mainFile = a;
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
            ctl.setBackgroundColor(0xFF000000);
            ctl.setPadding(0, 0, 0, 0);
            FrameLayout.LayoutParams ll = (FrameLayout.LayoutParams) listView.getLayoutParams();
            ll.topMargin = vb.height + Ui.dp(this, 96);
            uiOn = true; topBar.setVisibility(View.VISIBLE); ctl.setVisibility(View.VISIBLE);
            listView.setLayoutParams(ll);
            listView.setVisibility(View.VISIBLE);
            subsBox.setVisibility(View.GONE);
        } else {
            root.setBackgroundColor(0xFF000000);
            vb.height = ViewGroup.LayoutParams.MATCH_PARENT; vb.gravity = Gravity.NO_GRAVITY;
            cl.gravity = Gravity.BOTTOM; cl.topMargin = 0;
            ctl.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xAA000000, 0x00000000}));
            ctl.setPadding(0, Ui.dp(this, 18), 0, 0);
            listView.setVisibility(View.GONE);
            subsBox.setVisibility(View.VISIBLE);
        }
        if (fsIcon != null) fsIcon.setImageResource(port ? R.drawable.ic_pl_fs : R.drawable.ic_pl_fs_exit);
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
            boolean masked = pos >= lastLine && !modeShows(pos);
            boolean primed = cur && prime && pos == primeLine && ("read".equals(primePhase) || "listen".equals(primePhase));
            row.setBackground(cur ? Ui.round(Ui.mix(Ui.CARD, Ui.ACCENT, 0.22f), Ui.dp(Player.this, Ui.RS)) : null);
            time.setText((Marks.isMarked(file.getName(), c.start) ? "★ " : "") + Ui.time(c.start));
            time.setTextColor(cur ? Ui.ACCENT : Ui.DIM);
            tx.setTextSize(listSp);
            tx.setTextColor(cur ? Ui.TEXT : Ui.mix(Ui.BG, Ui.TEXT, 0.75f));
            tr.setTextSize(Math.max(12, listSp - 3));
            String t = trFor(c);
            if (masked && !primed) {
                tx.setText("· · ·");
                tr.setText(trFor(c));
                tr.setVisibility(trFor(c).isEmpty() || hideTr || prime ? View.GONE : View.VISIBLE);
                tx.setVisibility(View.VISIBLE);
            } else if (primed) {                                       // primed listening: the translation to read, the sound alone, then the text
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
                tr.setVisibility(t.isEmpty() || hideTr ? View.GONE : View.VISIBLE);
                tx.setVisibility(hideSub && !cur ? View.GONE : View.VISIBLE);
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
        sheetUp = false;
        if ("read".equals(primePhase)) { primePhase = "listen"; shown = -2; video.start(); return; }       // read enough: play the line
        if (video.isPlaying()) video.pause(); else video.start();
    }

    /** Does primed listening stop at this line? (Settings: every line, or only lines with an unknown word.) */
    private boolean primes(int i) {
        if (!"unk".equals(app.prefs().getString(lk("prime_when"), app.prefs().getString("prime_when", "all")))) return true;
        Integer q = unkN.get(i);
        return q != null && q > 0;
    }

    /** Does the mode need to know how many unknown words the lines have? */
    private boolean needsCounts() {
        return "i1".equals(pmode) || "pi1".equals(pmode) || "punk".equals(pmode) || (prime && "unk".equals(app.prefs().getString(lk("prime_when"), app.prefs().getString("prime_when", "all"))));
    }

    /** Works out, off the screen's thread, how many unknown words the next lines have (the dictionary is on disk). */
    private void qualify(int from) {
        if (!needsCounts()) return;
        boolean missing = false;
        for (int k = Math.max(0, from); k < Math.min(main.size(), Math.max(0, from) + 6); k++) if (!unkN.containsKey(k)) missing = true;
        if (!missing) return;
        final int f = Math.max(0, from);
        app.bg(() -> {
            boolean got = false;
            for (int k = f; k < Math.min(main.size(), f + 6); k++) {
                if (unkN.containsKey(k)) continue;
                int n = 0;
                String text = main.get(k).text;
                for (int[] w : Subs.words(text)) if (w[1] - w[0] > 1 && col.state(text.substring(w[0], w[1])).equals("u")) n++;
                unkN.put(k, n);
                got = true;
            }
            if (got) app.post(() -> { if (!isFinishing()) { shown = -2; if (stacked) adapter.notifyDataSetChanged(); } });
        });
    }

    /** May the text of this line be seen now, in the mode that is on? */
    private boolean modeShows(int i) {
        switch (pmode) {
            case "hidden": return false;
            case "paused": return video != null && !video.isPlaying();
            case "i1": { Integer n = unkN.get(i); return n != null && n == 1; }
            default: return true;
        }
    }

    private static final String[] MODE_KEYS = {"normal", "primed", "hidden", "paused", "i1", "pi1", "punk"};
    private static final String[] MODE_NAMES = {"Normal", "Primed listening", "Hidden subtitles", "Subtitles only while paused", "Subtitles only on i+1 lines", "Stop at i+1 lines", "Stop at lines with new words"};
    private static final String[] MODE_HINTS = {"The subtitles as the video goes", "The translation first, then the sound alone, then the text", "No subtitle text; tap a line in the list or pause to look",
            "The text shows when the video is paused", "Only lines with exactly one word you don't know show their text", "Stops at the end of a line with exactly one new word", "Stops at the end of every line with a word you don't know"};

    private String modeName() { for (int i = 0; i < MODE_KEYS.length; i++) if (MODE_KEYS[i].equals(pmode)) return MODE_NAMES[i]; return MODE_NAMES[0]; }

    private void setMode(String m) {
        pmode = m;
        prime = "primed".equals(m);
        app.prefs().edit().putString(lk("pmode"), m).putBoolean("prime", prime).apply();
        primeLine = -1; primePhase = ""; pausedAt = -2; shown = -2;
        if (needsCounts()) qualify(Math.max(0, lastLine));
        if (adapter != null) adapter.notifyDataSetChanged();
        Ui.toast(this, modeName());
    }

    /** The mode picker: each with a line saying what it does. */
    private void modeMenu() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 12));
        android.app.Dialog[] d = new android.app.Dialog[1];
        for (int i = 0; i < MODE_KEYS.length; i++) {
            final String k = MODE_KEYS[i];
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
            r.setBackground(Ui.press(0x00000000, 0x00000000, Ui.dp(this, Ui.RS)));
            TextView ck = Ui.text(this, k.equals(pmode) ? "✓" : "", 15, Ui.ACCENT);
            ck.setMinWidth(Ui.dp(this, 24));
            r.addView(ck);
            LinearLayout c = Ui.col(this);
            TextView t = Ui.text(this, MODE_NAMES[i], 16, Ui.TEXT);
            if (k.equals(pmode)) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            c.addView(t);
            c.addView(Ui.text(this, MODE_HINTS[i], 12, Ui.DIM));
            r.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.setOnClickListener(v -> { Ui.dismiss(d[0]); setMode(k); });
            l.addView(r);
        }
        d[0] = Ui.sheet(this, "How to use the lines", Ui.scroll(this, l), true);
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
            case "speed": speedMenu(); break;
            case "menu": mainMenu(); break;
            case "quick": quick(); break;
            case "mode": modeMenu(); break;
            case "delay": delayDialog(); break;
            case "mark": {                                         // ★ the line on screen: it comes back in Home → Review
                int i = Subs.around(main, t - delay);
                if (i < 0) { Ui.toast(this, "No line here"); break; }
                Marks.add(file.getName(), main.get(i).start, main.get(i).text, true);
                markIcon(Marks.isMarked(file.getName(), main.get(i).start));
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
                learnHard(main.get(i));
                loopFrom = main.get(i).start + delay - 0.15;
                loopUntil = main.get(i).end + delay;
                int n = app.prefs().getInt(lk("loop_times"), app.prefs().getInt("loop_times", 1));
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


    // ================================================================ the PC player's look: menus, quick settings, bars
    private android.widget.ImageView barIcon(int res, String what) {
        android.widget.ImageView iv = Ui.icon(this, res, 24, 0xFFFFFFFF);
        iv.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        iv.setBackground(Ui.press(0x00000000, 0x00000000, Ui.dp(this, 99)));
        iv.setContentDescription(what);
        return iv;
    }

    private void markIcon(boolean on) {
        markBtn.setImageResource(on ? R.drawable.ic_pl_star : R.drawable.ic_pl_star_o);
        markBtn.setColorFilter(on ? 0xFFF5C211 : 0xFFFFFFFF);
    }

    /** Controls and title in or out (landscape, while the video plays). */
    private void setUi(boolean on) {
        if (uiOn == on || stacked) return;
        uiOn = on;
        topBar.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        ctl.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        subsBox.setPadding(subsBox.getPaddingLeft(), 0, subsBox.getPaddingRight(), on ? Math.max(ctl.getHeight(), Ui.dp(this, 96)) + Ui.dp(this, 6) : Ui.dp(this, 28));
        if (on) lastTouch = System.currentTimeMillis();
    }

    /** What is switched on, shown at the top like the PC's pill: "🎧 primed · stops at lines · repeat 3". */
    private void statusUpdate() {
        String m = "normal".equals(pmode) ? "" : modeName().toLowerCase(Locale.ROOT).replace("subtitles only on i+1 lines", "i+1 subtitles").replace("subtitles only while paused", "subs when paused");
        String t = m + (loopUntil > 0 ? (m.isEmpty() ? "" : " · ") + "repeating" + (loopLeft > 0 ? " " + loopLeft : "") : "");
        if (!t.contentEquals(statusText.getText())) { statusText.setText(t); statusText.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE); }
    }

    /** The speeds: the usual ones (0,7 – 2×) big and first, the rest (down to 0,25×, up to 4×) smaller below, and a fine step. */
    private void speedMenu() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        android.app.Dialog[] d = new android.app.Dialog[1];
        float[] usual = {0.7f, 0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f};
        float[] other = {0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 2.25f, 2.5f, 2.75f, 3f, 3.5f, 4f};
        l.addView(menuHead("Usual speeds"));
        Flow f1 = new Flow(this, 8);
        for (float v : usual) f1.addView(speedChip(v, true, d));
        l.addView(f1);
        l.addView(menuHead("Slower and faster"));
        Flow f2 = new Flow(this, 6);
        for (float v : other) f2.addView(speedChip(v, false, d));
        l.addView(f2);
        l.addView(menuHead("In small steps"));
        LinearLayout r = Ui.row(this);
        r.addView(Ui.weight(Ui.button(this, "− 0,05", v -> { setSpeed(Math.max(0.25f, Math.round((speed - 0.05f) * 100) / 100f)); Ui.dismiss(d[0]); speedMenu(); }), 1));
        r.addView(Ui.weight(Ui.button(this, speedLabel(speed), v -> { setSpeed(1f); Ui.dismiss(d[0]); }), 1));
        r.addView(Ui.weight(Ui.button(this, "+ 0,05", v -> { setSpeed(Math.min(4f, Math.round((speed + 0.05f) * 100) / 100f)); Ui.dismiss(d[0]); speedMenu(); }), 1));
        l.addView(r);
        l.addView(Ui.hint(this, "Middle button: back to normal. Above 2× some phones lose the sound or keep it only partly."));
        d[0] = Ui.sheet(this, "Speed", Ui.scroll(this, l), false);
    }

    private TextView speedChip(float v, boolean big, android.app.Dialog[] d) {
        boolean on = Math.abs(v - speed) < 0.011f;
        TextView t = Ui.text(this, String.valueOf(v).replace('.', ',').replaceAll(",0$", "") + "×", big ? 17 : 14, on ? Ui.ON_ACC : big ? Ui.TEXT : Ui.DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, big ? 16 : 12), Ui.dp(this, big ? 11 : 8), Ui.dp(this, big ? 16 : 12), Ui.dp(this, big ? 11 : 8));
        t.setBackground(Ui.round(on ? Ui.ACCENT : Ui.mix(Ui.BG, Ui.TEXT, big ? 0.10f : 0.05f), Ui.dp(this, 99)));
        if (on) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setOnClickListener(x -> { setSpeed(v); Ui.dismiss(d[0]); });
        return t;
    }

    private void setSpeed(float v) {
        speed = Math.max(0.25f, Math.min(4f, v));
        app.prefs().edit().putFloat(lk("speed"), speed).apply();
        speedLbl.setText(speedLabel(speed));
        applySpeed();
    }

    /** A row in the menus: a tick (when ON), the name, a small hint at the right. */
    private LinearLayout menuRow(String name, String hint, boolean on, Runnable then, android.app.Dialog[] dlg) {
        LinearLayout r = Ui.row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        r.setBackground(Ui.press(0x00000000, 0x00000000, Ui.dp(this, Ui.RS)));
        TextView ck = Ui.text(this, on ? "✓" : "", 15, Ui.ACCENT);
        ck.setMinWidth(Ui.dp(this, 24));
        r.addView(ck);
        TextView t = Ui.text(this, name, 16, Ui.TEXT);
        if (on) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (hint != null && !hint.isEmpty()) r.addView(Ui.text(this, hint, 13, Ui.DIM));
        r.setOnClickListener(v -> { Ui.dismiss(dlg[0]); then.run(); });
        return r;
    }

    private TextView menuHead(String s) {
        TextView t = Ui.text(this, s.toUpperCase(Locale.ROOT), 11, Ui.DIM);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), 0, Ui.dp(this, 4));
        return t;
    }

    /** The menu of the PC player (≡): quick settings, subtitles, this line, lists, layout, speed. */
    private void mainMenu() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 12));
        android.app.Dialog[] d = new android.app.Dialog[1];
        boolean port = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT;
        l.addView(menuRow("Quick settings…", "", false, this::quick, d));
        l.addView(menuRow("How to use the lines…", modeName(), false, this::modeMenu, d));
        l.addView(menuRow("Subtitles…", subsOf(file).size() + " file" + (subsOf(file).size() == 1 ? "" : "s"), false, this::subsDialog, d));
        l.addView(menuRow(hideSub ? "Show the subtitles" : "Hide the subtitles", "", false, () -> { hideSub = !hideSub; shown = -2; adapter.notifyDataSetChanged(); }, d));
        l.addView(menuRow(hideTr ? "Show the translation" : "Hide the translation", "", false, () -> { hideTr = !hideTr; shown = -2; adapter.notifyDataSetChanged(); }, d));
        l.addView(menuRow("Speed…", speedLabel(speed), false, this::speedMenu, d));
        l.addView(menuHead("This line"));
        l.addView(menuRow("Previous line", "", false, () -> action("prev"), d));
        l.addView(menuRow("Say this line again", "", false, () -> action("repeat"), d));
        l.addView(menuRow("Next line", "", false, () -> action("next"), d));
        l.addView(menuRow("Mark the line for a card", "★", false, () -> action("mark"), d));
        l.addView(menuRow("Edit this line…", "", false, () -> editLine(Subs.around(main, video.getCurrentPosition() / 1000.0 - delay)), d));
        l.addView(menuRow("Copy the line", "", false, () -> copyLine(Subs.around(main, video.getCurrentPosition() / 1000.0 - delay)), d));
        l.addView(menuHead("Lists"));
        l.addView(menuRow("All lines", main.size() + "", false, this::linesDialog, d));
        l.addView(menuRow("Intro, credits, gaps, fast lines…", "", false, this::linesTools, d));
        l.addView(menuHead("Cards from many lines"));
        l.addView(menuRow("From the lines marked with ★…", "", false, () -> cardTool("marked"), d));
        l.addView(menuRow("From the unknown words…", "", false, () -> cardTool("words"), d));
        l.addView(menuRow("Lines with exactly one unknown word…", "", false, () -> cardTool("one"), d));
        l.addView(menuRow("Card tray", Tray.count() + " waiting", false, () -> Tray.show(this, null), d));
        l.addView(menuHead("Picture and text"));
        if (port) l.addView(menuRow("Video on top, the lines below", "", app.prefs().getBoolean("player_stack", true), () -> action("layout"), d));
        l.addView(menuRow("Subtitle timing…", delay == 0 ? "" : String.format(Locale.ROOT, "%+.1f s", delay), false, this::delayDialog, d));
        l.addView(menuRow("Text bigger / smaller", "", false, () -> action("size"), d));
        l.addView(menuRow("Turn the picture", "", false, () -> action("rotate"), d));
        l.addView(menuHead("Window"));
        if (android.os.Build.VERSION.SDK_INT >= 26) l.addView(menuRow("Small window on top of other apps", "", false, this::smallWindow, d));
        l.addView(menuRow("Save a picture of this moment", "", false, this::screenshot, d));
        l.addView(menuRow("Close the video", "", false, this::finish, d));
        d[0] = Ui.sheet(this, "Menu", Ui.scroll(this, l), true);
    }

    /** Quick settings (the PC's ⚙): the switches and steps you reach for while watching. */
    private void quick() {
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        android.app.Dialog[] d = new android.app.Dialog[1];
        l.addView(Ui.section(this, "Subtitles"));
        LinearLayout g = Ui.group(this);
        Ui.switchRow(g, "Subtitles", null, !hideSub, (b, on) -> { hideSub = !on; shown = -2; adapter.notifyDataSetChanged(); });
        Ui.switchRow(g, "Translation (2nd track)", null, !hideTr, (b, on) -> { hideTr = !on; shown = -2; adapter.notifyDataSetChanged(); });
        Ui.switchRow(g, "Word colours", "Unknown words coloured, tap a word for its card.", col.colours, (b, on) -> {
            col.colours = on; app.prefs().edit().putBoolean(app.lk("colours"), on).apply(); col.forget(); spanCache.clear(); shown = -2; adapter.notifyDataSetChanged(); });
        LinearLayout sz = Ui.row(this);
        sz.addView(Ui.small(this, "A−", v -> sizeStep(-1)));
        sz.addView(Ui.small(this, "A+", v -> sizeStep(1)));
        Ui.srow(g, "Size", null, sz, null);
        l.addView(g);
        l.addView(Ui.section(this, "Playback"));
        LinearLayout g2 = Ui.group(this);
        Ui.pickRow(g2, "Speed", speedLabel(speed), v -> { Ui.dismiss(d[0]); speedMenu(); });
        Ui.pickRow(g2, "Mode", modeName(), v -> { Ui.dismiss(d[0]); modeMenu(); });
        if (prime) {
            boolean unkOnly = "unk".equals(app.prefs().getString(lk("prime_when"), app.prefs().getString("prime_when", "all")));
            Ui.switchRow(g2, "Primed only on lines with a new word", "Off: every line is primed.", unkOnly, (b, on) -> {
                app.prefs().edit().putString(lk("prime_when"), on ? "unk" : "all").apply();
                if (on) qualify(Math.max(0, lastLine));
            });
        }
        int times = app.prefs().getInt(lk("loop_times"), app.prefs().getInt("loop_times", 1));
        String[] tn = {"1 time", "2 times", "3 times", "5 times", "until I go on"};
        int[] tv = {1, 2, 3, 5, 0};
        int ci = 0;
        for (int i = 0; i < tv.length; i++) if (tv[i] == times) ci = i;
        final int cur = ci;
        Ui.pickRow(g2, "Say a line again", tn[cur], v -> { Ui.dismiss(d[0]); Ui.choose(this, "Say a line again", tn, cur, i -> app.prefs().edit().putInt(lk("loop_times"), tv[i]).apply()); });
        l.addView(g2);
        if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            l.addView(Ui.section(this, "Layout"));
            LinearLayout g3 = Ui.group(this);
            Ui.switchRow(g3, "Video on top, the lines below", "Off: the picture fills the screen.", app.prefs().getBoolean("player_stack", true), (b, on) -> { app.prefs().edit().putBoolean("player_stack", on).apply(); arrange(); });
            l.addView(g3);
        }
        d[0] = Ui.sheet(this, "Quick settings", Ui.scroll(this, l), false);
    }

    private void sizeStep(int dir) {
        if (stacked) { listSp = Math.max(13, Math.min(28, listSp + dir * 2)); adapter.notifyDataSetChanged(); return; }
        float s = subText.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
        s = Math.max(14, Math.min(34, s + dir * 3));
        subText.setTextSize(s);
        trText.setTextSize(Math.max(12, s - 6));
    }

    static String speedLabel(float s) { return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "×"; }

    private void applySpeed() {
        if (mpl == null || android.os.Build.VERSION.SDK_INT < 23) return;
        try {
            boolean playing = video.isPlaying();
            mpl.setPlaybackParams(mpl.getPlaybackParams().setSpeed(effSpeed > 0 ? effSpeed : speed));
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
        android.app.AlertDialog.Builder b = Ui.dialog(this).setTitle("Subtitle timing").setView(box).setPositiveButton("OK", null).setNeutralButton("Reset", (d, w) -> setDelay(0));
        if (delay != 0) b.setNegativeButton("Write it into the file", (d, w) -> {          // the PC's "write the delay you set": the file itself is then on time
            main = Subs.shift(main, delay);
            double was = delay;
            setDelay(0);
            writeSubs();
            Ui.toast(this, String.format(Locale.ROOT, "Every line moved %+.1f s in the file", was));
        });
        b.show();
    }

    private void linesDialog() {
        if (main.isEmpty()) { Ui.toast(this, "No subtitles to list"); return; }
        String[] items = new String[main.size()];
        for (int i = 0; i < items.length; i++) items[i] = Ui.time(main.get(i).start) + "  " + main.get(i).text.replace('\n', ' ');
        int now = Math.max(0, Subs.around(main, video.getCurrentPosition() / 1000.0 - delay));
        android.app.AlertDialog dlg = Ui.dialog(this).setTitle("All lines (" + items.length + ")").setItems(items, (d, i) -> jump(i))
                .setNeutralButton("Unknown words", (d, w) -> unknownWords()).create();
        dlg.getListView().setFastScrollEnabled(true);
        dlg.getListView().setOnItemLongClickListener((av, v, pos, id) -> { dlg.dismiss(); editLine(pos); return true; });      // hold a line: edit it
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
                Ui.choose(this, found.size() + " unknown words, most frequent first", items, -1, i -> {
                    int line = list.get(i).getValue()[1];
                    String w = list.get(i).getKey();
                    video.pause();
                    tapped(w, 0, main.get(line).text, main.get(line));
                });
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
        if (subs.isEmpty()) { Ui.alert(this, "Subtitles", "There is no subtitle file (.srt, .vtt, .ass) with this video. Add the subtitle files with the video (Videos → Add a video, choose both), copy it from the PC with its subtitles, or long-press the video in the Videos tab → “Find subtitles on the internet”."); return; }
        String[] names = new String[subs.size()];
        for (int i = 0; i < names.length; i++) names[i] = subs.get(i).getName();
        Ui.choose(this, "Subtitles to learn from", names, -1, i -> {
            app.prefs().edit().putString("sub_" + file.getName(), names[i]).apply();
            String[] two = new String[names.length + 1];
            two[0] = "(no translation)";
            System.arraycopy(names, 0, two, 1, names.length);
            Ui.dialog(this).setTitle("Translation line (under it)").setItems(two, (d2, j) -> {
                app.prefs().edit().putString("sub2_" + file.getName(), j == 0 ? "-" : two[j]).apply();
                loadSubs();
                shown = -2;
            }).show();
        });
    }

    private String[] queue;                                  // the videos to play after this one (a series, a playlist)

    /** At the end of a video: the next of its series or playlist starts. */
    private void playNextInQueue() {
        if (queue == null || queue.length == 0 || isFinishing()) return;
        Intent i = new Intent(this, Player.class);
        i.putExtra("path", queue[0]);
        if (queue.length > 1) i.putExtra("queue", java.util.Arrays.copyOfRange(queue, 1, queue.length));
        startActivity(i);
        finish();
    }

    private boolean tickFailed;

    // ---- tools that work on many lines at once: intro / credits, fast lines, gaps, the picture under the finger on the timeline ----

    private float effSpeed = -1;
    private boolean introDone, outDone;
    private long gapCool;
    private ImageView prev;
    private volatile int scrubWant = -1;
    private android.media.MediaMetadataRetriever scrubR;
    private final java.util.concurrent.ExecutorService scrubEx = java.util.concurrent.Executors.newSingleThreadExecutor();

    private String showKey() { String sh = epOf(file).show; return sh.isEmpty() ? "" : com.batchmine.pocket.core.Shows.key(sh); }

    private void finishVideo() {
        app.prefs().edit().putBoolean("vdone_" + file.getName(), true).putFloat("vpos_" + file.getName(), 0).putLong("vpost_" + file.getName(), System.currentTimeMillis()).apply();
        playNextInQueue();
    }

    private void manyLines(int i, double t, int pos) {
        android.content.SharedPreferences pr = app.prefs();
        String sk = showKey();
        if (!sk.isEmpty() && !dragging) {
            float in = pr.getFloat("skipin_" + sk, 0), out = pr.getFloat("skipout_" + sk, 0);
            if (!introDone) { introDone = true; if (in > 0 && pos < in * 1000 - 500) { video.seekTo((int) (in * 1000)); shown = -2; return; } }
            int dur = video.getDuration();
            if (!outDone && out > 0 && dur > 0 && pos >= dur - out * 1000 && video.isPlaying()) {
                outDone = true;
                if (queue != null && queue.length > 0) finishVideo(); else video.seekTo(Math.max(0, dur - 1000));
                return;
            }
        }
        float slow = pr.getFloat(lk("slowfast"), 0), want = speed;
        if (slow > 0 && i >= 0 && loopUntil < 0) {
            Subs.Cue c = main.get(i);
            double len = Math.max(0.3, c.end - c.start);
            if (c.text.replaceAll("<[^>]*>|\\s+", "").length() / len > slow) want = Math.max(0.25f, speed * pr.getFloat(lk("slowfactor"), 0.8f));
        }
        if (Math.abs(want - effSpeed) > 0.001f && !(effSpeed < 0 && want == speed)) { effSpeed = want; applySpeed(); }
        if (pr.getBoolean(lk("gapskip"), false) && i < 0 && !main.isEmpty() && loopUntil < 0 && !prime && video.isPlaying() && System.currentTimeMillis() > gapCool) {
            double tt = t - delay, gap = pr.getFloat(lk("gapmin"), 2f);
            for (int n = Math.max(0, lastLine - 1); n < main.size(); n++) {
                double st = main.get(n).start;
                if (st > tt) {
                    if (st - tt > gap) { gapCool = System.currentTimeMillis() + 1500; video.seekTo((int) ((st + delay - 0.4) * 1000)); }
                    break;
                }
            }
        }
    }

    private void scrub(int ms) {
        scrubWant = ms;
        if (ms < 0) { prev.setVisibility(View.GONE); return; }
        int w = Math.max(1, tl.getWidth()), dur = Math.max(1, video.getDuration());
        float x = (float) ms / dur * w - prev.getLayoutParams().width / 2f;
        prev.setTranslationX(Math.max(0, Math.min(w - prev.getLayoutParams().width, x)));
        ((FrameLayout.LayoutParams) prev.getLayoutParams()).bottomMargin = ctl.getHeight() + Ui.dp(this, 8);
        prev.requestLayout();
        prev.setVisibility(View.VISIBLE);
        scrubEx.execute(() -> {
            if (scrubWant != ms) return;
            try {
                if (scrubR == null) { scrubR = new android.media.MediaMetadataRetriever(); scrubR.setDataSource(this, videoUri(file)); }
                android.graphics.Bitmap b = scrubR.getFrameAtTime(ms * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b == null) return;
                if (b.getWidth() > 352) b = android.graphics.Bitmap.createScaledBitmap(b, 352, Math.max(1, Math.round(b.getHeight() * 352f / b.getWidth())), true);
                android.graphics.Bitmap fb = b;
                if (scrubWant == ms) runOnUiThread(() -> prev.setImageBitmap(fb));
            } catch (RuntimeException e) { /* a video the phone cannot cut pictures from: no preview */ }
        });
    }

    private TextView toolChip(String label, boolean on, Runnable then) {
        TextView t = Ui.text(this, label, 15, on ? Ui.ON_ACC : Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 9), Ui.dp(this, 14), Ui.dp(this, 9));
        t.setBackground(Ui.round(on ? Ui.ACCENT : Ui.mix(Ui.BG, Ui.TEXT, 0.10f), Ui.dp(this, 99)));
        if (on) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setOnClickListener(x -> then.run());
        return t;
    }

    /** A line you replay was too fast: its characters per second are kept (the last 40, per language) so the limit for "Slow down fast lines" can be suggested. */
    private void learnHard(Subs.Cue c) {
        double len = Math.max(0.3, c.end - c.start);
        double cps = c.text.replaceAll("<[^>]*>|\\s+", "").length() / len;
        if (cps < 4 || cps > 40) return;
        String[] old = app.prefs().getString(lk("hard_cps"), "").split(",");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, old.length - 39);
        for (int k = from; k < old.length; k++) if (!old[k].isEmpty()) sb.append(old[k]).append(',');
        sb.append(String.format(Locale.ROOT, "%.1f", cps));
        app.prefs().edit().putString(lk("hard_cps"), sb.toString()).apply();
    }

    /** The limit suggested by the replays: a little below the typical speed of the lines you replayed (null: too few replays yet). */
    private Float suggestedLimit() {
        String[] a = app.prefs().getString(lk("hard_cps"), "").split(",");
        java.util.List<Float> v = new ArrayList<>();
        for (String x : a) { try { v.add(Float.parseFloat(x)); } catch (NumberFormatException ignored) { /* empty */ } }
        if (v.size() < 5) return null;
        java.util.Collections.sort(v);
        float q = v.get(v.size() / 4);                                  // the slower quarter: most lines you replay are at least this fast
        return Math.max(8f, Math.round(q - 1f));
    }

    /** Compares this episode's subtitles with those of other episodes of the series: the shared song / recap at the start is the intro, a shared stretch at the end the credits. */
    private void autoIntro() {
        String sk = showKey();
        if (sk.isEmpty()) { Ui.toast(this, "This video is not named like an episode of a series"); return; }
        if (main.isEmpty()) { Ui.toast(this, "This video has no subtitles to compare"); return; }
        final java.util.List<Subs.Cue> mine = new ArrayList<>(main);
        final double dur = video.getDuration() / 1000.0;
        Ui.toast(this, "Comparing with the other episodes…");
        app.bg(() -> {
            File[] all = app.videoDir().listFiles();
            int tried = 0;
            if (all != null) for (File v : all) {
                if (v.equals(file) || !isVideo(v.getName()) || !showKey().equals(com.batchmine.pocket.core.Shows.key(epOf(v).show))) continue;
                String stem = v.getName().replaceAll("\\.[^.]+$", "");
                for (File sf : all) {
                    String n = sf.getName().toLowerCase(Locale.ROOT);
                    if (!(n.endsWith(".srt") || n.endsWith(".vtt") || n.endsWith(".ass") || n.endsWith(".ssa")) || !sf.getName().startsWith(stem)) continue;
                    java.util.List<Subs.Cue> other;
                    try { other = Subs.parse(Subs.decode(Files.readAllBytes(sf.toPath()))); } catch (IOException | RuntimeException ex) { continue; }
                    if (other.isEmpty()) continue;
                    tried++;
                    double[] in = com.batchmine.pocket.core.IntroFinder.intro(other, mine);
                    double[] cr = com.batchmine.pocket.core.IntroFinder.credits(other, other.get(other.size() - 1).end + 90, mine, dur);
                    if (in == null && cr == null) break;                 // nothing shared with this one: try the next episode
                    android.content.SharedPreferences.Editor e = app.prefs().edit();
                    StringBuilder msg = new StringBuilder();
                    if (in != null) { e.putFloat("skipin_" + sk, (float) (in[1] + 0.5)); msg.append("Intro ").append(Ui.time(in[0])).append(" – ").append(Ui.time(in[1])); }
                    if (cr != null && dur - cr[0] > 20) { e.putFloat("skipout_" + sk, (float) (dur - cr[0])); msg.append(msg.length() > 0 ? "\n" : "").append("Credits from ").append(Ui.time(cr[0])); }
                    e.apply();
                    String m = msg + "\n\nfound by comparing with " + com.batchmine.pocket.core.Shows.pretty(videoName(v)) + ". They are skipped in every episode from now on.";
                    app.post(() -> Ui.alert(this, "Found", m));
                    return;
                }
            }
            int t = tried;
            app.post(() -> Ui.alert(this, "Nothing found", t == 0 ? "No other episode of this series with subtitles is on the phone to compare with." : "The subtitles of " + t + " other episode(s) share no song or recap lines with this one. Mark the intro by hand instead."));
        });
    }

    private void linesTools() {
        android.content.SharedPreferences pr = app.prefs();
        String sk = showKey();
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 16));
        android.app.Dialog[] d = new android.app.Dialog[1];
        double now = video.getCurrentPosition() / 1000.0;
        if (!sk.isEmpty()) {
            float in = pr.getFloat("skipin_" + sk, 0), out = pr.getFloat("skipout_" + sk, 0);
            l.addView(menuHead("Every episode of " + epOf(file).show));
            l.addView(menuRow("The intro ends here (" + Ui.time(now) + ")", in > 0 ? "now at " + Ui.time(in) : "", false,
                    () -> { pr.edit().putFloat("skipin_" + sk, (float) now).apply(); Ui.toast(this, "The intro is skipped from now on"); }, d));
            l.addView(menuRow("The credits start here", out > 0 ? Ui.time(out) + " before the end" : "", false, () -> {
                double left = (video.getDuration() - video.getCurrentPosition()) / 1000.0;
                if (left < 3) Ui.toast(this, "Go to where the credits begin first");
                else { pr.edit().putFloat("skipout_" + sk, (float) left).apply(); Ui.toast(this, "The credits are skipped from now on"); }
            }, d));
            l.addView(menuRow("Find them by comparing with another episode", "", false, this::autoIntro, d));
            if (in > 0 || out > 0) l.addView(menuRow("Forget intro and credits", "", false, () -> pr.edit().remove("skipin_" + sk).remove("skipout_" + sk).apply(), d));
        } else l.addView(Ui.hint(this, "Intro and credits skipping works for videos named like a series (Show S01E02)."));
        l.addView(menuHead("Slow down fast lines"));
        l.addView(Ui.hint(this, "Lines with more characters per second than this play slower. Counted per language."));
        Float sug = suggestedLimit();
        int nHard = app.prefs().getString(lk("hard_cps"), "").isEmpty() ? 0 : app.prefs().getString(lk("hard_cps"), "").split(",").length;
        if (sug != null) l.addView(menuRow("Learned from your replays: " + (int) (float) sug + " per second", "use it", false,
                () -> { app.prefs().edit().putFloat(lk("slowfast"), sug).apply(); effSpeed = -1; Ui.toast(this, "Lines faster than " + (int) (float) sug + "/s are slowed"); }, d));
        else l.addView(Ui.hint(this, "Replay lines that were too fast (“Say this line again”): after five of them the app suggests a limit (" + nHard + " so far)."));
        Flow f = new Flow(this, 8);
        float cur = pr.getFloat(lk("slowfast"), 0);
        float[] cps = {0, 13, 16, 19, 23};
        for (float c : cps) f.addView(toolChip(c == 0 ? "Off" : (int) c + "/s", c == cur, () -> { pr.edit().putFloat(lk("slowfast"), c).apply(); Ui.dismiss(d[0]); linesTools(); }));
        l.addView(f);
        if (cur > 0) {
            l.addView(menuHead("…to this much of your speed"));
            Flow g = new Flow(this, 8);
            float fc = pr.getFloat(lk("slowfactor"), 0.8f);
            for (float x : new float[]{0.9f, 0.8f, 0.7f, 0.6f}) g.addView(toolChip(String.valueOf(x).replace('.', ','), Math.abs(x - fc) < 0.01f, () -> { pr.edit().putFloat(lk("slowfactor"), x).apply(); effSpeed = -1; Ui.dismiss(d[0]); linesTools(); }));
            l.addView(g);
        }
        l.addView(menuHead("Gaps between lines"));
        LinearLayout grp = Ui.group(this);
        Ui.switchRow(grp, "Skip the silence between lines", "Jumps to just before the next line.", pr.getBoolean(lk("gapskip"), false),
                (b, on) -> pr.edit().putBoolean(lk("gapskip"), on).apply());
        l.addView(grp);
        l.addView(Ui.hint(this, "Only gaps longer than:"));
        Flow gg = new Flow(this, 8);
        float gm = pr.getFloat(lk("gapmin"), 2f);
        for (float x : new float[]{1.5f, 2f, 3f, 5f, 8f}) gg.addView(toolChip(String.valueOf(x).replace('.', ',').replace(",0", "") + " s", Math.abs(x - gm) < 0.01f, () -> { pr.edit().putFloat(lk("gapmin"), x).apply(); Ui.dismiss(d[0]); linesTools(); }));
        l.addView(gg);
        l.addView(Ui.hint(this, "Drag along the timeline to see the picture under your finger."));
        d[0] = Ui.sheet(this, "Many lines", Ui.scroll(this, l), true);
    }

    private void tick() {
        if (isFinishing() || video == null) return;
        try {
            if (sheetUp && video.isPlaying()) video.pause();          // whatever started it (a repeat, primed listening, the end of a seek)
            int pos = video.getCurrentPosition();
            double t = pos / 1000.0;
            playState(video.isPlaying(), pos);
            tl.set(pos, video.getDuration());
            statusUpdate();
            if (!stacked) {                                          // the controls go when the video plays and nobody touches the screen (the PC hides them too)
                if (!video.isPlaying() || "read".equals(primePhase)) { if (!uiOn) setUi(true); }
                else if (uiOn && System.currentTimeMillis() - lastTouch > 3500) setUi(false);
            }
            if (playIcon != null) playIcon.setImageResource(video.isPlaying() ? R.drawable.ic_pl_pause : R.drawable.ic_pl_play);
            if (loopUntil > 0 && t >= loopUntil) {
                if (loopLeft == 0 || --loopLeft > 0) video.seekTo((int) (Math.max(0, loopFrom) * 1000));
                else loopUntil = -1;                           // played as often as asked: on with the film
            }
            int i = Subs.at(main, t - delay);
            manyLines(i, t, pos);
            if (prime && loopUntil < 0) primeStep(i, t);
            if (i != shown) { shown = i; showLine(i); }
            if (needsCounts()) qualify(Math.max(0, i));
            if (("pi1".equals(pmode) || "punk".equals(pmode)) && i >= 0 && i != pausedAt && t - delay >= main.get(i).end - 0.08 && video.isPlaying()) {
                Integer n = unkN.get(i);
                if (n != null && ("punk".equals(pmode) ? n > 0 : n == 1)) { pausedAt = i; video.pause(); }
            }
            boolean pl = video.isPlaying();
            if (pl != wasPlaying) { wasPlaying = pl; if ("paused".equals(pmode)) { shown = -2; if (stacked) adapter.notifyDataSetChanged(); } }
            if (i >= 0 && i != pausedAt && t - delay < main.get(i).end - 0.3) pausedAt = -2;
        } catch (RuntimeException e) {
            if (!tickFailed) { tickFailed = true; app.report("Player: showing a line", e); Ui.toast(this, "Something went wrong: " + e); }
        }
        h.postDelayed(this::tick, 120);
    }

    private void showLine(int i) {
        if (i >= 0) lastLine = i;
        if (stacked) {
            if (i >= 0) markIcon(Marks.isMarked(file.getName(), main.get(i).start));
            followLine(i);
            return;
        }
        if (i < 0) { subText.setVisibility(View.GONE); trText.setVisibility(View.GONE); return; }
        Subs.Cue c = main.get(i);
        boolean modeHides = hideSub || !modeShows(i);
        if (modeHides) { subText.setVisibility(View.GONE); }
        markIcon(Marks.isMarked(file.getName(), c.start));
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
            app.post(() -> { if (shown == i && !hideSub && modeShows(i)) { subText.setText(s); subText.setVisibility(View.VISIBLE); } });
        });
        StringBuilder tr = new StringBuilder();
        for (Subs.Cue x : second) if (x.start < c.end && x.end > c.start) { if (tr.length() > 0) tr.append('\n'); tr.append(x.text); }
        trText.setText(tr);
        trText.setVisibility(tr.length() > 0 && !hideTr ? View.VISIBLE : View.GONE);
    }

    /** The key of a player setting for the language being learnt: each language remembers its own speed, mode, repeat count ... (the general value is the start). */
    static String lk(String key) { return key + "_" + App.get().lang(); }

    private File mainFile;                                   // the subtitle file the lines came from (edits are written back to it)
    private boolean sheetUp;                                 // the word sheet is open: the video stays paused (nothing restarts it)

    private void tapped(String word, int at, String text, Subs.Cue c) {
        video.pause();
        sheetUp = true;
        primePhase = "";                                     // primed listening must not play on after its reading time
        h.removeCallbacksAndMessages(null);
        h.postDelayed(this::tick, 120);
        Marks.add(file.getName(), c.start, c.text, false);                  // a line you looked a word up in
        Cards.Request r = requestFor(c, text);
        WordSheet.show(this, lang, word, r, () -> { col.forget(); spanCache.clear(); shown = -2; }, () -> { sheetUp = false; immersive(); });
    }


    // ---------------------------------------------------------------- editing the lines (the PC player's line editor)
    private void copyLine(int i) {
        if (i < 0 || i >= main.size()) { Ui.toast(this, "No line here"); return; }
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("line", main.get(i).text.replace('\n', ' ')));
        Ui.toast(this, "Line copied");
    }

    /** Writes the lines back: an .srt is replaced (the first time its old self is kept as .bak), any other format becomes a new .srt that is then the one in use. */
    private boolean writeSubs() {
        try {
            if (mainFile == null) return false;
            File target = mainFile;
            if (!mainFile.getName().toLowerCase(Locale.ROOT).endsWith(".srt")) {
                String n = mainFile.getName();
                target = new File(mainFile.getParentFile(), n.substring(0, n.lastIndexOf('.')) + ".edited.srt");
                app.prefs().edit().putString("sub_" + file.getName(), target.getName()).apply();
            } else {
                File bak = new File(mainFile.getPath() + ".bak");
                if (!bak.exists()) Files.copy(mainFile.toPath(), bak.toPath());
            }
            Files.write(target.toPath(), Subs.toSrt(main).getBytes(StandardCharsets.UTF_8));
            mainFile = target;
            spanCache.clear();
            shown = -2;
            if (adapter != null) adapter.notifyDataSetChanged();
            return true;
        } catch (IOException | RuntimeException e) {
            app.report("Could not save the subtitles", e);
            Ui.toast(this, "Could not save the subtitles: " + e.getMessage());
            return false;
        }
    }

    private void editLine(int i) {
        if (i < 0 || i >= main.size()) { Ui.toast(this, "No line here"); return; }
        final Subs.Cue c = main.get(i);
        video.pause();
        LinearLayout l = Ui.col(this);
        l.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 16));
        double[] t = {c.start, c.end};
        TextView when = Ui.text(this, "", 14, Ui.DIM);
        Runnable draw = () -> when.setText(Ui.time(t[0]) + "  →  " + Ui.time(t[1]) + String.format(Locale.ROOT, "   (%.1f s)", t[1] - t[0]));
        draw.run();
        l.addView(when);
        android.widget.EditText e = Ui.input(this, c.text, "the line");
        e.setSingleLine(false);
        e.setHorizontallyScrolling(false);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setMinLines(3);
        e.setMaxLines(9);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setTextSize(17);
        e.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ep.topMargin = Ui.dp(this, 8);
        l.addView(e, ep);
        l.addView(Ui.hint(this, "Put the cursor where a line should be cut, then “Cut here”."));
        l.addView(menuHead("Time"));
        LinearLayout r = Ui.row(this);
        String[] names = {"Starts", "Ends"};
        for (int k = 0; k < 2; k++) {
            final int kk = k;
            LinearLayout col = Ui.col(this);
            col.addView(Ui.text(this, names[k], 12, Ui.DIM));
            LinearLayout b = Ui.row(this);
            b.addView(Ui.weight(Ui.small(this, "− 0,1", v -> { t[kk] = Math.max(0, Math.round((t[kk] - 0.1) * 10) / 10.0); draw.run(); }), 1));
            b.addView(Ui.weight(Ui.small(this, "+ 0,1", v -> { t[kk] = Math.round((t[kk] + 0.1) * 10) / 10.0; draw.run(); }), 1));
            col.addView(b);
            r.addView(Ui.weight(col, 1));
        }
        l.addView(r);
        l.addView(Ui.hint(this, "The sound of the line is played again from here: close this and press ⟲."));
        android.app.Dialog[] d = new android.app.Dialog[1];
        // what the edited line is, as a list of lines (the text may be a different one now)
        java.util.function.Supplier<List<Subs.Cue>> edited = () -> {
            List<Subs.Cue> out = new ArrayList<>(main);
            out.set(i, new Subs.Cue(t[0], Math.max(t[1], t[0] + 0.2), e.getText().toString().trim()));
            return out;
        };
        java.util.function.Consumer<List<Subs.Cue>> apply = list -> { main = list; if (writeSubs()) Ui.toast(this, "Saved"); Ui.dismiss(d[0]); };
        LinearLayout b1 = Ui.row(this), b2 = Ui.row(this);
        Button save = Ui.button(this, "Save", v -> apply.accept(edited.get().get(i).text.isEmpty() ? deleteAt(edited.get(), i) : edited.get()));
        b1.addView(Ui.weight(save, 1.4f));
        b1.addView(Ui.weight(Ui.button(this, "Cut here", v -> {
            List<Subs.Cue> cur = edited.get();
            List<Subs.Cue> sp = Subs.split(cur, i, e.getSelectionStart());
            if (sp == cur) { Ui.toast(this, "Put the cursor inside the text, between two words"); return; }
            apply.accept(sp);
        }), 1));
        b2.addView(Ui.weight(Ui.button(this, "Join with the next", v -> {
            if (i + 1 >= main.size()) { Ui.toast(this, "This is the last line"); return; }
            apply.accept(Subs.mergeNext(edited.get(), i));
        }), 1));
        b2.addView(Ui.weight(Ui.button(this, "Copy", v -> { copyLine(i); }), 1));
        b2.addView(Ui.weight(Ui.button(this, "Delete", v -> apply.accept(deleteAt(new ArrayList<>(main), i))), 1));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(this, 10);
        l.addView(b1, bp);
        l.addView(b2, bp);
        d[0] = Ui.sheet(this, "Line " + (i + 1) + " of " + main.size(), Ui.scroll(this, l), false);
    }

    private static List<Subs.Cue> deleteAt(List<Subs.Cue> list, int i) { List<Subs.Cue> o = new ArrayList<>(list); o.remove(i); return o; }

    // ---------------------------------------------------------------- cards from many lines (the PC's "Make cards" lists)
    private static final class Cand {
        final String word; final int line; int count = 1;
        Cand(String word, int line) { this.word = word; this.line = line; }
    }

    /** MODE: "marked" = the unknown words of the lines you marked ★, "words" = of a set of lines you choose, "one" = lines with exactly one unknown word. */
    private void cardTool(String mode) {
        if (main.isEmpty()) { Ui.toast(this, "No subtitles"); return; }
        if (mode.equals("words")) {
            int now = Math.max(0, Subs.around(main, video.getCurrentPosition() / 1000.0 - delay));
            Ui.choose(this, "Which lines?", new String[]{"All " + main.size() + " lines", "The next 30 lines from here", "Only the ★ marked lines"}, -1, k -> {
                List<Integer> idx = new ArrayList<>();
                if (k == 2) idx = markedLines();
                else for (int i = k == 0 ? 0 : now; i < main.size() && (k == 0 || i < now + 30); i++) idx.add(i);
                findCandidates("words", idx);
            });
            return;
        }
        if (mode.equals("marked")) {
            List<Integer> idx = markedLines();
            if (idx.isEmpty()) { Ui.alert(this, "No marked lines", "Mark a line with ★ while you watch, then come back here."); return; }
            findCandidates("words", idx);
            return;
        }
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < main.size(); i++) all.add(i);
        findCandidates("one", all);
    }

    private List<Integer> markedLines() {
        List<Integer> idx = new ArrayList<>();
        for (Marks.Line ln : Marks.list(file.getName())) {
            if (!ln.marked()) continue;
            int i = Subs.around(main, ln.start);
            if (i >= 0 && !idx.contains(i)) idx.add(i);
        }
        java.util.Collections.sort(idx);
        return idx;
    }

    private void findCandidates(String mode, List<Integer> lines) {
        Ui.toast(this, "Looking for the unknown words…");
        app.bg(() -> {
            java.util.Map<String, Cand> words = new java.util.LinkedHashMap<>();
            List<Cand> ones = new ArrayList<>();
            java.util.Map<String, Boolean> state = new java.util.HashMap<>();
            for (int i : lines) {
                String text = main.get(i).text;
                java.util.LinkedHashSet<String> unk = new java.util.LinkedHashSet<>();
                for (int[] w : Subs.words(text)) {
                    String word = text.substring(w[0], w[1]).toLowerCase(Locale.ROOT);
                    if (word.length() < 2) continue;
                    Boolean u = state.get(word);
                    if (u == null) { u = col.state(word).equals("u"); state.put(word, u); }
                    if (u) unk.add(word);
                }
                for (String w : unk) { Cand c = words.get(w); if (c == null) words.put(w, new Cand(w, i)); else c.count++; }
                if (mode.equals("one") && unk.size() == 1) ones.add(new Cand(unk.iterator().next(), i));
            }
            List<Cand> list = new ArrayList<>(mode.equals("one") ? ones : words.values());
            if (!mode.equals("one")) list.sort((a, b) -> b.count != a.count ? Integer.compare(b.count, a.count) : Integer.compare(a.line, b.line));
            final List<Cand> shown = list.size() > 300 ? new ArrayList<>(list.subList(0, 300)) : list;
            app.post(() -> {
                if (shown.isEmpty()) { Ui.alert(this, "Nothing to make", mode.equals("one") ? "No line has exactly one unknown word." : "No unknown words in these lines."); return; }
                cardPicker(mode.equals("one") ? shown.size() + " lines with one unknown word" : shown.size() + " unknown words", shown, mode.equals("one"));
            });
        });
    }

    private void cardPicker(String title, List<Cand> cands, boolean showLine) {
        LinearLayout box = Ui.col(this);
        boolean[] on = new boolean[cands.size()];
        TextView[] marks = new TextView[cands.size()];
        Button go = Ui.button(this, "", null);
        Runnable count = () -> { int n = 0; for (boolean b : on) if (b) n++; go.setText(n == 0 ? "Choose the words" : "Make " + n + " card" + (n == 1 ? "" : "s")); go.setEnabled(n > 0); };
        LinearLayout top = Ui.row(this);
        top.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 4));
        top.addView(Ui.small(this, "All", v -> { for (int i = 0; i < on.length; i++) { on[i] = true; marks[i].setText("☑"); } count.run(); }));
        top.addView(Ui.small(this, "None", v -> { for (int i = 0; i < on.length; i++) { on[i] = false; marks[i].setText("☐"); } count.run(); }));
        top.addView(Ui.small(this, "Top 10", v -> { for (int i = 0; i < on.length; i++) { on[i] = i < 10; marks[i].setText(on[i] ? "☑" : "☐"); } count.run(); }));
        box.addView(top);
        LinearLayout list = Ui.col(this);
        list.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 6));
        for (int k = 0; k < cands.size(); k++) {
            final int kk = k;
            Cand c = cands.get(k);
            LinearLayout r = Ui.row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(Ui.dp(this, 10), Ui.dp(this, 9), Ui.dp(this, 10), Ui.dp(this, 9));
            r.setBackground(Ui.press(0x00000000, 0x00000000, Ui.dp(this, Ui.RS)));
            TextView ck = Ui.text(this, "☐", 20, Ui.ACCENT);
            ck.setMinWidth(Ui.dp(this, 32));
            marks[k] = ck;
            r.addView(ck);
            LinearLayout col2 = Ui.col(this);
            TextView w = Ui.text(this, c.word + (c.count > 1 ? "   ×" + c.count : ""), 17, Ui.TEXT);
            w.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            col2.addView(w);
            String ctx = main.get(c.line).text.replace('\n', ' ');
            TextView sub = Ui.text(this, Ui.time(main.get(c.line).start) + "  " + (ctx.length() > 90 ? ctx.substring(0, 90) + "…" : ctx), 12, Ui.DIM);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col2.addView(sub);
            r.addView(col2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            r.setOnClickListener(v -> { on[kk] = !on[kk]; ck.setText(on[kk] ? "☑" : "☐"); count.run(); });
            r.setOnLongClickListener(v -> { video.pause(); tapped(c.word, 0, main.get(c.line).text, main.get(c.line)); return true; });      // hold: look it up
            list.addView(r);
        }
        box.addView(Ui.scroll(this, list), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout foot = Ui.col(this);
        foot.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 12));
        foot.addView(Ui.hint(this, "Tap to choose. Hold a word to look it up first. Each card gets its sentence, the translation, the picture, the sound and the dictionaries' meanings."));
        foot.addView(go);
        box.addView(foot);
        count.run();
        android.app.Dialog[] d = new android.app.Dialog[1];
        go.setOnClickListener(v -> {
            List<Cand> chosen = new ArrayList<>();
            for (int i = 0; i < on.length; i++) if (on[i]) chosen.add(cands.get(i));
            Ui.dismiss(d[0]);
            makeCards(chosen, 0);
        });
        d[0] = Ui.sheet(this, title, box, true);
    }

    /** One card after the other (each is made on its own, like a tap on "Make card"). */
    private void makeCards(List<Cand> list, int k) {
        if (k >= list.size()) { Ui.toast(this, list.size() + " card" + (list.size() == 1 ? "" : "s") + " done"); return; }
        Cand c = list.get(k);
        if (k == 0) Ui.toast(this, "Making " + list.size() + " card" + (list.size() == 1 ? "" : "s") + "…");
        app.bg(() -> {
            com.batchmine.pocket.core.Dictionaries.Result res = null;
            try { res = app.dicts.lookup(c.word, lang); } catch (RuntimeException ignored) { }
            final com.batchmine.pocket.core.Dictionaries.Result rr = res;
            app.post(() -> {
                Subs.Cue cue = main.get(c.line);
                Cards.Request r = requestFor(cue, cue.text);
                r.surface = c.word;
                r.word = rr == null ? c.word : rr.lemma();
                if (rr != null && rr.found()) {
                    r.glosses = rr.hits.get(0).glosses;
                    r.defHtml = com.batchmine.pocket.core.Dictionaries.allMeanings(rr, null);
                    r.richBy = com.batchmine.pocket.core.Dictionaries.meaningsByLabel(rr, null, Cards.dictLabels(lang));
                    if (rr.freq > 0) r.freq = String.valueOf(rr.freq);
                    r.ipa = rr.ipa.isEmpty() ? "" : rr.ipa.values().iterator().next();
                }
                Cards.make(this, r, () -> makeCards(list, k + 1));
            });
        });
    }

    // ---------------------------------------------------------------- the window
    private boolean inPip;

    @android.annotation.SuppressLint("NewApi")
    private void smallWindow() {
        try {
            android.app.PictureInPictureParams p = new android.app.PictureInPictureParams.Builder().setAspectRatio(new android.util.Rational(16, 9)).build();
            enterPictureInPictureMode(p);
        } catch (RuntimeException e) { Ui.toast(this, "This phone cannot show the video in a small window"); }
    }

    @Override public void onPictureInPictureModeChanged(boolean in, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(in, cfg);
        inPip = in;
        if (in) { topBar.setVisibility(View.GONE); ctl.setVisibility(View.GONE); statusText.setVisibility(View.GONE); if (listView != null) listView.setVisibility(View.GONE); }
        else { topBar.setVisibility(View.VISIBLE); ctl.setVisibility(View.VISIBLE); uiOn = true; arrange(); }
    }

    /** The picture at this moment into Pictures/Glossa. */
    private void screenshot() {
        double t = video.getCurrentPosition() / 1000.0;
        Ui.toast(this, "Saving the picture…");
        app.bg(() -> {
            try {
                File tmp = new File(getCacheDir(), "shot_" + System.currentTimeMillis() + ".jpg");
                MediaCut.frame(this, videoUri(file), t, tmp);
                String name = niceName(file) + "_" + (int) t + "s.jpg";
                String where;
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    android.content.ContentValues cv = new android.content.ContentValues();
                    cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
                    cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put("relative_path", "Pictures/Glossa");
                    Uri u = getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (u == null) throw new IOException("the phone would not take the picture");
                    try (java.io.OutputStream o = getContentResolver().openOutputStream(u)) { Files.copy(tmp.toPath(), o); }
                    where = "Pictures / Glossa";
                } else {
                    File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
                    File dest = new File(dir, name);
                    Files.copy(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    where = dest.getParent();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                final String w = where;
                app.post(() -> Ui.toast(this, "Saved in " + w));
            } catch (IOException | RuntimeException e) { app.post(() -> Ui.toast(this, "Could not save the picture: " + e.getMessage())); }
        });
    }

    /** What a card of this line needs: the line, its translation, where it is in the video, the lines around it. */
    private Cards.Request requestFor(Subs.Cue c, String text) {
        Cards.Request r = new Cards.Request();
        r.lang = lang;
        r.kind = "video";
        r.sentence = text.replace('\n', ' ');
        r.start = c.start + delay;
        r.end = c.end + delay;
        r.video = videoUri(file).toString();
        r.videoName = videoName(file);
        r.title = com.batchmine.pocket.core.Shows.pretty(videoName(file));
        StringBuilder tr = new StringBuilder();
        for (Subs.Cue x : second) if (x.start < c.end && x.end > c.start) { if (tr.length() > 0) tr.append(' '); tr.append(x.text.replace('\n', ' ')); }
        r.translation = tr.toString();
        r.frameAt = (c.start + c.end) / 2 + delay;
        int ci = main.indexOf(c);
        for (int k = 1; k <= 3 && ci >= 0; k++) {
            if (ci - k >= 0) r.before.add(new Cards.Ctx(main.get(ci - k).text.replace('\n', ' '), main.get(ci - k).start + delay, main.get(ci - k).end + delay));
            if (ci + k < main.size()) r.after.add(new Cards.Ctx(main.get(ci + k).text.replace('\n', ' '), main.get(ci + k).start + delay, main.get(ci + k).end + delay));
        }
        return r;
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e) { Stopwatch.active(); lastTouch = System.currentTimeMillis(); return super.dispatchTouchEvent(e); }

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

    @Override protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        scrubEx.execute(() -> { try { if (scrubR != null) scrubR.release(); } catch (Exception ignored) { } });
        scrubEx.shutdown();
        if (session != null) { session.setActive(false); session.release(); session = null; }
        try { unregisterReceiver(noisy); } catch (RuntimeException ignored) { }
    }
}
