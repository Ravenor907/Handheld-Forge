"""Write a disk image to a USB drive byte for byte (SteamOS, CachyOS).

Some systems can't be rebuilt from their files the way Bazzite can: SteamOS ships
a whole disk image, and CachyOS expects its ISO written as is. Linux only lets an
admin open a drive for that, so this asks udisks to open it with interaction
allowed, which shows the desktop's own password window once. Everything else
(listing, unmounting, ejecting) stays password-free, as in the copy builder.

The image is streamed (decompressing bzip2 on the fly), the end of the drive is
cleared of a stale backup partition table, and afterwards the whole image is read
back from the drive, bypassing the page cache, and compared with what was written.
"""

from __future__ import annotations

import bz2
import hashlib
import mmap
import os
import threading
from collections.abc import Callable
from pathlib import Path

from .drives import Drive, find_drive
from .udisks import BLOCK, NotAuthorized, UDisks, UDisksError, object_path_for

CHUNK = 4 * 1024 * 1024
ALIGN = 4096  # O_DIRECT needs page-aligned buffers and sizes; 4 KiB covers 512 B and 4 KiB sectors
TAIL = 1024 * 1024

StageFn = Callable[[str, int, int], None]
LogFn = Callable[[str], None]


class RawWriteError(Exception):
    pass


class CorruptImage(RawWriteError):
    """The image file couldn't be read or decompressed: a damaged download."""


class Cancelled(Exception):
    pass


class _Counting:
    """Counts bytes read from the file, so progress follows the (compressed) download."""

    def __init__(self, f) -> None:
        self.f, self.count = f, 0

    def read(self, n: int = -1) -> bytes:
        b = self.f.read(n)
        self.count += len(b)
        return b

    def readinto(self, b) -> int:
        n = self.f.readinto(b)
        self.count += n or 0
        return n

    def __enter__(self):
        return self

    def __exit__(self, *_) -> None:
        pass  # the file itself is closed by its own with-block


def _round_up(n: int) -> int:
    return (n + ALIGN - 1) // ALIGN * ALIGN


