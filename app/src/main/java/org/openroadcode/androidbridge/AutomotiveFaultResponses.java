package org.openroadcode.androidbridge;

import java.util.Locale;

/** Deterministic SAE J1979 responses for the local simulated ECU only. */
final class AutomotiveFaultResponses {
  private AutomotiveFaultResponses() {}

  static String monitorStatus(String scenario) {
    boolean fault = scenario != null && scenario.matches("P(?:0300|0171|0420|0455)");
    boolean mil = fault;
    int storedCount = mil ? 1 : 0;
    int a = (mil ? 0x80 : 0) | storedCount;
    // Spark ignition; incomplete catalyst and oxygen sensor monitors when requested.
    int incomplete = "NOT_READY".equals(scenario) ? 0x21 : 0x00;
    return String.format(Locale.US, "41 01 %02X 07 21 %02X", a, incomplete);
  }

  /** ELM header format consumed by ORC: ECU, payload length, service payload. */
  static String frame(String payload, boolean headers, boolean spaces) {
    String compact = payload.replace(" ", "");
    String framed = headers
        ? String.format(Locale.US, "7E8 %02X %s", compact.length() / 2, payload)
        : payload;
    return spaces ? framed : framed.replace(" ", "");
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
