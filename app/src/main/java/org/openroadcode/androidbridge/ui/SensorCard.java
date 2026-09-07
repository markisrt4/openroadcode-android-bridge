package org.openroadcode.androidbridge.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.json.JSONObject;

/** Owns sensor dashboard presentation, not service lifecycle or permissions. */
public final class SensorCard {
    private final Context context;
    private final LinearLayout root;
    private final TextView status;
    private final TextView[] values = new TextView[7];
    private final Button start, stop;

    public SensorCard(Context context, Runnable onStart, Runnable onStop) {
        this.context = context;
        root = UiTheme.card(context);
        TextView title = UiTheme.text(context, "SENSOR BRIDGE", 18, UiTheme.TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        root.addView(UiTheme.text(context, "Phone telemetry • HTTP 8766", 12, UiTheme.BLUE));
        status = UiTheme.text(context, "●  Bridge stopped", 13, UiTheme.MUTED);
        status.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(status);
        String[] names = {"Accelerometer", "Linear acceleration", "Gyroscope", "Magnetometer", "Barometer", "Ambient light", "Position"};
        String[] units = {"m/s²", "m/s²", "rad/s", "µT", "hPa", "lux", "lat / lon • accuracy • sats"};
        String[] icons = {"↗", "⇢", "↻", "⌖", "◉", "☀", "◎"};
        for (int i = 0; i < values.length; i++) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            TextView icon = UiTheme.text(context, icons[i], 22, UiTheme.BLUE);
            row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(44)));
            LinearLayout labels = new LinearLayout(context);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = UiTheme.text(context, names[i], 14, UiTheme.TEXT);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            labels.addView(name);
            labels.addView(UiTheme.text(context, units[i], 11, UiTheme.MUTED));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1.18f));
            values[i] = UiTheme.text(context, "—", 13, UiTheme.TEXT);
            values[i].setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            values[i].setTypeface(Typeface.MONOSPACE);
            row.addView(values[i], new LinearLayout.LayoutParams(0, -2, 1.62f));
            root.addView(row);
        }
        start = UiTheme.actionButton(context, "START BRIDGE", UiTheme.BLUE, v -> onStart.run());
        stop = UiTheme.actionButton(context, "STOP", UiTheme.SURFACE_RAISED, v -> onStop.run());
        LinearLayout buttons = new LinearLayout(context);
        buttons.setPadding(0, dp(8), 0, 0);
        for (Button button : new Button[] {start, stop}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
            p.setMargins(dp(3), 0, dp(3), 0);
            buttons.addView(button, p);
        }
        root.addView(buttons);
    }

    public LinearLayout view() { return root; }
    public void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }
    public void setRunning(boolean running) {
        start.setText(running ? "RUNNING" : "START BRIDGE");
        UiTheme.setButtonColor(context, start, running ? UiTheme.SURFACE_RAISED : UiTheme.BLUE);
        UiTheme.setButtonColor(context, stop, running ? UiTheme.RED : UiTheme.SURFACE_RAISED);
    }
    public void clear() { for (TextView value : values) value.setText("—"); }
    public void displaySample(JSONObject r) {
        values[0].setText(vector(r.optJSONObject("acceleration_mps2")));
        values[1].setText(r.optBoolean("linear_acceleration_available") ? vector(r.optJSONObject("linear_acceleration_mps2")) : "Not available");
        values[2].setText(vector(r.optJSONObject("angular_velocity_rad_s")));
        values[3].setText(r.optBoolean("magnetometer_available") ? vector(r.optJSONObject("magnetic_field_uT")) : "Not available");
        values[4].setText(r.optBoolean("pressure_available") ? String.format(Locale.US, "%.2f", r.optDouble("pressure_hpa")) : "Not available");
        values[5].setText(r.optBoolean("ambient_light_available") ? String.format(Locale.US, "%.1f", r.optDouble("ambient_light_lux")) : "Not available");
    }
    public void displayPosition(JSONObject r) {
        if (!r.optBoolean("permission_granted", true)) { values[6].setText("Permission required"); return; }
        if (!r.optBoolean("ready")) { values[6].setText(r.optBoolean("available") ? "Waiting for fix" : "Provider unavailable"); return; }
        double a = r.optDouble("horizontal_accuracy_m", Double.NaN);
        int v = r.optInt("satellites_visible", -1), u = r.optInt("satellites_used_in_fix", -1);
        String sats = v < 0 ? "sats —" : (u >= 0 ? "sats " + u + "/" + v : "sats " + v);
        values[6].setText(String.format(Locale.US, "%.6f, %.6f\n%s  %s\n%s", r.optDouble("latitude"), r.optDouble("longitude"), Double.isNaN(a) ? "accuracy —" : String.format(Locale.US, "±%.1f m", a), r.optString("provider", ""), sats));
    }
    private String vector(JSONObject v) {
        if (v == null) return "—";
        return String.format(Locale.US, "x %+.2f  y %+.2f\nz %+.2f", v.optDouble("x"), v.optDouble("y"), v.optDouble("z"));
    }
    private int dp(int value) { return UiTheme.dp(context, value); }
}
