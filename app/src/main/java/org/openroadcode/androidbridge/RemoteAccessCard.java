package org.openroadcode.androidbridge;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Owns remote sensor-access presentation while MainActivity owns sensor service lifecycle. */
final class RemoteAccessCard {
  interface Listener {
    void onRemoteAccessChanged(boolean enabled);
  }

  private final Activity activity;
  private final LinearLayout view;
  private final TextView status;
  private final Switch remoteAccessSwitch;
  private final Listener listener;
  private boolean binding;

  RemoteAccessCard(Activity activity, boolean enabled, Listener listener) {
    this.activity = activity;
    this.listener = listener;
    view = UiTheme.card(activity);

    TextView heading = UiTheme.text(activity, "REMOTE SENSOR ACCESS", 18, UiTheme.TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.08f);
    view.addView(heading);

    TextView subtitle = UiTheme.text(activity,
        "Share sensor telemetry with devices on this network", 12, UiTheme.BLUE);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, dp(2), 0, dp(10));
    view.addView(subtitle);

    remoteAccessSwitch = new Switch(activity);
    remoteAccessSwitch.setText("Allow network clients");
    remoteAccessSwitch.setTextColor(UiTheme.TEXT);
    remoteAccessSwitch.setTextSize(15);
    remoteAccessSwitch.setPadding(dp(4), dp(4), dp(4), dp(8));
    view.addView(remoteAccessSwitch);

    status = UiTheme.text(activity, "", 13, UiTheme.MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    status.setPadding(dp(10), dp(8), dp(10), dp(8));
    status.setBackground(UiTheme.rounded(activity,
        UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
    statusParams.setMargins(0, 0, 0, dp(8));
    status.setLayoutParams(statusParams);
    view.addView(status);

    setEnabled(enabled);
    remoteAccessSwitch.setOnCheckedChangeListener((buttonView, checked) -> {
      if (!binding) listener.onRemoteAccessChanged(checked);
    });
  }

  View view() {
    return view;
  }

  void setEnabled(boolean enabled) {
    binding = true;
    remoteAccessSwitch.setChecked(enabled);
    binding = false;
  }

  void showStatus(boolean enabled, String address, int port) {
    if (!enabled) {
      status.setText("●  Disabled • localhost only • 127.0.0.1:" + port);
      status.setTextColor(UiTheme.MUTED);
      return;
    }
    status.setText(address == null
        ? "●  Enabled • waiting for a network address • port " + port
        : "●  Enabled • http://" + address + ":" + port);
    status.setTextColor(address == null ? UiTheme.BLUE : UiTheme.GREEN);
  }

  private int dp(int value) {
    return UiTheme.dp(activity, value);
  }
}
