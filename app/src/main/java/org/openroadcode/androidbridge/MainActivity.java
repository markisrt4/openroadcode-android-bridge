package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import java.util.Collections;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.ConfigRepository;
import org.openroadcode.androidbridge.config.ServiceConfig;
import org.openroadcode.androidbridge.config.ServiceProvider;
import org.openroadcode.androidbridge.ui.SensorCard;
import org.openroadcode.androidbridge.ui.UiTheme;

public final class MainActivity extends Activity {
  private static final int LOCATION_PERMISSION_REQUEST = 1001;
  private static final long DASHBOARD_PERIOD_MS = 500;
  private static final String IMU_URL = "http://127.0.0.1:8766/imu";
  private static final String LOCATION_URL = "http://127.0.0.1:8766/location";

  private static final int BG = UiTheme.BG;
  private static final int SURFACE_RAISED = UiTheme.SURFACE_RAISED;
  private static final int BORDER = UiTheme.BORDER;
  private static final int TEXT = UiTheme.TEXT;
  private static final int MUTED = UiTheme.MUTED;
  private static final int SILVER = UiTheme.SILVER;
  private static final int BLUE = UiTheme.BLUE;
  private static final int GREEN = UiTheme.GREEN;
  private static final int RED = UiTheme.RED;

  private final Handler dashboardHandler = new Handler(Looper.getMainLooper());
  private final Runnable dashboardRefresh = new Runnable() {
    @Override
    public void run() {
      refreshDashboard();
      if (cameraCard != null) cameraCard.refresh();
      dashboardHandler.postDelayed(this, DASHBOARD_PERIOD_MS);
    }
  };

  private TextView remoteAccessStatus;
  private SensorCard sensorCard;
  private CameraCard cameraCard;
  private BluetoothCard bluetoothCard;
  private TermuxServicesCard termuxServicesCard;
  private ConfigRepository configRepository;
  private boolean bridgeRequestedRunning;

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

    content.addView(createRemoteAccessCard(), cardParams());

    cameraCard = new CameraCard(this);
    content.addView(cameraCard.view(), cardParams());

    bluetoothCard = new BluetoothCard(this);
    content.addView(bluetoothCard.view(), cardParams());

    termuxServicesCard = new TermuxServicesCard(this);
    content.addView(termuxServicesCard.view(), cardParams());

    TextView footer = text("OPENROADC0DE  •  BUILD " + BuildConfig.VERSION_NAME, 11, MUTED);
    footer.setGravity(Gravity.CENTER);
    footer.setLetterSpacing(.12f);
    footer.setPadding(0, dp(8), 0, 0);
    content.addView(footer);

    scrollView.addView(content);
    setContentView(scrollView);
  }

  private LinearLayout createRemoteAccessCard() {
    LinearLayout card = card();
    addSectionHeading(card, "REMOTE SENSOR ACCESS", BLUE,
        "Share sensor telemetry with devices on this network");
    Switch remoteAccessSwitch = new Switch(this);
    remoteAccessSwitch.setText("Allow network clients");
    remoteAccessSwitch.setTextColor(TEXT);
    remoteAccessSwitch.setTextSize(15);
    remoteAccessSwitch.setPadding(dp(4), dp(4), dp(4), dp(8));
    boolean remoteEnabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
    remoteAccessSwitch.setChecked(remoteEnabled);
    card.addView(remoteAccessSwitch);
    remoteAccessStatus = statusPill("", remoteEnabled ? GREEN : MUTED);
    card.addView(remoteAccessStatus);
    updateRemoteAccessStatus();
    remoteAccessSwitch.setOnCheckedChangeListener((buttonView, checked) -> setRemoteAccess(checked));
    return card;
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
    LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(54), dp(54));
    markParams.setMargins(0, 0, dp(2), 0);
    brand.addView(mark, markParams);

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
    LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(46), dp(46));
    badgeParams.setMargins(dp(4), 0, 0, 0);
    brand.addView(badge, badgeParams);
    parent.addView(brand);
  }

  private void addBrandWord(LinearLayout row, String value, int color) {
    TextView word = text(value, 21, color);
    word.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    word.setLetterSpacing(.035f);
    row.addView(word);
  }

  private LinearLayout card() {
    return UiTheme.card(this);
  }

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
    view.setBackground(UiTheme.rounded(this, SURFACE_RAISED, BORDER, 9));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(8));
    view.setLayoutParams(params);
    return view;
  }

  private TextView text(String value, float size, int color) {
    return UiTheme.text(this, value, size, color);
  }

  private int dp(int value) {
    return UiTheme.dp(this, value);
  }

  @Override
  protected void onResume() {
    super.onResume();
    updateRemoteAccessStatus();
    dashboardHandler.post(dashboardRefresh);
    if (cameraCard != null) cameraCard.refresh();
    if (bluetoothCard != null) bluetoothCard.start();
    if (termuxServicesCard != null) termuxServicesCard.start();
  }

  @Override
  protected void onPause() {
    dashboardHandler.removeCallbacks(dashboardRefresh);
    if (bluetoothCard != null) bluetoothCard.stop();
    if (termuxServicesCard != null) termuxServicesCard.stop();
    super.onPause();
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
        JSONObject imu = getJson(IMU_URL);
        JSONObject position = getJson(LOCATION_URL);
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
      remoteAccessStatus.setText(
          "●  Disabled • localhost only • 127.0.0.1:" + SensorBridgeService.PORT);
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
        for (InetAddress address : Collections.list(network.getInetAddresses())) {
          if (address instanceof Inet4Address && !address.isLoopbackAddress())
            return address.getHostAddress();
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(requestCode, permissions, grants);
    if (bluetoothCard != null && bluetoothCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (cameraCard != null && cameraCard.onRequestPermissionsResult(requestCode, grants)) return;

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
    }
  }
}
