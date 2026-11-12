package com.batchmine.pocket;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** The PC player's timeline (uosc): a dark strip, the part played a little lighter, a white line at the position, elapsed time inside at the left, time left inside at the right. */
final class Timeline extends View {
    interface Seek { void to(int ms); }

    private int max = 1, pos;
    private float dragX = -1;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Seek seek;
    private Runnable onDragStart;
    private Seek scrub;                                         // while the finger is down: the time under it (ms), -1 when it lifts

    Timeline(Context c) {
        super(c);
        setContentDescription("Position");
    }

    void onSeek(Seek s, Runnable start) { seek = s; onDragStart = start; }
    void onScrub(Seek s) { scrub = s; }
    boolean dragging() { return dragX >= 0; }
    void set(int posMs, int maxMs) { max = Math.max(1, maxMs); pos = Math.max(0, Math.min(max, posMs)); if (dragX < 0) invalidate(); }

    private int at(float x) { return (int) (Math.max(0, Math.min(1, x / Math.max(1, getWidth()))) * max); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                dragX = e.getX();
                if (onDragStart != null) onDragStart.run();
                if (scrub != null) scrub.to(at(dragX));
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: dragX = e.getX(); if (scrub != null) scrub.to(at(dragX)); invalidate(); return true;
            case MotionEvent.ACTION_UP:
                int to = at(e.getX());
                dragX = -1;
                if (scrub != null) scrub.to(-1);
                pos = to;
                if (seek != null) seek.to(to);
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL: dragX = -1; if (scrub != null) scrub.to(-1); invalidate(); return true;
        }
        return super.onTouchEvent(e);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        int shown = dragX >= 0 ? at(dragX) : pos;
        float x = w * shown / (float) max;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xDB000000);
        c.drawRect(0, 0, w, h, p);
        p.setColor(0x24FFFFFF);
        c.drawRect(0, 0, x, h, p);
        p.setColor(0xFFFFFFFF);
        c.drawRect(Math.max(0, x - d), 0, x + d, h, p);
        p.setTextSize(13 * d);
        p.setFakeBoldText(true);
        p.setColor(0xFFFFFFFF);
        p.setShadowLayer(2 * d, 0, 0, 0xFF000000);
        float ty = h / 2 + 5 * d;
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(Ui.time(shown / 1000.0), 12 * d, ty, p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText("-" + Ui.time((max - shown) / 1000.0), w - 12 * d, ty, p);
    }
}