def write_image(fd: int, image: Path, compressed: bool, stage: StageFn = lambda *_: None,
                log: LogFn = print, cancel: threading.Event | None = None) -> int:
    """Write image (or its bzip2 contents) to the open device fd and verify it. Returns bytes written."""
    cancel = cancel or threading.Event()
    dev_size = os.lseek(fd, 0, os.SEEK_END)
    file_size = image.stat().st_size
    buf = mmap.mmap(-1, CHUNK)  # page aligned, as O_DIRECT requires
    view = memoryview(buf)

    # A stale backup partition table at the end of the drive can confuse firmware.
    tail = min(TAIL, dev_size // ALIGN * ALIGN)
    if tail:
        view[:tail] = bytes(tail)
        os.pwrite(fd, view[:tail], dev_size // ALIGN * ALIGN - tail)

    md = hashlib.sha256()
    done = 0
    with image.open("rb") as raw:
        counted = _Counting(raw)
        try:
            src = bz2.BZ2File(counted) if compressed else counted
        except (OSError, EOFError, ValueError) as e:
            raise CorruptImage(f"The image file is damaged: {e}") from e
        with src:
            while True:
                if cancel.is_set():
                    raise Cancelled()
                got = 0
                try:
                    while got < CHUNK:
                        n = src.readinto(view[got:CHUNK])
                        if not n:
                            break
                        got += n
                except (OSError, EOFError, ValueError) as e:
                    raise CorruptImage(f"The image file is damaged: {e}") from e
                if not got:
                    break
                md.update(view[:got])
                padded = _round_up(got)
                if padded > got:
                    view[got:padded] = bytes(padded - got)
                if done + padded > dev_size:
                    raise RawWriteError("This drive is too small for the image.")
                os.pwrite(fd, view[:padded], done)
                done += got
                stage("write", counted.count, file_size)
                if got < CHUNK:
                    break
    written = md.hexdigest()
    stage("write", file_size, file_size)
    stage("sync", 0, 0)
    os.fsync(fd)

    log("Reading the drive back to confirm")
    check = hashlib.sha256()
    read = 0
    while read < done:
        if cancel.is_set():
            raise Cancelled()
        want = min(CHUNK, done - read)
        n = os.preadv(fd, [view[:_round_up(want)]], read)
        if n < want:
            raise RawWriteError("The drive stopped answering while it was read back.")
        check.update(view[:want])
        read += want
        stage("check", read, done)
    view.release()
    buf.close()
    if check.hexdigest() != written:
        raise RawWriteError("The drive didn't store the image correctly. It may be failing or "
                            "counterfeit. Try another drive.")
    log("Every byte on the drive matches the image")
    return done


def open_device(block: str, timeout_ms: int = 300_000) -> int:
    """Ask udisks to open a drive for reading and writing. This is the one call that may
    show the desktop's password window; the user has up to timeout_ms to answer."""
    from PySide6.QtCore import QCoreApplication
    from PySide6.QtDBus import QDBusConnection, QDBusMessage, QDBusUnixFileDescriptor

    if QCoreApplication.instance() is None:  # the command line has no Qt app yet
        QCoreApplication([])
    # A fresh connection each time, so DBUS_SYSTEM_BUS_ADDRESS is honored (tests use a private bus).
    name = f"handheld-forge-{os.getpid()}-{threading.get_ident()}"
    addr = os.environ.get("DBUS_SYSTEM_BUS_ADDRESS")
    bus = (QDBusConnection.connectToBus(addr, name) if addr
           else QDBusConnection.connectToBus(QDBusConnection.BusType.SystemBus, name))
    try:
        return _open(bus, block, timeout_ms, QDBusMessage, QDBusUnixFileDescriptor)
    finally:
        QDBusConnection.disconnectFromBus(name)


def _open(bus, block: str, timeout_ms: int, QDBusMessage, QDBusUnixFileDescriptor) -> int:  # noqa: N803
    if not bus.isConnected():
        raise UDisksError("Can't reach the system's disk service (udisks).")
    msg = QDBusMessage.createMethodCall("org.freedesktop.UDisks2", block, BLOCK, "OpenDevice")
    # O_DIRECT so the read-back comes from the drive, not from memory; O_EXCL so nothing
    # else (like an automount) has it open while it's written.
    msg.setArguments(["rw", {"flags": os.O_EXCL | os.O_DIRECT | os.O_CLOEXEC}])
    msg.setInteractiveAuthorizationAllowed(True)
    reply = bus.call(msg, timeout=timeout_ms)
    if reply.type() == QDBusMessage.MessageType.ErrorMessage:
        name, text = reply.errorName(), reply.errorMessage()
        if "Dismissed" in name:
            raise NotAuthorized("The password window was closed, so the drive wasn't touched.", name)
        if "NotAuthorized" in name or "AccessDenied" in name:
            raise NotAuthorized("Linux didn't allow writing to this drive. It needs an admin "
                                "password; on SteamOS, set one first with: passwd", name)
        raise UDisksError(text or name, name)
    arg = reply.arguments()[0]
    fd = arg.fileDescriptor() if isinstance(arg, QDBusUnixFileDescriptor) else int(arg)
    return os.dup(fd)


def write_drive(
    drive: Drive,
    image: Path,
    compressed: bool,
    stage: StageFn = lambda *_: None,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    udisks: UDisks | None = None,
    recheck: bool = True,
    opener: Callable[[str], int] = open_device,
) -> None:
    """Unmount the drive, open it (password), write and verify the image, then eject."""
    cancel = cancel or threading.Event()
    ud = udisks or UDisks()
    if recheck:
        fresh = find_drive(drive.path)
        if fresh is None or not fresh.eligible:
            raise RawWriteError(f"{drive.path} is no longer a safe USB target"
                                + (f" ({fresh.reason})" if fresh else ""))
        drive = fresh
    block = object_path_for(drive.name)
    if ud.is_system_device(block):
        raise RawWriteError("Your system marks this drive as internal. Refusing to touch it.")

    stage("prepare", 0, 0)
    for part in drive.partitions:
        ud.unmount(object_path_for(part))
    if cancel.is_set():
        raise Cancelled()
    log("Asking for permission to write the whole drive (Linux shows its password window)")
    fd = opener(block)
    try:
        log(f"Writing {image.name} to {drive.path}")
        write_image(fd, image, compressed, stage, log, cancel)
    finally:
        os.close(fd)

    stage("eject", 0, 0)
    try:
        drive_obj = ud.drive_of(block)
        if drive_obj:
            ud.power_off(drive_obj)
            log("Drive ejected")
    except UDisksError as e:
        log(f"Could not eject automatically ({e}). Eject it from the file manager.")
