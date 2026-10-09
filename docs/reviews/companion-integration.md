# Android bridge performance and live logs integration review

Repository: `markisrt4/openroadcode-android-bridge`.
Integration branch: `review/companion-integration`.
Base main: `13b114a88ca0bb386d54a336380376dbad7d74f7`.
Performance source: `computing-unit-performance` at `cd87e6d`.
Logs source: `feature/live-log-viewer` (one commit ahead of the same main).

## Integrated behavior

The dashboard retains Configuration, Runtime, and Performance, and adds a
separate Live Logs entry. Both performance and logs can select Local Termux or
an individually named paired remote computing unit. Log selection uses the same
saved device endpoint and credential as runtime and performance selection.
Performance and log polling stop when leaving their screen or backgrounding the
activity; stale responses are rejected. Logs retain their existing bounded
history, severity/component filters, pause/resume, copy, and share behavior.

This review does not implement the proposed ORC Companion rename, home-screen
redesign, master activity toggle, or text input.

## Findings fixed

1. Dashboard and activity merge conflicts would lose one feature if either side
   were accepted wholesale. Preserve all routes and lifecycle hooks.
2. The logs branch only exposed Termux/one generic remote. Adapt its selector to
   the performance branch's saved-device model and named units.
3. The performance branch removed the existing remote sensor sharing control.
   Restore it under Configuration, including restarting an enabled sensor bridge
   after changing the network setting and reporting restart failures.
4. The shared runtime client parsed error bodies before classifying errors. An
   HTML/plain-text 404 or 401 therefore hid upgrade/pairing guidance. Classify
   these statuses first, retaining bounded JSON error details for other actions.
5. Runtime requests followed redirects and read unbounded bodies. Disable
   redirects, limit responses to 2 MiB, close streams, and disconnect even when
   request setup/output fails. The logs client already had these protections.
6. Performance could label a negative sample age as LIVE. Reject negative,
   missing, invalid, stale, errored, or unsupported samples.
7. Replace the log list's touch listener with native touch dispatch to preserve
   click/accessibility behavior. Reuse the trend drawing path instead of
   allocating a new path on every draw.

## Automated validation

Java 17, Gradle 8.7, Android SDK 34/build tools 34.0.0.

- `testDebugUnitTest`: 29 tests passing, zero failures/errors.
- `assembleDebug`: debug APK builds successfully.
- `apksigner verify --verbose`: APK signature verifies (v2 scheme).
- `aapt dump badging`: package `org.openroadcode.androidbridge`, version code 30,
  base version 0.9.0, minimum API 26, target API 34.
- `git diff --check`: clean.
- `lintDebug`: fails with 10 existing errors, also reproduced on untouched main.
  The errors concern Bluetooth/audio permission checks, foreground-service
  notification permissions, and an optional-camera feature declaration. These
  files are unchanged by the integration. Remaining warnings include the app's
  existing literal-string/localization conventions.

Added regression coverage checks performance routing/credentials, redirects,
plain-text error responses, response bounds, telemetry freshness, legacy pairing
migration, switching endpoints and credentials together, repairing saved units,
deleting the selected unit, and corrupt saved configuration. Existing log tests
cover buffering/rotation, malformed pages, limits, filters/credentials, and
stale polling completions.

## Phone and runtime verification still required

No phone/emulator or live paired runtime was available for this review. Unit
checks and APK compilation do not establish the following device behavior:

- Open Performance and Live Logs from Home on a narrow screen and with enlarged
  text; check scrolling, selector labels, trend display, and back navigation.
- Select Termux and at least two paired remotes; verify the displayed data comes
  from the selected unit and no prior unit's data arrives after switching.
- Pause/resume logs, change severity/component filters, rotate the log store,
  disconnect/reconnect the runtime, and copy/share the displayed history.
- Background/reopen both screens; verify polling stops/resumes appropriately.
- Exercise browser pairing, legacy configuration migration on upgrade, and
  forgetting/repairing devices against a current ORC service manager.
- Toggle LAN sensor sharing while the sensor bridge is enabled; verify the
  endpoint rebinds and readings continue. Check recovery after permission denial.
- Verify missing `/logs` and `/performance` APIs show upgrade guidance and revoked
  pairing shows Configuration guidance.

Recommendation: the integrated build is ready for a phone smoke test. Main has
not been changed or pushed; the existing lint errors remain a separate cleanup.
