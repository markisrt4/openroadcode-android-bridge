package org.openroadcode.androidbridge;

import android.app.Activity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.text.InputType;
import android.view.View;
import org.openroadcode.androidbridge.ui.UiTheme;
import org.openroadcode.androidbridge.ui.ExpandableCard;
import android.content.Context;

/** Radio device controls use the application's USB owner; leaving the screen keeps reception alive. */
final class RadioCard {
  private final OpenRoadCodeBridgeApplication application;
  private final LinearLayout root;
  private final TextView status, device, diagnostics;
  private final Button connect, disconnect;
  private final SdrForgeControlClient sdrforge = new SdrForgeControlClient();
  private final Activity activity;
  private TextView sdrforgeStatus;
  private EditText frequencyInput;
  private Spinner sourceSelector;
  private boolean requestInFlight;

  RadioCard(Activity activity) {
    this.activity = activity;
    application = (OpenRoadCodeBridgeApplication) activity.getApplication();
    root = UiTheme.card(activity);
    root.addView(UiTheme.text(activity,
        "Connect an RTL-SDR receiver to this phone for the local radio source. "
        + "ADS-B below runs on your selected computing unit.", 12, UiTheme.MUTED));
    status = UiTheme.text(activity, "Checking receiver…", 14, UiTheme.TEXT);
    status.setPadding(0, UiTheme.dp(activity, 12), 0, UiTheme.dp(activity, 6));
    root.addView(status);
    device = UiTheme.text(activity, "", 11, UiTheme.MUTED);
    root.addView(device);
    LinearLayout actions = new LinearLayout(activity);
    connect = UiTheme.actionButton(activity, "Connect receiver", UiTheme.VIOLET, v -> {
      try { application.startRadioBridge(); refresh(); }
      catch (RuntimeException error) { status.setText("Receiver connection failed. Try connecting again."); }
    });
    disconnect = UiTheme.actionButton(activity, "Disconnect", UiTheme.SURFACE_RAISED, v -> {
      application.stopRadioBridge(); refresh();
    });
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
    left.setMargins(0, UiTheme.dp(activity, 12), UiTheme.dp(activity, 4), 0);
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
    right.setMargins(UiTheme.dp(activity, 4), UiTheme.dp(activity, 12), 0, 0);
    actions.addView(connect, left);
    actions.addView(disconnect, right);
    root.addView(actions);
    diagnostics = UiTheme.text(activity, "", 11, UiTheme.MUTED);
    diagnostics.setPadding(0, UiTheme.dp(activity, 8), 0, 0);
    addSdrForgeControls(activity);
    root.addView(new ExpandableCard(activity, "Receiver details",
        "USB endpoint and recent stream diagnostics", UiTheme.VIOLET,
        diagnostics, false).view());
  }


