"""Every desktop job against a real udisks daemon and a real loop device, as a normal user (CI's
polkit rule stands in for typing the password, as in integration_raw.py):

  Format drive through udisks, then through the app's own formatter (udisks made to fail),
  your own file (xz) and Check a drive, Ventoy install, reading it back (version, Secure Boot,
  files), adding images through udisks' mount (plain and bz2), starts on its own, remove, and
  the drive test.

Usage: integration_jobs.py <loop device with partition scanning> <ventoy-x.y.z-linux.tar.gz>
"""

from __future__ import annotations

import bz2
import hashlib
import json
import lzma
import os
import random
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from handheld_forge import jobs, ventoy, ventoy_config
from handheld_forge.drives import Drive
from handheld_forge.udisks import UDisks, UDisksError

LOG = []


def log(m: str) -> None:
    LOG.append(m)
    print(m, flush=True)


def fail(title: str, msg: str) -> None:
    print(f"::error title={title}::{msg}")
    sys.exit(1)


def settle() -> None:
    subprocess.run(["udevadm", "settle"], check=False)
    time.sleep(1)


def parts(dev: str) -> list[dict]:
    settle()
    out = json.loads(subprocess.run(["lsblk", "--json", "-o", "NAME,FSTYPE,LABEL,PARTN", dev],
                                    capture_output=True, text=True, check=True).stdout)
    return out["blockdevices"][0].get("children") or []


def drive_for(dev: str) -> Drive:
    name = os.path.basename(os.path.realpath(dev))
    size = int(Path(f"/sys/block/{name}/size").read_text()) * 512
    kids = tuple(p["name"] for p in parts(dev))
    return Drive(dev, name, size, "CI loop", kids, True, "ok")


def wait_for(what: str, cond, secs: float = 20.0):
    end = time.monotonic() + secs
    while time.monotonic() < end:
        v = cond()
        if v:
            return v
        settle()
    fail(what, "didn't happen in time")


def disk_like(size: int, seed: int) -> bytes:
    r = random.Random(seed)
    out = bytearray()
    while len(out) < size:
        n = min(r.randint(1, 300_000), size - len(out))
        out += r.randbytes(n) if r.random() < 0.5 else bytes(n)
    out[510:512] = b"\x55\xAA"
    return bytes(out)


def main() -> int:
    dev, tar = sys.argv[1], sys.argv[2]
    UDisks.is_system_device = lambda self, block: False  # loop devices count as "system"
    kw = {"recheck": False}
    work = Path(tempfile.mkdtemp())

    # Format drive: udisks first.
    jobs.format_drive(drive_for(dev), log=log)
    p = wait_for("Format (udisks)", lambda: [x for x in parts(dev) if x.get("fstype") == "exfat"])
    if p[0].get("label") != "USB DRIVE":
        fail("Format (udisks)", f"label is {p[0].get('label')!r}")
    log("Format through udisks: exFAT, USB DRIVE")

    # ...and the app's own formatter when udisks can't.
    real = UDisks.format_disk

    def broken(self, block, table="gpt"):
        raise UDisksError("mkfs.exfat is missing")

    UDisks.format_disk = broken
    jobs.format_drive(drive_for(dev), log=log, **kw)
    UDisks.format_disk = real
    if not any("formatting it here instead" in m for m in LOG):
        fail("Format (fallback)", "the fallback didn't run")
    wait_for("Format (fallback)", lambda: [x for x in parts(dev) if x.get("fstype") == "exfat"
                                           and x.get("label") == "USB DRIVE"])
    log("Format with the app's own formatter: exFAT, USB DRIVE")

    # Your own file (xz) and Check a drive.
    image = disk_like(40_000_000, 3)
    xz = work / "mine.img.xz"
    xz.write_bytes(lzma.compress(image, preset=1))
    f = jobs.look_at(xz)
    jobs.write_own_file(drive_for(dev), f, log=log, **kw)
    if not any("Every byte on the drive matches the image" in m for m in LOG):
        fail("Your own file", "no read-back")
    if jobs.check_drive(drive_for(dev), xz, log=log, **kw) is not None:
        fail("Check a drive", "reported a difference on a matching drive")
    (work / "other.img").write_bytes(image[:1000] + b"!" + image[1001:])
    if jobs.check_drive(drive_for(dev), work / "other.img", log=log, **kw) != 1000:
        fail("Check a drive", "missed a difference at byte 1000")
    log("Your own file written; Check a drive agrees")

    # Ventoy install, then everything through udisks' mount (no password).
    with open(tar, "rb") as t:
        files = ventoy.extract(t)
    version = ventoy.version_of(bytearray(lzma.decompress(files.disk_img_xz))) or "?"
    rel = ventoy.Release(version, Path(tar).name, "", None)
    jobs.install_ventoy(drive_for(dev), True, log=log, fetch=lambda *a: (rel, files), **kw)
    d = wait_for("Ventoy install", lambda: (lambda x: x if jobs.ventoy_parts(x) else None)(drive_for(dev)))
    info = jobs.ventoy_info(d)
    if info is None or info.version != version or info.secure_boot is not True or info.files:
        fail("Ventoy info", repr(info))
    log(f"Ventoy {info.version} found, Secure Boot support on, {info.free / 1e9:.1f} GB free")

    iso = bytearray(disk_like(6_000_000, 4))
    iso[32769:32774] = b"CD001"
    (work / "Plain.iso").write_bytes(bytes(iso))
    disk = disk_like(9_000_000, 5)
    (work / "steamos.img.bz2").write_bytes(bz2.compress(disk))
    plans = [jobs.plan_import(work / "Plain.iso"), jobs.plan_import(work / "steamos.img.bz2")]
    names = jobs.import_images(d, plans, log=log, boot_next="steamos.img")
    if names != ["Plain.iso", "steamos.img"]:
        fail("Add images", repr(names))
    info = jobs.ventoy_info(d)
    if sorted(x.name for x in info.files) != ["Plain.iso", "steamos.img"] or info.boot_next != "steamos.img":
        fail("Add images", repr(info))
    mp = Path(UDisks().mount_points(f"/org/freedesktop/UDisks2/block_devices/{jobs.ventoy_parts(d)[0]}")[0])
    for name, want in (("Plain.iso", bytes(iso)), ("steamos.img", disk)):
        if hashlib.sha256((mp / name).read_bytes()).digest() != hashlib.sha256(want).digest():
            fail("Add images", f"{name} differs on the drive")
    if ventoy_config.read_boot_next(mp) != "steamos.img":
        fail("Starts on its own", "ventoy.json doesn't name steamos.img")
    jobs.set_boot_next(d, "Plain.iso", log=log)
    jobs.remove_image(d, "Plain.iso", log=log)
    info = jobs.ventoy_info(d)
    if [x.name for x in info.files] != ["steamos.img"] or info.boot_next is not None:
        fail("Remove", repr(info))
    jobs.unmount_all(drive_for(dev))
    log("Images added, read back, started on their own and removed through udisks")

    # The drive test (then it's formatted again).
    report = jobs.test_drive(drive_for(dev), log=log, **kw)
    if report.fake or report.failing:
        fail("Drive test", "\n".join(report.lines()))
    wait_for("Drive test format", lambda: [x for x in parts(dev) if x.get("fstype") == "exfat"])
    print(f"::notice title=Desktop jobs through real udisks::{' / '.join(report.lines()[:2])}")
    print("JOBS OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
