// SPDX-FileCopyrightText: 2026 Mark G. Russell
// SPDX-License-Identifier: MIT
package org.openroadcode.androidbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Scan-friendly summaries; technical details are opened deliberately. */
final class PerformanceMetricsView {
  private final Activity activity;
  private final LinearLayout root, tiles, rows;
  private final TextView note;
  private final Button[] tabs = new Button[4];
  private final List<TextView> values = new ArrayList<>();
  private final List<TextView> captions = new ArrayList<>();
  private final List<Row> rowViews = new ArrayList<>();
  private List<String> rowKeys = java.util.Collections.emptyList();
  private JSONObject sample;
  private int page;

  PerformanceMetricsView(Activity activity) {
    this.activity = activity;
    root = vertical();
    LinearLayout navigation = new LinearLayout(activity);
    String[] labels = {"Workload", "System", "Sensors", "Services"};
    for (int i = 0; i < tabs.length; i++) {
      final int index = i;
      tabs[i] = UiTheme.actionButton(activity, labels[i], UiTheme.SURFACE_RAISED, v -> select(index));
      tabs[i].setTextSize(10);
      tabs[i].setLetterSpacing(0);
      tabs[i].setPadding(0, 0, 0, 0);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
      params.setMargins(0, dp(8), dp(3), dp(8));
      navigation.addView(tabs[i], params);
    }
    root.addView(navigation);
    tiles = vertical();
    root.addView(tiles);
    note = UiTheme.text(activity, "Waiting for performance data", 11, UiTheme.MUTED);
    note.setPadding(0, dp(8), 0, dp(8));
    root.addView(note);
    Button info = UiTheme.actionButton(activity, "About these readings", UiTheme.SURFACE_RAISED,
        v -> details("About these readings", explanation()));
    root.addView(info, new LinearLayout.LayoutParams(-1, dp(36)));
    rows = vertical();
    root.addView(rows);
    select(0);
  }

  View view() { return root; }

  void clear() {
    sample = null;
    note.setText("No current readings");
    tiles.removeAllViews(); rows.removeAllViews();
    values.clear(); captions.clear(); rowViews.clear(); rowKeys = java.util.Collections.emptyList();
  }

  void render(JSONObject value) {
    sample = value;
    refresh();
  }

  private void select(int index) {
    page = index;
    for (int i = 0; i < tabs.length; i++) {
      UiTheme.setButtonColor(activity, tabs[i], i == page ? UiTheme.BLUE : UiTheme.SURFACE_RAISED);
    }
    tiles.removeAllViews(); rows.removeAllViews();
    values.clear(); captions.clear(); rowViews.clear(); rowKeys = java.util.Collections.emptyList();
    refresh();
  }

  private void refresh() {
    if (sample == null) return;
    switch (page) {
      case 0 -> workload();
      case 1 -> system();
      case 2 -> sensors();
      case 3 -> services();
    }
  }

  private void workload() {
    JSONObject work = sample.optJSONObject("workload");
    if (work == null) { clear(); return; }
    ensureTiles(4);
    metric(0, "CPU capacity", format(work, "cpu_capacity_percent", 1, "%.1f%%"), pressure(number(work, "cpu_capacity_percent")));
    metric(1, "Memory · RSS", format(work, "rss_bytes", 1048576, "%.1f MiB"), UiTheme.BLUE);
    metric(2, "ORC processes", String.valueOf(work.optInt("process_count")), UiTheme.TEXT);
    metric(3, "CPU · one core = 100%", format(work, "cpu_percent", 1, "%.1f%%"), UiTheme.BLUE);
    String visibility = work.optString("visibility");
    note.setText("partial".equals(visibility) ? "Partial process visibility · tap a row for details"
        : "Diagnostics excluded · tap a process for details");
    List<Item> items = new ArrayList<>();
    JSONArray processes = work.optJSONArray("processes");
    if (processes != null) for (int i = 0; i < Math.min(50, processes.length()); i++) {
      JSONObject process = processes.optJSONObject(i);
      if (process == null) continue;
      boolean diagnostic = "diagnostics".equals(process.optString("category"));
      items.add(new Item("pid:" + process.optInt("pid"), friendly(process.optString("name")),
          format(process, "cpu_percent", 1, "%.1f%% CPU"),
          format(process, "rss_bytes", 1048576, "%.1f MiB") + (diagnostic ? " · diagnostics" : ""),
          diagnostic ? UiTheme.MUTED : UiTheme.BLUE,
          process.optString("name") + "\nPID " + process.optInt("pid") + " · " + process.optString("state")
          + "\nCPU " + format(process, "cpu_percent", 1, "%.1f%%")
          + "\nRSS " + format(process, "rss_bytes", 1048576, "%.1f MiB")
          + "\nPSS " + format(process, "pss_bytes", 1048576, "%.1f MiB")
          + "\nThreads " + process.optInt("thread_count")
          + "\nDisk read " + format(process, "read_bytes_per_second", 1024, "%.1f KiB/s")
          + "\nDisk write " + format(process, "write_bytes_per_second", 1024, "%.1f KiB/s")));
    }
    updateRows(items);
  }