  private void addSdrForgeControls(Activity activity) {
    root.addView(UiTheme.text(activity, "libSDRForge service", 14, UiTheme.TEXT));
    root.addView(UiTheme.text(activity,
        "REST diagnostics for the Termux service. Start the SDRForge server in Termux first. "
        + "Source selection is saved for a future managed launcher; it does not switch a running server.",
        11, UiTheme.MUTED));
    sourceSelector = new Spinner(activity);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
        android.R.layout.simple_spinner_dropdown_item,
        new String[] {"Simulated IQ (no USB)", "RTL-SDR USB"});
    sourceSelector.setAdapter(adapter);
    String saved = application.getSharedPreferences("sdrforge_radio", Context.MODE_PRIVATE)
        .getString("source", "simulated");
    sourceSelector.setSelection("usb".equals(saved) ? 1 : 0);
    root.addView(sourceSelector);
    Button saveSource = UiTheme.actionButton(activity, "Save preferred source",
        UiTheme.SURFACE_RAISED, v -> {
          String selected = sourceSelector.getSelectedItemPosition() == 0 ? "simulated" : "usb";
          application.getSharedPreferences("sdrforge_radio", Context.MODE_PRIVATE)
              .edit().putString("source", selected).apply();
          sdrforgeStatus.setText("Preference saved: " + selected
              + ". Launch the matching server mode from Termux.");
        });
    root.addView(saveSource);
    frequencyInput = new EditText(activity);
    frequencyInput.setSingleLine(true);
    frequencyInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
    frequencyInput.setText("94.7");
    frequencyInput.setHint("Frequency (MHz)");
    root.addView(frequencyInput);
    sdrforgeStatus = UiTheme.text(activity, "SDRForge: not checked", 11, UiTheme.MUTED);
    root.addView(sdrforgeStatus);
    LinearLayout row = new LinearLayout(activity);
    row.addView(UiTheme.actionButton(activity, "Check", UiTheme.SURFACE_RAISED,
        v -> performSdrForge("check")), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(UiTheme.actionButton(activity, "Start", UiTheme.VIOLET,
        v -> performSdrForge("start")), new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(UiTheme.actionButton(activity, "Stop", UiTheme.SURFACE_RAISED,
        v -> performSdrForge("stop")), new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(row);
    root.addView(UiTheme.actionButton(activity, "Tune receiver", UiTheme.SURFACE_RAISED,
        v -> performSdrForge("tune")));
  }

  private void performSdrForge(String action) {
    if (requestInFlight) return;
    final int hz;
    if ("tune".equals(action)) {
      try {
        hz = Math.round(Float.parseFloat(frequencyInput.getText().toString()) * 1000000);
        if (hz < 87500000 || hz > 108000000) throw new NumberFormatException();
      } catch (NumberFormatException error) {
        sdrforgeStatus.setText("Enter an FM frequency from 87.5 to 108 MHz.");
        return;
      }
    } else {
      hz = 0;
    }
    requestInFlight = true;
    sdrforgeStatus.setText("SDRForge: " + action + "…");
    new Thread(() -> {
      final String message;
      try {
        if ("start".equals(action)) sdrforge.start();
        else if ("stop".equals(action)) sdrforge.stop();
        else if ("tune".equals(action)) sdrforge.tune(hz);
        org.json.JSONObject health = sdrforge.health();
        org.json.JSONObject receiver = sdrforge.receiver();
        message = "REST: " + health.optString("status", "unknown")
            + " | " + (receiver.optLong("frequency_hz") / 1000000.0) + " MHz"
            + "\\nMode: " + receiver.optString("mode", "?")
            + " | RDS: " + (receiver.isNull("rds") ? "none" : "available")
            + "\\n" + action + " completed";
      } catch (Exception error) {
        message = "SDRForge: " + error.getMessage();
      }
      activity.runOnUiThread(() -> {
        requestInFlight = false;
        sdrforgeStatus.setText(message);
      });
    }, "sdrforge-radio-control").start();
  }

  View view() { return root; }
  void refresh() {
    RtlSdrUsbManager.State state = application.radioState();
    if (state == null || (state.status != RtlSdrUsbManager.Status.PERMISSION_PENDING
        && state.status != RtlSdrUsbManager.Status.ERROR))
      state = application.getRtlSdrUsbManager().refresh();
    boolean open = state.status == RtlSdrUsbManager.Status.OPEN;
    status.setText(open ? "●  Receiver connected to this phone" : "○  " + state.message);
    status.setTextColor(open ? UiTheme.GREEN : UiTheme.MUTED);
    device.setText(state.device == null ? "Attach an RTL-SDR USB receiver to use the local source."
        : org.openroadcode.androidbridge.config.UiModeSettings.advanced(application) ? state.deviceLabel() : (open ? state.friendlyName() : "USB receiver attached"));
    android.content.SharedPreferences preferences = application.getSharedPreferences(
        RtlSdrUsbProxyService.DIAGNOSTIC_PREFERENCES, Context.MODE_PRIVATE);
    diagnostics.setText("USB proxy: 127.0.0.1:" + RtlSdrUsbProxyService.TCP_PORT
        + "\nLast stream: " + preferences.getString(RtlSdrUsbProxyService.PREF_LAST_STREAM_STATUS, "none recorded")
        + "\nControl: " + preferences.getString(RtlSdrUsbProxyService.PREF_CONTROL_STATUS, "none recorded")
        + "\nService: " + preferences.getString(RtlSdrUsbProxyService.PREF_LAST_SERVICE_STATUS, "none recorded"));
    boolean pending = state.status == RtlSdrUsbManager.Status.PERMISSION_PENDING;
    connect.setEnabled(!open && !pending && state.device != null);
    disconnect.setEnabled(open || pending);
  }
}
