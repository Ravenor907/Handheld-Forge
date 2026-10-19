"""Installs Ventoy (github.com/ventoy/Ventoy) exactly as the Android app does, which is how its
own Linux installer (Ventoy2Disk.sh) lays out a drive in its default MBR mode:

- partition 1: exFAT "Ventoy" from sector 2048 to just before partition 2 (images go here)
- partition 2: 32 MB (65536 sectors, 4 KB aligned) at the end, type 0xEF: ventoy.disk.img
- sector 0: Ventoy's boot code, the table, a random disk UUID (byte 384) and signature (byte 440)
- sectors 1 to 2047: core.img (GRUB for BIOS boot)

The package comes from Ventoy's official GitHub release, checked against its sha256.txt.
"""

from __future__ import annotations

import hashlib
import json
import lzma
import re
import secrets
import struct
import tarfile
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .blockdev import cancelled
from .exfat import format_volume, wipe_ends, write_mbr_table
from .fat16 import Fat16

API_LATEST = "https://api.github.com/repos/ventoy/Ventoy/releases/latest"
PART2_SECTORS = 65536
PART1_START = 2048
LABEL = "Ventoy"
MIN_BYTES = 1_000_000_000
USER_AGENT = "HandheldForge-Linux"


@dataclass(frozen=True)
class Release:
    version: str
    tar_name: str
    tar_url: str
    sha_url: str | None


@dataclass(frozen=True)
class Files:
    boot_img: bytes
    core_img_xz: bytes
    disk_img_xz: bytes


@dataclass(frozen=True)
class Plan:
    total_sectors: int
    part1_sectors: int
    part2_start: int
    sectors_per_cluster: int


def parse_release(text: str) -> Release:
    root = json.loads(text)
    tag = root.get("tag_name")
    if not isinstance(tag, str):
        raise OSError("GitHub didn't say which Ventoy release is newest")
    version = tag.removeprefix("v")
    assets = root.get("assets") or []
    tar = next((a for a in assets if re.fullmatch(r"ventoy-[0-9.]+-linux\.tar\.gz", a.get("name", ""))), None)
    if tar is None:
        raise OSError(f"Ventoy {version} has no Linux package to install from")
    sha = next((a for a in assets if a.get("name") == "sha256.txt"), None)
    return Release(version, tar["name"], tar["browser_download_url"], sha and sha["browser_download_url"])


def extract(tar_gz) -> Files:
    boot = core = disk = None
    with tarfile.open(fileobj=tar_gz, mode="r:gz") as t:
        for m in t:
            if not m.isfile():
                continue
            if m.name.endswith("/boot/boot.img"):
                boot = t.extractfile(m).read()
            elif m.name.endswith("/boot/core.img.xz"):
                core = t.extractfile(m).read()
            elif m.name.endswith("/ventoy/ventoy.disk.img.xz"):
                disk = t.extractfile(m).read()
    if not boot or len(boot) < 446:
        raise OSError("The Ventoy package is missing boot.img")
    if not core:
        raise OSError("The Ventoy package is missing core.img")
    if not disk:
        raise OSError("The Ventoy package is missing ventoy.disk.img")
    return Files(boot, core, disk)


def plan(total_sectors: int) -> Plan:
    part1_end = total_sectors - PART2_SECTORS - 1
    part2_start = part1_end + 1
    mod = part2_start % 8
    if mod:
        part1_end -= mod
        part2_start = part1_end + 1
    size_gb = total_sectors // 2097152
    return Plan(total_sectors, part1_end - PART1_START + 1, part2_start, 256 if size_gb > 32 else 64)


def _get(url: str, cap: int | None = None) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read(cap) if cap else r.read()


