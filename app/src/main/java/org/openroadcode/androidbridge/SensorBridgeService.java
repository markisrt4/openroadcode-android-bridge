package org.openroadcode.androidbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;

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

public final class SensorBridgeService extends Service implements SensorEventListener {
    private static final int PORT = 8766;
    private static final String CHANNEL_ID = "sensor_bridge";
    private static final int NOTIFICATION_ID = 1;

    private SensorManager sensorManager;
    private ServerSocket serverSocket;
    private Thread serverThread;

    private final AtomicReference<Sample> sample = new AtomicReference<>(new Sample());

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        }
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        }

        serverThread = new Thread(this::runServer, "orc-sensor-http");
        serverThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Sample previous = sample.get();
        Sample next = previous.copy();

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            next.ax = event.values[0];
            next.ay = event.values[1];
            next.az = event.values[2];
            next.accelTimestampNs = event.timestamp;
            next.hasAccel = true;
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            next.gx = event.values[0];
            next.gy = event.values[1];
            next.gz = event.values[2];
            next.gyroTimestampNs = event.timestamp;
            next.hasGyro = true;
        }

        sample.set(next);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void runServer() {
        try {
            serverSocket = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    handleRequest(socket);
                } catch (IOException e) {
                    if (!serverSocket.isClosed()) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        String requestLine = reader.readLine();

        if (requestLine == null) {
            return;
        }

        String[] parts = requestLine.split(" ");
        boolean valid = parts.length >= 2 && "GET".equals(parts[0]) && "/imu".equals(parts[1]);
        byte[] body = (valid ? sampleJson() : "{\"error\":\"not found\"}")
                .getBytes(StandardCharsets.UTF_8);

        String status = valid ? "200 OK" : "404 Not Found";
        String headers = String.format(Locale.US,
                "HTTP/1.1 %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
                status, body.length);

        OutputStream output = socket.getOutputStream();
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private String sampleJson() {
        Sample current = sample.get();
        try {
            JSONObject root = new JSONObject();
            root.put("ready", current.hasAccel && current.hasGyro);

            JSONObject acceleration = new JSONObject();
            acceleration.put("x", current.ax);
            acceleration.put("y", current.ay);
            acceleration.put("z", current.az);
            root.put("acceleration_mps2", acceleration);

            JSONObject angularVelocity = new JSONObject();
            angularVelocity.put("x", current.gx);
            angularVelocity.put("y", current.gy);
            angularVelocity.put("z", current.gz);
            root.put("angular_velocity_rad_s", angularVelocity);

            root.put("accelerometer_timestamp_ns", current.accelTimestampNs);
            root.put("gyroscope_timestamp_ns", current.gyroTimestampNs);
            return root.toString();
        } catch (JSONException e) {
            return "{\"error\":\"failed to encode sensor sample\"}";
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "OpenRoadCode Sensor Bridge",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("OpenRoadCode Sensor Bridge")
                .setContentText("Accelerometer and gyroscope bridge is running")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build();
    }

    private static final class Sample {
        float ax;
        float ay;
        float az;
        float gx;
        float gy;
        float gz;
        long accelTimestampNs;
        long gyroTimestampNs;
        boolean hasAccel;
        boolean hasGyro;

        Sample copy() {
            Sample result = new Sample();
            result.ax = ax;
            result.ay = ay;
            result.az = az;
            result.gx = gx;
            result.gy = gy;
            result.gz = gz;
            result.accelTimestampNs = accelTimestampNs;
            result.gyroTimestampNs = gyroTimestampNs;
            result.hasAccel = hasAccel;
            result.hasGyro = hasGyro;
            return result;
        }
    }
}
