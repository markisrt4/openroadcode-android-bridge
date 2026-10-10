package org.openroadcode.androidbridge.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A consistent set of small line icons, independent of emoji fonts. */
public final class FeatureIconView extends View {
  private final String feature;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  public FeatureIconView(Context context, String feature, int color) {
    super(context);
    this.feature = feature;
    paint.setColor(color);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.8f);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    float scale = Math.min(getWidth(), getHeight()) / 32f;
    canvas.translate((getWidth() - 32 * scale) / 2, (getHeight() - 32 * scale) / 2);
    canvas.scale(scale, scale);
    switch (feature) {
      case "navigation" -> {
        canvas.drawCircle(16, 16, 9, paint);
        canvas.drawLine(16, 2, 16, 7, paint); canvas.drawLine(16, 25, 16, 30, paint);
        canvas.drawLine(2, 16, 7, 16, paint); canvas.drawLine(25, 16, 30, 16, paint);
        canvas.drawCircle(16, 16, 2, paint);
      }
      case "automotive" -> {
        canvas.drawRoundRect(4, 13, 28, 23, 3, 3, paint);
        canvas.drawLine(7, 13, 10, 7, paint); canvas.drawLine(10, 7, 22, 7, paint);
        canvas.drawLine(22, 7, 25, 13, paint);
        canvas.drawLine(7, 18, 10, 18, paint); canvas.drawLine(22, 18, 25, 18, paint);
        canvas.drawLine(8, 23, 8, 26, paint); canvas.drawLine(24, 23, 24, 26, paint);
      }
      case "radio" -> {
        canvas.drawCircle(16, 16, 2, paint);
        canvas.drawArc(8, 8, 24, 24, -55, 110, false, paint);
        canvas.drawArc(8, 8, 24, 24, 125, 110, false, paint);
        canvas.drawArc(2, 2, 30, 30, -55, 110, false, paint);
        canvas.drawArc(2, 2, 30, 30, 125, 110, false, paint);
      }
      case "media" -> {
        canvas.drawRoundRect(3, 5, 29, 27, 4, 4, paint);
        canvas.drawLine(13, 11, 22, 16, paint); canvas.drawLine(22, 16, 13, 21, paint);
        canvas.drawLine(13, 21, 13, 11, paint);
      }
      case "environmental" -> {
        canvas.drawCircle(16, 16, 6, paint);
        for (int i = 0; i < 8; i++) {
          canvas.save(); canvas.rotate(i * 45, 16, 16);
          canvas.drawLine(16, 3, 16, 6, paint); canvas.restore();
        }
      }
      case "performance" -> {
        canvas.drawLine(4, 27, 28, 27, paint);
        canvas.drawRoundRect(6, 16, 10, 23, 1, 1, paint);
        canvas.drawRoundRect(14, 9, 18, 23, 1, 1, paint);
        canvas.drawRoundRect(22, 4, 26, 23, 1, 1, paint);
      }
      default -> {
        canvas.drawRoundRect(4, 4, 28, 28, 4, 4, paint);
        canvas.drawLine(9, 11, 23, 11, paint); canvas.drawLine(9, 16, 23, 16, paint);
        canvas.drawLine(9, 21, 18, 21, paint);
      }
    }
    canvas.restore();
  }
}
