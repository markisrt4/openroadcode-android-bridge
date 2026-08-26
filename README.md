# OpenRoadCode Android Bridge

A minimal Android hardware bridge for OpenRoadCode.

The first milestone exposes the phone's accelerometer and gyroscope through a localhost-only HTTP endpoint while a foreground service keeps sensor collection alive in the background.

## API

With the bridge running:

```bash
curl http://127.0.0.1:8766/imu
```

Example response:

```json
{
  "acceleration_mps2": {"x": 0.1, "y": 0.2, "z": 9.8},
  "angular_velocity_rad_s": {"x": 0.01, "y": 0.02, "z": 0.03},
  "accelerometer_timestamp_ns": 123456789,
  "gyroscope_timestamp_ns": 123456790
}
```

The HTTP server binds only to `127.0.0.1`; it is not exposed to the LAN.

## Build

The project targets Android API 34 and Java 17, matching `mrtf-android-buildenv`.

```bash
./gradlew assembleDebug
```

The debug APK is produced under `app/build/outputs/apk/debug/`.
