package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final int BLUETOOTH_PERMISSION_REQUEST = 1002;
    private static final long DASHBOARD_PERIOD_MS = 500;
    private static final String IMU_URL = "http://127.0.0.1:8766/imu";
    private static final String LOCATION_URL = "http://127.0.0.1:8766/location";

    private final Handler dashboardHandler = new Handler(Looper.getMainLooper());
    private final Runnable dashboardRefresh = new Runnable() {
        @Override public void run() { refreshDashboard(); dashboardHandler.postDelayed(this, DASHBOARD_PERIOD_MS); }
    };
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();

    private TextView status, bluetoothStatus, accelerometerValue, linearAccelerationValue, gyroscopeValue, magnetometerValue, pressureValue, positionValue;
    private Spinner bluetoothDeviceSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this); title.setText("OpenRoadCode Sensor Bridge"); title.setTextSize(24); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); content.addView(title);
        TextView version = new TextView(this); version.setText(String.format(Locale.US, "Version %s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)); version.setTextSize(12); version.setPadding(0, dp(2), 0, dp(2)); content.addView(version);
        status = new TextView(this); status.setText("Bridge stopped"); status.setTextSize(14); status.setPadding(0, dp(4), 0, dp(14)); content.addView(status);
        accelerometerValue = addSensorRow(content, "↗", "Accelerometer", "m/s²");
        linearAccelerationValue = addSensorRow(content, "⇢", "Linear acceleration", "m/s²");
        gyroscopeValue = addSensorRow(content, "↻", "Gyroscope", "rad/s");
        magnetometerValue = addSensorRow(content, "⌖", "Magnetometer", "µT");
        pressureValue = addSensorRow(content, "◉", "Barometer", "hPa");
        positionValue = addSensorRow(content, "◎", "Position", "lat / lon • accuracy");
        TextView hint = new TextView(this); hint.setText("Dashboard refresh: 2 Hz  •  Local API: 127.0.0.1:8766"); hint.setTextSize(12); hint.setPadding(0, dp(14), 0, dp(8)); content.addView(hint);
        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        Button start = new Button(this); start.setText("Start bridge"); start.setOnClickListener(v -> startBridge()); buttons.addView(start, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button stop = new Button(this); stop.setText("Stop bridge"); stop.setOnClickListener(v -> { stopService(new Intent(this, SensorBridgeService.class)); status.setText("Bridge stopped"); showUnavailable(); }); buttons.addView(stop, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(buttons);

        TextView bluetoothTitle = new TextView(this); bluetoothTitle.setText("Bluetooth SPP Bridge"); bluetoothTitle.setTextSize(20); bluetoothTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD); bluetoothTitle.setPadding(0, dp(24), 0, dp(6)); content.addView(bluetoothTitle);
        bluetoothStatus = new TextView(this); bluetoothStatus.setText("Select a paired device"); bluetoothStatus.setTextSize(13); content.addView(bluetoothStatus);
        bluetoothDeviceSpinner = new Spinner(this); content.addView(bluetoothDeviceSpinner);
        LinearLayout bluetoothButtons = new LinearLayout(this); bluetoothButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button refreshBluetooth = new Button(this); refreshBluetooth.setText("Refresh devices"); refreshBluetooth.setOnClickListener(v -> loadPairedDevices()); bluetoothButtons.addView(refreshBluetooth, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button startBluetooth = new Button(this); startBluetooth.setText("Start SPP"); startBluetooth.setOnClickListener(v -> startBluetoothBridge()); bluetoothButtons.addView(startBluetooth, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button stopBluetooth = new Button(this); stopBluetooth.setText("Stop SPP"); stopBluetooth.setOnClickListener(v -> { stopService(new Intent(this, BluetoothSppBridgeService.class)); bluetoothStatus.setText("Bluetooth bridge stopped"); }); bluetoothButtons.addView(stopBluetooth, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(bluetoothButtons);
        TextView bluetoothHint = new TextView(this); bluetoothHint.setText("Paired classic Bluetooth → RFCOMM/SPP → TCP 127.0.0.1:35000"); bluetoothHint.setTextSize(12); bluetoothHint.setPadding(0, dp(4), 0, dp(8)); content.addView(bluetoothHint);

        ScrollView scrollView = new ScrollView(this); scrollView.addView(content); setContentView(scrollView);
        ensureBluetoothPermissionAndLoad();
    }

    @Override protected void onResume() { super.onResume(); dashboardHandler.post(dashboardRefresh); }
    @Override protected void onPause() { dashboardHandler.removeCallbacks(dashboardRefresh); super.onPause(); }

    private TextView addSensorRow(LinearLayout parent, String icon, String name, String units) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, dp(8), 0, dp(8));
        TextView iconView = new TextView(this); iconView.setText(icon); iconView.setTextSize(26); iconView.setGravity(Gravity.CENTER); row.addView(iconView, new LinearLayout.LayoutParams(dp(44), dp(48)));
        LinearLayout labels = new LinearLayout(this); labels.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = new TextView(this); nameView.setText(name); nameView.setTextSize(16); nameView.setTypeface(Typeface.DEFAULT, Typeface.BOLD); labels.addView(nameView);
        TextView unitsView = new TextView(this); unitsView.setText(units); unitsView.setTextSize(12); labels.addView(unitsView); row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView value = new TextView(this); value.setText("—"); value.setTextSize(16); value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); value.setTypeface(Typeface.MONOSPACE); row.addView(value, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        parent.addView(row); return value;
    }

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(); connection.setConnectTimeout(250); connection.setReadTimeout(250); connection.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) { return new JSONObject(reader.readLine()); }
        finally { connection.disconnect(); }
    }

    private void refreshDashboard() {
        new Thread(() -> {
            try {
                JSONObject imu = getJson(IMU_URL); JSONObject position = getJson(LOCATION_URL);
                runOnUiThread(() -> { displaySample(imu); displayPosition(position); });
            } catch (Exception ignored) { runOnUiThread(() -> { status.setText("Bridge stopped or starting…"); showUnavailable(); }); }
        }, "orc-dashboard-refresh").start();
    }

    private void displaySample(JSONObject root) {
        status.setText(root.optBoolean("ready") ? "● Bridge running • IMU ready" : "● Bridge running • waiting for sensors");
        accelerometerValue.setText(vectorText(root.optJSONObject("acceleration_mps2")));
        linearAccelerationValue.setText(root.optBoolean("linear_acceleration_available") ? vectorText(root.optJSONObject("linear_acceleration_mps2")) : "Not available");
        gyroscopeValue.setText(vectorText(root.optJSONObject("angular_velocity_rad_s")));
        magnetometerValue.setText(root.optBoolean("magnetometer_available") ? vectorText(root.optJSONObject("magnetic_field_uT")) : "Not available");
        pressureValue.setText(root.optBoolean("pressure_available") ? String.format(Locale.US, "%.2f", root.optDouble("pressure_hpa")) : "Not available");
    }

    private void displayPosition(JSONObject root) {
        if (!root.optBoolean("permission_granted")) { positionValue.setText("Permission required"); return; }
        if (!root.optBoolean("ready")) { positionValue.setText(root.optBoolean("available") ? "Waiting for fix" : "Provider unavailable"); return; }
        double accuracy = root.optDouble("horizontal_accuracy_m", Double.NaN);
        positionValue.setText(String.format(Locale.US, "%.6f, %.6f\n%s  %s", root.optDouble("latitude"), root.optDouble("longitude"),
                Double.isNaN(accuracy) ? "accuracy —" : String.format(Locale.US, "±%.1f m", accuracy), root.optString("provider", "")));
    }

    private String vectorText(JSONObject vector) { if (vector == null) return "—"; return String.format(Locale.US, "x %+.2f  y %+.2f  z %+.2f", vector.optDouble("x"), vector.optDouble("y"), vector.optDouble("z")); }
    private void showUnavailable() { accelerometerValue.setText("—"); linearAccelerationValue.setText("—"); gyroscopeValue.setText("—"); magnetometerValue.setText("—"); pressureValue.setText("—"); positionValue.setText("—"); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void startBridge() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        }
        startForegroundService(new Intent(this, SensorBridgeService.class)); status.setText("Bridge starting…");
    }

    private void ensureBluetoothPermissionAndLoad() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST);
            return;
        }
        loadPairedDevices();
    }

    private void loadPairedDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { ensureBluetoothPermissionAndLoad(); return; }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
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
        bluetoothDeviceSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        bluetoothStatus.setText(labels.isEmpty() ? "No paired classic Bluetooth devices" : labels.size() + " paired device(s) available");
    }

    private void startBluetoothBridge() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { ensureBluetoothPermissionAndLoad(); return; }
        int position = bluetoothDeviceSpinner.getSelectedItemPosition();
        if (position < 0 || position >= pairedDevices.size()) { bluetoothStatus.setText("Select a paired Bluetooth device first"); return; }
        BluetoothDevice device = pairedDevices.get(position);
        Intent intent = new Intent(this, BluetoothSppBridgeService.class);
        intent.putExtra(BluetoothSppBridgeService.EXTRA_DEVICE_ADDRESS, device.getAddress());
        startForegroundService(intent);
        bluetoothStatus.setText("Connecting to " + (device.getName() == null ? device.getAddress() : device.getName()) + "…");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            startForegroundService(new Intent(this, SensorBridgeService.class));
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadPairedDevices();
        }
    }
}
