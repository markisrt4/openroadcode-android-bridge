package org.openroadcode.androidbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class AutomotiveFaultScenarioTest {
  @Test public void everySelectionRestoresItsDescriptionAndKeepsCodesOutOfTitles() {
    for (AutomotiveFaultScenario scenario : AutomotiveFaultScenario.values()) {
      assertSame(scenario, AutomotiveFaultScenario.fromId(scenario.id));
      assertFalse(scenario.title.matches(".*P[0-9]{4}.*"));
      assertFalse(scenario.expected.matches(".*P[0-9]{4}.*"));
      assertFalse(scenario.problem.isBlank());
      assertFalse(scenario.subtitle.isBlank());
      assertFalse(scenario.expected.isBlank());
      if (scenario.code != null) assertTrue(scenario.technicalDetails().contains(scenario.code));
    }
    assertSame(AutomotiveFaultScenario.OFF, AutomotiveFaultScenario.fromId("unknown"));
    assertSame(AutomotiveFaultScenario.OFF, AutomotiveFaultScenario.fromId(null));
  }

  @Test public void expectedResultsMatchActualSimulatorResponsesForEveryChoice() {
    for (AutomotiveFaultScenario scenario : AutomotiveFaultScenario.values()) {
      String[] monitor = AutomotiveFaultResponses.monitorStatus(scenario.id).split(" ");
      int a = Integer.parseInt(monitor[2], 16);
      int c = Integer.parseInt(monitor[4], 16);
      int d = Integer.parseInt(monitor[5], 16);
      assertEquals(scenario.mil, (a & 0x80) != 0);
      assertEquals(scenario.ready, (c & d) == 0);
      assertEquals(scenario.status.equals("stored") ? 1 : 0, a & 0x7F);
      String[] statuses = {"stored", "pending", "permanent"};
      String[] modes = {"43", "47", "4A"};
      for (int i = 0; i < modes.length; i++) {
        String response = AutomotiveFaultResponses.codes(scenario.id, modes[i]);
        if (!scenario.status.equals(statuses[i])) {
          assertEquals(modes[i] + " 00 00", response);
        } else {
          assertNotNull(scenario.code);
          String word = response.substring(3).replace(" ", "");
          assertEquals(scenario.code.substring(1), word);
        }
      }
    }
  }
}
