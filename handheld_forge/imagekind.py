"""What an image file is, judged from its first bytes (same rules as the Android app), and how
to unpack it while it's read."""

from __future__ import annotations

import gzip
import io
import lzma
import zipfile
from enum import Enum

SNIFF_BYTES = 34_816  # enough to see an ISO's volume descriptor at 32 KB


class ImageKind(Enum):
    ISO_HYBRID = ("ISO image", False)
    ISO_DVD_ONLY = ("ISO image (DVD only)", False)
    DISK = ("Disk image", False)
    BZIP2 = ("Compressed disk image (.bz2)", True)
    GZIP = ("Compressed disk image (.gz)", True)
    XZ = ("Compressed disk image (.xz)", True)
    ZIP = ("Zipped image (.zip)", True)
    ZSTD = ("Zstandard (.zst)", True)
    UNKNOWN = ("Unrecognized file", False)

    def __init__(self, label: str, compressed: bool) -> None:
        self.label = label
        self.compressed = compressed

    @property
    def writable(self) -> bool:
        return self is not ImageKind.ZSTD

    @property
    def warning(self) -> str | None:
        if self is ImageKind.ISO_DVD_ONLY:
            return (
                "This ISO is made for DVDs, not USB drives. It will be written exactly, but most computers "
                "and handhelds won't start from it. Check the download page for a USB version."
            )
        if self is ImageKind.UNKNOWN:
            return (
                "This doesn't look like an ISO or disk image. It will be written byte for byte as it is, "
                "which only works if it really is a drive image."
            )
        return None


def sniff(head: bytes) -> ImageKind:
    def at(off: int, *b: int) -> bool:
        return len(head) >= off + len(b) and all(head[off + i] == v for i, v in enumerate(b))

    if at(0, 0x42, 0x5A, 0x68):
        return ImageKind.BZIP2
    if at(0, 0x1F, 0x8B):
        return ImageKind.GZIP
    if at(0, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00):
        return ImageKind.XZ
    if at(0, 0x50, 0x4B, 0x03, 0x04):
        return ImageKind.ZIP
    if at(0, 0x28, 0xB5, 0x2F, 0xFD):
        return ImageKind.ZSTD
    if at(32_769, 0x43, 0x44, 0x30, 0x30, 0x31):  # "CD001": ISO 9660; a boot signature makes it hybrid
        return ImageKind.ISO_HYBRID if at(510, 0x55, 0xAA) else ImageKind.ISO_DVD_ONLY
    if at(512, *b"EFI PART"):
        return ImageKind.DISK
    if at(510, 0x55, 0xAA):
        return ImageKind.DISK
    return ImageKind.UNKNOWN


def sniff_file(path) -> ImageKind:
    with open(path, "rb") as f:
        return sniff(f.read(SNIFF_BYTES))


def _first_in_zip(f):
    z = zipfile.ZipFile(f)
    for info in z.infolist():
        name = info.filename
        if info.is_dir() or name.startswith("__MACOSX/") or name.rsplit("/", 1)[-1].startswith("._"):
            continue
        return z.open(info)
    raise OSError("The zip file is empty")


def decoder(kind: ImageKind, raw):
    """A readable stream of the image's real bytes. bzip2 unpacks on every core."""
    if kind is ImageKind.BZIP2:
        from .pbzip2 import ParallelBz2Reader

        return io.BufferedReader(ParallelBz2Reader(raw), 1 << 20)
    if kind is ImageKind.GZIP:
        return gzip.GzipFile(fileobj=raw)
    if kind is ImageKind.XZ:
        return lzma.LZMAFile(raw)
    if kind is ImageKind.ZIP:
        return _first_in_zip(raw)
    if kind is ImageKind.ZSTD:
        raise OSError("Zstandard files can't be written yet. Unpack the .zst first.")
    return raw
