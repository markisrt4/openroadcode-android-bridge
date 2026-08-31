package org.openroadcode.androidbridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.util.Collection;
import java.util.Locale;

/**
 * Owns Android USB discovery and permission for RTL-SDR class devices.
 *
 * This class intentionally does not implement any SDR protocol. Its job is to
 * obtain Android's permission to the physical USB device and keep an open
 * UsbDeviceConnection that the Termux transport layer can use next.
 */
public final class RtlSdrUsbManager implements AutoCloseable {
    public interface Listener {
        void onStateChanged(State state);
    }

    public enum Status {
        NOT_FOUND,
        DETECTED,
        PERMISSION_PENDING,
        OPEN,
        ERROR
    }

    public static final class State {
        public final Status status;
        public final UsbDevice device;
        public final String message;
        public final int fileDescriptor;

        State(Status status, UsbDevice device, String message, int fileDescriptor) {
            this.status = status;
            this.device = device;
            this.message = message;
            this.fileDescriptor = fileDescriptor;
        }

        public String deviceLabel() {
            if (device == null) return "No RTL-SDR detected";
            return String.format(
                    Locale.US,
                    "VID 0x%04X  PID 0x%04X  %s",
                    device.getVendorId(),
                    device.getProductId(),
                    device.getDeviceName());
        }
    }

    private static final String ACTION_USB_PERMISSION =
            "org.openroadcode.androidbridge.USB_PERMISSION_RTL_SDR";

    // Common RTL2832U / RTL-SDR USB IDs. We primarily match the Realtek
    // demodulator family while allowing known rebranded devices below.
    private static final int REALTEK_VENDOR_ID = 0x0BDA;
    private static final int RTL2832U_PRODUCT_ID = 0x2832;
    private static final int RTL2838UHIDIR_PRODUCT_ID = 0x2838;

    private final Context context;
    private final UsbManager usbManager;
    private final Listener listener;
    private UsbDevice selectedDevice;
    private UsbDeviceConnection connection;
    private boolean receiverRegistered;

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                @SuppressWarnings("deprecation")
                UsbDevice oldDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                device = oldDevice;
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted || device == null) {
                publish(Status.ERROR, selectedDevice, "USB permission denied", -1);
                return;
            }
            selectedDevice = device;
            openSelectedDevice();
        }
    };

    public RtlSdrUsbManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.usbManager = this.context.getSystemService(UsbManager.class);
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        this.context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    public State refresh() {
        selectedDevice = findRtlSdr();
        if (selectedDevice == null) {
            closeConnection();
            return publish(Status.NOT_FOUND, null, "No RTL-SDR USB device detected", -1);
        }
        if (connection != null) {
            return publish(Status.OPEN, selectedDevice, "RTL-SDR USB connection open", connection.getFileDescriptor());
        }
        return publish(Status.DETECTED, selectedDevice, "RTL-SDR detected", -1);
    }

    public void open() {
        selectedDevice = findRtlSdr();
        if (selectedDevice == null) {
            publish(Status.NOT_FOUND, null, "No RTL-SDR USB device detected", -1);
            return;
        }
        if (connection != null) {
            publish(Status.OPEN, selectedDevice, "RTL-SDR USB connection already open", connection.getFileDescriptor());
            return;
        }
        if (usbManager.hasPermission(selectedDevice)) {
            openSelectedDevice();
            return;
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        publish(Status.PERMISSION_PENDING, selectedDevice, "Waiting for Android USB permission", -1);
        usbManager.requestPermission(selectedDevice, permissionIntent);
    }

    public void disconnect() {
        closeConnection();
        selectedDevice = findRtlSdr();
        if (selectedDevice == null) {
            publish(Status.NOT_FOUND, null, "No RTL-SDR USB device detected", -1);
        } else {
            publish(Status.DETECTED, selectedDevice, "RTL-SDR disconnected from bridge", -1);
        }
    }

    public UsbDeviceConnection getConnection() {
        return connection;
    }

    public UsbDevice getDevice() {
        return selectedDevice;
    }

    private void openSelectedDevice() {
        closeConnection();
        if (selectedDevice == null) {
            publish(Status.ERROR, null, "RTL-SDR selection disappeared", -1);
            return;
        }
        connection = usbManager.openDevice(selectedDevice);
        if (connection == null) {
            publish(Status.ERROR, selectedDevice, "Android could not open RTL-SDR USB device", -1);
            return;
        }
        publish(Status.OPEN, selectedDevice, "RTL-SDR USB connection open", connection.getFileDescriptor());
    }

    private UsbDevice findRtlSdr() {
        if (usbManager == null) return null;
        Collection<UsbDevice> devices = usbManager.getDeviceList().values();
        for (UsbDevice device : devices) {
            if (isRtlSdr(device)) return device;
        }
        return null;
    }

    private static boolean isRtlSdr(UsbDevice device) {
        if (device.getVendorId() != REALTEK_VENDOR_ID) return false;
        int product = device.getProductId();
        return product == RTL2832U_PRODUCT_ID || product == RTL2838UHIDIR_PRODUCT_ID;
    }

    private State publish(Status status, UsbDevice device, String message, int fd) {
        State state = new State(status, device, message, fd);
        if (listener != null) listener.onStateChanged(state);
        return state;
    }

    private void closeConnection() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Override
    public void close() {
        closeConnection();
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(permissionReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
    }
}
