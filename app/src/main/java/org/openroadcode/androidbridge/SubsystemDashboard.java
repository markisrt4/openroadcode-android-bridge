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
  static final String CONNECTIVITY = "connectivity";
  static final String RUNTIME = "runtime";

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
        "Choose a subsystem to configure inputs, bridges, and runtime services",
        11, UiTheme.MUTED);
    subtitle.setPadding(dp(2), 0, dp(2), dp(12));
    root.addView(subtitle);

    root.addView(row(
        tile("▣", "AUTOMOTIVE", "OBD • Bluetooth • vehicle service",
            UiTheme.GREEN, AUTOMOTIVE),
        tile("⌖", "NAVIGATION", "GPS • sensors • navigation service",
            UiTheme.BLUE, NAVIGATION)));

    root.addView(row(
        tile("◉", "MEDIA I/O", "Camera • playback audio • RTL-SDR",
            UiTheme.RED, MEDIA),
        tile("⇄", "CONNECTIVITY", "LAN access • bridge endpoints",
            UiTheme.BLUE, CONNECTIVITY)));

    LinearLayout runtime = tile("⚙", "RUNTIME", "Termux • Linux • service profiles",
        UiTheme.SILVER, RUNTIME);
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
      String icon, String title, String subtitle, int accent, String subsystem) {
    LinearLayout tile = new LinearLayout(context);
    tile.setOrientation(LinearLayout.VERTICAL);
    tile.setGravity(Gravity.CENTER);
    tile.setPadding(dp(12), dp(12), dp(12), dp(12));
    tile.setBackground(UiTheme.rounded(context, UiTheme.SURFACE, UiTheme.BORDER, 14));
    tile.setClickable(true);
    tile.setFocusable(true);
    tile.setOnClickListener(v -> listener.onSubsystemSelected(subsystem));

    CircuitIconView iconView = new CircuitIconView(context, icon, accent);
    tile.addView(iconView, new LinearLayout.LayoutParams(dp(64), dp(64)));

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
