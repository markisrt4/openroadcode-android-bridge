package org.openroadcode.androidbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class SensorActivityPlanTest {
  @Test public void environmentAloneServesDataWithoutLocationPermissionOrMotion() {
    var plan = SensorActivityPlan.of(false, true, true, false);
    assertTrue(plan.serve());
    assertFalse(plan.navigation());
    assertFalse(plan.location());
  }
  @Test public void navigationWithoutPermissionDoesNotActivateHardware() {
    var plan = SensorActivityPlan.of(true, false, true, false);
    assertFalse(plan.serve());
    assertFalse(plan.navigation());
    assertFalse(plan.location());
  }
  @Test public void environmentSurvivesDeniedNavigationPermission() {
    var plan = SensorActivityPlan.of(true, true, true, false);
    assertTrue(plan.serve());
    assertFalse(plan.navigation());
    assertFalse(plan.location());
  }
  @Test public void switchingOffEnvironmentLeavesAuthorizedNavigationRunning() {
    var plan = SensorActivityPlan.of(true, false, true, true);
    assertTrue(plan.serve());
    assertTrue(plan.navigation());
    assertTrue(plan.location());
  }
  @Test public void demoNavigationDoesNotRequireOrStartPhoneLocation() {
    var plan = SensorActivityPlan.of(true, false, false, false);
    assertTrue(plan.serve());
    assertTrue(plan.navigation());
    assertFalse(plan.location());
  }
  @Test public void disablingBothFeaturesStopsTheDataEndpoint() {
    var plan = SensorActivityPlan.of(false, false, true, true);
    assertFalse(plan.serve());
    assertFalse(plan.navigation());
  }
}
