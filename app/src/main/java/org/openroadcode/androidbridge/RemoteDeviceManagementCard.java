package org.openroadcode.androidbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings.RuntimeDevice;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Pairing and saved-device management for remote OpenRoadCode runtimes. */
public final class RemoteDeviceManagementCard {
  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final Runnable onChanged;
  private final LinearLayout root;
  private final TextView selectedDevice;

  public RemoteDeviceManagementCard(Activity activity, Runnable onChanged) {
    this.activity = activity;
    this.onChanged = onChanged;
    settings = new RuntimeServiceManagerSettings(activity);
    root = UiTheme.card(activity);

    selectedDevice = UiTheme.text(activity, "", 12, UiTheme.MUTED);
    selectedDevice.setPadding(0, 0, 0, dp(10));
    root.addView(selectedDevice);

    Button choose = button("CHOOSE PAIRED DEVICE", v -> chooseDevice());
    root.addView(choose, fullButtonParams());

    LinearLayout actions = new LinearLayout(activity);
    actions.setOrientation(LinearLayout.HORIZONTAL);
    Button add = button("+ ADD DEVICE", v -> addDevice());
    Button edit = button("EDIT DEVICE", v -> editActiveDevice());
    Button delete = button("DELETE", v -> deleteActiveDevice());
    actions.addView(add, rowButtonParams(false));
    actions.addView(edit, rowButtonParams(true));
    actions.addView(delete, rowButtonParams(true));
    root.addView(actions);

    refresh();
  }

  public View view() { return root; }

  public void refresh() {
    RuntimeDevice active = settings.activeDevice();
    selectedDevice.setText(active == null
        ? "Selected device: none"
        : "Selected device: " + active.name() + "\n" + active.baseUrl());
  }

  private void chooseDevice() {
    List<RuntimeDevice> devices = settings.devices();
    if (devices.isEmpty()) {
      message("Paired OpenRoadCode devices", "No remote devices are paired yet.");
      return;
    }
    RuntimeDevice active = settings.activeDevice();
    String[] labels = new String[devices.size()];
    for (int i = 0; i < devices.size(); i++) {
      RuntimeDevice device = devices.get(i);
      boolean selected = active != null && active.deviceId().equals(device.deviceId());
      labels[i] = (selected ? "●  " : "") + device.name() + "\n" + device.baseUrl();
    }
    new AlertDialog.Builder(activity)
        .setTitle("Choose paired device")
        .setItems(labels, (dialog, which) -> {
          settings.setActiveDevice(devices.get(which).deviceId());
          changed();
        })
        .setNegativeButton("CLOSE", null)
        .show();
  }

