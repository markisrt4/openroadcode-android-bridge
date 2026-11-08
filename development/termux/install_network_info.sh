#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIR="${PREFIX:-/data/data/com.termux/files/usr}/bin"
mkdir -p "$BIN_DIR"

install_cli() {
  local source="$SCRIPT_DIR/$1"
  local target="$BIN_DIR/$2"
  [[ -f "$source" ]] || { echo "Missing $source" >&2; exit 1; }
  {
    printf '#!%s/python3\n' "$BIN_DIR"
    tail -n +2 "$source"
  } > "$target"
  chmod 755 "$target"
  echo "Installed $target"
}

install_cli orc_network_info.py orc-network-info
install_cli orc_usb_info.py orc-usb-info
echo "Commands: orc-network-info, orc-usb-info (both support --json and --help)"
