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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 1001;
    private static final int BLUETOOTH_PERMISSION_REQUEST = 1002;
    private static final int CAMERA_PERMISSION_REQUEST = 1003;
    private static final long DASHBOARD_PERIOD_MS = 500;
    private static final String IMU_URL = "http://127.0.0.1:8766/imu";
    private static final String LOCATION_URL = "http://127.0.0.1:8766/location";
    private static final String CAMERA_STATUS_URL = "http://127.0.0.1:8767/status";

    private static final int BG = Color.rgb(6, 16, 24);
    private static final int SURFACE = Color.rgb(11, 24, 33);
    private static final int SURFACE_RAISED = Color.rgb(16, 34, 46);
    private static final int BORDER = Color.rgb(36, 64, 79);
    private static final int TEXT = Color.rgb(243, 247, 249);
    private static final int MUTED = Color.rgb(147, 164, 174);
    private static final int BLUE = Color.rgb(22, 139, 209);
    private static final int GREEN = Color.rgb(132, 206, 31);
    private static final int RED = Color.rgb(241, 90, 22);

    private final Handler dashboardHandler = new Handler(Looper.getMainLooper());
    private final Runnable dashboardRefresh = new Runnable() {
        @Override public void run() {
            refreshDashboard();
            refreshCameraStatus();
            dashboardHandler.postDelayed(this, DASHBOARD_PERIOD_MS);
        }
    };
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();

    private TextView status, remoteAccessStatus, bluetoothStatus;
    private TextView cameraStatus, cameraDetails, cameraEndpoint;
    private TextView accelerometerValue, linearAccelerationValue, gyroscopeValue;
    private TextView magnetometerValue, pressureValue, ambientLightValue, positionValue;
    private Spinner bluetoothDeviceSpinner;
    private Switch remoteAccessSwitch;
    private Button bridgeStartButton, bridgeStopButton, bluetoothStartButton, bluetoothStopButton;
    private Button cameraStartButton, cameraStopButton;
    private boolean bridgeRequestedRunning;
    private boolean bluetoothRequestedRunning;
    private boolean cameraRequestedRunning;

    private final BroadcastReceiver bluetoothStatusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!BluetoothSppBridgeService.ACTION_STATUS.equals(intent.getAction())) return;
            String state = intent.getStringExtra(BluetoothSppBridgeService.EXTRA_STATUS);
            String message = intent.getStringExtra(BluetoothSppBridgeService.EXTRA_MESSAGE);
            if (message == null || message.isEmpty()) message = "Bluetooth bridge status unavailable";

            if (BluetoothSppBridgeService.STATUS_CONNECTING.equals(state)) {
                bluetoothRequestedRunning = true;
                updateBluetoothButtons(true, false);
                bluetoothStatus.setText("●  " + message);
                bluetoothStatus.setTextColor(BLUE);
            } else if (BluetoothSppBridgeService.STATUS_CONNECTED.equals(state)) {
                bluetoothRequestedRunning = true;
                updateBluetoothButtons(true, true);
                bluetoothStatus.setText("●  " + message);
                bluetoothStatus.setTextColor(GREEN);
            } else if (BluetoothSppBridgeService.STATUS_ERROR.equals(state)) {
                bluetoothRequestedRunning = false;
                updateBluetoothButtons(false, false);
                bluetoothStatus.setText("●  " + message);
                bluetoothStatus.setTextColor(RED);
            } else if (BluetoothSppBridgeService.STATUS_STOPPED.equals(state)) {
                bluetoothRequestedRunning = false;
                updateBluetoothButtons(false, false);
                bluetoothStatus.setText("●  " + message);
                bluetoothStatus.setTextColor(MUTED);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(BG);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(28));

        addBrandHeader(content);

        LinearLayout sensorCard = card();
        addSectionHeading(sensorCard, "SENSOR BRIDGE", BLUE, "Phone telemetry • HTTP 8766");
        status = statusPill("Bridge stopped", MUTED);
        sensorCard.addView(status);
        accelerometerValue = addSensorRow(sensorCard, "↗", "Accelerometer", "m/s²", BLUE);
        linearAccelerationValue = addSensorRow(sensorCard, "⇢", "Linear acceleration", "m/s²", GREEN);
        gyroscopeValue = addSensorRow(sensorCard, "↻", "Gyroscope", "rad/s", RED);
        magnetometerValue = addSensorRow(sensorCard, "⌖", "Magnetometer", "µT", BLUE);
        pressureValue = addSensorRow(sensorCard, "◉", "Barometer", "hPa", GREEN);
        ambientLightValue = addSensorRow(sensorCard, "☀", "Ambient light", "lux", BLUE);
        positionValue = addSensorRow(sensorCard, "◎", "Position", "lat / lon • accuracy", RED);
        bridgeStartButton = actionButton("START BRIDGE", BLUE, v -> startBridge());
        bridgeStopButton = actionButton("STOP", SURFACE_RAISED, v -> stopBridge());
        sensorCard.addView(buttonRow(bridgeStartButton, bridgeStopButton));
        content.addView(sensorCard, cardParams());

        LinearLayout networkCard = card();
        addSectionHeading(networkCard, "REMOTE SENSOR ACCESS", BLUE, "Share sensor telemetry with devices on this network");
        remoteAccessSwitch = new Switch(this);
        remoteAccessSwitch.setText("Allow network clients");
        remoteAccessSwitch.setTextColor(TEXT);
        remoteAccessSwitch.setTextSize(15);
        remoteAccessSwitch.setPadding(dp(4), dp(4), dp(4), dp(8));
        boolean remoteEnabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
                .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
        remoteAccessSwitch.setChecked(remoteEnabled);
        networkCard.addView(remoteAccessSwitch);
        remoteAccessStatus = statusPill("", remoteEnabled ? GREEN : MUTED);
        networkCard.addView(remoteAccessStatus);
        updateRemoteAccessStatus();
        remoteAccessSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> setRemoteAccess(isChecked));
        content.addView(networkCard, cardParams());

        LinearLayout cameraCard = card();
        addSectionHeading(cameraCard, "CAMERA STREAM", RED,
                "Rear camera • H.264 • 1280×720 • 30 FPS • HTTP 8767");
        cameraStatus = statusPill("Camera stopped", MUTED);
        cameraCard.addView(cameraStatus);
        cameraDetails = text("Frames  —    Viewer  —", 13, TEXT);
        cameraDetails.setTypeface(Typeface.MONOSPACE);
        cameraDetails.setPadding(dp(2), dp(5), 0, dp(4));
        cameraCard.addView(cameraDetails);
        cameraEndpoint = text("Video endpoint  waiting for network", 12, MUTED);
        cameraEndpoint.setTypeface(Typeface.MONOSPACE);
        cameraEndpoint.setPadding(dp(2), dp(2), 0, dp(4));
        cameraCard.addView(cameraEndpoint);
        updateCameraEndpoint();
        cameraStartButton = actionButton("START CAMERA", RED, v -> startCamera());
        cameraStopButton = actionButton("STOP", SURFACE_RAISED, v -> stopCamera());
        cameraCard.addView(buttonRow(cameraStartButton, cameraStopButton));
        content.addView(cameraCard, cardParams());

        LinearLayout bluetoothCard = card();
        addSectionHeading(bluetoothCard, "BLUETOOTH SPP", GREEN, "Classic Bluetooth • RFCOMM transport");
        bluetoothStatus = statusPill("Select a paired device", MUTED);
        bluetoothCard.addView(bluetoothStatus);
        bluetoothDeviceSpinner = new Spinner(this);
        bluetoothDeviceSpinner.setBackground(rounded(SURFACE_RAISED, BORDER, 10));
        bluetoothDeviceSpinner.setPadding(dp(10), 0, dp(10), 0);
        bluetoothCard.addView(bluetoothDeviceSpinner,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        bluetoothStartButton = actionButton("START SPP", BLUE, v -> startBluetoothBridge());
        bluetoothStopButton = actionButton("STOP", SURFACE_RAISED, v -> stopBluetoothBridge());
        bluetoothCard.addView(buttonRow(
                actionButton("REFRESH", BLUE, v -> loadPairedDevices()),
                bluetoothStartButton,
                bluetoothStopButton
        ));
        TextView bluetoothHint = text("TCP endpoint  127.0.0.1:35000", 12, MUTED);
        bluetoothHint.setTypeface(Typeface.MONOSPACE);
        bluetoothHint.setPadding(dp(2), dp(10), 0, 0);
        bluetoothCard.addView(bluetoothHint);
        content.addView(bluetoothCard, cardParams());

        TextView footer = text("OPENROADC0DE  •  BUILD " + BuildConfig.VERSION_NAME, 11, MUTED);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.12f);
        footer.setPadding(0, dp(8), 0, 0);
        content.addView(footer);

        scrollView.addView(content);
        setContentView(scrollView);
        registerReceiver(bluetoothStatusReceiver,
                new IntentFilter(BluetoothSppBridgeService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
        ensureBluetoothPermissionAndLoad();
    }

    private void addBrandHeader(LinearLayout parent) {
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(2), dp(4), dp(2), dp(18));
        TextView mark = text("△", 38, GREEN);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setGravity(Gravity.CENTER);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("OPEN ROAD CODE", 24, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        words.addView(title);
        TextView subtitle = text("ANDROID HARDWARE BRIDGE", 11, BLUE);
        subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        subtitle.setLetterSpacing(0.18f);
        words.addView(subtitle);
        brand.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView version = text("v" + BuildConfig.VERSION_NAME, 11, MUTED);
        version.setTypeface(Typeface.MONOSPACE);
        brand.addView(version);
        parent.addView(brand);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(SURFACE, BORDER, 14));
        return card;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(14));
        return params;
    }

    private void addSectionHeading(LinearLayout parent, String title, int accent, String subtitle) {
        TextView heading = text(title, 18, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setLetterSpacing(0.08f);
        parent.addView(heading);
        TextView sub = text(subtitle, 12, accent);
        sub.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sub.setPadding(0, dp(2), 0, dp(10));
        parent.addView(sub);
    }

    private TextView statusPill(String value, int accent) {
        TextView view = text("●  " + value, 13, accent);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setBackground(rounded(SURFACE_RAISED, BORDER, 9));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private TextView addSensorRow(LinearLayout parent, String icon, String name, String units, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));
        TextView iconView = text(icon, 24, accent);
        iconView.setGravity(Gravity.CENTER);
        row.addView(iconView, new LinearLayout.LayoutParams(dp(40), dp(44)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView nameView = text(name, 15, TEXT);
        nameView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(nameView);
        labels.addView(text(units, 11, MUTED));
        row.addView(labels, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text("—", 14, TEXT);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        value.setTypeface(Typeface.MONOSPACE);
        row.addView(value, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.65f));
        parent.addView(row);
        return value;
    }

    private Button actionButton(String label, int color, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(TEXT);
        button.setTextSize(11);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setLetterSpacing(0.08f);
        button.setAllCaps(false);
        setButtonColor(button, color);
        button.setOnClickListener(listener);
        return button;
    }

    private void setButtonColor(Button button, int color) {
        button.setBackground(rounded(color, color, 9));
    }

    private void updateBridgeButtons(boolean running) {
        bridgeStartButton.setText(running ? "RUNNING" : "START BRIDGE");
        setButtonColor(bridgeStartButton, running ? SURFACE_RAISED : BLUE);
        setButtonColor(bridgeStopButton, running ? RED : SURFACE_RAISED);
    }

    private void updateBluetoothButtons(boolean running, boolean connected) {
        bluetoothStartButton.setText(running ? (connected ? "RUNNING" : "CONNECTING") : "START SPP");
        setButtonColor(bluetoothStartButton, running ? SURFACE_RAISED : BLUE);
        setButtonColor(bluetoothStopButton, running ? RED : SURFACE_RAISED);
    }

    private void updateCameraButtons(boolean running, boolean streaming) {
        cameraStartButton.setText(running ? (streaming ? "RUNNING" : "STARTING") : "START CAMERA");
        setButtonColor(cameraStartButton, running ? SURFACE_RAISED : RED);
        setButtonColor(cameraStopButton, running ? RED : SURFACE_RAISED);
    }

    private LinearLayout buttonRow(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(10), 0, 0);
        for (Button button : buttons) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            params.setMargins(dp(3), 0, dp(3), 0);
            row.addView(button, params);
        }
        return row;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    @Override protected void onResume() {
        super.onResume();
        updateRemoteAccessStatus();
        updateCameraEndpoint();
        dashboardHandler.post(dashboardRefresh);
    }

    @Override protected void onPause() {
        dashboardHandler.removeCallbacks(dashboardRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(bluetoothStatusReceiver);
        super.onDestroy();
    }

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(250);
        connection.setReadTimeout(250);
        connection.setRequestMethod("GET");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
            return new JSONObject(reader.readLine());
        } finally {
            connection.disconnect();
        }
    }

    private void refreshDashboard() {
        new Thread(() -> {
            try {
                JSONObject imu = getJson(IMU_URL);
                JSONObject position = getJson(LOCATION_URL);
                runOnUiThread(() -> { displaySample(imu); displayPosition(position); });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (bridgeRequestedRunning) {
                        status.setText("●  Bridge starting…");
                        status.setTextColor(BLUE);
                    } else {
                        status.setText("●  Bridge stopped");
                        status.setTextColor(MUTED);
                    }
                    showUnavailable();
                });
            }
        }, "orc-dashboard-refresh").start();
    }

    private void refreshCameraStatus() {
        new Thread(() -> {
            try {
                JSONObject camera = getJson(CAMERA_STATUS_URL);
                runOnUiThread(() -> displayCameraStatus(camera));
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (!cameraRequestedRunning) {
                        cameraStatus.setText("●  Camera stopped");
                        cameraStatus.setTextColor(MUTED);
                        cameraDetails.setText("Frames  —    Viewer  —");
                        updateCameraButtons(false, false);
                    }
                });
            }
        }, "orc-camera-status-refresh").start();
    }

    private void displaySample(JSONObject root) {
        bridgeRequestedRunning = true;
        updateBridgeButtons(true);
        boolean ready = root.optBoolean("ready");
        status.setText(ready ? "●  Bridge running • IMU ready" : "●  Bridge running • waiting for sensors");
        status.setTextColor(ready ? GREEN : BLUE);
        accelerometerValue.setText(vectorText(root.optJSONObject("acceleration_mps2")));
        linearAccelerationValue.setText(root.optBoolean("linear_acceleration_available")
                ? vectorText(root.optJSONObject("linear_acceleration_mps2")) : "Not available");
        gyroscopeValue.setText(vectorText(root.optJSONObject("angular_velocity_rad_s")));
        magnetometerValue.setText(root.optBoolean("magnetometer_available")
                ? vectorText(root.optJSONObject("magnetic_field_uT")) : "Not available");
        pressureValue.setText(root.optBoolean("pressure_available")
                ? String.format(Locale.US, "%.2f", root.optDouble("pressure_hpa")) : "Not available");
        ambientLightValue.setText(root.optBoolean("ambient_light_available")
                ? String.format(Locale.US, "%.1f", root.optDouble("ambient_light_lux")) : "Not available");
    }

    private void displayPosition(JSONObject root) {
        if (!root.optBoolean("permission_granted")) { positionValue.setText("Permission required"); return; }
        if (!root.optBoolean("ready")) {
            positionValue.setText(root.optBoolean("available") ? "Waiting for fix" : "Provider unavailable");
            return;
        }
        double accuracy = root.optDouble("horizontal_accuracy_m", Double.NaN);
        positionValue.setText(String.format(Locale.US, "%.6f, %.6f\n%s  %s",
                root.optDouble("latitude"), root.optDouble("longitude"),
                Double.isNaN(accuracy) ? "accuracy —" : String.format(Locale.US, "±%.1f m", accuracy),
                root.optString("provider", "")));
    }

    private void displayCameraStatus(JSONObject root) {
        String state = root.optString("state", "unknown");
        String error = root.optString("error", "");
        boolean clientConnected = root.optBoolean("client_connected", false);
        long frames = root.optLong("encoded_frames", 0);

        if ("streaming".equals(state)) {
            cameraRequestedRunning = true;
            updateCameraButtons(true, true);
            cameraStatus.setText("●  Camera streaming • 720p30");
            cameraStatus.setTextColor(GREEN);
        } else if ("starting".equals(state)) {
            cameraRequestedRunning = true;
            updateCameraButtons(true, false);
            cameraStatus.setText("●  Camera starting…");
            cameraStatus.setTextColor(BLUE);
        } else if ("error".equals(state)) {
            cameraRequestedRunning = false;
            updateCameraButtons(false, false);
            cameraStatus.setText("●  Camera error • " + (error.isEmpty() ? "unknown error" : error));
            cameraStatus.setTextColor(RED);
        } else {
            cameraRequestedRunning = false;
            updateCameraButtons(false, false);
            cameraStatus.setText("●  Camera " + state);
            cameraStatus.setTextColor(MUTED);
        }

        cameraDetails.setText(String.format(Locale.US, "Frames  %,d    Viewer  %s",
                frames, clientConnected ? "connected" : "none"));
        updateCameraEndpoint();
    }

    private String vectorText(JSONObject vector) {
        if (vector == null) return "—";
        return String.format(Locale.US, "x %+.2f  y %+.2f  z %+.2f",
                vector.optDouble("x"), vector.optDouble("y"), vector.optDouble("z"));
    }

    private void showUnavailable() {
        accelerometerValue.setText("—"); linearAccelerationValue.setText("—");
        gyroscopeValue.setText("—"); magnetometerValue.setText("—"); pressureValue.setText("—");
        ambientLightValue.setText("—"); positionValue.setText("—");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startBridge() {
        bridgeRequestedRunning = true;
        updateBridgeButtons(true);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
        }
        startForegroundService(new Intent(this, SensorBridgeService.class));
        status.setText("●  Bridge running");
        status.setTextColor(GREEN);
    }

    private void stopBridge() {
        bridgeRequestedRunning = false;
        updateBridgeButtons(false);
        stopService(new Intent(this, SensorBridgeService.class));
        status.setText("●  Bridge stopped");
        status.setTextColor(MUTED);
        showUnavailable();
    }

    private void startCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        cameraRequestedRunning = true;
        updateCameraButtons(true, false);
        startForegroundService(new Intent(this, CameraStreamService.class));
        cameraStatus.setText("●  Camera starting…");
        cameraStatus.setTextColor(BLUE);
    }

    private void stopCamera() {
        cameraRequestedRunning = false;
        updateCameraButtons(false, false);
        stopService(new Intent(this, CameraStreamService.class));
        cameraStatus.setText("●  Camera stopped");
        cameraStatus.setTextColor(MUTED);
        cameraDetails.setText("Frames  —    Viewer  —");
    }

    private void updateCameraEndpoint() {
        if (cameraEndpoint == null) return;
        String address = findLanAddress();
        cameraEndpoint.setText(address == null
                ? "Video endpoint  waiting for network • port " + CameraStreamService.PORT
                : "Video  http://" + address + ":" + CameraStreamService.PORT + "/video");
        cameraEndpoint.setTextColor(address == null ? MUTED : GREEN);
    }

    private void setRemoteAccess(boolean enabled) {
        getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
                .edit().putBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, enabled).apply();
        updateRemoteAccessStatus();
        stopService(new Intent(this, SensorBridgeService.class));
        bridgeRequestedRunning = true;
        updateBridgeButtons(true);
        startForegroundService(new Intent(this, SensorBridgeService.class));
        status.setText("●  Bridge restarting for network change…");
        status.setTextColor(BLUE);
    }

    private void updateRemoteAccessStatus() {
        if (remoteAccessStatus == null) return;
        boolean enabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
                .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
        if (!enabled) {
            remoteAccessStatus.setText("●  Disabled • localhost only • 127.0.0.1:" + SensorBridgeService.PORT);
            remoteAccessStatus.setTextColor(MUTED);
            return;
        }
        String address = findLanAddress();
        remoteAccessStatus.setText(address == null
                ? "●  Enabled • waiting for a network address • port " + SensorBridgeService.PORT
                : "●  Enabled • http://" + address + ":" + SensorBridgeService.PORT);
        remoteAccessStatus.setTextColor(address == null ? BLUE : GREEN);
    }

    private String findLanAddress() {
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void ensureBluetoothPermissionAndLoad() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST);
            return;
        }
        loadPairedDevices();
    }

    private void loadPairedDevices() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ensureBluetoothPermissionAndLoad(); return;
        }
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
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_dropdown_item, labels) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(TEXT); view.setTextSize(13); return view;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(TEXT); view.setBackgroundColor(SURFACE_RAISED);
                view.setPadding(dp(12), dp(12), dp(12), dp(12)); return view;
            }
        };
        bluetoothDeviceSpinner.setAdapter(spinnerAdapter);
        if (!bluetoothRequestedRunning) {
            bluetoothStatus.setText(labels.isEmpty()
                    ? "●  No paired classic Bluetooth devices"
                    : "●  " + labels.size() + " paired device(s) available");
            bluetoothStatus.setTextColor(labels.isEmpty() ? RED : GREEN);
        }
    }

    private void startBluetoothBridge() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            ensureBluetoothPermissionAndLoad(); return;
        }
        int position = bluetoothDeviceSpinner.getSelectedItemPosition();
        if (position < 0 || position >= pairedDevices.size()) {
            bluetoothStatus.setText("●  Select a paired Bluetooth device first");
            bluetoothStatus.setTextColor(RED);
            return;
        }
        BluetoothDevice device = pairedDevices.get(position);
        Intent intent = new Intent(this, BluetoothSppBridgeService.class);
        intent.putExtra(BluetoothSppBridgeService.EXTRA_DEVICE_ADDRESS, device.getAddress());
        bluetoothRequestedRunning = true;
        updateBluetoothButtons(true, false);
        startForegroundService(intent);
        bluetoothStatus.setText("●  Connecting to "
                + (device.getName() == null ? device.getAddress() : device.getName()) + "…");
        bluetoothStatus.setTextColor(BLUE);
    }

    private void stopBluetoothBridge() {
        bluetoothRequestedRunning = false;
        updateBluetoothButtons(false, false);
        stopService(new Intent(this, BluetoothSppBridgeService.class));
        bluetoothStatus.setText("●  Bluetooth bridge stopped");
        bluetoothStatus.setTextColor(MUTED);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            bridgeRequestedRunning = true;
            updateBridgeButtons(true);
            startForegroundService(new Intent(this, SensorBridgeService.class));
        } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST
                && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadPairedDevices();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                cameraRequestedRunning = false;
                updateCameraButtons(false, false);
                cameraStatus.setText("●  Camera permission required");
                cameraStatus.setTextColor(RED);
            }
        }
    }
}