  private void system() {
    ensureTiles(4);
    metric(0, "System CPU", format(sample, "cpu_percent", 1, "%.0f%%"), pressure(number(sample, "cpu_percent")));
    metric(1, "RAM", format(sample, "memory_used_percent", 1, "%.0f%%"), pressure(number(sample, "memory_used_percent")));
    metric(2, "Temperature", format(sample, "temperature_c", 1, "%.1f°C"), thermalColor());
    metric(3, "Storage", format(sample, "disk_used_percent", 1, "%.0f%%"), pressure(number(sample, "disk_used_percent")));
    note.setText("Tap a row for source and measurement details");
    List<Item> items = new ArrayList<>();
    items.add(new Item("cpu", "CPU", format(sample, "cpu_frequency_hz", 1e6, "%.0f MHz"),
        sample.optInt("cpu_count") + " logical CPUs", UiTheme.BLUE,
        "Unavailable reason: " + (sample.isNull("cpu_unavailable_reason") ? "None" : sample.optString("cpu_unavailable_reason"))
        + "\nLoad " + format(sample, "load_1m", 1, "%.2f") + "\nPer-core CPU: " + sample.optJSONArray("per_core_percent")));
    items.add(new Item("ram", "Memory", format(sample, "memory_available_bytes", 1048576, "%.0f MiB free"),
        "Available RAM", UiTheme.GREEN, "Swap " + format(sample, "swap_used_bytes", 1048576, "%.0f MiB")
        + " / " + format(sample, "swap_total_bytes", 1048576, "%.0f MiB")));
    StringBuilder thermal = new StringBuilder(sample.optString("thermal_detail", "Kernel-reported sensor"));
    thermal.append("\nSelected zone: ").append(sample.optString("thermal_zone", "--"))
        .append("\nSource type: ").append(sample.optString("thermal_source_type", "--"))
        .append("\nHeadroom: ").append(format(sample, "thermal_headroom_c", 1, "%.1f°C"))
        .append("\nThrottle flags: ").append(sample.optString("throttled_flags", "--"));
    JSONArray sources = sample.optJSONArray("thermal_sources");
    if (sources != null) for (int i = 0; i < sources.length(); i++) {
      JSONObject source = sources.optJSONObject(i);
      if (source != null) thermal.append("\n").append(source.optString("zone")).append(" · ")
          .append(source.optString("source_type")).append(" · ").append(format(source, "temperature_c", 1, "%.1f°C"))
          .append(" · trip ").append(format(source, "trip_c", 1, "%.1f°C"));
    }
    items.add(new Item("thermal", "Thermal source", format(sample, "thermal_headroom_c", 1, "%.0f°C to trip"),
        sample.isNull("thermal_source_type") ? "Source unavailable" : sample.optString("thermal_source_type"),
        thermalColor(), thermal.toString()));
    items.add(new Item("storage", "Storage", format(sample, "disk_free_bytes", 1073741824, "%.1f GiB free"),
        sample.optString("disk_path", ""), UiTheme.BLUE, "Total " + format(sample, "disk_total_bytes", 1073741824, "%.1f GiB")));
    items.add(new Item("network", "Network", format(sample, "network_receive_bytes_per_second", 1024, "↓ %.1f KiB/s"),
        format(sample, "network_transmit_bytes_per_second", 1024, "↑ %.1f KiB/s"), UiTheme.BLUE, "Host interface traffic; loopback excluded."));
    items.add(new Item("disk", "Disk I/O", format(sample, "disk_read_bytes_per_second", 1024, "↓ %.1f KiB/s"),
        format(sample, "disk_write_bytes_per_second", 1024, "↑ %.1f KiB/s"), UiTheme.BLUE, "Read/write byte rates for physical disks."));
    JSONObject battery = sample.optJSONObject("battery");
    if (battery != null && !"not_applicable".equals(battery.optString("state"))) {
      boolean available = "available".equals(battery.optString("state"));
      items.add(new Item("battery", "Battery", available ? format(battery, "charge_percent", 1, "%.0f%%") : "--",
          available ? format(battery, "temperature_c", 1, "%.1f°C") + " · " + battery.optString("health") : "Unavailable",
          !available ? UiTheme.MUTED : switch (battery.optString("health", "UNKNOWN")) {
            case "GOOD" -> UiTheme.GREEN;
            case "UNKNOWN" -> UiTheme.MUTED;
            case "OVERHEAT", "DEAD", "OVER_VOLTAGE", "UNSPECIFIED_FAILURE" -> UiTheme.RED;
            default -> UiTheme.AMBER;
          },
          battery.optString("detail") + "\n" + battery.optString("health") + "\n" + battery.optString("charging_state")
          + "\n" + battery.optString("plugged") + "\nBattery temperature is separate from CPU temperature."));
    }
    updateRows(items);
  }

