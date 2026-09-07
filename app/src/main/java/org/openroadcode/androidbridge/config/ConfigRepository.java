package org.openroadcode.androidbridge.config;

import android.content.Context;
import android.content.SharedPreferences;

public final class ConfigRepository {
    private static final String PREFERENCES = "bridge_service_config";
    private static final String SENSOR_ENABLED = "sensor.enabled";
    private static final String SENSOR_PROVIDER = "sensor.provider";
    private static final String VEHICLE_ENABLED = "vehicle.enabled";
    private static final String VEHICLE_PROVIDER = "vehicle.provider";
    private static final String VEHICLE_DEVICE_ADDRESS = "vehicle.device_address";

    private final SharedPreferences preferences;

    public ConfigRepository(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public ServiceConfig sensorConfig() {
        return load(SENSOR_ENABLED, SENSOR_PROVIDER, false, ServiceProvider.ANDROID_SENSORS);
    }

    public void saveSensorConfig(ServiceConfig config) {
        save(SENSOR_ENABLED, SENSOR_PROVIDER, config);
    }

    public ServiceConfig vehicleConfig() {
        return load(VEHICLE_ENABLED, VEHICLE_PROVIDER, false, ServiceProvider.BLUETOOTH_SPP);
    }

    public void saveVehicleConfig(ServiceConfig config) {
        save(VEHICLE_ENABLED, VEHICLE_PROVIDER, config);
    }

    /** Selected paired Bluetooth device, independent from the provider type itself. */
    public String vehicleDeviceAddress() {
        return preferences.getString(VEHICLE_DEVICE_ADDRESS, null);
    }

    public void saveVehicleDeviceAddress(String address) {
        SharedPreferences.Editor editor = preferences.edit();
        if (address == null || address.isEmpty()) editor.remove(VEHICLE_DEVICE_ADDRESS);
        else editor.putString(VEHICLE_DEVICE_ADDRESS, address);
        editor.apply();
    }

    private ServiceConfig load(
            String enabledKey, String providerKey, boolean defaultEnabled, ServiceProvider defaultProvider) {
        boolean enabled = preferences.getBoolean(enabledKey, defaultEnabled);
        String providerName = preferences.getString(providerKey, defaultProvider.name());
        ServiceProvider provider;
        try {
            provider = ServiceProvider.valueOf(providerName);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            provider = defaultProvider;
        }
        return new ServiceConfig(enabled, provider);
    }

    private void save(String enabledKey, String providerKey, ServiceConfig config) {
        preferences.edit()
                .putBoolean(enabledKey, config.enabled())
                .putString(providerKey, config.provider().name())
                .apply();
    }
}
