package com.batchmine.pocket;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** A layout that puts its children in rows and starts a new row when one is full (tags, chips). */
final class Flow extends ViewGroup {
    private final int gap;

    Flow(Context c, int gapDp) { super(c); gap = Ui.dp(c, gapDp); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = v.getMeasuredWidth(), ch = v.getMeasuredHeight();
            if (x > 0 && x + cw > w) { x = 0; y += rowH + gap; rowH = 0; }
            x += cw + gap;
            rowH = Math.max(rowH, ch);
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + rowH + getPaddingTop() + getPaddingBottom());
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int cw = v.getMeasuredWidth(), ch = v.getMeasuredHeight();
            if (x > 0 && x + cw > w) { x = 0; y += rowH + gap; rowH = 0; }
            v.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + cw, getPaddingTop() + y + ch);
            x += cw + gap;
            rowH = Math.max(rowH, ch);
        }
    }
}
