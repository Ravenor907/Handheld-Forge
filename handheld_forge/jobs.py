"""Everything the desktop app does to a drive besides making a system's boot drive: your own
image file, Format drive, Ventoy (install, add images, starts on its own, remove) and the Labs
tools. Same jobs, wording and checks as the Android app.

Two ways in. Jobs that rewrite the whole drive (Ventoy install, your own file, the drive test,
checking a drive, and Format when udisks can't) open it through udisks, which shows the
desktop's password window once. Jobs on Ventoy's files mount its exFAT partition through udisks
like a file manager does, with no password, and read each file back from the drive, not memory.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

from . import drivetest, exfat, rawwrite, ventoy, ventoy_config
from .blockdev import FdDisk, cancelled
from .download import cache_dir
from .drives import Drive
from .imagekind import SNIFF_BYTES, ImageKind, decoder, sniff
from .rawwrite import Cancelled, RawWriteError
from .udisks import UDisks, UDisksError, object_path_for

StageFn = Callable[[str, int, int], None]
LogFn = Callable[[str], None]
CHUNK = 4 << 20
BOOTABLE = {"iso", "img", "wim", "vhd", "vhdx", "efi", "vtoy"}  # what Ventoy lists in its menu
PACKED = {"xz", "gz", "bz2", "zip"}
DOS_TYPE_EXFAT = "0x07"
_BAD_NAME = re.compile(r'[\x00-\x1f"*/:<>?\\|]')


def _noop(*_) -> None:
    pass


# ------------------------------------------------------------------ drive info


def link_speed(drive: Drive, sys_block: Path = Path("/sys/block")) -> str | None:
    """'USB 3 (5 Gbps)' or 'USB 2 (480 Mbps)' from sysfs, or None if it can't tell."""
    try:
        d = (sys_block / drive.name).resolve()
    except OSError:
        return None
    for p in [d, *d.parents]:
        f = p / "speed"
        if (p / "idVendor").exists() and f.exists():
            try:
                mbps = int(float(f.read_text().strip()))
            except (OSError, ValueError):
                return None
            if mbps >= 5000:
                return f"USB 3 ({mbps / 1000:g} Gbps)"
            if mbps >= 480:
                return "USB 2 (480 Mbps). A USB 3 port or cable would be much faster"
            return f"USB 1 ({mbps} Mbps)"
    return None


def log_drive(drive: Drive, log: LogFn) -> None:
    speed = link_speed(drive)
    log(f"Drive: {drive.model}, {drive.size_label}" + (f", connected at {speed}" if speed else ""))


def _clock(secs: float) -> str:
    s = int(secs)
    return f"{s // 60}:{s % 60:02d}"


def _rate(n: int, secs: float) -> str:
    return f"{n / 1e6 / max(secs, 1e-6):.1f} MB/s"


# ------------------------------------------------------------------ your own file


@dataclass
class OwnFile:
    path: Path
    kind: ImageKind
    size: int

    @property
    def warning(self) -> str | None:
        return self.kind.warning


def look_at(path: Path) -> OwnFile:
    with open(path, "rb") as f:
        kind = sniff(f.read(SNIFF_BYTES))
    if not kind.writable:
        raise RawWriteError(
            f"{path.name} is a {kind.label} file, which can't be unpacked here. Unpack it first."
        )
    return OwnFile(Path(path), kind, Path(path).stat().st_size)


def write_own_file(
    drive: Drive,
    f: OwnFile,
    stage: StageFn = _noop,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    verify: bool = True,
    **open_kw,
) -> None:
    log_drive(drive, log)
    log(f"{f.path.name}: {f.kind.label}, {f.size / 1e9:.2f} GB")
    rawwrite.write_drive(drive, f.path, f.kind, stage, log, cancel, verify=verify, **open_kw)


# ------------------------------------------------------------------ format


