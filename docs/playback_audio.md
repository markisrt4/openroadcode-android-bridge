# Android playback audio

The Playback Audio service is an independent, opt-in source for OpenRoadCode music analysis. It uses Android AudioPlaybackCapture (API 29+) and MediaProjection. The native service owns permission and capture lifecycle; OpenRoadCode owns the analyzer, calibration, and consumers.

## Start on the phone

Install a current debug APK and open the Android Bridge. In the Android Playback Audio card, press START PLAYBACK CAPTURE, grant RECORD_AUDIO permission if requested, and approve Android's capture prompt. The notification provides a Stop capture action. Consent is required for every new projection session. The bridge never starts playback capture automatically on boot or when the sensor service starts.

The service binds only to IPv4 localhost, port 8768. It does not expose a remote audio endpoint, record the microphone, or save audio to files. The stream is intended for a local Termux OpenRoadCode process. Stopping the ORC consumer does not revoke the Android projection; stop the native service through its card or notification when finished.

Android can capture only eligible playback from applications in the same user profile. Applications can opt out, DRM-protected content may be unavailable, and the operating system can revoke projection at any time. Silence from a particular app is not evidence that the PCM transport is broken. Test first with a capture-permitted audio source. This feature does not bypass Android capture restrictions.

## HTTP interface

`GET http://127.0.0.1:8768/status` returns a JSON object with `running`, `source`, `sample_rate_hz`, `channels`, `format`, `frames`, and `error`. The service must already be running; connection refused means no native capture service is listening.

`GET http://127.0.0.1:8768/stream` returns `application/octet-stream`. One streaming client is supported. A second client receives HTTP 409. The service sends the latest PCM blocks and may drop old blocks if a consumer cannot keep up, favoring live visualization over a growing latency backlog.

The ORCA v1 payload begins with a 12-byte header: four ASCII bytes `ORCA`, a little-endian uint32 sample rate, and a little-endian uint32 channel count. The remainder is raw interleaved signed 16-bit little-endian PCM, currently mono at 48,000 Hz. No file container, length prefix, or audio compression is used. The consumer must accumulate short reads and preserve sample alignment.

## OpenRoadCode

On Android/Termux, `apps.webUi.main` registers `android-playback` alongside the browser microphone and any available Linux capture backend. The source uses `AndroidPlaybackAudioCapture`, which implements `AudioCaptureIf` and feeds the existing `MusicAnalysisSession`. The native service must be started and consented before selecting START AUDIO in WebUI. Selecting STOP AUDIO only disconnects the consumer.

```bash
cd ~/src/OpenRoadCode
git fetch
git switch music-visualizer-refresh
git pull
source venv/bin/activate
PYTHONPATH="$PWD" python -m apps.webUi.main
```

Open `http://127.0.0.1:5000/menu/media/music_visualizer` in the phone browser, select `android-playback`, and start audio. The browser UI's calibration and sensitivity controls work through the same shared analyzer as PipeWire. For calibration, pause music, collect several seconds of background noise, finish calibration, and resume playback. A source change clears the previous noise profile.

## Diagnostics

```bash
curl -s http://127.0.0.1:8768/status
```

For a short live PCM check without writing an audio file:

```bash
python - <<'PY'
import struct, urllib.request
import numpy as np
with urllib.request.urlopen('http://127.0.0.1:8768/stream', timeout=3) as r:
    h = r.read(12)
    print(struct.unpack('<4sII', h))
    for _ in range(10):
        b = r.read(4096)
        x = np.frombuffer(b[:len(b) & ~1], dtype='<i2').astype(np.float32) / 32768
        if x.size:
            print('peak=', float(np.max(np.abs(x))), 'rms=', float(np.sqrt(np.mean(x*x))))
PY
```

Do not run this check while WebUI is consuming the stream because only one client is supported. For native failures inspect `adb logcat -s ORCPlayback`. If the service is running but samples are all zero, test a different capture-permitted playback application and verify the apps are in the same Android user profile.

## Validation

The Android project uses Java 17 and API 34. Run `gradle testDebugUnitTest assembleDebug` or the repository's configured Gradle wrapper. CI executes the unit tests, builds the debug APK, verifies metadata and signature, and uploads the APK artifact. ORC's adapter tests are in `controllers/audio/unit_test/test_android_playback_audio_capture.py` and cover short reads, PCM normalization, invalid headers, loopback-only transport, and shared analyzer integration. Real-device projection consent and playback eligibility require device testing.
