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
    public enum ServiceState { STOPPED, STARTING, RUNNING, ERROR }
    private final Context context;
    private final ConfigRepository configRepository;
    private ServiceState sensorState;
    private ServiceState vehicleState;
    private long sensorGeneration;
    private boolean sensorStartIssued;

    public BridgeServiceManager(Context context) {
        this.context = context.getApplicationContext();
        configRepository = new ConfigRepository(this.context);
        sensorState = sensorRequested() ? ServiceState.STARTING : ServiceState.STOPPED;
        vehicleState = vehicleRequested() ? ServiceState.STARTING : ServiceState.STOPPED;
    }
    public ServiceConfig sensorConfig() { return configRepository.sensorConfig(); }
    public boolean sensorRequested() { return sensorConfig().enabled(); }
    public ServiceState sensorState() { return sensorState; }
    public long sensorGeneration() { return sensorGeneration; }
    public void setSensorProvider(ServiceProvider provider) {
        ServiceConfig current = sensorConfig();
        if (current.provider() == provider) return;
        configRepository.saveSensorConfig(current.withProvider(provider));
        sensorGeneration++;
    }
    public void requestSensorEnabled() {
        ServiceConfig current = sensorConfig();
        if (!current.enabled()) configRepository.saveSensorConfig(current.withEnabled(true));
        sensorState = ServiceState.STARTING;
        sensorGeneration++;
    }
    public void startRequestedSensor() {
        if (!sensorRequested()) return;
        if (sensorStartIssued) return;
        sensorState = ServiceState.STARTING;
        sensorStartIssued = true;
        sensorGeneration++;
        try {
            context.startForegroundService(new Intent(context, SensorBridgeService.class));
        } catch (RuntimeException e) {
            sensorStartIssued = false;
            sensorState = ServiceState.ERROR;
            throw e;
        }
    }
    public void restartRequestedSensor() {
        if (!sensorRequested()) return;
        suspendSensor();
        startRequestedSensor();
    }
    public void suspendSensor() {
        sensorGeneration++;
        sensorStartIssued = false;
        context.stopService(new Intent(context, SensorBridgeService.class));
        sensorState = ServiceState.STOPPED;
    }
    public void disableSensor() {
        ServiceConfig current = sensorConfig();
        if (current.enabled()) configRepository.saveSensorConfig(current.withEnabled(false));
        suspendSensor();
    }
    public void markSensorRunning() { sensorState = ServiceState.RUNNING; sensorStartIssued = true; }
    public void markSensorStarting() { sensorState = ServiceState.STARTING; }
    public void markSensorError() { sensorState = ServiceState.ERROR; }
    public void markSensorStopped() { sensorState = ServiceState.STOPPED; sensorStartIssued = false; sensorGeneration++; }

    public ServiceConfig vehicleConfig() { return configRepository.vehicleConfig(); }
    public boolean vehicleRequested() { return vehicleConfig().enabled(); }
    public ServiceState vehicleState() { return vehicleState; }
    public String vehicleDeviceAddress() { return configRepository.vehicleDeviceAddress(); }
    public void setVehicleDeviceAddress(String address) { configRepository.saveVehicleDeviceAddress(address); }
    public void setVehicleProvider(ServiceProvider provider) {
        ServiceConfig current = vehicleConfig();
        if (current.provider() != provider) configRepository.saveVehicleConfig(current.withProvider(provider));
    }
    public void requestVehicleEnabled() {
        ServiceConfig current = vehicleConfig();
        if (!current.enabled()) configRepository.saveVehicleConfig(current.withEnabled(true));
        vehicleState = ServiceState.STARTING;
    }
    public void startRequestedVehicle() { startRequestedVehicle(vehicleDeviceAddress()); }
    public void startRequestedVehicle(String deviceAddress) {
        if (!vehicleRequested()) return;
        ServiceProvider provider = vehicleConfig().provider();
        vehicleState = ServiceState.STARTING;
        if (provider == ServiceProvider.SIMULATED_VEHICLE) {
            context.stopService(new Intent(context, BluetoothSppBridgeService.class));
            context.startForegroundService(new Intent(context, SimulatedVehicleBridgeService.class));
            return;
        }
        if (provider == ServiceProvider.BLUETOOTH_SPP) {
            context.stopService(new Intent(context, SimulatedVehicleBridgeService.class));
            if (deviceAddress == null || deviceAddress.isEmpty()) {
                vehicleState = ServiceState.ERROR;
                return;
            }
            configRepository.saveVehicleDeviceAddress(deviceAddress);
            Intent intent = new Intent(context, BluetoothSppBridgeService.class);
            intent.putExtra(BluetoothSppBridgeService.EXTRA_DEVICE_ADDRESS, deviceAddress);
            context.startForegroundService(intent);
            return;
        }
        vehicleState = ServiceState.ERROR;
    }
    public void suspendVehicle() {
        context.stopService(new Intent(context, BluetoothSppBridgeService.class));
        context.stopService(new Intent(context, SimulatedVehicleBridgeService.class));
        vehicleState = ServiceState.STOPPED;
    }
    public void disableVehicle() {
        ServiceConfig current = vehicleConfig();
        if (current.enabled()) configRepository.saveVehicleConfig(current.withEnabled(false));
        suspendVehicle();
    }
    public void markVehicleRunning() { vehicleState = ServiceState.RUNNING; }
    public void markVehicleStarting() { vehicleState = ServiceState.STARTING; }
    public void markVehicleStopped() { vehicleState = ServiceState.STOPPED; }
    public void markVehicleError() { vehicleState = ServiceState.ERROR; }
}
