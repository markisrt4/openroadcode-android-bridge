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
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings;
import org.openroadcode.androidbridge.config.RuntimeServiceManagerSettings.Target;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Runtime target, profile, and lifecycle controls for OpenRoadCode services. */
public final class TermuxServicesCard {
  private static final long REFRESH_MS = 2000;
  private static final int BUTTON_HEIGHT = 44;
  private static final int ROW_GAP = 9;

  private final Activity activity;
  private final RuntimeServiceManagerSettings settings;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, TextView> serviceStates = new LinkedHashMap<>();
  private final Map<String, TextView> serviceProfiles = new LinkedHashMap<>();
  private final Map<String, Button> startButtons = new LinkedHashMap<>();
  private final Map<String, Button> stopButtons = new LinkedHashMap<>();
  private final Map<String, Map<String, Button>> profileButtons = new LinkedHashMap<>();
  private final Set<String> visibleServices = new LinkedHashSet<>();
  private final boolean showCoreControls;
  private final boolean showTargetControls;
  private final boolean profileOnly;

  private final LinearLayout root;
  private final TextView targetSummary;
  private final TextView managerStatus;
  private final Button termuxButton;
  private final Button remotePiButton;
  private Button startCoreButton;
  private Button stopCoreButton;

  private final Runnable refreshTask = new Runnable() {
    @Override
    public void run() {
      refresh();
      handler.postDelayed(this, REFRESH_MS);
    }
  };

  public TermuxServicesCard(Activity activity) {
    this(activity,
        "openroadcode-message-broker",
        "openroadcode-navigation",
        "openroadcode-automotive",
        "openroadcode-adsb");
  }

  public TermuxServicesCard(Activity activity, String... services) {
    this.activity = activity;
    for (String service : services) visibleServices.add(service);
    showCoreControls = visibleServices.size() > 1;
    showTargetControls = showCoreControls;
    profileOnly = !showTargetControls && visibleServices.size() == 1;
    settings = new RuntimeServiceManagerSettings(activity);
    root = UiTheme.card(activity);

    TextView title = text(
        showTargetControls ? "OPENROADCODE RUNTIME" : "INPUT PROFILE",
        18, UiTheme.TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setLetterSpacing(.05f);
    root.addView(title);

    targetSummary = text("", 12, UiTheme.MUTED);
    targetSummary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    targetSummary.setPadding(0, dp(2), 0, dp(10));
    if (showTargetControls) root.addView(targetSummary);

    termuxButton = actionButton("TERMUX", UiTheme.BLUE, v -> selectTermux());
    remotePiButton = actionButton("REMOTE PI", UiTheme.SURFACE_RAISED, v -> selectRemotePi());
    if (showTargetControls) {
      addSectionLabel("RUNTIME TARGET");
      root.addView(buttonRow(termuxButton, remotePiButton));

      Button editConnectionButton = actionButton(
          "EDIT REMOTE CONNECTION", UiTheme.SURFACE_RAISED, v -> configureRemotePi());
      LinearLayout.LayoutParams editParams =
          new LinearLayout.LayoutParams(-1, dp(BUTTON_HEIGHT));
      editParams.setMargins(0, 0, 0, dp(10));
      root.addView(editConnectionButton, editParams);
    }

    managerStatus = statusPill(
        profileOnly ? "Checking selected profile…" : "Checking service manager…",
        UiTheme.MUTED);
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
    statusParams.setMargins(0, dp(1), 0, dp(11));
    root.addView(managerStatus, statusParams);

    if (showTargetControls) addSectionLabel("SERVICES");
    if (visibleServices.contains("openroadcode-message-broker")) {
      addService("openroadcode-message-broker", "Message broker",
          "Runtime message infrastructure", false);
    }
    if (visibleServices.contains("openroadcode-navigation")) {
      addService("openroadcode-navigation", "Navigation",
          "Position and motion pipeline", true);
    }
    if (visibleServices.contains("openroadcode-automotive")) {
      addService("openroadcode-automotive", "Automotive",
          "Vehicle telemetry pipeline", true);
    }
    if (visibleServices.contains("openroadcode-adsb")) {
      addService("openroadcode-adsb", "ADS-B",
          "Aircraft receiver service", false);
    }

    if (showCoreControls) {
      addSectionLabel("CORE STACK");
      LinearLayout coreRow = new LinearLayout(activity);
      coreRow.setOrientation(LinearLayout.HORIZONTAL);
      startCoreButton = actionButton("START CORE", UiTheme.BLUE,
          v -> runAction(RuntimeServiceManagerClient::startCoreStack));
      stopCoreButton = actionButton("STOP CORE", UiTheme.RED,
          v -> runAction(RuntimeServiceManagerClient::stopCoreStack));
      coreRow.addView(startCoreButton, pairedButtonParams(false));
      coreRow.addView(stopCoreButton, pairedButtonParams(true));
      root.addView(coreRow);
    }

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

  private void addSectionLabel(String label) {
    TextView view = text(label, 10, UiTheme.MUTED);
    view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    view.setLetterSpacing(.10f);
    view.setPadding(0, dp(2), 0, dp(6));
    root.addView(view);
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
    token.setHint("Access token");
    token.setText(settings.piToken());
    token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    fields.addView(token);

    TextView tokenHelp = text(
        "On the Linux/Pi target: sudo cat /etc/openroadcode/service-manager.env\n"
            + "Paste only the value after OPENROADCODE_SERVICE_MANAGER_TOKEN=.",
        10,
        UiTheme.MUTED);
    tokenHelp.setPadding(0, dp(4), 0, dp(4));
    fields.addView(tokenHelp);

    AlertDialog dialog = new AlertDialog.Builder(activity)
        .setTitle("Remote Linux service manager")
        .setMessage("Enter the service-manager endpoint and access token.")
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
            token.setError("The remote service manager requires an access token");
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
          ? "Target: Remote Linux • systemd • not configured"
          : "Target: Remote Linux • systemd • " + endpoint);
    } else {
      targetSummary.setText("Target: Termux • runit • local runtime");
    }
    if (showTargetControls) {
      UiTheme.setButtonColor(activity, termuxButton,
          remote ? UiTheme.SURFACE_RAISED : UiTheme.BLUE);
      boolean remoteConfigured = settings.hasRemotePiConfiguration();
      remotePiButton.setEnabled(remoteConfigured);
      UiTheme.setButtonColor(activity, remotePiButton,
          remote ? UiTheme.BLUE
              : (remoteConfigured ? UiTheme.SURFACE_RAISED : UiTheme.DISABLED));
    }
  }

