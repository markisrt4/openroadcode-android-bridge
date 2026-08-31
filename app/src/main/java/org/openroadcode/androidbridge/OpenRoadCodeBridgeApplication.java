package org.openroadcode.androidbridge;

import android.app.Application;
import android.util.Log;

/** Initializes Android-side ownership of attached RTL-SDR hardware. */
public final class OpenRoadCodeBridgeApplication extends Application {
    private static final String TAG = "ORC-RTL-SDR";
    private RtlSdrUsbManager rtlSdrUsbManager;

    @Override
    public void onCreate() {
        super.onCreate();
        rtlSdrUsbManager = new RtlSdrUsbManager(this, state -> {
            String device = state.device == null ? "none" : state.deviceLabel();
            Log.i(TAG, state.status + " | " + state.message + " | " + device +
                    (state.fileDescriptor >= 0 ? " | fd=" + state.fileDescriptor : ""));
        });

        RtlSdrUsbManager.State state = rtlSdrUsbManager.refresh();
        if (state.status == RtlSdrUsbManager.Status.DETECTED) {
            rtlSdrUsbManager.open();
        }
    }

    public RtlSdrUsbManager getRtlSdrUsbManager() {
        return rtlSdrUsbManager;
    }
}
