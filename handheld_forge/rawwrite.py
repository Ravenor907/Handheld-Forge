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

import fcntl
import hashlib
import lzma
import mmap
import os
import struct
import threading
import time
import zipfile
import zlib
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from .blockdev import BLKSSZGET
from .drives import Drive, find_drive
from .imagekind import ImageKind, decoder
from .udisks import BLOCK, NotAuthorized, UDisks, UDisksError, object_path_for
from .verify import HashWorker

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

    def readable(self) -> bool:
        return True

    def seekable(self) -> bool:  # zip files need to seek
        return getattr(self.f, "seekable", lambda: False)()

    def seek(self, *a) -> int:
        return self.f.seek(*a)

    def tell(self) -> int:
        return self.f.tell()

    @property
    def closed(self) -> bool:
        return False

    def close(self) -> None:
        pass  # the file itself is closed by its own with-block

    def __enter__(self):
        return self

    def __exit__(self, *_) -> None:
        pass


def _round_up(n: int) -> int:
    return (n + ALIGN - 1) // ALIGN * ALIGN


def _sector_size(fd: int) -> int:
    """The drive's logical sector size; 512 for a plain file (tests)."""
    try:
        size = struct.unpack("i", fcntl.ioctl(fd, BLKSSZGET, b"\0\0\0\0"))[0]
    except OSError:
        return 512
    return size if size > 0 else 512


def _kind(compressed) -> ImageKind:
    if isinstance(compressed, ImageKind):
        return compressed
    return ImageKind.BZIP2 if compressed else ImageKind.DISK


class _Timer:
    """Where the writing time went: waiting for the image (download or unpacking) or the drive."""

    def __init__(self) -> None:
        self.read = self.write = 0.0
        self.start = time.monotonic()

    def summary(self, n: int) -> str:
        total = max(time.monotonic() - self.start, 1e-6)
        busy = max(self.read + self.write, 1e-9)
        drive = int(self.write * 100 / busy)
        who = ("the drive is the limit" if drive >= 70 else
               "unpacking or downloading is the limit, not the drive" if drive <= 30 else
               "the drive and unpacking are about even")
        clock = f"{int(total) // 60}:{int(total) % 60:02d}"
        return (f"Wrote {n / 1e9:.2f} GB in {clock} ({n / 1e6 / total:.1f} MB/s). "
                f"{drive}% of the time waiting on the drive, {100 - drive}% on the image: {who}")


def _fill(src, view, counted) -> tuple[int, int]:
    """Reads into view until it's full or the image ends: (bytes read, bytes of the file used so far)."""
    got = 0
    while got < CHUNK:
        n = src.readinto(view[got:CHUNK])
        if not n:
            break
        got += n
    return got, counted.count


def write_image(fd: int, image, compressed=False, stage: StageFn = lambda *_: None,
                log: LogFn = print, cancel: threading.Event | None = None, verify: bool = True,
                raw_size: int | None = None, expected_sha256: str | None = None) -> int:
    """Write an image to the open device fd and read it back. `image` is a path or a function
    returning a fresh stream; `compressed` is an ImageKind (or True for bzip2). bzip2 unpacks on
    every core. Returns bytes written."""
    cancel = cancel or threading.Event()
    kind = _kind(compressed)
    dev_size = os.lseek(fd, 0, os.SEEK_END)
    if callable(image):
        opener, file_size = image, raw_size or 0
    else:
        path = Path(image)
        opener, file_size = (lambda: path.open("rb")), path.stat().st_size
    # Two buffers used in turn (page aligned, as O_DIRECT requires): while one is hashed and written,
    # the next piece of the image is read into the other.
    bufs = [mmap.mmap(-1, CHUNK), mmap.mmap(-1, CHUNK)]
    views = [memoryview(b) for b in bufs]
    hasher = HashWorker()
    try:
        done = _write_and_check(fd, opener, kind, file_size, dev_size, views, hasher, stage, log, cancel,
                                verify, expected_sha256, not callable(image))
    finally:
        hasher.close()
    for v in views:
        v.release()
    for b in bufs:
        b.close()
    return done