def format_drive(
    drive: Drive,
    stage: StageFn = _noop,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    udisks: UDisks | None = None,
    **open_kw,
) -> None:
    """One exFAT partition labeled USB DRIVE across the whole drive. udisks does it with no
    password when exFAT tools are installed; otherwise the app formats it itself (password)."""
    ud = udisks or UDisks()
    log_drive(drive, log)
    block = object_path_for(drive.name)
    if ud.is_system_device(block):
        raise RawWriteError("Your system marks this drive as internal. Refusing to touch it.")
    stage("prepare", 0, 0)
    try:
        for part in drive.partitions:
            ud.unmount(object_path_for(part))
        if cancelled(cancel):
            raise Cancelled()
        stage("write", 0, 0)
        log("Creating a fresh partition table")
        ud.format_disk(block, "dos")
        log(f'Formatting exFAT, label "{exfat.PLAIN_LABEL}"')
        ud.create_partition(block, 1 << 20, 0, DOS_TYPE_EXFAT, "", "exfat", {"label": exfat.PLAIN_LABEL})
        log("Done: one exFAT partition")
    except UDisksError as e:
        if cancelled(cancel):
            raise Cancelled() from None
        log(f"The system couldn't format it ({e}); formatting it here instead")

        def work(fd: int) -> None:
            dev = FdDisk(fd, drive.path)
            try:
                exfat.plain_format(dev, exfat.PLAIN_LABEL, stage, log, cancel)
            finally:
                dev.close()

        rawwrite.with_drive(drive, work, stage, log, cancel, ud, eject=False, **open_kw)
    stage("done", 1, 1)


# ------------------------------------------------------------------ Ventoy install


def install_ventoy(
    drive: Drive,
    secure_boot: bool = True,
    stage: StageFn = _noop,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    udisks: UDisks | None = None,
    fetch=ventoy.fetch_latest,
    **open_kw,
) -> str:
    """Downloads the newest Ventoy (checked against its sha256.txt) and installs it the way
    Ventoy2Disk.sh does. Returns the version installed."""
    cancel = cancel or threading.Event()
    log_drive(drive, log)
    if drive.size < ventoy.MIN_BYTES:
        raise RawWriteError("Ventoy needs a drive of at least 1 GB.")
    rel, files = fetch(cache_dir() / "ventoy", stage, log, cancel)

    def work(fd: int) -> None:
        dev = FdDisk(fd, drive.path)
        try:
            t = time.monotonic()
            ventoy.install(dev, files, stage, log, cancel, secure_boot=secure_boot)
            log(f"Installed in {_clock(time.monotonic() - t)}")
        finally:
            dev.close()

    rawwrite.with_drive(drive, work, stage, log, cancel, udisks, eject=False, **open_kw)
    log(f"Ventoy {rel.version} installed" + ("" if secure_boot else " with Secure Boot support off"))
    return rel.version


# ------------------------------------------------------------------ Ventoy files (no password)


@dataclass
class VentoyFile:
    name: str
    size: int

    @property
    def bootable(self) -> bool:
        return self.name.rsplit(".", 1)[-1].lower() in BOOTABLE if "." in self.name else False


@dataclass
class VentoyInfo:
    images_block: str  # udisks object of the exFAT partition
    version: str | None
    secure_boot: bool | None
    files: list[VentoyFile] = field(default_factory=list)
    free: int = 0
    size: int = 0
    boot_next: str | None = None


def _lsblk_parts(drive: Drive, run=subprocess.run) -> list[dict]:
    res = run(
        ["lsblk", "--json", "--bytes", "-o", "NAME,LABEL,FSTYPE,SIZE,PARTN,TYPE", f"/dev/{drive.name}"],
        capture_output=True,
        text=True,
    )
    if res.returncode != 0:
        return []
    disk = (json.loads(res.stdout).get("blockdevices") or [{}])[0]
    return [c for c in disk.get("children") or [] if c.get("type") == "part"]


def ventoy_parts(drive: Drive, run=subprocess.run) -> tuple[str, str] | None:
    """(images partition, VTOYEFI partition) kernel names if Ventoy is on the drive."""
    parts = sorted(_lsblk_parts(drive, run), key=lambda p: int(p.get("partn") or 0))
    for a, b in zip(parts, parts[1:], strict=False):
        if (
            b.get("label") == "VTOYEFI"
            and int(b.get("size") or 0) == ventoy.PART2_SECTORS * 512
            and int(b.get("partn") or 0) == int(a.get("partn") or 0) + 1
        ):
            return a["name"], b["name"]
    return None


def _mounted(ud: UDisks, block: str, read_only: bool = False) -> tuple[Path, bool]:
    """(mount point, whether we mounted it)."""
    have = ud.mount_points(block)
    if have:
        return Path(have[0]), False
    return Path(ud.mount(block, read_only=read_only)), True


