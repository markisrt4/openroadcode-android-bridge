// SPDX-FileCopyrightText: 2026 Mark G. Russell
// SPDX-License-Identifier: MIT

package org.openroadcode.androidbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings.RuntimeDevice;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Read-only performance of the selected computing unit, using existing pairing. */
public final class SystemPerformanceCard {
  private record Target(String url, String label, String token) {}

  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final LinearLayout root;
  private final TextView status;
  private final Button targetButton;
  private final PerformanceMetricsView metrics;
  private final Button trendButton;
  private boolean showTrends;
  private final TrendView trends;
  private boolean active;
  private boolean inFlight;
  private int generation;
  private Target lastTarget;

  private final Runnable refresh = new Runnable() {
    @Override public void run() {
      if (!active) return;
      poll();
      handler.postDelayed(this, 1000);
    }
  };

  public SystemPerformanceCard(Activity activity) {
    this.activity = activity;
    settings = new RuntimeServiceManagerSettings(activity);
    root = UiTheme.card(activity);
    status = UiTheme.text(activity, "Choose a computing unit", 12, UiTheme.MUTED);
    metrics = new PerformanceMetricsView(activity);
    trends = new TrendView(activity);
    trends.setVisibility(View.GONE);
    targetButton = UiTheme.actionButton(activity, "Computing unit ▾", UiTheme.SURFACE_RAISED, v -> chooseTarget());
    root.addView(targetButton);
    updateTargetLabel();
    root.addView(status);
    root.addView(metrics.view());
    trendButton = UiTheme.actionButton(activity, "Show trends ▾", UiTheme.SURFACE_RAISED, v -> {
      showTrends = !showTrends;
      ((Button) v).setText(showTrends ? "Hide trends ▴" : "Show trends ▾");
      trends.setVisibility(showTrends ? View.VISIBLE : View.GONE);
    });
    trendButton.setVisibility(View.GONE);
    root.addView(trendButton);
    root.addView(trends, new LinearLayout.LayoutParams(-1, UiTheme.dp(activity, 240)));
  }

  public View view() { return root; }

  public void start() {
    active = true;
    handler.removeCallbacks(refresh);
    handler.post(refresh);
  }

  public void stop() {
    active = false;
    generation++;
    handler.removeCallbacks(refresh);
  }

  private void updateTargetLabel() {
    RuntimeDevice device = settings.activeDevice();
    String label = settings.target() == RuntimeServiceManagerSettings.Target.TERMUX
        ? "Local Termux" : device == null ? "Choose a paired unit" : device.name();
    targetButton.setText("Computing unit: " + label + " ▾");
  }

  private void chooseTarget() {
    var devices = settings.devices();
    String[] labels = new String[devices.size() + 1];
    labels[0] = "Local Termux";
    int selected = 0;
    RuntimeDevice current = settings.activeDevice();
    for (int i = 0; i < devices.size(); i++) {
      labels[i + 1] = devices.get(i).name();
      if (settings.target() != RuntimeServiceManagerSettings.Target.TERMUX && current != null
          && current.deviceId().equals(devices.get(i).deviceId())) selected = i + 1;
    }
    new AlertDialog.Builder(activity).setTitle(devices.isEmpty()
        ? "Computing unit (pair remotes in Configuration)" : "Computing unit")
        .setSingleChoiceItems(labels, selected, (dialog, which) -> {
          if (which == 0) settings.setTarget(RuntimeServiceManagerSettings.Target.TERMUX);
          else settings.setActiveDevice(devices.get(which - 1).deviceId());
          updateTargetLabel();
          generation++;
          lastTarget = null;
          clear("Connecting to selected computing unit…");
          dialog.dismiss();
          if (active) poll();
        })
        .setNegativeButton("Cancel", null).show();
  }

  private Target selectedTarget() {
    if (settings.target() == RuntimeServiceManagerSettings.Target.TERMUX) {
      return new Target(TermuxServiceManagerClient.BASE_URL, "Local Termux", null);
    }
    RuntimeDevice device = settings.activeDevice();
    if (device == null) throw new IllegalStateException("Pair and choose a computing unit under Configuration");
    return new Target(device.baseUrl(), device.name(), device.accessToken());
  }

