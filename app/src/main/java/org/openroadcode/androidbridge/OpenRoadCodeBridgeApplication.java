package org.openroadcode.androidbridge;

import android.app.Application;
import android.content.Intent;
import android.util.Log;

/** Initializes Android-side ownership of attached RTL-SDR hardware. */
public final class OpenRoadCodeBridgeApplication extends Application {
    private static final String TAG = "ORC-RTL-SDR";
    private RtlSdrUsbManager rtlSdrUsbManager;
    private volatile boolean proxyStarted;
    private volatile boolean radioEnabled = true;
    private volatile RtlSdrUsbManager.State radioState;

    @Override
    public void onCreate() {
        super.onCreate();
        rtlSdrUsbManager = new RtlSdrUsbManager(this, state -> {
            radioState = state;
            String device = state.device == null ? "none" : state.deviceLabel();
            Log.i(TAG, state.status + " | " + state.message + " | " + device +
                    (state.fileDescriptor >= 0 ? " | fd=" + state.fileDescriptor : ""));
            if (state.status == RtlSdrUsbManager.Status.OPEN && !radioEnabled) {
                rtlSdrUsbManager.disconnect();
                return;
            }
            if (state.status == RtlSdrUsbManager.Status.OPEN && !proxyStarted) {
                proxyStarted = true;
                try {
                    startForegroundService(new Intent(this, RtlSdrUsbProxyService.class));
                } catch (RuntimeException error) {
                    proxyStarted = false;
                    Log.e(TAG, "Unable to start radio bridge", error);
                }
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

    public RtlSdrUsbManager.State radioState() { return radioState; }

    public void startRadioBridge() {
        radioEnabled = true;
        proxyStarted = false;
        rtlSdrUsbManager.open();
    }

    public void stopRadioBridge() {
        radioEnabled = false;
        stopService(new Intent(this, RtlSdrUsbProxyService.class));
        proxyStarted = false;
        rtlSdrUsbManager.disconnect();
    }

    public RtlSdrUsbManager getRtlSdrUsbManager() {
        return rtlSdrUsbManager;
    }
}
