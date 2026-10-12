# Video subsystem and unavailable controls — RC4

Base: main `85243a5`. Version `1.0.0-rc.4`, code 37.

- Unavailable action labels use a stateful grey text color; available labels remain
  white. Applied to shared action buttons and the custom Bluetooth buttons. Vehicle
  Start/Stop now correctly disable the unavailable action; camera controls initialize
  their availability before the first status response.
- Video is a separate half-width Home card in both modes, paired with Environment.
  The six feature cards retain the existing two-column layout. Video has a camera
  icon; Media uses an audio icon.
- Camera controls and preview moved from Media to Video. Media contains playback
  capture and audio output. Its Off switch stops audio services, leaving video alone.
- Simple Video On requests camera permission before starting. Off cancels the
  pending request and stops camera sharing; a later permission grant does not restart
  a cancelled request. Its switch observes the active camera service's state and uses
  the existing startup/permission/failure feedback model. Source is Phone camera.
- Existing camera interface, camera selection, endpoints and consent handling are
  retained. No source setting or network port is changed.

70 JVM tests pass; debug APK builds and signature verifies. Lint retains the same
nine pre-existing errors. Installer syntax checked. Native appearance and camera
permission/lifecycle behavior have not been exercised on a physical device here.
