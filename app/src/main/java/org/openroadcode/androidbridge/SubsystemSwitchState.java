package org.openroadcode.androidbridge;

/** Tracks a user request until observed activity confirms it, or it times out. */
final class SubsystemSwitchState {
  private Boolean requested;
  private long deadline;
  private String failure;
  private Boolean lastObserved;

  void request(boolean on, long now) {
    requested = on;
    deadline = now + 15000;
    failure = null;
  }

  void fail(String message) { requested = null; failure = message; }

  record Display(boolean checked, String status) {}

  Display observe(boolean running, String status, long now) {
    if (lastObserved != null && lastObserved != running) failure = null;
    lastObserved = running;
    if (requested != null) {
      if (running == requested) requested = null;
      else if (now >= deadline) fail("Not ready. Check permissions or open this feature for help.");
      else return new Display(requested, deadline == Long.MAX_VALUE ? "Waiting for permission…" : requested ? "Starting…" : "Stopping…");
    }
    return new Display(running, failure != null ? failure : status);
  }

  void waitingForPermission() { deadline = Long.MAX_VALUE; }
  void resume(long now) { if (requested != null) deadline = now + 15000; }
}
