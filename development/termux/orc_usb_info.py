#!/usr/bin/env python3
"""Inspect Android USB host devices through the ORC Companion diagnostics service."""
import argparse
import json
import sys
import urllib.request

DEFAULT_URL = "http://127.0.0.1:18766/network/diagnostics"
USB_CLASSES = {
    0: "Per-interface", 1: "Audio", 2: "Communications", 3: "HID",
    6: "Imaging", 7: "Printer", 8: "Mass storage", 9: "Hub",
    10: "CDC data", 11: "Smart card", 14: "Video", 224: "Wireless",
    239: "Miscellaneous", 255: "Vendor-specific",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Show USB inventory as JSON")
    parser.add_argument("--url", default=DEFAULT_URL, help="Override Companion diagnostics URL")
    args = parser.parse_args()
    try:
        with urllib.request.urlopen(args.url, timeout=5) as response:
            report = json.load(response)
    except (OSError, ValueError) as error:
        sys.exit(f"ORC Companion unavailable: {error}\n"
                 "Open the Companion app and check http://127.0.0.1:18766/health")
    if not isinstance(report, dict) or "error" in report:
        sys.exit(f"Companion diagnostics error: {report.get('error', 'invalid response') if isinstance(report, dict) else 'invalid response'}")
    devices = report.get("usb_devices")
    if not isinstance(devices, list):
        sys.exit("Companion response is missing the USB device inventory.")
    if args.json:
        print(json.dumps(devices, indent=2))
        return
    print(f"USB devices reported by Android: {len(devices)}")
    if not devices:
        print("No USB host devices exposed to the Companion app.")
        print("System-managed USB devices may not appear in this inventory.")
        return
    for index, device in enumerate(devices, 1):
        vendor = int(device.get("vendor_id", 0))
        product = int(device.get("product_id", 0))
        device_class = int(device.get("device_class", 0))
        print(f"\n[{index}] {vendor:04x}:{product:04x}")
        print(f"  Path: {device.get('device_name', 'unknown')}")
        print(f"  Class: {device_class} ({USB_CLASSES.get(device_class, 'Other')})")
        print(f"  Subclass: {device.get('device_subclass', 'unknown')}")
        print(f"  Interfaces: {device.get('interface_count', 'unknown')}")
        print(f"  Android USB permission: {'granted' if device.get('permission_granted') else 'not granted'}")


if __name__ == "__main__":
    main()
