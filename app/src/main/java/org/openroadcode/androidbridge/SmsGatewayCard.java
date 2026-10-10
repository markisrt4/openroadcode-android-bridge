package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openroadcode.androidbridge.runtime.BridgeServiceManager;
import org.openroadcode.androidbridge.ui.UiTheme;

/** SMS permissions and explicit opt-in controls. Does not reveal credentials. */
public final class SmsGatewayCard {
  public static final int PERMISSION_REQUEST = 1082;
  private final Activity activity;
  private final BridgeServiceManager manager;
  private final LinearLayout root;
  private final TextView status;

  public SmsGatewayCard(Activity activity, BridgeServiceManager manager) {
    this.activity = activity;
    this.manager = manager;
    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(UiTheme.dp(activity, 14), UiTheme.dp(activity, 14),
        UiTheme.dp(activity, 14), UiTheme.dp(activity, 14));
    root.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE, UiTheme.BORDER, 12));
    root.addView(UiTheme.text(activity, "SMS GATEWAY", 16, UiTheme.TEXT));
    root.addView(UiTheme.text(activity,
        "SMS only. Explicit permission and opt-in required. Local endpoint: 127.0.0.1:8772",
        12, UiTheme.MUTED));
    status = UiTheme.text(activity, "", 12, UiTheme.MUTED);
    root.addView(status);
    root.addView(UiTheme.actionButton(activity, "Enable SMS gateway", UiTheme.SURFACE_RAISED,
        v -> enable()));
    root.addView(UiTheme.actionButton(activity, "Disable SMS gateway", UiTheme.SURFACE_RAISED,
        v -> {
          manager.stopSmsGateway();
          refresh();
        }));
    refresh();
  }

  public View view() { return root; }

  public void refresh() {
    boolean read = activity.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    boolean send = activity.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    status.setText("Configured: " + (manager.smsEnabled() ? "enabled" : "disabled")
        + "  •  Read permission: " + (read ? "granted" : "missing")
        + "  •  Send permission: " + (send ? "granted" : "missing")
        + "\nService availability and Termux credential provisioning not yet verified.");
  }

  private void enable() {
    boolean read = activity.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    boolean send = activity.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    if (!read || !send) {
      activity.requestPermissions(new String[] {Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS},
          PERMISSION_REQUEST);
      return;
    }
    try {
      manager.startSmsGateway();
    } catch (RuntimeException error) {
      android.widget.Toast.makeText(activity, "Unable to start SMS gateway", android.widget.Toast.LENGTH_LONG).show();
    }
    refresh();
  }
}
