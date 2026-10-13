"""Installing, updating and uninstalling the desktop app for the current user.

install.sh puts a virtual environment in ~/.local/share/handheld-forge/venv,
links ~/.local/bin/handheld-forge to it and adds a menu entry. Everything here
works on that layout and never touches anything outside the user's home.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from . import APP_NAME, __version__

REPO = "Ravenor907/Handheld-Forge"
LATEST_VERSION_URL = f"https://raw.githubusercontent.com/{REPO}/main/handheld_forge/__init__.py"
LATEST_SOURCE_URL = f"https://github.com/{REPO}/archive/refs/heads/main.tar.gz"


@dataclass(frozen=True)
class Paths:
    app_dir: Path  # holds the venv
    venv: Path
    bin_link: Path
    desktop: Path
    cache: Path  # downloaded images

    @staticmethod
    def current() -> Paths:
        home = Path.home()
        data = Path(os.environ.get("XDG_DATA_HOME") or home / ".local/share")
        cache = Path(os.environ.get("XDG_CACHE_HOME") or home / ".cache")
        app = data / "handheld-forge"
        return Paths(app, app / "venv", home / ".local/bin/handheld-forge",
                     data / "applications/handheld-forge.desktop", cache / "handheld-forge")


def installed(p: Paths | None = None) -> bool:
    """True when running from (or alongside) an install.sh install."""
    p = p or Paths.current()
    return (p.venv / "bin/handheld-forge").exists()


def menu_entry(p: Paths) -> str:
    exe = p.venv / "bin/handheld-forge"
    return f"""[Desktop Entry]
Type=Application
Name={APP_NAME}
Comment=Create a handheld OS boot drive in one click
Exec={exe}
Icon=drive-removable-media-usb
Terminal=false
Categories=System;Utility;
StartupNotify=true
StartupWMClass=handheld-forge
Actions=update;uninstall;

[Desktop Action update]
Name=Update {APP_NAME}
Exec={exe} update --ask

[Desktop Action uninstall]
Name=Uninstall {APP_NAME}
Exec={exe} uninstall --ask
"""


def refresh_menus(apps: Path | None = None, run=subprocess.run) -> None:
    """Ask the desktop to reread app entries now instead of whenever it next notices."""
    apps = apps or Paths.current().desktop.parent
    for cmd in (["kbuildsycoca6"], ["kbuildsycoca5"], ["update-desktop-database", str(apps)]):
        if shutil.which(cmd[0]):
            try:
                run(cmd, capture_output=True, timeout=60)
            except (OSError, subprocess.TimeoutExpired):
                pass
            if cmd[0].startswith("kbuildsycoca"):
                break  # one KDE cache rebuild is enough


def write_menu_entry(p: Paths | None = None, refresh=None) -> Path:
    p = p or Paths.current()
    p.desktop.parent.mkdir(parents=True, exist_ok=True)
    text = menu_entry(p)
    # Replace the file in one step and only when it changed: a moment without a valid
    # entry is enough for KDE to drop a launcher pinned to the taskbar.
    if not p.desktop.exists() or p.desktop.read_text() != text:
        tmp = p.desktop.with_name(f".{p.desktop.name}.tmp")
        tmp.write_text(text)
        os.replace(tmp, p.desktop)
        (refresh or refresh_menus)(p.desktop.parent)
    p.bin_link.parent.mkdir(parents=True, exist_ok=True)
    if p.bin_link.is_symlink() or not p.bin_link.exists():
        p.bin_link.unlink(missing_ok=True)
        p.bin_link.symlink_to(p.venv / "bin/handheld-forge")
    return p.desktop


def _version_tuple(v: str) -> tuple[int, ...]:
    return tuple(int(x) for x in re.findall(r"\d+", v)[:3])


def parse_version(init_py: str) -> str | None:
    m = re.search(r'__version__\s*=\s*"([^"]+)"', init_py)
    return m.group(1) if m else None


def latest_version(timeout: float = 10) -> str | None:
    req = urllib.request.Request(LATEST_VERSION_URL, headers={"User-Agent": f"HandheldForge/{__version__}"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return parse_version(r.read(8192).decode("utf-8", "replace"))


def is_newer(remote: str | None, local: str = __version__) -> bool:
    return remote is not None and _version_tuple(remote) > _version_tuple(local)


def update(p: Paths | None = None, source: str = LATEST_SOURCE_URL, run=subprocess.run) -> str:
    """Install the latest code into the app's venv. Returns the version now installed."""
    p = p or Paths.current()
    pip = p.venv / "bin/pip"
    if not pip.exists():
        raise RuntimeError("Handheld Forge wasn't installed with install.sh, so it can't update itself. "
                           "Pull the latest code and run ./install.sh again.")
    # Dependencies first, then the app itself even if the version number didn't change.
    for args in ([source], ["--force-reinstall", "--no-deps", source]):
        r = run([str(pip), "install", "--quiet", "--disable-pip-version-check", *args],
                capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f"The update failed:\n{(r.stderr or r.stdout).strip()[-800:]}")
    r = run([str(p.venv / "bin/python"), "-c", "import handheld_forge; print(handheld_forge.__version__)"],
            capture_output=True, text=True)
    write_menu_entry(p)
    return r.stdout.strip() or "?"


