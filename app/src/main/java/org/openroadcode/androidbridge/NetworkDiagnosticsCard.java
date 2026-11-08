package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openroadcode.androidbridge.ui.ExpandableCard;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Compact dashboard diagnostics, with human-readable details and optional JSON export. */
final class NetworkDiagnosticsCard {
  private final Activity activity;
  private final ExpandableCard expandable;
  private final TextView summary;
  private final TextView details;
  private JSONObject lastReport;

  NetworkDiagnosticsCard(Activity activity) {
    this.activity = activity;
    LinearLayout body = UiTheme.card(activity);
    summary = UiTheme.text(activity, "Tap Refresh to inspect connections.", 12, UiTheme.MUTED);
    body.addView(summary);
    details = UiTheme.text(activity, "", 12, UiTheme.TEXT);
    details.setPadding(0, UiTheme.dp(activity, 10), 0, UiTheme.dp(activity, 8));
    body.addView(details);
    Button refresh = new Button(activity);
    refresh.setText("Refresh");
    refresh.setOnClickListener(view -> refresh());
    body.addView(refresh);
    Button copy = new Button(activity);
    copy.setText("Copy JSON report");
    copy.setOnClickListener(view -> {
      if (lastReport == null) refresh();
      if (lastReport == null) return;
      ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard != null)
        clipboard.setPrimaryClip(ClipData.newPlainText("ORC network diagnostics", lastReport.toString()));
    });
    body.addView(copy);
    expandable = new ExpandableCard(activity, "Network & USB Diagnostics",
        "Ethernet, Wi-Fi, cellular and attached USB hardware", UiTheme.BLUE, body, false);
  }

  android.view.View view() { return expandable.view(); }

  private static boolean transport(JSONObject network, String name) {
    JSONArray transports = network.optJSONArray("transports");
    if (transports == null) return false;
    for (int i = 0; i < transports.length(); i++)
      if (name.equals(transports.optString(i))) return true;
    return false;
  }

  private static String list(JSONArray array) {
    if (array == null || array.length() == 0) return "None reported";
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < array.length(); i++) {
      if (i > 0) text.append(", ");
      text.append(array.optString(i));
    }
    return text.toString();
  }

  private void refresh() {
    try {
      lastReport = NetworkDiagnosticsService.snapshot(activity);
      JSONArray networks = lastReport.optJSONArray("networks");
      JSONArray usb = lastReport.optJSONArray("usb_devices");
      int ethernet = 0;
      StringBuilder text = new StringBuilder();
      if (networks != null) for (int i = 0; i < networks.length(); i++) {
        JSONObject network = networks.optJSONObject(i);
        if (network == null) continue;
        String type = transport(network, "ethernet") ? "Ethernet"
            : transport(network, "wifi") ? "Wi-Fi"
            : transport(network, "cellular") ? "Cellular"
            : transport(network, "vpn") ? "VPN" : "Other network";
        if ("Ethernet".equals(type)) ethernet++;
        text.append(type).append(network.optBoolean("active") ? " (active)" : "")
            .append("\n  Interface: ").append(network.optString("interface", "Unknown"))
            .append("\n  IP: ").append(list(network.optJSONArray("addresses")))
            .append("\n  DNS: ").append(list(network.optJSONArray("dns")))
            .append("\n  Validated: ").append(network.optBoolean("validated") ? "Yes" : "No")
            .append("\n\n");
      }
      text.append("USB devices\n");
      if (usb == null || usb.length() == 0) text.append("  None reported by Android");
      else for (int i = 0; i < usb.length(); i++) {
        JSONObject device = usb.optJSONObject(i);
        if (device == null) continue;
        text.append("  Device ").append(i + 1)
            .append(": vendor ").append(device.optInt("vendor_id"))
            .append(", product ").append(device.optInt("product_id"))
            .append("\n  Interfaces: ").append(device.optInt("interface_count"))
            .append("\n");
      }
      summary.setText("Ethernet: " + (ethernet == 0 ? "Not detected" : ethernet + " network(s)")
          + " · USB: " + (usb == null ? 0 : usb.length()) + " device(s)");
      details.setText(text.toString().trim());
    } catch (Exception error) {
      summary.setText("Diagnostics unavailable");
      details.setText(error.getClass().getSimpleName());
      lastReport = null;
    }
  }
}
