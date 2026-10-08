# ORC Companion

Android hardware bridge for OpenRoadCode running either on the same phone or another device on the selected network.

The foreground services expose Android sensors, GNSS, Bluetooth SPP devices, and the rear camera through small HTTP-facing interfaces. OpenRoadCode owns the normalized application contracts and higher-level messaging architecture.

## Companion home

The launcher is named **ORC Companion**. Home is ordered Your Runtime, Manage,
Features, then Monitor. Your Runtime shows connection and running-service status;
Manage has the single Runtime entry and Configuration. Feature cards use distinct
accent colors and group the controls with their data:

- Navigation: navigation service start/stop, input source, GPS/motion, sensor sharing.
- Automotive: automotive service start/stop, input source, Bluetooth/OBD.
- Radio: local RTL-SDR USB connect/disconnect, expandable receiver diagnostics, and the selected runtime's ADS-B service.
- Media: camera, playback capture, and PCM audio output through Android speakers/Bluetooth.
- Environment: ambient light and pressure.

Runtime owns computing-unit selection, the message broker, and whole-core-stack
actions. Configuration owns pairing and saved computing units. Monitor contains
Performance and Live Logs. When Navigation starts with Android Bridge input on a
remote runtime, sensor sharing is enabled for the local network; the sharing
control remains available in Navigation. Location permission must be granted
before navigation start is sent. Home status polls every five seconds only while
Home is visible; feature service polling also stops when leaving the screen.

Home uses a consistent set of line icons and subtle accent colors. Environment
uses the same half-width as the other feature cards. Feature pages keep current
status and primary actions visible; source changes, motion readings, sensor
sharing, stream settings, audio details, and log filters expand on demand.

The master activity switch and text input are not implemented in this revision.

The `review/companion-sdr-integration` branch combines the companion layout with
the SDR branch as a **1.0.0-rc.1** candidate build. It has not been tagged or merged
to main. See [SDR integration review](docs/reviews/companion-sdr-integration.md)
for validation and the phone checklist.

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
## Computing-unit performance

With an updated OpenRoadCode service manager, open **Performance** from the subsystem dashboard, select the
local Termux runtime or a paired remote computing unit using **Computing unit**,
and view **Computing Unit Performance**. Runtime contains service controls only. The card prioritizes combined ORC workload and per-process CPU, RSS/PSS memory,
thread counts, and disk activity, plus observed sensor telemetry freshness,
rates, and invalid-message counts. Host CPU, memory, thermal, storage, and
network activity remain available with two-minute trends. Diagnostics processes
are identified separately and excluded from workload totals. It uses the existing pairing and
endpoint. Polling stops when you leave Performance or pause the app; unavailable
and stale readings are cleared. An older computing unit reports that its
service manager needs updating.


If Local Termux reports that performance monitoring is unavailable, update the
main OpenRoadCode checkout to `computing-unit-performance` and restart its Python
service manager; installing this APK does not replace the computing-unit service:

```bash
cd ~/src/OpenRoadCode
git fetch origin computing-unit-performance
git switch computing-unit-performance
git merge --ff-only FETCH_HEAD
sv restart openroadcode-service-manager
```

If runit still points to an older checkout, reinstall the version-controlled
definitions with `bash scripts/runit/install_termux_services.sh`, then restart
`openroadcode-service-manager`. Pairing and configured targets stay saved.

Termux battery status is shown separately in Performance: temperature in °C,
charge percentage, Android health, charging state, and plugged state. The
computing unit needs the Termux:API companion app and `termux-api` CLI package.
Battery polling runs every 30 seconds independently of CPU sampling; stale or
unavailable values stay unavailable. Battery temperature is never CPU temperature.

Performance uses four tabs: **Workload**, **System**, **Sensors**, and **Services**.
Summary tiles show the main readings. Process and stream rows show short names
and color-coded states; tap a row for full names, sources, counters, and details.
Service endpoints are grouped by process and protocol; tap a group for each
endpoint's bandwidth and queues. **About these readings** contains measurement
notes. Two-minute charts are available through **Show trends**, collapsed by
default. Live values update in place so reading or opening details is not
interrupted by rebuilding the list every second.
