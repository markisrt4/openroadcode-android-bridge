#!/usr/bin/env python3
"""Read Android network/USB diagnostics through the ORC companion HTTP bridge."""
import argparse
import json
import sys
import urllib.error
import urllib.request

URL = "http://127.0.0.1:18766/network/diagnostics"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Print raw formatted JSON")
    parser.add_argument("--url", default=URL, help="Override companion endpoint")
    args = parser.parse_args()
    try:
        with urllib.request.urlopen(args.url, timeout=5) as response:
            report = json.load(response)
    except (OSError, ValueError) as exc:
        sys.exit(f"ORC companion diagnostics unavailable: {exc}\n"
                 "Start the OpenRoadCode companion companion host service (diagnostics port 18766).")
    if "error" in report:
        sys.exit(f"Companion error: {report['error']}")
    if args.json:
        print(json.dumps(report, indent=2))
        return
    networks = report.get("networks", [])
    for label, transport in (("Ethernet", "ethernet"), ("Wi-Fi", "wifi"),
                             ("Cellular", "cellular"), ("VPN", "vpn")):
        matched = [n for n in networks if transport in n.get("transports", [])]
        print(f"{label}: {len(matched)} network(s)")
        for network in matched:
            active = " [active]" if network.get("active") else ""
            print(f"  {network.get('interface') or 'unknown'}{active}")
            print(f"    IP: {', '.join(network.get('addresses', [])) or 'none'}")
            print(f"    DNS: {', '.join(network.get('dns', [])) or 'none'}")
            print(f"    Validated: {'yes' if network.get('validated') else 'no'}")
    devices = report.get("usb_devices", [])
    print(f"USB devices: {len(devices)}")
    for device in devices:
        print(f"  Vendor {device.get('vendor_id')}, product {device.get('product_id')}"
              f", interfaces {device.get('interface_count')}")


if __name__ == "__main__":
    main()
