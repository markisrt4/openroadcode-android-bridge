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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.openroadcode.androidbridge.config.ServiceProvider;
import org.openroadcode.androidbridge.runtime.BridgeServiceManager;

/** Owns vehicle-provider controls and delegates lifecycle to BridgeServiceManager. */
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
  private final BridgeServiceManager serviceManager;
  private final LinearLayout view;
  private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
  private final TextView status;
  private final Spinner providerSpinner;
  private final Spinner deviceSpinner;
  private final Button refreshButton;
  private final Button startButton;
  private final Button stopButton;
  private boolean requestedRunning;
  private boolean receiverRegistered;
  private boolean bindingProvider;
  private boolean bindingDevice;
  private boolean restoredRequestedVehicle;

  private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();
      ServiceProvider provider = serviceManager.vehicleConfig().provider();
      boolean physical = BluetoothSppBridgeService.ACTION_STATUS.equals(action);
      boolean simulated = SimulatedVehicleBridgeService.ACTION_STATUS.equals(action);
      if (!physical && !simulated) return;
      if (physical && provider != ServiceProvider.BLUETOOTH_SPP) return;
      if (simulated && provider != ServiceProvider.SIMULATED_VEHICLE) return;

      String state = intent.getStringExtra(physical
          ? BluetoothSppBridgeService.EXTRA_STATUS : SimulatedVehicleBridgeService.EXTRA_STATUS);
      String message = intent.getStringExtra(physical
          ? BluetoothSppBridgeService.EXTRA_MESSAGE : SimulatedVehicleBridgeService.EXTRA_MESSAGE);
      if (message == null || message.isEmpty()) message = "Vehicle bridge status unavailable";

      boolean starting = physical
          ? BluetoothSppBridgeService.STATUS_CONNECTING.equals(state)
          : SimulatedVehicleBridgeService.STATUS_STARTING.equals(state);
      boolean running = physical
          ? BluetoothSppBridgeService.STATUS_CONNECTED.equals(state)
          : SimulatedVehicleBridgeService.STATUS_RUNNING.equals(state);
      boolean error = physical
          ? BluetoothSppBridgeService.STATUS_ERROR.equals(state)
          : SimulatedVehicleBridgeService.STATUS_ERROR.equals(state);
      boolean stopped = physical
          ? BluetoothSppBridgeService.STATUS_STOPPED.equals(state)
          : SimulatedVehicleBridgeService.STATUS_STOPPED.equals(state);

      if (starting) {
        requestedRunning = true;
        serviceManager.markVehicleStarting();
        updateButtons(true, false);
        setStatus(message, BLUE);
      } else if (running) {
        requestedRunning = true;
        serviceManager.markVehicleRunning();
        updateButtons(true, true);
        setStatus(message, GREEN);
      } else if (error) {
        requestedRunning = false;
        serviceManager.markVehicleError();
        updateButtons(false, false);
        setStatus(message, RED);
      } else if (stopped) {
        requestedRunning = false;
        serviceManager.markVehicleStopped();
        updateButtons(false, false);
        setStatus(message, MUTED);
      }
    }
  };

  BluetoothCard(Activity activity, BridgeServiceManager serviceManager) {
    this.activity = activity;
    this.serviceManager = serviceManager;
    requestedRunning = serviceManager.vehicleRequested();

    view = new LinearLayout(activity);
    view.setOrientation(LinearLayout.VERTICAL);
    view.setPadding(dp(12), dp(16), dp(12), dp(16));
    view.setBackground(rounded(SURFACE, BORDER, 14));

    TextView heading = text("VEHICLE BRIDGE", 18, TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.08f);
    view.addView(heading);
    TextView subtitle = text("Selectable OBD source • shared TCP transport", 12, GREEN);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, dp(2), 0, dp(10));
    view.addView(subtitle);

    status = text(requestedRunning
        ? "●  Vehicle bridge requested"
        : "●  Vehicle bridge stopped", 13, requestedRunning ? BLUE : MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    status.setPadding(dp(10), dp(8), dp(10), dp(8));
    status.setBackground(rounded(SURFACE_RAISED, BORDER, 9));
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
    statusParams.setMargins(0, 0, 0, dp(8));
    status.setLayoutParams(statusParams);
    view.addView(status);

    TextView providerLabel = text("VEHICLE SOURCE", 11, MUTED);
    providerLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    providerLabel.setLetterSpacing(.10f);
    providerLabel.setPadding(dp(2), dp(4), 0, dp(4));
    view.addView(providerLabel);

    providerSpinner = new Spinner(activity);
    providerSpinner.setBackground(rounded(SURFACE_RAISED, BORDER, 10));
    providerSpinner.setPadding(dp(10), 0, dp(10), 0);
    ArrayAdapter<String> providerAdapter = darkAdapter(
        new String[] {ServiceProvider.BLUETOOTH_SPP.displayName(), ServiceProvider.SIMULATED_VEHICLE.displayName()});
    providerSpinner.setAdapter(providerAdapter);
    bindingProvider = true;
    providerSpinner.setSelection(serviceManager.vehicleConfig().provider() == ServiceProvider.SIMULATED_VEHICLE ? 1 : 0);
    bindingProvider = false;
    providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
        if (bindingProvider) return;
        ServiceProvider provider = position == 1
            ? ServiceProvider.SIMULATED_VEHICLE : ServiceProvider.BLUETOOTH_SPP;
        selectProvider(provider);
      }
      @Override public void onNothingSelected(AdapterView<?> parent) { }
    });
    view.addView(providerSpinner, new LinearLayout.LayoutParams(-1, dp(52)));

    TextView deviceLabel = text("PAIRED OBD DEVICE", 11, MUTED);
    deviceLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    deviceLabel.setLetterSpacing(.10f);
    deviceLabel.setPadding(dp(2), dp(8), 0, dp(4));
    view.addView(deviceLabel);

    deviceSpinner = new Spinner(activity);
    deviceSpinner.setBackground(rounded(SURFACE_RAISED, BORDER, 10));
    deviceSpinner.setPadding(dp(10), 0, dp(10), 0);
    deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(AdapterView<?> parent, View selected, int position, long id) {
        if (bindingDevice || position < 0 || position >= pairedDevices.size()) return;
        serviceManager.setVehicleDeviceAddress(pairedDevices.get(position).getAddress());
      }
      @Override public void onNothingSelected(AdapterView<?> parent) { }
    });
    view.addView(deviceSpinner, new LinearLayout.LayoutParams(-1, dp(52)));

    refreshButton = actionButton("REFRESH", BLUE, ignored -> ensurePermissionAndLoad());
    startButton = actionButton("START", BLUE, ignored -> startBridge());
    stopButton = actionButton("STOP", SURFACE_RAISED, ignored -> stopBridge());
    view.addView(buttonRow(refreshButton, startButton, stopButton));

    TextView hint = text("TCP endpoint  127.0.0.1:" + BluetoothSppBridgeService.TCP_PORT,
        12, MUTED);
    hint.setTypeface(Typeface.MONOSPACE);
    hint.setPadding(dp(2), dp(10), 0, 0);
    view.addView(hint);

    updateProviderUi();
    updateButtons(requestedRunning, false);
  }

  View view() { return view; }

  void start() {
    if (!receiverRegistered) {
      IntentFilter filter = new IntentFilter();
      filter.addAction(BluetoothSppBridgeService.ACTION_STATUS);
      filter.addAction(SimulatedVehicleBridgeService.ACTION_STATUS);
      activity.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
      receiverRegistered = true;
    }

    ServiceProvider provider = serviceManager.vehicleConfig().provider();
    if (provider == ServiceProvider.BLUETOOTH_SPP
        && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED) {
      loadPairedDevices();
    }

    if (!restoredRequestedVehicle && serviceManager.vehicleRequested()) {
      restoredRequestedVehicle = true;
      if (provider == ServiceProvider.SIMULATED_VEHICLE) {
        requestedRunning = true;
        updateButtons(true, false);
        serviceManager.startRequestedVehicle();
        setStatus("Restoring simulated vehicle…", BLUE);
      } else if (provider == ServiceProvider.BLUETOOTH_SPP
          && activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
              == PackageManager.PERMISSION_GRANTED
          && serviceManager.vehicleDeviceAddress() != null) {
        requestedRunning = true;
        updateButtons(true, false);
        serviceManager.startRequestedVehicle();
        setStatus("Restoring Bluetooth OBD-II connection…", BLUE);
      }
    }
  }

  void stop() {
    if (receiverRegistered) {
      activity.unregisterReceiver(statusReceiver);
      receiverRegistered = false;
    }
  }

  boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
    if (requestCode != PERMISSION_REQUEST) return false;
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      loadPairedDevices();
      if (serviceManager.vehicleRequested() && serviceManager.vehicleDeviceAddress() != null) {
        requestedRunning = true;
        serviceManager.startRequestedVehicle();
        updateButtons(true, false);
        setStatus("Restoring Bluetooth OBD-II connection…", BLUE);
      }
    } else {
      setStatus("Bluetooth permission required for OBD-II SPP", RED);
    }
    return true;
  }

  private void selectProvider(ServiceProvider provider) {
    ServiceProvider current = serviceManager.vehicleConfig().provider();
    if (current == provider) return;
    boolean wasRequested = serviceManager.vehicleRequested();
    serviceManager.suspendVehicle();
    serviceManager.setVehicleProvider(provider);
    requestedRunning = false;
    updateProviderUi();
    updateButtons(false, false);

    if (provider == ServiceProvider.SIMULATED_VEHICLE && wasRequested) {
      requestedRunning = true;
      serviceManager.startRequestedVehicle();
      updateButtons(true, false);
      setStatus("Starting simulated vehicle…", BLUE);
    } else if (provider == ServiceProvider.BLUETOOTH_SPP) {
      setStatus("Bluetooth OBD-II (SPP) selected • choose a paired device", BLUE);
      if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
          == PackageManager.PERMISSION_GRANTED) loadPairedDevices();
    }
  }

  private void updateProviderUi() {
    boolean physical = serviceManager.vehicleConfig().provider() == ServiceProvider.BLUETOOTH_SPP;
    deviceSpinner.setEnabled(physical);
    refreshButton.setEnabled(physical);
    refreshButton.setAlpha(physical ? 1.0f : 0.45f);
    deviceSpinner.setAlpha(physical ? 1.0f : 0.45f);
  }

  private void ensurePermissionAndLoad() {
    if (serviceManager.vehicleConfig().provider() != ServiceProvider.BLUETOOTH_SPP) return;
    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQUEST);
      return;
    }
    loadPairedDevices();
  }

  private void loadPairedDevices() {
    if (serviceManager.vehicleConfig().provider() != ServiceProvider.BLUETOOTH_SPP) return;
    if (activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) return;
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

    bindingDevice = true;
    deviceSpinner.setAdapter(darkAdapter(labels.toArray(new String[0])));
    String selectedAddress = serviceManager.vehicleDeviceAddress();
    if (selectedAddress != null) {
      for (int index = 0; index < pairedDevices.size(); index++) {
        if (selectedAddress.equalsIgnoreCase(pairedDevices.get(index).getAddress())) {
          deviceSpinner.setSelection(index);
          break;
        }
      }
    }
    bindingDevice = false;

    if (!requestedRunning) {
      setStatus(labels.isEmpty() ? "No paired classic Bluetooth devices"
                                 : labels.size() + " paired device(s) available",
          labels.isEmpty() ? RED : GREEN);
    }
  }

  private void startBridge() {
    ServiceProvider provider = serviceManager.vehicleConfig().provider();
    serviceManager.requestVehicleEnabled();

    if (provider == ServiceProvider.SIMULATED_VEHICLE) {
      requestedRunning = true;
      updateButtons(true, false);
      serviceManager.startRequestedVehicle();
      setStatus("Starting simulated vehicle…", BLUE);
      return;
    }

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
    serviceManager.setVehicleDeviceAddress(device.getAddress());
    requestedRunning = true;
    updateButtons(true, false);
    serviceManager.startRequestedVehicle();
    setStatus("Connecting to "
        + (device.getName() == null ? device.getAddress() : device.getName()) + "…", BLUE);
  }

  private void stopBridge() {
    requestedRunning = false;
    serviceManager.disableVehicle();
    updateButtons(false, false);
    setStatus("Vehicle bridge stopped", MUTED);
  }

  private void updateButtons(boolean running, boolean connected) {
    boolean simulated = serviceManager.vehicleConfig().provider() == ServiceProvider.SIMULATED_VEHICLE;
    String idle = simulated ? "START SIM" : "START SPP";
    String pending = simulated ? "STARTING" : "CONNECTING";
    startButton.setText(running ? (connected ? "RUNNING" : pending) : idle);
    setButtonColor(startButton, running ? SURFACE_RAISED : BLUE);
    setButtonColor(stopButton, running ? RED : SURFACE_RAISED);
  }

  private ArrayAdapter<String> darkAdapter(String[] labels) {
    return new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, labels) {
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
