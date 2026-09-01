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
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final String TAG = "OpenRoadCodeSPP";
    private static final String CHANNEL_ID = "openroadcode-bluetooth-spp";
    private static final int NOTIFICATION_ID = 35000;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final long CONNECT_ATTEMPT_TIMEOUT_MS = 8000L;
    private static final long TOTAL_CONNECT_TIMEOUT_MS = CONNECT_ATTEMPT_TIMEOUT_MS * 2L;
    private static final int TCP_BIND_ATTEMPTS = 3;
    private static final long TCP_BIND_RETRY_DELAY_MS = 250L;
    private static final long WORKER_STOP_TIMEOUT_MS = 1500L;

    private final AtomicLong tcpToSppBytes = new AtomicLong();
    private final AtomicLong sppToTcpBytes = new AtomicLong();
    private final AtomicReference<IOException> bluetoothReaderFailure = new AtomicReference<>();
    private final Object clientLock = new Object();
    private final Object lifecycleLock = new Object();
    private volatile boolean running;
    private volatile boolean errorReported;
    private Thread worker;
    private Thread bluetoothReader;
    private ServerSocket serverSocket;
    private BluetoothSocket bluetoothSocket;
    private Socket activeClient;
    private OutputStream activeTcpOutput;

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
        synchronized (lifecycleLock) {
            if (worker != null && worker.isAlive()) {
                Log.i(TAG, "Start requested while Bluetooth SPP worker is already running");
                return START_NOT_STICKY;
            }

            worker = null;
            bluetoothReader = null;
            closeResources();

            String address = intent == null ? null : intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
            running = true;
            errorReported = false;
            tcpToSppBytes.set(0L);
            sppToTcpBytes.set(0L);
            bluetoothReaderFailure.set(null);
            worker = new Thread(() -> runBridge(address), "orc-bluetooth-spp");
            worker.start();
        }
        return START_NOT_STICKY;
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

            bluetoothSocket = connectDevice(device);
            connected = true;

            serverSocket = bindTcpServerWithRetry();
            startBluetoothReader(bluetoothSocket);

            String connectedMessage = "Connected to " + deviceName + " • TCP 127.0.0.1:" + TCP_PORT;
            reportStatus(STATUS_CONNECTED, connectedMessage);
            updateNotification(connectedMessage);

            while (running) {
                Socket client = serverSocket.accept();
                Log.i(TAG, "TCP client connected: " + client.getRemoteSocketAddress());
                try {
                    setActiveClient(client);
                    bridgeTcpToBluetooth(client, bluetoothSocket);
                } catch (IOException clientFailure) {
                    IOException readerFailure = bluetoothReaderFailure.get();
                    if (readerFailure != null && running) throw readerFailure;
                    if (running) Log.w(TAG, "TCP client I/O failure", clientFailure);
                } finally {
                    clearActiveClient(client);
                    try { client.close(); } catch (IOException ignored) { }
                    Log.i(TAG, "TCP client disconnected");
                }

                IOException readerFailure = bluetoothReaderFailure.get();
                if (readerFailure != null && running) throw readerFailure;
            }
        } catch (Exception exception) {
            IOException readerFailure = bluetoothReaderFailure.get();
            if (readerFailure != null && running) exception = readerFailure;
            if (running) {
                String message = exception.getMessage();
                if (message == null || message.isEmpty()) message = exception.getClass().getSimpleName();
                String display = connected
                        ? "Bluetooth bridge error: " + message
                        : "Unable to connect: " + message;
                errorReported = true;
                Log.e(TAG, display, exception);
                reportStatus(STATUS_ERROR, display);
                updateNotification(display);
            }
        } finally {
            closeResources();
            synchronized (lifecycleLock) {
                if (Thread.currentThread() == worker) worker = null;
            }
            if (!running && !errorReported) {
                reportStatus(STATUS_STOPPED, "Bluetooth bridge stopped");
            }
            running = false;
            stopSelf();
        }
    }

    private ServerSocket bindTcpServerWithRetry() throws IOException, InterruptedException {
        BindException lastBindFailure = null;
        for (int attempt = 1; attempt <= TCP_BIND_ATTEMPTS && running; attempt++) {
            ServerSocket candidate = new ServerSocket();
            candidate.setReuseAddress(true);
            try {
                candidate.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), TCP_PORT), 1);
                Log.i(TAG, "TCP bridge listening on 127.0.0.1:" + TCP_PORT);
                return candidate;
            } catch (BindException exception) {
                lastBindFailure = exception;
                try { candidate.close(); } catch (IOException ignored) { }
                Log.w(TAG, "TCP bind attempt " + attempt + " failed", exception);
                if (attempt < TCP_BIND_ATTEMPTS) Thread.sleep(TCP_BIND_RETRY_DELAY_MS);
            } catch (IOException exception) {
                try { candidate.close(); } catch (IOException ignored) { }
                throw exception;
            }
        }
        if (!running) throw new IOException("Bluetooth bridge stopped while opening TCP endpoint");
        throw lastBindFailure == null
                ? new BindException("Unable to bind TCP endpoint 127.0.0.1:" + TCP_PORT)
                : lastBindFailure;
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
        Log.i(TAG, "Bluetooth SPP connected using " + (insecure ? "insecure" : "secure") + " RFCOMM");
        return socket;
    }

    private BluetoothDevice findDevice(Set<BluetoothDevice> devices, String address) {
        if (address == null || address.isEmpty()) return null;
        for (BluetoothDevice device : devices) {
            if (address.equalsIgnoreCase(device.getAddress())) return device;
        }
        return null;
    }

    private void startBluetoothReader(BluetoothSocket bluetooth) throws IOException {
        InputStream btIn = bluetooth.getInputStream();
        bluetoothReader = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                int count;
                while (running && (count = btIn.read(buffer)) >= 0) {
                    OutputStream tcpOut;
                    Socket client;
                    synchronized (clientLock) {
                        tcpOut = activeTcpOutput;
                        client = activeClient;
                    }
                    if (tcpOut == null || client == null) continue;

                    try {
                        tcpOut.write(buffer, 0, count);
                        tcpOut.flush();
                        long total = sppToTcpBytes.addAndGet(count);
                        Log.i(TAG, "SPP→TCP " + count + " bytes (total " + total + ")");
                        reportTrafficStatus();
                    } catch (IOException clientFailure) {
                        Log.w(TAG, "SPP→TCP client disconnected", clientFailure);
                        closeActiveClient(client);
                    }
                }
            } catch (IOException exception) {
                if (running) {
                    bluetoothReaderFailure.set(exception);
                    Log.e(TAG, "SPP reader I/O failure", exception);
                    try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
                    closeActiveClient(null);
                }
            }
        }, "orc-spp-reader");
        bluetoothReader.start();
    }

    private void bridgeTcpToBluetooth(Socket client, BluetoothSocket bluetooth) throws IOException {
        InputStream tcpIn = client.getInputStream();
        OutputStream btOut = bluetooth.getOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while (running && (count = tcpIn.read(buffer)) >= 0) {
            btOut.write(buffer, 0, count);
            btOut.flush();
            long total = tcpToSppBytes.addAndGet(count);
            Log.i(TAG, "TCP→SPP " + count + " bytes (total " + total + ")");
            reportTrafficStatus();
        }
    }

    private void setActiveClient(Socket client) throws IOException {
        synchronized (clientLock) {
            activeClient = client;
            activeTcpOutput = client.getOutputStream();
        }
    }

    private void clearActiveClient(Socket client) {
        synchronized (clientLock) {
            if (activeClient != client) return;
            activeClient = null;
            activeTcpOutput = null;
        }
    }

    private void closeActiveClient(Socket expectedClient) {
        Socket client;
        synchronized (clientLock) {
            client = activeClient;
            if (client == null || (expectedClient != null && client != expectedClient)) return;
            activeClient = null;
            activeTcpOutput = null;
        }
        try { client.close(); } catch (IOException ignored) { }
    }

    private void reportTrafficStatus() {
        String message = "Connected • TCP→SPP " + tcpToSppBytes.get()
                + " B • SPP→TCP " + sppToTcpBytes.get() + " B";
        reportStatus(STATUS_CONNECTED, message);
        updateNotification(message);
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
        Thread workerToJoin;
        synchronized (lifecycleLock) {
            running = false;
            closeResources();
            workerToJoin = worker;
            if (workerToJoin != null) workerToJoin.interrupt();
        }

        if (workerToJoin != null && workerToJoin != Thread.currentThread()) {
            try {
                workerToJoin.join(WORKER_STOP_TIMEOUT_MS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            if (workerToJoin.isAlive()) {
                Log.w(TAG, "Bluetooth SPP worker did not stop within timeout");
            }
        }

        synchronized (lifecycleLock) {
            if (worker == workerToJoin && (workerToJoin == null || !workerToJoin.isAlive())) worker = null;
            bluetoothReader = null;
        }
        if (!errorReported) {
            reportStatus(STATUS_STOPPED, "Bluetooth bridge stopped");
        }
        super.onDestroy();
    }

    private void closeResources() {
        closeActiveClient(null);
        ServerSocket tcpServer = serverSocket;
        serverSocket = null;
        BluetoothSocket sppSocket = bluetoothSocket;
        bluetoothSocket = null;
        try { if (tcpServer != null) tcpServer.close(); } catch (IOException ignored) { }
        try { if (sppSocket != null) sppSocket.close(); } catch (IOException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
