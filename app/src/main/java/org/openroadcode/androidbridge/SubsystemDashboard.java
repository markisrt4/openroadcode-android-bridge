package org.openroadcode.androidbridge;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openroadcode.androidbridge.ui.CircuitIconView;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Icon-first launcher for the major OpenRoadCode bridge subsystems. */
final class SubsystemDashboard {
  interface Listener {
    void onSubsystemSelected(String subsystem);
  }

  static final String AUTOMOTIVE = "automotive";
  static final String NAVIGATION = "navigation";
  static final String MEDIA = "media";
  static final String ENVIRONMENTAL = "environmental";
  static final String RUNTIME = "runtime";
  static final String CONFIGURATION = "configuration";

  private final Context context;
  private final Listener listener;

  SubsystemDashboard(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
  }

  View view() {
    LinearLayout root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);

    TextView heading = UiTheme.text(context, "SUBSYSTEMS", 13, UiTheme.SILVER);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setLetterSpacing(.12f);
    heading.setPadding(dp(2), dp(4), dp(2), dp(4));
    root.addView(heading);

    TextView subtitle = UiTheme.text(context,
        "Choose a subsystem or configure how the bridge connects to OpenRoadCode",
        11, UiTheme.MUTED);
    subtitle.setPadding(dp(2), 0, dp(2), dp(12));
    root.addView(subtitle);

    LinearLayout configuration = tile("⚙", "CONFIGURATION",
        "Devices • pairing • remote access • settings",
        UiTheme.SILVER, CONFIGURATION, false);
    LinearLayout.LayoutParams configurationParams = new LinearLayout.LayoutParams(-1, dp(126));
    configurationParams.setMargins(0, 0, 0, dp(10));
    root.addView(configuration, configurationParams);

    root.addView(row(
        tile("🚗", "AUTOMOTIVE", "OBD • Bluetooth • vehicle service",
            UiTheme.GREEN, AUTOMOTIVE, true),
        tile("⌖", "NAVIGATION", "GPS • sensors • navigation service",
            UiTheme.BLUE, NAVIGATION, true)));

    root.addView(row(
        tile("☀", "ENVIRONMENTAL", "Ambient light • environment sensors",
            UiTheme.GREEN, ENVIRONMENTAL, true),
        tile("▶", "MEDIA I/O", "Camera • playback audio • RTL-SDR",
            UiTheme.RED, MEDIA, true)));

    LinearLayout runtime = tile("≡", "RUNTIME", "Termux • Linux • running services",
        UiTheme.SILVER, RUNTIME, false);
    LinearLayout.LayoutParams runtimeParams = new LinearLayout.LayoutParams(-1, dp(126));
    runtimeParams.setMargins(0, dp(5), 0, 0);
    root.addView(runtime, runtimeParams);

    return root;
  }

  private LinearLayout row(View left, View right) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(0, dp(142), 1);
    leftParams.setMargins(0, 0, dp(5), dp(5));
    LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, dp(142), 1);
    rightParams.setMargins(dp(5), 0, 0, dp(5));
    row.addView(left, leftParams);
    row.addView(right, rightParams);
    return row;
  }

  private LinearLayout tile(
      String endpointIcon, String title, String subtitle, int accent, String subsystem,
      boolean bridgeIsSource) {
    LinearLayout tile = new LinearLayout(context);
    tile.setOrientation(LinearLayout.VERTICAL);
    tile.setGravity(Gravity.CENTER);
    tile.setPadding(dp(12), dp(10), dp(12), dp(10));
    tile.setBackground(UiTheme.rounded(context, UiTheme.SURFACE, UiTheme.BORDER, 14));
    tile.setClickable(true);
    tile.setFocusable(true);
    tile.setOnClickListener(v -> listener.onSubsystemSelected(subsystem));

    LinearLayout flow = new LinearLayout(context);
    flow.setOrientation(LinearLayout.HORIZONTAL);
    flow.setGravity(Gravity.CENTER);

    CircuitIconView chip = new CircuitIconView(context, "", accent);
    TextView arrow = UiTheme.text(context, "→", 24, accent);
    arrow.setGravity(Gravity.CENTER);
    arrow.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    TextView endpoint = UiTheme.text(context, endpointIcon, 25, accent);
    endpoint.setGravity(Gravity.CENTER);
    endpoint.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

    if (bridgeIsSource) {
      flow.addView(chip, new LinearLayout.LayoutParams(dp(54), dp(54)));
      flow.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(54)));
      flow.addView(endpoint, new LinearLayout.LayoutParams(dp(54), dp(54)));
    } else {
      flow.addView(endpoint, new LinearLayout.LayoutParams(dp(54), dp(54)));
      flow.addView(arrow, new LinearLayout.LayoutParams(dp(42), dp(54)));
      flow.addView(chip, new LinearLayout.LayoutParams(dp(54), dp(54)));
    }
    tile.addView(flow);

    TextView titleView = UiTheme.text(context, title, 13, UiTheme.TEXT);
    titleView.setGravity(Gravity.CENTER);
    titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    titleView.setLetterSpacing(.08f);
    titleView.setPadding(0, dp(5), 0, dp(3));
    tile.addView(titleView);

    TextView subtitleView = UiTheme.text(context, subtitle, 10, UiTheme.MUTED);
    subtitleView.setGravity(Gravity.CENTER);
    tile.addView(subtitleView);

    return tile;
  }

  private int dp(int value) {
    return UiTheme.dp(context, value);
  }
}
