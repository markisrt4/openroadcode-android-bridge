package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Dashboard controls for the independent, consent-gated playback source. */
final class PlaybackAudioCard {
  static final int AUDIO_PERMISSION = 1005, PROJECTION_REQUEST = 1006;
  private final Activity activity;
  private final LinearLayout view;
  private final TextView status;
  private final TextView streamDetail;
  private final Button start, stop;
  private boolean requesting;
  private boolean requestedRunning;

  PlaybackAudioCard(Activity activity) {
    this.activity = activity;
    view = UiTheme.card(activity);

    TextView heading = UiTheme.text(activity, "ANDROID PLAYBACK AUDIO", 18, UiTheme.TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setPadding(0, 0, 0, UiTheme.dp(activity, 4));
    view.addView(heading);

    TextView subtitle = UiTheme.text(activity,
        "Native playback capture • PCM16 • localhost:8768", 12, UiTheme.BLUE);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, 0, 0, UiTheme.dp(activity, 12));
    view.addView(subtitle);

    status = UiTheme.text(activity, "●  Stopped", 13, UiTheme.MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    status.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 9),
        UiTheme.dp(activity, 10), UiTheme.dp(activity, 9));
    status.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
    view.addView(status);

    start = UiTheme.actionButton(activity, "START PLAYBACK CAPTURE", UiTheme.BLUE, v -> start());
    stop = UiTheme.actionButton(activity, "STOP", UiTheme.SURFACE_RAISED, v -> stop());
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, UiTheme.dp(activity, 10), 0, UiTheme.dp(activity, 10));
    LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 52), 1);
    startParams.setMargins(0, 0, UiTheme.dp(activity, 4), 0);
    LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 52), 1);
    stopParams.setMargins(UiTheme.dp(activity, 4), 0, 0, 0);
    row.addView(start, startParams);
    row.addView(stop, stopParams);
    view.addView(row);

    LinearLayout facts = new LinearLayout(activity);
    facts.setOrientation(LinearLayout.HORIZONTAL);
    facts.setPadding(0, 0, 0, UiTheme.dp(activity, 10));
    facts.addView(infoTile("FORMAT", "PCM16"), tileParams(false));
    facts.addView(infoTile("ENDPOINT", ":8768"), tileParams(true));
    streamDetail = infoTile("STREAM", "48 kHz mono");
    facts.addView(streamDetail, tileParams(false));
    view.addView(facts);

    TextView note = UiTheme.text(activity,
        "Android asks for capture consent each session. Only playback permitted by other apps is available. "
            + "No microphone, recording files, or remote audio endpoint.",
        12, UiTheme.MUTED);
    note.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 10),
        UiTheme.dp(activity, 10), UiTheme.dp(activity, 10));
    note.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
    view.addView(note);

    update(false, "Stopped");
  }

  View view() { return view; }

  private TextView infoTile(String label, String value) {
    TextView tile = UiTheme.text(activity, label + "\n" + value, 11, UiTheme.MUTED);
    tile.setGravity(Gravity.CENTER);
    tile.setLineSpacing(0f, 1.1f);
    tile.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 10),
        UiTheme.dp(activity, 6), UiTheme.dp(activity, 10));
    tile.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
    return tile;
  }

  private LinearLayout.LayoutParams tileParams(boolean middle) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    int gap = UiTheme.dp(activity, 4);
    if (middle) params.setMargins(gap, 0, gap, 0);
    return params;
  }

  private void update(boolean running, String message) {
    status.setText("●  " + message);
    status.setTextColor(running ? UiTheme.GREEN : requesting ? UiTheme.BLUE : UiTheme.MUTED);
    start.setEnabled(!running && !requesting);
    stop.setEnabled(running || requesting);
  }

  void start() {
    if (requesting || requestedRunning) return;
    if (Build.VERSION.SDK_INT < 29) { update(false, "Android 10 or newer required"); return; }
    requesting = true;
    update(false, "Requesting capture permission…");
    if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
    } else requestProjection();
  }

  private void requestProjection() {
    MediaProjectionManager manager = activity.getSystemService(MediaProjectionManager.class);
    activity.startActivityForResult(manager.createScreenCaptureIntent(), PROJECTION_REQUEST);
  }

  boolean onRequestPermissionsResult(int code, int[] grants) {
    if (code != AUDIO_PERMISSION) return false;
    if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) requestProjection();
    else { requesting = false; update(false, "Audio permission denied"); }
    return true;
  }

  boolean onActivityResult(int code, int result, Intent data) {
    if (code != PROJECTION_REQUEST) return false;
    requesting = false;
    if (result != Activity.RESULT_OK || data == null) { update(false, "Capture consent cancelled"); return true; }
    requestedRunning = true;
    Intent service = new Intent(activity, PlaybackAudioService.class).setAction(PlaybackAudioService.START)
        .putExtra(PlaybackAudioService.RESULT, result).putExtra(PlaybackAudioService.DATA, data);
    try {
      activity.startForegroundService(service);
      update(false, "Starting native playback capture…");
    } catch (RuntimeException ex) {
      requestedRunning = false;
      update(false, "Start failed: " + ex.getMessage());
    }
    return true;
  }

  void stop() {
    requesting = false; requestedRunning = false;
    activity.stopService(new Intent(activity, PlaybackAudioService.class));
    update(false, "Stopped");
  }

  void refresh() {
    new Thread(() -> {
      try {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:8768/status").openConnection();
        connection.setConnectTimeout(300); connection.setReadTimeout(300);
        JSONObject result;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
          result = new JSONObject(reader.readLine());
        } finally { connection.disconnect(); }
        boolean running = result.optBoolean("running");
        long frames = result.optLong("frames");
        activity.runOnUiThread(() -> {
          requestedRunning = running;
          streamDetail.setText("STREAM\n" + (running ? frames + " samples" : "48 kHz mono"));
          streamDetail.setTextColor(running ? UiTheme.GREEN : UiTheme.MUTED);
          update(running, running ? "Running • " + frames + " samples • 48 kHz mono" : "Stopped");
        });
      } catch (Exception ignored) {
        activity.runOnUiThread(() -> {
          streamDetail.setText("STREAM\n48 kHz mono");
          streamDetail.setTextColor(UiTheme.MUTED);
          if (!requesting) update(false, requestedRunning ? "Starting or unavailable…" : "Stopped");
        });
      }
    }, "orc-playback-status").start();
  }
}
