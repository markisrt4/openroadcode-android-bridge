package org.openroadcode.androidbridge;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import java.util.Locale;

/** Read-only discovery. Termux/libSDRForge owns USB permission and I/O. */
public final class RtlSdrUsbManager {
    public static final class State {
        public final UsbDevice device;
        public final String message;
        State(UsbDevice device, String message) {
            this.device = device;
            this.message = message;
        }
        public String friendlyName() {
            if (device == null) return "USB receiver";
            try {
                String name = device.getProductName();
                if (name != null && !name.isEmpty()) return name;
            } catch (SecurityException ignored) { }
            return "RTL-SDR receiver";
        }
        public String deviceLabel() {
            if (device == null) return "No RTL-SDR detected";
            return String.format(Locale.US, "VID 0x%04X PID 0x%04X",
                device.getVendorId(), device.getProductId());
        }
    }

    private final UsbManager usbManager;
    public RtlSdrUsbManager(Context context) {
        usbManager = context.getSystemService(UsbManager.class);
    }
    public State refresh() {
        if (usbManager != null) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                if (device.getVendorId() == 0x0BDA
                    && (device.getProductId() == 0x2832 || device.getProductId() == 0x2838)) {
                    return new State(device, "RTL-SDR detected; Termux manages USB access");
                }
            }
        }
        return new State(null, "No RTL-SDR USB device detected");
    }
}
