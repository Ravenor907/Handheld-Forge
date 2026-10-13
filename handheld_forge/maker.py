"""Make a boot drive the way a system needs: copied file by file, or written byte for byte."""

from __future__ import annotations

import threading
from pathlib import Path

from . import builder, rawwrite
from .config import Distro
from .drives import Drive


def make_drive(distro: Distro, drive: Drive, image: Path, stage=lambda *_: None, log=print,
               cancel: threading.Event | None = None) -> None:
    if not distro.needs_password:
        builder.build_drive(drive, image, stage, log, cancel)
        return
    try:
        rawwrite.write_drive(drive, image, distro.format == "raw-bz2", stage, log, cancel)
    except rawwrite.CorruptImage:
        # A damaged download: drop it so the next try fetches a fresh copy.
        for f in (image, image.with_name(image.name + ".verified")):
            f.unlink(missing_ok=True)
        raise rawwrite.RawWriteError("The download was damaged, so it was deleted. Try again.") from None
