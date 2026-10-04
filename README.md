# OpenRoadCode Android Bridge

Android hardware bridge for OpenRoadCode running either on the same phone or another device on the selected network.

The foreground services expose Android sensors, GNSS, Bluetooth SPP devices, and the rear camera through small HTTP-facing interfaces. OpenRoadCode owns the normalized application contracts and higher-level messaging architecture.

## Architecture

```text
Android sensors/GNSS -> Sensor Bridge -> HTTP :8766 -> OpenRoadCode hardware_io -> ZeroMQ
Bluetooth SPP device -> SPP Bridge -----------------> OpenRoadCode
Android rear camera  -> Camera2 -> MediaCodec H.264 -> MPEG-TS -> HTTP :8767 -> OpenRoadCode/video player
```

The bridge is being reorganized around independent service configuration. Each service can be enabled separately and can select an implementation/provider without changing the downstream OpenRoadCode interface. The first configurable service is the Sensor Bridge, with Android Sensors and Simulated Drive providers using the same HTTP endpoints.

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

The Sensor Bridge source is selected in the app. `Android Sensors` uses the phone hardware and GNSS. `Simulated Drive` produces a moving synthetic position plus representative IMU values while preserving the same `/imu`, `/location`, `/health`, and `/stream/imu` interfaces.

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

## Live ORC logs

Open **Diagnostics → Logs** from the dashboard to view recent history and live
updates from Termux or the paired remote Linux runtime. The screen defaults to
the target selected on the Runtime screen. Remote Linux reuses the saved pairing
credential; configure or renew pairing on the Runtime screen.

Choose a minimum severity and an optional dotted component prefix such as
`runtime`, `navigation`, or `media`. **Pause** stops polling and retains history;
**Resume** catches up from the last cursor. Leaving the screen or backgrounding
the app cancels pending requests. Connection failures retry with bounded backoff;
access errors explain how to pair again or update the service manager.

The screen keeps up to 200 events and 128 KiB of text. Large rows show a truncation
marker. **Copy** and **Share** export the displayed history, including diagnostic
context; review it before sending. These actions do not export the entire store.
Filters operate on existing logs and cannot enable DEBUG in a running process.

The ORC service manager must support `GET /logs` on its existing API port `8769`.
Update/restart the Termux service manager or reinstall the Linux service manager
before testing this screen. Termux uses its shared ORC log store. Linux currently
exposes the restricted service-manager account's private store; logs belonging
to other runtime accounts and Android bridge services are outside this feed.
The screen identifies its source. No new Android permissions or foreground
service are required.

Port `8768` belongs to playback audio. The Termux runtime client uses `8769`,
matching the current ORC service-manager launcher.

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
