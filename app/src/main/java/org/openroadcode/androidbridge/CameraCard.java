package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.json.JSONObject;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Owns camera-stream presentation and lifecycle orchestration for the bridge dashboard. */
final class CameraCard {
  static final int PERMISSION_REQUEST = 1003;

  private final Activity activity;
  private final LinearLayout view;
  private final TextView status;
  private final TextView details;
  private final TextView endpoint;
  private final Button startButton;
  private final Button stopButton;
  private final Button localButton;
  private final Button wifiButton;
  private final Button cellularButton;
  private boolean requestedRunning;

  CameraCard(Activity activity) {
    this.activity = activity;
    view = UiTheme.card(activity);

    TextView heading = UiTheme.text(activity, "CAMERA STREAM", 18, UiTheme.TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.08f);
    view.addView(heading);
    TextView subtitle = UiTheme.text(activity,
        "Selectable camera • H.264 • 1280×720 • 30 FPS • HTTP 8767", 12, UiTheme.RED);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, dp(2), 0, dp(10));
    view.addView(subtitle);

    status = UiTheme.text(activity, "●  Camera stopped", 13, UiTheme.MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    status.setPadding(dp(10), dp(8), dp(10), dp(8));
    status.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
    statusParams.setMargins(0, 0, 0, dp(8));
    status.setLayoutParams(statusParams);
    view.addView(status);

    CameraPreviewView preview = new CameraPreviewView(activity);
    LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
    previewParams.setMargins(dp(2), dp(2), dp(2), dp(8));
    view.addView(preview, previewParams);

    TextView routeLabel = UiTheme.text(activity, "VIDEO INTERFACE", 11, UiTheme.MUTED);
    routeLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    routeLabel.setLetterSpacing(.10f);
    routeLabel.setPadding(dp(2), dp(4), 0, 0);
    view.addView(routeLabel);

    localButton = actionButton("LOCAL", v -> setInterface(CameraStreamService.INTERFACE_LOCALHOST));
    wifiButton = actionButton("WI-FI", v -> setInterface(CameraStreamService.INTERFACE_WIFI));
    cellularButton = actionButton("5G", v -> setInterface(CameraStreamService.INTERFACE_CELLULAR));
    view.addView(buttonRow(localButton, wifiButton, cellularButton));
    updateInterfaceButtons();

    details = UiTheme.text(activity, "Frames  —    Viewer  —    Preview  —", 13, UiTheme.TEXT);
    details.setTypeface(Typeface.MONOSPACE);
    details.setPadding(dp(2), dp(5), 0, dp(4));
    view.addView(details);

    endpoint = UiTheme.text(activity, "Video endpoint  waiting for network", 12, UiTheme.MUTED);
    endpoint.setTypeface(Typeface.MONOSPACE);
    endpoint.setPadding(dp(2), dp(2), 0, dp(4));
    view.addView(endpoint);
    updateEndpoint();

    startButton = UiTheme.actionButton(activity, "START CAMERA", UiTheme.RED, v -> startCamera());
    stopButton = UiTheme.actionButton(activity, "STOP", UiTheme.SURFACE_RAISED, v -> stopCamera());
    view.addView(buttonRow(startButton, stopButton));
  }

  View view() { return view; }

  void refresh() {
    updateEndpoint();
    new Thread(() -> {
      try {
        String address = address();
        if (address == null) throw new IllegalStateException("No camera interface address");
        JSONObject response = getJson(
            "http://" + address + ":" + CameraStreamService.PORT + "/status");
        activity.runOnUiThread(() -> displayStatus(response));
      } catch (Exception ignored) {
        activity.runOnUiThread(() -> {
          if (!requestedRunning) {
            setStatus("Camera stopped", UiTheme.MUTED);
            details.setText("Frames  —    Viewer  —    Preview  —");
            updateButtons(false, false);
          }
        });
      }
    }, "orc-camera-status-refresh").start();
  }

