package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class CompanionStatusCardTest {
  @Test public void countsOnlyRunningServices() throws Exception {
    JSONArray services = new JSONArray()
        .put(new JSONObject().put("state", "running"))
        .put(new JSONObject().put("state", "stopped"))
        .put(new JSONObject().put("state", "RUNNING"))
        .put(new JSONObject().put("state", "starting"));
    assertEquals(2, CompanionStatusCard.runningServices(services));
  }

  @Test public void unknownMalformedAndEmptyEntriesAreNotRunning() throws Exception {
    assertEquals(0, CompanionStatusCard.runningServices(new JSONArray()));
    assertEquals(0, CompanionStatusCard.runningServices(new JSONArray()
        .put(JSONObject.NULL).put("unknown").put(new JSONObject())));
  }
}