  private void sensors() {
    JSONArray sensors = sample.optJSONArray("sensors");
    int streaming = 0, attention = 0;
    List<Item> items = new ArrayList<>();
    if (sensors != null) for (int i = 0; i < sensors.length(); i++) {
      JSONObject sensor = sensors.optJSONObject(i);
      if (sensor == null) continue;
      String state = sensor.optString("state", "not_observed");
      if ("streaming".equals(state)) streaming++;
      if (java.util.Arrays.asList("stale", "degraded", "invalid").contains(state)) attention++;
      String source = sensor.isNull("source") ? "Not observed" : sensor.optString("source");
      items.add(new Item(sensor.optString("topic") + ":" + source + ":" + i, sensor.optString("name"),
          state.replace('_', ' ').toUpperCase(Locale.US),
          "streaming".equals(state) ? format(sensor, "message_rate_hz", 1, "%.1f Hz") + " · " + source
              : source + " · " + format(sensor, "last_received_age_seconds", 1, "%.0f s ago"), stateColor(state),
          source + "\n" + sensor.optString("detail")
          + "\nReceived age " + format(sensor, "last_received_age_seconds", 1, "%.1f s")
          + "\nSample advance age " + format(sensor, "last_sample_age_seconds", 1, "%.1f s")
          + "\nRate " + format(sensor, "message_rate_hz", 1, "%.1f Hz")
          + "\nInvalid messages " + sensor.optInt("invalid_message_count")
          + "\nStale after " + format(sensor, "stale_after_seconds", 1, "%.1f s")));
    }
    ensureTiles(2);
    metric(0, "Streaming", String.valueOf(streaming), UiTheme.GREEN);
    metric(1, "Needs attention", String.valueOf(attention), attention > 0 ? UiTheme.AMBER : UiTheme.GREEN);
    note.setText("Telemetry freshness · tap a stream for details");
    updateRows(items);
  }

