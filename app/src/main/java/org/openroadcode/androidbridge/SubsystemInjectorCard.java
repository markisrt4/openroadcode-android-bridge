package org.openroadcode.androidbridge;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Bottom-of-subsystem development controls for deterministic simulation. */
final class SubsystemInjectorCard {
  interface Listener {
    void onSimulate(String subsystem, String scenario);
  }

  private final Context context;
  private final String subsystem;
  private final Listener listener;

  SubsystemInjectorCard(Context context, String subsystem, Listener listener) {
    this.context = context;
    this.subsystem = subsystem;
    this.listener = listener;
  }

  View view() {
    LinearLayout root = new LinearLayout(context);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(2), dp(4), dp(2), dp(4));

    boolean automotive = SubsystemDashboard.AUTOMOTIVE.equals(subsystem);
    TextView note = UiTheme.text(context,
        simulationNote(),
        automotive ? 13 : 11, UiTheme.MUTED);
    note.setPadding(0, 0, 0, dp(8));
    root.addView(note);

    TextView selectedTest = UiTheme.text(context, "", 14, UiTheme.TEXT);
    if (automotive) {
      selectedTest.setPadding(0, 0, 0, dp(12));
      selectedTest.setText(automotiveScenarioDescription(currentScenario()));
      root.addView(selectedTest);
    }

    LinearLayout actions = new LinearLayout(context);
    if (SubsystemDashboard.AUTOMOTIVE.equals(subsystem)) {
      actions.setOrientation(LinearLayout.VERTICAL);
    }
    actions.setGravity(Gravity.CENTER_VERTICAL);
    for (String scenario : scenarios()) {
      Button button = UiTheme.actionButton(context, automotive ? automotiveScenarioLabel(scenario) : scenario,
          UiTheme.SURFACE_RAISED,
          v -> {
            listener.onSimulate(subsystem, scenario);
            updateSelection(actions, scenario);
            if (automotive) selectedTest.setText(automotiveScenarioDescription(scenario));
          });
      button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      if (automotive) {
        button.setTextSize(14);
        button.setLetterSpacing(0);
        button.setMinHeight(dp(52));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
      }
      LinearLayout.LayoutParams params = SubsystemDashboard.AUTOMOTIVE.equals(subsystem)
          ? new LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
          : new LinearLayout.LayoutParams(0, dp(44), 1);
      params.setMargins(dp(2), 0, dp(2), automotive ? dp(6) : 0);
      button.setTag(scenario);
      actions.addView(button, params);
    }
    root.addView(actions);
    updateSelection(actions, currentScenario());
    return root;
  }

  private String simulationNote() {
    if (SubsystemDashboard.ENVIRONMENTAL.equals(subsystem)) {
      return "Weather simulation • select a deterministic radar scenario";
    }
    if (SubsystemDashboard.AUTOMOTIVE.equals(subsystem)) {
      return "Choose a simulated vehicle test below, then use Scan Now on ORC’s HEALTH screen. "
          + "The simulated vehicle connection must be running. These tests do not affect your real vehicle.";
    }
    return "Development simulation • simulated values replace live input";
  }

  static String automotiveScenarioLabel(String scenario) {
    return switch (scenario) {
      case "P0300" -> "Engine misfire · P0300";
      case "P0171" -> "Too much air / too little fuel · P0171";
      case "P0420" -> "Catalytic converter efficiency low · P0420";
      case "P0455" -> "Fuel-vapor system leak · P0455";
      case "PENDING" -> "Possible misfire · Pending code";
      case "PERMANENT" -> "Retained misfire record · Permanent code";
      case "NOT_READY" -> "Emissions checks unfinished · Not ready";
      default -> "No simulated fault · Off";
    };
  }

  private static String automotiveScenarioDescription(String scenario) {
    return switch (scenario) {
      case "P0300" -> "Test: engine misfire. Simulates uneven firing in multiple cylinders. "
          + "HEALTH should show P0300 as a stored fault and the Check Engine light on.";
      case "P0171" -> "Test: lean fuel mixture. Simulates too much air or too little fuel. "
          + "HEALTH should show P0171 as a stored fault and the Check Engine light on.";
      case "P0420" -> "Test: reduced exhaust-cleaning performance. "
          + "HEALTH should show P0420 as a stored fault and the Check Engine light on. "
          + "A code alone does not prove the converter needs replacement.";
      case "P0455" -> "Test: a large leak in the system that contains fuel vapors, such as a loose fuel cap. "
          + "HEALTH should show P0455 as a stored fault and the Check Engine light on.";
      case "PENDING" -> "Test: a possible problem that is not yet confirmed. "
          + "HEALTH should show pending misfire code P0300, with the Check Engine light off.";
      case "PERMANENT" -> "Test: an emissions fault record retained by the vehicle computer. "
          + "HEALTH should show permanent misfire code P0300. In this test, the Check Engine light is off "
          + "and there are no stored or pending faults.";
      case "NOT_READY" -> "Test: emissions self-checks have not finished yet. "
          + "HEALTH should show readiness as not ready, with no fault codes and the Check Engine light off.";
      default -> "Test: no injected faults. HEALTH should show no generic emissions faults, "
          + "the Check Engine light off, and emissions checks ready. This does not turn off the simulator.";
    };
  }

  private String currentScenario() {
    if (SubsystemDashboard.ENVIRONMENTAL.equals(subsystem)) {
      return InjectionState.environmentalRadarScenario(context);
    }
    if (SubsystemDashboard.AUTOMOTIVE.equals(subsystem)) {
      return InjectionState.automotiveFaultScenario(context);
    }
    return "";
  }

  private void updateSelection(LinearLayout actions, String selected) {
    for (int index = 0; index < actions.getChildCount(); index++) {
      View child = actions.getChildAt(index);
      if (!(child instanceof Button button)) continue;
      boolean active = selected.equals(button.getTag());
      UiTheme.setButtonColor(context, button, active ? UiTheme.GREEN : UiTheme.SURFACE_RAISED);
    }
  }

  private String[] scenarios() {
    return switch (subsystem) {
      case SubsystemDashboard.AUTOMOTIVE ->
          new String[] {"OFF", "P0300", "P0171", "P0420", "P0455", "PENDING", "PERMANENT", "NOT_READY"};
      case SubsystemDashboard.NAVIGATION ->
          new String[] {"STOPPED", "DRIVE", "TURN"};
      case SubsystemDashboard.ENVIRONMENTAL ->
          new String[] {"CLEAR", "STORM", "SEVERE"};
      case SubsystemDashboard.MEDIA ->
          new String[] {"TEST"};
      default -> new String[] {"RESET"};
    };
  }

  private int dp(int value) {
    return UiTheme.dp(context, value);
  }
}