  private RuntimeServiceManagerClient activeClient() {
    if (settings.target() == Target.REMOTE_PI) {
      if (!settings.hasRemotePiConfiguration()) {
        throw new IllegalStateException("Remote Linux service manager is not configured");
      }
      return new RuntimeServiceManagerClient(
          settings.piBaseUrl(), "Remote Linux", settings.piToken());
    }
    return new RuntimeServiceManagerClient(TermuxServiceManagerClient.BASE_URL, "Termux");
  }

  private void addService(String id, String label, String descriptionText, boolean profiles) {
    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(12), dp(10), dp(12), dp(10));
    card.setBackground(UiTheme.rounded(
        activity, UiTheme.SURFACE_RAISED, UiTheme.BORDER, 10));

    LinearLayout heading = new LinearLayout(activity);
    heading.setOrientation(LinearLayout.HORIZONTAL);
    heading.setGravity(Gravity.CENTER_VERTICAL);

    TextView name = text(label, 14, UiTheme.TEXT);
    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

    if (!profileOnly) {
      TextView state = text("●  Unknown", 11, UiTheme.MUTED);
      state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      state.setGravity(Gravity.END);
      heading.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
      serviceStates.put(id, state);
    }
    card.addView(heading);

    TextView description = text(
        profileOnly ? "Choose the input source used next time this service runs" : descriptionText,
        10, UiTheme.SILVER);
    description.setPadding(0, dp(2), 0, profiles ? dp(7) : dp(6));
    card.addView(description);

