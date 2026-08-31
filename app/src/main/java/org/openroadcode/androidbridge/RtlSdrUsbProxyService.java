package org.openroadcode.androidbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

/**
 * Localhost-only USB transport for RTL-SDR access from Termux.
 *
 * The bridge deliberately contains no RTL2832U/tuner logic. It exposes the
 * Android-owned UsbDeviceConnection as a small request/response protocol so a
 * Linux-side client can perform the control and bulk transfers itself.
 */
public final class RtlSdrUsbProxyService extends Service {
    public static final int TCP_PORT = 35100;
    public static final int PROTOCOL_VERSION = 1;

    public static final int OP_INFO = 1;
    public static final int OP_CLAIM_INTERFACE = 2;
    public static final int OP_RELEASE_INTERFACE = 3;
    public static final int OP_CONTROL_TRANSFER = 4;
    public static final int OP_BULK_TRANSFER = 5;
    public static final int OP_RESET_DEVICE = 6;
    public static final int OP_CLOSE_CLIENT = 7;

    public static final int RESULT_OK = 0;
    public static final int RESULT_ERROR = -1;

    private static final String TAG = "ORC-RTL-USB-PROXY";
    private static final int MAGIC = 0x4F524355; // "ORCU"
    private static final int MAX_TRANSFER_BYTES = 1024 * 1024;
    private static final int CONNECTION_WAIT_MS = 15000;
    private static final String CHANNEL_ID = "openroadcode-rtl-sdr-usb";
    private static final int NOTIFICATION_ID = 35100;

