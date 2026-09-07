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
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.ConfigRepository;
import org.openroadcode.androidbridge.config.ServiceConfig;
import org.openroadcode.androidbridge.config.ServiceProvider;
import org.openroadcode.androidbridge.ui.SensorCard;
import org.openroadcode.androidbridge.ui.UiTheme;

public final class MainActivity extends Activity {
  private static final int LOCATION_PERMISSION_REQUEST = 1001, BLUETOOTH_PERMISSION_REQUEST = 1002,
                           CAMERA_PERMISSION_REQUEST = 1003;
  private static final long DASHBOARD_PERIOD_MS = 500;
  private static final String IMU_URL = "http://127.0.0.1:8766/imu",
                              LOCATION_URL = "http://127.0.0.1:8766/location";
  private static final int BG = UiTheme.BG, SURFACE_RAISED = UiTheme.SURFACE_RAISED,
                           BORDER = UiTheme.BORDER, TEXT = UiTheme.TEXT, MUTED = UiTheme.MUTED,
                           SILVER = UiTheme.SILVER, BLUE = UiTheme.BLUE, GREEN = UiTheme.GREEN,
                           RED = UiTheme.RED;

  private final Handler dashboardHandler = new Handler(Looper.getMainLooper());
  private final Runnable dashboardRefresh = new Runnable() {
    @Override
    public void run() {
      refreshDashboard();
      refreshCameraStatus();
      dashboardHandler.postDelayed(this, DASHBOARD_PERIOD_MS);
    }
  };
  private final List<BluetoothDevice> pairedDevices = new ArrayList<>();

  private TextView remoteAccessStatus, bluetoothStatus, cameraStatus, cameraDetails, cameraEndpoint;
  private Spinner bluetoothDeviceSpinner;
  private Switch remoteAccessSwitch;
  private Button bluetoothStartButton, bluetoothStopButton, cameraStartButton, cameraStopButton,
      cameraLocalButton, cameraWifiButton, cameraCellularButton;
  private SensorCard sensorCard;
  private TermuxServicesCard termuxServicesCard;
  private ConfigRepository configRepository;
  private boolean bridgeRequestedRunning, bluetoothRequestedRunning, cameraRequestedRunning;

