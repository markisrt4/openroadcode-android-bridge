package org.openroadcode.androidbridge;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.openroadcode.androidbridge.ui.UiTheme;

/** Bottom-of-subsystem development controls for deterministic input injection. */
final class SubsystemInjectorCard {
  interface Listener {
    void onInject(String subsystem, String scenario);
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

    TextView note = UiTheme.text(context,
        "Development input override • injected values replace live input",
        11, UiTheme.MUTED);
    note.setPadding(0, 0, 0, dp(8));
    root.addView(note);

    LinearLayout actions = new LinearLayout(context);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    for (String scenario : scenarios()) {
      Button button = UiTheme.actionButton(context, scenario, UiTheme.SURFACE_RAISED,
          v -> listener.onInject(subsystem, scenario));
      button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
      params.setMargins(dp(2), 0, dp(2), 0);
      actions.addView(button, params);
    }
    root.addView(actions);
    return root;
  }

  private String[] scenarios() {
    return switch (subsystem) {
      case SubsystemDashboard.AUTOMOTIVE ->
          new String[] {"IDLE", "CRUISE", "BOOST"};
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
