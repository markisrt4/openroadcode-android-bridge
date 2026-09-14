package org.openroadcode.androidbridge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * Small integrated-circuit style subsystem icon.
 *
 * Draws a rounded IC package with edge pins and a centered glyph so subsystem
 * icons share one visual language without requiring bitmap assets.
 */
public final class CircuitIconView extends View {
    private final Paint packagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String glyph;
    private final int accent;

    public CircuitIconView(Context context, String glyph, int accent) {
        super(context);
        this.glyph = glyph;
        this.accent = accent;

        packagePaint.setStyle(Paint.Style.FILL);
        packagePaint.setColor(UiTheme.SURFACE_RAISED);

        pinPaint.setStyle(Paint.Style.STROKE);
        pinPaint.setStrokeWidth(dp(2));
        pinPaint.setStrokeCap(Paint.Cap.ROUND);
        pinPaint.setColor(accent);

        glyphPaint.setColor(accent);
        glyphPaint.setTextAlign(Paint.Align.CENTER);
        glyphPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float size = Math.min(width, height);
        float pin = size * 0.12f;
        float left = (width - size) / 2f + pin;
        float top = (height - size) / 2f + pin;
        float right = (width + size) / 2f - pin;
        float bottom = (height + size) / 2f - pin;
        float radius = size * 0.13f;

        RectF body = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(body, radius, radius, packagePaint);

        pinPaint.setStyle(Paint.Style.STROKE);
        pinPaint.setColor(accent);
        pinPaint.setStrokeWidth(dp(2));
        canvas.drawRoundRect(body, radius, radius, pinPaint);

        float[] fractions = {0.24f, 0.5f, 0.76f};
        for (float fraction : fractions) {
            float x = left + (right - left) * fraction;
            canvas.drawLine(x, top, x, top - pin * 0.62f, pinPaint);
            canvas.drawLine(x, bottom, x, bottom + pin * 0.62f, pinPaint);

            float y = top + (bottom - top) * fraction;
            canvas.drawLine(left, y, left - pin * 0.62f, y, pinPaint);
            canvas.drawLine(right, y, right + pin * 0.62f, y, pinPaint);
        }

        glyphPaint.setTextSize(size * 0.34f);
        Paint.FontMetrics metrics = glyphPaint.getFontMetrics();
        float baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(glyph, width / 2f, baseline, glyphPaint);
    }

    private float dp(int value) {
        return UiTheme.dp(getContext(), value);
    }
}
