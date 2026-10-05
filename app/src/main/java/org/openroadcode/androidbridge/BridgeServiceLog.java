package org.openroadcode.androidbridge;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.json.JSONObject;

/** Fixed messages and fields: device identifiers, payloads, and exception text cannot enter this API. */
final class BridgeServiceLog {
  enum Service {
    SENSORS("sensors"), BLUETOOTH("bluetooth"), CAMERA("camera"), PLAYBACK("playback"), LOGGING("logging");
    final String component;
    Service(String value) { component = "bridge." + value; }
  }
  enum Event {
    START_REQUESTED("service.start_requested", "INFO", "Bridge service start requested"),
    STARTED("service.started", "INFO", "Bridge service ready"),
    RECOVERED("service.recovered", "INFO", "Bridge service recovered"),
    STOPPED("service.stopped", "INFO", "Bridge service stopped"),
    FAILED("service.failed", "ERROR", "Bridge service failed"),
    DEVICE_DISCONNECTED("device.disconnected", "ERROR", "Bridge device disconnected unexpectedly"),
    CAMERA_UNAVAILABLE("camera.unavailable", "ERROR", "No usable camera found"),
    CAMERA_CONFIG_FAILED("camera.configure_failed", "ERROR", "Camera configuration failed"),
    CAPTURE_FAILED("capture.failed", "ERROR", "Bridge capture failed"),
    ENCODER_FAILED("video.encoder_failed", "ERROR", "Video encoder failed"),
    SERVER_FAILED("server.failed", "ERROR", "Bridge listener failed"),
    SERVER_READY("server.ready", "INFO", "Bridge listener ready"),
    CLIENT_CONNECTED("client.connected", "INFO", "Stream client connected"),
    CLIENT_DISCONNECTED("client.disconnected", "INFO", "Stream client disconnected"),
    CLIENT_FAILED("client.failed", "WARNING", "Stream client I/O failed"),
    BIND_RETRY("server.bind_retry", "WARNING", "Retrying bridge listener binding"),
    CONNECT_FALLBACK("device.connect_fallback", "WARNING", "Trying alternate Bluetooth connection"),
    SYNC_FRAME_FAILED("video.sync_frame_failed", "WARNING", "Video sync frame request failed"),
    CONSENT_DENIED("capture.consent_denied", "WARNING", "Playback capture consent not granted"),
    CONSENT_ENDED("capture.consent_ended", "INFO", "Playback capture consent ended"),
    UNSUPPORTED("service.unsupported", "WARNING", "Bridge service unavailable on this Android version"),
    SENSOR_UNAVAILABLE("sensor.unavailable", "WARNING", "Requested sensor unavailable"),
    SIMULATION_SELECTED("sensor.simulation_selected", "INFO", "Simulated sensor source selected"),
    EVENTS_DROPPED("logging.events_dropped", "WARNING", "Logging queue dropped events"),
    STORE_FAILED("logging.store_failed", "WARNING", "Log persistence unavailable; recent events remain in memory"),
    STORE_RECOVERED("logging.store_recovered", "INFO", "Log persistence recovered");
    final String name, level, message;
    Event(String name, String level, String message) {
      this.name = name; this.level = level; this.message = message;
    }
  }
  enum Condition {
    LOCATION_PERMISSION("location.permission", "Location permission"),
    LOCATION_PROVIDER("location.provider", "Location provider"),
    GPS_PROVIDER("gps.provider", "GPS provider"),
    NETWORK_PROVIDER("network.provider", "Network location provider"),
    LOCATION_FIX("location.fix", "Location fix"),
    BLUETOOTH_PERMISSION("bluetooth.permission", "Bluetooth permission"),
    BLUETOOTH_ADAPTER("bluetooth.adapter", "Bluetooth adapter"),
    DEVICE_CONNECTION("device.connection", "Bridge device connection"),
    DEVICE_SELECTION("device.selection", "Paired bridge device selection"),
    CAMERA_PERMISSION("camera.permission", "Camera permission"),
    AUDIO_PERMISSION("audio.permission", "Playback audio permission");
    final String name, message;
    Condition(String name, String message) { this.name = name; this.message = message; }
  }
  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
      .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
  private final Service service;
  private final int pid;
  private final Consumer<JSONObject> output;
  private final LongSupplier clock;
  private final EnumMap<Condition, Boolean> states = new EnumMap<>(Condition.class);
  private final EnumMap<Event, Long> lastErrors = new EnumMap<>(Event.class);
  private final EnumMap<Event, Integer> suppressed = new EnumMap<>(Event.class);
  private String operation = UUID.randomUUID().toString();
  private boolean started;
  private boolean failed;
  private boolean stopped;

  BridgeServiceLog(Service service, int pid, Consumer<JSONObject> output) {
    this(service, pid, output, System::currentTimeMillis);
  }
  BridgeServiceLog(Service service, int pid, Consumer<JSONObject> output, LongSupplier clock) {
    this.service = service; this.pid = pid; this.output = output; this.clock = clock;
  }
  synchronized void start() {
    operation = UUID.randomUUID().toString();
    started = false; failed = false; stopped = false;
    states.clear(); lastErrors.clear(); suppressed.clear();
    record(Event.START_REQUESTED);
  }
  synchronized void ready() {
    if (stopped) return;
    if (!started) { started = true; record(Event.STARTED); }
    else if (failed) record(Event.RECOVERED);
    failed = false;
  }
  void record(Event event) { record(event, null, null); }
  void failure(Event event, Throwable error) { record(event, error, null); }
  synchronized void record(Event event, Throwable error, Integer code) {
    try {
      if (stopped && (event == Event.STARTED || event == Event.RECOVERED
          || event == Event.SERVER_READY || event == Event.CLIENT_CONNECTED)) return;
      if (event == Event.STOPPED) stopped = true;
      if (event.level.equals("ERROR")) failed = true;
      long now = clock.getAsLong();
      if (event != Event.SENSOR_UNAVAILABLE && (event.level.equals("WARNING") || event.level.equals("ERROR"))) {
        Long previous = lastErrors.get(event);
        if (previous != null && now >= previous && now - previous < 5000) {
          suppressed.put(event, suppressed.getOrDefault(event, 0) + 1);
          return;
        }
        lastErrors.put(event, now);
      }
      JSONObject value = base(event.name, event.level, event.message, now);
      if (error != null) value.put("exception_type", error.getClass().getSimpleName());
      if (code != null) value.put(event == Event.EVENTS_DROPPED ? "dropped_count"
          : event == Event.SENSOR_UNAVAILABLE ? "sensor_type"
          : event == Event.BIND_RETRY ? "attempt" : "error_code", code);
      Integer count = suppressed.remove(event);
      if (count != null) value.put("suppressed_count", count);
      output.accept(value);
    } catch (Exception ignored) { /* Diagnostics cannot interrupt service behavior. */ }
  }
  synchronized void available(Condition condition, boolean available) {
    if (Boolean.valueOf(available).equals(states.put(condition, available))) return;
    try {
      output.accept(base(condition.name + (available ? ".available" : ".unavailable"),
          available ? "INFO" : "WARNING", condition.message + (available ? " available" : " unavailable"),
          clock.getAsLong()));
    } catch (Exception ignored) { }
  }
  private JSONObject base(String event, String level, String message, long now) throws Exception {
    return new JSONObject().put("timestamp", TIMESTAMP.format(Instant.ofEpochMilli(now)))
        .put("level", level).put("component", service.component).put("event", event)
        .put("message", message).put("pid", pid).put("operation_id", operation);
  }
}
