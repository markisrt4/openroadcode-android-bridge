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
import android.hardware.usb.UsbRequest;
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
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    public static final int OP_STREAM_BULK_IN = 8;
    public static final int OP_STREAM_STOP = 9;

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
                    case OP_STREAM_BULK_IN:
                        handleBulkInStream(in, out, connection, device);
                        break;
                    case OP_STREAM_STOP:
                        writeError(out, "RTL-SDR stream is not active");
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

    private static final class BulkChunk {
        final UsbRequest request;
        final ByteBuffer buffer;
        final int length;

        BulkChunk(UsbRequest request, ByteBuffer buffer, int length) {
            this.request = request;
            this.buffer = buffer;
            this.length = length;
        }
    }

    private void handleBulkInStream(DataInputStream in, DataOutputStream out,
                                    UsbDeviceConnection connection, UsbDevice device) throws IOException {
        int endpointAddress = in.readInt();
        int requestedLength = checkedLength(in.readInt());
        in.readInt(); // timeout is not used by Android's asynchronous UsbRequest API
        UsbEndpoint endpoint = findEndpoint(device, endpointAddress);
        if (endpoint == null || (endpoint.getDirection() & 0x80) == 0) {
            writeError(out, "USB bulk IN endpoint 0x" + Integer.toHexString(endpointAddress) + " not found");
            return;
        }

        // Mirror librtlsdr/libusb's strategy: keep several USB reads in flight
        // so the RTL2832U is never waiting for Java or the localhost writer to
        // submit the next transfer. Buffers are allocated once and recycled.
        final int transferLength = Math.min(requestedLength, 256 * 1024);
        final int requestCount = 8;
        ArrayBlockingQueue<BulkChunk> completed = new ArrayBlockingQueue<>(requestCount);
        ArrayBlockingQueue<BulkChunk> reusable = new ArrayBlockingQueue<>(requestCount);
        AtomicBoolean streaming = new AtomicBoolean(true);
        Map<UsbRequest, ByteBuffer> inFlight = new HashMap<>();

        for (int i = 0; i < requestCount; i++) {
            UsbRequest request = new UsbRequest();
            if (!request.initialize(connection, endpoint)) {
                request.close();
                throw new IOException("Unable to initialize asynchronous RTL-SDR USB request");
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(transferLength);
            buffer.clear();
            if (!request.queue(buffer, transferLength)) {
                request.close();
                throw new IOException("Unable to queue asynchronous RTL-SDR USB request");
            }
            inFlight.put(request, buffer);
        }

        Thread control = new Thread(() -> {
            try {
                int magic = in.readInt();
                int version = in.readUnsignedShort();
                int opcode = in.readUnsignedShort();
                if (magic != MAGIC || version != PROTOCOL_VERSION || opcode != OP_STREAM_STOP) {
                    streaming.set(false);
                    return;
                }
                streaming.set(false);
                for (UsbRequest request : inFlight.keySet()) {
                    try { request.cancel(); } catch (Exception ignored) { }
                }
            } catch (IOException exception) {
                streaming.set(false);
            }
        }, "orc-rtl-stream-control");
        control.start();

        Thread writer = new Thread(() -> {
            try {
                while (running && streaming.get()) {
                    BulkChunk chunk = completed.poll(500, TimeUnit.MILLISECONDS);
                    if (chunk == null) continue;
                    ByteBuffer buffer = chunk.buffer;
                    buffer.flip();
                    byte[] data = new byte[chunk.length];
                    buffer.get(data, 0, chunk.length);
                    out.writeInt(chunk.length);
                    out.write(data);
                    out.flush();
                    reusable.put(chunk);
                }
            } catch (IOException exception) {
                streaming.set(false);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                streaming.set(false);
            }
        }, "orc-rtl-socket-writer");
        writer.start();

        try {
            while (running && streaming.get()) {
                UsbRequest request = connection.requestWait();
                if (request == null) continue;
                ByteBuffer buffer = inFlight.remove(request);
                if (buffer == null) continue;
                int transferred = buffer.position();
                if (transferred > 0) {
                    completed.put(new BulkChunk(request, buffer, transferred));
                    BulkChunk ready = reusable.take();
                    ready.buffer.clear();
                    if (!ready.request.queue(ready.buffer, transferLength)) {
                        streaming.set(false);
                        break;
                    }
                    inFlight.put(ready.request, ready.buffer);
                } else {
                    buffer.clear();
                    if (!request.queue(buffer, transferLength)) {
                        streaming.set(false);
                        break;
                    }
                    inFlight.put(request, buffer);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            streaming.set(false);
            // Do not interrupt the stream-control thread here. It is the only
            // reader of the STREAM_STOP command and may still be consuming its
            // header. Wait for it to finish before returning to handleClient,
            // otherwise both threads race to read the next ORCU command.
            writer.interrupt();
            for (UsbRequest request : inFlight.keySet()) {
                try { request.cancel(); } catch (Exception ignored) { }
                try { request.close(); } catch (Exception ignored) { }
            }
            for (BulkChunk chunk : completed) {
                try { chunk.request.cancel(); } catch (Exception ignored) { }
                try { chunk.request.close(); } catch (Exception ignored) { }
            }
            for (BulkChunk chunk : reusable) {
                try { chunk.request.cancel(); } catch (Exception ignored) { }
                try { chunk.request.close(); } catch (Exception ignored) { }
            }
            try { writer.join(1000L); } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            try { control.join(1000L); } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            // Zero is an unambiguous stream terminator because IQ frames are nonempty.
            out.writeInt(0);
            out.flush();
        }
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