def ventoy_info(drive: Drive, udisks: UDisks | None = None, run=subprocess.run) -> VentoyInfo | None:
    """Ventoy's version, Secure Boot setting, images and free space, or None without Ventoy."""
    parts = ventoy_parts(drive, run)
    if parts is None:
        return None
    ud = udisks or UDisks()
    images, efi = (object_path_for(p) for p in parts)
    version = secure = None
    try:
        mp, ours = _mounted(ud, efi, read_only=True)
        try:
            cfg = mp / "grub" / "grub.cfg"
            m = (
                re.search(r'VENTOY_VERSION="?([0-9][0-9.]*)', cfg.read_text("utf-8", "replace"))
                if cfg.exists()
                else None
            )
            version = m.group(1) if m else None
            secure = any((mp / "EFI" / "BOOT" / n).exists() for n in ("grubx64_real.efi", "GRUBX64_REAL.EFI"))
        finally:
            if ours:
                ud.unmount(efi)
    except (UDisksError, OSError):
        pass
    mp, _ = _mounted(ud, images)
    files = []
    for e in sorted(mp.iterdir(), key=lambda p: p.name.lower()):
        if e.is_file() and not e.name.startswith("."):
            files.append(VentoyFile(e.name, e.stat().st_size))
    st = os.statvfs(mp)
    try:
        nxt = ventoy_config.read_boot_next(mp)
    except OSError:
        nxt = None
    return VentoyInfo(
        images, version, secure, files, st.f_bavail * st.f_frsize, st.f_blocks * st.f_frsize, nxt
    )


def clean_name(name: str) -> str:
    """A name exFAT and every OS accept."""
    n = _BAD_NAME.sub("_", name).strip().rstrip(".")
    return (n or "image")[:255]


def target_name(name: str, kind: ImageKind, content: ImageKind) -> str:
    """Name on the drive: compression suffix dropped, and an extension Ventoy recognizes."""
    n = name
    if kind.compressed and "." in n and n.rsplit(".", 1)[1].lower() in PACKED:
        n = n.rsplit(".", 1)[0]
    ext = n.rsplit(".", 1)[1].lower() if "." in n else ""
    if content in (ImageKind.ISO_HYBRID, ImageKind.ISO_DVD_ONLY):
        n = n if ext == "iso" else n + ".iso"
    elif content is ImageKind.DISK:
        if ext not in ("img", "vhd", "vhdx", "vtoy"):
            n = (n.rsplit(".", 1)[0] if ext == "raw" else n) + ".img"
    return clean_name(n)


@dataclass
class ImportPlan:
    source: Path
    kind: ImageKind
    content: ImageKind
    target: str
    size: int  # bytes on the drive, or -1 when it's unpacked (learned while writing)

    @property
    def unpacks(self) -> bool:
        return self.kind.compressed

    @property
    def bootable(self) -> bool:
        return "." in self.target and self.target.rsplit(".", 1)[1].lower() in BOOTABLE


def plan_import(path: Path) -> ImportPlan:
    path = Path(path)
    with path.open("rb") as f:
        kind = sniff(f.read(SNIFF_BYTES))
    if not kind.writable:
        raise OSError(f"{path.name} is a {kind.label} file, which can't be unpacked here. Unpack it first.")
    if not kind.compressed:
        return ImportPlan(path, kind, kind, target_name(path.name, kind, kind), path.stat().st_size)
    try:
        with path.open("rb") as raw, decoder(kind, raw) as d:
            head = d.read(SNIFF_BYTES)
    except Exception as e:  # noqa: BLE001 - any decoder failure means a damaged file
        raise OSError(
            f"{path.name} is damaged or not really a {kind.label} ({e}). Download it again."
        ) from None
    if not head:
        raise OSError(f"{path.name} is empty once unpacked.")
    content = sniff(head)
    return ImportPlan(path, kind, content, target_name(path.name, kind, content), -1)


def unique(plans: list[ImportPlan]) -> list[ImportPlan]:
    """Two picked files that would get the same name: the later ones get ' (2)', ' (3)'..."""
    seen: set[str] = set()
    out = []
    for p in plans:
        name, k = p.target, 1
        while name.lower() in seen:
            k += 1
            base, dot, ext = p.target.rpartition(".")
            name = f"{base} ({k}).{ext}" if dot else f"{p.target} ({k})"
        seen.add(name.lower())
        out.append(p if name == p.target else ImportPlan(p.source, p.kind, p.content, name, p.size))
    return out


def _drop_cache(fd: int) -> None:
    try:
        os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
    except OSError:
        pass


def _read_back(path: Path, size: int, want: str, stage: StageFn, before: int, total: int, cancel) -> None:
    """Reads a file back from the drive (its cached copy dropped first) and compares hashes."""
    fd = os.open(path, os.O_RDONLY)
    try:
        _drop_cache(fd)
        h = hashlib.sha256()
        done = 0
        while chunk := os.read(fd, CHUNK):
            if cancelled(cancel):
                raise Cancelled()
            h.update(chunk)
            done += len(chunk)
            stage("check", before + done, total)
    finally:
        os.close(fd)
    if done != size or h.hexdigest() != want:
        raise RawWriteError(
            f"{path.name} didn't come back from the drive the way it was written. "
            "The drive may be failing or counterfeit. Try another drive."
        )


