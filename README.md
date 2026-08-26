# OpenRoadCode Android Bridge

A minimal Android hardware bridge for OpenRoadCode running on the same phone.

The foreground service samples the phone's inertial, magnetic-field, and pressure sensors and exposes them through a localhost-only HTTP API. OpenRoadCode running in Termux consumes this hardware-facing API and publishes normalized application contracts over ZeroMQ.

## Architecture

```text
Android sensors -> Sensor Bridge APK -> 127.0.0.1:8766 -> OpenRoadCode hardware_io -> ZeroMQ
```

The APK deliberately does not speak ZeroMQ. This keeps Android framework concerns in the bridge while OpenRoadCode owns its messaging contracts and application architecture.

## API

Health/status snapshot:

```bash
curl http://127.0.0.1:8766/health
```

Sensor snapshot:

```bash
curl http://127.0.0.1:8766/imu
```

Continuous newline-delimited JSON stream:

```bash
curl -N http://127.0.0.1:8766/stream/imu
```

The sensor payload can contain:

- acceleration in m/s²
- linear acceleration in m/s²
- angular velocity in rad/s
- magnetic field in µT
- barometric pressure in hPa
- Android monotonic sensor timestamps
- per-sensor availability flags

The HTTP server binds only to `127.0.0.1`; it is not exposed to the LAN.

## Build

The project targets Android API 34 and Java 17, matching `mrtf-android-buildenv`.

```bash
./gradlew assembleDebug
```

The debug APK is produced under:

```text
app/build/outputs/apk/debug/
```

Pushes to GitHub also run the `Build Android APK` workflow and publish the debug APK as a workflow artifact.

## OpenRoadCode integration

The corresponding Termux-side hardware adapters and ZeroMQ publisher live in the main OpenRoadCode repository. See `docs/android_sensor_pipeline.md` there for the complete build, run, and diagnostic procedure.