    if (profiles) {
      LinearLayout profileColumn = new LinearLayout(activity);
      profileColumn.setOrientation(LinearLayout.VERTICAL);

      TextView profile = text("INPUT  •  loading…", 10, UiTheme.MUTED);
      profile.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      profile.setPadding(0, 0, 0, dp(6));
      serviceProfiles.put(id, profile);
      profileColumn.addView(profile);

      LinearLayout profileRow = new LinearLayout(activity);
      profileRow.setOrientation(LinearLayout.HORIZONTAL);
      profileRow.setGravity(Gravity.CENTER_VERTICAL);
      profileButtons.put(id, new LinkedHashMap<>());
      addProfileButton(profileRow, id, "LOCAL", "local");
      addProfileButton(profileRow, id, "REMOTE", "remote");
      addProfileButton(profileRow, id, "SIM", "simulated");
      profileColumn.addView(profileRow);
      card.addView(profileColumn);
    }

    if (!profileOnly) {
      LinearLayout actions = new LinearLayout(activity);
      actions.setOrientation(LinearLayout.HORIZONTAL);
      actions.setPadding(0, dp(8), 0, 0);
      Button startButton = actionButton("START", UiTheme.BLUE,
          v -> runAction(client -> client.startService(id)));
      Button stopButton = actionButton("STOP", UiTheme.RED,
          v -> runAction(client -> client.stopService(id)));
      startButtons.put(id, startButton);
      stopButtons.put(id, stopButton);
      actions.addView(startButton, pairedButtonParams(false));
      actions.addView(stopButton, pairedButtonParams(true));
      card.addView(actions);
    }

    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(ROW_GAP));
    root.addView(card, params);
  }

  private void addProfileButton(
      LinearLayout row, String service, String label, String profile) {
    Button button = profileButton(
        label, UiTheme.SURFACE, v -> setProfile(service, profile));
    profileButtons.get(service).put(profile, button);
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(0, dp(38), 1);
    params.setMargins(row.getChildCount() == 0 ? 0 : dp(5), 0, 0, 0);
    row.addView(button, params);
  }

  private void setProfile(String service, String profile) {
    managerStatus.setText("●  Switching " + shortServiceName(service)
        + " input to " + titleCase(profile) + "…");
    managerStatus.setTextColor(
        "simulated".equals(profile) ? UiTheme.BLUE : UiTheme.BLUE);
    runAction(client -> client.setServiceProfile(service, profile));
  }

  private String shortServiceName(String service) {
    if (service.endsWith("navigation")) return "navigation";
    if (service.endsWith("automotive")) return "automotive";
    return service;
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
    if (!profileOnly) {
      managerStatus.setText("●  " + targetLabel + " service manager available");
      managerStatus.setTextColor(UiTheme.GREEN);
    }

    JSONArray services = result.optJSONArray("services");
    if (services == null) return;

    for (int i = 0; i < services.length(); i++) {
      JSONObject service = services.optJSONObject(i);
      if (service == null) continue;

      String id = service.optString("name", "");
      TextView stateView = serviceStates.get(id);
      TextView profileView = serviceProfiles.get(id);
      if (stateView == null && profileView == null) continue;

      if (stateView != null) {
        String state = service.optString("state", "unknown").toLowerCase(Locale.US);
        stateView.setText("●  " + titleCase(state));
        if ("running".equals(state)) {
          stateView.setTextColor(UiTheme.GREEN);
        } else if ("stopped".equals(state)) {
          stateView.setTextColor(UiTheme.MUTED);
        } else {
          stateView.setTextColor(UiTheme.RED);
        }
        renderLifecycleButtons(id, state);
        renderCoreLifecycleButtons();
      }

      if (profileView != null) {
        String profile = service.optString("profile", "");
        renderProfile(id, profileView, profile);
        if (profileOnly) {
          managerStatus.setText("●  " + titleCase(profile.isBlank() ? "unknown" : profile)
              + " profile selected");
          managerStatus.setTextColor("simulated".equals(profile) ? UiTheme.BLUE
              : ("local".equals(profile) ? UiTheme.GREEN
                  : ("remote".equals(profile) ? UiTheme.BLUE : UiTheme.MUTED)));
        }
      }
    }
  }

  private void renderCoreLifecycleButtons() {
    if (startCoreButton == null || stopCoreButton == null) return;
    boolean anyRunning = false;
    boolean allRunning = true;
    boolean haveCore = false;
    for (String id : new String[] {
        "openroadcode-message-broker", "openroadcode-navigation", "openroadcode-automotive"
    }) {
      TextView stateView = serviceStates.get(id);
      if (stateView == null) continue;
      haveCore = true;
      String state = stateView.getText().toString().toLowerCase(Locale.US);
      boolean running = state.contains("running");
      anyRunning |= running;
      allRunning &= running;
    }
    startCoreButton.setEnabled(haveCore && !allRunning);
    stopCoreButton.setEnabled(haveCore && anyRunning);
    UiTheme.setButtonColor(activity, startCoreButton,
        startCoreButton.isEnabled() ? UiTheme.BLUE : UiTheme.DISABLED);
    UiTheme.setButtonColor(activity, stopCoreButton,
        stopCoreButton.isEnabled() ? UiTheme.RED : UiTheme.DISABLED);
  }

  private void renderLifecycleButtons(String id, String state) {
    Button start = startButtons.get(id);
    Button stop = stopButtons.get(id);
    if (start == null || stop == null) return;

    boolean running = "running".equals(state);
    boolean stopped = "stopped".equals(state);
    start.setEnabled(stopped);
    stop.setEnabled(running);
    UiTheme.setButtonColor(activity, start, stopped ? UiTheme.BLUE : UiTheme.DISABLED);
    UiTheme.setButtonColor(activity, stop, running ? UiTheme.RED : UiTheme.DISABLED);
  }

  private void renderProfile(String id, TextView view, String profile) {
    Map<String, Button> buttons = profileButtons.get(id);
    if (buttons == null) return;

    switch (profile) {
      case "local" -> {
        view.setText("●  LOCAL INPUT");
        view.setTextColor(UiTheme.BLUE);
      }
      case "remote" -> {
        view.setText("●  REMOTE BRIDGE INPUT");
        view.setTextColor(UiTheme.GREEN);
      }
      case "simulated" -> {
        view.setText("◇  SIMULATED INPUT");
        view.setTextColor(UiTheme.BLUE);
      }
      default -> {
        view.setText("○  PROFILE UNKNOWN");
        view.setTextColor(UiTheme.MUTED);
      }
    }

    for (Map.Entry<String, Button> entry : buttons.entrySet()) {
      boolean selected = entry.getKey().equals(profile);
      int color = UiTheme.SURFACE;
      if (selected) {
        color = "simulated".equals(profile) ? UiTheme.BLUE
            : ("local".equals(profile) ? UiTheme.GREEN : UiTheme.BLUE);
      }
      UiTheme.setButtonColor(activity, entry.getValue(), color);
    }
  }

  private void renderUnavailable(String message) {
    String target = settings.target() == Target.REMOTE_PI ? "Remote Linux" : "Termux";
    managerStatus.setText("●  " + target + " unavailable"
        + (message == null || message.isBlank() ? "" : ": " + message));
    managerStatus.setTextColor(UiTheme.RED);

    for (TextView state : serviceStates.values()) {
      state.setText("●  Unknown");
      state.setTextColor(UiTheme.MUTED);
    }
    for (String id : serviceStates.keySet()) {
      renderLifecycleButtons(id, "unknown");
    }
    for (TextView profile : serviceProfiles.values()) {
      profile.setText("○  PROFILE UNKNOWN");
      profile.setTextColor(UiTheme.MUTED);
    }
  }

  private void runAction(Action action) {
    new Thread(() -> {
      try {
        action.run(activeClient());
        activity.runOnUiThread(this::refresh);
      } catch (Exception e) {
        activity.runOnUiThread(() -> {
          String message = e.getMessage();
          managerStatus.setText("●  "
              + (message == null ? "Service request failed" : message));
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

  private Button profileButton(String label, int color, View.OnClickListener listener) {
    Button button = UiTheme.actionButton(activity, label, color, listener);
    button.setTextSize(10);
    button.setLetterSpacing(.06f);
    return button;
  }

  private LinearLayout buttonRow(Button... buttons) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER);
    row.setPadding(0, 0, 0, dp(10));

    for (int i = 0; i < buttons.length; i++) {
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), 1);
      int left = i == 0 ? 0 : dp(4);
      int right = i == buttons.length - 1 ? 0 : dp(4);
      params.setMargins(left, 0, right, 0);
      row.addView(buttons[i], params);
    }
    return row;
  }

  private LinearLayout.LayoutParams pairedButtonParams(boolean right) {
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(0, dp(BUTTON_HEIGHT), 1);
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
