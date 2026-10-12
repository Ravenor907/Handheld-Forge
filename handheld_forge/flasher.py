"""User-side wrapper around the privileged flash helper."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from . import flash_helper

HELPER = Path(flash_helper.__file__).resolve()

StageFn = Callable[[str, int, int], None]
LogFn = Callable[[str], None]


class FlashError(Exception):
    pass


@dataclass(frozen=True)
class Drive:
    path: str
    size: int
    model: str
    mounts: tuple[str, ...]
    eligible: bool
    reason: str

    @property
    def label(self) -> str:
        return f"{self.model}  ({self.size / 1000**3:.1f} GB, {self.path})"


def list_drives(include_ineligible: bool = False) -> list[Drive]:
    drives = [
        Drive(d["path"], d["size"], d["model"], tuple(d["mounts"]), d["eligible"], d["reason"])
        for d in flash_helper.list_disks()
    ]
    return drives if include_ineligible else [d for d in drives if d.eligible]


def flash(
    iso: Path,
    sha256: str,
    drive: Drive,
    min_gb: int,
    stage: StageFn = lambda *_: None,
    log: LogFn = print,
) -> None:
    pkexec = shutil.which("pkexec")
    if not pkexec:
        raise FlashError("pkexec not found. Install polkit to allow writing to USB drives.")
    cmd = [
        pkexec, sys.executable, "-I", str(HELPER), "write",
        "--iso", str(iso), "--device", drive.path,
        "--sha256", sha256, "--min-gb", str(min_gb),
    ]
    log("Asking for admin permission to write the drive")
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    error = None
    done = False
    last_text = ""
    assert proc.stdout is not None
    for line in proc.stdout:
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            if line.strip():
                last_text = line.strip()
                log(last_text)
            continue
        if "error" in msg:
            error = msg["error"]
        elif msg.get("stage") == "done":
            done = True
        else:
            stage(msg["stage"], msg.get("done", 0), msg.get("total", 0))
    rc = proc.wait()
    if rc in (126, 127) and not error:
        raise FlashError("Admin permission was cancelled or denied")
    if error or rc != 0 or not done:
        raise FlashError(error or last_text or f"helper exited with code {rc}")
    log("Write verified by reading the drive back")

    if shutil.which("udisksctl"):
        res = subprocess.run(["udisksctl", "power-off", "-b", drive.path],
                             capture_output=True, text=True)
        log("Drive ejected, safe to unplug" if res.returncode == 0
            else "Done. Eject the drive from your file manager before unplugging")
