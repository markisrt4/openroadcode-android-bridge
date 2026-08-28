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
import java.util.Set;
import java.util.UUID;

public final class BluetoothSppBridgeService extends Service {
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";
    public static final int TCP_PORT = 35000;

    private static final String CHANNEL_ID = "openroadcode-bluetooth-spp";
    private static final int NOTIFICATION_ID = 35000;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

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
        try {
            BluetoothManager manager = getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IOException("Bluetooth is unavailable or disabled");
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Bluetooth connect permission is required");
            }

            BluetoothDevice device = findDevice(adapter.getBondedDevices(), requestedAddress);
            if (device == null) throw new IOException("No paired Bluetooth device selected");

            updateNotification("Connecting to " + safeName(device));
            adapter.cancelDiscovery();
            bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            bluetoothSocket.connect();
            updateNotification("SPP connected • TCP 127.0.0.1:" + TCP_PORT);

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
            updateNotification("Bluetooth bridge error: " + exception.getMessage());
        } finally {
            closeResources();
            stopSelf();
        }
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
        super.onDestroy();
    }

    private void closeResources() {
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        try { if (bluetoothSocket != null) bluetoothSocket.close(); } catch (IOException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
