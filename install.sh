#!/usr/bin/env bash
# Installs or updates Handheld Forge for the current user (no root needed) and adds a
# menu entry. Works on immutable systems like Bazzite and SteamOS because nothing
# touches /usr.
#
#   ./install.sh               install, or update to the latest code
#   ./install.sh --uninstall   remove it again (add --keep-downloads to keep images)
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/handheld-forge"
VENV="$APP_DIR/venv"

if [ "${1:-}" = "--uninstall" ]; then
    shift
    if [ -x "$VENV/bin/handheld-forge" ]; then
        exec "$VENV/bin/handheld-forge" uninstall --yes "$@"
    fi
    # No working install left: remove whatever pieces remain.
    rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/applications/handheld-forge.desktop"
    [ -L "$HOME/.local/bin/handheld-forge" ] && rm -f "$HOME/.local/bin/handheld-forge"
    rm -rf "$APP_DIR"
    [ "${1:-}" = "--keep-downloads" ] || rm -rf "${XDG_CACHE_HOME:-$HOME/.cache}/handheld-forge"
    echo "Handheld Forge is uninstalled."
    exit 0
fi

command -v python3 >/dev/null || { echo "python3 is required"; exit 1; }
command -v gdbus   >/dev/null || echo "Warning: gdbus not found (part of glib2). It is needed to prepare drives."

# Running this from an old clone would reinstall the old code, so fetch the latest first.
if [ -d "$SRC/.git" ] && command -v git >/dev/null; then
    echo "Getting the latest code"
    git -C "$SRC" pull --ff-only --quiet || echo "Warning: couldn't update $SRC with git; installing it as it is."
fi

echo "Installing into $VENV"
[ -x "$VENV/bin/python" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet --disable-pip-version-check --upgrade pip
"$VENV/bin/pip" install --quiet --disable-pip-version-check "$SRC"
# Reinstall the app itself even if its version number didn't change.
"$VENV/bin/pip" install --quiet --disable-pip-version-check --force-reinstall --no-deps "$SRC"
"$VENV/bin/handheld-forge" setup-menu >/dev/null

VERSION="$("$VENV/bin/python" -c 'import handheld_forge; print(handheld_forge.__version__)')"
echo "Installed Handheld Forge $VERSION. Open it from your app menu, or run: handheld-forge"
echo "To update later, open the version menu in the app (top right). To remove it: ./install.sh --uninstall"
