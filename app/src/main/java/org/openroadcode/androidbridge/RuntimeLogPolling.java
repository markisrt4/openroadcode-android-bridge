package org.openroadcode.androidbridge;

/** Rejects stale network completions after pause, target/filter changes, or backgrounding. */
final class RuntimeLogPolling {
  private boolean active;
  private boolean inFlight;
  private long generation;

  void start() {
    stop();
    active = true;
  }

  void stop() {
    active = false;
    inFlight = false;
    generation++;
  }

  long begin() {
    if (!active || inFlight) return -1;
    inFlight = true;
    return generation;
  }

  boolean complete(long requestGeneration) {
    if (!active || !inFlight || requestGeneration != generation) return false;
    inFlight = false;
    return true;
  }
}
