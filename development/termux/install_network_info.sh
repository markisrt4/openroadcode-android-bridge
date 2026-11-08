#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${PREFIX:-/data/data/com.termux/files/usr}/bin/orc-network-info"
SOURCE="$SCRIPT_DIR/orc_network_info.py"
[[ -f "$SOURCE" ]] || { echo "Missing $SOURCE" >&2; exit 1; }
mkdir -p "$(dirname "$TARGET")"
printf '#!/data/data/com.termux/files/usr/bin/bash\nexec python3 %q "$@"\n' "$SOURCE" > "$TARGET"
chmod 755 "$TARGET"
echo "Installed $TARGET"
echo "Run: orc-network-info  (or: orc-network-info --json)"
