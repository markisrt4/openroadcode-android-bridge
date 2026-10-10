# Companion and SDR integration

Integration branch: `review/companion-sdr-integration`.

Parents: companion `099f1d8d78a30b3e7b4f393e4c6efc9da0dc8c25`,
SDR `f3133f5` (`media-rtl-sdr-bridge`). Main is unchanged.
Version: `1.0.0-rc.1`, code 34. This is a candidate build, not a release tag.

## Resolution

- Kept the companion dashboard, half-width Environment, dedicated Radio route,
  simplified headings and expandable details. Replaced the SDR branch's separate
  Media receiver card with receiver details on the existing Radio card.
- Preserved the SDR transport, synchronous 64 KiB streaming, shared USB claims,
  recovery, control history and persisted failure diagnostics.
- Added PCM output to Media and the localhost RTL-TCP provider control service.
- Combined authenticated bridge registration with the paired-unit client and its
  existing response bounds and credential redirect protection. Preserved all client
  tests and added the registration test using the shared socket fixture.
- Kept status polling independent of registration success; removed duplicate
  registration when selecting a remote, and rejected action completions for an
  inactive screen or changed target.
- Preserved the manual radio stop guard, including late permission responses.
  Proxy startup checks this guard; connection waits check it before reopening.
  Proxy destruction clears the startup flag, allowing later retry.
- USB reset now disconnects without unregistering the permission receiver and
  clears claim counts for the replaced connection.
- Fixed the incoming provider's API-33-only URLDecoder overload to work on minSDK
  26, bounded its form body, and waited for the visible activity's provider launch
  so launch errors reach the caller rather than crashing an asynchronous UI task.
- Installer requires a build for the exact checked-out commit; it cannot silently
  install an older successful APK.

## Validation

36 JVM unit tests pass. Debug APK builds. Lint retains main's 10 existing errors;
the two added API compatibility errors were fixed. Installer shell syntax checked.
USB streaming, AudioTrack output and external driver launching require a phone.

## Phone checklist before release tagging or main merge

1. Check Home and each feature page, including receiver details under Radio and
   capture/output controls under Media. Confirm Performance and Live Logs remain.
2. Connect the receiver, accept permission, stream for several minutes and inspect
   counters/termination diagnostics. Disconnect during streaming and reconnect.
3. Disconnect while USB permission is pending, then grant permission: the receiver
   must stay stopped. Verify retry after a stream failure and USB detach/reattach.
4. Switch among Termux and multiple paired computing units. Verify registration,
   service status updates, ADS-B actions, unavailable/older runtime behavior, and
   stale replies after switching targets or leaving a screen.
5. Start/stop camera, playback capture and PCM output. Verify phone/Bluetooth audio
   routing, stop/restart, and background behavior.
6. With the external RTL-TCP driver installed, request launch while Companion is
   visible; verify background requests return foreground-required guidance.
7. Resolve the known permission/notification lint errors before the final release.
   The master activity switch remains separate unfinished work; text input remains
   deferred.
