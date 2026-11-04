package org.openroadcode.androidbridge;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.EnumMap;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Scenario chooser for simulated ECU tests; owns presentation only. */
final class AutomotiveInjectorView {
  private final Context context;
  private final SubsystemInjectorCard.Listener listener;
  private final EnumMap<AutomotiveFaultScenario, LinearLayout> cards =
      new EnumMap<>(AutomotiveFaultScenario.class);
  private final EnumMap<AutomotiveFaultScenario, TextView> markers =
      new EnumMap<>(AutomotiveFaultScenario.class);
  private AutomotiveFaultScenario selected;
  private TextView selectedTitle;
  private TextView problem;
  private TextView expected;
  private TextView technical;

  AutomotiveInjectorView(Context context, SubsystemInjectorCard.Listener listener) {
    this.context = context;
    this.listener = listener;
    selected = AutomotiveFaultScenario.fromId(InjectionState.automotiveFaultScenario(context));
  }

  View view() {
    LinearLayout root = column();
    root.addView(text("SIMULATED VEHICLE · TEST TOOL", 12, UiTheme.BLUE, true));
    TextView guidance = text("Choose a test, then open VEHICLE → HEALTH → Scan Now in ORC. "
        + "Start the simulated vehicle connection first. Real vehicles are unaffected.", 13, UiTheme.MUTED, false);
    guidance.setPadding(0, dp(6), 0, dp(14));
    root.addView(guidance);

    LinearLayout result = UiTheme.card(context);
    result.setBackground(UiTheme.rounded(context, UiTheme.SURFACE_RAISED, UiTheme.BLUE, 12));
    result.addView(text("What should I see?", 17, UiTheme.TEXT, true));
    selectedTitle = text("", 14, UiTheme.BLUE, true);
    selectedTitle.setPadding(0, dp(10), 0, dp(8));
    result.addView(selectedTitle);
    result.addView(text("Simulated problem", 12, UiTheme.MUTED, true));
    problem = text("", 14, UiTheme.TEXT, false);
    result.addView(problem);
    TextView expectedLabel = text("Expected in ORC", 12, UiTheme.MUTED, true);
    expectedLabel.setPadding(0, dp(10), 0, 0);
    result.addView(expectedLabel);
    expected = text("", 14, UiTheme.TEXT, false);
    expected.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    result.addView(expected);
    Button details = UiTheme.actionButton(context, "Show technical details", UiTheme.SURFACE, v -> {
      boolean show = technical.getVisibility() != View.VISIBLE;
      technical.setVisibility(show ? View.VISIBLE : View.GONE);
      ((Button) v).setText(show ? "Hide technical details" : "Show technical details");
    });
    details.setTextSize(13);
    details.setLetterSpacing(0);
    LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
    detailParams.topMargin = dp(12);
    result.addView(details, detailParams);
    technical = text("", 13, UiTheme.MUTED, false);
    technical.setTypeface(Typeface.MONOSPACE);
    technical.setVisibility(View.GONE);
    technical.setPadding(0, dp(8), 0, 0);
    result.addView(technical);
    root.addView(result);

    section(root, "Baseline", AutomotiveFaultScenario.OFF);
    section(root, "Confirmed problems", AutomotiveFaultScenario.MISFIRE,
        AutomotiveFaultScenario.LEAN, AutomotiveFaultScenario.CATALYST, AutomotiveFaultScenario.VAPOR_LEAK);
    section(root, "Reporting & readiness tests", AutomotiveFaultScenario.PENDING,
        AutomotiveFaultScenario.PERMANENT, AutomotiveFaultScenario.NOT_READY);
    TextView note = text("These tests show how a fault is reported. A code alone does not identify "
        + "a failed part. Choosing “No injected fault” does not stop the simulator.", 12, UiTheme.MUTED, false);
    note.setPadding(0, dp(8), 0, 0);
    root.addView(note);
    updateSelection();
    return root;
  }

  private void section(LinearLayout root, String label, AutomotiveFaultScenario... scenarios) {
    TextView heading = text(label, 14, UiTheme.TEXT, true);
    heading.setPadding(0, dp(18), 0, dp(8));
    root.addView(heading);
    for (int index = 0; index < scenarios.length; index += 2) {
      LinearLayout row = new LinearLayout(context);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setBaselineAligned(false);
      for (int offset = 0; offset < 2 && index + offset < scenarios.length; offset++) {
        AutomotiveFaultScenario scenario = scenarios[index + offset];
        LinearLayout card = column();
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setMinimumHeight(dp(104));
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnClickListener(v -> {
          listener.onSimulate(SubsystemDashboard.AUTOMOTIVE, scenario.id);
          selected = scenario;
          updateSelection();
        });
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
          @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(host.isSelected());
          }
        });
        TextView marker = text("", 12, UiTheme.BLUE, true);
        markers.put(scenario, marker);
        card.addView(marker);
        card.addView(text(scenario.title, 15, UiTheme.TEXT, true));
        TextView subtitle = text(scenario.subtitle, 12, UiTheme.MUTED, false);
        subtitle.setPadding(0, dp(4), 0, 0);
        card.addView(subtitle);
        for (int child = 0; child < card.getChildCount(); child++) {
          card.getChildAt(child).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(offset == 0 ? 0 : dp(4), 0, offset == 0 ? dp(4) : 0, dp(8));
        row.addView(card, params);
        cards.put(scenario, card);
      }
      root.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
  }

  private void updateSelection() {
    for (AutomotiveFaultScenario scenario : cards.keySet()) {
      boolean active = scenario == selected;
      LinearLayout card = cards.get(scenario);
      card.setSelected(active);
      card.setContentDescription(scenario.title + ". " + scenario.subtitle
          + (active ? ". Selected" : ". Select test"));
      GradientDrawable background = UiTheme.rounded(context,
          active ? UiTheme.SURFACE_RAISED : UiTheme.SURFACE,
          active ? UiTheme.BLUE : UiTheme.BORDER, 12);
      background.setStroke(dp(active ? 2 : 1), active ? UiTheme.BLUE : UiTheme.BORDER);
      card.setBackground(background);
      markers.get(scenario).setText(active ? "✓ Selected" : "○ Select");
    }
    selectedTitle.setText(selected.title);
    problem.setText(selected.problem);
    expected.setText(selected.expected);
    technical.setText(selected.technicalDetails());
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  private TextView text(String value, int size, int color, boolean bold) {
    TextView view = UiTheme.text(context, value, size, color);
    if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return view;
  }

  private int dp(int value) { return UiTheme.dp(context, value); }
}
