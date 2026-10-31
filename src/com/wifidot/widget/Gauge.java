package com.wifidot.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Simplified speedometer: a 240° arc with a non-linear scale (0–1000 Mbit/s, like common speed
 * tests), a coloured fill up to the value, a needle and the value in the middle.
 */
final class Gauge extends View {
    private static final double[] MARKS = {0, 5, 10, 50, 100, 250, 500, 1000};
    private static final float START = 150f, SWEEP = 240f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            needle = new Paint(Paint.ANTI_ALIAS_FLAG), value = new Paint(Paint.ANTI_ALIAS_FLAG),
            small = new Paint(Paint.ANTI_ALIAS_FLAG), ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float dp;
    private double mbps = -1;
    private String label = "", text = "—";

    Gauge(Context c, String label, int accent, int trackColor, int textColor, int subColor) {
        super(c);
        this.label = label;
        dp = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE); track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(8 * dp); track.setColor(trackColor);
        fill.setStyle(Paint.Style.STROKE); fill.setStrokeCap(Paint.Cap.ROUND);
        fill.setStrokeWidth(8 * dp); fill.setColor(accent);
        needle.setStrokeWidth(2.5f * dp); needle.setStrokeCap(Paint.Cap.ROUND); needle.setColor(textColor);
        value.setColor(textColor); value.setTextAlign(Paint.Align.CENTER);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        small.setColor(subColor); small.setTextAlign(Paint.Align.CENTER);
        ticks.setColor(subColor); ticks.setTextAlign(Paint.Align.CENTER);
    }

    /** -1 = no value. */
    void setValue(double mbps) {
        this.mbps = mbps;
        text = Ndt7.fmt(mbps);
        invalidate();
    }

    /** Position 0..1 on the non-linear scale. */
    static float position(double v) {
        if (v <= 0) return 0;
        for (int i = 1; i < MARKS.length; i++) {
            if (v <= MARKS[i]) {
                double f = (v - MARKS[i - 1]) / (MARKS[i] - MARKS[i - 1]);
                return (float) ((i - 1 + f) / (MARKS.length - 1));
            }
        }
        return 1;
    }

    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        float pad = 4 * dp + 14 * dp, r = width / 2f - pad;
        setMeasuredDimension(width, (int) (pad + r * 1.48f + 38 * dp));   // arc + value + unit + label
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), stroke = 8 * dp, pad = stroke / 2 + 14 * dp;
        float r = w / 2 - pad, cx = w / 2, cy = pad + r;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(oval, START, SWEEP, false, track);
        float pos = position(mbps);
        if (mbps > 0) c.drawArc(oval, START, Math.max(0.5f, SWEEP * pos), false, fill);

        // scale labels
        ticks.setTextSize(9 * dp);
        for (double m : new double[]{0, 10, 100, 1000}) {
            double a = Math.toRadians(START + SWEEP * position(m));
            float tr = r - stroke - 8 * dp;
            c.drawText(m >= 1000 ? "1G" : String.valueOf((int) m),
                    (float) (cx + tr * Math.cos(a)), (float) (cy + tr * Math.sin(a)) + 3 * dp, ticks);
        }
        // needle
        if (mbps >= 0) {
            double a = Math.toRadians(START + SWEEP * pos);
            float nr = r - stroke - 18 * dp;
            c.drawLine(cx + (float) (8 * dp * Math.cos(a)), cy + (float) (8 * dp * Math.sin(a)),
                    cx + (float) (nr * Math.cos(a)), cy + (float) (nr * Math.sin(a)), needle);
            c.drawCircle(cx, cy, 3 * dp, needle);
        }
        // value + unit + label
        value.setTextSize(r * 0.36f);
        c.drawText(text, cx, cy + r * 0.48f, value);
        small.setTextSize(11 * dp);
        c.drawText("Mbit/s", cx, cy + r * 0.48f + 14 * dp, small);
        small.setTextSize(12 * dp);
        c.drawText(label, cx, cy + r * 0.48f + 30 * dp, small);
    }
}
