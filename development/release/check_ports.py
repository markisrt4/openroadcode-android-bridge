#!/usr/bin/env python3
"""Check companion listener port ownership and configured clients without binding sockets."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
registry = json.loads((ROOT / "config/ports.json").read_text())
ports = registry["ports"]
numbers = [item["port"] for item in ports]
names = [item["name"] for item in ports]
assert len(numbers) == len(set(numbers)), "Port collision in companion registry"
assert len(names) == len(set(names)), "Duplicate service name in companion registry"
for item in ports:
    port = item["port"]
    assert isinstance(port, int) and 1024 <= port <= 65535, f"Invalid port: {port}"
    owner = ROOT / "app/src/main/java/org/openroadcode/androidbridge" / (item["owner"] + ".java")
    assert owner.exists(), f"Missing listener owner: {owner}"
    source = owner.read_text()
    assert re.search(r"\b(?:PORT|DEFAULT_PORT)\s*=\s*" + str(port) + r"\b", source), (
        f"Port {port} not declared in {owner}"
    )
cli = (ROOT / "development/termux/orc_network_info.py").read_text()
assert "127.0.0.1:18766/network/diagnostics" in cli, "Termux CLI port mismatch"
assert any(x["name"] == "android_network_diagnostics" and x["port"] == 18766 for x in ports)
print("Companion port registry and diagnostics client verified.")
