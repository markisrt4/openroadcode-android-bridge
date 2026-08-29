package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public final class SensorBridgeService extends Service implements SensorEventListener, LocationListener {
    private static final int PORT = 8766;
    private static final String CHANNEL_ID = "sensor_bridge";
    private static final int NOTIFICATION_ID = 1;
    private static final long STREAM_PERIOD_MS = 20;
    private static final long LOCATION_PERIOD_MS = 500;

    private SensorManager sensorManager;
    private LocationManager locationManager;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private long startedElapsedRealtimeMs;

    private final AtomicReference<Sample> sample = new AtomicReference<>(new Sample());
    private final AtomicReference<Location> location = new AtomicReference<>();
    private volatile long locationCount;
    private volatile boolean locationProviderAvailable;

    @Override
    public void onCreate() {
        super.onCreate();
        startedElapsedRealtimeMs = SystemClock.elapsedRealtime();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        registerSensor(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_GAME);
        registerSensor(Sensor.TYPE_LINEAR_ACCELERATION, SensorManager.SENSOR_DELAY_GAME);
        registerSensor(Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME);
        registerSensor(Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_GAME);
        registerSensor(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL);
        registerSensor(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL);
        startLocationUpdates();
        serverThread = new Thread(this::runServer, "orc-sensor-http");
        serverThread.start();
    }

    private void registerSensor(int sensorType, int delay) {
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        if (sensor != null) sensorManager.registerListener(this, sensor, delay);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null || !hasLocationPermission()) return;
        try {
            locationProviderAvailable = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            if (locationProviderAvailable) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, LOCATION_PERIOD_MS, 0.0f, this);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationProviderAvailable = true;
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_PERIOD_MS, 0.0f, this);
            }
        } catch (SecurityException ignored) {
            locationProviderAvailable = false;
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (locationManager == null || (hasLocationPermission() && location.get() == null)) startLocationUpdates();
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        }
        if (serverSocket != null) try { serverSocket.close(); } catch (IOException ignored) { }
        super.onDestroy();
    }

    @Override
    public void onLocationChanged(Location value) {
        location.set(new Location(value));
        locationCount++;
        locationProviderAvailable = true;
    }

    @Override public void onProviderEnabled(String provider) { locationProviderAvailable = true; }
    @Override public void onProviderDisabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Sample next = sample.get().copy();
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                next.ax = event.values[0]; next.ay = event.values[1]; next.az = event.values[2];
                next.accelTimestampNs = event.timestamp; next.hasAccel = true; next.accelCount++;
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                next.lax = event.values[0]; next.lay = event.values[1]; next.laz = event.values[2];
                next.linearAccelTimestampNs = event.timestamp; next.hasLinearAccel = true; next.linearAccelCount++;
                break;
            case Sensor.TYPE_GYROSCOPE:
                next.gx = event.values[0]; next.gy = event.values[1]; next.gz = event.values[2];
                next.gyroTimestampNs = event.timestamp; next.hasGyro = true; next.gyroCount++;
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                next.mx = event.values[0]; next.my = event.values[1]; next.mz = event.values[2];
                next.magTimestampNs = event.timestamp; next.hasMag = true; next.magCount++;
                break;
            case Sensor.TYPE_PRESSURE:
                next.pressureHpa = event.values[0]; next.pressureTimestampNs = event.timestamp;
                next.hasPressure = true; next.pressureCount++;
                break;
            case Sensor.TYPE_LIGHT:
                next.ambientLightLux = event.values[0]; next.lightTimestampNs = event.timestamp;
                next.hasLight = true; next.lightCount++;
                break;
            default: return;
        }
        sample.set(next);
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try (Socket owned = socket) { handleRequest(owned); }
                        catch (IOException ignored) { }
                    }, "orc-sensor-http-client").start();
                } catch (IOException e) { if (!serverSocket.isClosed()) e.printStackTrace(); }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void handleRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String requestLine = reader.readLine();
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        String path = parts.length >= 2 && "GET".equals(parts[0]) ? parts[1] : "";
        if ("/stream/imu".equals(path)) { streamImu(socket); return; }
        boolean valid = "/imu".equals(path) || "/location".equals(path) || "/health".equals(path);
        String json = "/imu".equals(path) ? sampleJson()
                : "/location".equals(path) ? locationJson()
                : "/health".equals(path) ? healthJson() : "{\"error\":\"not found\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String status = valid ? "200 OK" : "404 Not Found";
        String headers = String.format(Locale.US, "HTTP/1.1 %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", status, body.length);
        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII)); output.write(body); output.flush();
    }

    private void streamImu(Socket socket) throws IOException {
        OutputStream output = socket.getOutputStream();
        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        output.flush();
        while (!socket.isClosed()) {
            output.write((sampleJson() + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
            try { Thread.sleep(STREAM_PERIOD_MS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static JSONObject vector(float x, float y, float z) throws JSONException {
        JSONObject result = new JSONObject(); result.put("x", x); result.put("y", y); result.put("z", z); return result;
    }

    private String sampleJson() {
        Sample current = sample.get();
        try {
            JSONObject root = new JSONObject();
            root.put("ready", current.hasAccel && current.hasGyro);
            root.put("acceleration_mps2", vector(current.ax, current.ay, current.az));
            root.put("linear_acceleration_mps2", vector(current.lax, current.lay, current.laz));
            root.put("angular_velocity_rad_s", vector(current.gx, current.gy, current.gz));
            root.put("magnetic_field_uT", vector(current.mx, current.my, current.mz));
            root.put("pressure_hpa", current.pressureHpa);
            root.put("ambient_light_lux", current.ambientLightLux);
            root.put("accelerometer_timestamp_ns", current.accelTimestampNs);
            root.put("linear_acceleration_timestamp_ns", current.linearAccelTimestampNs);
            root.put("gyroscope_timestamp_ns", current.gyroTimestampNs);
            root.put("magnetometer_timestamp_ns", current.magTimestampNs);
            root.put("pressure_timestamp_ns", current.pressureTimestampNs);
            root.put("ambient_light_timestamp_ns", current.lightTimestampNs);
            root.put("linear_acceleration_available", current.hasLinearAccel);
            root.put("magnetometer_available", current.hasMag);
            root.put("pressure_available", current.hasPressure);
            root.put("ambient_light_available", current.hasLight);
            return root.toString();
        } catch (JSONException e) { return "{\"error\":\"failed to encode sensor sample\"}"; }
    }

    private String locationJson() {
        Location current = location.get();
        try {
            JSONObject root = new JSONObject();
            root.put("permission_granted", hasLocationPermission());
            root.put("available", locationProviderAvailable);
            root.put("ready", current != null);
            if (current == null) return root.toString();
            root.put("provider", current.getProvider());
            root.put("latitude", current.getLatitude());
            root.put("longitude", current.getLongitude());
            root.put("altitude_m", current.hasAltitude() ? current.getAltitude() : JSONObject.NULL);
            root.put("speed_mps", current.hasSpeed() ? current.getSpeed() : JSONObject.NULL);
            root.put("bearing_deg", current.hasBearing() ? current.getBearing() : JSONObject.NULL);
            root.put("horizontal_accuracy_m", current.hasAccuracy() ? current.getAccuracy() : JSONObject.NULL);
            root.put("timestamp_ms", current.getTime());
            root.put("elapsed_realtime_ns", current.getElapsedRealtimeNanos());
            long ageNs = Math.max(0L, SystemClock.elapsedRealtimeNanos() - current.getElapsedRealtimeNanos());
            root.put("age_ms", ageNs / 1_000_000L);
            return root.toString();
        } catch (JSONException e) { return "{\"error\":\"failed to encode location\"}"; }
    }

    private String healthJson() {
        Sample current = sample.get();
        Location currentLocation = location.get();
        long uptimeMs = SystemClock.elapsedRealtime() - startedElapsedRealtimeMs;
        double uptimeSeconds = Math.max(0.001, uptimeMs / 1000.0);
        try {
            JSONObject root = new JSONObject();
            root.put("status", current.hasAccel && current.hasGyro ? "ready" : "starting");
            root.put("version_name", BuildConfig.VERSION_NAME); root.put("version_code", BuildConfig.VERSION_CODE);
            root.put("application_id", BuildConfig.APPLICATION_ID); root.put("build_type", BuildConfig.BUILD_TYPE);
            root.put("uptime_ms", uptimeMs);
            root.put("accelerometer_samples", current.accelCount); root.put("linear_acceleration_samples", current.linearAccelCount);
            root.put("gyroscope_samples", current.gyroCount); root.put("magnetometer_samples", current.magCount); root.put("pressure_samples", current.pressureCount); root.put("ambient_light_samples", current.lightCount);
            root.put("accelerometer_rate_hz", current.accelCount / uptimeSeconds); root.put("linear_acceleration_rate_hz", current.linearAccelCount / uptimeSeconds);
            root.put("gyroscope_rate_hz", current.gyroCount / uptimeSeconds); root.put("magnetometer_rate_hz", current.magCount / uptimeSeconds); root.put("pressure_rate_hz", current.pressureCount / uptimeSeconds); root.put("ambient_light_rate_hz", current.lightCount / uptimeSeconds);
            root.put("location_permission_granted", hasLocationPermission()); root.put("location_provider_available", locationProviderAvailable);
            root.put("location_ready", currentLocation != null); root.put("location_samples", locationCount);
            return root.toString();
        } catch (JSONException e) { return "{\"error\":\"failed to encode health status\"}"; }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "OpenRoadCode Sensor Bridge", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID).setContentTitle("OpenRoadCode Sensor Bridge")
                .setContentText("Device sensors and position are being bridged locally")
                .setSmallIcon(R.drawable.ic_openroadcode_notification).build();
    }

    private static final class Sample {
        float ax, ay, az, lax, lay, laz, gx, gy, gz, mx, my, mz, pressureHpa, ambientLightLux;
        long accelTimestampNs, linearAccelTimestampNs, gyroTimestampNs, magTimestampNs, pressureTimestampNs, lightTimestampNs;
        long accelCount, linearAccelCount, gyroCount, magCount, pressureCount, lightCount;
        boolean hasAccel, hasLinearAccel, hasGyro, hasMag, hasPressure, hasLight;
        Sample copy() {
            Sample r = new Sample();
            r.ax=ax; r.ay=ay; r.az=az; r.lax=lax; r.lay=lay; r.laz=laz; r.gx=gx; r.gy=gy; r.gz=gz; r.mx=mx; r.my=my; r.mz=mz; r.pressureHpa=pressureHpa; r.ambientLightLux=ambientLightLux;
            r.accelTimestampNs=accelTimestampNs; r.linearAccelTimestampNs=linearAccelTimestampNs; r.gyroTimestampNs=gyroTimestampNs; r.magTimestampNs=magTimestampNs; r.pressureTimestampNs=pressureTimestampNs; r.lightTimestampNs=lightTimestampNs;
            r.accelCount=accelCount; r.linearAccelCount=linearAccelCount; r.gyroCount=gyroCount; r.magCount=magCount; r.pressureCount=pressureCount; r.lightCount=lightCount;
            r.hasAccel=hasAccel; r.hasLinearAccel=hasLinearAccel; r.hasGyro=hasGyro; r.hasMag=hasMag; r.hasPressure=hasPressure; r.hasLight=hasLight; return r;
        }
    }
}