def fetch_latest(
    cache_dir: Path, stage=lambda *_: None, log=print, cancel=None, download=None
) -> tuple[Release, Files]:
    """Downloads (or reuses) the newest release and checks it against sha256.txt."""
    import threading

    from .verify import parse_checksum

    cancel = cancel or threading.Event()
    stage("lookup", 0, 0)
    rel = parse_release(_get(API_LATEST, 2 << 20).decode())
    log(f"Latest Ventoy is {rel.version}")
    cache_dir.mkdir(parents=True, exist_ok=True)
    tar = cache_dir / rel.tar_name
    expected = parse_checksum(_get(rel.sha_url).decode(), rel.tar_name) if rel.sha_url else None
    if tar.exists() and expected and _sha256(tar) == expected:
        log(f"Already have Ventoy {rel.version}")
    else:
        part = tar.with_suffix(tar.suffix + ".part")
        if download is None:
            from .download import _download as download
        download(rel.tar_url, part, stage, cancel)
        stage("verify", 0, 0)
        if expected:
            if _sha256(part) != expected:
                part.unlink(missing_ok=True)
                raise OSError("The Ventoy download was corrupted, so it was deleted. Try again.")
            log("Checksum matches Ventoy's sha256.txt")
        else:
            log("Ventoy published no checksum for this release")
        part.replace(tar)
    stage("verify", 1, 1)
    with tar.open("rb") as f:
        return rel, extract(f)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def disable_secure_boot(part2: bytearray) -> None:
    """As Ventoy2Disk.sh -S does (vtoycli partresize -s): BOOTX64.EFI becomes Ventoy's own GRUB,
    the shim's helpers and Ventoy's key file go. Same for 32-bit UEFI."""
    fs = Fat16(part2)
    if fs.find("/EFI/BOOT/grubx64_real.efi"):
        real = fs.read("/EFI/BOOT/grubx64_real.efi")
        for f in ("grubx64.efi", "grubx64_real.efi", "MokManager.efi", "mmx64.efi"):
            fs.delete(f"/EFI/BOOT/{f}")
        fs.delete("/ENROLL_THIS_KEY_IN_MOKMANAGER.cer")
        fs.replace("/EFI/BOOT/BOOTX64.EFI", real)
    if fs.find("/EFI/BOOT/grubia32_real.efi"):
        real = fs.read("/EFI/BOOT/grubia32_real.efi")
        for f in ("grubia32.efi", "grubia32_real.efi", "mmia32.efi"):
            fs.delete(f"/EFI/BOOT/{f}")
        fs.replace("/EFI/BOOT/BOOTIA32.EFI", real)


def secure_boot_enabled(part2: bytes) -> bool:
    return Fat16(bytearray(part2)).find("/EFI/BOOT/grubx64_real.efi") is not None


def version_of(part2: bytes) -> str | None:
    try:
        cfg = Fat16(bytearray(part2)).read("/grub/grub.cfg").decode("utf-8", "replace")
    except OSError:
        return None
    m = re.search(r'VENTOY_VERSION="?([0-9][0-9.]*)', cfg)
    return m.group(1) if m else None


