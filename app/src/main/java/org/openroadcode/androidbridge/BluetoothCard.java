package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Owns the Bluetooth SPP controls and lifecycle for the bridge dashboard. */
final class BluetoothCard {
  static final int PERMISSION_REQUEST = 1002;

  private static final int SURFACE = Color.rgb(11, 24, 33);
  private static final int SURFACE_RAISED = Color.rgb(16, 34, 46);
  private static final int BORDER = Color.rgb(36, 64, 79);
  private static final int TEXT = Color.rgb(243, 247, 249);
  private static final int MUTED = Color.rgb(147, 164, 174);
  private static final int BLUE = Color.rgb(22, 139, 209);
  private static final int GREEN = Color.rgb(132, 206, 31);
  private static final int RED = Color.rgb(241, 90, 22);

  private final Activity activity;
  private final LinearLayout view;
  private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
  private final TextView status;
  private final Spinner deviceSpinner;
  private final Button startButton;
  private final Button stopButton;
  private boolean requestedRunning;
  private boolean receiverRegistered;

  private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (!BluetoothSppBridgeService.ACTION_STATUS.equals(intent.getAction()))
        return;
      String state = intent.getStringExtra(BluetoothSppBridgeService.EXTRA_STATUS);
      String message = intent.getStringExtra(BluetoothSppBridgeService.EXTRA_MESSAGE);
      if (message == null || message.isEmpty())
        message = "Bluetooth bridge status unavailable";
      if (BluetoothSppBridgeService.STATUS_CONNECTING.equals(state)) {
        requestedRunning = true;
        updateButtons(true, false);
        setStatus(message, BLUE);
      } else if (BluetoothSppBridgeService.STATUS_CONNECTED.equals(state)) {
        requestedRunning = true;
        updateButtons(true, true);
        setStatus(message, GREEN);
      } else if (BluetoothSppBridgeService.STATUS_ERROR.equals(state)) {
        requestedRunning = false;
        updateButtons(false, false);
        setStatus(message, RED);
      } else if (BluetoothSppBridgeService.STATUS_STOPPED.equals(state)) {
        requestedRunning = false;
        updateButtons(false, false);
        setStatus(message, MUTED);
      }
    }
  };

  BluetoothCard(Activity activity) {
    this.activity = activity;
    view = new LinearLayout(activity);
    view.setOrientation(LinearLayout.VERTICAL);
    view.setPadding(dp(12), dp(16), dp(12), dp(16));
    view.setBackground(rounded(SURFACE, BORDER, 14));

    TextView heading = text("BLUETOOTH SPP", 18, TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.08f);
    view.addView(heading);
    TextView subtitle = text("Classic Bluetooth • RFCOMM transport", 12, GREEN);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, dp(2), 0, dp(10));
    view.addView(subtitle);

    status = text("●  Tap REFRESH to load paired devices", 13, MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    status.setPadding(dp(10), dp(8), dp(10), dp(8));
    status.setBackground(rounded(SURFACE_RAISED, BORDER, 9));
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
    statusParams.setMargins(0, 0, 0, dp(8));
    status.setLayoutParams(statusParams);
    view.addView(status);

    deviceSpinner = new Spinner(activity);
    deviceSpinner.setBackground(rounded(SURFACE_RAISED, BORDER, 10));
    deviceSpinner.setPadding(dp(10), 0, dp(10), 0);
    view.addView(deviceSpinner, new LinearLayout.LayoutParams(-1, dp(52)));

    Button refreshButton = actionButton("REFRESH", BLUE, ignored -> ensurePermissionAndLoad());
    startButton = actionButton("START SPP", BLUE, ignored -> startBridge());
    stopButton = actionButton("STOP", SURFACE_RAISED, ignored -> stopBridge());
    view.addView(buttonRow(refreshButton, startButton, stopButton));

    TextView hint = text("TCP endpoint  127.0.0.1:35000", 12, MUTED);
    hint.setTypeface(Typeface.MONOSPACE);
    hint.setPadding(dp(2), dp(10), 0, 0);
    view.addView(hint);
  }

  View view() {
    return view;
  }

  void start() {
    if (!receiverRegistered) {
      activity.registerReceiver(statusReceiver,
          new IntentFilter(BluetoothSppBridgeService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
      receiverRegistered = true;
    }
    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        == PackageManager.PERMISSION_GRANTED)
      loadPairedDevices();
  }

  void stop() {
    if (receiverRegistered) {
      activity.unregisterReceiver(statusReceiver);
      receiverRegistered = false;
    }
  }

  boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
    if (requestCode != PERMISSION_REQUEST)
      return false;
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
      loadPairedDevices();
    else
      setStatus("Bluetooth permission required", RED);
    return true;
  }

  private void ensurePermissionAndLoad() {
    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
      return;
    }
    loadPairedDevices();
  }

  private void loadPairedDevices() {
    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED)
      return;
    BluetoothManager manager = activity.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    pairedDevices.clear();
    List<String> labels = new ArrayList<>();
    if (adapter != null) {
      for (BluetoothDevice device : adapter.getBondedDevices()) {
        pairedDevices.add(device);
        String name = device.getName();
        labels.add((name == null ? "Unknown device" : name) + "  •  " + device.getAddress());
      }
    }
    ArrayAdapter<String> spinnerAdapter =
        new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, labels) {
          @Override
          public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView value = (TextView) super.getView(position, convertView, parent);
            value.setTextColor(TEXT);
            value.setTextSize(13);
            return value;
          }

          @Override
          public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
            TextView value = (TextView) super.getDropDownView(position, convertView, parent);
            value.setTextColor(TEXT);
            value.setBackgroundColor(SURFACE_RAISED);
            value.setPadding(dp(12), dp(12), dp(12), dp(12));
            return value;
          }
        };
    deviceSpinner.setAdapter(spinnerAdapter);
    if (!requestedRunning)
      setStatus(labels.isEmpty() ? "No paired classic Bluetooth devices"
                                 : labels.size() + " paired device(s) available",
          labels.isEmpty() ? RED : GREEN);
  }

  private void startBridge() {
    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) {
      ensurePermissionAndLoad();
      return;
    }
    int position = deviceSpinner.getSelectedItemPosition();
    if (position < 0 || position >= pairedDevices.size()) {
      setStatus("Select a paired Bluetooth device first", RED);
      return;
    }
    BluetoothDevice device = pairedDevices.get(position);
    Intent intent = new Intent(activity, BluetoothSppBridgeService.class);
    intent.putExtra(BluetoothSppBridgeService.EXTRA_DEVICE_ADDRESS, device.getAddress());
    requestedRunning = true;
    updateButtons(true, false);
    activity.startForegroundService(intent);
    setStatus("Connecting to "
        + (device.getName() == null ? device.getAddress() : device.getName()) + "…", BLUE);
  }

  private void stopBridge() {
    requestedRunning = false;
    updateButtons(false, false);
    activity.stopService(new Intent(activity, BluetoothSppBridgeService.class));
    setStatus("Bluetooth bridge stopped", MUTED);
  }

  private void updateButtons(boolean running, boolean connected) {
    startButton.setText(running ? (connected ? "RUNNING" : "CONNECTING") : "START SPP");
    setButtonColor(startButton, running ? SURFACE_RAISED : BLUE);
    setButtonColor(stopButton, running ? RED : SURFACE_RAISED);
  }

  private void setStatus(String message, int color) {
    status.setText("●  " + message);
    status.setTextColor(color);
  }

  private Button actionButton(String label, int color, View.OnClickListener listener) {
    Button button = new Button(activity);
    button.setText(label);
    button.setTextColor(TEXT);
    button.setTextSize(11);
    button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    button.setLetterSpacing(.08f);
    button.setAllCaps(false);
    setButtonColor(button, color);
    button.setOnClickListener(listener);
    return button;
  }

  private LinearLayout buttonRow(Button... buttons) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER);
    row.setPadding(0, dp(8), 0, 0);
    for (Button button : buttons) {
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
      params.setMargins(dp(3), 0, dp(3), 0);
      row.addView(button, params);
    }
    return row;
  }

  private TextView text(String value, float size, int color) {
    TextView text = new TextView(activity);
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(color);
    return text;
  }

  private void setButtonColor(Button button, int color) {
    button.setBackground(rounded(color, color, 9));
  }

  private GradientDrawable rounded(int fill, int stroke, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(fill);
    drawable.setCornerRadius(dp(radius));
    drawable.setStroke(dp(1), stroke);
    return drawable;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }
}
