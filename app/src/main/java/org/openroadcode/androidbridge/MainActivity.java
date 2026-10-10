package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import java.lang.ref.WeakReference;
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
import org.openroadcode.androidbridge.ui.EnvironmentalSensorCard;
import org.openroadcode.androidbridge.ui.SensorCard;
import org.openroadcode.androidbridge.ui.UiTheme;

public final class MainActivity extends Activity {
  private static volatile WeakReference<MainActivity> visibleActivity = new WeakReference<>(null);

  static boolean launchRtlTcpProvider(String uri, String driverPackage, String driverActivity) {
    MainActivity activity = visibleActivity.get();
    if (activity == null) return false;
    java.util.concurrent.FutureTask<Boolean> launch = new java.util.concurrent.FutureTask<>(() -> {
      if (visibleActivity.get() != activity || activity.isFinishing()) return false;
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
      intent.setClassName(driverPackage, driverActivity);
      activity.startActivity(intent);
      return true;
    });
    if (Looper.myLooper() == Looper.getMainLooper()) launch.run();
    else activity.dashboardHandler.post(launch);
    try {
      return launch.get(3, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      launch.cancel(false);
      Thread.currentThread().interrupt();
      return false;
    } catch (java.util.concurrent.TimeoutException error) {
      launch.cancel(false);
      return false;
    } catch (java.util.concurrent.ExecutionException error) {
      throw new IllegalStateException("Unable to launch RTL-TCP provider", error.getCause());
    }
  }

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
      if (sensorCard != null || environmentalSensorCard != null) refreshDashboard();
      if (cameraCard != null) cameraCard.refresh();
      if (playbackAudioCard != null) playbackAudioCard.refresh();
      if (radioCard != null) radioCard.refresh();
      if (remoteAccessCard != null) updateRemoteAccessStatus();
      if (pcmAudioOutputCard != null) pcmAudioOutputCard.refresh();
      dashboardHandler.postDelayed(this, DASHBOARD_PERIOD_MS);
    }
  };

  private ScrollView scrollView;
  private LinearLayout content;
  private SensorCard sensorCard;
  private EnvironmentalSensorCard environmentalSensorCard;
  private RemoteAccessCard remoteAccessCard;
  private CameraCard cameraCard;
  private PlaybackAudioCard playbackAudioCard;
  private RadioCard radioCard;
  private PcmAudioOutputCard pcmAudioOutputCard;
  private BluetoothCard bluetoothCard;
  private TermuxServicesCard termuxServicesCard;
  private SystemPerformanceCard systemPerformanceCard;
  private CompanionStatusCard companionStatusCard;
  private RuntimeLogsScreen runtimeLogsScreen;
  private SmsGatewayCard smsGatewayCard;
  private BridgeServiceManager serviceManager;
  private final BridgeServiceLog sensorDiagnostic = BridgeLog.service(BridgeServiceLog.Service.SENSORS);

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    serviceManager = new BridgeServiceManager(this);
    startForegroundService(new Intent(this, RtlTcpProviderControlService.class));
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
    companionStatusCard = new CompanionStatusCard(this);
    content.addView(companionStatusCard.view(), cardParams());
    content.addView(new SubsystemDashboard(this, this::showSubsystem).view());
    if (dashboardActive) companionStatusCard.start();
    addFooter();
    scrollView.scrollTo(0, 0);
  }

  private void showSubsystem(String subsystem) {
    stopVisibleCards();
    currentScreen = subsystem;
    resetContent();

    switch (subsystem) {
      case SubsystemDashboard.AUTOMOTIVE -> showAutomotive();
      case SubsystemDashboard.NAVIGATION -> showNavigation();
      case SubsystemDashboard.MEDIA -> showMedia();
      case SubsystemDashboard.RADIO -> showRadio();
      case SubsystemDashboard.ENVIRONMENTAL -> showEnvironmental();
      case SubsystemDashboard.RUNTIME -> showRuntime();
      case SubsystemDashboard.PERFORMANCE -> showPerformance();
      case SubsystemDashboard.CONFIGURATION -> showConfiguration();
      case SubsystemDashboard.DIAGNOSTICS -> showDiagnostics();
      default -> showDashboard();
    }

    addFooter();
    scrollView.scrollTo(0, 0);
    if (dashboardActive) startVisibleCards();
  }

  private void showAutomotive() {
    addSubsystemHeader("🚗", "Automotive", "Vehicle bridge and automotive runtime", GREEN);

    termuxServicesCard = new TermuxServicesCard(this, "openroadcode-automotive");
    addServiceCard(content, "AUTOMOTIVE SERVICE", "Runtime service and input source", GREEN,
        termuxServicesCard.view(), true, false);
    bluetoothCard = new BluetoothCard(this, serviceManager);
    addServiceCard(content, "VEHICLE DATA",
        serviceManager.vehicleConfig().provider().displayName(), GREEN,
        bluetoothCard.view(), true, false);

  }

  private void showNavigation() {
    addSubsystemHeader("⌖", "Navigation", "Phone motion, GPS, and navigation runtime", BLUE);

    ServiceConfig sensorConfig = serviceManager.sensorConfig();
    sensorCard = new SensorCard(this, sensorConfig.provider(), this::selectSensorProvider,
        this::startBridge, this::stopBridge);
    sensorCard.setRunning(sensorConfig.enabled());
    termuxServicesCard = new TermuxServicesCard(this, this::ensureNavigationSensorBridge,
        "openroadcode-navigation");
    addServiceCard(content, "NAVIGATION SERVICE", "Runtime service and input source", BLUE,
        termuxServicesCard.view(), true, false);
    addServiceCard(content, "MOTION & POSITION",
        sensorConfig.provider().displayName(), BLUE, sensorCard.view(), true, false);
    showSensorSharing();

  }

  private void showEnvironmental() {
    addSubsystemHeader("☀", "Environment", "Ambient light and environmental telemetry", GREEN);

    environmentalSensorCard = new EnvironmentalSensorCard(this);
    addServiceCard(content, "ENVIRONMENT", "Android environmental sensors", GREEN,
        environmentalSensorCard.view(), true, true);
  }

  private void showMedia() {
    addSubsystemHeader("◉", "Media", "Camera, playback capture and audio output", RED);

    cameraCard = new CameraCard(this);
    addServiceCard(content, "CAMERA",
        "Video capture and stream", RED, cameraCard.view(), true, false);

    playbackAudioCard = new PlaybackAudioCard(this);
    addServiceCard(content, "PLAYBACK AUDIO CAPTURE",
        "Android playback → ORC PCM", BLUE, playbackAudioCard.view(), true, false);

    pcmAudioOutputCard = new PcmAudioOutputCard(this);
    addServiceCard(content, "AUDIO OUTPUT",
        "ORC PCM → Android AudioTrack", BLUE, pcmAudioOutputCard.view(), true, false);

  }

  private void showConfiguration() {
    addSubsystemHeader("⚙", "Configuration",
        "Pair, choose, and manage computing units", SILVER);

    RemoteDeviceManagementCard remoteDevices = new RemoteDeviceManagementCard(this, null);
    addServiceCard(content, "REMOTE DEVICE MANAGEMENT",
        "Pairing • remote Linux connection", SILVER,
        remoteDevices.view(), true, false);

    smsGatewayCard = new SmsGatewayCard(this, serviceManager);
    addServiceCard(content, "SMS GATEWAY", "Permission-controlled local SMS access", BLUE,
        smsGatewayCard.view(), true, true);
  }

  private void showSensorSharing() {
    boolean remoteEnabled = getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
        .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false);
    remoteAccessCard = new RemoteAccessCard(this, remoteEnabled, this::setRemoteAccess);
    addServiceCard(content, "Sensor sharing",
        "This phone or local network", GREEN, remoteAccessCard.view(), false, false);
    updateRemoteAccessStatus();

  }

  private void showRadio() {
    addSubsystemHeader("◉", "Radio", "Radio hardware and aircraft reception", UiTheme.VIOLET);
    radioCard = new RadioCard(this);
    addServiceCard(content, "RTL-SDR", "USB receiver and local radio bridge", UiTheme.VIOLET,
        radioCard.view(), true, false);
    termuxServicesCard = new TermuxServicesCard(this, "openroadcode-adsb");
    addServiceCard(content, "ADS-B", "Aircraft receiver service on your selected runtime", BLUE,
        termuxServicesCard.view(), true, true);
  }

  private void showRuntime() {
    addSubsystemHeader("≡", "Runtime", "Running Termux and remote Linux services", SILVER);

    termuxServicesCard = new TermuxServicesCard(this);
    content.addView(UiTheme.actionButton(this, "Paired devices & settings  ›", UiTheme.SURFACE_RAISED,
        v -> showSubsystem(SubsystemDashboard.CONFIGURATION)), cardParams());
    addServiceCard(content, "OPENROADCODE SERVICES",
        "Computing unit • message broker • core stack", SILVER,
        termuxServicesCard.view(), true, true);
  }

  private void showPerformance() {
    addSubsystemHeader("▥", "Performance", "Computing-unit workload, sensors, and service traffic", BLUE);
    systemPerformanceCard = new SystemPerformanceCard(this);
    addServiceCard(content, "COMPUTING UNIT PERFORMANCE",
        "CPU • memory • thermal • storage • activity", BLUE,
        systemPerformanceCard.view(), true, true);
  }

  private void showDiagnostics() {
    addSubsystemHeader("≡", "Live logs", "Recent history and live runtime logs", SILVER);
    runtimeLogsScreen = new RuntimeLogsScreen(this);
    content.addView(runtimeLogsScreen.view(), sectionEndCardParams());
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

    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);

    TextView heading = text(title, 21, UiTheme.TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
    environmentalSensorCard = null;
    remoteAccessCard = null;
    cameraCard = null;
    playbackAudioCard = null;
    radioCard = null;
    pcmAudioOutputCard = null;
    bluetoothCard = null;
    termuxServicesCard = null;
    systemPerformanceCard = null;
    companionStatusCard = null;
    runtimeLogsScreen = null;
    smsGatewayCard = null;
  }

  private void stopVisibleCards() {
    if (companionStatusCard != null) companionStatusCard.stop();
    if (systemPerformanceCard != null) systemPerformanceCard.stop();
    if (bluetoothCard != null) bluetoothCard.stop();
    if (termuxServicesCard != null) termuxServicesCard.stop();
    if (runtimeLogsScreen != null) runtimeLogsScreen.stop();
  }

  private void startVisibleCards() {
    if (companionStatusCard != null) companionStatusCard.start();
    updateRemoteAccessStatus();
    if (systemPerformanceCard != null) systemPerformanceCard.start();
    if (sensorCard != null) reconcileSensor(false);
    if (cameraCard != null) cameraCard.refresh();
    if (playbackAudioCard != null) playbackAudioCard.refresh();
    if (radioCard != null) radioCard.refresh();
    if (pcmAudioOutputCard != null) pcmAudioOutputCard.refresh();
    if (bluetoothCard != null) bluetoothCard.start();
    if (termuxServicesCard != null) termuxServicesCard.start();
    if (runtimeLogsScreen != null) runtimeLogsScreen.start();
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
    LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(dp(42), dp(42));
    markParams.setMargins(0, 0, dp(10), 0);
    brand.addView(mark, markParams);

    LinearLayout words = new LinearLayout(this);
    words.setOrientation(LinearLayout.VERTICAL);
    TextView title = text("ORC Companion", 22, UiTheme.TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    words.addView(title);
    TextView subtitle = text("OPEN ROAD CODE", 10, MUTED);
    subtitle.setLetterSpacing(.12f);
    subtitle.setPadding(0, dp(3), 0, 0);
    words.addView(subtitle);
    brand.addView(words, new LinearLayout.LayoutParams(0, -2, 1));

    Button settings = UiTheme.actionButton(this, "⚙", UiTheme.SURFACE_RAISED,
        v -> showSubsystem(SubsystemDashboard.CONFIGURATION));
    settings.setTextSize(20);
    settings.setContentDescription("Configuration: paired devices and network sharing");
    brand.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));

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
    View section = detailView;
    if (!initiallyExpanded) {
      section = new ExpandableCard(this, title, subtitle, accent, detailView, false).view();
    }
    parent.addView(section, sectionEnd ? sectionEndCardParams() : cardParams());
  }

  private void addFooter() {
    TextView footer = text("ORC Companion  •  " + BuildConfig.VERSION_NAME, 11, MUTED);
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

  @Override protected void onStart() {
    super.onStart();
    visibleActivity = new WeakReference<>(this);
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

  @Override protected void onStop() {
    MainActivity visible = visibleActivity.get();
    if (visible == this) visibleActivity.clear();
    super.onStop();
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
    if ((sensorCard == null && environmentalSensorCard == null) || sensorPollInFlight) return;
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
        if (!dashboardActive || (sensorCard == null && environmentalSensorCard == null)
            || generation != serviceManager.sensorGeneration()) return;
        if (environmentalSensorCard != null) {
          if (sample == null) environmentalSensorCard.clear();
          else environmentalSensorCard.displaySample(sample);
          if (sensorCard == null) return;
        }
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
    if (sensorNeedsLocationPermission())
      sensorDiagnostic.available(BridgeServiceLog.Condition.LOCATION_PERMISSION, hasLocationPermission());
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
      sensorDiagnostic.failure(BridgeServiceLog.Event.FAILED, e);
      sensorStartFailed = true;
      sensorStartError = "Unable to start sensor bridge: " + e.getClass().getSimpleName();
      sensorCard.setStatus("●  " + sensorStartError, RED);
    }
  }

  private boolean ensureNavigationSensorBridge() {
    if (serviceManager.sensorConfig().provider() != ServiceProvider.ANDROID_SENSORS) {
      serviceManager.setSensorProvider(ServiceProvider.ANDROID_SENSORS);
      if (sensorCard != null) sensorCard.setProvider(ServiceProvider.ANDROID_SENSORS);
    }
    org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings runtime =
        new org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings(this);
    if (runtime.target() == org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings.Target.REMOTE_PI
        && !getSharedPreferences(SensorBridgeService.PREFERENCES, MODE_PRIVATE)
            .getBoolean(SensorBridgeService.PREF_REMOTE_ACCESS, false)) setRemoteAccess(true);
    serviceManager.requestSensorEnabled();
    sensorStartFailed = false;
    reconcileSensor(true);
    return !sensorPermissionRequired && !sensorStartFailed;
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
    sensorStartFailed = false;
    try {
      serviceManager.restartRequestedSensor();
    } catch (RuntimeException error) {
      sensorStartFailed = true;
      new android.app.AlertDialog.Builder(this)
          .setTitle("Sensor bridge restart failed")
          .setMessage("The network setting is saved. Restart the bridge from Navigation.")
          .setPositiveButton("OK", null).show();
    }
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
    if (requestCode == SmsGatewayCard.READ_PERMISSION_REQUEST
        || requestCode == SmsGatewayCard.SEND_PERMISSION_REQUEST) {
      if (smsGatewayCard != null) smsGatewayCard.refresh();
      return;
    }
    if (playbackAudioCard != null
        && playbackAudioCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (bluetoothCard != null
        && bluetoothCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (cameraCard != null
        && cameraCard.onRequestPermissionsResult(requestCode, grants)) return;
    if (requestCode == LOCATION_PERMISSION_REQUEST) {
      sensorDiagnostic.available(BridgeServiceLog.Condition.LOCATION_PERMISSION, hasLocationPermission());
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
