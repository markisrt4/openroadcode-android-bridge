# Play Protect review and release signing

Reviewed RC6 source at 36f5fd6 and the APK build configuration on 2026-10-12.
This is a scoped source/build review, not a malware certification or a determination
of Google's detection reason. The screenshot says “Harmful app blocked”; it does
not disclose the detected category. Do not disable Play Protect or repeatedly
change package names/certificates to evade classification.

## Findings

- The existing APK is debuggable and uses a publicly committed development key.
  All tagged GitHub releases previously copied that same debug APK. That key is
  unsuitable for production and cannot provide exclusive publisher identity.
- Runtime dependencies are platform APIs only; JUnit and org.json are test-only.
  The inspected source has no dynamic code loader, shell execution, accessibility
  service, device administrator, boot receiver, or notification-listener service.
  This does not prove that a downloaded APK is identical to inspected source.
- READ_SMS, SEND_SMS and READ_CONTACTS support optional ORC messaging. Location,
  camera, microphone, Bluetooth and foreground-service permissions support the
  named hardware features. Removing them would disable existing features; none
  was removed on speculation about Google's detection.
- SMS/contacts access checks Android runtime permissions. SMS requires opt-in
  and a generated bearer token; its listener binds to loopback. Sending also
  checks SEND_SMS and uses persisted request IDs to avoid automatic duplicate sends.
  Review the paired ORC client's user consent flow before asserting that every
  remote send has per-message confirmation: the Android endpoint itself accepts
  authenticated send requests without showing a confirmation dialog.
- Services and the SMS result receiver are not exported. MainActivity is exported
  for the launcher and documented app-launch deep link.
- Sensor sharing can intentionally bind to all interfaces when enabled; camera
  streaming can use a selected network interface. These streams and the localhost
  host-action interface are not all bearer-authenticated. Loopback alone is not
  authentication against other apps on the phone. These are security boundaries
  to review before broader distribution, not confirmed causes of this warning.
- Cleartext HTTP is enabled for local ORC integrations and saved remote endpoints.
  A paired remote device can therefore use HTTP; use HTTPS on untrusted networks.
- No advertising/analytics SDK is declared. Message content and credentials must
  not be included in review evidence, screenshots, or diagnostic uploads.

## Implemented release preparation

`app/build.gradle` supports private release signing through four environment
variables. Partial configuration fails. Without credentials, release assembly
produces an unsigned APK for CI packaging checks, never a development-key fallback.
Release builds are non-debuggable. Normal debug installs retain their existing key.

The separate **Build signed release APK** workflow uses the `android-release`
environment, tests the release variant, requires all private signing secrets and
a certificate fingerprint, verifies signature/non-debuggable status, and uploads
an APK plus evidence. It does not install or automatically publish the APK.
The ordinary workflow now checks unsigned release assembly too and no longer
publishes debug APKs as tagged releases. Private signing has not been provisioned. The signed configuration and evidence
script were locally exercised with the public development key as a test fixture
only (not a production artifact); the release workflow explicitly rejects that
certificate. Debug and release variants each passed 70 unit tests locally.
Normal CI uploads review evidence for its exact debug APK.

## Provisioning and migration

An owner should create and back up a production key in durable private storage
using Android Studio's signing wizard. Do not put the private key, its base64
encoding, passwords, or a secret backup into git, chat, or an issue.
In GitHub Settings → Environments, create `android-release`, restrict allowed refs
and set reviewers appropriate for release access. Add environment secrets:

- ORC_RELEASE_KEYSTORE_BASE64
- ORC_RELEASE_STORE_PASSWORD
- ORC_RELEASE_KEY_ALIAS
- ORC_RELEASE_KEY_PASSWORD

Add environment variable ORC_RELEASE_CERT_SHA256: lowercase hex SHA-256
certificate digest without colons. It is public certificate metadata, not a key.
Keep the production key distinct from the repository's development key.
Run the signed workflow on the reviewed ref once those values are provisioned.

A new signing key cannot ordinarily update this installed package directly.
Do not tell users to uninstall yet: uninstalling loses private app settings,
paired-device credentials and permissions. Plan/export non-secret settings,
re-pair devices and regrant permissions for a one-time fresh installation, or
validate an Android signing-lineage migration separately. No lineage, package ID
change or automatic uninstall is implemented. Development builds remain updatable.

## Appeal packet

Google's form currently requires an email, package name, the SHA-256 of the APK
uploaded to VirusTotal, and supporting information. Use the exact flagged APK
from the user's download or the matching CI artifact, not a local rebuild or a
new release with a different hash. Run `development/release/apk_evidence.sh` on a
workstation with aapt/apksigner/unzip to collect metadata, permissions, signature,
ZIP validation and hash. Upload only the APK (never a keystore or user app data).
An upload/submission has not been made by this review.

Draft supporting text, to complete with exact version/hash and developer identity:

> Please review the Play Protect classification of ORC Companion,
> org.openroadcode.androidbridge, version [exact version], APK SHA-256 [exact hash].
> Installation displays “Harmful app blocked / This app can put your device or data
> at risk.” Source: https://github.com/markisrt4/openroadcode-android-bridge.
> This app connects phone hardware and optional messaging to the user's
> OpenRoadCode runtime. Location, camera, audio and Bluetooth features use Android
> permission/consent APIs. Optional SMS and contacts access uses runtime grants;
> the SMS gateway is loopback-only, opt-in and bearer-authenticated. Authenticated
> ORC clients can request SMS sending when SEND_SMS is granted. The flagged APK
> is a development build signed with the repository's shared development key.
> We are preparing private-key, non-debuggable release builds. Please review this
> exact APK's classification; we do not claim that changing signing removes it.

Attach/reference the exact APK evidence and supplied screenshot. This draft is
not a claim of complete policy compliance or a clean malware verdict.

Official references checked on 2026-10-12:
- https://developers.google.com/android/play-protect/warning-dev-guidance
- https://support.google.com/googleplay/android-developer/contact/protectappeals
- https://developer.android.com/studio/publish/app-signing
