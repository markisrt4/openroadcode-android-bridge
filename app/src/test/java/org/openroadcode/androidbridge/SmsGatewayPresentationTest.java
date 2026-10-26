package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SmsGatewayPresentationTest {
  @Test public void enabledPreferenceDoesNotImplyListenerIsRunning() {
    String stopped = SmsGatewayPresentation.simpleStatus(true, true, true, false);
    assertTrue(stopped.contains("listener is not running"));
    assertTrue(stopped.contains("Tap Enable messages"));
    assertEquals("Messages are running", SmsGatewayPresentation.simpleStatus(true, true, true, true));
    assertTrue(SmsGatewayPresentation.simpleStatus(true, true, false, true).contains("Read only"));
  }

  @Test public void offAndMissingPermissionHaveClearActions() {
    assertTrue(SmsGatewayPresentation.simpleStatus(false, true, false, false).contains("turned off"));
    assertTrue(SmsGatewayPresentation.simpleStatus(false, false, false, false).contains("Android permission"));
  }

  @Test public void permissionFeedbackNamesTheVisibleButtonInEachMode() {
    assertTrue(SmsGatewayPresentation.readPermissionFeedback(false, true).contains("Tap Enable messages"));
    assertFalse(SmsGatewayPresentation.readPermissionFeedback(false, true).contains("gateway"));
    assertTrue(SmsGatewayPresentation.readPermissionFeedback(true, true).contains("Tap Enable SMS gateway"));
    assertTrue(SmsGatewayPresentation.readPermissionFeedback(false, false).contains("did not grant"));
  }
}
