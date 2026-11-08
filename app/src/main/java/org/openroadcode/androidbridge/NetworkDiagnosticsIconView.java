package org.openroadcode.androidbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Small custom line icons for the network diagnostics cards. */
final class NetworkDiagnosticsIconView extends View {
  private final String type;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

  NetworkDiagnosticsIconView(Context context, String type, int color) {
    super(context);
    this.type = type;
    paint.setColor(color);
    paint.setStrokeWidth(1.8f);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    canvas.translate((getWidth() - Math.min(getWidth(), getHeight())) / 2f,
        (getHeight() - Math.min(getWidth(), getHeight())) / 2f);
    canvas.scale(Math.min(getWidth(), getHeight()) / 32f,
        Math.min(getWidth(), getHeight()) / 32f);
    switch (type) {
      case "ethernet":
        canvas.drawRoundRect(5, 7, 27, 25, 2, 2, paint);
        canvas.drawLine(10, 7, 10, 14, paint);
        canvas.drawLine(22, 7, 22, 14, paint);
        canvas.drawLine(10, 14, 22, 14, paint);
        for (int i = 0; i < 4; i++) canvas.drawLine(12 + i * 3, 14, 12 + i * 3, 19, paint);
        break;
      case "wifi":
        arc(canvas, 16, 25, 19, 210, 120);
        arc(canvas, 16, 25, 13, 210, 120);
        arc(canvas, 16, 25, 7, 210, 120);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(16, 25, 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        break;
      case "cellular":
        canvas.drawLine(16, 5, 16, 27, paint);
        canvas.drawLine(16, 11, 8, 27, paint);
        canvas.drawLine(16, 11, 24, 27, paint);
        canvas.drawLine(10, 23, 22, 23, paint);
        arc(canvas, 16, 11, 8, 300, 120);
        arc(canvas, 16, 11, 14, 300, 120);
        break;
      case "usb":
        canvas.drawLine(16, 27, 16, 6, paint);
        canvas.drawLine(16, 16, 8, 10, paint);
        canvas.drawLine(16, 21, 24, 15, paint);
        canvas.drawCircle(8, 10, 2, paint);
        canvas.drawRect(22, 13, 26, 17, paint);
        Path arrow = new Path();
        arrow.moveTo(16, 4); arrow.lineTo(12, 10); arrow.lineTo(20, 10); arrow.close();
        canvas.drawPath(arrow, paint);
        break;
      default:
        canvas.drawCircle(16, 16, 10, paint);
    }
    canvas.restore();
  }

  private void arc(Canvas canvas, float x, float y, float radius, float start, float sweep) {
    canvas.drawArc(x-radius, y-radius, x+radius, y+radius, start, sweep, false, paint);
  }
}
