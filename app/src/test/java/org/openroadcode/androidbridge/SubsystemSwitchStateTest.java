package org.openroadcode.androidbridge;

import org.junit.Test;
import static org.junit.Assert.*;

public class SubsystemSwitchStateTest {
  @Test public void startIsPendingUntilObservedActivityConfirmsIt() {
    SubsystemSwitchState state = new SubsystemSwitchState();
    state.request(true, 100);
    assertTrue(state.observe(false, "Off", 101).checked());
    assertEquals("Starting…", state.observe(false, "Off", 102).status());
    assertEquals("Sharing location", state.observe(true, "Sharing location", 103).status());
  }

  @Test public void stopIsPendingUntilTheServiceHasStopped() {
    SubsystemSwitchState state = new SubsystemSwitchState();
    state.request(false, 100);
    assertFalse(state.observe(true, "On", 101).checked());
    assertEquals("Stopping…", state.observe(true, "On", 102).status());
    assertEquals("Off", state.observe(false, "Off", 103).status());
  }

  @Test public void missingAcknowledgementReturnsToActualState() {
    SubsystemSwitchState state = new SubsystemSwitchState();
    state.request(true, 0);
    var result = state.observe(false, "Off", 15000);
    assertFalse(result.checked());
    assertTrue(result.status().contains("Not ready"));
  }

  @Test public void cancellingPendingStartDoesNotLeaveTheSwitchOn() {
    SubsystemSwitchState state = new SubsystemSwitchState();
    state.request(true, 100);
    assertTrue(state.observe(false, "Off", 101).checked());
    state.request(false, 102);
    assertFalse(state.observe(false, "Off", 103).checked());
    assertEquals("Off", state.observe(false, "Off", 104).status());
  }

  @Test public void permissionWaitCanOutlastNormalStartupTimeout() {
    SubsystemSwitchState state = new SubsystemSwitchState();
    state.request(true, 0);
    state.waitingForPermission();
    assertEquals("Waiting for permission…", state.observe(false, "Off", 60000).status());
    state.resume(60000);
    assertEquals("Starting…", state.observe(false, "Off", 60001).status());
    assertEquals("On", state.observe(true, "On", 60002).status());
  }

  @Test public void permissionDenialShowsAnOffSwitchAndExplanation() {
    SubsystemSwitchState state = new SubsystemSwitchState();
    state.request(true, 0);
    state.waitingForPermission();
    state.fail("Location permission was denied.");
    assertFalse(state.observe(false, "Off", 100).checked());
    assertEquals("Location permission was denied.", state.observe(false, "Off", 101).status());
  }

  @Test public void externalActivityChangesClearOldFailureFeedback() {
    SubsystemSwitchState state = new SubsystemSwitchState();
    state.observe(false, "Off", 0);
    state.fail("Start failed");
    assertEquals("Start failed", state.observe(false, "Off", 1).status());
    assertEquals("On", state.observe(true, "On", 2).status());
    assertFalse(state.observe(false, "Off", 3).checked());
  }
}
