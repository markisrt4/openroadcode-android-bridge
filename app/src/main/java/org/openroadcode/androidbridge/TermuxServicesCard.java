package org.openroadcode.androidbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings.Target;

/**
 * UI and controller for the restricted OpenRoadCode runtime service-manager API.
 *
 * The card can control the local Termux/runit manager or an authenticated remote
 * Pi/systemd manager while preserving the same service names and operations.
 */
public final class TermuxServicesCard {
  private static final int SURFACE = Color.rgb(11, 24, 33);
  private static final int SURFACE_RAISED = Color.rgb(16, 34, 46);
  private static final int BORDER = Color.rgb(36, 64, 79);
  private static final int TEXT = Color.rgb(243, 247, 249);
  private static final int MUTED = Color.rgb(147, 164, 174);
  private static final int BLUE = Color.rgb(22, 139, 209);
  private static final int GREEN = Color.rgb(132, 206, 31);
  private static final int RED = Color.rgb(241, 90, 22);

  private static final long REFRESH_MS = 2000;

  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, TextView> serviceStates = new LinkedHashMap<>();

  private final LinearLayout root;
  private final TextView targetSummary;
  private final TextView managerStatus;

  private final Runnable refreshTask = new Runnable() {
    @Override
    public void run() {
      refresh();
      handler.postDelayed(this, REFRESH_MS);
    }
  };

  public TermuxServicesCard(Activity activity) {
    this.activity = activity;
    settings = new RuntimeServiceManagerSettings(activity);

    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(16), dp(12), dp(16));
    root.setBackground(rounded(SURFACE, BORDER, 14));

    TextView title = text("OPENROADCODE SERVICES", 18, TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setLetterSpacing(.08f);
    root.addView(title);

    targetSummary = text("", 12, BLUE);
    targetSummary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    targetSummary.setPadding(0, dp(2), 0, dp(8));
    root.addView(targetSummary);
    refreshTargetSummary();

    root.addView(buttonRow(
        actionButton("TERMUX", BLUE, v -> selectTermux()),
        actionButton("REMOTE PI", BLUE, v -> selectRemotePi()),
        actionButton("CONFIGURE PI", MUTED, v -> configureRemotePi())));

    managerStatus = statusPill("Checking service manager…", MUTED);
    root.addView(managerStatus);

    root.addView(buttonRow(
        actionButton("START CORE", BLUE, v -> runAction(RuntimeServiceManagerClient::startCoreStack)),
        actionButton("STOP CORE", RED, v -> runAction(RuntimeServiceManagerClient::stopCoreStack))));

    addService("openroadcode-message-broker", "Message broker");
    addService("openroadcode-navigation", "Navigation");
    addService("openroadcode-automotive", "Automotive");
    addService("openroadcode-adsb", "ADS-B");
  }

  public View view() {
    return root;
  }

  public void start() {
    handler.removeCallbacks(refreshTask);
    handler.post(refreshTask);
  }

  public void stop() {
    handler.removeCallbacks(refreshTask);
  }

  private void selectTermux() {
    settings.setTarget(Target.TERMUX);
    refreshTargetSummary();
    refresh();
  }

  private void selectRemotePi() {
    if (!settings.hasRemotePiConfiguration()) {
      configureRemotePi();
      return;
    }
    settings.setTarget(Target.REMOTE_PI);
    refreshTargetSummary();
    refresh();
  }

