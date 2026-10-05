package org.openroadcode.androidbridge;

import android.content.Context;
import android.os.Process;
import java.io.File;
import org.json.JSONObject;

/** One process-wide writer; hardware callbacks enqueue without file I/O or waiting. */
final class BridgeLog {
  private static final BridgeLogQueue QUEUE = new BridgeLogQueue(128);
  private static volatile BridgeLogStore store;
  private static boolean initialized;

  static synchronized void initialize(Context context) {
    if (initialized) return;
    initialized = true;
    File directory = new File(context.getFilesDir(), "logs");
    Thread writer = new Thread(() -> {
      BridgeLogStore target = new BridgeLogStore(directory);
      store = target;
      BridgeServiceLog diagnostic = new BridgeServiceLog(BridgeServiceLog.Service.LOGGING,
          Process.myPid(), target::memoryOnly);
      boolean failed = target.restoreFailed();
      if (failed) diagnostic.record(BridgeServiceLog.Event.STORE_FAILED);
      while (true) {
        try {
          JSONObject value = QUEUE.take();
          int dropped = QUEUE.dropped();
          if (dropped > 0) diagnostic.record(BridgeServiceLog.Event.EVENTS_DROPPED, null, dropped);
          boolean saved = target.append(value);
          if (!saved && !failed) diagnostic.record(BridgeServiceLog.Event.STORE_FAILED);
          if (saved && failed) diagnostic.record(BridgeServiceLog.Event.STORE_RECOVERED);
          failed = !saved;
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
      }
    }, "orc-bridge-log-writer");
    writer.setDaemon(true);
    writer.start();
  }
  static BridgeServiceLog service(BridgeServiceLog.Service service) {
    return new BridgeServiceLog(service, Process.myPid(), QUEUE::offer);
  }
  static JSONObject read(String cursor, String level, String component) throws Exception {
    BridgeLogStore current = store;
    if (current == null) return new JSONObject().put("events", new org.json.JSONArray())
        .put("cursor", "").put("reset", false).put("has_more", false)
        .put("scope", "Android bridge • initializing");
    return current.read(cursor, level, component);
  }
}