  private void addDevice() {
    LinearLayout fields = fields();
    EditText name = textField("Device name");
    EditText endpoint = textField("http://device-address:8769");
    endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    fields.addView(name);
    fields.addView(endpoint);

    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle("Add OpenRoadCode device")
        .setMessage("Enter the device name and service-manager endpoint. Your browser will open to approve pairing.")
        .setView(fields)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("PAIR IN BROWSER", null)
        .create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(v -> pairInBrowser(dialog, name, endpoint)));
    dialog.show();
  }

  private void pairInBrowser(AlertDialog dialog, EditText name, EditText endpoint) {
    String deviceName = name.getText().toString().trim();
    String baseUrl = endpoint.getText().toString().trim();
    if (deviceName.isBlank()) { name.setError("Enter a device name"); return; }
    if (!validEndpoint(baseUrl)) { endpoint.setError("Enter a complete http:// or https:// endpoint"); return; }

    Button pairButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    pairButton.setEnabled(false);
    pairButton.setText("STARTING…");
    new Thread(() -> {
      try {
        RuntimeServiceManagerClient client = new RuntimeServiceManagerClient(baseUrl, deviceName);
        JSONObject started = client.startBrowserPairing("OpenRoadCode Android - " + Build.MODEL);
        String sessionId = started.optString("session_id", "").trim();
        String pollToken = started.optString("poll_token", "").trim();
        String approvalUrl = started.optString("approval_url", "").trim();
        if (sessionId.isBlank() || pollToken.isBlank() || approvalUrl.isBlank()) {
          throw new IllegalStateException("Service manager returned an incomplete browser pairing session");
        }
        activity.runOnUiThread(() -> {
          pairButton.setText("WAITING…");
          activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(approvalUrl)));
        });
        pollBrowserPairing(client, sessionId, pollToken, deviceName, baseUrl, dialog, pairButton, endpoint);
      } catch (Exception e) {
        pairingFailed(pairButton, endpoint, e);
      }
    }, "orc-browser-pairing").start();
  }

  private void pollBrowserPairing(
      RuntimeServiceManagerClient client,
      String sessionId,
      String pollToken,
      String deviceName,
      String baseUrl,
      AlertDialog dialog,
      Button pairButton,
      EditText endpoint) {
    long deadline = System.currentTimeMillis() + 300_000L;
    try {
      while (System.currentTimeMillis() < deadline && dialog.isShowing()) {
        JSONObject response = client.browserPairingStatus(sessionId, pollToken);
        if ("approved".equals(response.optString("status"))) {
          String token = response.optString("access_token", "").trim();
          String clientId = response.optString("client_id", "").trim();
          if (token.isBlank() || clientId.isBlank()) {
            throw new IllegalStateException("Pairing approval did not contain client credentials");
          }
          activity.runOnUiThread(() -> {
            settings.saveDevice(deviceName, baseUrl, clientId, token);
            dialog.dismiss();
            changed();
          });
          return;
        }
        Thread.sleep(1000L);
      }
      if (dialog.isShowing()) {
        throw new IllegalStateException("Browser pairing expired before approval");
      }
    } catch (Exception e) {
      if (dialog.isShowing()) pairingFailed(pairButton, endpoint, e);
    }
  }

  private void pairingFailed(Button pairButton, EditText endpoint, Exception error) {
    activity.runOnUiThread(() -> {
      pairButton.setEnabled(true);
      pairButton.setText("PAIR IN BROWSER");
      endpoint.setError(error.getMessage() == null ? "Pairing failed" : error.getMessage());
    });
  }

  private void editActiveDevice() {
    RuntimeDevice device = settings.activeDevice();
    if (device == null) { message("Edit device", "Choose or add a remote device first."); return; }

    LinearLayout fields = fields();
    EditText name = textField("Device name");
    name.setText(device.name());
    EditText endpoint = textField("Service-manager endpoint");
    endpoint.setText(device.baseUrl());
    endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    fields.addView(name);
    fields.addView(endpoint);

    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle("Edit " + device.name())
        .setView(fields)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("SAVE", null)
        .create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(v -> {
          String newName = name.getText().toString().trim();
          String newUrl = endpoint.getText().toString().trim();
          if (newName.isBlank()) { name.setError("Enter a device name"); return; }
          if (!validEndpoint(newUrl)) { endpoint.setError("Enter a complete http:// or https:// endpoint"); return; }
          settings.updateDevice(device.deviceId(), newName, newUrl);
          dialog.dismiss();
          changed();
        }));
    dialog.show();
  }

  private void deleteActiveDevice() {
    RuntimeDevice device = settings.activeDevice();
    if (device == null) { message("Delete device", "Choose or add a remote device first."); return; }
    new AlertDialog.Builder(activity)
        .setTitle("Delete " + device.name() + "?")
        .setMessage("This removes the saved pairing from this Android app.")
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("DELETE", (dialog, which) -> {
          settings.forgetDevice(device.deviceId());
          changed();
        })
        .show();
  }

  private void changed() {
    refresh();
    if (onChanged != null) onChanged.run();
  }

  private void message(String title, String body) {
    new AlertDialog.Builder(activity).setTitle(title).setMessage(body)
        .setPositiveButton("CLOSE", null).show();
  }

  private LinearLayout fields() {
    LinearLayout fields = new LinearLayout(activity);
    fields.setOrientation(LinearLayout.VERTICAL);
    fields.setPadding(dp(20), dp(8), dp(20), 0);
    return fields;
  }

  private EditText textField(String hint) {
    EditText field = new EditText(activity);
    field.setSingleLine(true);
    field.setHint(hint);
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    return field;
  }

  private boolean validEndpoint(String value) {
    return value.startsWith("http://") || value.startsWith("https://");
  }

  private Button button(String label, View.OnClickListener listener) {
    Button button = UiTheme.actionButton(activity, label, UiTheme.SURFACE_RAISED, listener);
    button.setTextSize(10);
    return button;
  }

  private LinearLayout.LayoutParams fullButtonParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(44));
    params.setMargins(0, 0, 0, dp(8));
    return params;
  }

  private LinearLayout.LayoutParams rowButtonParams(boolean withLeftMargin) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
    if (withLeftMargin) params.setMargins(dp(5), 0, 0, 0);
    return params;
  }

  private int dp(int value) { return UiTheme.dp(activity, value); }
}
