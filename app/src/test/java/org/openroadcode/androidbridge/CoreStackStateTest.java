package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class CoreStackStateTest {
  private JSONObject service(String name, String state) throws Exception {
    return new JSONObject().put("name", "openroadcode-" + name).put("state", state);
  }
  private JSONArray stack(String broker, String navigation, String automotive) throws Exception {
    return new JSONArray().put(service("message-broker", broker))
        .put(service("navigation", navigation)).put(service("automotive", automotive));
  }
  @Test public void brokerRunningDoesNotMeanEntireCoreIsRunning() throws Exception {
    CoreStackState state = CoreStackState.from(stack("running", "stopped", "stopped"));
    assertTrue(state.canStart()); assertTrue(state.canStop());
  }
  @Test public void allStoppedAllowsStartAndAllRunningAllowsOnlyStop() throws Exception {
    assertEquals(new CoreStackState(true, false), CoreStackState.from(stack("stopped", "stopped", "stopped")));
    assertEquals(new CoreStackState(false, true), CoreStackState.from(stack("running", "running", "running")));
  }
  @Test public void missingOrUnknownServicesCannotEnableStart() throws Exception {
    assertEquals(new CoreStackState(false, false), CoreStackState.from(new JSONArray()));
    assertEquals(new CoreStackState(false, true), CoreStackState.from(new JSONArray().put(service("message-broker", "running"))));
    assertFalse(CoreStackState.from(stack("stopped", "unknown", "stopped")).canStart());
  }
  @Test public void adsbDoesNotAffectCoreStackButtons() throws Exception {
    JSONArray list = stack("stopped", "stopped", "stopped").put(service("adsb", "running"));
    assertEquals(new CoreStackState(true, false), CoreStackState.from(list));
  }
}