def import_images(
    drive: Drive,
    picked: list[ImportPlan],
    stage: StageFn = _noop,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    verify: bool = True,
    boot_next: str | None = None,
    udisks: UDisks | None = None,
    run=subprocess.run,
) -> list[str]:
    """Copies (or unpacks) each file onto Ventoy's partition, replacing files of the same name,
    then reads each back from the drive. Returns the names added."""
    cancel = cancel or threading.Event()
    parts = ventoy_parts(drive, run)
    if parts is None:
        raise RawWriteError("This drive doesn't have Ventoy on it. Install Ventoy first.")
    ud = udisks or UDisks()
    log_drive(drive, log)
    block = object_path_for(parts[0])
    mp, ours = _mounted(ud, block)
    try:
        return _import(mp, picked, stage, log, cancel, verify, boot_next)
    finally:
        if ours:  # flushes the volume, so the drive can be unplugged right away
            try:
                ud.unmount(block)
            except UDisksError as e:
                log(f"Couldn't unmount the drive ({e}). Eject it from the file manager before unplugging it.")


def _import(mp: Path, picked, stage, log, cancel, verify, boot_next) -> list[str]:
    plans = unique(picked)
    known = [p for p in plans if p.size >= 0]
    st = os.statvfs(mp)
    free = st.f_bavail * st.f_frsize
    replacing = sum((mp / p.target).stat().st_size for p in plans if (mp / p.target).is_file())
    need = sum(p.size for p in known)
    if need > free + replacing:
        raise RawWriteError(
            f"Not enough space: {need / 1e9:.1f} GB to add, {(free + replacing) / 1e9:.1f} GB "
            "free on the drive."
        )
    order = known + [p for p in plans if p.size < 0]
    total_in = sum(p.source.stat().st_size for p in order)
    done_in = 0
    results: dict[str, tuple[int, str]] = {}
    stage("write", 0, total_in)
    for p in order:
        dest = mp / p.target
        tmp = mp / f".{p.target}.part"
        if dest.exists():
            log(f"Replacing {p.target} already on the drive")
            dest.unlink()
        started = time.monotonic()
        before = done_in
        log(f"Unpacking {p.source.name} onto the drive as {p.target}" if p.unpacks else f"Copying {p.target}")
        md = hashlib.sha256()
        wrote = 0
        last_log = 0
        try:
            with p.source.open("rb") as raw:
                counted = rawwrite._Counting(raw)
                src = decoder(p.kind, counted) if p.unpacks else counted
                out = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
                try:
                    while True:
                        if cancelled(cancel):
                            raise Cancelled()
                        try:
                            chunk = src.read(CHUNK)
                        except (OSError, EOFError, ValueError) as e:
                            raise OSError(
                                f"{p.source.name} is damaged or didn't finish downloading ({e}). "
                                "Download it again."
                            ) from None
                        if not chunk:
                            break
                        md.update(chunk)
                        view = memoryview(chunk)
                        while view:
                            n = os.write(out, view)
                            view = view[n:]
                        wrote += len(chunk)
                        stage("write", before + counted.count, total_in)
                        if p.unpacks and wrote - last_log >= 1_000_000_000:
                            last_log = wrote
                            log(f"{wrote / 1e9:.1f} GB unpacked onto the drive so far")
                    os.fsync(out)
                finally:
                    os.close(out)
            tmp.replace(dest)
        except OSError as e:
            tmp.unlink(missing_ok=True)
            if e.errno == 28:  # ENOSPC
                raise RawWriteError(
                    f"The drive filled up while copying {p.target}. Remove an image and try again."
                ) from None
            raise RawWriteError(f"Couldn't copy {p.target}: {e}") from None
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise
        results[p.target] = (wrote, md.hexdigest())
        took = time.monotonic() - started
        if p.unpacks:
            log(f"{p.target}: {wrote / 1e9:.2f} GB once unpacked")
        log(f"{p.target}: {wrote / 1e9:.2f} GB in {_clock(took)} ({_rate(wrote, took)})")
        done_in += p.source.stat().st_size
        stage("write", done_in, total_in)
    os.sync()

    names = [p.target for p in plans]
    if boot_next is not None:
        ventoy_config.set_boot_next(mp, boot_next or None)
        log(f"{boot_next} starts on its own" if boot_next else "Ventoy shows its menu again")
    total_out = sum(r[0] for r in results.values())
    if not verify:
        log("Skipped reading the images back (Labs setting)")
        stage("check", total_out, total_out)
        return names
    checked = 0
    stage("check", 0, total_out)
    r0 = time.monotonic()
    for name in names:
        size, want = results[name]
        log(f"Reading {name} back from the drive to confirm")
        _read_back(mp / name, size, want, stage, checked, total_out, cancel)
        checked += size
    log(f"Every image matches what was written (read back at {_rate(total_out, time.monotonic() - r0)})")
    stage("check", total_out, total_out)
    return names


