package org.openroadcode.androidbridge;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * UI and controller for OpenRoadCode services supervised by runit in Termux.
 *
 * MainActivity owns only this component's lifecycle. All service-manager HTTP
 * communication remains inside TermuxServiceManagerClient.
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
  private final TermuxServiceManagerClient client = new TermuxServiceManagerClient();
  private final Handler handler = new Handler(Looper.getMainLooper());

  private final Map<String, TextView> serviceStates = new LinkedHashMap<>();

  private final LinearLayout root;
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

    root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(12), dp(16), dp(12), dp(16));
    root.setBackground(rounded(SURFACE, BORDER, 14));

    TextView title = text("OPENROADCODE SERVICES", 18, TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setLetterSpacing(.08f);
    root.addView(title);

    TextView subtitle = text("Termux • runit • localhost control plane", 12, BLUE);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, dp(2), 0, dp(10));
    root.addView(subtitle);

    managerStatus = statusPill("Checking Termux service manager…", MUTED);
    root.addView(managerStatus);

    root.addView(buttonRow(actionButton("START CORE", BLUE, v -> runAction(client::startCoreStack)),
        actionButton("STOP CORE", RED, v -> runAction(client::stopCoreStack))));

    addService("openroadcode-message-broker", "Message broker", false);
    addService("openroadcode-navigation", "Navigation", false);
    addService("openroadcode-automotive", "Automotive", false);
    addService("openroadcode-adsb", "ADS-B", true);
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

  private void addService(String id, String label, boolean individualControls) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(2), dp(8), dp(2), individualControls ? dp(2) : dp(8));

    TextView name = text(label, 14, TEXT);
    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

    TextView state = text("● UNKNOWN", 12, MUTED);
    state.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
    state.setGravity(Gravity.END);

    row.addView(state, new LinearLayout.LayoutParams(0, -2, 1));

    serviceStates.put(id, state);
    root.addView(row);

    if (individualControls) {
      root.addView(buttonRow(
          actionButton("START ADS-B", BLUE, v -> runAction(() -> client.startService(id))),
          actionButton("STOP ADS-B", RED, v -> runAction(() -> client.stopService(id)))));
    }
  }

  private void refresh() {
    new Thread(() -> {
      try {
        JSONObject result = client.getServices();
        activity.runOnUiThread(() -> render(result));
      } catch (Exception e) {
        activity.runOnUiThread(this::renderUnavailable);
      }
    }, "orc-service-status").start();
  }

  private void render(JSONObject result) {
    managerStatus.setText("●  Termux service manager available");
    managerStatus.setTextColor(GREEN);

    JSONArray services = result.optJSONArray("services");
    if (services == null) {
      return;
    }

    for (int i = 0; i < services.length(); i++) {
      JSONObject service = services.optJSONObject(i);
      if (service == null) {
        continue;
      }

      String id = service.optString("name", "");
      TextView view = serviceStates.get(id);
      if (view == null) {
        continue;
      }

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

  private void renderUnavailable() {
    managerStatus.setText("●  Termux service manager unavailable");
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
        action.run();
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
    JSONObject run() throws Exception;
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
    row.setPadding(0, dp(8), 0, dp(8));

    for (Button button : buttons) {
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
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
