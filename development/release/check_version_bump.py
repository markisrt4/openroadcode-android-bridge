#!/usr/bin/env python3
"""Enforce Android version increments against the pull request base revision."""
import re
import subprocess
import sys
from pathlib import Path

GRADLE = "app/build.gradle"
APP_PREFIX = "app/src/"


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def versions(source):
    code = re.search(r"(?m)^\s*versionCode\s+(\d+)\s*$", source)
    name = re.search(r"(?m)^def baseVersion = '(\d+)\.(\d+)\.(\d+)'\s*$", source)
    if not code or not name:
        raise ValueError("Missing versionCode or semantic baseVersion in app/build.gradle")
    return int(code.group(1)), tuple(int(name.group(i)) for i in range(1, 4))


def main():
    if len(sys.argv) != 2:
        sys.exit("Usage: check_version_bump.py <base-ref>")
    base = sys.argv[1]
    changed = git("diff", "--name-only", f"{base}...HEAD").splitlines()
    if not any(path.startswith(APP_PREFIX) for path in changed):
        print("No Android app source/resource changes; version bump not required.")
        return
    old_code, old_name = versions(git("show", f"{base}:{GRADLE}"))
    new_code, new_name = versions(Path(GRADLE).read_text())
    if new_code <= old_code or new_name <= old_name:
        sys.exit(
            f"Android version bump required: base {old_name} ({old_code}), "
            f"head {new_name} ({new_code}). Increment BOTH baseVersion and versionCode."
        )
    print(f"Android version gate passed: {old_name} ({old_code}) -> {new_name} ({new_code})")


if __name__ == "__main__":
    main()
