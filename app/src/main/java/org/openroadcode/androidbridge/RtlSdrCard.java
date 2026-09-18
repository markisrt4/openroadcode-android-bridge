package org.openroadcode.androidbridge;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Controls Android ownership of an attached RTL-SDR and its localhost USB proxy. */
final class RtlSdrCard {
  private final Context context;
  private final RtlSdrUsbManager manager;
  private final LinearLayout root;
  private final TextView status;
  private final TextView device;
  private final TextView endpoint;
  private final Button connect;
  private final Button stop;

  RtlSdrCard(Context context) {
    this.context = context;
    manager = ((OpenRoadCodeBridgeApplication) context.getApplicationContext()).getRtlSdrUsbManager();
    root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);

    status = UiTheme.text(context, "●  Checking USB…", 12, UiTheme.MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    root.addView(status);

    device = UiTheme.text(context, "No RTL-SDR detected", 11, UiTheme.MUTED);
    device.setPadding(0, dp(6), 0, 0);
    root.addView(device);

    endpoint = UiTheme.text(context,
        "USB proxy: 127.0.0.1:" + RtlSdrUsbProxyService.TCP_PORT, 11, UiTheme.MUTED);
    endpoint.setPadding(0, dp(4), 0, dp(10));
    root.addView(endpoint);

    LinearLayout actions = new LinearLayout(context);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    connect = UiTheme.actionButton(context, "CONNECT", UiTheme.BLUE, v -> connect());
    stop = UiTheme.actionButton(context, "STOP", UiTheme.SURFACE_RAISED, v -> stop());
    actions.addView(connect, new LinearLayout.LayoutParams(0, dp(44), 1));
    LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(44), 1);
    stopParams.setMargins(dp(8), 0, 0, 0);
    actions.addView(stop, stopParams);
    root.addView(actions);
    refresh();
  }

  View view() { return root; }

  void refresh() {
    RtlSdrUsbManager.State state = manager.refresh();
    device.setText(state.deviceLabel());
    switch (state.status) {
      case OPEN -> {
        status.setText("●  RTL-SDR connected");
        status.setTextColor(UiTheme.GREEN);
        connect.setText("CONNECTED");
        connect.setEnabled(false);
        stop.setEnabled(true);
      }
      case PERMISSION_PENDING -> {
        status.setText("●  Waiting for USB permission…");
        status.setTextColor(UiTheme.BLUE);
        connect.setText("WAITING…");
        connect.setEnabled(false);
        stop.setEnabled(true);
      }
      case DETECTED -> {
        status.setText("●  RTL-SDR detected");
        status.setTextColor(UiTheme.BLUE);
        connect.setText("CONNECT");
        connect.setEnabled(true);
        stop.setEnabled(false);
      }
      case NOT_FOUND -> {
        status.setText("●  No RTL-SDR detected");
        status.setTextColor(UiTheme.MUTED);
        connect.setText("CONNECT");
        connect.setEnabled(false);
        stop.setEnabled(false);
      }
      case ERROR -> {
        status.setText("●  " + state.message);
        status.setTextColor(UiTheme.RED);
        connect.setText("RETRY");
        connect.setEnabled(true);
        stop.setEnabled(true);
      }
    }
  }

  private void connect() {
    manager.open();
    context.startForegroundService(new Intent(context, RtlSdrUsbProxyService.class));
    refresh();
  }

  private void stop() {
    context.stopService(new Intent(context, RtlSdrUsbProxyService.class));
    manager.disconnect();
    refresh();
  }

  private int dp(int value) { return UiTheme.dp(context, value); }
}
