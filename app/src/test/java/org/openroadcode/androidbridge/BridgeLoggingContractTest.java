package org.openroadcode.androidbridge;

import static org.junit.Assert.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Logging contract and failure tests run in the APK's existing required unit-test gate. */
public final class BridgeLoggingContractTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void everyServiceEventHasOrcSchemaAndCannotCopyPrivateExceptionDetails() throws Exception {
    List<JSONObject> events = new ArrayList<>();
    String privateMarker = "secret-token device-address coordinates audio-buffer";
    for (BridgeServiceLog.Service service : BridgeServiceLog.Service.values()) {
      for (BridgeServiceLog.Event event : BridgeServiceLog.Event.values()) {
        BridgeServiceLog log = new BridgeServiceLog(service, 123, events::add);
        log.record(event, new IOException(privateMarker), 42);
      }
    }
    for (JSONObject item : events) {
      assertTrue(item.getString("timestamp").matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
      assertTrue(item.getString("component").startsWith("bridge."));
      assertTrue(item.getString("level").matches("INFO|WARNING|ERROR"));
      assertFalse(item.getString("event").isEmpty());
      assertFalse(item.getString("message").isEmpty());
      assertEquals(123, item.getInt("pid"));
      assertFalse(item.getString("operation_id").isEmpty());
      assertEquals("IOException", item.getString("exception_type"));
      assertFalse(item.toString().contains(privateMarker));
      assertFalse(item.has("error_message"));
      assertFalse(item.has("stack_trace"));
    }
    assertEquals(BridgeServiceLog.Service.values().length * BridgeServiceLog.Event.values().length, events.size());
  }

  @Test public void operationSpansWorkerThreadAndChangesOnNewServiceStart() throws Exception {
    List<JSONObject> events = java.util.Collections.synchronizedList(new ArrayList<>());
    BridgeServiceLog log = new BridgeServiceLog(BridgeServiceLog.Service.CAMERA, 123, events::add);
    log.start();
    Thread worker = new Thread(() -> { log.ready(); log.record(BridgeServiceLog.Event.CLIENT_CONNECTED); });
    worker.start(); worker.join(2000);
    assertFalse(worker.isAlive());
    log.record(BridgeServiceLog.Event.STOPPED);
    String operation = events.get(0).getString("operation_id");
    for (JSONObject item : events) assertEquals(operation, item.getString("operation_id"));
    log.start();
    assertNotEquals(operation, events.get(events.size() - 1).getString("operation_id"));
  }

  @Test public void readinessAndAvailabilityLogOnlyChangesIncludingRecovery() throws Exception {
    List<JSONObject> events = new ArrayList<>();
    BridgeServiceLog log = new BridgeServiceLog(BridgeServiceLog.Service.SENSORS, 123, events::add);
    log.start(); log.ready(); log.ready();
    for (int i = 0; i < 1000; i++) log.available(BridgeServiceLog.Condition.LOCATION_FIX, true);
    log.available(BridgeServiceLog.Condition.GPS_PROVIDER, false);
    log.available(BridgeServiceLog.Condition.GPS_PROVIDER, false);
    log.available(BridgeServiceLog.Condition.GPS_PROVIDER, true);
    log.failure(BridgeServiceLog.Event.FAILED, new SecurityException("private"));
    log.ready(); log.ready();
    assertEquals(7, events.size());
    assertEquals("gps.provider.unavailable", events.get(3).getString("event"));
    assertEquals("WARNING", events.get(3).getString("level"));
    assertEquals("gps.provider.available", events.get(4).getString("event"));
    assertEquals("service.recovered", events.get(6).getString("event"));
  }

  @Test public void repeatedErrorsAreSuppressedAndSummaryIsAttachedAfterInterval() throws Exception {
    AtomicLong clock = new AtomicLong(10000);
    List<JSONObject> events = new ArrayList<>();
    BridgeServiceLog log = new BridgeServiceLog(BridgeServiceLog.Service.PLAYBACK, 123, events::add, clock::get);
    for (int i = 0; i < 50; i++) log.failure(BridgeServiceLog.Event.SERVER_FAILED, new IOException("private"));
    assertEquals(1, events.size());
    clock.addAndGet(5000);
    log.failure(BridgeServiceLog.Event.SERVER_FAILED, new IOException("private"));
    assertEquals(49, events.get(1).getInt("suppressed_count"));
  }

  @Test public void loggingSinkFailureCannotBreakHardwareCallbacks() {
    BridgeServiceLog log = new BridgeServiceLog(BridgeServiceLog.Service.BLUETOOTH, 123,
        event -> { throw new IllegalStateException("unavailable"); });
    log.start(); log.ready(); log.failure(BridgeServiceLog.Event.FAILED, new IOException());
    log.available(BridgeServiceLog.Condition.DEVICE_CONNECTION, false);
  }

