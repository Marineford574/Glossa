package com.batchmine.pocket;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pictures for the video lists, like the PC's posters: one frame of the video (a quarter of the way in, so not the black
 * start), cut once in the background and kept as a small JPEG in the app's "thumbs" folder.
 */
final class Thumbs {
    private Thumbs() { }

    private static final ConcurrentHashMap<String, Bitmap> MEM = new ConcurrentHashMap<>();
    private static final java.util.Set<String> BAD = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());   // videos with no readable frame: not tried again this run

    static File file(File video) { return new File(App.get().dir("thumbs"), video.getName().replaceAll("[^\\w.-]+", "_") + ".jpg"); }

    /** Puts the video's picture into VIEW when it is there (at once if already cut, else after cutting it in the background). */
    static void into(ImageView view, File video) {
        String key = video.getName();
        view.setTag(key);
        Bitmap m = MEM.get(key);
        if (m != null) { view.setImageBitmap(m); return; }
        view.setImageDrawable(null);
        if (BAD.contains(key)) return;
        App app = App.get();
        app.bg(() -> {
            Bitmap b = load(video);
            if (b == null) { BAD.add(key); return; }
            MEM.put(key, b);
            app.post(() -> { if (key.equals(view.getTag())) view.setImageBitmap(b); });
        });
    }

    private static Bitmap load(File video) {
        File f = file(video);
        if (f.isFile() && f.length() > 0) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(App.get(), Player.videoUri(video));
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long us = 0;
            try { us = Long.parseLong(d) * 250; } catch (RuntimeException ignored) { }          // a quarter of the way in (ms -> us)
            Bitmap b = r.getFrameAtTime(Math.min(us, 600_000_000L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            int w = 360, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            if (s != b) b.recycle();
            try (FileOutputStream o = new FileOutputStream(f)) { s.compress(Bitmap.CompressFormat.JPEG, 80, o); } catch (IOException ignored) { }
            return s;
        } catch (RuntimeException e) {                         // no access to the video any more, or a format the phone cannot read
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    /** A tile for a video: a picture area (16:9) with its fallback colour, a ▶ and, when started, a thin bar of how far you are. */
    static android.widget.FrameLayout tile(android.content.Context c, File video, String fallback, float progress) {
        android.widget.FrameLayout fl = new android.widget.FrameLayout(c);
        int h = Math.abs(fallback.hashCode());
        fl.setBackground(Ui.round(Ui.mix(Ui.ACCENT, (h & 1) == 0 ? Ui.BG : Ui.TEXT, 0.15f + (h % 5) * 0.08f), Ui.dp(c, Ui.RS)));
        fl.setClipToOutline(true);
        fl.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(c, Ui.RS)); }
        });
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fl.addView(iv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.widget.TextView ini = Ui.text(c, MainActivity.initialsOf(fallback), 20, Ui.ON_ACC);
        ini.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ini.setGravity(android.view.Gravity.CENTER);
        fl.addView(ini, 0, new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.widget.TextView pl = Ui.text(c, "▶", 14, 0xFFFFFFFF);
        pl.setGravity(android.view.Gravity.CENTER);
        pl.setBackground(Ui.round(0x88000000, Ui.dp(c, 14)));
        android.widget.FrameLayout.LayoutParams plp = new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 28), Ui.dp(c, 28), android.view.Gravity.CENTER);
        fl.addView(pl, plp);
        if (progress > 0.01f) {
            android.view.View bar = new android.view.View(c);
            bar.setBackgroundColor(Ui.ACCENT);
            fl.addView(bar, new android.widget.FrameLayout.LayoutParams(1, Ui.dp(c, 3), android.view.Gravity.BOTTOM | android.view.Gravity.START));
            fl.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
                android.widget.FrameLayout.LayoutParams lp = (android.widget.FrameLayout.LayoutParams) bar.getLayoutParams();
                int w = (int) ((r - l) * Math.min(1f, progress));
                if (lp.width != w) { lp.width = Math.max(1, w); bar.setLayoutParams(lp); }
            });
        }
        into(iv, video);
        return fl;
    }
}
