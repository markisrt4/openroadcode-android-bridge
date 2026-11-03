package org.openroadcode.androidbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutomotiveFaultResponsesTest {
  @Test public void confirmedFaultsHaveMatchingMilCountAndStoredCode() {
    String[] scenarios = {"P0300", "P0171", "P0420", "P0455"};
    String[] words = {"03 00", "01 71", "04 20", "04 55"};
    for (int i = 0; i < scenarios.length; i++) {
      assertEquals("41 01 81 07 21 00", AutomotiveFaultResponses.monitorStatus(scenarios[i]));
      assertEquals("43 " + words[i], AutomotiveFaultResponses.codes(scenarios[i], "43"));
      assertEquals("47 00 00", AutomotiveFaultResponses.codes(scenarios[i], "47"));
      assertEquals("4A 00 00", AutomotiveFaultResponses.codes(scenarios[i], "4A"));
    }
  }
  @Test public void pendingAndPermanentDoNotImplyConfirmedMilOrStoredCount() {
    for (String scenario : new String[]{"PENDING", "PERMANENT"}) {
      assertEquals("41 01 00 07 21 00", AutomotiveFaultResponses.monitorStatus(scenario));
      assertEquals("43 00 00", AutomotiveFaultResponses.codes(scenario, "43"));
    }
    assertEquals("47 03 00", AutomotiveFaultResponses.codes("PENDING", "47"));
    assertEquals("4A 03 00", AutomotiveFaultResponses.codes("PERMANENT", "4A"));
  }
  @Test public void incompleteMonitorsAreSupportedAndMarkedIncomplete() {
    assertEquals("41 01 00 07 21 21", AutomotiveFaultResponses.monitorStatus("NOT_READY"));
    assertEquals("41 01 00 07 21 00", AutomotiveFaultResponses.monitorStatus("OFF"));
    for (String mode : new String[]{"43", "47", "4A"}) {
      assertEquals(mode + " 00 00", AutomotiveFaultResponses.codes("NOT_READY", mode));
      assertEquals(mode + " 00 00", AutomotiveFaultResponses.codes("OFF", mode));
    }
  }
  @Test public void headersAndLengthMatchOrcParserForDiagnosticsAndTelemetry() {
    assertEquals("7E806410181072100", AutomotiveFaultResponses.frame("41 01 81 07 21 00", true, false));
    assertEquals("7E8 03 43 03 00", AutomotiveFaultResponses.frame("43 03 00", true, true));
    assertEquals("7E804410C1234", AutomotiveFaultResponses.frame("41 0C 12 34", true, false));
    assertEquals("43 00 00", AutomotiveFaultResponses.frame("43 00 00", false, true));
  }
}
