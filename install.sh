#!/usr/bin/env bash
# Installs Handheld Forge for the current user (no root needed) and adds a menu entry.
# Works on immutable systems like Bazzite and SteamOS because nothing touches /usr.
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/handheld-forge"
VENV="$APP_DIR/venv"
BIN="$HOME/.local/bin"
DESKTOP="${XDG_DATA_HOME:-$HOME/.local/share}/applications/handheld-forge.desktop"

command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }
command -v pkexec  >/dev/null || echo "Warning: pkexec not found. Writing drives needs polkit."

echo "Creating environment in $VENV"
python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet --upgrade pip
"$VENV/bin/pip" install --quiet "$SRC"

mkdir -p "$BIN" "$(dirname "$DESKTOP")"
ln -sf "$VENV/bin/handheld-forge" "$BIN/handheld-forge"

cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=Handheld Forge
Comment=Create a handheld OS boot drive in one click
Exec=$VENV/bin/handheld-forge
Icon=drive-removable-media-usb
Terminal=false
Categories=System;Utility;
EOF

echo "Installed. Open 'Handheld Forge' from your app menu, or run: handheld-forge"
