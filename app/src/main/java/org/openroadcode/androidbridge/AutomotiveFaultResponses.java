package org.openroadcode.androidbridge;

import java.util.Locale;

/** Deterministic SAE J1979 responses for the local simulated ECU only. */
final class AutomotiveFaultResponses {
  private AutomotiveFaultResponses() {}

  static String monitorStatus(String scenario) {
    boolean fault = !"OFF".equals(scenario) && !"NOT_READY".equals(scenario);
    boolean mil = fault && !"PENDING".equals(scenario);
    int storedCount = mil ? 1 : 0;
    int a = (mil ? 0x80 : 0) | storedCount;
    // Spark ignition; incomplete catalyst and oxygen sensor monitors when requested.
    int c = "NOT_READY".equals(scenario) ? 0x21 : 0x00;
    return String.format(Locale.US, "41 01 %02X 00 %02X 00", a, c);
  }

  static String codes(String scenario, String modeResponse) {
    boolean stored = "43".equals(modeResponse);
    boolean pending = "47".equals(modeResponse);
    boolean permanent = "4A".equals(modeResponse);
    boolean include = (stored && !("PENDING".equals(scenario) || "PERMANENT".equals(scenario)))
        || (pending && "PENDING".equals(scenario))
        || (permanent && "PERMANENT".equals(scenario));
    if (!include || "OFF".equals(scenario) || "NOT_READY".equals(scenario)) {
      return modeResponse + " 00 00";
    }
    String code = switch (scenario) {
      case "P0171" -> "01 71";
      case "P0420" -> "04 20";
      case "P0455" -> "04 55";
      default -> "03 00";
    };
    return modeResponse + " " + code;
  }
}
