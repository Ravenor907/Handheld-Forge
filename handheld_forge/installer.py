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

    @property
    def source_note(self) -> Path:
        """install.sh records here which copy of the code it was run from."""
        return self.app_dir / "source"

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
Categories=Utility;
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


def refresh_menus(apps: Path | None = None, run=subprocess.run) -> list[str]:
    """Make the desktop reread app entries now. Returns what ran, for fix-menu to report.

    KDE's launcher reads a cache (ksycoca); a full rebuild (--noincremental) also picks
    up entries in a folder it wasn't watching yet.
    """
    apps = apps or Paths.current().desktop.parent
    done = []
    for cmd in (["kbuildsycoca6", "--noincremental"], ["kbuildsycoca5", "--noincremental"],
                ["update-desktop-database", str(apps)]):
        if not shutil.which(cmd[0]):
            continue
        try:
            r = run(cmd, capture_output=True, timeout=120)
            done.append(f"{cmd[0]} ({'ok' if r.returncode == 0 else f'exit {r.returncode}'})")
        except (OSError, subprocess.TimeoutExpired) as e:
            done.append(f"{cmd[0]} (failed: {e})")
        if cmd[0].startswith("kbuildsycoca"):
            break  # one KDE cache rebuild is enough
    return done


def write_menu_entry(p: Paths | None = None, refresh=None, force_refresh: bool = False) -> Path:
    """Write the app menu entry and the command link. The menu is refreshed when the entry
    changed, or always with force_refresh (installs and updates, in case the cache is stale)."""
    p = p or Paths.current()
    p.desktop.parent.mkdir(parents=True, exist_ok=True)
    text = menu_entry(p)
    changed = not p.desktop.exists() or p.desktop.read_text() != text
    # Replace the file in one step and only when it changed: a moment without a valid
    # entry is enough for KDE to drop a launcher pinned to the taskbar.
    if changed:
        tmp = p.desktop.with_name(f".{p.desktop.name}.tmp")
        tmp.write_text(text)
        os.replace(tmp, p.desktop)
    if changed or force_refresh:
        (refresh or refresh_menus)(p.desktop.parent)
    p.bin_link.parent.mkdir(parents=True, exist_ok=True)
    if p.bin_link.is_symlink() or not p.bin_link.exists():
        p.bin_link.unlink(missing_ok=True)
        p.bin_link.symlink_to(p.venv / "bin/handheld-forge")
    return p.desktop


def in_container() -> str | None:
    """Name of the container this runs in (distrobox, toolbox), where installs don't reach the host menu."""
    if os.environ.get("CONTAINER_ID"):
        return os.environ["CONTAINER_ID"]
    for marker in ("/run/.containerenv", "/.dockerenv"):
        if Path(marker).exists():
            return "a container"
    return None


def menu_override_files() -> list[Path]:
    """The user's own launcher edits (KDE's Edit Applications and Hide Application write these)."""
    config = Path(os.environ.get("XDG_CONFIG_HOME") or Path.home() / ".config")
    menus = config / "menus"
    return sorted(menus.rglob("*.menu")) if menus.is_dir() else []


_OUR_FILENAME = re.compile(r"[ \t]*<Filename>\s*handheld-forge\.desktop\s*</Filename>[ \t]*\n?")
_EMPTY_RULE = re.compile(r"[ \t]*<(Exclude|Include)>\s*</\1>[ \t]*\n?")


def clear_menu_overrides(backup_dir: Path | None = None) -> list[Path]:
    """Remove rules that hide or move Handheld Forge in the launcher, touching nothing else.

    A KDE menu edit can exclude an app by its file name; that survives uninstalls and
    keeps a fresh install out of the launcher even though KDE knows the app.
    Returns the files changed. Originals are copied to backup_dir first.
    """
    changed = []
    for f in menu_override_files():
        text = f.read_text(errors="replace")
        if "handheld-forge.desktop" not in text:
            continue
        new = _EMPTY_RULE.sub("", _OUR_FILENAME.sub("", text))
        if new == text:
            continue
        if backup_dir:
            backup_dir.mkdir(parents=True, exist_ok=True)
            (backup_dir / f.name).write_text(text)
        tmp = f.with_name(f".{f.name}.tmp")
        tmp.write_text(new)
        os.replace(tmp, f)
        changed.append(f)
    return changed


def fix_menu(p: Paths | None = None, refresh=None) -> list[str]:
    """Rewrite the menu entry, rebuild the launcher cache, and report what was found."""
    p = p or Paths.current()
    report = []
    box = in_container()
    if box:
        report.append(f"Problem: this is running inside {box} (distrobox or toolbox). The app menu belongs "
                      "to your normal desktop, so open a plain Konsole tab (not a container) and run "
                      "./install.sh there.")
    exe = p.venv / "bin/handheld-forge"
    if not os.access(exe, os.X_OK):
        report.append(f"Problem: the app isn't installed at {exe}. "
                      "Run ./install.sh from your copy of the code.")
        return report
    write_menu_entry(p, refresh=lambda _: None)
    report.append(f"Menu entry: {p.desktop}")
    for f in clear_menu_overrides(backup_dir=p.cache / "menu-backups"):
        report.append(f"Fixed: {f} was hiding Handheld Forge in the launcher (a saved menu edit). "
                      f"Removed just that rule; a copy of the original is in {p.cache / 'menu-backups'}.")
    ran = (refresh or refresh_menus)(p.desktop.parent)
    report.append("Launcher refreshed with " + ", ".join(ran) if ran else
                  "Couldn't find kbuildsycoca6/5 to refresh the launcher; log out and back in to see it.")
    desktop = os.environ.get("XDG_CURRENT_DESKTOP", "")
    data_home = os.environ.get("XDG_DATA_HOME")
    if data_home and Path(data_home) != p.desktop.parent.parent:
        report.append(f"Note: XDG_DATA_HOME is {data_home}; the launcher looks there.")
    report.append(f"Desktop: {desktop or 'unknown'}. "
                  "Look for Handheld Forge under Utilities, or search for it.")
    return report


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
                capture_output=True, text=True, env={**os.environ, "PIP_NO_CACHE_DIR": "1"})
        if r.returncode != 0:
            raise RuntimeError(f"The update failed:\n{(r.stderr or r.stdout).strip()[-800:]}")
    r = run([str(p.venv / "bin/python"), "-c", "import handheld_forge; print(handheld_forge.__version__)"],
            capture_output=True, text=True)
    write_menu_entry(p, force_refresh=True)
    return r.stdout.strip() or "?"


