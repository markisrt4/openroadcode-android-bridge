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

    boolean remoteEnabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
    remoteAccessCard = new RemoteAccessCard(this, remoteEnabled, this::setRemoteAccess);
    content.addView(remoteAccessCard.view(), cardParams());
    updateRemoteAccessStatus();

    cameraCard = new CameraCard(this);
    content.addView(cameraCard.view(), cardParams());

    playbackAudioCard = new PlaybackAudioCard(this);
    content.addView(playbackAudioCard.view(), cardParams());

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

  private LinearLayout.LayoutParams cardParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(14));
    return params;
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
    if (playbackAudioCard != null) playbackAudioCard.refresh();
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
    remoteAccessCard.setEnabled(enabled);
    updateRemoteAccessStatus();

    if (!bridgeRequestedRunning) return;

    stopService(new Intent(this, SensorBridgeService.class));
    if (!sensorNeedsLocationPermission() || hasLocationPermission()) {
      startSensorBridgeService();
    } else {
      bridgeRequestedRunning = false;
      configRepository.saveSensorConfig(configRepository.sensorConfig().withEnabled(false));
      sensorCard.setRunning(false);
    }
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
    } catch (Exception ignored) {
    }
    return null;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(requestCode, permissions, grants);
    if (playbackAudioCard != null && playbackAudioCard.onRequestPermissionsResult(requestCode, grants)) return;
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

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (playbackAudioCard != null && playbackAudioCard.onActivityResult(requestCode, resultCode, data)) return;
  }
}
