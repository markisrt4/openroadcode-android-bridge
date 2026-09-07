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
import org.openroadcode.androidbridge.config.ServiceConfig;
import org.openroadcode.androidbridge.config.ServiceProvider;
import org.openroadcode.androidbridge.runtime.BridgeServiceManager;
import org.openroadcode.androidbridge.ui.SensorCard;
import org.openroadcode.androidbridge.ui.UiTheme;

public final class MainActivity extends Activity {
  private static final int LOCATION_PERMISSION_REQUEST = 1001;
  private static final long DASHBOARD_PERIOD_MS = 500;
  private static final String IMU_URL = "http://127.0.0.1:8766/imu";
  private static final String LOCATION_URL = "http://127.0.0.1:8766/location";
  private static final int BG = UiTheme.BG, MUTED = UiTheme.MUTED, SILVER = UiTheme.SILVER;
  private static final int BLUE = UiTheme.BLUE, GREEN = UiTheme.GREEN, RED = UiTheme.RED;
  private final Handler dashboardHandler = new Handler(Looper.getMainLooper());
  private boolean dashboardActive;
  private boolean sensorPollInFlight;
  private boolean locationPermissionPending;
  private boolean sensorPermissionRequired;
  private boolean sensorStartFailed;
  private String sensorStartError = "";
  private final Runnable dashboardRefresh = new Runnable() {
    @Override public void run() {
      if (!dashboardActive) return;
      refreshDashboard();
      if (cameraCard != null) cameraCard.refresh();
      if (playbackAudioCard != null) playbackAudioCard.refresh();
      dashboardHandler.postDelayed(this, DASHBOARD_PERIOD_MS);
    }
  };
  private SensorCard sensorCard;
  private RemoteAccessCard remoteAccessCard;
  private CameraCard cameraCard;
  private PlaybackAudioCard playbackAudioCard;
  private BluetoothCard bluetoothCard;
  private TermuxServicesCard termuxServicesCard;
  private BridgeServiceManager serviceManager;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    serviceManager = new BridgeServiceManager(this);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    ScrollView scrollView = new ScrollView(this);
    scrollView.setBackgroundColor(BG);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(10), dp(18), dp(10), dp(28));
    addBrandHeader(content);
    ServiceConfig sensorConfig = serviceManager.sensorConfig();
    sensorCard = new SensorCard(this, sensorConfig.provider(), this::selectSensorProvider,
        this::startBridge, this::stopBridge);
    sensorCard.setRunning(sensorConfig.enabled());
    content.addView(sensorCard.view(), cardParams());
    boolean remoteEnabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
    remoteAccessCard = new RemoteAccessCard(this, remoteEnabled, this::setRemoteAccess);
    content.addView(remoteAccessCard.view(), cardParams());
    updateRemoteAccessStatus();
    cameraCard = new CameraCard(this);
    content.addView(cameraCard.view(), cardParams());
    playbackAudioCard = new PlaybackAudioCard(this);
    content.addView(playbackAudioCard.view(), cardParams());
    bluetoothCard = new BluetoothCard(this, serviceManager);
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

  private void selectSensorProvider(ServiceProvider provider) {
    if (serviceManager.sensorConfig().provider() == provider) return;
    serviceManager.suspendSensor();
    serviceManager.setSensorProvider(provider);
    sensorPermissionRequired = false;
    sensorStartFailed = false;
    sensorCard.clear();
    if (serviceManager.sensorRequested()) reconcileSensor(true);
    else {
      sensorCard.setRunning(false);
      sensorCard.setStatus("●  " + provider.displayName() + " selected", MUTED);
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
  private LinearLayout.LayoutParams cardParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(14));
    return params;
  }
  private TextView text(String value, float size, int color) { return UiTheme.text(this, value, size, color); }
  private int dp(int value) { return UiTheme.dp(this, value); }

  @Override protected void onResume() {
    super.onResume();
    updateRemoteAccessStatus();
    dashboardActive = true;
    reconcileSensor(false);
    dashboardHandler.removeCallbacks(dashboardRefresh);
    dashboardHandler.post(dashboardRefresh);
    if (cameraCard != null) cameraCard.refresh();
    if (playbackAudioCard != null) playbackAudioCard.refresh();
    if (bluetoothCard != null) bluetoothCard.start();
    if (termuxServicesCard != null) termuxServicesCard.start();
  }
  @Override protected void onPause() {
    dashboardActive = false;
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
    } finally { connection.disconnect(); }
  }
  private void refreshDashboard() {
    if (sensorPollInFlight) return;
    sensorPollInFlight = true;
    final long generation = serviceManager.sensorGeneration();
    final ServiceProvider provider = serviceManager.sensorConfig().provider();
    new Thread(() -> {
      JSONObject imu = null, position = null;
      try {
        imu = getJson(IMU_URL);
        position = getJson(LOCATION_URL);
      } catch (Exception ignored) { }
      final JSONObject sample = imu, fix = position;
      runOnUiThread(() -> {
        sensorPollInFlight = false;
        if (!dashboardActive || generation != serviceManager.sensorGeneration()) return;
        if (!serviceManager.sensorRequested()) {
          sensorCard.setRunning(false);
          sensorCard.setStatus("●  Bridge stopped", MUTED);
          sensorCard.clear();
          return;
        }
        if (sensorPermissionRequired) {
          sensorCard.setStatus("●  Location permission required", RED);
          sensorCard.clear();
          return;
        }
        if (sensorStartFailed) {
          sensorCard.setStatus("●  " + sensorStartError, RED);
          sensorCard.clear();
          return;
        }
        if (sample == null || fix == null) {
          if (serviceManager.sensorState() == BridgeServiceManager.ServiceState.RUNNING)
            serviceManager.markSensorStarting();
          sensorCard.setStatus("●  Bridge starting or unavailable…", BLUE);
          sensorCard.setRunning(true);
          sensorCard.clear();
          return;
        }
        String source = sample.optString("source", "");
        if (!source.isEmpty()) {
          boolean matches = provider == ServiceProvider.SIMULATED_DRIVE
              ? source.toLowerCase(java.util.Locale.ROOT).contains("simulat")
              : !source.toLowerCase(java.util.Locale.ROOT).contains("simulat");
          if (!matches) {
            sensorCard.setStatus("●  Waiting for selected sensor source…", BLUE);
            sensorCard.clear();
            return;
          }
        }
        serviceManager.markSensorRunning();
        sensorCard.setRunning(true);
        boolean ready = sample.optBoolean("ready");
        String suffix = source.isEmpty() ? "" : " • " + source;
        sensorCard.setStatus(ready ? "●  Bridge running • sensors ready" + suffix
                                   : "●  Bridge running • waiting for sensors" + suffix,
            ready ? GREEN : BLUE);
        sensorCard.displaySample(sample);
        sensorCard.displayPosition(fix);
      });
    }, "orc-dashboard-refresh").start();
  }

  private boolean hasLocationPermission() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }
  private boolean sensorNeedsLocationPermission() {
    return serviceManager.sensorConfig().provider() == ServiceProvider.ANDROID_SENSORS;
  }
  private void reconcileSensor(boolean requestPermission) {
    if (!serviceManager.sensorRequested()) {
      sensorCard.setRunning(false);
      sensorCard.setStatus("●  Bridge stopped", MUTED);
      return;
    }
    sensorCard.setRunning(true);
    if (sensorNeedsLocationPermission() && !hasLocationPermission()) {
      serviceManager.suspendSensor();
      sensorPermissionRequired = true;
      sensorCard.clear();
      sensorCard.setStatus("●  Location permission required", RED);
      if (requestPermission && !locationPermissionPending) {
        locationPermissionPending = true;
        requestPermissions(new String[] {Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
      }
      return;
    }
    sensorPermissionRequired = false;
    if (sensorStartFailed && !requestPermission) {
      sensorCard.setStatus("●  " + sensorStartError, RED);
      return;
    }
    sensorStartFailed = false;
    try {
      serviceManager.startRequestedSensor();
      if (serviceManager.sensorState() != BridgeServiceManager.ServiceState.RUNNING)
        sensorCard.setStatus("●  Bridge starting…", BLUE);
    } catch (RuntimeException e) {
      sensorStartFailed = true;
      sensorStartError = "Unable to start sensor bridge: " + e.getClass().getSimpleName();
      sensorCard.setStatus("●  " + sensorStartError, RED);
    }
  }
  private void startBridge() {
    serviceManager.requestSensorEnabled();
    sensorStartFailed = false;
    reconcileSensor(true);
  }
  private void stopBridge() {
    serviceManager.disableSensor();
    sensorPermissionRequired = false;
    sensorStartFailed = false;
    sensorCard.setRunning(false);
    sensorCard.setStatus("●  Bridge stopped", MUTED);
    sensorCard.clear();
  }
  private void setRemoteAccess(boolean enabled) {
    getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .edit().putBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, enabled).apply();
    remoteAccessCard.setEnabled(enabled);
    updateRemoteAccessStatus();
    if (!serviceManager.sensorRequested()) return;
    serviceManager.suspendSensor();
    sensorStartFailed = false;
    reconcileSensor(true);
  }
  private void updateRemoteAccessStatus() {
    if (remoteAccessCard == null) return;
    boolean enabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
    remoteAccessCard.setEnabled(enabled);
    remoteAccessCard.showStatus(enabled, enabled ? findLanAddress() : null, SensorBridgeService.PORT);
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
    } catch (Exception ignored) { }
    return null;
  }
  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(requestCode, permissions, grants);
    if (playbackAudioCard != null && playbackAudioCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (bluetoothCard != null && bluetoothCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (cameraCard != null && cameraCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (requestCode == LOCATION_PERMISSION_REQUEST) {
      locationPermissionPending = false;
      if (!serviceManager.sensorRequested()) return;
      if (hasLocationPermission() || !sensorNeedsLocationPermission()) {
        sensorPermissionRequired = false;
        sensorStartFailed = false;
        reconcileSensor(false);
      } else {
        serviceManager.suspendSensor();
        sensorPermissionRequired = true;
        sensorCard.setRunning(true);
        sensorCard.setStatus("●  Location permission required", RED);
        sensorCard.clear();
      }
    }
  }
  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (playbackAudioCard != null && playbackAudioCard.onActivityResult(requestCode, resultCode, data)) return;
  }
}