  private void services() {
    JSONArray services = sample.optJSONArray("services");
    LinkedHashMap<String, List<JSONObject>> groups = new LinkedHashMap<>();
    if (services != null) for (int i = 0; i < services.length(); i++) {
      JSONObject service = services.optJSONObject(i);
      if (service == null) continue;
      String key = service.optString("name") + ":" + service.optString("pid") + ":" + service.optString("protocol");
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(service);
    }
    List<Item> items = new ArrayList<>();
    int observed = 0, attention = 0;
    for (var group : groups.entrySet()) {
      JSONObject first = group.getValue().get(0);
      String state = first.optString("state", "unknown");
      StringBuilder detail = new StringBuilder(first.optString("name"));
      for (JSONObject endpoint : group.getValue()) {
        String endpointState = endpoint.optString("state", "unknown");
        if (servicePriority(endpointState) > servicePriority(state)) state = endpointState;
        detail.append("\n\n").append(endpoint.optString("local_endpoint", "--")).append(" → ")
            .append(endpoint.optString("remote_endpoint", "--"))
            .append("\n").append(endpointState).append(" · PID ").append(endpoint.optString("pid", "--"))
            .append("\nRX ").append(format(endpoint, "receive_bytes_per_second", 1024, "%.1f KiB/s"))
            .append(" · TX ").append(format(endpoint, "transmit_bytes_per_second", 1024, "%.1f KiB/s"))
            .append("\nQueues RX ").append(format(endpoint, "receive_queue_bytes", 1, "%.0f B"))
            .append(" · TX ").append(format(endpoint, "transmit_queue_bytes", 1, "%.0f B"))
            .append("\nUDP drops ").append(format(endpoint, "udp_drops", 1, "%.0f"))
            .append("\n").append(endpoint.optString("detail"));
      }
      if (!first.isNull("pid")) observed++;
      if (severity(state) == 2) attention++;
      items.add(new Item(group.getKey(), friendly(first.optString("name")), state.replace('_', ' ').toUpperCase(Locale.US),
          first.optString("protocol", "--") + " · " + group.getValue().size() + " endpoint(s) · traffic details ›",
          stateColor(state), detail.toString()));
    }
    ensureTiles(2);
    metric(0, "Observed groups", String.valueOf(observed), UiTheme.BLUE);
    metric(1, "Warnings", String.valueOf(attention), attention > 0 ? UiTheme.RED : UiTheme.GREEN);
    note.setText("Socket state · tap a service for bandwidth and endpoints");
    updateRows(items);
  }

  private void ensureTiles(int count) {
    if (values.size() == count) return;
    tiles.removeAllViews(); values.clear(); captions.clear();
    for (int i = 0; i < count; i += 2) {
      LinearLayout pair = new LinearLayout(activity);
      for (int j = 0; j < 2; j++) {
        LinearLayout tile = vertical();
        tile.setPadding(dp(10), dp(10), dp(10), dp(10));
        tile.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
        TextView caption = UiTheme.text(activity, "", 10, UiTheme.MUTED);
        TextView value = UiTheme.text(activity, "--", 24, UiTheme.TEXT);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tile.addView(caption); tile.addView(value);
        captions.add(caption); values.add(value);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(0, 0, dp(5), dp(5));
        pair.addView(tile, params);
      }
      tiles.addView(pair);
    }
  }

  private void metric(int index, String label, String value, int color) {
    captions.get(index).setText(label); values.get(index).setText(value); values.get(index).setTextColor(color);
  }

  private void updateRows(List<Item> items) {
    List<String> keys = new ArrayList<>();
    for (Item item : items) keys.add(item.key());
    if (!keys.equals(rowKeys)) {
      rows.removeAllViews(); rowViews.clear(); rowKeys = keys;
      for (Item item : items) { Row row = new Row(); rowViews.add(row); rows.addView(row.view); }
    }
    for (int i = 0; i < items.size(); i++) rowViews.get(i).update(items.get(i));
  }

