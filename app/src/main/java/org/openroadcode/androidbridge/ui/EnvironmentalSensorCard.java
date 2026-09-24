package org.openroadcode.androidbridge.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.json.JSONObject;

/** Environmental telemetry presented separately from navigation/motion sensors. */
public final class EnvironmentalSensorCard {
  private final Context context;
  private final LinearLayout root;
  private final TextView ambientLight;
  private final TextView pressure;

  public EnvironmentalSensorCard(Context context) {
    this.context = context;
    root = UiTheme.card(context);

    TextView title = UiTheme.text(context, "ENVIRONMENTAL SENSORS", 18, UiTheme.TEXT);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setLetterSpacing(.08f);
    root.addView(title);

    TextView subtitle = UiTheme.text(context, "Phone environment telemetry", 12, UiTheme.GREEN);
    subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    subtitle.setPadding(0, dp(2), 0, dp(10));
    root.addView(subtitle);

    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(7), 0, dp(7));

    TextView icon = UiTheme.text(context, "☀", 22, UiTheme.GREEN);
    icon.setGravity(Gravity.CENTER);
    row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(44)));

    LinearLayout labels = new LinearLayout(context);
    labels.setOrientation(LinearLayout.VERTICAL);
    TextView name = UiTheme.text(context, "Ambient light", 14, UiTheme.TEXT);
    name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    labels.addView(name);
    labels.addView(UiTheme.text(context, "lux", 11, UiTheme.MUTED));
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

    ambientLight = UiTheme.text(context, "—", 13, UiTheme.TEXT);
    ambientLight.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    ambientLight.setTypeface(Typeface.MONOSPACE);
    row.addView(ambientLight, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(row);

    LinearLayout pressureRow = new LinearLayout(context);
    pressureRow.setGravity(Gravity.CENTER_VERTICAL);
    pressureRow.setPadding(0, dp(7), 0, dp(7));

    TextView pressureIcon = UiTheme.text(context, "◉", 22, UiTheme.BLUE);
    pressureIcon.setGravity(Gravity.CENTER);
    pressureRow.addView(pressureIcon, new LinearLayout.LayoutParams(dp(32), dp(44)));

    LinearLayout pressureLabels = new LinearLayout(context);
    pressureLabels.setOrientation(LinearLayout.VERTICAL);
    TextView pressureName = UiTheme.text(context, "Barometric pressure", 14, UiTheme.TEXT);
    pressureName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    pressureLabels.addView(pressureName);
    pressureLabels.addView(UiTheme.text(context, "hPa", 11, UiTheme.MUTED));
    pressureRow.addView(pressureLabels, new LinearLayout.LayoutParams(0, -2, 1));

    pressure = UiTheme.text(context, "—", 13, UiTheme.TEXT);
    pressure.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    pressure.setTypeface(Typeface.MONOSPACE);
    pressureRow.addView(pressure, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(pressureRow);
  }

  public LinearLayout view() { return root; }

  public void clear() {
    ambientLight.setText("—");
    pressure.setText("—");
  }

  public void displaySample(JSONObject sample) {
    ambientLight.setText(sample.optBoolean("ambient_light_available")
        ? String.format(Locale.US, "%.1f", sample.optDouble("ambient_light_lux"))
        : "Not available");
    pressure.setText(sample.optBoolean("pressure_available")
        ? String.format(Locale.US, "%.2f", sample.optDouble("pressure_hpa"))
        : "Not available");
  }

  private int dp(int value) { return UiTheme.dp(context, value); }
}
