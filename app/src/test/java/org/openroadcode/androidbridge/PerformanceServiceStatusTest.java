// SPDX-License-Identifier: MIT
package org.openroadcode.androidbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;
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
    assertEquals("Traffic unavailable", PerformanceServiceStatus.trafficSummary("unavailable", "--", 1));
    assertEquals("No sockets observed", PerformanceServiceStatus.trafficSummary("not_observed", null, 1));
    assertEquals("listening · TCP · 2 endpoint(s)", PerformanceServiceStatus.trafficSummary("listening", "TCP", 2));
  }

  @Test public void skipsPlaceholdersWithoutManagedStatusAndKeepsObservedSockets() throws Exception {
    var states = Collections.singletonMap("openroadcode-navigation", "running");
    var row = new JSONObject().put("name", "apps.orcUi").put("pid", 11).put("protocol", "--");
    assertNull(PerformanceServiceStatus.rowKey(row, states));
    assertEquals("socket:apps.orcUi:11:TCP", PerformanceServiceStatus.rowKey(row.put("protocol", "TCP"), states));
    row.put("name", "Navigation").put("protocol", "--");
    assertEquals("managed:Navigation", PerformanceServiceStatus.rowKey(row, states));
    assertEquals("managed:Navigation", PerformanceServiceStatus.rowKey(row.put("pid", 12), states));
  }

  @Test public void calendarIntegrationsStayVisibleWithoutSocketOrServiceStatus() throws Exception {
    var row = new JSONObject().put("protocol", "--").put("name", "Calendar sync");
    assertEquals("managed:Calendar sync", PerformanceServiceStatus.rowKey(row, Collections.emptyMap()));
    assertEquals("managed:Carbonate bridge", PerformanceServiceStatus.rowKey(
        row.put("name", "Carbonate bridge"), Collections.emptyMap()));
    assertEquals("Last sync is stale", PerformanceServiceStatus.calendarSyncSummary(
        new JSONObject().put("status", "ok").put("stale", true)));
    assertEquals("Sync health unavailable", PerformanceServiceStatus.calendarSyncSummary(null));
  }
}
