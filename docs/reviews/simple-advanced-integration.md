# Main integration and Simple/Advanced modes

Base: main `611162b`. Candidate: `1.0.0-rc.2`, version code 35.

## Branch coverage

Main already contained companion/SDR RC1, performance, live logs, UI polish,
master and the log-shutdown fix. The remaining branches integrated here are:

- `feature/sms-messaging-bridge` (`298fbc6`): opt-in authenticated SMS gateway,
  permissions, persistent request IDs and status callbacks, read-only contacts,
  and explicit local credential provisioning.
- `host-actions` (`92c11b4`): foreground app/website launches, bounded requests,
  POI app visibility and Android validated network state.
- `injector-dashboard` (`765371d`): development simulation controls and weather
  scenario state/endpoints. Kept the existing registration/polling fixes.

Merge commits preserve branch ancestry. The host-action and RTL-TCP provider
control listeners both previously bound 8772. They now share one foreground
host-control listener: `/health`, `/launch/package`, `/open/uri` and
`/rtl-tcp/start`. Existing client addresses and routes are unchanged. No new
port was allocated. Network state and environmental injector routes both survive.

Incoming SMS `readNBytes` calls were replaced with bounded minSDK-compatible reads.
Telephony is optional in the manifest. SMS status distinguishes the enabled
preference from whether its local listener has actually bound.

## Modes

- Default Simple mode: ORC start/stop and connection on Home, plain-language
  feature actions, larger buttons and a device chooser including this phone.
- Advanced mode: existing target/source/service controls, simulations, raw
  readings, network details, SMS provisioning, Performance and Live Logs.
- Mode is saved independently of service and pairing configuration. Switching
  does not start/stop phone hardware, change saved input sources or request
  permissions. Simulated sources remain saved and are described as demos.
- SMS remains opt-in. Optional contacts/send permissions and provisioning remain
  available in Advanced mode. Simple mode never silently grants or enables them.
- ORC start/stop operates on the selected runtime core stack. The previously
  proposed master switch for all sensors/services is still separate work.

## Validation

57 JVM tests pass, including saved-mode/default/round-trip checks and bounded
shared-listener request tests (host/SDR routes, UTF-8 byte lengths, truncated
bodies, duplicate/malformed lengths, transfer encoding and oversized headers).
Debug APK builds; installer syntax and APK signature/metadata checked.
Lint has nine pre-existing errors, with no new error types relative to the
original main baseline. No release tag is created by this change.

Physical phone/USB/audio/SMS permission behavior and final visual appearance
remain device-dependent and have not been verified from this cloud environment.

## SMS start and status follow-up

Simple mode's Messages card now names its visible **Enable messages** action in
permission/start feedback and distinguishes a saved enable preference from a
running listener. A granted read-permission request continues the user's pending
Enable action without requiring a second tap; denial and Turn off clear that
pending action. This is the same gateway/start path in both modes. First-time
Termux credential provisioning remains in Advanced mode and is separate from
selecting a remote ORC runtime.

`SmsGatewayPresentationTest` checks mode-aware action text and enabled-but-not-
listening status. APK CI builds/tests the exact feature commit. Device checks
remain necessary: start in Simple mode with permission initially denied, grant
from Enable messages, confirm listener startup without a second tap, deny and
retry, stop, and toggle modes. Starting/stopping message access sends no SMS.
