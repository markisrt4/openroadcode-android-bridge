package org.openroadcode.androidbridge;

import org.json.JSONArray;
import org.json.JSONObject;

/** Whole-stack actions use all core service states, even when only the broker has a card. */
record CoreStackState(boolean canStart, boolean canStop) {
  static CoreStackState from(JSONArray services) {
    boolean allKnown = true, allRunning = true, anyRunning = false;
    for (String name : new String[] {
        "openroadcode-message-broker", "openroadcode-navigation", "openroadcode-automotive"}) {
      String state = "unknown";
      for (int i = 0; i < services.length(); i++) {
        JSONObject service = services.optJSONObject(i);
        if (service != null && name.equals(service.optString("name"))) {
          state = service.optString("state", "unknown").toLowerCase(java.util.Locale.US);
          break;
        }
      }
      allKnown &= "running".equals(state) || "stopped".equals(state);
      allRunning &= "running".equals(state);
      anyRunning |= "running".equals(state);
    }
    return new CoreStackState(allKnown && !allRunning, anyRunning);
  }
}
