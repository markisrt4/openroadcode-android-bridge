package org.openroadcode.androidbridge.runtime;

import android.content.Context;
import android.content.Intent;
import org.openroadcode.androidbridge.BluetoothSppBridgeService;
import org.openroadcode.androidbridge.SensorBridgeService;
import org.openroadcode.androidbridge.SimulatedVehicleBridgeService;
import org.openroadcode.androidbridge.config.ConfigRepository;
import org.openroadcode.androidbridge.config.ServiceConfig;
import org.openroadcode.androidbridge.config.ServiceProvider;

/** Coordinates persisted intent and Android lifecycle for bridge services. */
public final class BridgeServiceManager {
    public enum ServiceState {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR
    }

    private final Context context;
    private final ConfigRepository configRepository;
    private ServiceState sensorState;
    private ServiceState vehicleState;

    public BridgeServiceManager(Context context) {
        this.context = context.getApplicationContext();
        this.configRepository = new ConfigRepository(this.context);
        this.sensorState = sensorConfig().enabled() ? ServiceState.STARTING : ServiceState.STOPPED;
        this.vehicleState = vehicleConfig().enabled() ? ServiceState.STARTING : ServiceState.STOPPED;
    }

    public ServiceConfig sensorConfig() {
        return configRepository.sensorConfig();
    }

    public boolean sensorRequested() {
        return sensorConfig().enabled();
    }

    public ServiceState sensorState() {
        return sensorState;
    }

    public void setSensorProvider(ServiceProvider provider) {
        ServiceConfig current = sensorConfig();
        if (current.provider() == provider) return;
        configRepository.saveSensorConfig(current.withProvider(provider));
    }

    /** Records the user's intent to run the sensor bridge before permission checks complete. */
    public void requestSensorEnabled() {
        ServiceConfig current = sensorConfig();
        if (!current.enabled()) configRepository.saveSensorConfig(current.withEnabled(true));
        sensorState = ServiceState.STARTING;
    }

    /** Starts the sensor process without changing the persisted requested state. */
    public void startRequestedSensor() {
        if (!sensorRequested()) return;
        sensorState = ServiceState.STARTING;
        context.startForegroundService(new Intent(context, SensorBridgeService.class));
    }

    /** Restarts a requested sensor process, preserving provider and enabled configuration. */
    public void restartRequestedSensor() {
        if (!sensorRequested()) return;
        context.stopService(new Intent(context, SensorBridgeService.class));
        startRequestedSensor();
    }

    /** Stops the process temporarily while preserving the user's requested enabled state. */
    public void suspendSensor() {
        context.stopService(new Intent(context, SensorBridgeService.class));
        sensorState = ServiceState.STOPPED;
    }

    /** Stops the sensor bridge and clears the persisted enabled request. */
    public void disableSensor() {
        ServiceConfig current = sensorConfig();
        if (current.enabled()) configRepository.saveSensorConfig(current.withEnabled(false));
        context.stopService(new Intent(context, SensorBridgeService.class));
        sensorState = ServiceState.STOPPED;
    }

    public void markSensorRunning() {
        sensorState = ServiceState.RUNNING;
    }

    public void markSensorStarting() {
        sensorState = ServiceState.STARTING;
    }

    public void markSensorError() {
        sensorState = ServiceState.ERROR;
    }

    public ServiceConfig vehicleConfig() {
        return configRepository.vehicleConfig();
    }

    public boolean vehicleRequested() {
        return vehicleConfig().enabled();
    }

    public ServiceState vehicleState() {
        return vehicleState;
    }

    public void setVehicleProvider(ServiceProvider provider) {
        ServiceConfig current = vehicleConfig();
        if (current.provider() == provider) return;
        configRepository.saveVehicleConfig(current.withProvider(provider));
    }

    public void requestVehicleEnabled() {
        ServiceConfig current = vehicleConfig();
        if (!current.enabled()) configRepository.saveVehicleConfig(current.withEnabled(true));
        vehicleState = ServiceState.STARTING;
    }

    /** Starts whichever vehicle provider is currently configured. */
    public void startRequestedVehicle(String deviceAddress) {
        if (!vehicleRequested()) return;
        ServiceProvider provider = vehicleConfig().provider();
        vehicleState = ServiceState.STARTING;

        if (provider == ServiceProvider.SIMULATED_VEHICLE) {
            context.stopService(new Intent(context, BluetoothSppBridgeService.class));
            context.startForegroundService(new Intent(context, SimulatedVehicleBridgeService.class));
            return;
        }

        if (provider == ServiceProvider.KONNWEI_SPP) {
            context.stopService(new Intent(context, SimulatedVehicleBridgeService.class));
            Intent intent = new Intent(context, BluetoothSppBridgeService.class);
            intent.putExtra(BluetoothSppBridgeService.EXTRA_DEVICE_ADDRESS, deviceAddress);
            context.startForegroundService(intent);
            return;
        }

        vehicleState = ServiceState.ERROR;
    }

    /** Stops either vehicle provider while preserving the persisted requested state. */
    public void suspendVehicle() {
        context.stopService(new Intent(context, BluetoothSppBridgeService.class));
        context.stopService(new Intent(context, SimulatedVehicleBridgeService.class));
        vehicleState = ServiceState.STOPPED;
    }

    /** Stops all vehicle providers and clears the persisted requested state. */
    public void disableVehicle() {
        ServiceConfig current = vehicleConfig();
        if (current.enabled()) configRepository.saveVehicleConfig(current.withEnabled(false));
        context.stopService(new Intent(context, BluetoothSppBridgeService.class));
        context.stopService(new Intent(context, SimulatedVehicleBridgeService.class));
        vehicleState = ServiceState.STOPPED;
    }

    public void markVehicleRunning() {
        vehicleState = ServiceState.RUNNING;
    }

    public void markVehicleStarting() {
        vehicleState = ServiceState.STARTING;
    }

    public void markVehicleStopped() {
        vehicleState = ServiceState.STOPPED;
    }

    public void markVehicleError() {
        vehicleState = ServiceState.ERROR;
    }
}
