#!/usr/bin/env bash
# Run on a build workstation/CI, not the Termux phone.
set -euo pipefail
APK="${1:?Usage: apk_evidence.sh APK OUTPUT_DIRECTORY}"
OUT="${2:?Output directory is required}"
test -f "$APK"
command -v aapt >/dev/null
command -v apksigner >/dev/null
mkdir -p "$OUT"
sha256sum "$APK" > "$OUT/apk-sha256.txt"
apksigner verify --verbose --print-certs "$APK" > "$OUT/signature.txt"
aapt dump badging "$APK" > "$OUT/badging.txt"
aapt dump permissions "$APK" > "$OUT/permissions.txt"
unzip -t "$APK" > "$OUT/zip-integrity.txt"
unzip -l "$APK" > "$OUT/contents.txt"
echo "APK review evidence written to $OUT"
