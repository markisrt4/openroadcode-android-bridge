package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class SystemPerformanceCardTest {
  private JSONObject payload(double age) throws Exception {
    return new JSONObject().put("version", 1).put("sample_age_seconds", age)
        .put("error", JSONObject.NULL).put("snapshot", new JSONObject().put("hostname", "unit"));
  }

  @Test public void freshSupportedSampleIsDisplayed() throws Exception {
    assertEquals("unit", SystemPerformanceCard.freshSnapshot(payload(0)).getString("hostname"));
    assertNotNull(SystemPerformanceCard.freshSnapshot(payload(3)));
  }

  @Test public void staleOrInvalidAgeCannotBeShownAsLive() throws Exception {
    assertNull(SystemPerformanceCard.freshSnapshot(payload(3.01)));
    assertNull(SystemPerformanceCard.freshSnapshot(payload(-1)));
    JSONObject missing = payload(1);
    missing.remove("sample_age_seconds");
    assertNull(SystemPerformanceCard.freshSnapshot(missing));
    assertNull(SystemPerformanceCard.freshSnapshot(payload(1).put("sample_age_seconds", "invalid")));
  }

  @Test public void erroredMissingOrUnsupportedSamplesCannotBeShownAsLive() throws Exception {
    assertNull(SystemPerformanceCard.freshSnapshot(payload(1).put("error", "unavailable")));
    assertNull(SystemPerformanceCard.freshSnapshot(payload(1).put("version", 2)));
    assertNull(SystemPerformanceCard.freshSnapshot(payload(1).put("snapshot", JSONObject.NULL)));
  }
}