  private void poll() {
    updateTargetLabel();
    final Target target;
    try {
      target = selectedTarget();
    } catch (Exception error) {
      clear(error.getMessage());
      return;
    }
    if (!target.equals(lastTarget)) {
      lastTarget = target;
      generation++;
      clear("Connecting to " + target.label() + "…");
    }
    if (inFlight) return;
    inFlight = true;
    final int requestGeneration = generation;
    new Thread(() -> {
      JSONObject payload = null;
      String failure = null;
      try {
        payload = new RuntimeServiceManagerClient(target.url(), target.label(), target.token()).getPerformance();
        if (payload.optInt("version", -1) != 1) {
          throw new IllegalStateException("Unsupported performance telemetry version");
        }
      } catch (Exception error) {
        failure = error.getMessage() == null ? "Computing unit unavailable" : error.getMessage();
      }
      final JSONObject result = payload;
      final String error = failure;
      handler.post(() -> {
        inFlight = false;
        if (!active || requestGeneration != generation) return;
        try {
          if (!target.equals(selectedTarget())) return;
        } catch (Exception ignored) { return; }
        if (error != null) clear(target.label() + ": " + error);
        else render(target, result);
      });
    }, "orc-performance-request").start();
  }

  private void clear(String message) {
    status.setText(message);
    status.setTextColor(UiTheme.MUTED);
    metrics.clear();
    trendButton.setVisibility(View.GONE);
    trends.setHistory(new JSONArray());
    trends.setVisibility(View.GONE);
  }

  private void render(Target target, JSONObject payload) {
    JSONObject sample = payload.optJSONObject("snapshot");
    Double age = number(payload, "sample_age_seconds");
    if (!payload.isNull("error") || age == null || age > 3 || sample == null) {
      clear(target.label() + ": waiting for a fresh performance sample");
      return;
    }
    status.setText(target.label() + " • " + sample.optString("hostname", "computing unit")
        + " • " + sample.optString("platform", "") + " • LIVE");
    status.setTextColor(UiTheme.GREEN);
    metrics.render(sample);
    trendButton.setVisibility(View.VISIBLE);
    trends.setVisibility(showTrends ? View.VISIBLE : View.GONE);
    JSONArray history = payload.optJSONArray("history");
    trends.setHistory(history == null ? new JSONArray() : history);
  }

  private static Double number(JSONObject object, String key) {
    if (object.isNull(key)) return null;
    double value = object.optDouble(key, Double.NaN);
    return Double.isFinite(value) ? value : null;
  }

  private static String format(JSONObject object, String key, double divisor, String pattern) {
    Double value = number(object, key);
    return value == null ? "--" : String.format(Locale.US, pattern, value / divisor);
  }

  /** Repaint a bounded two-minute history; null measurements leave gaps. */
  private static final class TrendView extends View {
    private JSONArray history = new JSONArray();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String[] keys = {"orc_cpu_percent", "memory_used_percent", "temperature_c"};
    private final String[] labels = {"ORC CPU % (100 = one core)", "RAM %", "TEMP °C"};
    private final int[] colors = {UiTheme.BLUE, UiTheme.GREEN, UiTheme.AMBER};

    TrendView(Context context) { super(context); }

    void setHistory(JSONArray values) { history = values; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float rowHeight = getHeight() / 3f;
      float labelHeight = UiTheme.dp(getContext(), 22);
      int start = Math.max(0, history.length() - 120);
      JSONObject first = history.optJSONObject(start);
      JSONObject last = history.optJSONObject(history.length() - 1);
      Double firstTime = first == null ? null : number(first, "sampled_at_unix_s");
      Double lastTime = last == null ? null : number(last, "sampled_at_unix_s");
      for (int row = 0; row < keys.length; row++) {
        paint.setColor(colors[row]);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(UiTheme.dp(getContext(), 11));
        canvas.drawText(labels[row] + " • last 2 minutes", 0, row * rowHeight + labelHeight - 4, paint);
        float top = row * rowHeight + labelHeight;
        float bottom = (row + 1) * rowHeight - 6;
        Path path = new Path();
        boolean connected = false;
        float ceiling = row == 2 ? 120 : 100;
        if (row == 0) {
          for (int j = start; j < history.length(); j++) {
            JSONObject entry = history.optJSONObject(j);
            Double cpu = entry == null ? null : number(entry, "orc_cpu_percent");
            if (cpu != null) ceiling = Math.max(ceiling, cpu.floatValue());
          }
        }
        for (int i = start; i < history.length(); i++) {
          JSONObject sample = history.optJSONObject(i);
          Double value = sample == null ? null : number(sample, keys[row]);
          Double sampleTime = sample == null ? null : number(sample, "sampled_at_unix_s");
          if (value == null || sampleTime == null || firstTime == null || lastTime == null) {
            connected = false;
            continue;
          }
          double duration = Math.max(120, lastTime - firstTime);
          float x = (float) ((sampleTime - lastTime + duration) / duration * getWidth());
          float y = bottom - (float) Math.max(0, Math.min(ceiling, value)) / ceiling * (bottom - top);
          if (connected) path.lineTo(x, y); else path.moveTo(x, y);
          connected = true;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(UiTheme.dp(getContext(), 2));
        canvas.drawPath(path, paint);
      }
    }
  }
}
