package org.openroadcode.androidbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class BluetoothSppBridgeService extends Service {
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";
    public static final int TCP_PORT = 35000;

    public static final String ACTION_STATUS =
            "org.openroadcode.androidbridge.BLUETOOTH_SPP_STATUS";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_MESSAGE = "message";
    public static final String STATUS_CONNECTING = "connecting";
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_STOPPED = "stopped";

    private static final String CHANNEL_ID = "openroadcode-bluetooth-spp";
    private static final int NOTIFICATION_ID = 35000;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final long CONNECT_ATTEMPT_TIMEOUT_MS = 8000L;
    private static final long TOTAL_CONNECT_TIMEOUT_MS = CONNECT_ATTEMPT_TIMEOUT_MS * 2L;

    private volatile boolean running;
    private Thread worker;
    private ServerSocket serverSocket;
    private BluetoothSocket bluetoothSocket;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "OpenRoadCode Bluetooth", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("Bluetooth SPP bridge starting"));
        if (worker != null && worker.isAlive()) return START_STICKY;
        String address = intent == null ? null : intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
        running = true;
        worker = new Thread(() -> runBridge(address), "orc-bluetooth-spp");
        worker.start();
        return START_STICKY;
    }

    private void runBridge(String requestedAddress) {
        boolean connected = false;
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                throw new IOException("Bluetooth is unavailable or disabled");
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Bluetooth connect permission is required");
            }

            BluetoothDevice device = findDevice(adapter.getBondedDevices(), requestedAddress);
            if (device == null) throw new IOException("No paired Bluetooth device selected");

            String deviceName = safeName(device);
            reportStatus(STATUS_CONNECTING, "Connecting to " + deviceName + "…");
            updateNotification("Connecting to " + deviceName);
            adapter.cancelDiscovery();

            bluetoothSocket = connectDevice(device);
            connected = true;
            String connectedMessage = "Connected to " + deviceName + " • TCP 127.0.0.1:" + TCP_PORT;
            reportStatus(STATUS_CONNECTED, connectedMessage);
            updateNotification(connectedMessage);

            serverSocket = new ServerSocket(TCP_PORT, 1, InetAddress.getByName("127.0.0.1"));
            while (running) {
                Socket client = serverSocket.accept();
                try {
                    bridgeClient(client, bluetoothSocket);
                } finally {
                    try { client.close(); } catch (IOException ignored) { }
                }
            }
        } catch (Exception exception) {
            if (running) {
                String message = exception.getMessage();
                if (message == null || message.isEmpty()) message = exception.getClass().getSimpleName();
                String display = connected
                        ? "Bluetooth bridge error: " + message
                        : "Unable to connect: " + message;
                reportStatus(STATUS_ERROR, display);
                updateNotification(display);
            }
        } finally {
            closeResources();
            if (!running) reportStatus(STATUS_STOPPED, "Bluetooth bridge stopped");
            running = false;
            stopSelf();
        }
    }

    private BluetoothSocket connectDevice(BluetoothDevice device) throws IOException, InterruptedException {
        IOException insecureFailure;
        try {
            return connectSocket(device, true, CONNECT_ATTEMPT_TIMEOUT_MS);
        } catch (IOException exception) {
            insecureFailure = exception;
        }

        if (!running) throw new IOException("Connection cancelled");
        try {
            return connectSocket(device, false, CONNECT_ATTEMPT_TIMEOUT_MS);
        } catch (IOException secureFailure) {
            IOException failure = new IOException(
                    "connection timed out or was refused after " + (TOTAL_CONNECT_TIMEOUT_MS / 1000L)
                            + " seconds (insecure and secure SPP both failed)", secureFailure);
            failure.addSuppressed(insecureFailure);
            throw failure;
        }
    }

    private BluetoothSocket connectSocket(BluetoothDevice device, boolean insecure, long timeoutMs)
            throws IOException, InterruptedException {
        BluetoothSocket socket = insecure
                ? device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                : device.createRfcommSocketToServiceRecord(SPP_UUID);
        bluetoothSocket = socket;

        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread connector = new Thread(() -> {
            try {
                socket.connect();
            } catch (IOException exception) {
                failure.set(exception);
            }
        }, insecure ? "orc-spp-connect-insecure" : "orc-spp-connect-secure");
        connector.start();
        connector.join(timeoutMs);

        if (connector.isAlive()) {
            try { socket.close(); } catch (IOException ignored) { }
            connector.join(500L);
            throw new SocketTimeoutException((insecure ? "insecure" : "secure")
                    + " SPP connection timed out after " + (timeoutMs / 1000L) + " seconds");
        }

        IOException connectFailure = failure.get();
        if (connectFailure != null) {
            try { socket.close(); } catch (IOException ignored) { }
            throw connectFailure;
        }
        return socket;
    }

    private BluetoothDevice findDevice(Set<BluetoothDevice> devices, String address) {
        if (address == null || address.isEmpty()) return null;
        for (BluetoothDevice device : devices) {
            if (address.equalsIgnoreCase(device.getAddress())) return device;
        }
        return null;
    }

    private void bridgeClient(Socket client, BluetoothSocket bluetooth) throws Exception {
        InputStream tcpIn = client.getInputStream();
        OutputStream tcpOut = client.getOutputStream();
        InputStream btIn = bluetooth.getInputStream();
        OutputStream btOut = bluetooth.getOutputStream();

        Thread tcpToBt = new Thread(() -> copy(tcpIn, btOut), "orc-tcp-to-spp");
        tcpToBt.start();
        copy(btIn, tcpOut);
        try { client.shutdownInput(); } catch (IOException ignored) { }
        try { client.shutdownOutput(); } catch (IOException ignored) { }
        tcpToBt.interrupt();
    }

    private void copy(InputStream input, OutputStream output) {
        byte[] buffer = new byte[1024];
        try {
            int count;
            while (running && (count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                output.flush();
            }
        } catch (IOException ignored) { }
    }

    private String safeName(BluetoothDevice device) {
        String name = device.getName();
        return name == null || name.isEmpty() ? device.getAddress() : name;
    }

    private void reportStatus(String state, String message) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATUS, state);
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_openroadcode_notification)
                .setContentTitle("OpenRoadCode Bluetooth Bridge")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    @Override
    public void onDestroy() {
        running = false;
        closeResources();
        reportStatus(STATUS_STOPPED, "Bluetooth bridge stopped");
        super.onDestroy();
    }

    private void closeResources() {
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        try { if (bluetoothSocket != null) bluetoothSocket.close(); } catch (IOException ignored) { }
        serverSocket = null;
        bluetoothSocket = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
