package org.openroadcode.androidbridge;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import org.openroadcode.androidbridge.ui.UiTheme;
import org.openroadcode.androidbridge.ui.ExpandableCard;
import android.content.Context;

/** Radio device controls use the application's USB owner; leaving the screen keeps reception alive. */
final class RadioCard {
  private final OpenRoadCodeBridgeApplication application;
  private final LinearLayout root;
  private final TextView status, device, diagnostics;
  private final Button connect, disconnect;

  RadioCard(Activity activity) {
    application = (OpenRoadCodeBridgeApplication) activity.getApplication();
    root = UiTheme.card(activity);
    root.addView(UiTheme.text(activity,
        "Connect an RTL-SDR receiver to this phone for the local radio source. "
        + "ADS-B below runs on your selected computing unit.", 12, UiTheme.MUTED));
    status = UiTheme.text(activity, "Checking receiver…", 14, UiTheme.TEXT);
    status.setPadding(0, UiTheme.dp(activity, 12), 0, UiTheme.dp(activity, 6));
    root.addView(status);
    device = UiTheme.text(activity, "", 11, UiTheme.MUTED);
    root.addView(device);
    LinearLayout actions = new LinearLayout(activity);
    connect = UiTheme.actionButton(activity, "Connect receiver", UiTheme.VIOLET, v -> {
      try { application.startRadioBridge(); refresh(); }
      catch (RuntimeException error) { status.setText("Receiver connection failed. Try connecting again."); }
    });
    disconnect = UiTheme.actionButton(activity, "Disconnect", UiTheme.SURFACE_RAISED, v -> {
      application.stopRadioBridge(); refresh();
    });
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
    left.setMargins(0, UiTheme.dp(activity, 12), UiTheme.dp(activity, 4), 0);
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
    right.setMargins(UiTheme.dp(activity, 4), UiTheme.dp(activity, 12), 0, 0);
    actions.addView(connect, left);
    actions.addView(disconnect, right);
    root.addView(actions);
    diagnostics = UiTheme.text(activity, "", 11, UiTheme.MUTED);
    diagnostics.setPadding(0, UiTheme.dp(activity, 8), 0, 0);
    root.addView(new ExpandableCard(activity, "Receiver details",
        "USB endpoint and recent stream diagnostics", UiTheme.VIOLET,
        diagnostics, false).view());
  }

  View view() { return root; }
  void refresh() {
    RtlSdrUsbManager.State state = application.radioState();
    if (state == null || (state.status != RtlSdrUsbManager.Status.PERMISSION_PENDING
        && state.status != RtlSdrUsbManager.Status.ERROR))
      state = application.getRtlSdrUsbManager().refresh();
    boolean open = state.status == RtlSdrUsbManager.Status.OPEN;
    status.setText(open ? "●  Receiver connected to this phone" : "○  " + state.message);
    status.setTextColor(open ? UiTheme.GREEN : UiTheme.MUTED);
    device.setText(state.device == null ? "Attach an RTL-SDR USB receiver to use the local source."
        : state.deviceLabel());
    android.content.SharedPreferences preferences = application.getSharedPreferences(
        RtlSdrUsbProxyService.DIAGNOSTIC_PREFERENCES, Context.MODE_PRIVATE);
    diagnostics.setText("USB proxy: 127.0.0.1:" + RtlSdrUsbProxyService.TCP_PORT
        + "\nLast stream: " + preferences.getString(RtlSdrUsbProxyService.PREF_LAST_STREAM_STATUS, "none recorded")
        + "\nControl: " + preferences.getString(RtlSdrUsbProxyService.PREF_CONTROL_STATUS, "none recorded")
        + "\nService: " + preferences.getString(RtlSdrUsbProxyService.PREF_LAST_SERVICE_STATUS, "none recorded"));
    boolean pending = state.status == RtlSdrUsbManager.Status.PERMISSION_PENDING;
    connect.setEnabled(!open && !pending && state.device != null);
    disconnect.setEnabled(open || pending);
  }
}
