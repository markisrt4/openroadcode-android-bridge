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
import android.view.View;
import android.widget.Button;
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
import org.openroadcode.androidbridge.ui.CircuitIconView;
import org.openroadcode.androidbridge.ui.ExpandableCard;
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
  private String currentScreen = "dashboard";

  private final Runnable dashboardRefresh = new Runnable() {
    @Override public void run() {
      if (!dashboardActive) return;
      if (sensorCard != null) refreshDashboard();
      if (cameraCard != null) cameraCard.refresh();
      if (playbackAudioCard != null) playbackAudioCard.refresh();
      if (rtlSdrCard != null) rtlSdrCard.refresh();
      dashboardHandler.postDelayed(this, DASHBOARD_PERIOD_MS);
    }
  };

  private ScrollView scrollView;
  private LinearLayout content;
  private SensorCard sensorCard;
  private RemoteAccessCard remoteAccessCard;
  private CameraCard cameraCard;
  private PlaybackAudioCard playbackAudioCard;
  private RtlSdrCard rtlSdrCard;
  private BluetoothCard bluetoothCard;
  private TermuxServicesCard termuxServicesCard;
  private BridgeServiceManager serviceManager;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    serviceManager = new BridgeServiceManager(this);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);

    scrollView = new ScrollView(this);
    scrollView.setBackgroundColor(BG);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(10), dp(18), dp(10), dp(28));
    scrollView.addView(content);
    setContentView(scrollView);

    showDashboard();
  }

  private void showDashboard() {
    stopVisibleCards();
    currentScreen = "dashboard";
    resetContent();
    addBrandHeader(content);
    content.addView(new SubsystemDashboard(this, this::showSubsystem).view());
    addFooter();
    scrollView.scrollTo(0, 0);
  }

  private void showSubsystem(String subsystem) {
    stopVisibleCards();
    currentScreen = subsystem;
    resetContent();
    addBrandHeader(content);

    switch (subsystem) {
      case SubsystemDashboard.AUTOMOTIVE -> showAutomotive();
      case SubsystemDashboard.NAVIGATION -> showNavigation();
      case SubsystemDashboard.MEDIA -> showMedia();
      case SubsystemDashboard.CONNECTIVITY -> showConnectivity();
      case SubsystemDashboard.RUNTIME -> showRuntime();
      default -> showDashboard();
    }

    addFooter();
    scrollView.scrollTo(0, 0);
    if (dashboardActive) startVisibleCards();
  }

  private void showAutomotive() {
    addSubsystemHeader("▣", "AUTOMOTIVE", "Vehicle bridge and automotive runtime", GREEN);

    bluetoothCard = new BluetoothCard(this, serviceManager);
    addServiceCard(content, "VEHICLE DATA",
        serviceManager.vehicleConfig().provider().displayName(), GREEN,
        bluetoothCard.view(), true, false);

    termuxServicesCard = new TermuxServicesCard(this, "openroadcode-automotive");
    addServiceCard(content, "AUTOMOTIVE SERVICE",
        "Live / simulated input profile", SILVER,
        termuxServicesCard.view(), true, true);
  }

  private void showNavigation() {
    addSubsystemHeader("⌖", "NAVIGATION", "Phone motion, GPS, and navigation runtime", BLUE);

    ServiceConfig sensorConfig = serviceManager.sensorConfig();
    sensorCard = new SensorCard(this, sensorConfig.provider(), this::selectSensorProvider,
        this::startBridge, this::stopBridge);
    sensorCard.setRunning(sensorConfig.enabled());
    addServiceCard(content, "MOTION & POSITION",
        sensorConfig.provider().displayName(), BLUE, sensorCard.view(), true, false);

    termuxServicesCard = new TermuxServicesCard(
        this, this::ensureNavigationSensorBridge, "openroadcode-navigation");
    addServiceCard(content, "NAVIGATION SERVICE",
        "Live / simulated input profile", SILVER,
        termuxServicesCard.view(), true, true);
  }

  private void showMedia() {
    addSubsystemHeader("◉", "MEDIA I/O", "Camera, playback-audio, and SDR bridges", RED);

    cameraCard = new CameraCard(this);
    addServiceCard(content, "CAMERA",
        "Video capture and stream", RED, cameraCard.view(), true, false);

    playbackAudioCard = new PlaybackAudioCard(this);
    addServiceCard(content, "PLAYBACK AUDIO",
        "Audio bridge and playback", BLUE, playbackAudioCard.view(), true, false);

    rtlSdrCard = new RtlSdrCard(this);
    addServiceCard(content, "RTL-SDR",
        "USB receiver bridge • localhost TCP", GREEN, rtlSdrCard.view(), true, true);
  }

  private void showConnectivity() {
    addSubsystemHeader("⇄", "CONNECTIVITY", "Choose where Android bridge data is reachable", BLUE);

    boolean remoteEnabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
    remoteAccessCard = new RemoteAccessCard(this, remoteEnabled, this::setRemoteAccess);
    addServiceCard(content, "REMOTE SENSOR ACCESS",
        remoteEnabled ? "Shared on local network" : "This phone only", GREEN,
        remoteAccessCard.view(), true, true);
    updateRemoteAccessStatus();
  }

  private void showRuntime() {
    addSubsystemHeader("⚙", "RUNTIME", "Termux and remote Linux service orchestration", SILVER);

    termuxServicesCard = new TermuxServicesCard(this);
    addServiceCard(content, "OPENROADCODE SERVICES",
        "Targets • profiles • core stack", SILVER,
        termuxServicesCard.view(), true, true);
  }

  private void addSubsystemHeader(String icon, String title, String subtitle, int accent) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, 0, 0, dp(12));

    Button back = UiTheme.actionButton(this, "‹", UiTheme.SURFACE_RAISED, v -> showDashboard());
    back.setTextSize(24);
    LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(46), dp(46));
    backParams.setMargins(0, 0, dp(10), 0);
    row.addView(back, backParams);

    CircuitIconView iconView = new CircuitIconView(this, icon, accent);
    row.addView(iconView, new LinearLayout.LayoutParams(dp(52), dp(52)));

    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);

    TextView heading = text(title, 17, UiTheme.TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.08f);
    labels.addView(heading);

    TextView detail = text(subtitle, 11, MUTED);
    detail.setPadding(0, dp(2), 0, 0);
    labels.addView(detail);

    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    content.addView(row);
  }

  private void resetContent() {
    content.removeAllViews();
    sensorCard = null;
    remoteAccessCard = null;
    cameraCard = null;
    playbackAudioCard = null;
    rtlSdrCard = null;
    bluetoothCard = null;
    termuxServicesCard = null;
  }

  private void stopVisibleCards() {
    if (bluetoothCard != null) bluetoothCard.stop();
    if (termuxServicesCard != null) termuxServicesCard.stop();
  }

  private void startVisibleCards() {
    updateRemoteAccessStatus();
    if (sensorCard != null) reconcileSensor(false);
    if (cameraCard != null) cameraCard.refresh();
    if (playbackAudioCard != null) playbackAudioCard.refresh();
    if (rtlSdrCard != null) rtlSdrCard.refresh();
    if (bluetoothCard != null) bluetoothCard.start();
    if (termuxServicesCard != null) termuxServicesCard.start();
  }

  private void selectSensorProvider(ServiceProvider provider) {
    if (serviceManager.sensorConfig().provider() == provider) return;
    serviceManager.suspendSensor();
    serviceManager.setSensorProvider(provider);
    sensorPermissionRequired = false;
    sensorStartFailed = false;
    if (sensorCard == null) return;
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

  private void addServiceCard(
      LinearLayout parent,
      String title,
      String subtitle,
      int accent,
      View detailView,
      boolean initiallyExpanded,
      boolean sectionEnd) {
    ExpandableCard card = new ExpandableCard(
        this, title, subtitle, accent, detailView, initiallyExpanded);
    parent.addView(card.view(), sectionEnd ? sectionEndCardParams() : cardParams());
  }

  private void addBrandWord(LinearLayout row, String value, int color) {
    TextView word = text(value, 21, color);
    word.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    word.setLetterSpacing(.035f);
    row.addView(word);
  }

  private void addFooter() {
    TextView footer = text("OPENROADC0DE  •  BUILD " + BuildConfig.VERSION_NAME, 11, MUTED);
    footer.setGravity(Gravity.CENTER);
    footer.setLetterSpacing(.12f);
    footer.setPadding(0, dp(8), 0, 0);
    content.addView(footer);
  }

  private LinearLayout.LayoutParams cardParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(10));
    return params;
  }

  private LinearLayout.LayoutParams sectionEndCardParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(18));
    return params;
  }

  private TextView text(String value, float size, int color) {
    return UiTheme.text(this, value, size, color);
  }

  private int dp(int value) {
    return UiTheme.dp(this, value);
  }

  @Override protected void onResume() {
    super.onResume();
    dashboardActive = true;
    startVisibleCards();
    dashboardHandler.removeCallbacks(dashboardRefresh);
    dashboardHandler.post(dashboardRefresh);
  }

  @Override protected void onPause() {
    dashboardActive = false;
    dashboardHandler.removeCallbacks(dashboardRefresh);
    stopVisibleCards();
    super.onPause();
  }

  @Override public void onBackPressed() {
    if (!"dashboard".equals(currentScreen)) {
      showDashboard();
      return;
    }
    super.onBackPressed();
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
    if (sensorCard == null || sensorPollInFlight) return;
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
        if (!dashboardActive || sensorCard == null
            || generation != serviceManager.sensorGeneration()) return;
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
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  private boolean sensorNeedsLocationPermission() {
    return serviceManager.sensorConfig().provider() == ServiceProvider.ANDROID_SENSORS;
  }

  private void reconcileSensor(boolean requestPermission) {
    if (sensorCard == null) return;
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

  private void ensureNavigationSensorBridge() {
    if (serviceManager.sensorConfig().provider() != ServiceProvider.ANDROID_SENSORS) {
      serviceManager.setSensorProvider(ServiceProvider.ANDROID_SENSORS);
    }
    serviceManager.requestSensorEnabled();
    sensorStartFailed = false;
    reconcileSensor(true);
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
    if (sensorCard == null) return;
    sensorCard.setRunning(false);
    sensorCard.setStatus("●  Bridge stopped", MUTED);
    sensorCard.clear();
  }

  private void setRemoteAccess(boolean enabled) {
    getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .edit().putBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, enabled).apply();
    if (remoteAccessCard != null) remoteAccessCard.setEnabled(enabled);
    updateRemoteAccessStatus();
    if (!serviceManager.sensorRequested()) return;
    serviceManager.suspendSensor();
    sensorStartFailed = false;
    if (sensorCard != null) reconcileSensor(true);
  }

  private void updateRemoteAccessStatus() {
    if (remoteAccessCard == null) return;
    boolean enabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
    remoteAccessCard.setEnabled(enabled);
    remoteAccessCard.showStatus(
        enabled, enabled ? findLanAddress() : null, SensorBridgeService.PORT);
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

  @Override public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(requestCode, permissions, grants);
    if (playbackAudioCard != null
        && playbackAudioCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (bluetoothCard != null
        && bluetoothCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (cameraCard != null
        && cameraCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (requestCode == LOCATION_PERMISSION_REQUEST) {
      locationPermissionPending = false;
      if (!serviceManager.sensorRequested() || sensorCard == null) return;
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
    if (playbackAudioCard != null
        && playbackAudioCard.onActivityResult(requestCode, resultCode, data)) return;
  }
}