  boolean onRequestPermissionsResult(int requestCode, int[] grants) {
    if (requestCode != PERMISSION_REQUEST) return false;
    if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
      startCamera();
    } else {
      requestedRunning = false;
      updateButtons(false, false);
      setStatus("Camera permission required", UiTheme.RED);
    }
    return true;
  }

  private void startCamera() {
    if (activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(new String[] {Manifest.permission.CAMERA}, PERMISSION_REQUEST);
      return;
    }
    requestedRunning = true;
    updateButtons(true, false);
    activity.startForegroundService(new Intent(activity, CameraStreamService.class));
    setStatus("Camera starting…", UiTheme.BLUE);
  }

  private void stopCamera() {
    requestedRunning = false;
    updateButtons(false, false);
    activity.stopService(new Intent(activity, CameraStreamService.class));
    setStatus("Camera stopped", UiTheme.MUTED);
    details.setText("Frames  —    Viewer  —    Preview  —");
  }

  private void setInterface(String mode) {
    activity.getSharedPreferences(CameraStreamService.PREFERENCES, Activity.MODE_PRIVATE)
        .edit().putString(CameraStreamService.PREF_INTERFACE, mode).apply();
    updateInterfaceButtons();
    updateEndpoint();
    if (requestedRunning) {
      activity.stopService(new Intent(activity, CameraStreamService.class));
      updateButtons(true, false);
      activity.startForegroundService(new Intent(activity, CameraStreamService.class));
      setStatus("Camera restarting on " + interfaceLabel(mode) + "…", UiTheme.BLUE);
    }
  }

  private void displayStatus(JSONObject response) {
    String state = response.optString("state", "unknown");
    String error = response.optString("error", "");
    boolean client = response.optBoolean("client_connected", false);
    boolean preview = response.optBoolean("preview_attached", false);
    long frames = response.optLong("encoded_frames", 0);

    if ("streaming".equals(state)) {
      requestedRunning = true;
      updateButtons(true, true);
      setStatus("Camera " + response.optString("camera_id", "?") + " streaming • 720p30 • "
          + interfaceLabel(response.optString("interface", currentInterface())), UiTheme.GREEN);
    } else if ("starting".equals(state)) {
      requestedRunning = true;
      updateButtons(true, false);
      setStatus("Camera starting…", UiTheme.BLUE);
    } else if ("error".equals(state)) {
      requestedRunning = false;
      updateButtons(false, false);
      setStatus("Camera error • " + (error.isEmpty() ? "unknown error" : error), UiTheme.RED);
    } else {
      requestedRunning = false;
      updateButtons(false, false);
      setStatus("Camera " + state, UiTheme.MUTED);
    }

    details.setText(String.format(Locale.US, "Frames  %,d    Viewer  %s    Preview  %s",
        frames, client ? "connected" : "none", preview ? "on" : "off"));
    updateEndpoint();
  }

  private String currentInterface() {
    return activity.getSharedPreferences(CameraStreamService.PREFERENCES, Activity.MODE_PRIVATE)
        .getString(CameraStreamService.PREF_INTERFACE, CameraStreamService.INTERFACE_WIFI);
  }

  private void updateInterfaceButtons() {
    String mode = currentInterface();
    UiTheme.setButtonColor(activity, localButton,
        CameraStreamService.INTERFACE_LOCALHOST.equals(mode) ? UiTheme.BLUE : UiTheme.SURFACE_RAISED);
    UiTheme.setButtonColor(activity, wifiButton,
        CameraStreamService.INTERFACE_WIFI.equals(mode) ? UiTheme.BLUE : UiTheme.SURFACE_RAISED);
    UiTheme.setButtonColor(activity, cellularButton,
        CameraStreamService.INTERFACE_CELLULAR.equals(mode) ? UiTheme.BLUE : UiTheme.SURFACE_RAISED);
  }

  private void updateButtons(boolean running, boolean streaming) {
    startButton.setText(running ? (streaming ? "RUNNING" : "STARTING") : "START CAMERA");
    UiTheme.setButtonColor(activity, startButton,
        running ? UiTheme.SURFACE_RAISED : UiTheme.RED);
    UiTheme.setButtonColor(activity, stopButton,
        running ? UiTheme.RED : UiTheme.SURFACE_RAISED);
  }

  private String address() {
    String mode = currentInterface();
    if (CameraStreamService.INTERFACE_LOCALHOST.equals(mode)) return "127.0.0.1";
    return findTransportAddress(CameraStreamService.INTERFACE_CELLULAR.equals(mode)
        ? NetworkCapabilities.TRANSPORT_CELLULAR : NetworkCapabilities.TRANSPORT_WIFI);
  }

  private String findTransportAddress(int transport) {
    ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
    if (manager == null) return null;
    for (Network network : manager.getAllNetworks()) {
      NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
      if (capabilities == null || !capabilities.hasTransport(transport)) continue;
      LinkProperties properties = manager.getLinkProperties(network);
      if (properties == null) continue;
      for (LinkAddress link : properties.getLinkAddresses()) {
        InetAddress candidate = link.getAddress();
        if (candidate instanceof Inet4Address && !candidate.isLoopbackAddress())
          return candidate.getHostAddress();
      }
    }
    return null;
  }

  private void updateEndpoint() {
    String address = address();
    endpoint.setText(address == null
        ? interfaceLabel(currentInterface()) + " unavailable • port " + CameraStreamService.PORT
        : "Video  http://" + address + ":" + CameraStreamService.PORT + "/video");
    endpoint.setTextColor(address == null ? UiTheme.RED : UiTheme.GREEN);
  }

  private String interfaceLabel(String mode) {
    if (CameraStreamService.INTERFACE_LOCALHOST.equals(mode)) return "LOCAL";
    if (CameraStreamService.INTERFACE_CELLULAR.equals(mode)) return "5G";
    return "WI-FI";
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

  private Button actionButton(String label, View.OnClickListener listener) {
    return UiTheme.actionButton(activity, label, UiTheme.SURFACE_RAISED, listener);
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

  private void setStatus(String message, int color) {
    status.setText("●  " + message);
    status.setTextColor(color);
  }

  private int dp(int value) { return UiTheme.dp(activity, value); }
}
