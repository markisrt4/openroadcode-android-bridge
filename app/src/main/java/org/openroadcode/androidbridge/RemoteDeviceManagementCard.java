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
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings.RuntimeDevice;
import java.util.List;
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

    Button select = UiTheme.actionButton(activity, "SELECT REMOTE", UiTheme.SURFACE_RAISED,
        v -> selectRemoteDevice());
    LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(-1, dp(44));
    selectParams.setMargins(0, 0, 0, dp(8));
    root.addView(select, selectParams);

    Button pair = UiTheme.actionButton(activity, "ADD REMOTE", UiTheme.BLUE, v -> configure());
    Button edit = UiTheme.actionButton(activity, "EDIT REMOTE", UiTheme.SURFACE_RAISED,
        v -> editActiveDevice());
    Button delete = UiTheme.actionButton(activity, "DELETE REMOTE", UiTheme.RED,
        v -> deleteActiveDevice());

    LinearLayout primaryActions = new LinearLayout(activity);
    primaryActions.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(44), 1);
    left.setMargins(0, 0, dp(4), 0);
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(44), 1);
    right.setMargins(dp(4), 0, 0, 0);
    primaryActions.addView(pair, left);
    primaryActions.addView(edit, right);
    root.addView(primaryActions);

    LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(-1, dp(44));
    deleteParams.setMargins(0, dp(8), 0, 0);
    root.addView(delete, deleteParams);

    boolean haveDevice = settings.activeDevice() != null;
    edit.setEnabled(haveDevice);
    delete.setEnabled(haveDevice);
    UiTheme.setButtonColor(activity, edit,
        haveDevice ? UiTheme.SURFACE_RAISED : UiTheme.DISABLED);
    UiTheme.setButtonColor(activity, delete,
        haveDevice ? UiTheme.RED : UiTheme.DISABLED);
    refresh();
  }

  public View view() { return root; }

  private void refresh() {
    List<RuntimeDevice> devices = settings.devices();
    RuntimeDevice active = settings.activeDevice();
    endpoint.setText(devices.isEmpty()
        ? "No remote devices configured"
        : devices.size() + " paired device" + (devices.size() == 1 ? "" : "s")
            + (active == null ? "" : " • active: " + active.name()));
    status.setText(devices.isEmpty() ? "○  Not paired" : "●  Paired credentials stored");
    status.setTextColor(devices.isEmpty() ? UiTheme.MUTED : UiTheme.GREEN);
  }


  private void selectRemoteDevice() {
    List<RuntimeDevice> devices = settings.devices();
    if (devices.isEmpty()) {
      showDevices();
      return;
    }
    RuntimeDevice active = settings.activeDevice();
    String[] labels = new String[devices.size()];
    int checked = -1;
    for (int i = 0; i < devices.size(); i++) {
      RuntimeDevice device = devices.get(i);
      labels[i] = device.name() + "\n" + device.baseUrl();
      if (active != null && active.deviceId().equals(device.deviceId())) checked = i;
    }
    new AlertDialog.Builder(activity)
        .setTitle("Select remote device")
        .setSingleChoiceItems(labels, checked, (dialog, which) -> {
          settings.setActiveDevice(devices.get(which).deviceId());
          refresh();
          dialog.dismiss();
        })
        .setNegativeButton("CANCEL", null)
        .show();
  }

  private void editActiveDevice() {
    RuntimeDevice active = settings.activeDevice();
    if (active == null) {
      showDevices();
      return;
    }
    editDevice(active);
  }

  private void deleteActiveDevice() {
    RuntimeDevice active = settings.activeDevice();
    if (active == null) {
      showDevices();
      return;
    }
    confirmForget(active);
  }

  private void showDevices() {
    List<RuntimeDevice> devices = settings.devices();
    if (devices.isEmpty()) {
      new AlertDialog.Builder(activity)
          .setTitle("Paired OpenRoadCode devices")
          .setMessage("No remote devices are paired yet.")
          .setPositiveButton("CLOSE", null)
          .show();
      return;
    }
    RuntimeDevice active = settings.activeDevice();
    String[] labels = new String[devices.size()];
    for (int i = 0; i < devices.size(); i++) {
      RuntimeDevice device = devices.get(i);
      String selected = active != null && active.deviceId().equals(device.deviceId()) ? "●  " : "";
      labels[i] = selected + device.name() + "\n" + device.baseUrl();
    }
    new AlertDialog.Builder(activity)
        .setTitle("Paired OpenRoadCode devices")
        .setItems(labels, (dialog, which) -> showDeviceActions(devices.get(which)))
        .setNegativeButton("CLOSE", null)
        .show();
  }

  private void showDeviceActions(RuntimeDevice device) {
    String[] actions = {"EDIT", "FORGET"};
    new AlertDialog.Builder(activity)
        .setTitle(device.name())
        .setMessage(device.baseUrl())
        .setItems(actions, (dialog, which) -> {
          if (which == 0) editDevice(device);
          else confirmForget(device);
        })
        .setNegativeButton("CLOSE", null)
        .show();
  }

  private void editDevice(RuntimeDevice device) {
    LinearLayout fields = new LinearLayout(activity);
    fields.setOrientation(LinearLayout.VERTICAL);
    fields.setPadding(dp(20), dp(8), dp(20), 0);
    EditText name = new EditText(activity);
    name.setSingleLine(true);
    name.setText(device.name());
    fields.addView(name);
    EditText url = new EditText(activity);
    url.setSingleLine(true);
    url.setText(device.baseUrl());
    url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    fields.addView(url);
    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle("Edit device")
        .setView(fields)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("SAVE", null)
        .create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(v -> {
          String newName = name.getText().toString().trim();
          String newUrl = url.getText().toString().trim();
          if (newName.isBlank()) {
            name.setError("Enter a device name");
            return;
          }
          if (!(newUrl.startsWith("http://") || newUrl.startsWith("https://"))) {
            url.setError("Enter a complete http:// or https:// endpoint");
            return;
          }
          settings.updateDevice(device.deviceId(), newName, newUrl);
          refresh();
          dialog.dismiss();
          showDevices();
        }));
    dialog.show();
  }

  private void confirmForget(RuntimeDevice device) {
    new AlertDialog.Builder(activity)
        .setTitle("Forget " + device.name() + "?")
        .setMessage("This removes the saved pairing from this Android app.")
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("FORGET", (dialog, which) -> {
          settings.forgetDevice(device.deviceId());
          refresh();
          showDevices();
        })
        .show();
  }

  private void configure() {
    LinearLayout fields = new LinearLayout(activity);
    fields.setOrientation(LinearLayout.VERTICAL);
    fields.setPadding(dp(20), dp(8), dp(20), 0);

    EditText deviceName = new EditText(activity);
    deviceName.setSingleLine(true);
    deviceName.setHint("Device name (for example, Car Pi 5)");
    deviceName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    fields.addView(deviceName);

    EditText url = new EditText(activity);
    url.setSingleLine(true);
    url.setHint("http://pi-address:8769");
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
          String name = deviceName.getText().toString().trim();
          String baseUrl = url.getText().toString().trim();
          String pairingPin = pin.getText().toString().trim();
          if (name.isBlank()) {
            deviceName.setError("Enter a name for this device");
            return;
          }
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
              String clientId = response.optString("client_id", "").trim();
              if (token.isBlank()) throw new IllegalStateException("Pairing response contained no access token");
              activity.runOnUiThread(() -> {
                settings.saveDevice(name, baseUrl, clientId, token);
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
