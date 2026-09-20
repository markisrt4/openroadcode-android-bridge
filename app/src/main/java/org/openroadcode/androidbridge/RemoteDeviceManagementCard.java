package org.openroadcode.androidbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Persistent remote runtime device pairing and connection configuration. */
public final class RemoteDeviceManagementCard {
  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final LinearLayout root;
  private final TextView status;
  private final TextView endpoint;

  public RemoteDeviceManagementCard(Activity activity) {
    this.activity = activity;
    settings = new RuntimeServiceManagerSettings(activity);
    root = UiTheme.card(activity);

    TextView title = UiTheme.text(activity, "REMOTE DEVICE MANAGEMENT", 18, UiTheme.TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setLetterSpacing(.05f);
    root.addView(title);

    TextView help = UiTheme.text(activity,
        "Pair and manage the remote Linux service manager available to Runtime.",
        11, UiTheme.MUTED);
    help.setPadding(0, dp(2), 0, dp(10));
    root.addView(help);

    endpoint = UiTheme.text(activity, "", 12, UiTheme.TEXT);
    endpoint.setPadding(dp(10), dp(9), dp(10), dp(9));
    endpoint.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
    root.addView(endpoint);

    status = UiTheme.text(activity, "", 12, UiTheme.MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    status.setPadding(0, dp(8), 0, dp(8));
    root.addView(status);

    Button pair = UiTheme.actionButton(activity, "PAIR / EDIT REMOTE LINUX", UiTheme.BLUE,
        v -> configure());
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44));
    root.addView(pair, p);
    refresh();
  }

  public View view() { return root; }

  private void refresh() {
    String url = settings.piBaseUrl();
    endpoint.setText(url.isBlank() ? "Remote Linux: not configured" : "Remote Linux: " + url);
    status.setText(settings.hasRemotePiConfiguration() ? "●  Paired credential stored" : "○  Not paired");
    status.setTextColor(settings.hasRemotePiConfiguration() ? UiTheme.GREEN : UiTheme.MUTED);
  }

  private void configure() {
    LinearLayout fields = new LinearLayout(activity);
    fields.setOrientation(LinearLayout.VERTICAL);
    fields.setPadding(dp(20), dp(8), dp(20), 0);

    EditText url = new EditText(activity);
    url.setSingleLine(true);
    url.setHint("http://pi-address:8769");
    url.setText(settings.piBaseUrl());
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    fields.addView(url);

    EditText pin = new EditText(activity);
    pin.setSingleLine(true);
    pin.setHint("6-digit pairing PIN");
    pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    fields.addView(pin);

    TextView help = UiTheme.text(activity,
        "Start pairing on the OpenRoadCode service manager, then enter its temporary PIN. "
            + "The PIN is exchanged for this Android client's credential and is not saved.",
        10, UiTheme.MUTED);
    help.setPadding(0, dp(4), 0, dp(4));
    fields.addView(help);

    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle("Pair remote Linux service manager")
        .setView(fields)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("PAIR", null)
        .create();

    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(v -> {
          String baseUrl = url.getText().toString().trim();
          String pairingPin = pin.getText().toString().trim();
          if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            url.setError("Enter a complete http:// or https:// endpoint");
            return;
          }
          if (!pairingPin.matches("\\d{6}")) {
            pin.setError("Enter the 6-digit pairing PIN");
            return;
          }
          Button button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
          button.setEnabled(false);
          button.setText("PAIRING…");
          new Thread(() -> {
            try {
              RuntimeServiceManagerClient client = new RuntimeServiceManagerClient(baseUrl, "Remote Linux");
              JSONObject response = client.pair(pairingPin, "OpenRoadCode Android - " + Build.MODEL);
              String token = response.optString("access_token", "").trim();
              if (token.isBlank()) throw new IllegalStateException("Pairing response contained no access token");
              activity.runOnUiThread(() -> {
                settings.setPiBaseUrl(baseUrl);
                settings.setPiToken(token);
                refresh();
                dialog.dismiss();
              });
            } catch (Exception e) {
              String message = e.getMessage();
              activity.runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("PAIR");
                pin.setError(message == null || message.isBlank() ? "Pairing failed" : message);
              });
            }
          }, "orc-service-pairing").start();
        }));
    dialog.show();
  }

  private int dp(int value) { return UiTheme.dp(activity, value); }
}
