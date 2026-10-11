package org.openroadcode.androidbridge;

import android.content.Context;
import java.util.Locale;

/** Persistent subsystem injection state shared by UI and bridge services. */
final class InjectionState {
  private static final String PREFS = "injector";
  private static final String ENV_RADAR_SCENARIO = "environmental.radar.scenario";

  private InjectionState() {}

  static void setEnvironmentalRadarScenario(Context context, String scenario) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(ENV_RADAR_SCENARIO, normalize(scenario)).apply();
  }

  static String environmentalRadarScenario(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(ENV_RADAR_SCENARIO, "OFF");
  }

  private static String normalize(String value) {
    if (value == null) return "OFF";
    String scenario = value.trim().toUpperCase(Locale.ROOT);
    return switch (scenario) {
      case "CLEAR", "STORM", "SEVERE" -> scenario;
      default -> "OFF";
    };
  }
}
