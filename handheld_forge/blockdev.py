"""Drives as numbered sectors, the way the Android app sees them.

The desktop opens a whole drive through udisks (one password prompt, see rawwrite.open_device)
and gets a file descriptor opened with O_DIRECT, so reads come from the drive, not from memory.
O_DIRECT wants buffers aligned in memory and sizes in whole sectors; FdDisk handles that, so the
code above it (exFAT, Ventoy, the drive test) just reads and writes sectors.
"""

from __future__ import annotations

import fcntl
import mmap
import os
import struct
from typing import Protocol

BLKSSZGET = 0x1268  # logical sector size
CHUNK = 4 * 1024 * 1024


class BlockDevice(Protocol):
    name: str
    block_size: int
    block_count: int

    def read(self, lba: int, count: int) -> bytes: ...

    def write(self, lba: int, data: bytes | bytearray | memoryview) -> None: ...

    def sync(self) -> None: ...


def cancelled(c) -> bool:
    """True if the job was asked to stop: c is a threading.Event or a function returning a bool."""
    if c is None:
        return False
    return c.is_set() if hasattr(c, "is_set") else bool(c())


def size_bytes(dev: BlockDevice) -> int:
    return dev.block_size * dev.block_count


class FdDisk:
    """A drive opened by udisks (or any block device fd). Owns nothing: the caller closes the fd."""

    def __init__(self, fd: int, name: str = "drive") -> None:
        self.fd = fd
        self.name = name
        try:
            self.block_size = struct.unpack("i", fcntl.ioctl(fd, BLKSSZGET, b"\0\0\0\0"))[0]
        except OSError:
            self.block_size = 512  # a plain file (tests)
        self.block_count = os.lseek(fd, 0, os.SEEK_END) // self.block_size
        self._buf = mmap.mmap(-1, CHUNK)  # page aligned, as O_DIRECT requires
        self._view = memoryview(self._buf)

    def read(self, lba: int, count: int) -> bytes:
        out = bytearray()
        bs = self.block_size
        while count > 0:
            n = min(count, CHUNK // bs)
            got = os.preadv(self.fd, [self._view[: n * bs]], lba * bs)
            if got < n * bs:
                raise OSError(f"The drive stopped answering at sector {lba}")
            out += self._view[: n * bs]
            lba += n
            count -= n
        return bytes(out)

    def write(self, lba: int, data) -> None:
        bs = self.block_size
        data = memoryview(data).cast("B")
        if len(data) % bs:
            raise ValueError("writes must be whole sectors")
        at = 0
        while at < len(data):
            n = min(len(data) - at, CHUNK)
            self._view[:n] = data[at : at + n]
            done = os.pwritev(self.fd, [self._view[:n]], lba * bs + at)
            if done < n:
                raise OSError(f"The drive stopped answering at sector {lba + at // bs}")
            at += n

    def sync(self) -> None:
        os.fsync(self.fd)

    def close(self) -> None:
        self._view.release()
        self._buf.close()


class FileDisk:
    """A disk image file as a drive (tests, and CI images checked with real tools)."""

    def __init__(self, path, blocks: int | None = None, block_size: int = 512) -> None:
        self.name = str(path)
        self.block_size = block_size
        self.fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o644)
        if blocks is not None:
            os.ftruncate(self.fd, blocks * block_size)
        self.block_count = os.lseek(self.fd, 0, os.SEEK_END) // block_size

    def read(self, lba: int, count: int) -> bytes:
        data = os.pread(self.fd, count * self.block_size, lba * self.block_size)
        if len(data) < count * self.block_size:
            raise OSError("read past the end")
        return data

    def write(self, lba: int, data) -> None:
        data = memoryview(data).cast("B")
        if len(data) % self.block_size:
            raise ValueError("writes must be whole sectors")
        if lba < 0 or lba + len(data) // self.block_size > self.block_count:
            raise ValueError(
                f"write past the end: {lba}+{len(data) // self.block_size} of {self.block_count}"
            )
        os.pwrite(self.fd, data, lba * self.block_size)

    def sync(self) -> None:
        os.fsync(self.fd)

    def close(self) -> None:
        if self.fd >= 0:
            os.close(self.fd)
            self.fd = -1

    def __enter__(self):
        return self

    def __exit__(self, *_) -> None:
        self.close()