  private final BroadcastReceiver bluetoothStatusReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (!BluetoothSppBridgeService.ACTION_STATUS.equals(intent.getAction())) return;
      String state = intent.getStringExtra(BluetoothSppBridgeService.EXTRA_STATUS),
             message = intent.getStringExtra(BluetoothSppBridgeService.EXTRA_MESSAGE);
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
    configRepository = new ConfigRepository(this);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);

    ScrollView scrollView = new ScrollView(this);
    scrollView.setBackgroundColor(BG);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(10), dp(18), dp(10), dp(28));
    addBrandHeader(content);

    ServiceConfig sensorConfig = configRepository.sensorConfig();
    bridgeRequestedRunning = sensorConfig.enabled();
    sensorCard = new SensorCard(this, sensorConfig.provider(), this::selectSensorProvider,
        this::startBridge, this::stopBridge);
    sensorCard.setRunning(bridgeRequestedRunning);
    content.addView(sensorCard.view(), cardParams());

    LinearLayout networkCard = card();
    addSectionHeading(networkCard, "REMOTE SENSOR ACCESS", BLUE,
        "Share sensor telemetry with devices on this network");
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
        "Selectable camera • H.264 • 1280×720 • 30 FPS • HTTP 8767");
    cameraStatus = statusPill("Camera stopped", MUTED);
    cameraCard.addView(cameraStatus);
    CameraPreviewView cameraPreview = new CameraPreviewView(this);
    LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
    previewParams.setMargins(dp(2), dp(2), dp(2), dp(8));
    cameraCard.addView(cameraPreview, previewParams);
    TextView routeLabel = text("VIDEO INTERFACE", 11, MUTED);
    routeLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    routeLabel.setLetterSpacing(.10f);
    routeLabel.setPadding(dp(2), dp(4), 0, 0);
    cameraCard.addView(routeLabel);
    cameraLocalButton = actionButton(
        "LOCAL", SURFACE_RAISED, v -> setCameraInterface(CameraStreamService.INTERFACE_LOCALHOST));
    cameraWifiButton = actionButton(
        "WI-FI", SURFACE_RAISED, v -> setCameraInterface(CameraStreamService.INTERFACE_WIFI));
    cameraCellularButton = actionButton(
        "5G", SURFACE_RAISED, v -> setCameraInterface(CameraStreamService.INTERFACE_CELLULAR));
    cameraCard.addView(buttonRow(cameraLocalButton, cameraWifiButton, cameraCellularButton));
    updateCameraInterfaceButtons();
    cameraDetails = text("Frames  —    Viewer  —    Preview  —", 13, TEXT);
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
    addSectionHeading(bluetoothCard, "BLUETOOTH SPP", GREEN,
        "Classic Bluetooth • RFCOMM transport");
    bluetoothStatus = statusPill("Tap REFRESH to load paired devices", MUTED);
    bluetoothCard.addView(bluetoothStatus);
    bluetoothDeviceSpinner = new Spinner(this);
    bluetoothDeviceSpinner.setBackground(rounded(SURFACE_RAISED, BORDER, 10));
    bluetoothDeviceSpinner.setPadding(dp(10), 0, dp(10), 0);
    bluetoothCard.addView(bluetoothDeviceSpinner, new LinearLayout.LayoutParams(-1, dp(52)));
    bluetoothStartButton = actionButton("START SPP", BLUE, v -> startBluetoothBridge());
    bluetoothStopButton = actionButton("STOP", SURFACE_RAISED, v -> stopBluetoothBridge());
    bluetoothCard.addView(buttonRow(actionButton("REFRESH", BLUE, v -> ensureBluetoothPermissionAndLoad()),
        bluetoothStartButton, bluetoothStopButton));
    TextView bluetoothHint = text("TCP endpoint  127.0.0.1:35000", 12, MUTED);
    bluetoothHint.setTypeface(Typeface.MONOSPACE);
    bluetoothHint.setPadding(dp(2), dp(10), 0, 0);
    bluetoothCard.addView(bluetoothHint);
    content.addView(bluetoothCard, cardParams());

    termuxServicesCard = new TermuxServicesCard(this);
    content.addView(termuxServicesCard.view(), cardParams());
    TextView footer = text("OPENROADC0DE  •  BUILD " + BuildConfig.VERSION_NAME, 11, MUTED);
    footer.setGravity(Gravity.CENTER);
    footer.setLetterSpacing(.12f);
    footer.setPadding(0, dp(8), 0, 0);
    content.addView(footer);

    scrollView.addView(content);
    setContentView(scrollView);
    registerReceiver(bluetoothStatusReceiver,
        new IntentFilter(BluetoothSppBridgeService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
  }

  private void selectSensorProvider(ServiceProvider provider) {
    ServiceConfig current = configRepository.sensorConfig();
    if (current.provider() == provider) return;
    configRepository.saveSensorConfig(current.withProvider(provider));
    if (bridgeRequestedRunning) {
      stopService(new Intent(this, SensorBridgeService.class));
      startSensorBridgeService();
      sensorCard.setStatus("●  Bridge restarting with " + provider.displayName() + "…", BLUE);
    }
  }

  private void addBrandHeader(LinearLayout parent) {
    LinearLayout brand = new LinearLayout(this);
    brand.setOrientation(LinearLayout.HORIZONTAL);
    brand.setGravity(Gravity.CENTER_VERTICAL);
    brand.setPadding(0, dp(4), 0, dp(18));
    ImageView mark = new ImageView(this);
    mark.setImageResource(R.drawable.ic_openroadcode);
    mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(54), dp(54));
    mp.setMargins(0, 0, dp(2), 0);
    brand.addView(mark, mp);
    LinearLayout words = new LinearLayout(this);
    words.setOrientation(LinearLayout.VERTICAL);
    LinearLayout titleRow = new LinearLayout(this);
    titleRow.setOrientation(LinearLayout.HORIZONTAL);
    addBrandWord(titleRow, "OPEN", BLUE);
    addBrandWord(titleRow, " ROAD", RED);
    addBrandWord(titleRow, " CODE", GREEN);
    words.addView(titleRow);
    TextView subtitle = text("ANDROID HARDWARE BRIDGE", 11, SILVER);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setLetterSpacing(.14f);
    words.addView(subtitle);
    brand.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView badge = new ImageView(this);
    badge.setImageResource(R.drawable.ic_linux_sensor_bridge);
    badge.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(46), dp(46));
    bp.setMargins(dp(4), 0, 0, 0);
    brand.addView(badge, bp);
    parent.addView(brand);
  }

  private void addBrandWord(LinearLayout row, String value, int color) {
    TextView word = text(value, 21, color);
    word.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    word.setLetterSpacing(.035f);
    row.addView(word);
  }

  private LinearLayout card() { return UiTheme.card(this); }

  private LinearLayout.LayoutParams cardParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(14));
    return params;
  }

  private void addSectionHeading(LinearLayout parent, String title, int accent, String subtitle) {
    TextView heading = text(title, 18, TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.08f);
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
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(8));
    view.setLayoutParams(params);
    return view;
  }

  private Button actionButton(String label, int color, View.OnClickListener listener) {
    return UiTheme.actionButton(this, label, color, listener);
  }

  private void setButtonColor(Button button, int color) {
    UiTheme.setButtonColor(this, button, color);
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
    row.setPadding(0, dp(8), 0, 0);
    for (Button button : buttons) {
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
      params.setMargins(dp(3), 0, dp(3), 0);
      row.addView(button, params);
    }
    return row;
  }

  private TextView text(String value, float size, int color) {
    return UiTheme.text(this, value, size, color);
  }

  private android.graphics.drawable.GradientDrawable rounded(int fill, int stroke, int radius) {
    return UiTheme.rounded(this, fill, stroke, radius);
  }

  @Override
  protected void onResume() {
    super.onResume();
    updateRemoteAccessStatus();
    updateCameraEndpoint();
    dashboardHandler.post(dashboardRefresh);
    if (termuxServicesCard != null) termuxServicesCard.start();
  }

  @Override
  protected void onPause() {
    dashboardHandler.removeCallbacks(dashboardRefresh);
    if (termuxServicesCard != null) termuxServicesCard.stop();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    unregisterReceiver(bluetoothStatusReceiver);
    super.onDestroy();
  }

  private JSONObject getJson(String url) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setConnectTimeout(250);
    connection.setReadTimeout(250);
    connection.setRequestMethod("GET");
    try (BufferedReader reader = new BufferedReader(
             new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
      return new JSONObject(reader.readLine());
    } finally {
      connection.disconnect();
    }
  }

  private void refreshDashboard() {
    new Thread(() -> {
      try {
        JSONObject imu = getJson(IMU_URL), position = getJson(LOCATION_URL);
        runOnUiThread(() -> {
          bridgeRequestedRunning = true;
          sensorCard.setRunning(true);
          boolean ready = imu.optBoolean("ready");
          String source = imu.optString("source", "");
          String suffix = source.isEmpty() ? "" : " • " + source;
          sensorCard.setStatus(ready ? "●  Bridge running • sensors ready" + suffix
                                     : "●  Bridge running • waiting for sensors" + suffix,
              ready ? GREEN : BLUE);
          sensorCard.displaySample(imu);
          sensorCard.displayPosition(position);
        });
      } catch (Exception ignored) {
        runOnUiThread(() -> {
          sensorCard.setStatus(bridgeRequestedRunning ? "●  Bridge starting…" : "●  Bridge stopped",
              bridgeRequestedRunning ? BLUE : MUTED);
          sensorCard.clear();
        });
      }
    }, "orc-dashboard-refresh").start();
  }

  private void refreshCameraStatus() {
    new Thread(() -> {
      try {
        String address = cameraAddress();
        if (address == null) throw new Exception();
        JSONObject camera = getJson("http://" + address + ":" + CameraStreamService.PORT + "/status");
        runOnUiThread(() -> displayCameraStatus(camera));
      } catch (Exception ignored) {
        runOnUiThread(() -> {
          if (!cameraRequestedRunning) {
            cameraStatus.setText("●  Camera stopped");
            cameraStatus.setTextColor(MUTED);
            cameraDetails.setText("Frames  —    Viewer  —    Preview  —");
            updateCameraButtons(false, false);
          }
        });
      }
    }, "orc-camera-status-refresh").start();
  }

  private void displayCameraStatus(JSONObject response) {
    String state = response.optString("state", "unknown"), error = response.optString("error", "");
    boolean client = response.optBoolean("client_connected", false),
            preview = response.optBoolean("preview_attached", false);
    long frames = response.optLong("encoded_frames", 0);
    if ("streaming".equals(state)) {
      cameraRequestedRunning = true;
      updateCameraButtons(true, true);
      cameraStatus.setText("●  Camera " + response.optString("camera_id", "?")
          + " streaming • 720p30 • "
          + interfaceLabel(response.optString("interface", currentCameraInterface())));
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
    cameraDetails.setText(String.format(Locale.US, "Frames  %,d    Viewer  %s    Preview  %s",
        frames, client ? "connected" : "none", preview ? "on" : "off"));
    updateCameraEndpoint();
  }

  private int dp(int value) { return UiTheme.dp(this, value); }

  private boolean hasLocationPermission() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  private boolean sensorNeedsLocationPermission() {
    return configRepository.sensorConfig().provider() == ServiceProvider.ANDROID_SENSORS;
  }

  private void startBridge() {
    bridgeRequestedRunning = true;
    configRepository.saveSensorConfig(configRepository.sensorConfig().withEnabled(true));
    sensorCard.setRunning(true);
    if (sensorNeedsLocationPermission() && !hasLocationPermission()) {
      sensorCard.setStatus("●  Location permission required…", BLUE);
      requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
                             Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
      return;
    }
    startSensorBridgeService();
  }

  private void startSensorBridgeService() {
    startForegroundService(new Intent(this, SensorBridgeService.class));
    sensorCard.setStatus("●  Bridge starting…", BLUE);
  }

  private void stopBridge() {
    bridgeRequestedRunning = false;
    configRepository.saveSensorConfig(configRepository.sensorConfig().withEnabled(false));
    sensorCard.setRunning(false);
    stopService(new Intent(this, SensorBridgeService.class));
    sensorCard.setStatus("●  Bridge stopped", MUTED);
    sensorCard.clear();
  }

  private void startCamera() {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
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
    cameraDetails.setText("Frames  —    Viewer  —    Preview  —");
  }

  private String currentCameraInterface() {
    return getSharedPreferences(CameraStreamService.PREFERENCES, MODE_PRIVATE)
        .getString(CameraStreamService.PREF_INTERFACE, CameraStreamService.INTERFACE_WIFI);
  }

  private void setCameraInterface(String mode) {
    getSharedPreferences(CameraStreamService.PREFERENCES, MODE_PRIVATE)
        .edit().putString(CameraStreamService.PREF_INTERFACE, mode).apply();
    updateCameraInterfaceButtons();
    updateCameraEndpoint();
    if (cameraRequestedRunning) {
      stopService(new Intent(this, CameraStreamService.class));
      cameraRequestedRunning = true;
      updateCameraButtons(true, false);
      startForegroundService(new Intent(this, CameraStreamService.class));
      cameraStatus.setText("●  Camera restarting on " + interfaceLabel(mode) + "…");
      cameraStatus.setTextColor(BLUE);
    }
  }

  private void updateCameraInterfaceButtons() {
    if (cameraLocalButton == null) return;
    String mode = currentCameraInterface();
    setButtonColor(cameraLocalButton,
        CameraStreamService.INTERFACE_LOCALHOST.equals(mode) ? BLUE : SURFACE_RAISED);
    setButtonColor(cameraWifiButton,
        CameraStreamService.INTERFACE_WIFI.equals(mode) ? BLUE : SURFACE_RAISED);
    setButtonColor(cameraCellularButton,
        CameraStreamService.INTERFACE_CELLULAR.equals(mode) ? BLUE : SURFACE_RAISED);
  }

  private String interfaceLabel(String mode) {
    if (CameraStreamService.INTERFACE_LOCALHOST.equals(mode)) return "LOCAL";
    if (CameraStreamService.INTERFACE_CELLULAR.equals(mode)) return "5G";
    return "WI-FI";
  }

  private String cameraAddress() {
    String mode = currentCameraInterface();
    if (CameraStreamService.INTERFACE_LOCALHOST.equals(mode)) return "127.0.0.1";
    return findTransportAddress(CameraStreamService.INTERFACE_CELLULAR.equals(mode)
            ? NetworkCapabilities.TRANSPORT_CELLULAR : NetworkCapabilities.TRANSPORT_WIFI);
  }

  private String findTransportAddress(int transport) {
    ConnectivityManager manager = getSystemService(ConnectivityManager.class);
    if (manager == null) return null;
    for (Network network : manager.getAllNetworks()) {
      NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
      if (capabilities == null || !capabilities.hasTransport(transport)) continue;
      LinkProperties properties = manager.getLinkProperties(network);
      if (properties == null) continue;
      for (LinkAddress link : properties.getLinkAddresses()) {
        InetAddress address = link.getAddress();
        if (address instanceof Inet4Address && !address.isLoopbackAddress())
          return address.getHostAddress();
      }
    }
    return null;
  }

  private void updateCameraEndpoint() {
    if (cameraEndpoint == null) return;
    String address = cameraAddress();
    cameraEndpoint.setText(address == null
            ? interfaceLabel(currentCameraInterface()) + " unavailable • port " + CameraStreamService.PORT
            : "Video  http://" + address + ":" + CameraStreamService.PORT + "/video");
    cameraEndpoint.setTextColor(address == null ? RED : GREEN);
  }

  private void setRemoteAccess(boolean enabled) {
    getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .edit().putBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, enabled).apply();
    updateRemoteAccessStatus();
    stopService(new Intent(this, SensorBridgeService.class));
    if (!sensorNeedsLocationPermission() || hasLocationPermission()) {
      bridgeRequestedRunning = true;
      sensorCard.setRunning(true);
      startSensorBridgeService();
    } else {
      bridgeRequestedRunning = false;
      sensorCard.setRunning(false);
    }
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
        for (InetAddress address : Collections.list(network.getInetAddresses()))
          if (address instanceof Inet4Address && !address.isLoopbackAddress())
            return address.getHostAddress();
      }
    } catch (Exception ignored) { }
    return null;
  }

  private void ensureBluetoothPermissionAndLoad() {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION_REQUEST);
      return;
    }
    loadPairedDevices();
  }

  private void loadPairedDevices() {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      ensureBluetoothPermissionAndLoad();
      return;
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
    ArrayAdapter<String> spinnerAdapter =
        new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
          @Override
          public View getView(int position, View convertView, android.view.ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            view.setTextColor(TEXT);
            view.setTextSize(13);
            return view;
          }

          @Override
          public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
            TextView view = (TextView) super.getDropDownView(position, convertView, parent);
            view.setTextColor(TEXT);
            view.setBackgroundColor(SURFACE_RAISED);
            view.setPadding(dp(12), dp(12), dp(12), dp(12));
            return view;
          }
        };
    bluetoothDeviceSpinner.setAdapter(spinnerAdapter);
    if (!bluetoothRequestedRunning) {
      bluetoothStatus.setText(labels.isEmpty() ? "●  No paired classic Bluetooth devices"
                                               : "●  " + labels.size() + " paired device(s) available");
      bluetoothStatus.setTextColor(labels.isEmpty() ? RED : GREEN);
    }
  }

  private void startBluetoothBridge() {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      ensureBluetoothPermissionAndLoad();
      return;
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
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(requestCode, permissions, grants);
    if (requestCode == LOCATION_PERMISSION_REQUEST) {
      if (hasLocationPermission()) {
        bridgeRequestedRunning = true;
        sensorCard.setRunning(true);
        startSensorBridgeService();
      } else {
        bridgeRequestedRunning = false;
        configRepository.saveSensorConfig(configRepository.sensorConfig().withEnabled(false));
        sensorCard.setRunning(false);
        sensorCard.setStatus("●  Location permission required", RED);
      }
    } else if (requestCode == BLUETOOTH_PERMISSION_REQUEST && grants.length > 0
        && grants[0] == PackageManager.PERMISSION_GRANTED) {
      loadPairedDevices();
    } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
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
