package org.openroadcode.androidbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
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
import org.openroadcode.androidbridge.ui.UiTheme;

/**
 * UI and controller for the restricted OpenRoadCode runtime service-manager API.
 *
 * The card can control the local Termux/runit manager or an authenticated remote
 * Pi/systemd manager while preserving the same service names and operations.
 */
public final class TermuxServicesCard {
  private static final long REFRESH_MS = 2000;
  private static final int BUTTON_HEIGHT = 46;
  private static final int ROW_GAP = 8;

  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, TextView> serviceStates = new LinkedHashMap<>();

  private final LinearLayout root;
  private final TextView targetSummary;
  private final TextView managerStatus;
  private final Button termuxButton;
  private final Button remotePiButton;

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
    root = UiTheme.card(activity);

    LinearLayout titleRow = new LinearLayout(activity);
    titleRow.setOrientation(LinearLayout.HORIZONTAL);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);

    TextView title = text("OPENROADCODE SERVICES", 18, UiTheme.TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setLetterSpacing(.05f);
    titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(titleRow);

    targetSummary = text("", 12, UiTheme.MUTED);
    targetSummary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    targetSummary.setPadding(0, dp(2), 0, dp(10));
    root.addView(targetSummary);

    termuxButton = actionButton("TERMUX", UiTheme.BLUE, v -> selectTermux());
    remotePiButton = actionButton("REMOTE PI", UiTheme.SURFACE_RAISED, v -> selectRemotePi());
    Button configureButton = actionButton("CONFIGURE PI", UiTheme.SURFACE_RAISED, v -> configureRemotePi());
    root.addView(buttonRow(termuxButton, remotePiButton, configureButton));

    managerStatus = statusPill("Checking service manager…", UiTheme.MUTED);
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
    statusParams.setMargins(0, dp(2), 0, dp(10));
    root.addView(managerStatus, statusParams);

    addService("openroadcode-message-broker", "Message broker");
    addService("openroadcode-navigation", "Navigation");
    addService("openroadcode-automotive", "Automotive");
    addService("openroadcode-adsb", "ADS-B");

    LinearLayout coreRow = new LinearLayout(activity);
    coreRow.setOrientation(LinearLayout.HORIZONTAL);
    coreRow.setPadding(0, dp(4), 0, 0);
    coreRow.addView(
        actionButton("START CORE", UiTheme.BLUE,
            v -> runAction(RuntimeServiceManagerClient::startCoreStack)),
        pairedButtonParams(false));
    coreRow.addView(
        actionButton("STOP CORE", UiTheme.RED,
            v -> runAction(RuntimeServiceManagerClient::stopCoreStack)),
        pairedButtonParams(true));
    root.addView(coreRow);

    refreshTargetSummary();
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
    boolean remote = settings.target() == Target.REMOTE_PI;
    if (remote) {
      String endpoint = settings.piBaseUrl();
      targetSummary.setText(endpoint.isBlank()
          ? "Remote Pi • systemd • not configured"
          : "Remote Pi • systemd • " + endpoint);
    } else {
      targetSummary.setText("Termux • runit • localhost control plane");
    }
    UiTheme.setButtonColor(activity, termuxButton,
        remote ? UiTheme.SURFACE_RAISED : UiTheme.BLUE);
    UiTheme.setButtonColor(activity, remotePiButton,
        remote ? UiTheme.BLUE : UiTheme.SURFACE_RAISED);
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
    LinearLayout serviceRow = new LinearLayout(activity);
    serviceRow.setOrientation(LinearLayout.HORIZONTAL);
    serviceRow.setGravity(Gravity.CENTER_VERTICAL);
    serviceRow.setPadding(dp(12), dp(8), dp(8), dp(8));
    serviceRow.setBackground(
        UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 10));

    LinearLayout description = new LinearLayout(activity);
    description.setOrientation(LinearLayout.VERTICAL);
    description.setGravity(Gravity.CENTER_VERTICAL);

    TextView name = text(label, 13, UiTheme.TEXT);
    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    description.addView(name);

    TextView state = text("●  Unknown", 11, UiTheme.MUTED);
    state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    state.setPadding(0, dp(2), 0, 0);
    description.addView(state);
    serviceStates.put(id, state);

    serviceRow.addView(description, new LinearLayout.LayoutParams(0, -2, 1.35f));

    Button startButton = actionButton(
        "START", UiTheme.BLUE, v -> runAction(client -> client.startService(id)));
    Button stopButton = actionButton(
        "STOP", UiTheme.RED, v -> runAction(client -> client.stopService(id)));

    LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), .82f);
    startParams.setMargins(dp(6), 0, dp(3), 0);
    LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), .82f);
    stopParams.setMargins(dp(3), 0, 0, 0);
    serviceRow.addView(startButton, startParams);
    serviceRow.addView(stopButton, stopParams);

    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
    rowParams.setMargins(0, 0, 0, dp(ROW_GAP));
    root.addView(serviceRow, rowParams);
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
    managerStatus.setTextColor(UiTheme.GREEN);

    JSONArray services = result.optJSONArray("services");
    if (services == null) return;

    for (int i = 0; i < services.length(); i++) {
      JSONObject service = services.optJSONObject(i);
      if (service == null) continue;

      String id = service.optString("name", "");
      TextView stateView = serviceStates.get(id);
      if (stateView == null) continue;

      String state = service.optString("state", "unknown").toLowerCase(Locale.US);
      stateView.setText("●  " + titleCase(state));

      if ("running".equals(state)) {
        stateView.setTextColor(UiTheme.GREEN);
      } else if ("stopped".equals(state)) {
        stateView.setTextColor(UiTheme.MUTED);
      } else {
        stateView.setTextColor(UiTheme.RED);
      }
    }
  }

  private void renderUnavailable(String message) {
    String target = settings.target() == Target.REMOTE_PI ? "Remote Pi" : "Termux";
    managerStatus.setText("●  " + target + " unavailable"
        + (message == null || message.isBlank() ? "" : ": " + message));
    managerStatus.setTextColor(UiTheme.RED);

    for (TextView state : serviceStates.values()) {
      state.setText("●  Unknown");
      state.setTextColor(UiTheme.MUTED);
    }
  }

  private void runAction(Action action) {
    managerStatus.setText("●  Applying service change…");
    managerStatus.setTextColor(UiTheme.BLUE);

    new Thread(() -> {
      try {
        action.run(activeClient());
        activity.runOnUiThread(this::refresh);
      } catch (Exception e) {
        activity.runOnUiThread(() -> {
          String message = e.getMessage();
          managerStatus.setText("●  " + (message == null ? "Service request failed" : message));
          managerStatus.setTextColor(UiTheme.RED);
        });
      }
    }, "orc-service-action").start();
  }

  private interface Action {
    JSONObject run(RuntimeServiceManagerClient client) throws Exception;
  }

  private Button actionButton(String label, int color, View.OnClickListener listener) {
    Button button = UiTheme.actionButton(activity, label, color, listener);
    button.setTextSize(11);
    return button;
  }

  private LinearLayout buttonRow(Button... buttons) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER);
    row.setPadding(0, 0, 0, dp(10));

    for (int i = 0; i < buttons.length; i++) {
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), 1);
      int left = i == 0 ? 0 : dp(4);
      int right = i == buttons.length - 1 ? 0 : dp(4);
      params.setMargins(left, 0, right, 0);
      row.addView(buttons[i], params);
    }
    return row;
  }

  private LinearLayout.LayoutParams pairedButtonParams(boolean right) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1);
    if (right) params.setMargins(dp(4), 0, 0, 0);
    else params.setMargins(0, 0, dp(4), 0);
    return params;
  }

  private TextView statusPill(String value, int color) {
    TextView view = text("●  " + value, 12, color);
    view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    view.setPadding(dp(10), dp(9), dp(10), dp(9));
    view.setBackground(
        UiTheme.rounded(activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 9));
    return view;
  }

  private TextView text(String value, float size, int color) {
    return UiTheme.text(activity, value, size, color);
  }

  private String titleCase(String value) {
    if (value.isEmpty()) return value;
    return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
  }

  private int dp(int value) {
    return UiTheme.dp(activity, value);
  }
}