  private void configureRemotePi() {
    LinearLayout fields = new LinearLayout(activity);
    fields.setOrientation(LinearLayout.VERTICAL);
    fields.setPadding(dp(20), dp(8), dp(20), 0);

    EditText endpoint = new EditText(activity);
    endpoint.setSingleLine(true);
    endpoint.setHint("http://pi-address:8769");
    endpoint.setText(settings.piBaseUrl());
    endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    fields.addView(endpoint);

    EditText token = new EditText(activity);
    token.setSingleLine(true);
    token.setHint("Service manager token");
    token.setText(settings.piToken());
    token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    fields.addView(token);

    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle("Remote Pi service manager")
        .setMessage("Enter the Pi service-manager endpoint and bearer token.")
        .setView(fields)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("SAVE", null)
        .create();

    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(v -> {
          String baseUrl = endpoint.getText().toString().trim();
          String bearerToken = token.getText().toString().trim();
          if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            endpoint.setError("Enter a complete http:// or https:// endpoint");
            return;
          }
          if (bearerToken.isBlank()) {
            token.setError("The remote Pi requires a bearer token");
            return;
          }
          settings.setPiBaseUrl(baseUrl);
          settings.setPiToken(bearerToken);
          settings.setTarget(Target.REMOTE_PI);
          refreshTargetSummary();
          dialog.dismiss();
          refresh();
        }));
    dialog.show();
  }

  private void refreshTargetSummary() {
    if (settings.target() == Target.REMOTE_PI) {
      String endpoint = settings.piBaseUrl();
      targetSummary.setText(endpoint.isBlank()
          ? "Remote Pi • systemd • not configured"
          : "Remote Pi • systemd • " + endpoint);
    } else {
      targetSummary.setText("Termux • runit • localhost control plane");
    }
  }

  private RuntimeServiceManagerClient activeClient() {
    if (settings.target() == Target.REMOTE_PI) {
      if (!settings.hasRemotePiConfiguration()) {
        throw new IllegalStateException("Remote Pi service manager is not configured");
      }
      return new RuntimeServiceManagerClient(
          settings.piBaseUrl(), "Remote Pi", settings.piToken());
    }
    return new RuntimeServiceManagerClient(TermuxServiceManagerClient.BASE_URL, "Termux");
  }

  private void addService(String id, String label) {
    LinearLayout serviceBlock = new LinearLayout(activity);
    serviceBlock.setOrientation(LinearLayout.VERTICAL);
    serviceBlock.setPadding(0, dp(6), 0, dp(6));

    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(2), dp(6), dp(2), dp(2));

    TextView name = text(label, 14, TEXT);
    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

    TextView state = text("● UNKNOWN", 12, MUTED);
    state.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    state.setGravity(Gravity.END);
    row.addView(state, new LinearLayout.LayoutParams(0, -2, 1));

    serviceStates.put(id, state);
    serviceBlock.addView(row);

    serviceBlock.addView(buttonRow(
        actionButton("START", BLUE, v -> runAction(client -> client.startService(id))),
        actionButton("STOP", RED, v -> runAction(client -> client.stopService(id)))));

    root.addView(serviceBlock);
  }

  private void refresh() {
    new Thread(() -> {
      try {
        RuntimeServiceManagerClient client = activeClient();
        JSONObject result = client.getServices();
        String label = client.targetLabel();
        activity.runOnUiThread(() -> render(result, label));
      } catch (Exception e) {
        String message = e.getMessage();
        activity.runOnUiThread(() -> renderUnavailable(message));
      }
    }, "orc-service-status").start();
  }

  private void render(JSONObject result, String targetLabel) {
    managerStatus.setText("●  " + targetLabel + " service manager available");
    managerStatus.setTextColor(GREEN);

    JSONArray services = result.optJSONArray("services");
    if (services == null) return;

    for (int i = 0; i < services.length(); i++) {
      JSONObject service = services.optJSONObject(i);
      if (service == null) continue;

      String id = service.optString("name", "");
      TextView view = serviceStates.get(id);
      if (view == null) continue;

      String state = service.optString("state", "unknown").toLowerCase(Locale.US);
      view.setText("● " + state.toUpperCase(Locale.US));

      if ("running".equals(state)) {
        view.setTextColor(GREEN);
      } else if ("stopped".equals(state)) {
        view.setTextColor(MUTED);
      } else {
        view.setTextColor(RED);
      }
    }
  }

  private void renderUnavailable(String message) {
    String target = settings.target() == Target.REMOTE_PI ? "Remote Pi" : "Termux";
    managerStatus.setText("●  " + target + " unavailable"
        + (message == null || message.isBlank() ? "" : ": " + message));
    managerStatus.setTextColor(RED);

    for (TextView state : serviceStates.values()) {
      state.setText("● UNKNOWN");
      state.setTextColor(MUTED);
    }
  }

  private void runAction(Action action) {
    managerStatus.setText("●  Applying service change…");
    managerStatus.setTextColor(BLUE);

    new Thread(() -> {
      try {
        action.run(activeClient());
        activity.runOnUiThread(this::refresh);
      } catch (Exception e) {
        activity.runOnUiThread(() -> {
          String message = e.getMessage();
          managerStatus.setText("●  " + (message == null ? "Service request failed" : message));
          managerStatus.setTextColor(RED);
        });
      }
    }, "orc-service-action").start();
  }

  private interface Action {
    JSONObject run(RuntimeServiceManagerClient client) throws Exception;
  }

  private Button actionButton(String label, int color, View.OnClickListener listener) {
    Button button = new Button(activity);
    button.setText(label);
    button.setTextColor(TEXT);
    button.setTextSize(11);
    button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    button.setLetterSpacing(.08f);
    button.setAllCaps(false);
    button.setBackground(rounded(color, color, 9));
    button.setOnClickListener(listener);
    return button;
  }

  private LinearLayout buttonRow(Button... buttons) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER);
    row.setPadding(0, dp(6), 0, dp(6));

    for (Button button : buttons) {
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
      params.setMargins(dp(3), 0, dp(3), 0);
      row.addView(button, params);
    }

    return row;
  }

  private TextView statusPill(String value, int color) {
    TextView view = text("●  " + value, 13, color);
    view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    view.setPadding(dp(10), dp(8), dp(10), dp(8));
    view.setBackground(rounded(SURFACE_RAISED, BORDER, 9));
    return view;
  }

  private TextView text(String value, float size, int color) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  private GradientDrawable rounded(int fill, int stroke, int radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(fill);
    drawable.setCornerRadius(dp(radius));
    drawable.setStroke(dp(1), stroke);
    return drawable;
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }
}
