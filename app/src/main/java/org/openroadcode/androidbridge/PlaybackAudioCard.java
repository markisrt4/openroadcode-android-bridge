package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import java.util.Locale;
import org.json.JSONObject;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Dashboard controls for the independent, consent-gated playback source. */
final class PlaybackAudioCard {
  static final int AUDIO_PERMISSION = 1005, PROJECTION_REQUEST = 1006;
  private static final int GAP = 8;
  private static final int BUTTON_HEIGHT = 54;

  private final Activity activity;
  private final LinearLayout view;
  private final TextView status;
  private final TextView levelValue;
  private final AudioLevelMeter levelMeter;
  private final Button start, stop;
  private boolean requesting;
  private boolean requestedRunning;

  PlaybackAudioCard(Activity activity) {
    this.activity = activity;
    view = UiTheme.card(activity);

    LinearLayout headingRow = new LinearLayout(activity);
    headingRow.setOrientation(LinearLayout.HORIZONTAL);
    headingRow.setGravity(Gravity.CENTER_VERTICAL);
    headingRow.setPadding(0, 0, 0, UiTheme.dp(activity, 2));

    TextView heading = UiTheme.text(activity, "ANDROID PLAYBACK AUDIO", 18, UiTheme.TEXT);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.04f);
    headingRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

    status = UiTheme.text(activity, "●  Stopped", 13, UiTheme.MUTED);
    status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    headingRow.addView(status, new LinearLayout.LayoutParams(-2, -2));
    view.addView(headingRow);

