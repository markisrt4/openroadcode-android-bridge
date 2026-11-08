package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openroadcode.androidbridge.ui.ExpandableCard;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Expandable diagnostics section with individual connection-type subcards. */
final class NetworkDiagnosticsCard {
  private final Activity activity;
  private final ExpandableCard expandable;
  private final TextView summary;
  private final GridLayout cards;
  private final TextView selectedDetails;
  private JSONObject lastReport;

  NetworkDiagnosticsCard(Activity activity) {
    this.activity = activity;
    LinearLayout body = UiTheme.card(activity);
    summary = UiTheme.text(activity, "Tap Refresh to inspect connections.", 12, UiTheme.MUTED);
    body.addView(summary);
    cards = new GridLayout(activity);
    cards.setColumnCount(2);
    cards.setPadding(0, UiTheme.dp(activity, 10), 0, UiTheme.dp(activity, 8));
    body.addView(cards);
    selectedDetails = UiTheme.text(activity, "", 12, UiTheme.MUTED);
    selectedDetails.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 12),
        UiTheme.dp(activity, 10), UiTheme.dp(activity, 12));
    selectedDetails.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 12));
    selectedDetails.setVisibility(View.GONE);
    body.addView(selectedDetails);
    Button refresh = UiTheme.actionButton(activity, "Refresh", UiTheme.BLUE, view -> refresh());
    body.addView(refresh);
    Button copy = UiTheme.actionButton(activity, "Copy JSON report", UiTheme.SURFACE_RAISED, view -> {
      if (lastReport == null) refresh();
      if (lastReport == null) return;
      ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
      if (clipboard != null)
        clipboard.setPrimaryClip(ClipData.newPlainText("ORC network diagnostics", lastReport.toString()));
    });
    LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(-1, -2);
    copyParams.topMargin = UiTheme.dp(activity, 8);
    body.addView(copy, copyParams);
    expandable = new ExpandableCard(activity, "Network & USB Diagnostics",
        "Ethernet, Wi-Fi, cellular and attached USB hardware", UiTheme.BLUE, body, false);
  }

  View view() { return expandable.view(); }

  private static boolean transport(JSONObject network, String name) {
    JSONArray transports = network.optJSONArray("transports");
    if (transports == null) return false;
    for (int i = 0; i < transports.length(); i++)
      if (name.equals(transports.optString(i))) return true;
    return false;
  }

  private static String list(JSONArray array) {
    if (array == null || array.length() == 0) return "None reported";
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < array.length(); i++) {
      if (i > 0) result.append(", ");
      result.append(array.optString(i));
    }
    return result.toString();
  }

  private void addCard(String title, String type, int accent, String status, String detail) {
    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 12),
        UiTheme.dp(activity, 8), UiTheme.dp(activity, 12));
    card.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 12));
    ImageView icon = new ImageView(activity);
    int iconId = "ethernet".equals(type) ? R.drawable.ic_diagnostics_ethernet
        : "wifi".equals(type) ? R.drawable.ic_diagnostics_wifi
        : "cellular".equals(type) ? R.drawable.ic_diagnostics_cellular
        : R.drawable.ic_diagnostics_usb;
    icon.setImageResource(iconId);
    icon.setContentDescription(title);
    card.addView(icon, new LinearLayout.LayoutParams(UiTheme.dp(activity, 30), UiTheme.dp(activity, 30)));
    TextView name = UiTheme.text(activity, title, 14, UiTheme.TEXT);
    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
    nameParams.topMargin = UiTheme.dp(activity, 8);
    card.addView(name, nameParams);
    card.addView(UiTheme.text(activity, status, 11, accent));
    card.setClickable(true);
    card.setFocusable(true);
    card.setContentDescription(title + ", " + status + ". Tap for details.");
    card.setOnClickListener(view -> {
      if (selectedDetails.getVisibility() == View.VISIBLE
          && selectedDetails.getTag() != null && selectedDetails.getTag().equals(type)) {
        selectedDetails.setVisibility(View.GONE);
        selectedDetails.setTag(null);
      } else {
        selectedDetails.setText(title + "\n" + detail);
        selectedDetails.setTag(type);
        selectedDetails.setVisibility(View.VISIBLE);
      }
    });
    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
    params.width = 0;
    params.height = UiTheme.dp(activity, 126);
    params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
    params.setMargins(UiTheme.dp(activity, 3), UiTheme.dp(activity, 3),
        UiTheme.dp(activity, 3), UiTheme.dp(activity, 3));
    cards.addView(card, params);
  }

  private String networkDetails(JSONArray networks, String type) {
    StringBuilder details = new StringBuilder();
    if (networks != null) for (int i = 0; i < networks.length(); i++) {
      JSONObject network = networks.optJSONObject(i);
      if (network == null || !transport(network, type)) continue;
      if (details.length() > 0) details.append("\n\n");
      details.append(network.optString("interface", "Unknown interface"));
      if (network.optBoolean("active")) details.append(" (active)");
      details.append("\nIP: ").append(list(network.optJSONArray("addresses")))
          .append("\nDNS: ").append(list(network.optJSONArray("dns")))
          .append("\nInternet validated: ").append(network.optBoolean("validated") ? "Yes" : "No");
    }
    return details.length() == 0 ? "No " + type + " network reported by Android." : details.toString();
  }

  private static int count(JSONArray networks, String type) {
    int total = 0;
    if (networks != null) for (int i = 0; i < networks.length(); i++) {
      JSONObject network = networks.optJSONObject(i);
      if (network != null && transport(network, type)) total++;
    }
    return total;
  }

  private void refresh() {
    try {
      lastReport = NetworkDiagnosticsService.snapshot(activity);
      JSONArray networks = lastReport.optJSONArray("networks");
      JSONArray usb = lastReport.optJSONArray("usb_devices");
      cards.removeAllViews();
      selectedDetails.setVisibility(View.GONE);
      selectedDetails.setTag(null);
      int ethernet = count(networks, "ethernet");
      int wifi = count(networks, "wifi");
      int cellular = count(networks, "cellular");
      int activeCellular = 0;
      if (networks != null) for (int i = 0; i < networks.length(); i++) {
        JSONObject network = networks.optJSONObject(i);
        if (network != null && transport(network, "cellular") && network.optBoolean("active")) activeCellular++;
      }
      int usbCount = usb == null ? 0 : usb.length();
      summary.setText("Ethernet: " + (ethernet > 0 ? "Detected" : "Not detected")
          + " · USB: " + usbCount + " device(s)");
      addCard("Ethernet", "ethernet", ethernet > 0 ? UiTheme.GREEN : UiTheme.MUTED,
          ethernet > 0 ? ethernet + " network(s)" : "Not detected", networkDetails(networks, "ethernet"));
      addCard("Wi-Fi", "wifi", wifi > 0 ? UiTheme.GREEN : UiTheme.MUTED,
          wifi > 0 ? wifi + " network(s)" : "Not connected", networkDetails(networks, "wifi"));
      addCard("Cellular", "cellular", cellular > 0 ? UiTheme.GREEN : UiTheme.MUTED,
          cellular + " interface(s) · " + activeCellular + " active", networkDetails(networks, "cellular"));
      StringBuilder usbDetails = new StringBuilder();
      if (usb != null) for (int i = 0; i < usb.length(); i++) {
        JSONObject device = usb.optJSONObject(i);
        if (device == null) continue;
        if (usbDetails.length() > 0) usbDetails.append("\n\n");
        usbDetails.append("Device ").append(i + 1)
            .append("\nVendor ID: ").append(device.optInt("vendor_id"))
            .append("\nProduct ID: ").append(device.optInt("product_id"))
            .append("\nInterfaces: ").append(device.optInt("interface_count"));
      }
      JSONArray managed = lastReport.optJSONArray("system_managed_networks");
      JSONObject sysfs = lastReport.optJSONObject("usb_sysfs");
      JSONArray sysfsDevices = sysfs == null ? null : sysfs.optJSONArray("devices");
      int managedCount = managed == null ? 0 : managed.length();
      int sysfsCount = sysfsDevices == null ? 0 : sysfsDevices.length();
      usbDetails.append("\n\nSystem-managed Ethernet indicators: ").append(managedCount);
      if (managed != null) for (int i = 0; i < managed.length(); i++) {
        JSONObject entry = managed.optJSONObject(i);
        if (entry == null) continue;
        usbDetails.append("\n").append(entry.optString("interface", "Unknown interface"))
            .append(" (USB backing unconfirmed)");
      }
      usbDetails.append("\nUSB sysfs: ")
          .append(sysfs == null ? "not reported" : sysfs.optString("status", "unknown"))
          .append("\nReadable USB metadata: ").append(sysfsCount);
      if (sysfsDevices != null) for (int i = 0; i < sysfsDevices.length(); i++) {
        JSONObject entry = sysfsDevices.optJSONObject(i);
        if (entry == null) continue;
        usbDetails.append("\n").append(entry.optString("vendor_id_hex", "????"))
            .append(":").append(entry.optString("product_id_hex", "????"));
      }
      usbDetails.append("\nSystem-managed devices may be hidden from the Android USB host list.");
      addCard("USB Devices", "usb", usbCount > 0 ? UiTheme.GREEN : UiTheme.MUTED,
          usbCount + " host · " + sysfsCount + " sysfs", usbDetails.toString());
    } catch (Exception error) {
      summary.setText("Diagnostics unavailable");
      cards.removeAllViews();
      selectedDetails.setText(error.getClass().getSimpleName());
      selectedDetails.setVisibility(View.VISIBLE);
      lastReport = null;
    }
  }
}
