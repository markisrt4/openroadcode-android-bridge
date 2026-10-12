# Simple subsystem switches — RC3

Base: main `a1ea25e`. Version `1.0.0-rc.3`, code 36.

## Interface

OpenRoadCode is spelled out in the Simple-mode connection summary, core start/stop
buttons and connection setup. Core buttons use full-width rows to accommodate the
name. The app identity remains ORC Companion.

Simple feature cards have switches for phone location sharing, the saved physical
or simulated car connection, USB receiver connection, phone audio playback and
light/pressure sensors. Card taps open their existing detail screens. Advanced
mode retains service/profile controls and simulation setup.

Hardware and Demo data badges describe the saved source. Switches do not change
source selections. Media On starts audio playback, as selected by the user; Off
stops playback, capture and camera sharing. Aircraft tracking remains separate
from the phone's USB receiver control on Radio.

## Lifecycle

- Switch requests show starting/stopping or permission waits until confirmed by
  observed activity. Startup timeouts and denial/failure feedback restore the
  observed state. Rendering does not issue additional toggle actions.
- Location and Bluetooth callbacks honor cancellation before starting hardware.
  Missing saved car adapters require selection on Vehicle; no arbitrary adapter
  is selected. Radio Off persists, including across later USB permission grants
  and app restarts. USB detach removes the connected indication.
- Observation belongs to the active service instance so an older worker finishing
  cannot clear the state of a newly started instance.
- Light/pressure sensors have an independent foreground service. Navigation no
  longer registers those sensors. Both features use the existing HTTP listener;
  the sensor activity plan keeps Environment available without enabling motion or
  requesting location. Stopping the last feature stops the endpoint.
- Android foreground types follow active features/permissions; environment-only
  activity uses special-use on Android 14+, without the location foreground type.
- Environment demo values preserve the existing simulated drive values. Availability,
  timestamps and health counts/rates come from the independent environment activity.
  No network port or client address is changed.

## Verification

70 JVM tests pass, including request acknowledgement, cancellation, timeouts,
permission wait/denial, external activity changes, and environment/navigation
independence for real and demo sources. Debug APK builds; signature, metadata and
installer syntax checked. Lint retains the same nine existing errors.

Native appearance, USB/Bluetooth behavior and Android lifecycle/permission prompts
have not been exercised on a physical device from this cloud environment.
