package org.openroadcode.androidbridge;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

/** Initializes Android-side ownership of attached RTL-SDR hardware. */
public final class OpenRoadCodeBridgeApplication extends Application {
    private static final String TAG = "ORC-RTL-SDR";
    private RtlSdrUsbManager rtlSdrUsbManager;
    private boolean proxyStarted;

    @Override
    public void onCreate() {
        super.onCreate();
        rtlSdrUsbManager = new RtlSdrUsbManager(this, state -> {
            String device = state.device == null ? "none" : state.deviceLabel();
            Log.i(TAG, state.status + " | " + state.message + " | " + device +
                    (state.fileDescriptor >= 0 ? " | fd=" + state.fileDescriptor : ""));
            if (state.status == RtlSdrUsbManager.Status.OPEN && !proxyStarted) {
                proxyStarted = true;
                startForegroundService(new Intent(this, RtlSdrUsbProxyService.class));
            }
        });

        RtlSdrUsbManager.State state = rtlSdrUsbManager.refresh();
        if (state.status == RtlSdrUsbManager.Status.DETECTED) {
            rtlSdrUsbManager.open();
        } else if (state.status == RtlSdrUsbManager.Status.OPEN && !proxyStarted) {
            proxyStarted = true;
            startForegroundService(new Intent(this, RtlSdrUsbProxyService.class));
        }
    }

    public RtlSdrUsbManager getRtlSdrUsbManager() {
        return rtlSdrUsbManager;
    }
}
