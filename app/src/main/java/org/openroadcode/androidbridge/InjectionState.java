package org.openroadcode.androidbridge;

import android.content.Context;
import java.util.Locale;

/** Persistent subsystem injection state shared by UI and bridge services. */
final class InjectionState {
  private static final String PREFS = "injector";
  private static final String ENV_RADAR_SCENARIO = "environmental.radar.scenario";
  private static final String AUTO_FAULT_SCENARIO = "automotive.fault.scenario";

  private InjectionState() {}

  static void setEnvironmentalRadarScenario(Context context, String scenario) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(ENV_RADAR_SCENARIO, normalize(scenario)).apply();
  }

  static String environmentalRadarScenario(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(ENV_RADAR_SCENARIO, "OFF");
  }

  static void setAutomotiveFaultScenario(Context context, String scenario) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putString(AUTO_FAULT_SCENARIO, normalizeAutomotiveFault(scenario)).apply();
  }

  static String automotiveFaultScenario(Context context) {
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(AUTO_FAULT_SCENARIO, "OFF");
  }

  static String normalizeAutomotiveFault(String value) {
    if (value == null) return "OFF";
    return switch (value.trim().toUpperCase(Locale.ROOT)) {
      case "P0300", "P0171", "P0420", "P0455", "PENDING", "PERMANENT", "NOT_READY" -> value.trim().toUpperCase(Locale.ROOT);
      default -> "OFF";
    };
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
