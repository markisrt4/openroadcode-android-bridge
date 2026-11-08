# Network & USB diagnostics

The Companion provides a read-only Android-native network and USB inventory in the expandable **Network & USB Diagnostics** dashboard card.

## Local API

The independent loopback listener is owned by `NetworkDiagnosticsHttpServer`, started and stopped with the always-on `AndroidHostActionService` lifecycle. It is **not** dependent on navigation, GPS, or the sensor bridge.

- `http://127.0.0.1:18766/health`
- `http://127.0.0.1:18766/network/diagnostics`

The legacy sensor bridge retains `8766`. The host-actions service retains `8772`. Network diagnostics binds only to `127.0.0.1`, not the device LAN address.

The source of truth for Companion-owned listener registrations is `config/ports.json`. The project-wide port IDD lives in `OpenRoadCode/docs/ethernet_idd.md` and must be kept synchronized when the OpenRoadCode repository is updated.

## Termux

From the Android Bridge checkout:

```sh
bash development/termux/install_network_info.sh
orc-network-info
orc-network-info --json
curl -fsS http://127.0.0.1:18766/health
```

The installer creates a launcher in `$PREFIX/bin` pointing to the repository's Python CLI. The checkout must remain in place.

If the CLI reports connection refused, open the Companion app and verify its host service is running. If `/health` succeeds but `/network/diagnostics` fails, check the diagnostics service logs rather than restarting navigation.

USB devices already claimed by Android's system drivers might not appear in the USB device inventory; a recognized Ethernet adapter can still appear as a network transport. Neither empty list alone proves the physical adapter is defective.

## Validation

The GitHub Actions Android workflow runs `development/release/check_ports.py` before Gradle builds. Verify the exact branch commit's build and install its matching APK before device testing.
