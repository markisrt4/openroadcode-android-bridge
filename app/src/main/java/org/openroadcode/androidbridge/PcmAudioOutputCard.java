package org.openroadcode.androidbridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Controls the localhost PCM-to-Android AudioTrack output bridge. */
final class PcmAudioOutputCard {
  private final Activity activity;
  private final LinearLayout root;
  private final TextView status;
  private final TextView traffic;
  private final Button start;
  private final Button stop;

  PcmAudioOutputCard(Activity activity) {
    this.activity = activity;
    root = UiTheme.card(activity);

    TextView heading = UiTheme.text(activity, "ANDROID AUDIO OUTPUT", 18, UiTheme.TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.04f);
    root.addView(heading);

    TextView subtitle = UiTheme.text(activity,
        "PCM16 stereo • 48 kHz • localhost:" + PcmAudioOutputService.PORT + " → AudioTrack",
        12, UiTheme.BLUE);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, 0, 0, dp(10));
    root.addView(subtitle);

    status = UiTheme.text(activity, "●  Stopped", 13, UiTheme.MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    status.setPadding(dp(10), dp(8), dp(10), dp(8));
    status.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
    root.addView(status);

    traffic = UiTheme.text(activity, "Played 0 B", 12, UiTheme.MUTED);
    traffic.setTypeface(Typeface.MONOSPACE);
    traffic.setPadding(dp(2), dp(8), 0, dp(8));
    root.addView(traffic);

    start = UiTheme.actionButton(activity, "START OUTPUT", UiTheme.BLUE, v -> start());
    stop = UiTheme.actionButton(activity, "STOP", UiTheme.SURFACE_RAISED, v -> stop());
    LinearLayout controls = new LinearLayout(activity);
    controls.setOrientation(LinearLayout.HORIZONTAL);
    controls.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, dp(46), 1);
    left.setMargins(0, 0, dp(4), 0);
    LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, dp(46), 1);
    right.setMargins(dp(4), 0, 0, 0);
    controls.addView(start, left);
    controls.addView(stop, right);
    root.addView(controls);

    TextView note = UiTheme.text(activity,
        "Local-only raw PCM input for OpenRoadCode. Android AudioTrack supplies the real-time "
            + "playback clock and routes audio normally to the phone, Bluetooth, or car audio.",
        12, UiTheme.MUTED);
    note.setPadding(dp(2), dp(10), dp(2), 0);
    root.addView(note);
    refresh();
  }

  View view() { return root; }

  void refresh() {
    boolean running = PcmAudioOutputService.isRunning();
    boolean connected = PcmAudioOutputService.isClientConnected();
    String error = PcmAudioOutputService.lastError();
    if (running && connected) {
      setStatus("Streaming PCM to Android", UiTheme.GREEN);
    } else if (running) {
      setStatus("Listening on 127.0.0.1:" + PcmAudioOutputService.PORT, UiTheme.BLUE);
    } else if (!error.isEmpty()) {
      setStatus("Stopped • " + error, UiTheme.RED);
    } else {
      setStatus("Stopped", UiTheme.MUTED);
    }
    traffic.setText(String.format(Locale.US, "Played %,d B", PcmAudioOutputService.bytesPlayed()));
    start.setEnabled(!running);
    stop.setEnabled(running);
  }

  private void start() {
    try {
      activity.startForegroundService(
          new Intent(activity, PcmAudioOutputService.class).setAction(PcmAudioOutputService.START));
    } catch (RuntimeException ex) {
      setStatus("Start failed • " + ex.getMessage(), UiTheme.RED);
    }
    refresh();
  }

  private void stop() {
    activity.stopService(new Intent(activity, PcmAudioOutputService.class));
    refresh();
  }

  private void setStatus(String message, int color) {
    status.setText("●  " + message);
    status.setTextColor(color);
  }

  private int dp(int value) { return UiTheme.dp(activity, value); }
}
