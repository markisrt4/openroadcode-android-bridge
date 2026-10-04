// SPDX-FileCopyrightText: 2026 Mark G. Russell
// SPDX-License-Identifier: MIT

package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import java.util.ArrayList;
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
  private record ColorRange(int start, int end, int color) {}
  private record Target(String url, String label, String token) {}

  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final LinearLayout root;
  private final TextView status;
  private final TextView metrics;
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
    status = UiTheme.text(activity, "Choose the computing unit under Runtime target above", 12, UiTheme.MUTED);
    metrics = UiTheme.text(activity, "Waiting for performance data…", 13, UiTheme.TEXT);
    metrics.setPadding(0, UiTheme.dp(activity, 10), 0, UiTheme.dp(activity, 10));
    trends = new TrendView(activity);
    root.addView(status);
    root.addView(metrics);
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

  private Target selectedTarget() {
    if (settings.target() == RuntimeServiceManagerSettings.Target.TERMUX) {
      return new Target(TermuxServiceManagerClient.BASE_URL, "Local Termux", null);
    }
    RuntimeDevice device = settings.activeDevice();
    if (device == null) throw new IllegalStateException("Pair and choose a computing unit under Configuration");
    return new Target(device.baseUrl(), device.name(), device.accessToken());
  }

  private void poll() {
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
    metrics.setText("No current performance data");
    trends.setHistory(new JSONArray());
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
    JSONArray cores = sample.optJSONArray("per_core_percent");
    StringBuilder coreText = new StringBuilder();
    if (cores != null) {
      for (int i = 0; i < cores.length(); i++) {
        double value = cores.optDouble(i, Double.NaN);
        if (Double.isFinite(value)) {
          if (coreText.length() > 0) coreText.append("  ");
          coreText.append(String.format(Locale.US, "C%d %.0f%%", i, value));
        }
      }
    }
    ArrayList<ColorRange> colors = new ArrayList<>();
    StringBuilder workloadText = new StringBuilder();
    JSONObject workload = sample.optJSONObject("workload");
    if (workload != null) {
      workloadText.append("ORC WORKLOAD (diagnostics excluded)\nCPU ")
          .append(format(workload, "cpu_percent", 1, "%.1f%%"))
          .append(" • capacity ").append(format(workload, "cpu_capacity_percent", 1, "%.1f%%"))
          .append(" • ").append(workload.optInt("process_count", 0)).append(" processes\nRSS sum ")
          .append(format(workload, "rss_bytes", 1048576, "%.1f MiB"))
          .append(" • PSS ").append(format(workload, "pss_bytes", 1048576, "%.1f MiB"))
          .append("\n100% CPU = one logical core; ").append(sample.isNull("cpu_count") ? "--" : sample.optString("cpu_count"))
          .append(" logical CPUs detected; RSS counts shared pages\n")
          .append(workload.optString("visibility", "unknown")).append(": ")
          .append(workload.optString("detail", "")).append("\n");
      colors.add(new ColorRange(0, workloadText.indexOf("\n"), UiTheme.BLUE));
      int cpuStart = workloadText.indexOf("\n") + 1;
      colors.add(new ColorRange(cpuStart, workloadText.indexOf("\n", cpuStart),
          pressureColor(number(workload, "cpu_capacity_percent"))));
      JSONArray processes = workload.optJSONArray("processes");
      if (processes != null) {
        for (int i = 0; i < Math.min(50, processes.length()); i++) {
          JSONObject process = processes.optJSONObject(i);
          if (process == null) continue;
          int rowStart = workloadText.length();
          workloadText.append("\n").append(process.optString("name", "ORC process"))
              .append(" [").append(process.optInt("pid")).append("] ")
              .append(process.optString("category", "workload"))
              .append("\n  CPU ").append(format(process, "cpu_percent", 1, "%.1f%%"))
              .append(" • RSS ").append(format(process, "rss_bytes", 1048576, "%.1f MiB"))
              .append(" • PSS ").append(format(process, "pss_bytes", 1048576, "%.1f MiB"))
              .append("\n  threads ").append(process.optInt("thread_count", 0))
              .append(" • read ").append(format(process, "read_bytes_per_second", 1024, "%.1f KiB/s"))
              .append(" • write ").append(format(process, "write_bytes_per_second", 1024, "%.1f KiB/s"));
          colors.add(new ColorRange(rowStart, workloadText.length(),
              "diagnostics".equals(process.optString("category")) ? UiTheme.MUTED : UiTheme.BLUE));
        }
        if (processes.length() > 50) workloadText.append("\nShowing the top 50 processes by CPU use");
      }
      workloadText.append("\n\n");
    }
    JSONArray sensors = sample.optJSONArray("sensors");
    if (sensors != null) {
      int headingStart = workloadText.length();
      workloadText.append("SENSOR TELEMETRY • ").append(sample.optString("sensor_monitor_status", "unknown"))
          .append("\nFreshness, not a hardware self-test\n");
      colors.add(new ColorRange(headingStart, workloadText.indexOf("\n", headingStart), UiTheme.BLUE));
      for (int i = 0; i < sensors.length(); i++) {
        JSONObject sensor = sensors.optJSONObject(i);
        if (sensor == null) continue;
        int rowStart = workloadText.length();
        workloadText.append("\n").append(sensor.optString("name", "Sensor"))
            .append(" • ").append(sensor.optString("state", "not_observed"))
            .append(" • ").append(sensor.isNull("source") ? "--" : sensor.optString("source"))
            .append("\n  age ").append(format(sensor, "last_received_age_seconds", 1, "%.1f s"))
            .append(" • rate ").append(format(sensor, "message_rate_hz", 1, "%.1f Hz"))
            .append(" • invalid ").append(sensor.optInt("invalid_message_count", 0))
            .append("\n  ").append(sensor.optString("detail", ""));
        colors.add(new ColorRange(rowStart, workloadText.length(), sensorColor(sensor.optString("state"))));
      }
      workloadText.append("\n\n");
    }
    JSONArray services = sample.optJSONArray("services");
    if (services != null) {
      int headingStart = workloadText.length();
      workloadText.append("SERVICES • ").append(sample.optString("service_monitor_status", "unknown"))
          .append("\nSocket state, not an application response check. UDP bandwidth unavailable.\n");
      colors.add(new ColorRange(headingStart, workloadText.indexOf("\n", headingStart), UiTheme.BLUE));
      for (int i = 0; i < services.length(); i++) {
        JSONObject service = services.optJSONObject(i);
        if (service == null) continue;
        int rowStart = workloadText.length();
        workloadText.append("\n").append(service.optString("name", "Service"))
            .append(" [").append(service.isNull("pid") ? "--" : service.optString("pid"))
            .append("] ").append(service.optString("protocol", "--"))
            .append(" • ").append(service.optString("state", "unknown"))
            .append("\n  ").append(service.optString("local_endpoint", "--"))
            .append(" → ").append(service.optString("remote_endpoint", "--"))
            .append("\n  RX ").append(format(service, "receive_bytes_per_second", 1024, "%.1f KiB/s"))
            .append(" • TX ").append(format(service, "transmit_bytes_per_second", 1024, "%.1f KiB/s"))
            .append("\n  queues RX ").append(format(service, "receive_queue_bytes", 1, "%.0f B"))
            .append(" • TX ").append(format(service, "transmit_queue_bytes", 1, "%.0f B"))
            .append(" • UDP drops ").append(format(service, "udp_drops", 1, "%.0f"));
        colors.add(new ColorRange(rowStart, workloadText.length(), serviceColor(service.optString("state"))));
      }
      workloadText.append("\n\n");
    }
    String text = workloadText.toString()
        + "SYSTEM\nCPU  " + format(sample, "cpu_percent", 1, "%.0f%%")
        + (sample.isNull("cpu_unavailable_reason") ? "" : " • " + sample.optString("cpu_unavailable_reason"))
        + "   " + format(sample, "cpu_frequency_hz", 1e6, "%.0f MHz")
        + "   load " + format(sample, "load_1m", 1, "%.2f") + "\n" + coreText
        + "\nRAM  " + format(sample, "memory_used_percent", 1, "%.0f%%")
        + "   " + format(sample, "memory_available_bytes", 1048576, "%.0f MiB available")
        + "\nSwap  " + format(sample, "swap_used_bytes", 1048576, "%.0f MiB")
        + " / " + format(sample, "swap_total_bytes", 1048576, "%.0f MiB")
        + "\nThermal  " + format(sample, "temperature_c", 1, "%.0f°C")
        + "   to trip " + format(sample, "thermal_headroom_c", 1, "%.0f°C")
        + "   throttle " + (sample.isNull("throttled_flags") ? "--" : sample.optString("throttled_flags", "--"))
        + "\nStorage  " + format(sample, "disk_used_percent", 1, "%.0f%%")
        + "   " + format(sample, "disk_free_bytes", 1073741824, "%.1f GiB free")
        + "\nNetwork ↓ " + format(sample, "network_receive_bytes_per_second", 1024, "%.0f KiB/s")
        + "   ↑ " + format(sample, "network_transmit_bytes_per_second", 1024, "%.0f KiB/s")
        + "\nDisk read " + format(sample, "disk_read_bytes_per_second", 1024, "%.0f KiB/s")
        + "   write " + format(sample, "disk_write_bytes_per_second", 1024, "%.0f KiB/s")
        + "\nUptime  " + format(sample, "uptime_seconds", 3600, "%.1f hours");
    SpannableString styled = new SpannableString(text);
    for (ColorRange range : colors) {
      styled.setSpan(new ForegroundColorSpan(range.color()), range.start(), range.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    int systemStart = workloadText.length();
    colorLine(styled, systemStart, "SYSTEM", UiTheme.BLUE);
    colorLine(styled, systemStart, "CPU  ", pressureColor(number(sample, "cpu_percent")));
    colorLine(styled, systemStart, "RAM  ", pressureColor(number(sample, "memory_used_percent")));
    colorLine(styled, systemStart, "Storage  ", pressureColor(number(sample, "disk_used_percent")));
    Double headroom = number(sample, "thermal_headroom_c");
    colorLine(styled, systemStart, "Thermal  ", headroom == null ? UiTheme.MUTED
        : headroom <= 5 ? UiTheme.RED : headroom <= 10 ? UiTheme.AMBER : UiTheme.GREEN);
    metrics.setText(styled);
    JSONArray history = payload.optJSONArray("history");
    trends.setHistory(history == null ? new JSONArray() : history);
  }

  private static int pressureColor(Double percent) {
    return percent == null ? UiTheme.MUTED : percent >= 95 ? UiTheme.RED
        : percent >= 80 ? UiTheme.AMBER : UiTheme.GREEN;
  }

  private static int serviceColor(String state) {
    return switch (state) {
      case "connected", "listening" -> UiTheme.GREEN;
      case "connecting", "closing" -> UiTheme.AMBER;
      case "dropping", "stopped" -> UiTheme.RED;
      case "bound" -> UiTheme.BLUE;
      default -> UiTheme.MUTED;
    };
  }

  private static int sensorColor(String state) {
    return switch (state) {
      case "streaming" -> UiTheme.GREEN;
      case "stale", "degraded" -> UiTheme.AMBER;
      case "invalid" -> UiTheme.RED;
      default -> UiTheme.MUTED;
    };
  }

  private static void colorLine(SpannableString text, int from, String prefix, int color) {
    String value = text.toString();
    int start = value.startsWith(prefix, from) ? from : value.indexOf("\n" + prefix, from);
    if (start < 0) return;
    if (value.charAt(start) == '\n') start++;
    int end = value.indexOf('\n', start);
    text.setSpan(new ForegroundColorSpan(color), start, end < 0 ? value.length() : end,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
