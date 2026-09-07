package org.openroadcode.androidbridge.config;

public enum ServiceProvider {
    ANDROID_SENSORS("Android Sensors"),
    SIMULATED_DRIVE("Simulated Drive"),
    KONNWEI_SPP("KONNWEI SPP"),
    SIMULATED_VEHICLE("Simulated Vehicle");

    private final String displayName;

    ServiceProvider(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
