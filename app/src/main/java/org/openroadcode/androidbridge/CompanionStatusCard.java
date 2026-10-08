package org.openroadcode.androidbridge;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Read-only runtime overview; polling exists only while Home is visible. */
final class CompanionStatusCard {
  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final LinearLayout root;
  private final TextView target, status, detail;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final RuntimeLogPolling polling = new RuntimeLogPolling();
  private final Runnable refresh = this::refresh;

  CompanionStatusCard(Activity activity, Runnable manageRuntime) {
    this.activity = activity;
    settings = new RuntimeServiceManagerSettings(activity);
    root = UiTheme.card(activity);
    root.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 16));
    TextView label = UiTheme.text(activity, "YOUR RUNTIME", 10, UiTheme.BLUE);
    label.setLetterSpacing(.12f);
    root.addView(label);
    target = UiTheme.text(activity, "", 22, UiTheme.TEXT);
    target.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    target.setPadding(0, dp(6), 0, dp(8));
    root.addView(target);
    status = UiTheme.text(activity, "Checking connection…", 13, UiTheme.MUTED);
    root.addView(status);
    detail = UiTheme.text(activity, "", 12, UiTheme.MUTED);
    detail.setPadding(0, dp(6), 0, dp(14));
    root.addView(detail);
    root.addView(UiTheme.actionButton(activity, "Manage runtime  ›", UiTheme.BLUE,
        v -> manageRuntime.run()), new LinearLayout.LayoutParams(-1, dp(48)));
    target.setText(targetName());
  }

  View view() { return root; }
  void start() {
    handler.removeCallbacks(refresh);
    polling.start();
    handler.post(refresh);
  }
  void stop() {
    polling.stop();
    handler.removeCallbacks(refresh);
  }

  private String targetName() {
    if (settings.target() == RuntimeServiceManagerSettings.Target.TERMUX) return "Local Termux";
    var device = settings.activeDevice();
    return device == null ? "Choose a computing unit" : device.name();
  }

  private void refresh() {
    long generation = polling.begin();
    if (generation < 0) return;
    String label = targetName();
    target.setText(label);
    boolean local = settings.target() == RuntimeServiceManagerSettings.Target.TERMUX;
    String url = local ? TermuxServiceManagerClient.BASE_URL : settings.piBaseUrl();
    String token = local ? null : settings.piToken();
    new Thread(() -> {
      JSONObject services = null;
      try {
        if (url.isBlank()) throw new IllegalStateException("Pair a computing unit in Configuration.");
        services = new RuntimeServiceManagerClient(url, label, token).getServices();
      } catch (Exception failure) {
        // Connection failures are shown without exposing server details on Home.
      }
      JSONObject result = services;
      handler.post(() -> {
        if (!polling.complete(generation)) return;
        // Settings can change while a request is in progress; never label old data
        // as belonging to a newly selected computing unit.
        boolean nowLocal = settings.target() == RuntimeServiceManagerSettings.Target.TERMUX;
        String nowUrl = nowLocal ? TermuxServiceManagerClient.BASE_URL : settings.piBaseUrl();
        String nowToken = nowLocal ? null : settings.piToken();
        if (local != nowLocal || !url.equals(nowUrl) || !java.util.Objects.equals(token, nowToken)) {
          status.setText("Checking connection…");
          detail.setText("");
          handler.post(refresh);
          return;
        }
        if (result == null) {
          status.setText("○  Runtime unavailable");
          status.setTextColor(UiTheme.AMBER);
          detail.setText(url.isBlank() ? "Pair a computing unit in Configuration."
              : "Check the runtime or its connection in Configuration.");
        } else {
          JSONArray list = result.optJSONArray("services");
          if (list == null) {
            status.setText("○  Service status unavailable");
            status.setTextColor(UiTheme.AMBER);
            detail.setText("Open Runtime services to check this computing unit.");
          } else {
            int running = runningServices(list);
            status.setText("●  Connected · " + running + " of " + list.length() + " services running");
            status.setTextColor(UiTheme.GREEN);
            detail.setText(running == 0 ? "Runtime is idle. Start services when you need ORC."
                : "Open Performance for workload and sensor health.");
          }
        }
        handler.postDelayed(refresh, 5000);
      });
    }, "orc-home-status").start();
  }

  static int runningServices(JSONArray services) {
    int running = 0;
    for (int i = 0; i < services.length(); i++) {
      JSONObject service = services.optJSONObject(i);
      if (service != null && "running".equalsIgnoreCase(service.optString("state"))) running++;
    }
    return running;
  }
  private int dp(int value) { return UiTheme.dp(activity, value); }
}
