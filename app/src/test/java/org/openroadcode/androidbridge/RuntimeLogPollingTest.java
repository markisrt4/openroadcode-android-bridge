package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import org.junit.Test;

public final class RuntimeLogPollingTest {
  @Test public void neverStartsRequestsWhileInactiveOrWithOneInFlight() {
    RuntimeLogPolling polling = new RuntimeLogPolling();
    assertEquals(-1, polling.begin());
    polling.start();
    long first = polling.begin();
    assertTrue(first >= 0);
    assertEquals(-1, polling.begin());
    assertTrue(polling.complete(first));
    assertTrue(polling.begin() >= 0);
  }

  @Test public void pauseOrBackgroundStopRejectsCompletionAndFurtherRequests() {
    RuntimeLogPolling polling = new RuntimeLogPolling();
    polling.start();
    long request = polling.begin();
    polling.stop();
    assertFalse(polling.complete(request));
    assertEquals(-1, polling.begin());
  }

  @Test public void staleCompletionCannotReleaseNewTargetOrResumedRequest() {
    RuntimeLogPolling polling = new RuntimeLogPolling();
    polling.start();
    long oldRequest = polling.begin();
    polling.stop();
    polling.start();
    long newRequest = polling.begin();
    assertFalse(polling.complete(oldRequest));
    assertEquals(-1, polling.begin());
    assertTrue(polling.complete(newRequest));
    assertFalse(polling.complete(newRequest));
  }
}
