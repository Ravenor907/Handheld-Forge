#!/usr/bin/env bash
# Installs or updates Handheld Forge for the current user (no root needed) and adds a
# menu entry. Works on immutable systems like Bazzite and SteamOS because nothing
# touches /usr.
#
#   ./install.sh               install, or update to the latest code
#   ./install.sh --uninstall   remove it completely, including this folder
#                              (add --keep-downloads or --keep-source to keep those)
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/handheld-forge"
VENV="$APP_DIR/venv"
PIP=(--quiet --disable-pip-version-check)
export PIP_NO_CACHE_DIR=1  # leave nothing in ~/.cache/pip (the env var reaches pip's build helpers too)

if [ "${1:-}" = "--uninstall" ]; then
    shift
    if [ -x "$VENV/bin/handheld-forge" ]; then
        # This copy goes too, even if the app was installed or updated another way.
        echo "$SRC" > "$APP_DIR/source"
        # Lists everything it will delete and asks first.
        exec "$VENV/bin/handheld-forge" uninstall "$@"
    fi
    # No working install left: remove whatever pieces remain, this folder included.
    read -r -p "Remove Handheld Forge completely, including $SRC? [y/N] " answer
    case "$answer" in y|Y|yes) ;; *) echo "Nothing was removed."; exit 0 ;; esac
    rm -f "${XDG_DATA_HOME:-$HOME/.local/share}/applications/handheld-forge.desktop"
    [ -L "$HOME/.local/bin/handheld-forge" ] && rm -f "$HOME/.local/bin/handheld-forge"
    rmdir "$HOME/.local/bin" "${XDG_DATA_HOME:-$HOME/.local/share}/applications" 2>/dev/null || true
    rm -rf "$APP_DIR"
    [[ " $* " == *" --keep-downloads "* ]] || rm -rf "${XDG_CACHE_HOME:-$HOME/.cache}/handheld-forge"
    if [[ " $* " != *" --keep-source "* ]] && [ -f "$SRC/handheld_forge/__init__.py" ] && [ "$SRC" != "$HOME" ]; then
        rm -rf "$SRC"
    fi
    echo "Handheld Forge is completely removed. If this terminal was in that folder, run: cd ~"
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
"$VENV/bin/pip" install "${PIP[@]}" --upgrade pip
"$VENV/bin/pip" install "${PIP[@]}" "$SRC"
# Reinstall the app itself even if its version number didn't change.
"$VENV/bin/pip" install "${PIP[@]}" --force-reinstall --no-deps "$SRC"
"$VENV/bin/handheld-forge" fix-menu || true
# Remember this copy so uninstalling can remove it too.
echo "$SRC" > "$APP_DIR/source"

VERSION="$("$VENV/bin/python" -c 'import handheld_forge; print(handheld_forge.__version__)')"
echo "Installed Handheld Forge $VERSION. Open it from your app menu, or run: handheld-forge"
echo "To update later, open the version menu in the app (top right). To remove it: ./install.sh --uninstall"
