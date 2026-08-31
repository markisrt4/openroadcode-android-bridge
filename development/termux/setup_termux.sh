#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROPERTIES_DIR="$HOME/.termux"
PROPERTIES_FILE="$PROPERTIES_DIR/termux.properties"
PROPERTY="allow-external-apps"
VALUE="true"

mkdir -p "$PROPERTIES_DIR"
touch "$PROPERTIES_FILE"

if grep -qE "^[[:space:]]*${PROPERTY}=" "$PROPERTIES_FILE"; then
    sed -i -E "s|^[[:space:]]*${PROPERTY}=.*|${PROPERTY}=${VALUE}|" "$PROPERTIES_FILE"
else
    printf '\n%s=%s\n' "$PROPERTY" "$VALUE" >> "$PROPERTIES_FILE"
fi

echo "Configured: ${PROPERTY}=${VALUE}"
echo "File      : $PROPERTIES_FILE"

if command -v termux-reload-settings >/dev/null 2>&1; then
    termux-reload-settings
    echo "Termux settings reloaded."
else
    echo "termux-reload-settings is unavailable. Fully close and reopen Termux before installing APKs."
fi

echo
echo "Termux is configured to share APKs with Android Package Installer."
echo "Android may also require 'Allow from this source' for Termux under Install unknown apps."
