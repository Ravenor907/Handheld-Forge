"""Make a boot drive the way a system needs: copied file by file, or written byte for byte."""

from __future__ import annotations

import threading
from pathlib import Path

from . import builder, rawwrite
from .imagekind import ImageKind, sniff_file
from .config import Distro
from .drives import Drive


FORMATS = {"raw-bz2": ImageKind.BZIP2, "raw-gz": ImageKind.GZIP, "raw-xz": ImageKind.XZ}


def image_kind(distro: Distro, image: Path) -> ImageKind:
    """What the file really is (as the Android app checks), falling back on the catalog's word."""
    kind = sniff_file(image)
    if kind is not ImageKind.UNKNOWN:
        return kind
    return FORMATS.get(distro.format, ImageKind.DISK)


def make_drive(distro: Distro, drive: Drive, image: Path, stage=lambda *_: None, log=print,
               cancel: threading.Event | None = None, verify: bool = True, live=None) -> None:
    """Makes the drive. `live` is a download still arriving (Labs): the image is written from it
    as it comes in, and its checksum is checked once it's complete."""
    if not distro.needs_password:
        u = distro.preset_user
        ks = builder.user_kickstart(u.name, u.full_name, u.password) if u else None
        builder.build_drive(drive, image, stage, log, cancel, layout=distro.layout, kickstart=ks)
        return
    try:
        if live is not None:
            kind = FORMATS.get(distro.format, ImageKind.DISK)
            rawwrite.write_drive(drive, live.open, kind, stage, log, cancel, verify=verify,
                                 raw_size=live.size, name=image.name.removesuffix(".part"))
            live.finish(log)
            return
        rawwrite.write_drive(drive, image, image_kind(distro, image), stage, log, cancel, verify=verify)
    except rawwrite.CorruptImage:
        # A damaged download: drop it so the next try fetches a fresh copy.
        for f in (image, image.with_name(image.name + ".verified")):
            f.unlink(missing_ok=True)
        raise rawwrite.RawWriteError("The download was damaged, so it was deleted. Try again.") from None
