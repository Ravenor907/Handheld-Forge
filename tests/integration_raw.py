"""Real-world check of the byte-for-byte path, run in CI against a real udisks daemon.

Usage: integration_raw.py <image> <loop device> [--bz2]

Runs as a normal user, like the app. CI has no one to type a password, so a polkit
rule stands in for it; the OpenDevice call, fd passing, O_DIRECT writes and the
read-back are the real code path. udisks marks loop devices as system devices, so
that one check is bypassed here.
"""

import os
import sys
from pathlib import Path

from handheld_forge import rawwrite
from handheld_forge.drives import Drive
from handheld_forge.udisks import UDisks


def main() -> int:
    image, dev = Path(sys.argv[1]), sys.argv[2]
    compressed = "--bz2" in sys.argv
    name = os.path.basename(os.path.realpath(dev))
    size = int(Path(f"/sys/block/{name}/size").read_text()) * 512
    UDisks.is_system_device = lambda self, block: False  # loop devices count as "system"
    drive = Drive(dev, name, size, "CI loop", (), True, "ok")
    try:
        rawwrite.write_drive(drive, image, compressed, log=lambda m: print(m, flush=True), recheck=False)
    except Exception as e:
        print(f"::error title=Raw write failed::{type(e).__name__}: {e}")
        raise
    print("RAW WRITE OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