  private final class Row {
    final LinearLayout view = vertical();
    final TextView title = UiTheme.text(activity, "", 13, UiTheme.TEXT);
    final TextView badge = UiTheme.text(activity, "", 11, UiTheme.MUTED);
    final TextView subtitle = UiTheme.text(activity, "", 11, UiTheme.MUTED);
    Item item;
    Row() {
      view.setPadding(dp(10), dp(10), dp(10), dp(10));
      view.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE, UiTheme.BORDER, 9));
      LinearLayout heading = new LinearLayout(activity);
      heading.setGravity(Gravity.CENTER_VERTICAL);
      title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
      heading.addView(badge); view.addView(heading); view.addView(subtitle);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      params.setMargins(0, dp(6), 0, 0); view.setLayoutParams(params);
      view.setClickable(true); view.setFocusable(true);
      view.setOnClickListener(v -> details(item.title(), item.detail()));
    }
    void update(Item value) {
      item = value; title.setText(value.title()); badge.setText(value.badge());
      badge.setTextColor(value.color()); subtitle.setText(value.subtitle());
      view.setContentDescription(value.title() + ", " + value.badge() + ", " + value.subtitle() + ". Tap for details.");
    }
  }

  private String explanation() {
    if (sample == null) return "Waiting for current readings.";
    JSONObject work = sample.optJSONObject("workload");
    return switch (page) {
      case 0 -> "CPU capacity is ORC's share of all detected logical CPUs. Raw CPU 100% means one logical core.\n"
          + sample.optInt("cpu_count") + " logical CPUs detected.\nRSS includes shared pages; PSS apportions them.\nDiagnostics processes are excluded from totals.\n"
          + (work == null ? "" : "PSS total " + format(work, "pss_bytes", 1048576, "%.1f MiB")
              + "\nDisk read " + format(work, "read_bytes_per_second", 1024, "%.1f KiB/s")
              + "\nDisk write " + format(work, "write_bytes_per_second", 1024, "%.1f KiB/s")
              + "\n" + work.optString("detail"));
      case 1 -> "Host readings describe the selected computing unit. Missing metrics stay unavailable.\nBattery is separate from CPU temperature.\nUptime "
          + format(sample, "uptime_seconds", 3600, "%.1f hours");
      case 2 -> "Freshness and validity of observed telemetry, not a hardware self-test. Unobserved streams may be disabled.\n"
          + sample.optString("sensor_monitor_status");
      default -> "Socket state is not an application response check. UDP bandwidth needs service-level counters.\n"
          + sample.optString("service_monitor_status");
    };
  }

  private void details(String title, String message) {
    new AlertDialog.Builder(activity).setTitle(title).setMessage(message).setPositiveButton("Close", null).show();
  }
  private int thermalColor() {
    if (number(sample, "temperature_c") == null) return UiTheme.MUTED;
    Double headroom = number(sample, "thermal_headroom_c");
    return headroom == null ? UiTheme.BLUE : headroom <= 5 ? UiTheme.RED : headroom <= 10 ? UiTheme.AMBER : UiTheme.GREEN;
  }
  private LinearLayout vertical() { LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
  private int dp(int value) { return UiTheme.dp(activity, value); }
  private record Item(String key, String title, String badge, String subtitle, int color, String detail) {}
  private static Double number(JSONObject object, String key) { double v = object.optDouble(key, Double.NaN); return Double.isFinite(v) ? v : null; }
  private static String format(JSONObject object, String key, double divisor, String pattern) { Double value = number(object, key); return value == null ? "--" : String.format(Locale.US, pattern, value / divisor); }
  private static int pressure(Double value) { return value == null ? UiTheme.MUTED : value >= 95 ? UiTheme.RED : value >= 80 ? UiTheme.AMBER : UiTheme.GREEN; }
  private static int severity(String state) { return java.util.Arrays.asList("dropping", "stopped", "invalid").contains(state) ? 2 : java.util.Arrays.asList("stale", "degraded", "connecting", "closing").contains(state) ? 1 : 0; }
  private static int servicePriority(String state) {
    if (severity(state) == 2) return 10;
    return switch (state) {
      case "connected" -> 6;
      case "listening" -> 5;
      case "bound" -> 4;
      case "connecting" -> 3;
      case "closing" -> 2;
      default -> 0;
    };
  }
  private static int stateColor(String state) { return severity(state) == 2 ? UiTheme.RED : severity(state) == 1 ? UiTheme.AMBER : java.util.Arrays.asList("streaming", "connected", "listening").contains(state) ? UiTheme.GREEN : "bound".equals(state) ? UiTheme.BLUE : UiTheme.MUTED; }
  private static String friendly(String name) {
    if (name.contains("service_manager")) return "Service manager";
    if (name.contains("navigation_service") || name.equals("Navigation")) return "Navigation";
    if (name.contains("automotive_service")) return "Automotive";
    if (name.contains("broker")) return "Message broker";
    if (name.contains("android_sensor")) return "Android sensors";
    if (name.contains("weather")) return "Weather";
    if (name.contains("trip")) return "Trip";
    if (name.contains("apps.orcUi")) return "ORC UI";
    String shortName = name.substring(name.lastIndexOf('.') + 1).replace("_cli", "").replace('_', ' ');
    return shortName.isEmpty() ? "Process" : shortName;
  }
}
