package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import org.openroadcode.androidbridge.ui.ExpandableCard;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Read-only USB discovery and Termux-owned libSDRForge service controls. */
final class RadioCard {
  private final OpenRoadCodeBridgeApplication application;
  private final Activity activity;
  private final LinearLayout root;
  private final TextView status;
  private final TextView device;
  private final SdrForgeControlClient sdrforge = new SdrForgeControlClient();
  private TextView sdrforgeStatus;
  private EditText frequencyInput;
  private Spinner sourceSelector;
  private boolean requestInFlight;

  RadioCard(Activity activity) {
    this.activity = activity;
    application = (OpenRoadCodeBridgeApplication) activity.getApplication();
    root = UiTheme.card(activity);

    status = UiTheme.text(activity, "", 14, UiTheme.TEXT);
    device = UiTheme.text(activity, "", 11, UiTheme.MUTED);
    addSdrForgeControls(activity);
    LinearLayout details = new LinearLayout(activity);
    details.setOrientation(LinearLayout.VERTICAL);
    details.addView(UiTheme.text(activity,
        "USB acquisition is owned by Termux/libSDRForge, not Android. "
        + "The source preference does not launch or switch a running server.", 11, UiTheme.MUTED));
    details.addView(status);
    details.addView(device);
    root.addView(new ExpandableCard(activity, "USB and setup details",
        "Device detection and acquisition ownership", UiTheme.VIOLET,
        details, false).view());
  }

  private void addSdrForgeControls(Activity activity) {

    sourceSelector = new Spinner(activity);
    ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
        android.R.layout.simple_spinner_dropdown_item,
        new String[] {"Simulated IQ (no USB)", "RTL-SDR USB"});
    sourceSelector.setAdapter(adapter);
    String saved = application.getSharedPreferences("sdrforge_radio", Context.MODE_PRIVATE)
        .getString("source", "simulated");
    sourceSelector.setSelection("usb".equals(saved) ? 1 : 0);
    root.addView(sourceSelector);
    Button saveSource = UiTheme.actionButton(activity, "Save source",
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
    frequencyInput.setHint("FM MHz");
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
    root.addView(UiTheme.actionButton(activity, "Tune", UiTheme.SURFACE_RAISED,
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
      String resultMessage;
      try {
        if ("start".equals(action)) sdrforge.start();
        else if ("stop".equals(action)) sdrforge.stop();
        else if ("tune".equals(action)) sdrforge.tune(hz);
        org.json.JSONObject health = sdrforge.health();
        org.json.JSONObject receiver = sdrforge.receiver();
        resultMessage = "REST: " + health.optString("status", "unknown")
            + " | " + (receiver.optLong("frequency_hz") / 1000000.0) + " MHz"
            + "\nMode: " + receiver.optString("mode", "?")
            + " | RDS: " + (receiver.isNull("rds") ? "none" : "available")
            + "\n" + action + " completed";
      } catch (Exception error) {
        resultMessage = "SDRForge: " + error.getMessage();
      }
      final String message = resultMessage;
      activity.runOnUiThread(() -> {
        requestInFlight = false;
        sdrforgeStatus.setText(message);
      });
    }, "sdrforge-radio-control").start();
  }


  View view() { return root; }

  void refresh() {
    RtlSdrUsbManager.State state = application.getRtlSdrUsbManager().refresh();
    status.setText(state.message);
    status.setTextColor(state.device != null ? UiTheme.GREEN : UiTheme.MUTED);
    device.setText(state.device == null ? "Attach an RTL-SDR USB receiver."
        : state.friendlyName() + " • " + state.deviceLabel());
  }
}
