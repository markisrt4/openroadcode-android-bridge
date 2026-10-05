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
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    public static final int OP_LAST_STREAM_STATUS = 10;

    public static final int RESULT_OK = 0;
    public static final int RESULT_ERROR = -1;

    private static final String TAG = "ORC-RTL-USB-PROXY";
    private static final int MAGIC = 0x4F524355; // "ORCU"
    private static final int MAX_TRANSFER_BYTES = 1024 * 1024;
    private static final int CONNECTION_WAIT_MS = 15000;
    private static final String CHANNEL_ID = "openroadcode-rtl-sdr-usb";
    private static final int NOTIFICATION_ID = 35100;
    static final String DIAGNOSTIC_PREFERENCES = "rtl_sdr_proxy_diagnostics";
    static final String PREF_LAST_STREAM_STATUS = "last_stream_status";
    static final String PREF_LAST_SERVICE_STATUS = "last_service_status";
    static final String PREF_CONTROL_STATUS = "control_status";

    private volatile boolean running;
    private Thread worker;
    private ServerSocket serverSocket;
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
    private final Object usbLock = new Object();
    private final Map<Integer, Integer> interfaceClaimCounts = new HashMap<>();
    private volatile String lastStreamStatus = "No RTL-SDR stream has ended yet";
    private final java.util.concurrent.atomic.AtomicLong controlRequests = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong controlFirstAttemptSuccesses = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong controlRetryRecoveries = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong controlHardFailures = new java.util.concurrent.atomic.AtomicLong();
    private static final int CONTROL_HISTORY_LIMIT = 12;
    private final java.util.ArrayDeque<String> controlHistory = new java.util.ArrayDeque<>();

    @Override
    public void onCreate() {
        super.onCreate();
        persistServiceStatus("RTL-SDR proxy service created");
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
            persistServiceStatus(ready);
            updateNotification(ready);

            while (running) {
                Socket client = serverSocket.accept();
                activeClients.add(client);
                Log.i(TAG, "Termux client connected: " + client.getRemoteSocketAddress());
                Thread clientWorker = new Thread(() -> {
                    try {
                        handleClient(client, manager);
                    } catch (EOFException ignored) {
                        Log.i(TAG, "Termux client disconnected");
                    } catch (IOException exception) {
                        if (running) Log.w(TAG, "USB proxy client failure", exception);
                    } finally {
                        activeClients.remove(client);
                        try { client.close(); } catch (IOException ignored) { }
                    }
                }, "orc-rtl-usb-client");
                clientWorker.start();
            }
        } catch (Exception exception) {
            if (running) {
                String failure = "RTL-SDR proxy error • " + safeMessage(exception);
                Log.e(TAG, "RTL-SDR USB proxy failed", exception);
                persistServiceStatus(failure);
                updateNotification(failure);
            }
        } finally {
            persistServiceStatus("RTL-SDR proxy server loop ended • running=" + running);
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
                    case OP_LAST_STREAM_STATUS:
                        writeResult(out, RESULT_OK,
                                lastStreamStatus.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
                    if (connection != null) releaseSharedInterface(connection, usbInterface);
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
        boolean ok;
        synchronized (usbLock) {
            int count = interfaceClaimCounts.getOrDefault(interfaceId, 0);
            // Android's UsbDeviceConnection owns the claim, not the TCP client.
            // Call claimInterface for every ORCU claim so we can verify whether
            // the stream client's second claim changes endpoint usability.
            ok = connection.claimInterface(iface, force);
            Log.i(TAG, "RTL-SDR interface claim"
                    + " • interfaceId=" + interfaceId
                    + " force=" + force
                    + " priorSharedCount=" + count
                    + " androidResult=" + ok
                    + " fd=" + connection.getFileDescriptor());
            if (ok) {
                interfaceClaimCounts.put(interfaceId, count + 1);
                claimed.put(interfaceId, iface);
            }
        }
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
        boolean ok = releaseSharedInterface(connection, iface);
        writeResult(out, ok ? RESULT_OK : RESULT_ERROR, null);
    }

    private boolean releaseSharedInterface(UsbDeviceConnection connection, UsbInterface iface) {
        synchronized (usbLock) {
            int interfaceId = iface.getId();
            int count = interfaceClaimCounts.getOrDefault(interfaceId, 0);
            if (count <= 1) {
                interfaceClaimCounts.remove(interfaceId);
                return connection.releaseInterface(iface);
            }
            interfaceClaimCounts.put(interfaceId, count - 1);
            return true;
        }
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
        long sequence = controlRequests.incrementAndGet();
        int transferred;
        int attempts = 1;
        // Preserve libusb semantics exactly. Replaying a failed RTL2832U/I2C
        // vendor transaction is not equivalent to one libusb_control_transfer()
        // and can advance tuner state unexpectedly.
        synchronized (usbLock) {
            transferred = connection.controlTransfer(
                    requestType, request, value, index, buffer, length, timeoutMs);
        }
        String payload = "";
        if (!input && length > 0) {
            StringBuilder hex = new StringBuilder();
            int shown = Math.min(length, 16);
            for (int i = 0; i < shown; i++) {
                if (i > 0) hex.append(' ');
                hex.append(String.format(java.util.Locale.US, "%02X", buffer[i] & 0xFF));
            }
            payload = " data=" + hex;
        } else if (input && transferred > 0) {
            StringBuilder hex = new StringBuilder();
            int shown = Math.min(transferred, 16);
            for (int i = 0; i < shown; i++) {
                if (i > 0) hex.append(' ');
                hex.append(String.format(java.util.Locale.US, "%02X", buffer[i] & 0xFF));
            }
            payload = " data=" + hex;
        }
        String trace = String.format(java.util.Locale.US,
                "ORCU control #%d type=0x%02X req=0x%02X value=0x%04X index=0x%04X len=%d timeout=%d result=%d%s",
                sequence, requestType & 0xFF, request & 0xFF, value & 0xFFFF,
                index & 0xFFFF, length, timeoutMs, transferred, payload);
        synchronized (controlHistory) {
            if (transferred < 0) {
                Log.w(TAG, "ORCU control failure context BEGIN");
                for (String previous : controlHistory) Log.w(TAG, previous);
                Log.w(TAG, trace);
                Log.w(TAG, "ORCU control failure context END");
            } else if (sequence <= 32) {
                Log.w(TAG, trace);
            }
            controlHistory.addLast(trace);
            while (controlHistory.size() > CONTROL_HISTORY_LIMIT) controlHistory.removeFirst();
        }
        if (transferred < 0) {
            controlHardFailures.incrementAndGet();
            persistControlStatus(String.format(java.util.Locale.US,
                    "requests=%d firstOk=%d recovered=%d failed=%d • last failure type=0x%02X req=0x%02X value=0x%04X index=0x%04X len=%d attempts=%d",
                    controlRequests.get(), controlFirstAttemptSuccesses.get(),
                    controlRetryRecoveries.get(), controlHardFailures.get(),
                    requestType & 0xFF, request & 0xFF, value & 0xFFFF,
                    index & 0xFFFF, length, attempts));
            Log.e(TAG, String.format(java.util.Locale.US,
                    "USB control failed after %d attempt(s): type=0x%02X request=0x%02X value=0x%04X index=0x%04X length=%d timeout=%d",
                    attempts, requestType & 0xFF, request & 0xFF, value & 0xFFFF, index & 0xFFFF, length, timeoutMs));
            writeResult(out, transferred, null);
            return;
        }
        if (attempts == 1) {
            controlFirstAttemptSuccesses.incrementAndGet();
        } else {
            controlRetryRecoveries.incrementAndGet();
            persistControlStatus(String.format(java.util.Locale.US,
                    "requests=%d firstOk=%d recovered=%d failed=%d • last recovery attempt=%d type=0x%02X req=0x%02X value=0x%04X index=0x%04X len=%d",
                    controlRequests.get(), controlFirstAttemptSuccesses.get(),
                    controlRetryRecoveries.get(), controlHardFailures.get(),
                    attempts, requestType & 0xFF, request & 0xFF, value & 0xFFFF,
                    index & 0xFFFF, length));
            Log.w(TAG, String.format(java.util.Locale.US,
                    "USB control recovered on attempt %d: type=0x%02X request=0x%02X value=0x%04X index=0x%04X length=%d",
                    attempts, requestType & 0xFF, request & 0xFF, value & 0xFFFF, index & 0xFFFF, length));
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
        int transferred;
        synchronized (usbLock) {
            transferred = connection.bulkTransfer(endpoint, buffer, length, timeoutMs);
        }
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

    private void handleBulkInStream(DataInputStream in, DataOutputStream out,
                                    UsbDeviceConnection connection, UsbDevice device) throws IOException {
        int endpointAddress = in.readInt();
        int requestedLength = checkedLength(in.readInt());
        int requestedTimeoutMs = in.readInt();
        UsbEndpoint endpoint = findEndpoint(device, endpointAddress);
        if (endpoint == null || (endpoint.getDirection() & 0x80) == 0) {
            writeError(out, "USB bulk IN endpoint 0x" + Integer.toHexString(endpointAddress) + " not found");
            return;
        }

        // Samsung/Android 17 has been observed throwing from requestWait() before
        // the first UsbRequest completion. Starting with synchronous bulkTransfer()
        // avoids putting the USB connection through that broken async state first.
        final int transferLength = Math.min(requestedLength, 128 * 1024);
        final int timeoutMs = requestedTimeoutMs > 0 ? requestedTimeoutMs : 1000;
        final byte[] buffer = new byte[transferLength];
        AtomicBoolean streaming = new AtomicBoolean(true);
        AtomicReference<String> stopReason = new AtomicReference<>("unknown");
        java.util.concurrent.atomic.AtomicLong chunks = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong failures = new java.util.concurrent.atomic.AtomicLong();

        Thread control = new Thread(() -> {
            try {
                int magic = in.readInt();
                int version = in.readUnsignedShort();
                int opcode = in.readUnsignedShort();
                String controlHeader = String.format(java.util.Locale.US,
                        "stream control header magic=0x%08X version=%d opcode=%d",
                        magic, version, opcode);
                Log.i(TAG, controlHeader);
                if (magic != MAGIC || version != PROTOCOL_VERSION || opcode != OP_STREAM_STOP) {
                    Log.w(TAG, "Ignoring unexpected RTL-SDR stream control header: magic=0x"
                            + Integer.toHexString(magic) + " version=" + version + " opcode=" + opcode);
                    return;
                }
                Log.i(TAG, "RTL-SDR stream stop requested by client");
                stopReason.compareAndSet("unknown", "client STREAM_STOP");
                streaming.set(false);
            } catch (IOException exception) {
                // EOF on the control side does not by itself stop a healthy stream.
                Log.i(TAG, "RTL-SDR stream control reader ended: " + exception.getMessage());
            }
        }, "orc-rtl-stream-control");
        control.start();

        try {
            Log.i(TAG, "RTL-SDR USB topology"
                    + " • device=" + String.format(java.util.Locale.US, "%04X:%04X",
                            device.getVendorId(), device.getProductId())
                    + " interfaces=" + device.getInterfaceCount());
            for (int interfaceIndex = 0; interfaceIndex < device.getInterfaceCount(); interfaceIndex++) {
                UsbInterface usbInterface = device.getInterface(interfaceIndex);
                Log.i(TAG, "RTL-SDR USB interface"
                        + " • index=" + interfaceIndex
                        + " id=" + usbInterface.getId()
                        + " class=" + usbInterface.getInterfaceClass()
                        + " subclass=" + usbInterface.getInterfaceSubclass()
                        + " protocol=" + usbInterface.getInterfaceProtocol()
                        + " endpoints=" + usbInterface.getEndpointCount());
                for (int endpointIndex = 0; endpointIndex < usbInterface.getEndpointCount(); endpointIndex++) {
                    UsbEndpoint usbEndpoint = usbInterface.getEndpoint(endpointIndex);
                    Log.i(TAG, "RTL-SDR USB endpoint"
                            + " • interfaceId=" + usbInterface.getId()
                            + " index=" + endpointIndex
                            + " address=0x" + Integer.toHexString(usbEndpoint.getAddress())
                            + " attributes=0x" + Integer.toHexString(usbEndpoint.getAttributes())
                            + " direction=0x" + Integer.toHexString(usbEndpoint.getDirection())
                            + " type=" + usbEndpoint.getType()
                            + " maxPacketSize=" + usbEndpoint.getMaxPacketSize()
                            + " interval=" + usbEndpoint.getInterval());
                }
            }
            Log.i(TAG, "RTL-SDR synchronous bulk stream starting"
                    + " • endpoint=0x" + Integer.toHexString(endpoint.getAddress())
                    + " attributes=0x" + Integer.toHexString(endpoint.getAttributes())
                    + " direction=0x" + Integer.toHexString(endpoint.getDirection())
                    + " type=" + endpoint.getType()
                    + " maxPacketSize=" + endpoint.getMaxPacketSize()
                    + " interval=" + endpoint.getInterval()
                    + " transferLength=" + transferLength
                    + " timeoutMs=" + timeoutMs);

            // Android's synchronous USB path on the target Samsung device
            // accepts complete 128 KiB reads but rejects 256 KiB requests.
            // ORCU frames may be smaller than librtlsdr's requested stream size;
            // the Termux transport already carries/splits frames for SDR++.
            while (running && streaming.get()) {
                int transferred;
                synchronized (usbLock) {
                    transferred = connection.bulkTransfer(
                            endpoint, buffer, buffer.length, timeoutMs);
                }
                if (transferred > 0) {
                    long chunk = chunks.incrementAndGet();
                    long totalBytes = bytes.addAndGet(transferred);
                    out.writeInt(transferred);
                    out.write(buffer, 0, transferred);
                    out.flush();
                    if (chunk <= 3 || chunk % 32 == 0) {
                        Log.i(TAG, "RTL-SDR synchronous stream progress"
                                + " • chunks=" + chunk
                                + " bytes=" + totalBytes
                                + " last=" + transferred);
                    }
                    continue;
                }

                long failure = failures.incrementAndGet();
                Log.w(TAG, "RTL-SDR synchronous bulk read failed"
                        + " • result=" + transferred
                        + " failure=" + failure
                        + " chunks=" + chunks.get()
                        + " bytes=" + bytes.get());
                // A timeout/error can be transient. Three consecutive failures
                // preserve the previous fallback policy without touching UsbRequest.
                if (failure >= 3) {
                    stopReason.compareAndSet("unknown",
                            "synchronous bulk stream failed"
                                    + " • result=" + transferred
                                    + " failures=" + failure);
                    streaming.set(false);
                    break;
                }
            }
        } catch (IOException exception) {
            stopReason.compareAndSet("unknown", "socket writer IOException: " + exception);
            Log.e(TAG, "RTL-SDR synchronous socket writer failed", exception);
            streaming.set(false);
        } finally {
            if (!running) stopReason.compareAndSet("unknown", "service stopping");
            else if (!streaming.get()) stopReason.compareAndSet("unknown", "streaming flag cleared");
            else stopReason.compareAndSet("unknown", "synchronous stream loop exited unexpectedly");

            String terminationSummary = "RTL-SDR synchronous stream ended: " + stopReason.get()
                    + " • chunks=" + chunks.get()
                    + " bytes=" + bytes.get()
                    + " failures=" + failures.get();
            lastStreamStatus = terminationSummary;
            getSharedPreferences(DIAGNOSTIC_PREFERENCES, MODE_PRIVATE)
                    .edit().putString(PREF_LAST_STREAM_STATUS, terminationSummary).apply();
            Log.w(TAG, terminationSummary);
            updateNotification(terminationSummary);
            streaming.set(false);

            try { control.join(1000L); } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }

            String terminatorStatus = "sending zero-length stream terminator"
                    + " • reason=" + stopReason.get()
                    + " chunks=" + chunks.get()
                    + " bytes=" + bytes.get()
                    + " failures=" + failures.get();
            Log.w(TAG, terminatorStatus);
            getSharedPreferences(DIAGNOSTIC_PREFERENCES, MODE_PRIVATE)
                    .edit().putString(PREF_LAST_STREAM_STATUS, terminatorStatus).commit();
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
        persistServiceStatus("RTL-SDR proxy service destroyed • running=" + running);
        running = false;
        closeSockets();
        super.onDestroy();
    }

    private void persistControlStatus(String status) {
        getSharedPreferences(DIAGNOSTIC_PREFERENCES, MODE_PRIVATE)
                .edit().putString(PREF_CONTROL_STATUS, status).apply();
    }

    private void persistServiceStatus(String status) {
        getSharedPreferences(DIAGNOSTIC_PREFERENCES, MODE_PRIVATE)
                .edit().putString(PREF_LAST_SERVICE_STATUS, status).apply();
    }

    private void closeSockets() {
        for (Socket client : activeClients) {
            try { client.close(); } catch (IOException ignored) { }
        }
        activeClients.clear();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        serverSocket = null;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
