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

    static boolean isVideo(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".m4v") || n.endsWith(".mov") || n.endsWith(".avi") || n.endsWith(".3gp") || n.endsWith(".ts");
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
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        video.setOnClickListener(v -> toggle());
        LinearLayout subs = Ui.col(this);
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
        LinearLayout ctl = Ui.col(this);
        ctl.setBackgroundColor(0xCC000000);
        ctl.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 6));
        LinearLayout sr = Ui.row(this);
        timeText = Ui.text(this, "0:00", 13, Ui.TEXT);
        sr.addView(timeText);
        seek = new SeekBar(this);
        sr.addView(Ui.weight(seek, 1));
        ctl.addView(sr);
        LinearLayout br = Ui.row(this);
        String[][] btns = {{"⏮", "prev"}, {"↺", "repeat"}, {"⏯", "play"}, {"⏭", "next"}, {"CC", "subs"}, {"Aa", "size"}};
        for (String[] bt : btns) {
            android.widget.Button bb = Ui.button(this, bt[0], v -> action(bt[1]));
            bb.setMinWidth(0);
            bb.setPadding(0, 0, 0, 0);
            br.addView(Ui.weight(bb, 1));
        }
        ctl.addView(br);
        root.addView(ctl, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        subs.setTranslationY(-Ui.dp(this, 0));
        setContentView(root);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) timeText.setText(Ui.time(p / 1000.0)); }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) { dragging = false; video.seekTo(s.getProgress()); shown = -2; }
        });
        loadSubs();
        video.setVideoURI(Uri.fromFile(file));
        video.setOnPreparedListener(mp -> {
            seek.setMax(video.getDuration());
            int at = (int) (app.prefs().getFloat("vpos_" + file.getName(), 0) * 1000);
            if (at > 5000 && at < video.getDuration() - 8000) video.seekTo(at - 2000);
            video.start();
            tick();
        });
        video.setOnErrorListener((mp, w, e) -> { Ui.alert(this, "Cannot play this video", "The phone could not play " + file.getName() + ". MP4 (H.264) works everywhere; other formats depend on the phone."); return true; });
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
    }

    private List<Subs.Cue> read(File f) {
        try { return Subs.parse(Subs.decode(Files.readAllBytes(f.toPath()))); }
        catch (IOException | RuntimeException e) {
            app.report("Could not read " + f.getName(), e);
            Ui.toast(this, "Could not read " + f.getName());
            return new ArrayList<>();
        }
    }

    private void toggle() { if (video.isPlaying()) video.pause(); else video.start(); }

    private void action(String a) {
        double t = video.getCurrentPosition() / 1000.0;
        switch (a) {
            case "play": toggle(); break;
            case "prev": { int i = Subs.around(main, t); int j = Subs.at(main, t) >= 0 ? i - 1 : i; jump(j); break; }
            case "next": { int i = Subs.at(main, t); int j = i >= 0 ? i + 1 : nextAfter(t); jump(j); break; }
            case "repeat": {
                int i = Subs.around(main, t);
                if (i < 0) return;
                loopFrom = main.get(i).start - 0.15;
                loopUntil = main.get(i).end;
                video.seekTo((int) (Math.max(0, loopFrom) * 1000));
                video.start();
                break;
            }
            case "subs": subsDialog(); break;
            case "size": {
                float s = subText.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
                s = s >= 30 ? 16 : s + 4;
                subText.setTextSize(s);
                trText.setTextSize(Math.max(12, s - 6));
                break;
            }
        }
    }

    private int nextAfter(double t) { for (int i = 0; i < main.size(); i++) if (main.get(i).start > t) return i; return -1; }

    private void jump(int i) {
        if (i < 0 || i >= main.size()) return;
        loopUntil = -1;
        video.seekTo((int) (Math.max(0, main.get(i).start - 0.05) * 1000));
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
            if (loopUntil > 0 && t >= loopUntil) { video.seekTo((int) (Math.max(0, loopFrom) * 1000)); }
            int i = Subs.at(main, t);
            if (i != shown) { shown = i; showLine(i); }
        } catch (RuntimeException e) {
            if (!tickFailed) { tickFailed = true; app.report("Player: showing a line", e); Ui.toast(this, "Something went wrong: " + e); }
        }
        h.postDelayed(this::tick, 120);
    }

    private void showLine(int i) {
        if (i < 0) { subText.setVisibility(View.GONE); trText.setVisibility(View.GONE); return; }
        Subs.Cue c = main.get(i);
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
        Cards.Request r = new Cards.Request();
        r.lang = lang;
        r.kind = "video";
        r.sentence = text.replace('\n', ' ');
        r.start = c.start;
        r.end = c.end;
        r.video = Uri.fromFile(file).toString();
        r.videoName = file.getName();
        r.title = niceName(file);
        StringBuilder tr = new StringBuilder();
        for (Subs.Cue x : second) if (x.start < c.end && x.end > c.start) { if (tr.length() > 0) tr.append(' '); tr.append(x.text.replace('\n', ' ')); }
        r.translation = tr.toString();
        r.frameAt = (c.start + c.end) / 2;
        WordSheet.show(this, lang, word, r, () -> { col.forget(); shown = -2; });
    }

    @Override protected void onPause() {
        super.onPause();
        if (video != null && file != null) {
            app.prefs().edit().putFloat("vpos_" + file.getName(), video.getCurrentPosition() / 1000f).apply();
            video.pause();
        }
    }

    @Override protected void onDestroy() { super.onDestroy(); h.removeCallbacksAndMessages(null); }
}
