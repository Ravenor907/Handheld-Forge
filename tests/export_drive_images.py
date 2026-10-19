"""Makes the desktop app's drives as image files for CI, which checks them with the real tools and
boots them (android/ci/drive-images.sh, the same checks the Android app's drives get):

  plain.img            Format drive (the app's own exFAT formatter, used when udisks can't)
  ventoy.img           Ventoy, Secure Boot support on
  ventoy-nosb.img      Ventoy, Secure Boot support off
  ventoy-import.img    Ventoy plus every file in the import folder, added through a real exFAT
                       mount the way the app adds them (needs root for the loop device and mount)
  ventoy-bootnext.img  The same, with forge-zip.iso set to start on its own

usage: sudo python3 tests/export_drive_images.py OUT SECTORS VENTOY_TAR IMPORT_DIR
"""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

from handheld_forge import exfat, jobs, ventoy
from handheld_forge.blockdev import FileDisk


def run(*cmd: str) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip()


def mount_exfat(dev: str, where: str) -> None:
    if subprocess.run(["mount", "-t", "exfat", dev, where]).returncode != 0:
        run("mount.exfat-fuse", dev, where)


def main(out: str, sectors: str, tar: str, imports: str) -> None:
    out_dir, n = Path(out), int(sectors)
    out_dir.mkdir(parents=True, exist_ok=True)
    with FileDisk(out_dir / "plain.img", n) as d:
        exfat.plain_format(d, log=print)
    with open(tar, "rb") as f:
        files = ventoy.extract(f)
    for name, sb in (("ventoy", True), ("ventoy-nosb", False)):
        with FileDisk(out_dir / f"{name}.img", n) as d:
            ventoy.install(d, files, log=print, secure_boot=sb)
    sources = sorted(p for p in Path(imports).iterdir() if p.is_file())
    for name in ("ventoy-import", "ventoy-bootnext"):
        img = out_dir / f"{name}.img"
        with FileDisk(img, n) as d:
            ventoy.install(d, files, log=print)
        loop = run("losetup", "-f", "--show", "-P", str(img))
        subprocess.run(["partprobe", loop])
        mnt = tempfile.mkdtemp()
        try:
            mount_exfat(f"{loop}p1", mnt)
            plans = [jobs.plan_import(p) for p in sources]
            added = jobs._import(Path(mnt), plans, lambda *_: None, print, None, True,
                                 "forge-zip.iso" if name == "ventoy-bootnext" else None)
            print(f"Imported: {added}")
        finally:
            subprocess.run(["umount", mnt])
            subprocess.run(["losetup", "-d", loop])


if __name__ == "__main__":
    main(*sys.argv[1:5])
