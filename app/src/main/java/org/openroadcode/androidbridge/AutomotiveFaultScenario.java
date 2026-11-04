package org.openroadcode.androidbridge;

/** Plain-language test descriptions, with protocol identifiers kept separate. */
enum AutomotiveFaultScenario {
  OFF("OFF", "No injected fault", "Baseline test", "No fault is added to the simulated vehicle.",
      "No generic emissions faults; Check Engine light off; emissions checks ready.", null, "none", false, true),
  MISFIRE("P0300", "Engine misfire", "Uneven engine firing", "Multiple cylinders are not firing evenly.",
      "A confirmed misfire fault; Check Engine light on; emissions checks ready.", "P0300", "stored", true, true),
  LEAN("P0171", "Lean fuel mixture", "Too much air or too little fuel", "The engine's fuel mixture has too much air or too little fuel.",
      "A confirmed lean-mixture fault; Check Engine light on; emissions checks ready.", "P0171", "stored", true, true),
  CATALYST("P0420", "Catalyst efficiency", "Reduced exhaust cleaning", "The catalytic converter is reporting reduced exhaust-cleaning performance.",
      "A confirmed catalyst-efficiency fault; Check Engine light on; emissions checks ready.", "P0420", "stored", true, true),
  VAPOR_LEAK("P0455", "Fuel-vapor leak", "Large leak in the vapor system", "The fuel-vapor system reports a large leak, such as a loose fuel cap.",
      "A confirmed fuel-vapor leak fault; Check Engine light on; emissions checks ready.", "P0455", "stored", true, true),
  PENDING("PENDING", "Possible misfire", "Not yet confirmed", "A possible misfire has been detected but is not confirmed.",
      "A pending misfire fault; no stored or permanent faults; Check Engine light off; emissions checks ready.", "P0300", "pending", false, true),
  PERMANENT("PERMANENT", "Retained fault record", "Permanent misfire record", "The vehicle computer retains an emissions-related misfire record.",
      "A permanent misfire record; no stored or pending faults; Check Engine light off in this test; emissions checks ready.", "P0300", "permanent", false, true),
  NOT_READY("NOT_READY", "Checks unfinished", "Emissions checks not ready", "The simulated vehicle has not finished its emissions self-checks.",
      "Readiness not ready; no fault codes; Check Engine light off.", null, "none", false, false);

  final String id;
  final String title;
  final String subtitle;
  final String problem;
  final String expected;
  final String code;
  final String status;
  final boolean mil;
  final boolean ready;

  AutomotiveFaultScenario(String id, String title, String subtitle, String problem,
      String expected, String code, String status, boolean mil, boolean ready) {
    this.id = id;
    this.title = title;
    this.subtitle = subtitle;
    this.problem = problem;
    this.expected = expected;
    this.code = code;
    this.status = status;
    this.mil = mil;
    this.ready = ready;
  }

  String technicalDetails() {
    return "DTC: " + (code == null ? "none" : code) + "\nRecord: " + status
        + "\nMIL (Check Engine): " + (mil ? "on" : "off")
        + "\nEmissions readiness: " + (ready ? "ready" : "not ready");
  }

  static AutomotiveFaultScenario fromId(String id) {
    for (AutomotiveFaultScenario scenario : values()) {
      if (scenario.id.equals(id)) return scenario;
    }
    return OFF;
  }
}