def install(
    dev,
    files: Files,
    stage=lambda *_: None,
    log=print,
    cancel=None,
    secure_boot: bool = True,
    random_bytes=secrets.token_bytes,
) -> None:
    """Lays Ventoy out on dev, then reads back everything except the empty exFAT space."""
    from .rawwrite import Cancelled, RawWriteError

    if dev.block_size != 512:
        raise RawWriteError(f"This drive uses {dev.block_size}-byte sectors; Ventoy needs 512-byte sectors.")
    if dev.block_count * 512 < MIN_BYTES:
        raise RawWriteError("Ventoy needs a drive of at least 1 GB.")
    p = plan(dev.block_count)
    core = lzma.decompress(files.core_img_xz)
    if len(core) > 2047 * 512:
        raise RawWriteError(
            "This Ventoy release's boot code is larger than expected; it can't be installed here."
        )
    part2 = bytearray(lzma.decompress(files.disk_img_xz))
    if len(part2) != PART2_SECTORS * 512:
        raise RawWriteError(
            "This Ventoy release's EFI partition isn't the expected 32 MB; it can't be installed here."
        )
    if not secure_boot:
        if not secure_boot_enabled(part2):
            raise RawWriteError("This Ventoy release has no Secure Boot files to remove.")
        disable_secure_boot(part2)
        log("Secure Boot support off: BOOTX64.EFI is Ventoy's GRUB, the shim and key are removed")
    else:
        log("Secure Boot support on: first start asks to enroll Ventoy's key once")

    written: list[tuple[int, bytes]] = []
    total = 2048 + PART2_SECTORS + 4096
    done = 0

    def put(lba: int, data: bytes) -> None:
        nonlocal done
        step = 2048 * 512
        for at in range(0, len(data), step):
            if cancelled(cancel):
                raise Cancelled()
            piece = data[at : at + step]
            dev.write(lba + at // 512, piece)
            done += len(piece) // 512
            stage("write", min(done, total), total)
        written.append((lba, data))

    stage("write", 0, total)
    log(f"Clearing the old layout from {dev.name}")
    wipe_ends(dev)
    log(f"Formatting the Ventoy partition as exFAT ({p.part1_sectors * 512 / 1e9:.1f} GB)")
    format_volume(dev, PART1_START, p.part1_sectors, LABEL, p.sectors_per_cluster)
    log("Writing Ventoy's EFI partition")
    put(p.part2_start, bytes(part2))
    log("Writing Ventoy's boot code")
    put(1, core.ljust((len(core) + 511) // 512 * 512, b"\0"))

    mbr = bytearray(512)
    mbr[0:446] = files.boot_img[:446]
    write_mbr_table(
        mbr, [(PART1_START, p.part1_sectors, 0x07, True), (p.part2_start, PART2_SECTORS, 0xEF, False)]
    )
    mbr[384:400] = random_bytes(16)
    mbr[440:444] = random_bytes(4)
    put(0, bytes(mbr))
    dev.sync()

    log("Reading Ventoy back from the drive to confirm")
    check_total = sum(len(d) for _, d in written)
    checked = 0
    for lba, data in written:
        back = bytearray()
        for at in range(0, len(data) // 512, 2048):
            if cancelled(cancel):
                raise Cancelled()
            n = min(len(data) // 512 - at, 2048)
            back += dev.read(lba + at, n)
            stage("check", checked + len(back), check_total)
        if hashlib.sha256(back).digest() != hashlib.sha256(data).digest():
            raise RawWriteError(
                "The drive didn't keep what was written. It may be failing or counterfeit; try another drive."
            )
        checked += len(data)
    stage("check", check_total, check_total)
    log("Ventoy is installed")


# ---------------------------------------------------------------- finding Ventoy on a drive


@dataclass(frozen=True)
class Partition:
    index: int
    start: int
    sectors: int
    mbr_type: int
    gpt_name: str = ""


def read_partitions(dev) -> list[Partition]:
    s0 = dev.read(0, 1)
    if s0[510:512] != b"\x55\xaa":
        return []
    parts = []
    for i in range(4):
        o = 446 + i * 16
        ptype = s0[o + 4]
        start, length = struct.unpack_from("<II", s0, o + 8)
        if ptype and length:
            parts.append(Partition(i + 1, start, length, ptype))
    if any(p.mbr_type == 0xEE for p in parts):
        h = dev.read(1, 1)
        if h[:8] != b"EFI PART":
            return []
        lba, count, size = struct.unpack_from("<QII", h, 72)
        count = max(0, min(count, 256))
        if not 128 <= size <= 1024:
            return []
        bs = dev.block_size
        buf = dev.read(lba, (count * size + bs - 1) // bs)
        out = []
        for i in range(count):
            o = i * size
            if not any(buf[o : o + 16]):
                continue
            first, last = struct.unpack_from("<QQ", buf, o + 32)
            name = buf[o + 56 : o + 128].decode("utf-16-le", "replace").rstrip("\0")
            out.append(Partition(i + 1, first, last - first + 1, 0, name))
        return out
    return parts


def detect(dev) -> tuple[Partition, Partition] | None:
    """(images partition, EFI partition) if this drive has Ventoy."""
    try:
        parts = read_partitions(dev)
    except OSError:
        return None
    if len(parts) < 2:
        return None
    p1 = min(parts, key=lambda p: p.index)
    p2 = next((p for p in parts if p.index == p1.index + 1), None)
    if p2 is None or p2.sectors * dev.block_size != PART2_SECTORS * 512:
        return None
    label = dev.read(p2.start, 1)[43:54].decode("ascii", "replace").strip()
    if label != "VTOYEFI" and p2.gpt_name != "VTOYEFI":
        return None
    return p1, p2


def read_efi(dev, efi: Partition) -> bytes:
    return dev.read(efi.start, efi.sectors)
