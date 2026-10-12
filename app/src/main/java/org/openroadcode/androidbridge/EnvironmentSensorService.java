package org.openroadcode.androidbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import org.openroadcode.androidbridge.config.ConfigRepository;
import org.openroadcode.androidbridge.config.ServiceProvider;

/** Independently owns light and pressure; it never requests location permission. */
public final class EnvironmentSensorService extends Service implements SensorEventListener {
  private static volatile EnvironmentSensorService activeInstance;
  private volatile boolean running;
  private volatile Sample sample = Sample.empty();
  private static volatile String lastError = "";
  public static String lastError() { return lastError; }
  private long startedNs;
  private SensorManager sensors;
  private boolean simulated;
  private record Sample(float light, float pressure, boolean hasLight, boolean hasPressure,
                        long lightTime, long pressureTime, long lightCount, long pressureCount) {
    static Sample empty() { return new Sample(0, 0, false, false, 0, 0, 0, 0); }
  }

  public static boolean isRunning() {
    EnvironmentSensorService active = activeInstance;
    return active != null && active.running;
  }
  @Override public void onCreate() { super.onCreate(); activeInstance = this; }

  public static JSONObject sampleJson() {
    EnvironmentSensorService active = activeInstance;
    boolean running = active != null && active.running;
    Sample current = active == null ? Sample.empty() : active.sample;
    long now = SystemClock.elapsedRealtimeNanos();
    double uptime = active == null ? 1 : Math.max(0.001, (now - active.startedNs) / 1e9);
    if (running && active.simulated) {
      long count = Math.max(1, (now - active.startedNs) / 100000000L);
      current = new Sample(current.light, current.pressure, true, true, now, now, count, count);
    }
    JSONObject json = new JSONObject();
    try {
      json.put("ambient_light_lux", current.light).put("pressure_hpa", current.pressure)
          .put("ambient_light_available", running && current.hasLight)
          .put("pressure_available", running && current.hasPressure)
          .put("ambient_light_timestamp_ns", current.lightTime)
          .put("pressure_timestamp_ns", current.pressureTime)
          .put("ambient_light_samples", running ? current.lightCount : 0)
          .put("pressure_samples", running ? current.pressureCount : 0)
          .put("ambient_light_rate_hz", running ? current.lightCount / uptime : 0)
          .put("pressure_rate_hz", running ? current.pressureCount / uptime : 0);
    } catch (JSONException ignored) { }
    return json;
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    ConfigRepository configuration = new ConfigRepository(this);
    if (!configuration.environmentEnabled()) { stopSelf(); return START_NOT_STICKY; }
    NotificationManager notifications = getSystemService(NotificationManager.class);
    notifications.createNotificationChannel(new NotificationChannel("orc-environment",
        "OpenRoadCode environment", NotificationManager.IMPORTANCE_LOW));
    startForeground(35002, new Notification.Builder(this, "orc-environment")
        .setSmallIcon(R.drawable.ic_openroadcode_notification)
        .setContentTitle("OpenRoadCode environment")
        .setContentText("Light and pressure readings active").setOngoing(true).build());
    boolean nextSimulated = configuration.sensorConfig().provider() == ServiceProvider.SIMULATED_DRIVE;
    if (running && simulated == nextSimulated) return START_STICKY;
    if (sensors != null) sensors.unregisterListener(this);
    sample = Sample.empty();
    lastError = "";
    startedNs = SystemClock.elapsedRealtimeNanos();
    simulated = nextSimulated;
    running = false;
    if (simulated) {
      long now = SystemClock.elapsedRealtimeNanos();
      sample = new Sample(420, 1007.5f, true, true, now, now, 1, 1);
    } else {
      sensors = getSystemService(SensorManager.class);
      boolean light = sensors != null && register(Sensor.TYPE_LIGHT);
      boolean pressure = sensors != null && register(Sensor.TYPE_PRESSURE);
      if (!light && !pressure) {
        lastError = "No light or pressure sensors available on this phone.";
        configuration.setEnvironmentEnabled(false);
        running = false;
        stopSelf();
        return START_NOT_STICKY;
      }
    }
    running = true;
    startForegroundService(new Intent(this, SensorBridgeService.class));
    return START_STICKY;
  }

  private boolean register(int type) {
    Sensor sensor = sensors.getDefaultSensor(type);
    return sensor != null && sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL);
  }

  @Override public void onSensorChanged(SensorEvent event) {
    if (!running || simulated || event.values.length == 0) return;
    Sample previous = sample;
    if (event.sensor.getType() == Sensor.TYPE_LIGHT)
      sample = new Sample(event.values[0], previous.pressure, true, previous.hasPressure,
          event.timestamp, previous.pressureTime, previous.lightCount + 1, previous.pressureCount);
    else if (event.sensor.getType() == Sensor.TYPE_PRESSURE)
      sample = new Sample(previous.light, event.values[0], previous.hasLight, true,
          previous.lightTime, event.timestamp, previous.lightCount, previous.pressureCount + 1);
  }

  @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
  @Override public IBinder onBind(Intent intent) { return null; }
  @Override public void onDestroy() {
    running = false;
    if (sensors != null) sensors.unregisterListener(this);
    sample = Sample.empty();
    if (activeInstance == this) activeInstance = null;
    ConfigRepository configuration = new ConfigRepository(this);
    if (!configuration.sensorConfig().enabled() && !configuration.environmentEnabled())
      stopService(new Intent(this, SensorBridgeService.class));
    super.onDestroy();
  }
}
