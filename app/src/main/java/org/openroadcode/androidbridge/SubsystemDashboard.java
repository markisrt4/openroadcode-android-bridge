package org.openroadcode.androidbridge;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openroadcode.androidbridge.ui.UiTheme;
import org.openroadcode.androidbridge.ui.FeatureIconView;

/** Home navigation grouped around monitoring, phone hardware, and runtime controls. */
final class SubsystemDashboard {
  interface Listener { void onSubsystemSelected(String subsystem); }
  static final String AUTOMOTIVE = "automotive";
  static final String NAVIGATION = "navigation";
  static final String MEDIA = "media";
  static final String RADIO = "radio";
  static final String ENVIRONMENTAL = "environmental";
  static final String RUNTIME = "runtime";
  static final String PERFORMANCE = "performance";
  static final String CONFIGURATION = "configuration";
  static final String DIAGNOSTICS = "diagnostics";
  private final Context context;
  private final Listener listener;

  SubsystemDashboard(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
  }

  View view() {
    LinearLayout root = vertical();
    heading(root, "MANAGE");
    root.addView(link("Runtime", "Core stack and message broker", RUNTIME));
    root.addView(link("Configuration", "Paired computing units", CONFIGURATION));
    heading(root, "FEATURES");
    root.addView(row(
        tile("⌖", "Navigation", "GPS, sensors and service", UiTheme.BLUE, NAVIGATION),
        tile("🚗", "Automotive", "OBD, Bluetooth and service", UiTheme.GREEN, AUTOMOTIVE)));
    root.addView(row(
        tile("◉", "Radio", "RTL-SDR and ADS-B", UiTheme.VIOLET, RADIO),
        tile("▶", "Media", "Camera and audio", UiTheme.RED, MEDIA)));
    root.addView(row(tile("☀", "Environment", "Light and pressure", UiTheme.AMBER, ENVIRONMENTAL), new View(context)));
    heading(root, "MONITOR");
    root.addView(row(
        tile("▥", "Performance", "Workload and service health", UiTheme.BLUE, PERFORMANCE),
        tile("≡", "Live logs", "Events, filters and sharing", UiTheme.GREEN, DIAGNOSTICS)));
    return root;
  }

  private void heading(LinearLayout root, String title) {
    TextView text = UiTheme.text(context, title, 11, UiTheme.MUTED);
    text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    text.setLetterSpacing(.12f);
    text.setPadding(dp(2), dp(16), 0, dp(10));
    root.addView(text);
  }

  private LinearLayout row(View left, View right) {
    LinearLayout row = new LinearLayout(context);
    row.setBaselineAligned(false);
    LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, -1, 1);
    a.setMargins(0, 0, dp(5), dp(10));
    LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, -1, 1);
    b.setMargins(dp(5), 0, 0, dp(10));
    row.addView(left, a);
    row.addView(right, b);
    return row;
  }

  private LinearLayout tile(String icon, String title, String detail, int accent, String route) {
    LinearLayout card = vertical();
    card.setMinimumHeight(dp(128));
    card.setPadding(dp(14), dp(14), dp(14), dp(14));
    boolean feature = !PERFORMANCE.equals(route) && !DIAGNOSTICS.equals(route);
    int weight = feature ? 16 : 24;
    int tint = android.graphics.Color.rgb(
        (android.graphics.Color.red(UiTheme.SURFACE) * (weight - 1) + android.graphics.Color.red(accent)) / weight,
        (android.graphics.Color.green(UiTheme.SURFACE) * (weight - 1) + android.graphics.Color.green(accent)) / weight,
        (android.graphics.Color.blue(UiTheme.SURFACE) * (weight - 1) + android.graphics.Color.blue(accent)) / weight);
    int edge = android.graphics.Color.rgb(
        (android.graphics.Color.red(UiTheme.BORDER) * 3 + android.graphics.Color.red(accent)) / 4,
        (android.graphics.Color.green(UiTheme.BORDER) * 3 + android.graphics.Color.green(accent)) / 4,
        (android.graphics.Color.blue(UiTheme.BORDER) * 3 + android.graphics.Color.blue(accent)) / 4);
    card.setBackground(UiTheme.rounded(context, tint, feature ? edge : UiTheme.BORDER, 14));
    card.addView(new FeatureIconView(context, route, accent),
        new LinearLayout.LayoutParams(dp(28), dp(28)));
    TextView name = UiTheme.text(context, title, 15, UiTheme.TEXT);
    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    name.setPadding(0, dp(12), 0, dp(5));
    card.addView(name);
    card.addView(UiTheme.text(context, detail, 11, UiTheme.MUTED));
    navigate(card, title + ". " + detail, route);
    return card;
  }

  private View link(String title, String detail, String route) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(14), dp(14), dp(14), dp(14));
    row.setBackground(UiTheme.rounded(context, UiTheme.SURFACE, UiTheme.BORDER, 12));
    LinearLayout words = vertical();
    TextView name = UiTheme.text(context, title, 14, UiTheme.TEXT);
    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    words.addView(name);
    TextView subtitle = UiTheme.text(context, detail, 11, UiTheme.MUTED);
    subtitle.setPadding(0, dp(4), dp(8), 0);
    words.addView(subtitle);
    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(UiTheme.text(context, "›", 24, UiTheme.MUTED));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.setMargins(0, 0, 0, dp(8));
    row.setLayoutParams(params);
    navigate(row, title + ". " + detail, route);
    return row;
  }

  private void navigate(View view, String description, String route) {
    view.setClickable(true);
    view.setFocusable(true);
    view.setContentDescription(description);
    android.util.TypedValue ripple = new android.util.TypedValue();
    context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
    if (ripple.resourceId != 0) view.setForeground(context.getDrawable(ripple.resourceId));
    view.setOnClickListener(v -> listener.onSubsystemSelected(route));
  }
  private LinearLayout vertical() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }
  private int dp(int value) { return UiTheme.dp(context, value); }
}
