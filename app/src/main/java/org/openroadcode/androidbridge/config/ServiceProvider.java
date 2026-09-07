package org.openroadcode.androidbridge.config;

public enum ServiceProvider {
    ANDROID_SENSORS("Android Sensors"),
    SIMULATED_DRIVE("Simulated Drive"),
    BLUETOOTH_SPP("Bluetooth OBD-II (SPP)"),
    SIMULATED_VEHICLE("Simulated Vehicle");

    private final String displayName;

    ServiceProvider(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