def cache_size(p: Paths | None = None) -> int:
    p = p or Paths.current()
    if not p.cache.is_dir():
        return 0
    return sum(f.stat().st_size for f in p.cache.rglob("*") if f.is_file())


def uninstall(p: Paths | None = None, keep_downloads: bool = False) -> list[Path]:
    """Remove the menu entry, the command link, the venv and (unless kept) downloads."""
    p = p or Paths.current()
    removed = []
    for path in (p.desktop,):
        if path.exists():
            path.unlink()
            removed.append(path)
    # Only remove the command if it's our link, never someone else's file.
    if p.bin_link.is_symlink() and str(os.readlink(p.bin_link)).startswith(str(p.app_dir)):
        p.bin_link.unlink()
        removed.append(p.bin_link)
    if removed:
        refresh_menus(p.desktop.parent)
    targets = [p.app_dir] + ([] if keep_downloads else [p.cache])
    for d in targets:
        if d.is_dir() and d.name == "handheld-forge" and Path.home() in d.parents:
            shutil.rmtree(d)
            removed.append(d)
    return removed


# ---------------------------------------------------------------- menu actions

def _windows(ask: bool) -> bool:
    """Ask and report in small windows when started from the app menu (no terminal, a screen)."""
    if not ask or (sys.stdin and sys.stdin.isatty()):
        return False
    return bool(os.environ.get("DISPLAY") or os.environ.get("WAYLAND_DISPLAY"))


def _qt():
    # Loaded up front: after an uninstall the library's files are already gone.
    from PySide6.QtWidgets import QApplication, QMessageBox
    QApplication.instance() or QApplication([])
    return QMessageBox


def _ask(question: str, detail: str, windows: bool) -> bool:
    if not windows:
        print(question)
        if detail:
            print(detail)
        try:
            return input("Continue? [y/N] ").strip().lower() in ("y", "yes")
        except EOFError:
            return False
    box_cls = _qt()
    box = box_cls(box_cls.Question, APP_NAME, question, box_cls.Yes | box_cls.No)
    box.setInformativeText(detail)
    return box.exec() == box_cls.Yes


def _tell(text: str, windows: bool) -> None:
    print(text)
    if windows:
        _qt().information(None, APP_NAME, text)


def run_update(ask: bool, force: bool = False) -> int:
    win = _windows(ask)
    try:
        remote = latest_version()
    except OSError as e:
        _tell(f"Couldn't check for updates: {e}", win)
        return 1
    if is_newer(remote):
        question = f"Update {APP_NAME} from {__version__} to {remote}?"
        if ask and not _ask(question, "This takes about a minute.", win):
            return 0
    elif not force:
        if not ask:
            print(f"Already up to date ({__version__}). Use --force to reinstall anyway.")
            return 0
        if not _ask(f"You have the latest version ({__version__}).",
                    "Reinstall it anyway? This can fix a broken install.", win):
            return 0
    try:
        now = update()
    except RuntimeError as e:
        _tell(str(e), win)
        return 1
    _tell(f"{APP_NAME} is now version {now}. Close and reopen it to use the new version.", win)
    return 0


def run_uninstall(ask: bool, keep_downloads: bool) -> int:
    win = _windows(ask)
    if win:
        _qt()
    p = Paths.current()
    size = 0 if keep_downloads else cache_size(p)
    detail = "Your USB drives aren't touched."
    if size:
        amount = f"{size / 1e9:.1f} GB" if size >= 1e9 else f"{size / 1e6:.0f} MB"
        detail = f"This also deletes downloaded system images ({amount}). {detail}"
    if ask and not _ask(f"Uninstall {APP_NAME}?", detail, win):
        print("Nothing was removed.")
        return 0
    removed = uninstall(p, keep_downloads)
    _tell(f"{APP_NAME} is uninstalled." if removed else f"{APP_NAME} wasn't installed for this user.", win)
    return 0
