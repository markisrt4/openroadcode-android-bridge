#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${PREFIX:-/data/data/com.termux/files/usr}/bin/orc-network-info"
SOURCE="$SCRIPT_DIR/orc_network_info.py"
[[ -f "$SOURCE" ]] || { echo "Missing $SOURCE" >&2; exit 1; }
mkdir -p "$(dirname "$TARGET")"
# Install a standalone executable. The source checkout can be moved or deleted.
{
  printf '#!%s/bin/python3\n' "${PREFIX:-/data/data/com.termux/files/usr}"
  tail -n +2 "$SOURCE"
} > "$TARGET"
chmod 755 "$TARGET"
echo "Installed standalone command: $TARGET"
echo "Run: orc-network-info  (or: orc-network-info --json)"
