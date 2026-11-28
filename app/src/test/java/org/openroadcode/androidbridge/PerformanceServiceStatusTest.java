// SPDX-License-Identifier: MIT
package org.openroadcode.androidbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class PerformanceServiceStatusTest {
  @Test public void runningNavigationCanHaveUnavailableTraffic() throws Exception {
    var services = new JSONArray()
        .put(new JSONObject().put("name", "openroadcode-navigation").put("state", "running"))
        .put(new JSONObject().put("name", "openroadcode-automotive").put("state", "stopped"))
        .put(new JSONObject().put("name", "openroadcode-calendar-sync").put("state", "unknown"));
    var states = PerformanceServiceStatus.readStates(new JSONObject().put("services", services));

    assertEquals("running", PerformanceServiceStatus.managedState("Navigation", states));
    assertEquals("stopped", PerformanceServiceStatus.managedState("Automotive", states));
    assertNull(PerformanceServiceStatus.managedState("Calendar sync", states));
    assertNull(PerformanceServiceStatus.managedState("GPSD", states));
    assertEquals("Traffic unavailable", PerformanceServiceStatus.trafficSummary("unavailable", null, 1));
    assertEquals("No sockets observed", PerformanceServiceStatus.trafficSummary("not_observed", null, 1));
    assertEquals("listening · TCP · 2 endpoint(s)", PerformanceServiceStatus.trafficSummary("listening", "TCP", 2));
  }
}