    TextView subtitle = UiTheme.text(activity,
        "Native playback capture • PCM16 • localhost:8768", 12, UiTheme.MUTED);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, 0, 0, UiTheme.dp(activity, 10));
    view.addView(subtitle);

    LinearLayout meterRow = new LinearLayout(activity);
    meterRow.setOrientation(LinearLayout.HORIZONTAL);
    meterRow.setGravity(Gravity.CENTER_VERTICAL);
    meterRow.setPadding(0, 0, 0, UiTheme.dp(activity, 12));

    levelMeter = new AudioLevelMeter(activity);
    meterRow.addView(levelMeter, new LinearLayout.LayoutParams(0, UiTheme.dp(activity, 36), 1));

    levelValue = UiTheme.text(activity, "-60 dB", 12, UiTheme.SILVER);
    levelValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    levelValue.setPadding(UiTheme.dp(activity, 10), 0, 0, 0);
    meterRow.addView(levelValue,
        new LinearLayout.LayoutParams(UiTheme.dp(activity, 66), UiTheme.dp(activity, 36)));
    view.addView(meterRow);

    start = UiTheme.actionButton(activity, "▶  START CAPTURE", UiTheme.BLUE, v -> start());
    stop = UiTheme.actionButton(activity, "■  STOP", UiTheme.SURFACE_RAISED, v -> stop());
    start.setTextSize(12);
    stop.setTextSize(12);

    LinearLayout controls = new LinearLayout(activity);
    controls.setOrientation(LinearLayout.HORIZONTAL);
    controls.setPadding(0, 0, 0, UiTheme.dp(activity, 12));
    controls.addView(start, buttonParams(false));
    controls.addView(stop, buttonParams(true));
    view.addView(controls);

    LinearLayout facts = new LinearLayout(activity);
    facts.setOrientation(LinearLayout.HORIZONTAL);
    facts.setPadding(0, 0, 0, UiTheme.dp(activity, 12));
    facts.addView(infoTile("♪", "Format", "PCM16"), tileParams(false, false));
    facts.addView(infoTile("⌘", "Endpoint", "localhost:8768"), tileParams(true, false));
    facts.addView(infoTile("▂▅▇", "Level", "-60 dB"), tileParams(false, true));
    view.addView(facts);

    LinearLayout noteBox = new LinearLayout(activity);
    noteBox.setOrientation(LinearLayout.HORIZONTAL);
    noteBox.setGravity(Gravity.TOP);
    noteBox.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 12),
        UiTheme.dp(activity, 12), UiTheme.dp(activity, 12));
    noteBox.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));

    TextView info = UiTheme.text(activity, "ⓘ", 18, UiTheme.BLUE);
    info.setGravity(Gravity.TOP);
    noteBox.addView(info, new LinearLayout.LayoutParams(UiTheme.dp(activity, 32), -2));

    TextView note = UiTheme.text(activity,
        "Android asks for capture consent each session. Only playback permitted by other apps is available. "
            + "No microphone, recording files, or remote audio endpoint.",
        12, UiTheme.MUTED);
    note.setLineSpacing(0, 1.08f);
    noteBox.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
    view.addView(noteBox);

    update(false, "Stopped");
    updateLevel(-60.0);
  }

  View view() { return view; }

  private LinearLayout infoTile(String icon, String label, String value) {
    LinearLayout tile = new LinearLayout(activity);
    tile.setOrientation(LinearLayout.HORIZONTAL);
    tile.setGravity(Gravity.CENTER_VERTICAL);
    tile.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 10),
        UiTheme.dp(activity, 10), UiTheme.dp(activity, 10));
    tile.setBackground(UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));

    TextView iconView = UiTheme.text(activity, icon, 18, UiTheme.BLUE);
    iconView.setGravity(Gravity.CENTER);
    tile.addView(iconView, new LinearLayout.LayoutParams(UiTheme.dp(activity, 32), -2));

    LinearLayout words = new LinearLayout(activity);
    words.setOrientation(LinearLayout.VERTICAL);
    TextView caption = UiTheme.text(activity, label, 10, UiTheme.MUTED);
    TextView detail = UiTheme.text(activity, value, 11, UiTheme.TEXT);
    detail.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    words.addView(caption);
    words.addView(detail);
    tile.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    return tile;
  }

  private LinearLayout.LayoutParams buttonParams(boolean right) {
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(0, UiTheme.dp(activity, BUTTON_HEIGHT), 1);
    int halfGap = UiTheme.dp(activity, GAP / 2);
    if (right) params.setMargins(halfGap, 0, 0, 0);
    else params.setMargins(0, 0, halfGap, 0);
    return params;
  }

  private LinearLayout.LayoutParams tileParams(boolean middle, boolean last) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
    int gap = UiTheme.dp(activity, 4);
    if (middle) params.setMargins(gap, 0, gap, 0);
    else if (last) params.setMargins(gap, 0, 0, 0);
    else params.setMargins(0, 0, gap, 0);
    return params;
  }

  private void update(boolean running, String message) {
    status.setText("●  " + message);
    status.setTextColor(running ? UiTheme.GREEN : requesting ? UiTheme.BLUE : UiTheme.MUTED);
    start.setEnabled(!running && !requesting);
    stop.setEnabled(running || requesting);
  }

  private void updateLevel(double db) {
    double clamped = Math.max(-60.0, Math.min(0.0, db));
    levelMeter.setLevelDb(clamped);
    levelValue.setText(String.format(Locale.US, "%.0f dB", clamped));
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
    updateLevel(-60.0);
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
        double peakDb = result.optDouble("peak_db", -60.0);
        activity.runOnUiThread(() -> {
          requestedRunning = running;
          updateLevel(running ? peakDb : -60.0);
          update(running, running ? "Running • " + frames + " samples • 48 kHz mono" : "Stopped");
        });
      } catch (Exception ignored) {
        activity.runOnUiThread(() -> {
          updateLevel(-60.0);
          if (!requesting) update(false, requestedRunning ? "Starting or unavailable…" : "Stopped");
        });
      }
    }, "orc-playback-status").start();
  }

  private static final class AudioLevelMeter extends View {
    private final Paint inactive = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double levelDb = -60.0;

    AudioLevelMeter(Activity activity) {
      super(activity);
      inactive.setColor(UiTheme.BORDER);
      active.setColor(UiTheme.BLUE);
    }

    void setLevelDb(double db) {
      levelDb = Math.max(-60.0, Math.min(0.0, db));
      invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      int bars = 18;
      float width = getWidth();
      float height = getHeight();
      float gap = UiTheme.dp(getContext(), 2);
      float barWidth = Math.max(2f, (width - gap * (bars - 1)) / bars);
      int activeBars = (int) Math.round(((levelDb + 60.0) / 60.0) * bars);
      for (int i = 0; i < bars; i++) {
        float left = i * (barWidth + gap);
        float top = height * .18f;
        float right = left + barWidth;
        float bottom = height * .82f;
        canvas.drawRoundRect(left, top, right, bottom,
            barWidth * .45f, barWidth * .45f, i < activeBars ? active : inactive);
      }
    }
  }
}
