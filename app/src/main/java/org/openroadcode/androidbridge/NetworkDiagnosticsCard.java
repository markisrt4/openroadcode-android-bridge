package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openroadcode.androidbridge.ui.UiTheme;

/** On-demand, read-only network and USB diagnostics card. */
final class NetworkDiagnosticsCard {
  private final Activity activity;
  private final LinearLayout root;
  private final TextView summary;
  private final TextView details;
  private JSONObject lastReport;

  NetworkDiagnosticsCard(Activity activity) {
    this.activity = activity;
    root = UiTheme.card(activity);
    root.addView(UiTheme.text(activity, "NETWORK & USB DIAGNOSTICS", 14, UiTheme.TEXT));
    summary = UiTheme.text(activity, "Tap Refresh to inspect connected hardware.", 12, UiTheme.MUTED);
    root.addView(summary);
    details = UiTheme.text(activity, "", 12, UiTheme.MUTED);
    details.setVisibility(View.GONE);
    root.addView(details);
    Button refresh = new Button(activity);
    refresh.setText("Refresh");
    refresh.setOnClickListener(view -> refresh());
    root.addView(refresh);
    Button copy = new Button(activity);
    copy.setText("Copy JSON report");
    copy.setOnClickListener(view -> {
      if (lastReport == null) refresh();
      if (lastReport == null) return;
      ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("ORC network diagnostics", lastReport.toString(2)));
    });
    root.addView(copy);
  }

  View view() { return root; }

  private void refresh() {
    try {
      lastReport = NetworkDiagnosticsService.snapshot(activity);
      JSONArray networks = lastReport.optJSONArray("networks");
      JSONArray usb = lastReport.optJSONArray("usb_devices");
      int ethernet = 0;
      if (networks != null) for (int i = 0; i < networks.length(); i++) {
        JSONObject network = networks.optJSONObject(i);
        if (network != null && network.optJSONArray("transports") != null) {
          JSONArray transports = network.optJSONArray("transports");
          for (int j = 0; j < transports.length(); j++)
            if ("ethernet".equals(transports.optString(j))) { ethernet++; break; }
        }
      }
      summary.setText("Ethernet networks: " + ethernet + " · USB devices: " + (usb == null ? 0 : usb.length()));
      details.setText(lastReport.toString(2));
      details.setVisibility(View.VISIBLE);
    } catch (Exception error) {
      summary.setText("Diagnostics unavailable: " + error.getClass().getSimpleName());
      details.setVisibility(View.GONE);
      lastReport = null;
    }
  }
}