def remove_image(
    drive: Drive, name: str, log: LogFn = print, udisks: UDisks | None = None, run=subprocess.run
) -> None:
    parts = ventoy_parts(drive, run)
    if parts is None:
        raise RawWriteError("This drive doesn't have Ventoy on it.")
    ud = udisks or UDisks()
    mp, _ = _mounted(ud, object_path_for(parts[0]))
    if ventoy_config.read_boot_next(mp) == name:
        ventoy_config.set_boot_next(mp, None)
    (mp / name).unlink(missing_ok=True)
    os.sync()
    log(f"Removed {name}")


def set_boot_next(
    drive: Drive, name: str | None, log: LogFn = print, udisks: UDisks | None = None, run=subprocess.run
) -> None:
    """Which image Ventoy starts with no input (5 second menu), or None for the normal menu."""
    parts = ventoy_parts(drive, run)
    if parts is None:
        raise RawWriteError("This drive doesn't have Ventoy on it.")
    ud = udisks or UDisks()
    mp, _ = _mounted(ud, object_path_for(parts[0]))
    if name is not None and not (mp / name).is_file():
        raise RawWriteError(f"{name} isn't on the drive.")
    ventoy_config.set_boot_next(mp, name)
    os.sync()
    log(f"{name} starts on its own after 5 seconds" if name else "Ventoy shows its menu again")


def unmount_all(drive: Drive, udisks: UDisks | None = None) -> None:
    ud = udisks or UDisks()
    for part in drive.partitions:
        try:
            ud.unmount(object_path_for(part))
        except UDisksError:
            pass


def eject(drive: Drive, log: LogFn = print, udisks: UDisks | None = None) -> None:
    ud = udisks or UDisks()
    unmount_all(drive, ud)
    try:
        obj = ud.drive_of(object_path_for(drive.name))
        if obj:
            ud.power_off(obj)
            log("Drive ejected")
    except UDisksError as e:
        log(f"Could not eject automatically ({e}). Eject it from the file manager.")


# ------------------------------------------------------------------ Labs tools


def test_drive(
    drive: Drive,
    stage: StageFn = _noop,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    udisks: UDisks | None = None,
    **open_kw,
) -> drivetest.Report:
    """Labs: real speed and capacity (erases the drive)."""
    log_drive(drive, log)

    def work(fd: int) -> drivetest.Report:
        dev = FdDisk(fd, drive.path)
        try:
            r = drivetest.run(dev, stage, log, cancel)
            for line in r.lines():
                log(line)
            log("Formatting the drive so it's usable again")
            exfat.plain_format(dev, exfat.PLAIN_LABEL, _noop, log, cancel)
            return r
        finally:
            dev.close()

    return rawwrite.with_drive(drive, work, stage, log, cancel, udisks, eject=False, **open_kw)


def check_drive(
    drive: Drive,
    image: Path,
    stage: StageFn = _noop,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    udisks: UDisks | None = None,
    **open_kw,
) -> int | None:
    """Labs: compares a drive with an image file without writing. None if they match."""
    log_drive(drive, log)
    image = Path(image)
    with image.open("rb") as f:
        kind = sniff(f.read(SNIFF_BYTES))
    if not kind.writable:
        raise RawWriteError(f"{image.name} is a {kind.label} file, which can't be unpacked here.")

    def work(fd: int) -> int | None:
        dev = FdDisk(fd, drive.path)
        try:
            with image.open("rb") as raw:
                counted = rawwrite._Counting(raw)
                with decoder(kind, counted) as src:
                    log(f"Comparing {drive.path} with {image.name}")
                    return drivetest.compare(src, dev, stage, log, cancel, image.stat().st_size, counted)
        finally:
            dev.close()

    return rawwrite.with_drive(drive, work, stage, log, cancel, udisks, eject=False, **open_kw)
