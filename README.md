# OpenRoadCode Android Bridge

Android hardware bridge for OpenRoadCode running either on the same phone or another device on the selected network.

The foreground services expose Android sensors, GNSS, Bluetooth SPP devices, and the rear camera through small HTTP-facing interfaces. OpenRoadCode owns the normalized application contracts and higher-level messaging architecture.

## Architecture

```text
Android sensors/GNSS -> Sensor Bridge -> HTTP :8766 -> OpenRoadCode hardware_io -> ZeroMQ
Bluetooth SPP device -> SPP Bridge -----------------> OpenRoadCode
Android rear camera  -> Camera2 -> MediaCodec H.264 -> MPEG-TS -> HTTP :8767 -> OpenRoadCode/video player
```

## Sensor API

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

The sensor payload can contain acceleration, linear acceleration, angular velocity, magnetic field, barometric pressure, ambient light, Android monotonic timestamps, per-sensor availability, GNSS position, and satellite counts.

## Camera stream

The camera service uses the rear Camera2 device and Android's hardware H.264 encoder. The initial v0.5.0 stream is:

- 1280x720
- 30 FPS target
- H.264/AVC
- approximately 3 Mbps
- MPEG-TS transport with PTS/PCR timing
- no audio
- one video client at a time

The app can bind the camera endpoint to localhost, Wi-Fi, or the active cellular IPv4 interface. Port `8767` is dedicated to video.

Video stream:

```text
GET /video
Content-Type: video/mp2t
```

Camera status:

```text
GET /status
```

For localhost operation on the phone:

```bash
ffplay http://127.0.0.1:8767/video
```

For Wi-Fi or cellular operation, replace `127.0.0.1` with the address shown by the app for the selected interface. Remote cellular reachability still depends on the carrier/network path.

A short stream capture in Termux should use Termux's writable temporary directory rather than Android's `/tmp`:

```bash
curl --max-time 5 http://127.0.0.1:8767/video -o "$PREFIX/tmp/camera.ts"
ffprobe "$PREFIX/tmp/camera.ts"
```

## Build

The project targets Android API 34 and Java 17, matching `mrtf-android-buildenv`.

```bash
./gradlew assembleDebug
```

The debug APK is produced under:

```text
app/build/outputs/apk/debug/
```

Pushes to `main`, pull requests, and manual workflow runs build and validate the APK. Version tags also create a GitHub release after verifying that the tag matches the Gradle version.

## OpenRoadCode integration

The corresponding Termux-side hardware adapters and ZeroMQ publisher live in the main OpenRoadCode repository. See `docs/android_sensor_pipeline.md` there for the sensor build, run, and diagnostic procedure. Camera consumption belongs behind an OpenRoadCode camera/video controller so UI code does not need to know the bridge transport details.
