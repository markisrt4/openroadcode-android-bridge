"""Offline CLI regression tests. Run on GitHub Actions Ubuntu, not Codex."""
import contextlib
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
from types import SimpleNamespace

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "termux" / "orc_usb_info.py"
spec = importlib.util.spec_from_file_location("orc_usb_info", SCRIPT)
usb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(usb)


class UsbCliTests(unittest.TestCase):
    def options(self, **changes):
        values = dict(json=False, verbose=False, export=None)
        values.update(changes)
        return SimpleNamespace(**values)

    def capture(self, report, **options):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            usb.display(report, self.options(**options))
        return output.getvalue()

    def test_empty_inventory_explains_limits(self):
        result = self.capture({"usb_devices": [], "system_managed_networks": [],
                               "usb_sysfs": {"status": "unavailable_or_restricted", "devices": []}})
        self.assertIn("may be hidden", result)
        self.assertIn("unavailable_or_restricted", result)

    def test_ethernet_not_misidentified_as_usb(self):
        result = self.capture({"usb_devices": [], "system_managed_networks": [
            {"interface": "eth0", "active": True, "addresses": ["192.0.2.2/24"]}],
            "usb_sysfs": {"status": "no_readable_device_metadata", "devices": []}})
        self.assertIn("USB backing: unconfirmed", result)

    def test_interface_details_and_sysfs(self):
        result = self.capture({"usb_devices": [{"vendor_id": 3034, "product_id": 1,
            "interface_count": 1, "interfaces": [{"id": 0, "class": 3, "subclass": 1,
            "protocol": 2, "endpoint_count": 1}]}],
            "system_managed_networks": [],
            "usb_sysfs": {"status": "partial_or_available", "devices": [
                {"vendor_id_hex": "0bda", "product_id_hex": "8153", "speed": "5000",
                 "bMaxPower": "224mA"}]}}, verbose=True)
        self.assertIn("Interface 0:", result)
        self.assertIn("5000 Mb/s", result)

    def test_json_and_export(self):
        report = {"usb_devices": [], "system_managed_networks": [],
                  "usb_sysfs": {"status": "unavailable_or_restricted", "devices": []}}
        with tempfile.TemporaryDirectory() as directory:
            target = pathlib.Path(directory) / "report.json"
            output = self.capture(report, json=True, export=str(target))
            self.assertEqual(json.loads(output)["usb_devices"], [])
            self.assertEqual(json.loads(target.read_text()), report)

    def test_missing_usb_list_is_error(self):
        with self.assertRaises(SystemExit):
            self.capture({"system_managed_networks": []})


if __name__ == "__main__":
    unittest.main()
