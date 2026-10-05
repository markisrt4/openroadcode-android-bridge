package org.openroadcode.androidbridge;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Nonblocking bounded handoff between hardware callbacks and the disk writer. */
final class BridgeLogQueue {
  private final ArrayBlockingQueue<JSONObject> events;
  private final AtomicInteger dropped = new AtomicInteger();
  BridgeLogQueue(int capacity) { events = new ArrayBlockingQueue<>(capacity); }
  void offer(JSONObject event) {
    if (!events.offer(event)) dropped.updateAndGet(count -> count == Integer.MAX_VALUE ? count : count + 1);
  }
  JSONObject take() throws InterruptedException { return events.take(); }
  int dropped() { return dropped.getAndSet(0); }
}
