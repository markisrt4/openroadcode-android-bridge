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
        BridgeLog.initialize(this);
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
                startRtlSdrProxy();
            }
        });

        RtlSdrUsbManager.State state = rtlSdrUsbManager.refresh();
        if (state.status == RtlSdrUsbManager.Status.DETECTED) {
            rtlSdrUsbManager.open();
        } else if (state.status == RtlSdrUsbManager.Status.OPEN && !proxyStarted) {
            startRtlSdrProxy();
        }
    }

    public RtlSdrUsbManager.State radioState() { return radioState; }

    public void startRadioBridge() {
        radioEnabled = true;
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

    public void startRtlSdrProxy() {
        if (!radioEnabled || proxyStarted) return;
        proxyStarted = true;
        try {
            startForegroundService(new Intent(this, RtlSdrUsbProxyService.class));
        } catch (RuntimeException error) {
            proxyStarted = false;
            Log.e(TAG, "Unable to start radio bridge", error);
        }
    }

    public void stopRtlSdrProxy() {
        stopRadioBridge();
    }

    public boolean isRadioEnabled() { return radioEnabled; }

    public void onRadioProxyStopped() { proxyStarted = false; }
}
