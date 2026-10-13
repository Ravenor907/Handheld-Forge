"""Command line interface. Running with no arguments opens the GUI."""

from __future__ import annotations

import argparse
import sys
import time

from . import APP_NAME, __version__
from .config import experimental_variants, get_distro, install_guide, load_catalog
from .progress import Progress
from .preflight import SleepBlocker, can_start, checks, read_state
from .download import prepare_iso
from .drives import list_drives

DEFAULT_DISTRO = "bazzite-deck"


class _Bar:
    """Single-line progress readout for the terminal."""

    def __init__(self) -> None:
        self.last = 0.0
        self.progress = Progress()

    def __call__(self, stage: str, done: int, total: int) -> None:
        self.progress.update(stage, done, total)
        now = time.monotonic()
        if now - self.last < 0.25 and done != total:
            return
        self.last = now
        line = f"\r{self.progress.percent:3d}%  {stage:>8}"
        if total > 0:
            line += f"  {min(done, total) / 1e9:6.2f}/{total / 1e9:.2f} GB"
            left = self.progress.time_left(done, total)
            if left:
                line += f", {left}"
        sys.stderr.write(f"{line:<70}")
        sys.stderr.flush()


def _log(msg: str) -> None:
    sys.stderr.write(f"\n{msg}\n")


def cmd_distros(_: argparse.Namespace) -> int:
    catalog = load_catalog()
    for d in catalog:
        state = ("ready, asks for password" if d.needs_password else "ready") if d.enabled else "planned"
        print(f"{d.id:<20} {state:<8} {d.name}")
    extra = experimental_variants(catalog)
    if extra:
        print("\nExperimental (no password, not tested on a handheld yet):")
        for d in extra:
            print(f"{d.id:<20} {d.name}, copied file by file")
    return 0


def cmd_drives(a: argparse.Namespace) -> int:
    drives = list_drives(include_ineligible=a.all)
    if not drives:
        print("No USB drives found. Plug one in and try again.")
        return 1
    for d in drives:
        flag = "" if d.eligible else f"  [blocked: {d.reason}]"
        print(f"{d.label}{flag}")
    return 0


def _usable(distro) -> bool:
    if not distro.enabled:
        print(f"{distro.name} isn't available yet.", file=sys.stderr)
    return distro.enabled


def cmd_download(a: argparse.Namespace) -> int:
    distro = get_distro(a.distro)
    if not _usable(distro):
        return 1
    img = prepare_iso(distro, _Bar(), _log)
    sig = "verified" if img.signature_verified else "not available"
    print(f"\n{img.path}\nsha256 {img.sha256}  signature {sig}")
    return 0


def cmd_create(a: argparse.Namespace) -> int:
    from .maker import make_drive

    distro = get_distro(a.distro)
    if not _usable(distro):
        return 1
    drive = next((d for d in list_drives() if d.path == a.device), None)
    if drive is None:
        print(f"{a.device} is not an eligible USB drive. See: handheld-forge drives --all", file=sys.stderr)
        return 1
    if drive.size < distro.min_drive_gb * 1000**3 * 0.93:
        print(f"Use a drive of {distro.min_drive_gb} GB or larger.", file=sys.stderr)
        return 1
    items = checks(read_state())
    for c in items:
        if not c.ok:
            print(f"{'Blocked' if c.required else 'Warning'}: {c.title}. {c.detail}", file=sys.stderr)
    if not can_start(items, acknowledged=True):
        return 1
    if not a.yes:
        print(f"This will ERASE everything on {drive.label}")
        if distro.needs_password:
            print(f"{distro.name} is written byte for byte, so Linux will ask for your password once.")
        if distro.warning:
            print(distro.warning)
        print("Leave the drive plugged in until it's done. This can take 30 minutes or more.")
        if input("Type ERASE to continue: ").strip() != "ERASE":
            print("Cancelled.")
            return 1
    bar = _Bar()
    with SleepBlocker():
        img = prepare_iso(distro, bar, _log)
        make_drive(distro, drive, img.path, bar, _log)
    print(f"\n{distro.name} boot drive is ready.\n\n{install_guide(distro)}")
    return 0


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if not argv:
        from .gui import run
        return run()

    p = argparse.ArgumentParser(prog="handheld-forge", description=f"{APP_NAME} {__version__}")
    p.add_argument("--version", action="version", version=__version__)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("gui", help="open the window (default)")
    sub.add_parser("distros", help="list supported operating systems")
    s = sub.add_parser("drives", help="list USB drives you can write to")
    s.add_argument("--all", action="store_true", help="also show blocked disks and why")
    s = sub.add_parser("download", help="download and verify the latest ISO only")
    s.add_argument("--distro", default=DEFAULT_DISTRO)
    s = sub.add_parser("create", help="download, verify and write a boot drive")
    s.add_argument("--device", required=True, help="e.g. /dev/sdb")
    s.add_argument("--distro", default=DEFAULT_DISTRO)
    s.add_argument("--yes", action="store_true", help="skip the ERASE confirmation")
    s = sub.add_parser("update", help="update Handheld Forge to the latest version")
    s.add_argument("--force", action="store_true", help="reinstall even if already up to date")
    s.add_argument("--ask", action="store_true", help=argparse.SUPPRESS)
    s = sub.add_parser("uninstall", help="remove Handheld Forge completely")
    s.add_argument("--keep-downloads", action="store_true", help="keep downloaded system images")
    s.add_argument("--keep-source", action="store_true", help="keep the downloaded copy of the code")
    s.add_argument("--yes", action="store_true", help="don't ask first")
    s.add_argument("--ask", action="store_true", help=argparse.SUPPRESS)
    sub.add_parser("fix-menu", help="put Handheld Forge back in the app launcher and say what was wrong")
    sub.add_parser("setup-menu", help=argparse.SUPPRESS)  # older name, same as fix-menu
    a = p.parse_args(argv)

    if a.cmd == "gui":
        from .gui import run
        return run()
    if a.cmd in ("update", "uninstall", "fix-menu", "setup-menu"):
        from . import installer
        if a.cmd == "update":
            return installer.run_update(ask=a.ask, force=a.force)
        if a.cmd == "uninstall":
            return installer.run_uninstall(ask=a.ask or not a.yes, keep_downloads=a.keep_downloads,
                                           keep_source=a.keep_source)
        report = installer.fix_menu()
        print("\n".join(report))
        return 1 if any(line.startswith("Problem") for line in report) else 0
    try:
        return {"distros": cmd_distros, "drives": cmd_drives,
                "download": cmd_download, "create": cmd_create}[a.cmd](a)
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        return 130
    except Exception as e:
        print(f"\nError: {e}", file=sys.stderr)
        return 1