  @Test public void lateWorkerReadinessCannotReportSuccessAfterServiceStop() throws Exception {
    List<JSONObject> events = new ArrayList<>();
    BridgeServiceLog log = new BridgeServiceLog(BridgeServiceLog.Service.CAMERA, 123, events::add);
    log.start();
    log.record(BridgeServiceLog.Event.STOPPED);
    log.ready(); log.record(BridgeServiceLog.Event.SERVER_READY);
    log.record(BridgeServiceLog.Event.CLIENT_CONNECTED);
    assertEquals(2, events.size());
    log.start(); log.ready();
    assertEquals("service.started", events.get(3).getString("event"));
  }

  @Test public void queueDoesNotBlockAndReportsDropsWithoutGrowing() throws Exception {
    BridgeLogQueue queue = new BridgeLogQueue(2);
    JSONObject first = new JSONObject(), second = new JSONObject();
    queue.offer(first); queue.offer(second);
    for (int i = 0; i < 10000; i++) queue.offer(new JSONObject());
    assertEquals(10000, queue.dropped());
    assertEquals(0, queue.dropped());
    assertSame(first, queue.take()); assertSame(second, queue.take());
  }

  private JSONObject event(int number) throws Exception {
    List<JSONObject> events = new ArrayList<>();
    new BridgeServiceLog(BridgeServiceLog.Service.CAMERA, 123, events::add)
        .record(BridgeServiceLog.Event.CLIENT_CONNECTED, null, number);
    return events.get(0);
  }

  @Test public void rotationRestoreAndLocalViewerUseSameSchemaWithoutRepeatingPages() throws Exception {
    File directory = temporary.newFolder();
    BridgeLogStore store = new BridgeLogStore(directory, 1024);
    for (int i = 0; i < 20; i++) assertTrue(store.append(event(i)));
    for (File file : directory.listFiles()) assertTrue(file.length() <= 1024);
    assertTrue(directory.listFiles().length <= 3);
    BridgeLogStore restored = new BridgeLogStore(directory, 1024);
    JSONObject page = restored.read("", "INFO", "bridge.camera");
    assertTrue(page.getJSONArray("events").length() > 0);
    RuntimeLogBuffer viewer = new RuntimeLogBuffer();
    viewer.append(page);
    assertFalse(viewer.rows().isEmpty());
    assertTrue(viewer.scope().contains("Android bridge"));
    assertEquals(0, restored.read(page.getString("cursor"), "INFO", "bridge").getJSONArray("events").length());
    restored.append(event(21));
    assertEquals(1, restored.read(page.getString("cursor"), "INFO", "bridge").getJSONArray("events").length());
  }

  @Test public void localCursorResetsAfterRestartOrHistoryEviction() throws Exception {
    File directory = temporary.newFolder();
    BridgeLogStore store = new BridgeLogStore(directory);
    store.append(event(0));
    String cursor = store.read("", "INFO", "").getString("cursor");
    for (int i = 1; i < 250; i++) store.append(event(i));
    JSONObject page = store.read(cursor, "INFO", "");
    assertTrue(page.getBoolean("reset"));
    assertEquals(200, page.getJSONArray("events").length());
    BridgeLogStore restarted = new BridgeLogStore(directory);
    assertTrue(restarted.read(page.getString("cursor"), "INFO", "").getBoolean("reset"));
  }

  @Test public void diskFailureRetainsMemoryAndSubsequentWritesRecover() throws Exception {
    File directory = new File(temporary.newFolder(), "logs");
    Files.write(directory.toPath(), new byte[] {1});
    BridgeLogStore store = new BridgeLogStore(directory);
    assertFalse(store.append(event(1)));
    assertEquals(1, store.read("", "INFO", "").getJSONArray("events").length());
    Files.delete(directory.toPath());
    assertTrue(store.append(event(2)));
    assertEquals(2, store.read("", "INFO", "").getJSONArray("events").length());
  }

  @Test public void malformedOversizedAndPartialPersistedRecordsAreIgnored() throws Exception {
    File directory = temporary.newFolder();
    File file = new File(directory, "bridge.jsonl");
    String valid = event(1).toString();
    Files.write(file.toPath(), ("not-json\n{}\n" + "x".repeat(20000) + "\n" + valid + "\n{partial")
        .getBytes(StandardCharsets.UTF_8));
    BridgeLogStore store = new BridgeLogStore(directory);
    assertEquals(1, store.read("", "INFO", "").getJSONArray("events").length());
    store.append(event(2));
    BridgeLogStore restarted = new BridgeLogStore(directory);
    assertEquals(2, restarted.read("", "INFO", "").getJSONArray("events").length());
    store.append(event(3).put("message", "x".repeat(20000)));
    assertEquals(2, store.read("", "INFO", "").getJSONArray("events").length());
  }

  @Test public void filterIsDottedPrefixAndSeverityAndMalformedCursorIsRejected() throws Exception {
    BridgeLogStore store = new BridgeLogStore(temporary.newFolder());
    store.append(event(1));
    assertEquals(1, store.read("", "INFO", "bridge").getJSONArray("events").length());
    assertEquals(0, store.read("", "ERROR", "bridge").getJSONArray("events").length());
    assertEquals(0, store.read("", "INFO", "bridge.cam").getJSONArray("events").length());
    try { store.read("../../private", "INFO", ""); fail("Invalid cursor accepted"); }
    catch (IllegalArgumentException expected) { }
  }
}