def _write_and_check(fd: int, opener, kind: ImageKind, file_size: int, dev_size: int, views, hasher,
                     stage: StageFn, log: LogFn, cancel: threading.Event, verify: bool,
                     expected_sha256: str | None, read_ahead: bool) -> int:
    """write_image's work. With read_ahead, the next piece of the image is read (and unpacked) on a
    worker thread while this one goes to the drive. A stream that's still downloading is read in
    step instead, so nothing is left waiting on the network if the write stops early."""
    view = views[0]

    # A stale backup partition table at the end of the drive can confuse firmware. The cleared part
    # runs to the drive's last whole sector, which on a drive that isn't a multiple of 4 KiB lies
    # past the last 4 KiB boundary.
    sector = _sector_size(fd)
    end = dev_size // sector * sector
    start = max(0, dev_size // ALIGN * ALIGN - TAIL)
    tail = end - start
    if tail:
        view[:tail] = bytes(tail)
        os.pwrite(fd, view[:tail], start)

    md = hashlib.sha256()
    done = 0
    timer = _Timer()
    hashing = [None, None]  # each buffer's hash job; the buffer is free again once it's done
    with opener() as raw:
        counted = _Counting(raw)
        try:
            src = decoder(kind, counted)
        except (OSError, EOFError, ValueError, zipfile.BadZipFile, NotImplementedError, RuntimeError) as e:
            # zipfile's own errors include a packing method it lacks and a password-protected entry
            raise CorruptImage(f"The image file is damaged: {e}") from e
        reader = ThreadPoolExecutor(1, thread_name_prefix="image") if read_ahead else None
        with src:
            try:
                i = 0
                coming = reader.submit(_fill, src, view, counted) if reader else None
                while True:
                    if cancel.is_set():
                        raise Cancelled()
                    view = views[i]
                    t = time.monotonic()
                    try:
                        if coming:
                            got, count = coming.result()
                        else:
                            if hashing[i]:
                                hashing[i].result()
                            got, count = _fill(src, view, counted)
                    except (OSError, EOFError, ValueError, zlib.error, lzma.LZMAError,
                            zipfile.BadZipFile) as e:
                        raise CorruptImage(f"The image file is damaged: {e}") from e
                    timer.read += time.monotonic() - t
                    if not got:
                        break
                    hashing[i] = hasher.feed(md, view[:got])
                    if coming and got == CHUNK:
                        if hashing[1 - i]:
                            hashing[1 - i].result()
                        coming = reader.submit(_fill, src, views[1 - i], counted)
                    padded = _round_up(got)
                    if padded > got:
                        view[got:padded] = bytes(padded - got)
                    if done + padded > dev_size:
                        # The drive may end inside this 4 KiB: pad to its own sector size instead.
                        padded = (got + sector - 1) // sector * sector
                        if done + padded > dev_size:
                            raise RawWriteError("This drive is too small for the image.")
                    t = time.monotonic()
                    os.pwrite(fd, view[:padded], done)
                    timer.write += time.monotonic() - t
                    done += got
                    total = file_size or count
                    stage("write", min(count, total), total)  # a zip's directory is read on top of the image
                    if got < CHUNK:
                        break
                    i = 1 - i
            finally:
                if reader:  # nothing may still be reading the image when it's closed
                    reader.shutdown(wait=True)
    hasher.wait()
    written = md.hexdigest()
    if expected_sha256 and written != expected_sha256:
        raise RawWriteError("The image changed while it was being written. Try again.")
    stage("write", file_size or counted.count, file_size or counted.count)
    stage("sync", 0, 0)
    t = time.monotonic()
    os.fsync(fd)
    timer.write += time.monotonic() - t
    log(timer.summary(done))
    if not verify:
        log("Skipped reading the drive back (Labs setting)")
        return done

    log("Reading the drive back to confirm")
    check = hashlib.sha256()
    hashing = [None, None]
    i = 0
    read = 0
    r0 = time.monotonic()
    while read < done:
        if cancel.is_set():
            raise Cancelled()
        want = min(CHUNK, done - read)
        view = views[i]
        if hashing[i]:
            hashing[i].result()
        n = os.preadv(fd, [view[:_round_up(want)]], read)
        if n < want:
            raise RawWriteError("The drive stopped answering while it was read back.")
        hashing[i] = hasher.feed(check, view[:want])
        read += want
        stage("check", read, done)
        i = 1 - i
    hasher.wait()
    if check.hexdigest() != written:
        raise RawWriteError("The drive didn't store the image correctly. It may be failing or "
                            "counterfeit. Try another drive.")
    rate = done / 1e6 / max(time.monotonic() - r0, 1e-6)
    log(f"Every byte on the drive matches the image (read back at {rate:.1f} MB/s)")
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


def with_drive(
    drive: Drive,
    work: Callable[[int], object],
    stage: StageFn = lambda *_: None,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    udisks: UDisks | None = None,
    recheck: bool = True,
    opener: Callable[[str], int] = open_device,
    eject: bool = True,
):
    """Checks the drive is still a safe USB target, unmounts it, opens the whole drive (one
    password window), runs work(fd), closes it and optionally ejects. Returns what work returns."""
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
    log("Asking for permission to use the whole drive (Linux shows its password window)")
    fd = opener(block)
    try:
        result = work(fd)
    finally:
        os.close(fd)
    if eject:
        stage("eject", 0, 0)
        try:
            drive_obj = ud.drive_of(block)
            if drive_obj:
                ud.power_off(drive_obj)
                log("Drive ejected")
        except UDisksError as e:
            log(f"Could not eject automatically ({e}). Eject it from the file manager.")
    return result


def write_drive(
    drive: Drive,
    image: Path,
    compressed=False,
    stage: StageFn = lambda *_: None,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    udisks: UDisks | None = None,
    recheck: bool = True,
    opener: Callable[[str], int] = open_device,
    verify: bool = True,
    raw_size: int | None = None,
    name: str | None = None,
) -> None:
    """Unmount the drive, open it (password), write and verify the image, then eject. `image` is
    a path, or a function returning a stream (writing while downloading) with its raw_size."""
    cancel = cancel or threading.Event()

    def work(fd: int) -> None:
        log(f"Writing {name or Path(image).name} to {drive.path}")
        write_image(fd, image, compressed, stage, log, cancel, verify=verify, raw_size=raw_size)

    with_drive(drive, work, stage, log, cancel, udisks, recheck, opener)
