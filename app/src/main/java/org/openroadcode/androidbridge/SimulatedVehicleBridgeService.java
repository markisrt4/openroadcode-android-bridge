package org.openroadcode.androidbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Small ELM327-compatible simulator for development without a physical OBD adapter.
 * It exposes the same localhost TCP endpoint as BluetoothSppBridgeService so ORC does
 * not need a second transport contract for simulated vehicle data.
 */
public final class SimulatedVehicleBridgeService extends Service {
    public static final int TCP_PORT = BluetoothSppBridgeService.TCP_PORT;
    public static final String ACTION_STATUS =
            "org.openroadcode.androidbridge.SIMULATED_VEHICLE_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_MESSAGE = "message";
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_STOPPED = "stopped";

    private static final String TAG = "OpenRoadCodeVehicleSim";
    private static final String CHANNEL_ID = "openroadcode-vehicle-sim";
    private static final int NOTIFICATION_ID = 35001;

    private volatile boolean running;
    private Thread worker;
    private ServerSocket serverSocket;
    private Socket activeClient;
    private boolean echo = true;
    private final long startedAtMs = System.currentTimeMillis();

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "OpenRoadCode Vehicle Simulator", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Simulated vehicle starting"));
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        running = true;
        worker = new Thread(this::runServer, "orc-simulated-vehicle");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runServer() {
        try {
            reportStatus(STATUS_STARTING, "Starting simulated vehicle endpoint…");
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), TCP_PORT), 1);
            String ready = "Simulated vehicle ready • ELM327 • TCP 127.0.0.1:" + TCP_PORT;
            reportStatus(STATUS_RUNNING, ready);
            updateNotification(ready);
            Log.i(TAG, ready);

            while (running) {
                Socket client = serverSocket.accept();
                activeClient = client;
                try {
                    serveClient(client);
                } catch (IOException exception) {
                    if (running) Log.w(TAG, "Simulator client disconnected", exception);
                } finally {
                    activeClient = null;
                    try { client.close(); } catch (IOException ignored) { }
                }
            }
        } catch (Exception exception) {
            if (running) {
                String message = exception.getMessage();
                if (message == null || message.isEmpty()) message = exception.getClass().getSimpleName();
                Log.e(TAG, "Vehicle simulator failed", exception);
                reportStatus(STATUS_ERROR, "Simulated vehicle error • " + message);
                updateNotification("Simulator error: " + message);
            }
        } finally {
            closeResources();
            running = false;
            worker = null;
            stopSelf();
        }
    }

    private void serveClient(Socket client) throws IOException {
        InputStream input = client.getInputStream();
        OutputStream output = client.getOutputStream();
        StringBuilder command = new StringBuilder();
        output.write('>');
        output.flush();

        int value;
        while (running && (value = input.read()) >= 0) {
            char ch = (char) value;
            if (ch == '\r' || ch == '\n') {
                if (command.length() == 0) continue;
                String raw = command.toString();
                command.setLength(0);
                writeResponse(output, raw);
            } else if (ch >= 32 && ch < 127) {
                command.append(ch);
                if (command.length() > 128) command.setLength(0);
            }
        }
    }

    private void writeResponse(OutputStream output, String rawCommand) throws IOException {
        String normalized = rawCommand.toUpperCase(Locale.US).replace(" ", "").trim();
        String response = responseFor(normalized);
        StringBuilder wire = new StringBuilder();
        if (echo) wire.append(rawCommand).append('\r');
        wire.append(response).append("\r>");
        output.write(wire.toString().getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    private String responseFor(String command) {
        if (command.isEmpty()) return "";
        if ("ATZ".equals(command)) {
            echo = true;
            return "ELM327 v1.5";
        }
        if ("ATI".equals(command)) return "ELM327 v1.5";
        if ("ATE0".equals(command)) { echo = false; return "OK"; }
        if ("ATE1".equals(command)) { echo = true; return "OK"; }
        if (command.startsWith("ATL") || command.startsWith("ATS") || command.startsWith("ATH")
                || command.startsWith("ATSP") || command.startsWith("ATAT")
                || command.startsWith("ATST") || command.startsWith("ATCAF")) return "OK";
        if ("ATDP".equals(command)) return "AUTO, ISO 15765-4 (CAN 11/500)";
        if ("ATDPN".equals(command)) return "A6";

        double phase = (System.currentTimeMillis() - startedAtMs) / 1000.0;
        int speedKph = clamp((int) Math.round(58.0 + 24.0 * Math.sin(phase / 8.0)), 0, 120);
        int rpm = clamp((int) Math.round(950.0 + speedKph * 32.0 + 220.0 * Math.sin(phase * 1.7)), 750, 5200);
        int throttlePct = clamp((int) Math.round(24.0 + 13.0 * Math.sin(phase / 3.0)), 5, 70);
        int loadPct = clamp(28 + throttlePct / 2, 0, 100);
        int mapKpa = clamp(42 + throttlePct, 20, 115);

        switch (command) {
            case "0100": return "41 00 BE 3F A8 13";
            case "0104": return String.format(Locale.US, "41 04 %02X", percentByte(loadPct));
            case "0105": return "41 05 7D"; // 85 C
            case "010B": return String.format(Locale.US, "41 0B %02X", mapKpa);
            case "010C": {
                int encoded = rpm * 4;
                return String.format(Locale.US, "41 0C %02X %02X", (encoded >> 8) & 0xFF, encoded & 0xFF);
            }
            case "010D": return String.format(Locale.US, "41 0D %02X", speedKph);
            case "010F": return "41 0F 46"; // 30 C
            case "0111": return String.format(Locale.US, "41 11 %02X", percentByte(throttlePct));
            case "0142": return "41 42 36 B0"; // 14.000 V
            default: return "NO DATA";
        }
    }

    private int percentByte(int percent) {
        return clamp((int) Math.round(percent * 255.0 / 100.0), 0, 255);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_openroadcode_notification)
                .setContentTitle("OpenRoadCode Vehicle Simulator")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private void reportStatus(String state, String message) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, state);
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        closeResources();
        Thread current = worker;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
            try { current.join(750L); } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        worker = null;
        reportStatus(STATUS_STOPPED, "Simulated vehicle stopped");
        super.onDestroy();
    }

    private void closeResources() {
        Socket client = activeClient;
        activeClient = null;
        ServerSocket server = serverSocket;
        serverSocket = null;
        try { if (client != null) client.close(); } catch (IOException ignored) { }
        try { if (server != null) server.close(); } catch (IOException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