    private volatile boolean running;
    private Thread worker;
    private ServerSocket serverSocket;
    private Socket activeClient;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "OpenRoadCode RTL-SDR USB", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("RTL-SDR USB proxy starting"));
        if (worker != null && worker.isAlive()) return START_STICKY;
        running = true;
        worker = new Thread(this::runServer, "orc-rtl-usb-proxy");
        worker.start();
        return START_STICKY;
    }

    private void runServer() {
        try {
            RtlSdrUsbManager manager = getUsbManager();
            UsbDeviceConnection connection = waitForConnection(manager);
            if (connection == null) throw new IOException("RTL-SDR USB device is not open");

            serverSocket = new ServerSocket(TCP_PORT, 1, InetAddress.getByName("127.0.0.1"));
            String ready = "RTL-SDR USB proxy ready • 127.0.0.1:" + TCP_PORT;
            Log.i(TAG, ready);
            updateNotification(ready);

            while (running) {
                Socket client = serverSocket.accept();
                activeClient = client;
                Log.i(TAG, "Termux client connected: " + client.getRemoteSocketAddress());
                try {
                    handleClient(client, manager);
                } catch (EOFException ignored) {
                    Log.i(TAG, "Termux client disconnected");
                } catch (IOException exception) {
                    if (running) Log.w(TAG, "USB proxy client failure", exception);
                } finally {
                    activeClient = null;
                    try { client.close(); } catch (IOException ignored) { }
                }
            }
        } catch (Exception exception) {
            if (running) {
                Log.e(TAG, "RTL-SDR USB proxy failed", exception);
                updateNotification("RTL-SDR proxy error • " + safeMessage(exception));
            }
        } finally {
            closeSockets();
            running = false;
            stopSelf();
        }
    }

    private UsbDeviceConnection waitForConnection(RtlSdrUsbManager manager) throws InterruptedException {
        long deadline = System.currentTimeMillis() + CONNECTION_WAIT_MS;
        manager.refresh();
        manager.open();
        while (running && System.currentTimeMillis() < deadline) {
            UsbDeviceConnection connection = manager.getConnection();
            if (connection != null) return connection;
            Thread.sleep(100L);
        }
        return manager.getConnection();
    }

    private void handleClient(Socket socket, RtlSdrUsbManager manager) throws IOException {
        socket.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
        Map<Integer, UsbInterface> claimed = new HashMap<>();

        try {
            while (running) {
                int magic = in.readInt();
                if (magic != MAGIC) throw new IOException("Bad RTL-SDR proxy magic");
                int version = in.readUnsignedShort();
                int opcode = in.readUnsignedShort();
                if (version != PROTOCOL_VERSION) {
                    writeError(out, "Unsupported protocol version " + version);
                    continue;
                }

                UsbDeviceConnection connection = manager.getConnection();
                UsbDevice device = manager.getDevice();
                if (connection == null || device == null) {
                    writeError(out, "RTL-SDR USB connection is not open");
                    continue;
                }

                switch (opcode) {
                    case OP_INFO:
                        handleInfo(out, device);
                        break;
                    case OP_CLAIM_INTERFACE:
                        handleClaim(in, out, connection, device, claimed);
                        break;
                    case OP_RELEASE_INTERFACE:
                        handleRelease(in, out, connection, claimed);
                        break;
                    case OP_CONTROL_TRANSFER:
                        handleControlTransfer(in, out, connection);
                        break;
                    case OP_BULK_TRANSFER:
                        handleBulkTransfer(in, out, connection, device);
                        break;
                    case OP_RESET_DEVICE:
                        handleReset(out, manager, claimed);
                        break;
                    case OP_CLOSE_CLIENT:
                        writeResult(out, RESULT_OK, null);
                        return;
                    default:
                        writeError(out, "Unknown opcode " + opcode);
                        break;
                }
            }
        } finally {
            for (UsbInterface usbInterface : claimed.values()) {
                try {
                    UsbDeviceConnection connection = connectionOrNull(manager);
                    if (connection != null) connection.releaseInterface(usbInterface);
                } catch (Exception ignored) { }
            }
        }
    }

    private void handleReset(DataOutputStream out, RtlSdrUsbManager manager,
                             Map<Integer, UsbInterface> claimed) throws IOException {
        UsbDeviceConnection connection = manager.getConnection();
        if (connection != null) {
            for (UsbInterface usbInterface : claimed.values()) {
                try { connection.releaseInterface(usbInterface); } catch (Exception ignored) { }
            }
        }
        claimed.clear();

        // Android's public UsbDeviceConnection API has no resetDevice().
        // Reopen the device instead, which gives the proxy a fresh connection
        // without relying on hidden/private Android USB APIs.
        manager.close();
        manager.refresh();
        manager.open();
        UsbDeviceConnection reopened;
        try {
            reopened = waitForConnection(manager);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writeError(out, "Interrupted while reopening RTL-SDR USB device");
            return;
        }
        writeResult(out, reopened != null ? RESULT_OK : RESULT_ERROR, null);
    }

    private void handleInfo(DataOutputStream out, UsbDevice device) throws IOException {
        out.writeInt(RESULT_OK);
        out.writeInt(device.getVendorId());
        out.writeInt(device.getProductId());
        out.writeInt(device.getInterfaceCount());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            out.writeInt(iface.getId());
            out.writeInt(iface.getInterfaceClass());
            out.writeInt(iface.getInterfaceSubclass());
            out.writeInt(iface.getInterfaceProtocol());
            out.writeInt(iface.getEndpointCount());
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint endpoint = iface.getEndpoint(j);
                out.writeInt(endpoint.getAddress());
                out.writeInt(endpoint.getAttributes());
                out.writeInt(endpoint.getDirection());
                out.writeInt(endpoint.getType());
                out.writeInt(endpoint.getMaxPacketSize());
            }
        }
        out.flush();
    }

    private void handleClaim(DataInputStream in, DataOutputStream out,
                             UsbDeviceConnection connection, UsbDevice device,
                             Map<Integer, UsbInterface> claimed) throws IOException {
        int interfaceId = in.readInt();
        boolean force = in.readBoolean();
        UsbInterface iface = findInterface(device, interfaceId);
        if (iface == null) {
            writeError(out, "USB interface " + interfaceId + " not found");
            return;
        }
        boolean ok = connection.claimInterface(iface, force);
        if (ok) claimed.put(interfaceId, iface);
        writeResult(out, ok ? RESULT_OK : RESULT_ERROR, null);
    }

    private void handleRelease(DataInputStream in, DataOutputStream out,
                               UsbDeviceConnection connection,
                               Map<Integer, UsbInterface> claimed) throws IOException {
        int interfaceId = in.readInt();
        UsbInterface iface = claimed.remove(interfaceId);
        if (iface == null) {
            writeError(out, "USB interface " + interfaceId + " is not claimed");
            return;
        }
        writeResult(out, connection.releaseInterface(iface) ? RESULT_OK : RESULT_ERROR, null);
    }

    private void handleControlTransfer(DataInputStream in, DataOutputStream out,
                                       UsbDeviceConnection connection) throws IOException {
        int requestType = in.readInt();
        int request = in.readInt();
        int value = in.readInt();
        int index = in.readInt();
        int length = checkedLength(in.readInt());
        int timeoutMs = in.readInt();
        boolean input = (requestType & 0x80) != 0;
        byte[] buffer = new byte[length];
        if (!input && length > 0) in.readFully(buffer);
        int transferred = connection.controlTransfer(
                requestType, request, value, index, buffer, length, timeoutMs);
        if (transferred < 0) {
            writeResult(out, transferred, null);
            return;
        }
        byte[] response = null;
        if (input && transferred > 0) {
            response = new byte[transferred];
            System.arraycopy(buffer, 0, response, 0, transferred);
        }
        writeResult(out, transferred, response);
    }

    private void handleBulkTransfer(DataInputStream in, DataOutputStream out,
                                    UsbDeviceConnection connection, UsbDevice device) throws IOException {
        int endpointAddress = in.readInt();
        int length = checkedLength(in.readInt());
        int timeoutMs = in.readInt();
        UsbEndpoint endpoint = findEndpoint(device, endpointAddress);
        if (endpoint == null) {
            writeError(out, "USB endpoint 0x" + Integer.toHexString(endpointAddress) + " not found");
            return;
        }
        boolean input = (endpoint.getDirection() & 0x80) != 0;
        byte[] buffer = new byte[length];
        if (!input && length > 0) in.readFully(buffer);
        int transferred = connection.bulkTransfer(endpoint, buffer, length, timeoutMs);
        if (transferred < 0) {
            writeResult(out, transferred, null);
            return;
        }
        byte[] response = null;
        if (input && transferred > 0) {
            response = new byte[transferred];
            System.arraycopy(buffer, 0, response, 0, transferred);
        }
        writeResult(out, transferred, response);
    }

    private static int checkedLength(int length) throws IOException {
        if (length < 0 || length > MAX_TRANSFER_BYTES) {
            throw new IOException("Invalid USB transfer length " + length);
        }
        return length;
    }

    private static UsbInterface findInterface(UsbDevice device, int id) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getId() == id) return iface;
        }
        return null;
    }

    private static UsbEndpoint findEndpoint(UsbDevice device, int address) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint endpoint = iface.getEndpoint(j);
                if (endpoint.getAddress() == address) return endpoint;
            }
        }
        return null;
    }

    private static void writeResult(DataOutputStream out, int result, byte[] data) throws IOException {
        out.writeInt(result);
        int length = data == null ? 0 : data.length;
        out.writeInt(length);
        if (length > 0) out.write(data);
        out.flush();
    }

    private static void writeError(DataOutputStream out, String message) throws IOException {
        byte[] data = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(RESULT_ERROR);
        out.writeInt(data.length);
        out.write(data);
        out.flush();
    }

    private RtlSdrUsbManager getUsbManager() {
        return ((OpenRoadCodeBridgeApplication) getApplication()).getRtlSdrUsbManager();
    }

    private static UsbDeviceConnection connectionOrNull(RtlSdrUsbManager manager) {
        return manager.getConnection();
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_openroadcode_notification)
                .setContentTitle("OpenRoadCode RTL-SDR Bridge")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeSockets();
        super.onDestroy();
    }

    private void closeSockets() {
        try { if (activeClient != null) activeClient.close(); } catch (IOException ignored) { }
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        activeClient = null;
        serverSocket = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
