// SPDX-License-Identifier: MIT
package org.openroadcode.androidbridge;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Match known socket groups to the service manager's independent lifecycle state. */
final class PerformanceServiceStatus {
  private PerformanceServiceStatus() {}

  static Map<String, String> readStates(JSONObject response) {
    Map<String, String> states = new HashMap<>();
    JSONArray services = response.optJSONArray("services");
    if (services == null) return states;
    for (int i = 0; i < services.length(); i++) {
      JSONObject service = services.optJSONObject(i);
      if (service == null) continue;
      String state = service.optString("state");
      if ("running".equals(state) || "stopped".equals(state)) {
        states.put(service.optString("name"), state);
      }
    }
    return states;
  }

  static String managedState(String group, Map<String, String> states) {
    String id = switch (group) {
      case "Message broker" -> "openroadcode-message-broker";
      case "Navigation" -> "openroadcode-navigation";
      case "Automotive" -> "openroadcode-automotive";
      case "ADS-B" -> "openroadcode-adsb";
      case "Calendar sync" -> "openroadcode-calendar-sync";
      case "Carbonate bridge" -> "openroadcode-carbonate";
      default -> "";
    };
    return id.isEmpty() ? null : states.get(id);
  }

  static String trafficSummary(String state, String protocol, int endpointCount) {
    if (protocol == null) {
      return "unavailable".equals(state) ? "Traffic unavailable" : "No sockets observed";
    }
    return state.replace('_', ' ') + " · " + protocol + " · " + endpointCount + " endpoint(s)";
  }
}
