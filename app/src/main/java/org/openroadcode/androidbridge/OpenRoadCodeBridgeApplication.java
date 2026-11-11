package org.openroadcode.androidbridge;

import android.app.Application;

/** Initializes bridge-wide services without claiming Termux USB devices. */
public final class OpenRoadCodeBridgeApplication extends Application {
    private RtlSdrUsbManager rtlSdrUsbManager;

    @Override public void onCreate() {
        super.onCreate();
        BridgeLog.initialize(this);
        rtlSdrUsbManager = new RtlSdrUsbManager(this);
    }

    public RtlSdrUsbManager getRtlSdrUsbManager() { return rtlSdrUsbManager; }
}