def _size(path: Path) -> int:
    if path.is_symlink() or path.is_file():
        return path.lstat().st_size
    if not path.is_dir():
        return 0
    return sum(f.lstat().st_size for f in path.rglob("*") if f.is_file() and not f.is_symlink())


def cache_size(p: Paths | None = None) -> int:
    return _size((p or Paths.current()).cache)


def _ours(d: Path) -> bool:
    """A folder we may delete: named handheld-forge, inside the user's home, not the home itself."""
    home = Path.home().resolve()
    d = d.resolve()
    return d.is_dir() and d.name == "handheld-forge" and home in d.parents


def source_copy(p: Paths) -> Path | None:
    """The downloaded copy of the code install.sh was run from, if it's still there and really ours."""
    try:
        src = Path(p.source_note.read_text().strip()).resolve()
    except OSError:
        return None
    home = Path.home().resolve()
    looks_right = (src / "install.sh").is_file() and (src / "handheld_forge/__init__.py").is_file()
    if not looks_right or src == home or home not in src.parents:
        return None
    return src


@dataclass(frozen=True)
class Item:
    path: Path
    what: str
    size: int


def removal_plan(p: Paths | None = None, keep_downloads: bool = False,
                 keep_source: bool = False) -> list[Item]:
    """Everything an uninstall would delete, so it can be shown before anything happens."""
    p = p or Paths.current()
    items = []
    if p.desktop.exists():
        items.append(Item(p.desktop, "App menu entry", _size(p.desktop)))
    if p.bin_link.is_symlink() and str(os.readlink(p.bin_link)).startswith(str(p.app_dir)):
        items.append(Item(p.bin_link, "handheld-forge command", 0))
    src = None if keep_source else source_copy(p)
    if p.app_dir.is_dir() and _ours(p.app_dir):
        items.append(Item(p.app_dir, "The app and its Python libraries", _size(p.app_dir)))
    if not keep_downloads and p.cache.is_dir() and _ours(p.cache):
        items.append(Item(p.cache, "Downloaded system images", _size(p.cache)))
    if src:
        items.append(Item(src, "The downloaded copy of Handheld Forge's code", _size(src)))
    return items


def uninstall(p: Paths | None = None, keep_downloads: bool = False, keep_source: bool = False) -> list[Path]:
    """Remove everything in the plan, then tidy up an empty ~/.local/bin and refresh menus."""
    p = p or Paths.current()
    removed = []
    for item in removal_plan(p, keep_downloads, keep_source):
        if item.path.is_dir() and not item.path.is_symlink():
            shutil.rmtree(item.path)
        else:
            item.path.unlink()
        removed.append(item.path)
    for folder in (p.bin_link.parent, p.desktop.parent):
        try:
            folder.rmdir()  # only succeeds when empty
        except OSError:
            pass
    # A rule hiding the app would also hide a later reinstall, so it goes too.
    removed += clear_menu_overrides()
    if removed:
        refresh_menus(p.desktop.parent)
    return removed


def human(size: int) -> str:
    return f"{size / 1e9:.1f} GB" if size >= 1e9 else f"{size / 1e6:.0f} MB" if size >= 1e6 else ""


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


def run_uninstall(ask: bool, keep_downloads: bool, keep_source: bool = False) -> int:
    win = _windows(ask)
    if win:
        _qt()
    p = Paths.current()
    plan = removal_plan(p, keep_downloads, keep_source)
    if not plan:
        _tell(f"{APP_NAME} isn't installed for this user, so there's nothing to remove.", win)
        return 0
    lines = [f"- {i.what}" + (f" ({human(i.size)})" if human(i.size) else "") + f"\n  {i.path}" for i in plan]
    total = human(sum(i.size for i in plan))
    detail = ("This deletes:\n" + "\n".join(lines) + (f"\n\nIn total {total}." if total else "")
              + "\n\nYour USB drives aren't touched.")
    if ask and not _ask(f"Remove {APP_NAME} completely?", detail, win):
        print("Nothing was removed.")
        return 0
    try:
        here = Path.cwd().resolve()
    except OSError:
        here = None
    removed = uninstall(p, keep_downloads, keep_source)
    msg = f"{APP_NAME} is completely removed."
    if here and any(r == here or r in here.parents for r in removed):
        msg += " The folder this terminal is in was deleted, so run: cd ~"
    _tell(msg, win)
    return 0
