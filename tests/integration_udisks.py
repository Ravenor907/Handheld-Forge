"""Real-world check, run in CI as root against a real udisks daemon and a loop device.

Usage: integration_udisks.py <iso> <loop device, e.g. /dev/loop3>

Root is only needed because CI has no desktop session; the app itself never
runs as root. udisks marks loop devices as system devices, so that one check is
bypassed here; everything else (gdbus calls, partitioning, mkfs, mounts, copy,
read-back, label handling) is the real code path.
"""

import os
import sys
from pathlib import Path

from handheld_forge import builder
from handheld_forge.drives import Drive
from handheld_forge.udisks import UDisks


def main() -> int:
    iso, dev = Path(sys.argv[1]), sys.argv[2]
    name = os.path.basename(os.path.realpath(dev))
    size = int(Path(f"/sys/block/{name}/size").read_text()) * 512
    UDisks.is_system_device = lambda self, block: False  # loop devices count as "system"
    drive = Drive(dev, name, size, "CI loop", (), True, "ok")
    last = {}

    def stage(s, done, total):
        if last.get(s) != (done * 10 // total if total else 0):
            last[s] = done * 10 // total if total else 0
            print(f"  {s} {last[s] * 10}%", flush=True)

    try:
        builder.build_drive(drive, iso, stage, log=lambda m: print(m, flush=True), recheck=False)
    except Exception as e:
        print(f"::error title=Integration build failed::{type(e).__name__}: {e}")
        raise
    print("BUILD OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
