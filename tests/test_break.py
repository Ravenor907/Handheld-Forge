"""Adversarial tests for the imaging core: every result is compared with an independent reference
(the standard library's own decoders, a naive loop, a hand-written exFAT checker), faults and
cancels are injected at every step, and every call that could hang runs under a watchdog."""

import bz2
import errno
import gzip
import hashlib
import http.server
import io
import json
import lzma
import os
import random
import struct
import tempfile
import threading
import time
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from handheld_forge import (
    builder,
    download,
    drivetest,
    exfat,
    fat16,
    jobs,
    pbzip2,
    rawwrite,
    ventoy,
    ventoy_config,
)
from handheld_forge.blockdev import FdDisk, FileDisk
from handheld_forge.config import Distro, ImageSource
from handheld_forge.imagekind import ImageKind
from handheld_forge.verify import VerificationError
from tests.test_drive_tools import fat16_image, ventoy_files

MB = 1 << 20
CHUNK = 4 * MB
APP_THREADS = ("bzip2", "image", "hash", "drivetest", "live-download")
FILL = b"\x07"
KIND = {
    "raw": ImageKind.DISK,
    "gz": ImageKind.GZIP,
    "xz": ImageKind.XZ,
    "zip": ImageKind.ZIP,
    "bz2-1": ImageKind.BZIP2,
    "bz2-9": ImageKind.BZIP2,
    "bz2-multi": ImageKind.BZIP2,
}


class Hang(AssertionError):
    pass


def bounded(fn, seconds=40.0, on_hang=None):
    """Runs fn on a watchdog-bounded thread: its result, its exception, or Hang."""
    box = {}

    def go():
        try:
            box["value"] = fn()
        except BaseException as e:  # noqa: BLE001 - handed to the caller
            box["error"] = e

    t = threading.Thread(target=go, daemon=True, name="bounded-call")
    t.start()
    t.join(seconds)
    if t.is_alive():
        if on_hang:
            on_hang()
        raise Hang(f"still running after {seconds} s")
    if "error" in box:
        raise box["error"]
    return box.get("value")


def leaked(before, grace=1.5):
    """Names of threads started since `before` that are still alive after a grace period and are
    either not daemons or carry one of the app's thread names."""
    end = time.monotonic() + grace
    while True:
        extra = [
            t
            for t in threading.enumerate()
            if t not in before and t.is_alive() and (not t.daemon or t.name.startswith(APP_THREADS))
        ]
        if not extra or time.monotonic() > end:
            return sorted(t.name for t in extra)
        time.sleep(0.05)


def mixed(size, seed, random_share=0.3):
    r = random.Random(seed)
    out = bytearray()
    while len(out) < size:
        n = min(r.randint(1, 60_000), size - len(out))
        out += r.randbytes(n) if r.random() < random_share else bytes([r.randrange(256)]) * n
    return bytes(out)


def zipped(data):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as z:
        z.writestr("__MACOSX/._x.img", b"junk")
        z.writestr("x.img", data)
    return buf.getvalue()


def pack(kind, data, seed=0):
    if kind == "raw":
        return data
    if kind == "gz":
        return gzip.compress(data, 1)
    if kind == "xz":
        return lzma.compress(data, preset=0)
    if kind == "zip":
        return zipped(data)
    if kind == "bz2-1":
        return bz2.compress(data, 1)
    if kind == "bz2-9":
        return bz2.compress(data, 9)
    r = random.Random(seed)
    cuts = sorted(r.randint(0, len(data)) for _ in range(2))
    parts = [data[: cuts[0]], data[cuts[0] : cuts[1]], data[cuts[1] :]]
    return b"".join(bz2.compress(p, lv) for p, lv in zip(parts, (1, 9, 5), strict=True))


def unpack(kind, blob):
    """The standard library's own answer for this file."""
    if kind == "raw":
        return blob
    if kind == "gz":
        return gzip.decompress(blob)
    if kind == "xz":
        return lzma.decompress(blob)
    if kind == "zip":
        with zipfile.ZipFile(io.BytesIO(blob)) as z:
            return z.read("x.img")
    return bz2.decompress(blob)


def padded(n):
    return -(-n // 4096) * 4096


def expected_device(dev_size, data):
    """What the device must hold after a write: the image, zeros up to the next 4 KiB, the old
    contents after that, and a cleared last megabyte where the image doesn't reach."""
    dev = bytearray(FILL * dev_size)
    end = dev_size // 4096 * 4096
    tail = min(MB, end)
    dev[end - tail : end] = bytes(tail)
    dev[: padded(len(data))] = data + bytes(padded(len(data)) - len(data))
    return bytes(dev)


class Short:
    """A seekable stream that hands out at most a few bytes per call."""

    def __init__(self, blob, seed=0, most=70_000):
        self.f, self.r, self.most = io.BytesIO(blob), random.Random(seed), most

    def read(self, n=-1):
        if n is None or n < 0:
            return self.f.read()
        return self.f.read(min(n, self.r.randint(1, self.most)))

    def readinto(self, b):
        data = self.read(len(b))
        b[: len(data)] = data
        return len(data)

    def readable(self):
        return True

    def seekable(self):
        return True

    def seek(self, *a):
        return self.f.seek(*a)

    def tell(self):
        return self.f.tell()

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass


class Fixed:
    """A raw source returning exactly n bytes per read (fewer only at the end)."""

    def __init__(self, blob, n):
        self.f, self.n = io.BytesIO(blob), n

    def read(self, _n=-1):
        return self.f.read(self.n)


_BLOBS = {}


def blob_for(kind, size):
    key = (kind, size)
    if key not in _BLOBS:
        data = mixed(size, size % 9973 + 1)
        _BLOBS[key] = (data, pack(kind, data, size))
    return _BLOBS[key]


class Case(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.before = set(threading.enumerate())

    def tearDown(self):
        self.tmp.cleanup()

    def assert_no_leaks(self, what=""):
        self.assertEqual([], leaked(self.before), f"threads left running {what}")

    def write(self, blob, kind, mode="path", dev_size=None, data_len=0, seed=0, fill=True, **kw):
        """Runs write_image on a file standing in for the drive: (returned, device bytes, logs, stages)."""
        if dev_size is None:
            dev_size = padded(data_len) + 2 * MB
        dev = self.dir / "device"
        if fill:
            dev.write_bytes(FILL * dev_size)
        fd = os.open(dev, os.O_RDWR)
        logs, stages = [], []
        user_stage = kw.pop("stage", None)

        def stage(*a):
            stages.append(a)
            if user_stage:
                user_stage(*a)

        try:
            if mode == "path":
                image = self.dir / "image.bin"
                image.write_bytes(blob)
            elif mode == "stream":
                def image():
                    return io.BytesIO(blob)
            else:
                def image():
                    return Short(blob, seed)
            if callable(image):
                kw.setdefault("raw_size", len(blob))
            done = bounded(lambda: rawwrite.write_image(fd, image, KIND[kind], stage, logs.append, **kw))
        finally:
            os.close(fd)
        return done, dev.read_bytes(), logs, stages


# --------------------------------------------------------------------------- write_image

SIZES = [0, 1, 511, 512, 513, CHUNK - 1, CHUNK, CHUNK + 1, 6_000_001, 2 * CHUNK + 513]


class WriteImageDifferential(Case):
    def matrix(self, mode, kinds, sizes):
        bad = []
        for kind in kinds:
            for size in sizes:
                data, blob = blob_for(kind, size)
                want = unpack(kind, blob)
                self.assertEqual(data, want)
                try:
                    done, dev, logs, _ = self.write(blob, kind, mode, data_len=size, seed=size)
                except Exception as e:  # noqa: BLE001 - every failure is collected
                    bad.append(f"{kind} {size} {mode}: {type(e).__name__}: {e}")
                    continue
                if done != size:
                    bad.append(f"{kind} {size} {mode}: returned {done}")
                if dev != expected_device(len(dev), want):
                    at = next(i for i, (a, b) in enumerate(zip(dev, expected_device(len(dev), want),
                                                                 strict=True)) if a != b)
                    bad.append(f"{kind} {size} {mode}: device differs first at byte {at}")
                if not any("Every byte on the drive matches" in m for m in logs):
                    bad.append(f"{kind} {size} {mode}: no read-back confirmation logged")
        self.assertEqual([], bad)
        self.assert_no_leaks(f"after {mode} writes")

    def test_path_images_every_kind_and_boundary(self):
        self.matrix("path", list(KIND), SIZES)

    def test_stream_images_every_kind_and_boundary(self):
        self.matrix("stream", list(KIND), SIZES)

    def test_short_read_streams(self):
        self.matrix("short", [k for k in KIND if k != "zip"], [0, 1, 513, CHUNK - 1, CHUNK + 1, 6_000_001])

    def test_buffers_alternate_without_stale_bytes(self):
        # Chunks 1 and 3 share a buffer, as do 2 and 4; a short last chunk follows a full one in
        # the same buffer, so stale bytes from two chunks back would land on the drive.
        for size in (2 * CHUNK + 1, 3 * CHUNK + 4097, 3 * CHUNK - 1, 4 * CHUNK):
            for mode in ("path", "stream"):
                data = bytes([1 + size % 5]) * CHUNK + random.Random(size).randbytes(size - CHUNK)
                done, dev, _, _ = self.write(data, "raw", mode, data_len=size)
                self.assertEqual(size, done)
                self.assertEqual(expected_device(len(dev), data), dev, f"{size} {mode}")

    def test_verify_off_and_expected_hash(self):
        data, blob = blob_for("gz", CHUNK + 1)
        good = hashlib.sha256(data).hexdigest()
        for mode in ("path", "stream"):
            done, dev, logs, stages = self.write(blob, "gz", mode, data_len=len(data), verify=False)
            self.assertEqual(len(data), done)
            self.assertEqual(expected_device(len(dev), data), dev)
            self.assertFalse([s for s in stages if s[0] == "check"])
            self.assertTrue(any("Skipped reading" in m for m in logs))
            done, dev, _, _ = self.write(blob, "gz", mode, data_len=len(data), expected_sha256=good)
            self.assertEqual(len(data), done)
            with self.assertRaises(rawwrite.RawWriteError) as cm:
                self.write(blob, "gz", mode, data_len=len(data), expected_sha256="0" * 64)
            self.assertIn("changed while it was being written", str(cm.exception))
        self.assert_no_leaks()

    def test_exact_fit_and_too_small(self):
        data = mixed(CHUNK + 4096, 3)
        done, dev, _, _ = self.write(data, "raw", dev_size=len(data))
        self.assertEqual((len(data), data), (done, dev))
        for mode in ("path", "stream"):
            for short_by in (512, 4096):
                with self.assertRaises(rawwrite.RawWriteError) as cm:
                    self.write(data, "raw", mode, dev_size=len(data) - short_by)
                self.assertIn("too small", str(cm.exception))
        self.assert_no_leaks()

    def test_progress_never_passes_its_total(self):
        bad = []
        data = mixed(CHUNK + 1, 6)
        plain = io.BytesIO()
        with zipfile.ZipFile(plain, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as z:
            z.writestr("x.img", data)  # the usual zip: the image is its only entry
        for kind in KIND:
            blob = plain.getvalue() if kind == "zip" else pack(kind, data)
            for mode in ("path", "stream"):
                _, _, _, stages = self.write(blob, kind, mode, data_len=len(data))
                over = [s for s in stages if s[1] > s[2]]
                if over:
                    bad.append(f"{kind} {mode}: {over[0]} (file is {len(blob)} bytes)")
                writes = [s for s in stages if s[0] == "write"]
                checks = [s for s in stages if s[0] == "check"]
                if writes[-1][1] != writes[-1][2] or checks[-1][1:] != (len(data), len(data)):
                    bad.append(f"{kind} {mode}: ends at {writes[-1]} {checks[-1]}")
        self.assertEqual([], bad, "progress reported past 100%")

    def test_last_sector_of_an_odd_sized_drive_is_cleared(self):
        # A backup GPT header lives in the drive's very last 512-byte sector. Drives whose sector
        # count isn't a multiple of 8 are common, and the write is meant to clear that sector.
        data = mixed(8192, 4)
        dev_size = 3 * MB + 512
        _, dev, _, _ = self.write(data, "raw", dev_size=dev_size)
        self.assertEqual(data, dev[:8192])
        self.assertEqual(bytes(512), dev[-512:], "the drive's last sector still holds its old contents")

    def test_image_exactly_the_size_of_an_odd_sized_drive(self):
        # An image as long as the drive, on a drive whose sector count isn't a multiple of 8.
        data = mixed(2 * MB + 512, 5)
        try:
            done, dev, _, _ = self.write(data, "raw", dev_size=len(data))
        except rawwrite.RawWriteError as e:
            self.fail(f"an image that fits the drive exactly was refused: {e}")
        self.assertEqual((len(data), data), (done, dev))


class WriteImageFaults(Case):
    SIZE = 2 * CHUNK + 513

    def test_pwrite_failing_at_each_call(self):
        data = mixed(self.SIZE, 7)
        real = os.pwrite
        for mode in ("path", "stream"):
            for fail_at in range(4):  # call 0 clears the end of the drive, 1 to 3 are the image
                calls = [0]

                def pwrite(fd, buf, off, fail_at=fail_at, calls=calls):
                    calls[0] += 1
                    if calls[0] - 1 == fail_at:
                        raise OSError(errno.EIO, "Input/output error")
                    return real(fd, buf, off)

                with mock.patch.object(os, "pwrite", pwrite):
                    with self.assertRaises(OSError) as cm:
                        self.write(data, "raw", mode, data_len=len(data))
                self.assertEqual(errno.EIO, cm.exception.errno, f"{mode} call {fail_at}")
                self.assert_no_leaks(f"after pwrite failed at call {fail_at} ({mode})")

    def test_write_silently_dropped_is_caught_by_the_read_back(self):
        data = mixed(self.SIZE, 8, random_share=1.0)
        real = os.pwrite
        for drop in (1, 2, 3):
            calls = [0]

            def pwrite(fd, buf, off, drop=drop, calls=calls):
                calls[0] += 1
                return len(buf) if calls[0] - 1 == drop else real(fd, buf, off)

            with mock.patch.object(os, "pwrite", pwrite):
                with self.assertRaises(rawwrite.RawWriteError) as cm:
                    self.write(data, "raw", data_len=len(data))
            self.assertIn("didn't store the image correctly", str(cm.exception), f"chunk {drop}")

    def test_read_back_wrong_byte_in_each_chunk(self):
        data = mixed(self.SIZE, 9)
        real = os.preadv
        lengths = [CHUNK, CHUNK, 513]
        for mode in ("path", "stream"):
            for chunk in range(3):
                for where in (0, lengths[chunk] // 2, lengths[chunk] - 1):
                    calls = [0]

                    def preadv(fd, bufs, off, chunk=chunk, where=where, calls=calls):
                        n = real(fd, bufs, off)
                        if calls[0] == chunk:
                            bufs[0][where] ^= 0x10
                        calls[0] += 1
                        return n

                    with mock.patch.object(os, "preadv", preadv):
                        with self.assertRaises(rawwrite.RawWriteError) as cm:
                            self.write(data, "raw", mode, data_len=len(data))
                    self.assertIn("didn't store the image correctly", str(cm.exception),
                                  f"{mode} chunk {chunk} byte {where}")
        self.assert_no_leaks()

    def test_read_back_cut_short(self):
        data = mixed(self.SIZE, 10)
        real = os.preadv
        for chunk in range(3):
            calls = [0]

            def preadv(fd, bufs, off, chunk=chunk, calls=calls):
                calls[0] += 1
                return 512 if calls[0] - 1 == chunk else real(fd, bufs, off)

            with mock.patch.object(os, "preadv", preadv):
                with self.assertRaises(rawwrite.RawWriteError) as cm:
                    self.write(data, "raw", data_len=len(data))
            self.assertIn("stopped answering", str(cm.exception))
        self.assert_no_leaks()

    def test_source_failing_mid_stream(self):
        data = mixed(self.SIZE, 11)

        class Failing(Short):
            def __init__(self, blob, at, error):
                super().__init__(blob, 1, most=CHUNK)
                self.at, self.error, self.calls = at, error, 0

            def read(self, n=-1):
                self.calls += 1
                if self.calls - 1 == self.at:
                    raise self.error
                return super().read(n)

        for at in range(5):
            for error, want in ((OSError("network gone"), rawwrite.CorruptImage),
                                (RuntimeError("boom"), RuntimeError)):
                dev = self.dir / "device"
                dev.write_bytes(FILL * (padded(len(data)) + MB))
                fd = os.open(dev, os.O_RDWR)
                try:
                    with self.assertRaises(want, msg=f"read {at} {error!r}"):
                        bounded(lambda at=at, error=error, fd=fd: rawwrite.write_image(
                            fd, lambda: Failing(data, at, error), ImageKind.DISK, log=lambda m: None))
                finally:
                    os.close(fd)
                self.assert_no_leaks(f"after the source failed at read {at}")


class WriteImageCancel(Case):
    def cancel_everywhere(self, kind, mode, size):
        data, blob = blob_for(kind, size)
        _, _, _, seq = self.write(blob, kind, mode, data_len=size)
        last_check = max(i for i, s in enumerate(seq) if s[0] == "check")
        bad = []
        for k in range(len(seq)):
            cancel = threading.Event()
            count = [0]

            def stage(*_, k=k, cancel=cancel, count=count):
                if count[0] == k:
                    cancel.set()
                count[0] += 1

            t = time.monotonic()
            try:
                self.write(blob, kind, mode, data_len=size, cancel=cancel, stage=stage)
                if k < last_check:
                    bad.append(f"cancel at stage call {k} {seq[k]}: finished anyway")
            except rawwrite.Cancelled:
                pass
            except Hang:
                bad.append(f"cancel at stage call {k} {seq[k]}: hung")
            except Exception as e:  # noqa: BLE001 - every failure is collected
                bad.append(f"cancel at stage call {k} {seq[k]}: {type(e).__name__}: {e}")
            if time.monotonic() - t > 10:
                bad.append(f"cancel at stage call {k}: took {time.monotonic() - t:.1f} s")
            left = leaked(self.before)
            if left:
                bad.append(f"cancel at stage call {k} {seq[k]}: threads left {left}")
                self.before = set(threading.enumerate())
        self.assertEqual([], bad, f"{kind} {mode}")

    def test_cancel_at_every_stage_raw(self):
        for mode in ("path", "stream"):
            self.cancel_everywhere("raw", mode, 2 * CHUNK + 513)

    def test_cancel_at_every_stage_gz(self):
        for mode in ("path", "stream"):
            self.cancel_everywhere("gz", mode, CHUNK + 1)

    def test_cancel_at_every_stage_bz2(self):
        for mode in ("path", "stream"):
            self.cancel_everywhere("bz2-1", mode, CHUNK + 1)

    def test_cancel_before_the_first_byte(self):
        for kind in ("raw", "bz2-multi", "xz", "zip"):
            data, blob = blob_for(kind, CHUNK + 1)
            for mode in ("path", "stream"):
                cancel = threading.Event()
                cancel.set()
                with self.assertRaises(rawwrite.Cancelled):
                    self.write(blob, kind, mode, data_len=len(data), cancel=cancel)
                self.assert_no_leaks(f"after an early cancel ({kind} {mode})")


# --------------------------------------------------------------------------- corrupt inputs


def mutations(blob, seed, flips=60, cuts=40):
    r = random.Random(seed)
    out = []
    bits = {*range(0, min(len(blob) * 8, 96)), *(r.randrange(len(blob) * 8) for _ in range(flips))}
    for bit in sorted(bits):
        b = bytearray(blob)
        b[bit // 8] ^= 0x80 >> (bit % 8)
        out.append((f"bit {bit} flipped", bytes(b)))
    ends = {0, 1, 2, 3, 4, 5, 10, len(blob) - 1, len(blob) - 2, len(blob) - 4, len(blob) - 5, len(blob) - 11}
    ends |= {r.randrange(len(blob)) for _ in range(cuts)}
    for n in sorted(e for e in ends if 0 <= e < len(blob)):
        out.append((f"cut to {n} bytes", blob[:n]))
    out.append(("100 random bytes appended", blob + r.randbytes(100)))
    out.append(("4096 zero bytes appended", blob + bytes(4096)))
    out.append(("its own first half appended", blob + blob[: len(blob) // 2]))
    out.append(("block marker appended", blob + bytes.fromhex("314159265359") + r.randbytes(40)))
    return out


def judge(original, reference, produce):
    """None if the outcome is right: the documented error, the reference decoder's bytes, or (when
    the reference rejects the file) the undamaged original. Otherwise what went wrong."""
    try:
        ref = reference()
    except Exception:  # noqa: BLE001 - the reference rejecting the file is an expected outcome
        ref = None
    try:
        got = produce()
    except (rawwrite.CorruptImage, OSError):
        return None
    except Hang:
        return "hung"
    except Exception as e:  # noqa: BLE001 - reported as the wrong kind of error
        return f"undocumented error {type(e).__module__}.{type(e).__name__}: {e}"
    if ref is not None and got == ref:
        return None
    if ref is None and got == original:
        return None
    return (f"succeeded with {len(got)} bytes that are neither the reference decoder's "
            f"({'rejected' if ref is None else len(ref)}) nor the original ({len(original)})")


class CorruptThroughWriteImage(Case):
    def attack(self, kind, size=150_000):
        data = mixed(size, 21)
        blob = pack(kind, data, 21)
        bad = []
        for what, hurt in mutations(blob, 5):
            def produce(hurt=hurt):
                done, dev, _, _ = self.write(hurt, kind, dev_size=MB, fill=False)
                return dev[:done]

            (self.dir / "device").write_bytes(bytes(MB))
            verdict = judge(data, lambda hurt=hurt: unpack(kind, hurt), produce)
            if verdict:
                bad.append(f"{kind}, {what}: {verdict}")
        left = leaked(self.before)
        self.assertEqual([], bad[:12], f"{len(bad)} wrong outcomes for damaged {kind} files")
        self.assertEqual([], left)

    def test_damaged_gz(self):
        self.attack("gz")

    def test_damaged_xz(self):
        self.attack("xz")

    def test_damaged_zip(self):
        self.attack("zip")

    def test_damaged_bz2(self):
        self.attack("bz2-1")

    def test_damaged_bz2_multi_stream(self):
        self.attack("bz2-multi")

    def test_empty_files(self):
        bad = []
        for kind in ("gz", "xz", "zip", "bz2-1"):
            def produce(kind=kind):
                done, dev, _, _ = self.write(b"", kind, dev_size=MB)
                return dev[:done]

            verdict = judge(b"", lambda kind=kind: unpack(kind, b""), produce)
            if verdict:
                bad.append(f"empty {kind}: {verdict}")
        self.assertEqual([], bad)


# --------------------------------------------------------------------------- pbzip2


def marker_bits(blob, magic):
    """Every bit position where the 48-bit magic appears in blob, at any alignment."""
    n = int.from_bytes(blob, "big")
    total = len(blob) * 8
    needle = magic.to_bytes(6, "big")
    out = []
    for k in range(8):
        shifted = ((n << k) & ((1 << total) - 1)).to_bytes(len(blob), "big")
        i = shifted.find(needle)
        while i >= 0:
            out.append(i * 8 + k)
            i = shifted.find(needle, i + 1)
    return sorted(out)


def marker_alphabet(magic, reps=1):
    """Byte values whose 'symbols in use' maps, as bzip2 stores them in a block header, spell out
    the magic number: three 16-bit maps per repetition, for consecutive groups of 16 byte values."""
    maps = [(magic >> 32) & 0xFFFF, (magic >> 16) & 0xFFFF, magic & 0xFFFF]
    out = []
    for rep in range(reps):
        for j, m in enumerate(maps):
            out += [(rep * 3 + j) * 16 + bit for bit in range(16) if m & (0x8000 >> bit)]
    return bytes(out)


def marker_data(alphabet, size, seed):
    """Every symbol of the alphabet, then random ones, never four alike in a row (so bzip2's first
    run-length pass adds no symbols of its own)."""
    r = random.Random(seed)
    out = bytearray(alphabet)
    while len(out) < size:
        c = r.choice(alphabet)
        if out[-1] != c:
            out.append(c)
    return bytes(out)


def read_all(blob, raw_n=None, threads=None, fakes=None, seed=0, most=300_000):
    """Everything the parallel reader gives for blob, read in pieces of random sizes."""
    r = random.Random(seed)
    raw = io.BytesIO(blob) if raw_n is None else Fixed(blob, raw_n)
    rd = pbzip2.ParallelBz2Reader(raw, threads=threads, fake_splits=fakes)

    def go():
        out = bytearray()
        while True:
            buf = bytearray(r.randint(1, most))
            n = rd.readinto(buf)
            if not n:
                return bytes(out)
            out += buf[:n]

    try:
        return bounded(go, 60, on_hang=rd.close)
    finally:
        rd.close()


class Bz2Reader(Case):
    def false_markers(self, magic):
        hit = set()
        bad = []
        for seed, level in ((1, 1), (2, 1), (3, 2), (4, 1), (5, 1), (6, 3), (7, 1), (8, 1), (9, 1), (10, 1)):
            if len(hit) == 8:
                break
            data = marker_data(marker_alphabet(magic), 600_000 + seed * 4321, seed)
            blob = bz2.compress(data, level)
            real = marker_bits(blob, pbzip2.BLOCK_MAGIC)
            false = [p for p in marker_bits(blob, magic) if p - 121 in real]
            self.assertTrue(false, "the crafted data holds no false marker")
            hit |= {p % 8 for p in false}
            for raw_n in (None, 4096, 613):
                try:
                    got = read_all(blob, raw_n, seed=seed)
                    if got != data:
                        bad.append(f"seed {seed} raw reads of {raw_n}: wrong bytes")
                except Exception as e:  # noqa: BLE001 - every failure is collected
                    bad.append(f"seed {seed} raw reads of {raw_n}: {type(e).__name__}: {e}")
        self.assertEqual(set(range(8)), hit, "the crafted files don't cover every bit alignment")
        self.assertEqual([], bad)
        self.assert_no_leaks()

    def test_false_block_markers_at_every_alignment(self):
        self.false_markers(pbzip2.BLOCK_MAGIC)

    def test_false_end_markers_at_every_alignment(self):
        self.false_markers(pbzip2.END_MAGIC)

    def test_block_with_several_false_markers(self):
        # A valid file whose block headers hold the marker 2 to 5 times each.
        bad = []
        for reps in (2, 3, 4, 5):
            data = marker_data(marker_alphabet(pbzip2.BLOCK_MAGIC, reps), 150_000, reps)
            blob = bz2.compress(data, 9)
            self.assertEqual(reps + 1, len(marker_bits(blob, pbzip2.BLOCK_MAGIC)))
            self.assertEqual(data, bz2.decompress(blob))
            try:
                if read_all(blob) != data:
                    bad.append(f"{reps} false markers: wrong bytes")
            except OSError as e:
                bad.append(f"{reps} false markers in one block: a valid file was refused ({e})")
        self.assertEqual([], bad)

    def test_false_marker_just_before_a_real_one(self):
        # Known limit, kept as is: a marker-shaped run of bits under 80 bits ahead of a real block marker
        # hides the real one, and the file is refused as damaged (never unpacked to wrong bytes).
        data = mixed(400_000, 31, random_share=1.0)
        blob = bz2.compress(data, 1)
        real = marker_bits(blob, pbzip2.BLOCK_MAGIC)
        self.assertGreaterEqual(len(real), 3)
        for back in (79, 48, 8, 1):
            with self.assertRaises(OSError, msg=f"{back} bits before") as cm:
                read_all(blob, fakes={real[2] - back})
            self.assertIn("damaged block", str(cm.exception))
        self.assert_no_leaks()

    def test_markers_across_every_window_boundary(self):
        small = b"".join(bz2.compress(mixed(n, n), lv) for n, lv in ((3000, 1), (0, 9), (9000, 4), (1, 1)))
        big = b"".join(bz2.compress(mixed(n, n, 0.8), lv) for n, lv in ((350_000, 1), (120_000, 9), (0, 1)))
        bad = []
        r = random.Random(77)
        sizes = [1, 2, 3, 5, 6, 7, 8, 9, 13, 15, 16, 17, 63, 64, 511, 512, 513, 4093, 65_536]
        for blob, ns in ((small, sizes + [r.randint(1, 40) for _ in range(25)]),
                         (big, sizes[2:] + [r.randint(3, 20_000) for _ in range(25)])):
            want = bz2.decompress(blob)
            for n in ns:
                try:
                    if read_all(blob, n, threads=r.choice((1, 2, 8)), seed=n) != want:
                        bad.append(f"raw reads of {n} bytes: wrong bytes")
                except Exception as e:  # noqa: BLE001 - every failure is collected
                    bad.append(f"raw reads of {n} bytes: {type(e).__name__}: {e}")
        self.assertEqual([], bad)
        self.assert_no_leaks()

    def stress_case(self, seed):
        r = random.Random(seed)
        size = int(2 ** r.uniform(0, 21.5)) - 1
        style = r.choice(("random", "zeros", "mixed", "runs", "text"))
        if style == "random":
            data = r.randbytes(size)
        elif style == "zeros":
            data = bytes(size)
        elif style == "mixed":
            data = mixed(size, seed, r.random())
        elif style == "runs":
            runs = (bytes([r.randrange(256)]) * r.randint(1, 600) for _ in range(size // 300 + 1))
            data = b"".join(runs)[:size]
        else:
            data = (b"The quick brown fox %d jumps. " % seed * (size // 20 + 1))[:size]
        cuts = sorted(r.randint(0, size) for _ in range(r.choice((0, 0, 0, 1, 2, 3))))
        parts = [data[a:b] for a, b in zip([0, *cuts], [*cuts, size], strict=True)]
        blob = b"".join(bz2.compress(p, r.randint(1, 9)) for p in parts)
        threads = r.choice((1, 2, 8))
        raw_n = r.choice((1, 7, 4096, 65_537, None))
        if raw_n == 1 and len(blob) > 20_000:
            raw_n = 997
        most = r.choice((1, 100, 4096, 65_536, 300_000))
        if most == 1 and size > 5000:
            most = 37
        fakes = set()
        if r.random() < 0.3 and len(blob) < 400_000:
            blocks = marker_bits(blob, pbzip2.BLOCK_MAGIC)
            marks = sorted(blocks + marker_bits(blob, pbzip2.END_MAGIC))
            for start in r.sample(blocks, min(len(blocks), r.randint(1, 3))):
                end = marks[marks.index(start) + 1]
                if end - start > 400:
                    fakes.add(r.randint(start + 80, end - 81))
        what = (f"seed {seed}: {size} bytes ({style}), {len(parts)} streams, {threads} threads, raw reads "
                f"{raw_n}, reads up to {most}, fake splits {sorted(fakes)}")
        try:
            got = read_all(blob, raw_n, threads, fakes, seed, most)
        except Exception as e:  # noqa: BLE001 - every failure is collected
            return f"{what}: {type(e).__name__}: {e}"
        if got != data or got != bz2.decompress(blob):
            return f"{what}: wrong bytes ({len(got)} of {len(data)})"
        return None

    def test_stress_against_bz2_decompress(self):
        bad = [v for v in (self.stress_case(seed) for seed in range(1000, 1330)) if v]
        self.assertEqual([], bad[:10], f"{len(bad)} of 330 random inputs failed")
        self.assert_no_leaks("after the stress run")

    def test_damaged_files_never_give_wrong_bytes(self):
        data = mixed(160_000, 41, 0.6)
        blob = bz2.compress(data[:110_000], 1) + bz2.compress(data[110_000:], 9)
        r = random.Random(9)
        bad = []
        for what, hurt in mutations(blob, 17, flips=260, cuts=130):
            raw_n = r.choice((None, 1000, 4096, 33))
            verdict = judge(data, lambda hurt=hurt: bz2.decompress(hurt),
                            lambda hurt=hurt, raw_n=raw_n: read_all(hurt, raw_n, r.choice((1, 2, 8))))
            if verdict:
                bad.append(f"{what} (raw reads {raw_n}): {verdict}")
        self.assertEqual([], bad[:10], f"{len(bad)} wrong outcomes")
        self.assert_no_leaks("after damaged files")

    def test_find_cores_matches_a_plain_search(self):
        if not hasattr(pbzip2, "_find_cores"):
            self.skipTest("this tree has no _find_cores")
        r = random.Random(3)
        cores = pbzip2._CORES
        for trial in range(400):
            window = bytearray(r.randbytes(r.randint(0, 3000)))
            for _ in range(r.randint(0, 6)):
                k, core = r.choice(cores)
                piece = core[: r.randint(1, len(core))] if r.random() < 0.3 else core
                at = r.randint(0, max(0, len(window) - 1))
                window[at : at + len(piece)] = piece
            want = sorted({(i, k) for k, core in cores for i in range(len(window))
                           if window.startswith(core, i)})
            got = sorted(set(pbzip2._find_cores(window)))
            self.assertEqual(want, got, f"trial {trial}")
            self.assertEqual(want, sorted(set(pbzip2._find_cores(bytes(window)))), f"trial {trial} (bytes)")

    def test_stream_one_matches_the_general_path(self):
        if not hasattr(pbzip2, "_stream_one"):
            self.skipTest("this tree has no _stream_one")
        r = random.Random(4)
        for trial in range(600):
            off = r.randint(0, 7)
            n = r.choice((80, 81, 87, 88, 89, r.randint(80, 4000)))
            data = r.randbytes((off + n + 7) // 8)
            rng = pbzip2._Range(True, data, off, n)
            fast = pbzip2._stream_one(rng)
            with mock.patch.object(pbzip2, "_stream_one", lambda _r: None):
                slow = pbzip2._stream([rng])
            self.assertIsNotNone(fast, f"trial {trial}")
            self.assertEqual(slow, fast, f"trial {trial}: off {off} bits {n}")

    def test_close_then_threads_stop(self):
        blob = bz2.compress(mixed(900_000, 5, 0.9), 1)
        for threads in (1, 2, 8):
            rd = pbzip2.ParallelBz2Reader(io.BytesIO(blob), threads=threads)
            self.assertEqual(100, len(rd.read(100)))
            rd.close()
            self.assert_no_leaks(f"after close() with {threads} threads")

    def test_close_from_another_thread_while_a_read_waits(self):
        release = threading.Event()

        class Stalled:
            def __init__(self, blob):
                self.f, self.calls = io.BytesIO(blob), 0

            def read(self, n=-1):
                self.calls += 1
                if self.calls > 1:
                    release.wait(30)
                return self.f.read(1000)

        rd = pbzip2.ParallelBz2Reader(Stalled(bz2.compress(mixed(300_000, 8, 0.9), 1)))
        box = {}

        def consume():
            try:
                box["n"] = len(rd.readall())
            except Exception as e:  # noqa: BLE001 - either outcome ends the wait
                box["error"] = e

        t = threading.Thread(target=consume, daemon=True, name="bounded-consumer")
        t.start()
        time.sleep(0.3)
        rd.close()
        release.set()
        t.join(5)
        self.assertFalse(t.is_alive(), "readinto() never returned after close() from another thread")


# --------------------------------------------------------------------------- drivetest


class OddDisk(FileDisk):
    """A FileDisk that misbehaves: capacity that wraps around, writes that vanish, bits that flip."""

    def __init__(self, path, blocks, block_size=512, real_blocks=None, drop=None, flip=None):
        super().__init__(path, blocks, block_size)
        self.real_blocks, self.drop, self.flip = real_blocks, drop, flip

    def _pieces(self, lba, count):
        """(lba on the real medium, sectors, offset into the request) for a wrapping drive."""
        at = 0
        while at < count:
            real = (lba + at) % self.real_blocks
            n = min(count - at, self.real_blocks - real)
            yield real, n, at
            at += n

    def write(self, lba, data):
        data = bytes(data)
        bs = self.block_size
        if self.real_blocks:
            for real, n, at in self._pieces(lba, len(data) // bs):
                super().write(real, data[at * bs : (at + n) * bs])
            return
        if self.drop:
            lo, hi = self.drop  # byte range whose sectors silently keep their old contents
            for i in range(len(data) // bs):
                if not lo <= (lba + i) * bs < hi:
                    super().write(lba + i, data[i * bs : (i + 1) * bs])
            return
        super().write(lba, data)

    def read(self, lba, count):
        bs = self.block_size
        if self.real_blocks:
            return b"".join(super(OddDisk, self).read(real, n) for real, n, _ in self._pieces(lba, count))
        data = super().read(lba, count)
        if self.flip is not None and lba * bs <= self.flip < (lba + count) * bs:
            data = bytearray(data)
            data[self.flip - lba * bs] ^= 0x04
            data = bytes(data)
        return data


class DriveTest(Case):
    ARGS = dict(min_bytes=8 * MB, max_bytes=8 * MB, spots=8, budget_seconds=0.001)

    def run_test(self, dev, seed=11, **kw):
        stages = []
        args = {**self.ARGS, **kw}
        rep = bounded(lambda: drivetest.run(dev, stages.append if False else (lambda *a: stages.append(a)),
                                            lambda m: None, seed=seed, **args), 60)
        return rep, stages

    def spot_positions(self, size, written, spots, bs):
        span = size - written - drivetest.SPOT
        return sorted({(written + span * i // max(1, spots - 1)) // bs * bs for i in range(spots)})

    def test_pattern_is_deterministic_and_chunking_independent(self):
        r = random.Random(1)
        for _ in range(60):
            off = r.randrange(0, 1 << 40, 512)
            a = r.randint(0, 12) * 65536
            b = r.randint(0, 300_000)
            seed = r.getrandbits(r.choice((8, 63, 64, 80)))
            whole = drivetest.pattern(a + b, off, seed)
            self.assertEqual(a + b, len(whole))
            self.assertEqual(whole, drivetest.pattern(a, off, seed) + drivetest.pattern(b, off + a, seed))
            self.assertEqual(whole, drivetest.pattern(a + b, off, seed))
            if a + b >= 16:
                self.assertNotEqual(whole, drivetest.pattern(a + b, off + 65536, seed))
                self.assertNotEqual(whole, drivetest.pattern(a + b, off, seed ^ 1))
                self.assertNotEqual(whole, drivetest.pattern(a + b, off, ~seed))

    def test_wrong_bytes_matches_a_plain_count(self):
        if not hasattr(drivetest, "_wrong_bytes"):
            self.skipTest("this tree has no _wrong_bytes")
        r = random.Random(2)
        for trial in range(120):
            n = r.choice((0, 1, 2, 65_535, 65_536, 65_537, 131_072, r.randint(0, 300_000)))
            a = bytearray(r.randbytes(n))
            b = bytearray(a)
            for _ in range(r.choice((0, 1, 2, 50, 5000))):
                if n:
                    b[r.randrange(n)] = r.randrange(256)
            if r.random() < 0.3:
                b += r.randbytes(r.randint(1, 70_000))
            elif r.random() < 0.3 and n:
                del b[r.randrange(n) :]
            want = sum(1 for x, y in zip(a, b, strict=False) if x != y)
            self.assertEqual(want, drivetest._wrong_bytes(bytes(a), bytes(b)), f"trial {trial}")
            self.assertEqual(want, drivetest._wrong_bytes(memoryview(a), b), f"trial {trial} (views)")

    def test_first_difference_matches_a_plain_scan(self):
        if not hasattr(drivetest, "_first_difference"):
            self.skipTest("this tree has no _first_difference")
        r = random.Random(3)
        for n in (1, 2, 65_535, 65_536, 65_537, 200_000, CHUNK):
            a = r.randbytes(n)
            for at in {0, n - 1, n // 2, 65_535 % n, 65_536 % n, r.randrange(n)}:
                b = bytearray(a)
                b[at] ^= 1
                for extra in range(0, 3):
                    if at + extra * 7 < n:
                        b[at + extra * 7] ^= 1 if extra else 0
                self.assertEqual(at, drivetest._first_difference(a, bytes(b)), f"{n} bytes, byte {at}")

    def test_ahead_gives_the_pattern_planned_or_not(self):
        if not hasattr(drivetest, "_Ahead"):
            self.skipTest("this tree has no _Ahead")
        ahead = drivetest._Ahead(99)
        try:
            ahead.plan(70_000, 4096)
            self.assertEqual(drivetest.pattern(70_000, 4096, 99), ahead.take(70_000, 4096))
            ahead.plan(70_000, 8192)
            self.assertEqual(drivetest.pattern(1000, 8192, 99), ahead.take(1000, 8192))
            self.assertEqual(drivetest.pattern(70_000, 8192, 99), ahead.take(70_000, 8192))
            self.assertEqual(drivetest.pattern(70_000, 8192, 99), ahead.take(70_000, 8192))
            self.assertEqual(b"", ahead.take(0, 0))
        finally:
            ahead.close()
        self.assert_no_leaks()

    def test_clean_drive_and_what_it_leaves_on_it(self):
        for bs in (512, 4096):
            size = 24 * MB
            dev = FileDisk(self.dir / f"clean{bs}", size // bs, bs)
            rep, stages = self.run_test(dev)
            self.assertEqual((False, False, 0, 0), (rep.fake, rep.failing, rep.bad_bytes, rep.bad_spots), bs)
            self.assertEqual((size, 8 * MB, 8), (rep.drive_bytes, rep.tested_bytes, rep.spots))
            raw = (self.dir / f"clean{bs}").read_bytes()
            self.assertEqual(drivetest.pattern(8 * MB, 0, 11), raw[: 8 * MB], "sequential test data")
            for p in self.spot_positions(size, 8 * MB, 8, bs):
                self.assertEqual(drivetest.pattern(MB, p, ~11), raw[p : p + MB], f"marker at {p}")
            self.assertEqual(size - MB, self.spot_positions(size, 8 * MB, 8, bs)[-1])
            self.assertFalse([s for s in stages if s[1] > s[2]], "progress past its total")
            self.assertEqual(stages[-1][1], stages[-1][2])
            self.assertTrue(any("Capacity: all 8 markers" in line for line in rep.lines()))
            dev.close()
        self.assert_no_leaks()

    def test_fake_capacity_is_reported(self):
        for real_mb in (4, 10, 16, 23):
            dev = OddDisk(self.dir / f"fake{real_mb}", 24 * MB // 512, real_blocks=real_mb * MB // 512)
            rep, _ = self.run_test(dev)
            self.assertTrue(rep.fake or rep.failing, f"a {real_mb} MB drive claiming 24 MB passed")
            self.assertTrue(any("FAKE CAPACITY" in x or "LOST DATA" in x for x in rep.lines()))
            dev.close()

    def test_dropped_writes_are_counted_exactly(self):
        lo, hi = 3 * MB + 512, 3 * MB + 512 * 300
        dev = OddDisk(self.dir / "drop", 24 * MB // 512, drop=(lo, hi))
        rep, _ = self.run_test(dev, seed=5)
        want = sum(1 for b in drivetest.pattern(8 * MB, 0, 5)[lo:hi] if b)
        self.assertEqual(want, rep.bad_bytes)
        self.assertTrue(rep.failing)
        dev.close()
        spot = self.spot_positions(24 * MB, 8 * MB, 8, 512)[3]
        dev = OddDisk(self.dir / "drop2", 24 * MB // 512, drop=(spot + 4096, spot + 8192))
        rep, _ = self.run_test(dev, seed=5)
        self.assertEqual((0, 1, spot), (rep.bad_bytes, rep.bad_spots, rep.first_bad_spot))
        dev.close()

    def test_one_flipped_bit_is_one_bad_byte(self):
        for bs in (512, 4096):
            for at in (0, 65_535, 65_536, CHUNK - 1, CHUNK, 8 * MB - 1):
                dev = OddDisk(self.dir / f"flip{bs}", 24 * MB // bs, bs, flip=at)
                rep, _ = self.run_test(dev)
                self.assertEqual((1, 0), (rep.bad_bytes, rep.bad_spots), f"bit flipped in byte {at}")
                dev.close()
        spots = self.spot_positions(24 * MB, 8 * MB, 8, 512)
        dev = OddDisk(self.dir / "flipspot", 24 * MB // 512, flip=spots[-1] + MB - 1)
        rep, _ = self.run_test(dev)
        self.assertEqual((0, 1, spots[-1]), (rep.bad_bytes, rep.bad_spots, rep.first_bad_spot))
        self.assertTrue(rep.fake)
        dev.close()

    def test_too_small_and_smallest(self):
        dev = FileDisk(self.dir / "tiny", (10 * MB - 512) // 512)
        with self.assertRaises(OSError):
            self.run_test(dev)
        dev.close()
        dev = FileDisk(self.dir / "least", 10 * MB // 512)  # min_bytes / 4 + the markers, exactly
        rep, _ = self.run_test(dev)
        self.assertEqual((False, False), (rep.fake, rep.failing), rep.lines())
        dev.close()

    def test_cancel_at_every_stage(self):
        dev = FileDisk(self.dir / "c", 24 * MB // 512)
        _, seq = self.run_test(dev)
        bad = []
        for k in range(len(seq) - 1):
            cancel = threading.Event()
            count = [0]

            def stage(*_, k=k, cancel=cancel, count=count):
                if count[0] == k:
                    cancel.set()
                count[0] += 1

            try:
                bounded(lambda stage=stage, cancel=cancel: drivetest.run(
                    dev, stage, lambda m: None, cancel, seed=11, **self.ARGS), 30)
                bad.append(f"cancel at stage call {k} {seq[k]}: finished anyway")
            except rawwrite.Cancelled:
                pass
            except Hang:
                bad.append(f"cancel at stage call {k}: hung")
            left = leaked(self.before)
            if left:
                bad.append(f"cancel at stage call {k}: threads left {left}")
                self.before = set(threading.enumerate())
        dev.close()
        self.assertEqual([], bad)

    def test_sector_size_that_does_not_divide_the_chunk(self):
        # 520-byte sectors (some enterprise drives): the write loop must finish or refuse, not spin.
        dev = FileDisk(self.dir / "s520", 24 * MB // 520, 520)
        stop = threading.Event()
        try:
            bounded(lambda: drivetest.run(dev, lambda *_: None, lambda m: None, stop, seed=1, **self.ARGS),
                    6, on_hang=stop.set)
        except Hang:
            self.fail("drivetest.run never finishes on a drive with 520-byte sectors (endless write loop)")
        except (OSError, ValueError, rawwrite.RawWriteError):
            pass  # refusing the drive is fine
        finally:
            stop.set()
            time.sleep(0.2)
            dev.close()

    def test_compare_finds_the_first_difference(self):
        data = mixed(2 * CHUNK + 777, 13, 0.9)
        logs = []
        for bs in (512, 4096):
            path = self.dir / f"cmp{bs}"
            path.write_bytes(data + bytes(-len(data) % bs) + bytes(bs * 4))
            dev = FileDisk(path, block_size=bs)
            self.assertIsNone(drivetest.compare(io.BytesIO(data), dev, log=logs.append))
            for at in (0, 1, 65_535, 65_536, CHUNK - 1, CHUNK, 2 * CHUNK, len(data) - 1):
                hurt = bytearray(data)
                hurt[at] ^= 0x80
                hurt[min(at + 100_000, len(data) - 1)] ^= 0x01
                self.assertEqual(at, drivetest.compare(io.BytesIO(bytes(hurt)), dev, log=logs.append), at)
            dev.close()
        small = FileDisk(self.dir / "small", CHUNK // 512)
        self.assertEqual(CHUNK, drivetest.compare(io.BytesIO(bytes(CHUNK + 1)), small, log=logs.append))
        cancel = threading.Event()
        cancel.set()
        with self.assertRaises(rawwrite.Cancelled):
            drivetest.compare(io.BytesIO(bytes(CHUNK)), small, log=logs.append, cancel=cancel)
        small.close()


# --------------------------------------------------------------------------- download


class Site:
    files: dict = {}
    plan: dict = {}  # path -> what the next GETs do: ("ok",), ("cut", n), ("norange", n or None), ("hold", n)
    hits: list = []
    release = threading.Event()


class SiteHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_HEAD(self):
        body = Site.files.get(self.path)
        if body is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()

    def do_GET(self):
        body = Site.files.get(self.path)
        if body is None:
            self.send_error(404)
            return
        steps = Site.plan.get(self.path) or []
        step = steps.pop(0) if steps else ("ok",)
        rng = self.headers.get("Range")
        Site.hits.append((self.path, rng, step))
        if rng and step[0] != "norange":
            start = int(rng.split("=")[1].split("-")[0])
            if start >= len(body):
                self.send_error(416)
                return
            chunk = body[start:]
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{len(body) - 1}/{len(body)}")
        else:
            chunk = body
            self.send_response(200)
        self.send_header("Content-Length", str(len(chunk)))
        self.end_headers()
        if step[0] in ("cut", "norange", "hold") and step[1] is not None:
            self.wfile.write(chunk[: step[1]])
            self.wfile.flush()
            if step[0] == "hold":
                Site.release.wait(30)
            self.close_connection = True
            return
        self.wfile.write(chunk)


class Downloads(Case):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SiteHandler)
        cls.server.daemon_threads = True
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        threading.Thread(target=cls.server.serve_forever, daemon=True, name="test-site").start()
        cls.data = mixed(2 * CHUNK + MB + 3, 51, 0.5)

    @classmethod
    def tearDownClass(cls):
        Site.release.set()
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        super().setUp()
        Site.files.clear()
        Site.plan.clear()
        Site.hits.clear()
        Site.release.clear()
        for p in (mock.patch.object(download, "_backoff", return_value=0),
                  mock.patch.dict(os.environ, {"XDG_CACHE_HOME": self.tmp.name, "NO_PROXY": "*",
                                               "no_proxy": "*"})):
            p.start()
            self.addCleanup(p.stop)
        self.cache = self.dir / "handheld-forge"
        self.cache.mkdir()
        self.name = "img.iso"
        self.digest = hashlib.sha256(self.data).hexdigest()
        self.publish()

    def publish(self, digest=None):
        Site.files[f"/{self.name}"] = self.data
        Site.files[f"/{self.name}.sha"] = f"{digest or self.digest}  {self.name}\n".encode()

    def distro(self):
        src = ImageSource("Test", f"{self.base}/{self.name}", f"{self.base}/{self.name}.sha", None, False)
        return Distro("t", "Test", "e", True, 1, None, [src])

    def leave_part(self, content):
        (self.cache / f"{self.name}.part").write_bytes(content)
        (self.cache / f"{self.name}.part.expected").write_text(self.digest)

    def prepare(self):
        return bounded(lambda: download.prepare_iso(self.distro(), log=lambda m: None), 60)

    def assert_good(self, what=""):
        res = self.prepare()
        self.assertEqual(self.data, res.path.read_bytes(), what)
        self.assertEqual(self.digest, res.sha256)
        self.assertFalse((self.cache / f"{self.name}.part").exists(), what)
        return res

    def clear_cache(self):
        for f in self.cache.iterdir():
            f.unlink()

    def test_resume_from_every_cut_point(self):
        size = len(self.data)
        for cut in (0, 1, CHUNK, size // 2, size - 1, size):
            self.clear_cache()
            self.leave_part(self.data[:cut])
            Site.hits.clear()
            self.assertFalse(self.assert_good(f"resumed from {cut}").from_cache)
            gets = [h for h in Site.hits if h[0] == f"/{self.name}"]
            self.assertEqual([f"bytes={cut}-" if cut else None], [h[1] for h in gets], f"cut {cut}")
        self.clear_cache()
        self.leave_part(self.data + b"x")  # longer than the real file
        with self.assertRaises(VerificationError):
            self.prepare()
        self.assert_good("after the overlong partial file was thrown away")
        self.assert_no_leaks()

    def test_damaged_partial_file_is_not_accepted(self):
        size = len(self.data)
        for cut, at in ((1, 0), (size // 2, 0), (size // 2, size // 2 - 1), (size - 1, 5), (size, 0),
                        (size, size - 1)):
            self.clear_cache()
            hurt = bytearray(self.data[:cut])
            hurt[at] ^= 0x01
            self.leave_part(bytes(hurt))
            with self.assertRaises(VerificationError, msg=f"{cut} bytes kept, byte {at} damaged"):
                self.prepare()
            self.assertFalse((self.cache / self.name).exists())
            self.assertFalse((self.cache / f"{self.name}.part").exists(), "the bad partial file was kept")
            self.assert_good("after the damaged partial file was thrown away")
        self.assert_no_leaks()

    def test_server_ignoring_range(self):
        for cut in (1, CHUNK + 7, len(self.data) - 1):
            self.clear_cache()
            self.leave_part(bytes(cut))  # wrong bytes too: a restart must not keep any of them
            Site.plan[f"/{self.name}"] = [("norange", None)]
            self.assert_good(f"server ignored Range at {cut}")

    def test_drops_and_restarts_in_any_order(self):
        size = len(self.data)
        bad = []
        for seed in range(24):
            r = random.Random(seed)
            self.clear_cache()
            if r.random() < 0.5:
                self.leave_part(self.data[: r.choice((1, CHUNK, r.randrange(size)))])
            steps = []
            for _ in range(r.randint(1, 6)):
                n = r.choice((0, 1, CHUNK - 1, CHUNK, CHUNK + 5, 2 * CHUNK + 9, r.randrange(size)))
                steps.append(r.choice((("cut", n), ("cut", n), ("norange", n), ("norange", None), ("ok",))))
            Site.plan[f"/{self.name}"] = list(steps)
            try:
                self.assert_good()
            except Exception as e:  # noqa: BLE001 - every failure is collected
                bad.append(f"seed {seed}, server steps {steps}: {type(e).__name__}: {e}")
        self.assertEqual([], bad)
        self.assert_no_leaks()

    def test_wrong_checksum_is_not_kept(self):
        self.publish("ab" * 32)
        for _ in range(2):
            with self.assertRaises(VerificationError):
                self.prepare()
            self.assertEqual([], [f.name for f in self.cache.iterdir() if "img.iso" in f.name])
        self.publish()
        self.assert_good("once the published checksum was right")
        self.assertTrue(self.assert_good().from_cache)

    def live(self, expected=None, cancel=None):
        part = self.cache / f"{self.name}.part"
        return download.LiveDownload(
            f"{self.base}/{self.name}", part, self.cache / self.name, self.cache / f"{self.name}.verified",
            self.cache / f"{self.name}.part.expected", expected or self.digest, True, len(self.data),
            lambda *_: None, cancel or threading.Event(), lambda: False)

    def test_live_download_written_while_it_arrives(self):
        for steps in ([], [("cut", CHUNK + 5), ("cut", 0), ("cut", CHUNK)], [("cut", 2 * CHUNK + 1)]):
            self.clear_cache()
            self.leave_part(self.data + self.data)  # a stale, longer partial file
            Site.plan[f"/{self.name}"] = list(steps)
            live = self.live()
            dev = self.dir / "device"
            dev.write_bytes(FILL * (padded(len(self.data)) + 2 * MB))
            fd = os.open(dev, os.O_RDWR)
            try:
                done = bounded(lambda live=live, fd=fd: rawwrite.write_image(
                    fd, live.open, ImageKind.DISK, log=lambda m: None, raw_size=len(self.data)), 60)
            finally:
                os.close(fd)
            self.assertEqual(len(self.data), done, steps)
            self.assertEqual(expected_device(dev.stat().st_size, self.data), dev.read_bytes(), steps)
            self.assertFalse(bounded(lambda live=live: live.finish(lambda m: None), 30))
            self.assertEqual(self.data, (self.cache / self.name).read_bytes())
            self.assertFalse((self.cache / f"{self.name}.part").exists())
            self.assert_no_leaks(f"after a live download with {steps}")

    def test_live_download_with_the_wrong_checksum(self):
        live = self.live(expected="cd" * 32)
        with live.open() as tail:
            got = bounded(tail.read, 30)
        self.assertEqual(self.data, got)
        with self.assertRaises(VerificationError):
            bounded(lambda: live.finish(lambda m: None), 30)
        self.assertFalse((self.cache / f"{self.name}.part").exists())
        self.assertFalse((self.cache / self.name).exists())

    def test_tail_when_the_server_restarts_the_file(self):
        for seed in range(6):
            r = random.Random(seed)
            self.clear_cache()
            Site.plan[f"/{self.name}"] = [("cut", CHUNK + r.randrange(CHUNK)), ("norange", None)]
            live = self.live()

            def slow_read(live=live, r=r):
                out = bytearray()
                with live.open() as tail:
                    while True:
                        buf = bytearray(r.choice((4096, 65_536, MB)))
                        n = tail.readinto(buf)
                        if not n:
                            return bytes(out)
                        out += buf[:n]
                        time.sleep(0.0005)

            try:
                got = bounded(slow_read, 60)
            except OSError:
                got = None  # telling the user the server restarted is the documented outcome
            if got is not None:
                self.assertEqual(self.data, got, f"seed {seed}: wrong bytes read without an error")
                self.assertFalse(bounded(lambda live=live: live.finish(lambda m: None), 30))
            bounded(live.thread.join, 30)
        self.assert_no_leaks()

    def test_tail_cancel_while_waiting_for_bytes(self):
        Site.plan[f"/{self.name}"] = [("hold", 70_000)]
        cancel = threading.Event()
        live = self.live(cancel=cancel)
        tail = live.open()
        threading.Timer(0.5, cancel.set).start()
        t = time.monotonic()
        with self.assertRaises(download.Cancelled):
            bounded(lambda: [tail.read(CHUNK) for _ in range(10_000)], 20)
        self.assertLess(time.monotonic() - t, 5)
        tail.close()
        Site.release.set()
        bounded(live.thread.join, 30)
        self.assert_no_leaks("after a cancelled live download")


# --------------------------------------------------------------------------- jobs: importing images


def part_fd(fd):
    try:
        return os.readlink(f"/proc/self/fd/{fd}").endswith(".part")
    except OSError:
        return False


class Imports(Case):
    def setUp(self):
        super().setUp()
        self.mp = self.dir / "mount"
        self.src = self.dir / "src"
        self.mp.mkdir()
        self.src.mkdir()

    def plan(self, name, blob):
        (self.src / name).write_bytes(blob)
        return jobs.plan_import(self.src / name)

    def run_import(self, plans, cancel=None, stage=None, verify=True, seconds=60):
        stages = []

        def note(*a):
            stages.append(a)
            if stage:
                stage(*a)

        names = bounded(lambda: jobs._import(self.mp, plans, note, lambda m: None,
                                             cancel or threading.Event(), verify, None), seconds)
        return names, stages

    def leftovers(self):
        return sorted(p.name for p in self.mp.iterdir() if p.name.startswith("."))

    def test_boundary_sizes_and_every_kind(self):
        want = {}
        plans = []
        for size in (0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 2 * CHUNK + 513):
            data = mixed(size, size % 977 + 2)
            plans.append(self.plan(f"r{size}.img", data))
            want[f"r{size}.img"] = data
        for kind, ext in (("gz", "gz"), ("xz", "xz"), ("zip", "zip"), ("bz2-1", "bz2"), ("bz2-multi", "bz2")):
            for size in (1, CHUNK, 2 * CHUNK + 513):
                data, blob = blob_for(kind, size)
                plans.append(self.plan(f"{kind}{size}.img.{ext}", blob))
                want[f"{kind}{size}.img"] = data
        names, stages = self.run_import(plans, seconds=120)
        self.assertEqual(list(want), names)
        for name, data in want.items():
            self.assertEqual(data, (self.mp / name).read_bytes(), name)
        self.assertEqual([], self.leftovers())
        self.assertFalse([s for s in stages if s[1] > s[2]], "progress past its total")
        total = sum(len(d) for d in want.values())
        self.assertEqual(("check", total, total), stages[-1])
        self.assert_no_leaks("after a finished import")

    def test_duplicate_names_and_replacing(self):
        a, b = mixed(70_000, 1), mixed(CHUNK + 3, 2)
        (self.mp / "same.img").write_bytes(b"old contents")
        plans = [self.plan("same.img", a), self.plan("same.img", a),
                 self.plan("SAME.IMG.gz", gzip.compress(b)),
                 self.plan("same (2).img", b)]
        names, _ = self.run_import(plans)
        self.assertEqual(len(names), len({n.lower() for n in names}), names)
        self.assertEqual("same.img", names[0])
        got = [(self.mp / n).read_bytes() for n in names]
        self.assertEqual([a, a, b, b], got)
        self.assertEqual(sorted(names), sorted(p.name for p in self.mp.iterdir()))

    def full_at(self, limit):
        real = os.write
        wrote = [0]

        def write(fd, data):
            if not part_fd(fd):
                return real(fd, data)
            room = limit - wrote[0]
            if room <= 0:
                raise OSError(errno.ENOSPC, "No space left on device")
            n = real(fd, bytes(data[:room]))
            wrote[0] += n
            return n

        return mock.patch.object(os, "write", write)

    def drive_full(self, name, blob):
        bad = []
        for limit in (0, 1, CHUNK - 1, CHUNK, CHUNK + 7):
            plans = [self.plan(name, blob)]
            with self.full_at(limit):
                try:
                    self.run_import(plans)
                    bad.append(f"full after {limit} bytes: no error")
                except rawwrite.RawWriteError as e:
                    if "filled up" not in str(e):
                        bad.append(f"full after {limit} bytes: {e}")
                except Exception as e:  # noqa: BLE001 - every failure is collected
                    bad.append(f"full after {limit} bytes: {type(e).__name__}: {e}")
            if self.leftovers():
                bad.append(f"full after {limit} bytes: left {self.leftovers()}")
            left = leaked(self.before)
            if left:
                bad.append(f"full after {limit} bytes: threads left {left}")
                self.before = set(threading.enumerate())
        self.assertEqual([], bad)

    def test_drive_fills_up_while_copying(self):
        self.drive_full("big.img", mixed(2 * CHUNK + 513, 3))

    def test_drive_fills_up_while_unpacking_gz(self):
        self.drive_full("big.img.gz", blob_for("gz", 2 * CHUNK + 513)[1])

    def test_drive_fills_up_while_unpacking_bz2(self):
        self.drive_full("big.img.bz2", blob_for("bz2-1", 2 * CHUNK + 513)[1])

    def test_short_writes(self):
        data = mixed(CHUNK + 4097, 4)
        real = os.write

        def write(fd, buf):
            return real(fd, bytes(buf[:100_003])) if part_fd(fd) else real(fd, buf)

        with mock.patch.object(os, "write", write):
            plans = [self.plan("w.img", data), self.plan("w2.img.gz", gzip.compress(data))]
            names, _ = self.run_import(plans)
        self.assertEqual([data, data], [(self.mp / n).read_bytes() for n in names])

    def test_read_back_that_differs(self):
        data = mixed(2 * CHUNK + 513, 5)
        real = os.readv
        lengths = [CHUNK, CHUNK, 513]
        for chunk in range(3):
            for where in (0, lengths[chunk] - 1):
                calls = [0]

                def readv(fd, bufs, chunk=chunk, where=where, calls=calls):
                    n = real(fd, bufs)
                    if calls[0] == chunk:
                        bufs[0][where] ^= 0x20
                    calls[0] += 1
                    return n

                with mock.patch.object(os, "readv", readv):
                    with self.assertRaises(rawwrite.RawWriteError, msg=f"chunk {chunk} byte {where}") as cm:
                        self.run_import([self.plan("v.img", data)])
                self.assertIn("didn't come back", str(cm.exception))
                self.assert_no_leaks()
        calls = [0]

        def cut_short(fd, bufs):
            calls[0] += 1
            return 0 if calls[0] == 3 else real(fd, bufs)

        with mock.patch.object(os, "readv", cut_short):
            with self.assertRaises(rawwrite.RawWriteError):
                self.run_import([self.plan("v.img", data)])

    def cancel_everywhere(self, name, blob):
        plans = [self.plan(name, blob)]
        _, seq = self.run_import(plans)
        bad = []
        for k in range(len(seq) - 1):
            for f in self.mp.iterdir():
                f.unlink()
            cancel = threading.Event()
            count = [0]

            def stage(*_, k=k, cancel=cancel, count=count):
                if count[0] == k:
                    cancel.set()
                count[0] += 1

            try:
                self.run_import(plans, cancel, stage)
                more = k + 1 < len(seq) and seq[k + 1][0] == "write"
                if seq[k][0] == "write" and more and seq[k][1] < seq[k][2]:
                    bad.append(f"cancel at stage call {k} {seq[k]}: finished anyway")
            except rawwrite.Cancelled:
                pass
            except Hang:
                bad.append(f"cancel at stage call {k} {seq[k]}: hung")
            except Exception as e:  # noqa: BLE001 - every failure is collected
                bad.append(f"cancel at stage call {k} {seq[k]}: {type(e).__name__}: {e}")
            if self.leftovers():
                bad.append(f"cancel at stage call {k} {seq[k]}: left {self.leftovers()}")
            left = leaked(self.before)
            if left:
                bad.append(f"cancel at stage call {k} {seq[k]}: threads left {left}")
                self.before = set(threading.enumerate())
        self.assertEqual([], bad)

    def test_cancel_while_copying(self):
        self.cancel_everywhere("c.img", mixed(2 * CHUNK + 513, 6))

    def test_cancel_while_unpacking_gz(self):
        self.cancel_everywhere("c.img.gz", blob_for("gz", 2 * CHUNK + 513)[1])

    def test_cancel_while_unpacking_bz2(self):
        self.cancel_everywhere("c.img.bz2", blob_for("bz2-1", 2 * CHUNK + 513)[1])

    def damaged(self, kind, ext):
        data, blob = blob_for(kind, 2 * CHUNK + 513)
        bad = []
        r = random.Random(7)
        spots = [len(blob) // 2, len(blob) - 9, *(r.randrange(len(blob) // 4, len(blob)) for _ in range(6))]
        cases = [(f"byte {at} changed", blob[:at] + bytes([blob[at] ^ 0x5A]) + blob[at + 1 :])
                 for at in spots]
        cases += [(f"cut to {n} bytes", blob[:n]) for n in (len(blob) // 2, len(blob) - 1, len(blob) - 13)]
        for what, hurt in cases:
            for f in self.mp.iterdir():
                f.unlink()
            try:
                plans = [self.plan(f"d.img.{ext}", hurt)]
            except OSError:
                continue  # refused while planning, which is fine
            try:
                names, _ = self.run_import(plans)
                got = (self.mp / names[0]).read_bytes()
                if got != data:
                    bad.append(f"{what}: imported {len(got)} wrong bytes without an error")
            except rawwrite.RawWriteError:
                pass
            except Exception as e:  # noqa: BLE001 - reported as the wrong kind of error
                bad.append(f"{what}: undocumented error {type(e).__module__}.{type(e).__name__}: {e}")
            if self.leftovers():
                bad.append(f"{what}: left {self.leftovers()}")
            left = leaked(self.before)
            if left:
                bad.append(f"{what}: threads left {left}")
                self.before = set(threading.enumerate())
        self.assertEqual([], bad)

    def test_damaged_gz_past_its_start(self):
        self.damaged("gz", "gz")

    def test_damaged_xz_past_its_start(self):
        self.damaged("xz", "xz")

    def test_damaged_zip_past_its_start(self):
        self.damaged("zip", "zip")

    def test_damaged_bz2_past_its_start(self):
        self.damaged("bz2-1", "bz2")


# --------------------------------------------------------------------------- ventoy.json


class VentoyJson(Case):
    def setUp(self):
        super().setUp()
        (self.dir / "ventoy").mkdir()
        self.file = self.dir / ventoy_config.PATH

    def refuses(self, texts):
        bad = []
        for text in texts:
            for image in ("a.iso", None):
                self.file.write_text(text, "utf-8")
                try:
                    ventoy_config.set_boot_next(self.dir, image)
                    bad.append(f"{text!r} was accepted (now {self.file.read_text('utf-8')[:40]!r})")
                except OSError:
                    pass
                except Exception as e:  # noqa: BLE001 - reported as the wrong kind of error
                    bad.append(f"{text!r}: {type(e).__name__}: {e}")
                if self.file.read_text("utf-8") != text:
                    bad.append(f"{text!r} was overwritten")
        self.assertEqual([], bad)

    def test_broken_files_are_reported_and_left_alone(self):
        self.refuses(['{', '[1,2]', '"text"', '{"control": {}}', '{"a":1,}', '{"a" 1}', "{'a':1}",
                      '{"a":tru}',
                      '{"a":1}x', '{"control":[}', 'nul', '{"a":"\\x"}', '{"a":"\\u12"}', '{"a":"open}',
                      '{"a":+1}', '{"a":.5}', '{"control": "x"}', '{"a":[1 2]}'])

    def test_broken_numbers_are_reported_and_left_alone(self):
        self.refuses(['{"a": -}', '{"a": 1.2.3}', '{"a": 1e}', '{"a": 1-2}', '{"a": 1e+-3}'])

    def test_nesting_depth_is_capped(self):
        for opener, closer in (("[", "]"), ('{"a":', "}")):
            ok = opener * 63 + "1" + closer * 63
            self.assertEqual(json.loads('{"x":' + ok + "}"),
                             json.loads(ventoy_config.write(ventoy_config.parse('{"x":' + ok + "}"))))
            for depth in (64, 65, 5000, 200_000):
                with self.assertRaises(ventoy_config.JsonError, msg=f"{opener} x {depth}"):
                    ventoy_config.parse('{"x":' + opener * depth + "1" + closer * depth + "}")
        self.refuses(['{"x":' + "[" * 100_000 + "]" * 100_000 + "}", "[" * 100_000])

    def test_control_characters_in_text_are_reported(self):
        self.refuses(['{"a": "line\nbreak"}', '{"a": "tab\there"}', '{"a": "nul\x00"}', '{"a\x1f": 1}'])
        self.assertEqual({"a": "\n\t\x01"}, ventoy_config.parse('{"a": "\\n\\t\\u0001"}'))

    def test_bad_unicode_escapes_are_reported(self):
        self.refuses(['{"a": "\\u+123"}', '{"a": "\\u-123"}', '{"a": "\\u 123"}', '{"a": "\\u12_3"}',
                      '{"a": "\\u0x1f"}', '{"a": "\\u12g4"}', '{"a": "\\u123"}', '{"a": "\\u"}',
                      '{"a": "\\ud83d"}', '{"a": "\\ude00"}', '{"a": "\\ud83d\\u0041"}',
                      '{"a": "\\ud83dx"}', '{"a": "\\ud83d\\ud83d"}', '{"a": "\\ud83d\\u+e00"}'])
        self.assertEqual({"a": "\U0001f600\u00e9A"},
                         ventoy_config.parse('{"a": "\\uD83D\\uDE00\\u00E9\\u0041"}'))
        self.assertFalse(self.file.with_name("ventoy.json.new").exists())

    def test_valid_numbers_keep_their_text(self):
        nums = ["0", "-0", "7", "-12", "1.50", "0.000", "1e5", "1E+05", "-3.25e-07",
                "123456789012345678901234567890"]
        text = '{"n": [' + ", ".join(nums) + "]}"
        out = ventoy_config.with_boot_next(text, None)
        self.assertEqual(nums, [line.strip().rstrip(",") for line in out.splitlines()[2:-2]])
        self.refuses(['{"a": 01}', '{"a": 1.}', '{"a": -.5}', '{"a": 1e5.5}', '{"a": 0x10}', '{"a": --1}',
                      '{"a": 1.e3}', '{"a": \u0661}', '{"a": 1_000}', '[-]', '{"a": 1 2}'])

    def test_our_keys_sharing_an_entry_with_others(self):
        text = ('{"control": [{"VTOY_DEFAULT_IMAGE": "/old.iso", "VTOY_WIN11_BYPASS_CHECK": "1"}, '
                '{"VTOY_MENU_TIMEOUT": "9", "VTOY_SECONDARY_BOOT_MENU": "1"}, '
                '{"VTOY_LINUX_REMOUNT": "1", "VTOY_MENU_TIMEOUT": "3", "VTOY_DEFAULT_SEARCH_ROOT": "/iso"}, '
                '{}]}')
        rest = [{"VTOY_WIN11_BYPASS_CHECK": "1"},
                {"VTOY_LINUX_REMOUNT": "1", "VTOY_DEFAULT_SEARCH_ROOT": "/iso"}, {}]
        self.file.write_text(text, "utf-8")
        self.assertEqual("old.iso", ventoy_config.read_boot_next(self.dir))
        ventoy_config.set_boot_next(self.dir, "n.iso")
        now = json.loads(self.file.read_text("utf-8"))
        self.assertEqual([*rest, {"VTOY_DEFAULT_IMAGE": "/n.iso"}, {"VTOY_MENU_TIMEOUT": "5"},
                          {"VTOY_SECONDARY_BOOT_MENU": "0"}], now["control"])
        self.file.write_text(text, "utf-8")
        ventoy_config.set_boot_next(self.dir, None)
        self.assertEqual({"control": rest}, json.loads(self.file.read_text("utf-8")))
        self.assertIsNone(ventoy_config.read_boot_next(self.dir))

    def test_file_that_is_not_utf8_is_reported_and_left_alone(self):
        raw = b'{"menu_alias": [{"image": "/a.iso", "alias": "caf\xe9"}]}'
        for image in ("a.iso", None):
            self.file.write_bytes(raw)
            with self.assertRaises(OSError) as cm:
                ventoy_config.set_boot_next(self.dir, image)
            self.assertNotIsInstance(cm.exception, UnicodeError)
            self.assertIn("Fix or remove it on a computer", str(cm.exception))
            self.assertEqual(raw, self.file.read_bytes())
        with self.assertRaises(OSError) as cm:
            ventoy_config.read_boot_next(self.dir)
        self.assertNotIsInstance(cm.exception, UnicodeError)
        self.assertEqual(["ventoy.json"], sorted(p.name for p in self.file.parent.iterdir()))

    def test_repeated_key_is_reported_and_left_alone(self):
        texts = ['{"theme": {"file": "/a"}, "theme": {"file": "/b"}}',
                 '{"control": [{"VTOY_DEFAULT_IMAGE": "/x.iso"}], '
                 '"menu_alias": [{"image": "/a", "image": "/b"}]}',
                 '{"control": [], "control": [{"VTOY_LINUX_REMOUNT": "1"}]}']
        self.refuses(texts)
        for text in texts:
            with self.assertRaises(OSError) as cm:
                ventoy_config.with_boot_next(text, "a.iso")
            self.assertIn("Fix or remove it on a computer", str(cm.exception))
        self.assertEqual("x.iso", ventoy_config.boot_next(texts[1]))  # reading is still answered
        same_name_apart = '{"a": {"k": 1}, "b": {"k": 2}, "c": [{"k": 3}, {"k": 4}]}'
        self.assertEqual(json.loads(same_name_apart),
                         json.loads(ventoy_config.with_boot_next(same_name_apart, None)))

    def test_set_and_clear_keep_everything_else(self):
        text = ('{"theme": {"file": "/t/theme.txt", "n": 1.50, "big": 1e5, "neg": -0}, "control": ['
                '{"VTOY_WIN11_BYPASS_CHECK": "1"}, {"VTOY_DEFAULT_IMAGE": "/old.iso"}, '
                '{"VTOY_MENU_TIMEOUT": "9"}, {"VTOY_LINUX_REMOUNT": "1"}], '
                '"menu_alias": [{"image": "/a b.iso", "alias": "caf\\u00e9 \\"q\\" \\\\ \\n"}], "z": null}')
        self.file.write_text(text, "utf-8")
        orig = json.loads(text)
        ventoy_config.set_boot_next(self.dir, "new one.iso")
        now = json.loads(self.file.read_text("utf-8"))
        self.assertEqual(list(orig), list(now))
        self.assertEqual({k: v for k, v in orig.items() if k != "control"},
                         {k: v for k, v in now.items() if k != "control"})
        self.assertEqual([{"VTOY_WIN11_BYPASS_CHECK": "1"}, {"VTOY_LINUX_REMOUNT": "1"},
                          {"VTOY_DEFAULT_IMAGE": "/new one.iso"}, {"VTOY_MENU_TIMEOUT": "5"},
                          {"VTOY_SECONDARY_BOOT_MENU": "0"}], now["control"])
        self.assertIn("1.50", self.file.read_text("utf-8"))
        self.assertEqual("new one.iso", ventoy_config.read_boot_next(self.dir))
        ventoy_config.set_boot_next(self.dir, None)
        now = json.loads(self.file.read_text("utf-8"))
        self.assertEqual(list(orig), list(now))
        self.assertEqual([{"VTOY_WIN11_BYPASS_CHECK": "1"}, {"VTOY_LINUX_REMOUNT": "1"}], now["control"])
        self.assertIsNone(ventoy_config.read_boot_next(self.dir))
        self.file.unlink()
        ventoy_config.set_boot_next(self.dir, "x.iso")
        self.assertEqual("x.iso", ventoy_config.read_boot_next(self.dir))
        ventoy_config.set_boot_next(self.dir, None)
        self.assertFalse(self.file.exists())

    def test_valid_json_round_trips(self):
        r = random.Random(1)

        def value(depth):
            pick = r.randrange(8 if depth < 4 else 6)
            if pick == 0:
                return r.choice((True, False, None))
            if pick in (1, 2):
                return r.choice((0, -1, 17, 2.5, 1e20, -3.25e-7))
            if pick in (3, 4, 5):
                return "".join(r.choice('ab "\\/\n\té中\x01{}[],:') for _ in range(r.randint(0, 8)))
            if pick == 6:
                return [value(depth + 1) for _ in range(r.randint(0, 4))]
            return {f"k{i}{r.randint(0, 9)}": value(depth + 1) for i in range(r.randint(0, 4))}

        for trial in range(300):
            root = {"x": value(0), "y": value(0)}
            text = json.dumps(root, indent=r.choice((None, 2)), ensure_ascii=r.random() < 0.5)
            out = ventoy_config.with_boot_next(text, "i.iso")
            back = json.loads(out)
            self.assertEqual("/i.iso", back.pop("control")[0]["VTOY_DEFAULT_IMAGE"], trial)
            self.assertEqual(root, back, f"trial {trial}: {text!r}")
            self.assertEqual(list(root), list(back))

    def test_escaped_emoji_in_another_setting(self):
        # Valid JSON: an emoji written as a surrogate pair of \u escapes, in a setting that isn't ours.
        text = '{"menu_alias": [{"image": "/a.iso", "alias": "\\ud83d\\ude00 Fun"}]}'
        self.file.write_text(text, "utf-8")
        try:
            ventoy_config.set_boot_next(self.dir, "a.iso")
        except UnicodeError as e:
            self.fail(f"a valid ventoy.json with an escaped emoji can't be updated: {e}")
        now = json.loads(self.file.read_text("utf-8"))
        self.assertEqual(json.loads(text)["menu_alias"], now["menu_alias"])


# --------------------------------------------------------------------------- exFAT, Ventoy, FAT16


def table_sum(data):
    s = 0
    for x in data:
        s = ((s >> 1) | ((s & 1) << 31)) + x & 0xFFFFFFFF
    return s


def check_exfat(t, dev, start, sectors, label, spc=None):
    """A minimal exFAT checker written from the published format, not from the formatter."""
    boot, backup = dev.read(start, 12), dev.read(start + 12, 12)
    t.assertEqual(boot, backup, "backup boot region differs from the main one")
    t.assertEqual(b"\xeb\x76\x90EXFAT   " + bytes(53), boot[:64])
    poff, vlen, fat_off, fat_len, heap, count, root, _serial, rev, _flags = struct.unpack_from(
        "<QQIIIIIIHH", boot, 64)
    t.assertEqual((start, 9, 1, 0x100), (poff, boot[108], boot[110], rev))
    t.assertEqual(b"\x55\xaa", boot[510:512])
    for s in range(1, 9):
        t.assertEqual(b"\x00\x00\x55\xaa", boot[s * 512 + 508 : s * 512 + 512], f"extended boot sector {s}")
    csum = 0
    for i, x in enumerate(boot[: 11 * 512]):
        if i not in (106, 107, 112):
            csum = ((csum >> 1) | ((csum & 1) << 31)) + x & 0xFFFFFFFF
    t.assertEqual(struct.pack("<I", csum) * 128, boot[11 * 512 :], "boot checksum sector")
    cs = 1 << boot[109]
    if spc:
        t.assertEqual(spc, cs)
    t.assertLessEqual(boot[109], 16)
    t.assertTrue(sectors - cs < vlen <= sectors or vlen == sectors, f"volume length {vlen} of {sectors}")
    t.assertGreaterEqual(fat_off, 24)
    t.assertGreaterEqual(fat_len * 512, (count + 2) * 4)
    t.assertLessEqual(fat_off + fat_len, heap)
    t.assertLessEqual(heap + count * cs, vlen, "the cluster heap runs past the volume")
    most = min((vlen - heap) // cs, 0xFFFFFFF5)
    t.assertLessEqual(count, most, "cluster count")
    t.assertLessEqual((most - count) * cs, 2048, "over 1 MiB of the volume is left out of the cluster heap")
    t.assertTrue(2 <= root < count + 2)
    t.assertFalse(any(dev.read(start + 24, fat_off - 24)), "old data between the boot region and the FAT")
    if heap > fat_off + fat_len:
        t.assertFalse(any(dev.read(start + fat_off + fat_len, heap - fat_off - fat_len)))
    fat_head = dev.read(start + fat_off, min(fat_len, 2048))
    entries = struct.unpack_from(f"<{len(fat_head) // 4}I", fat_head)
    t.assertEqual((0xFFFFFFF8, 0xFFFFFFFF), entries[:2])
    t.assertFalse(any(dev.read(start + fat_off + fat_len - 1, 1)) and fat_len > 2048)

    def chain(c):
        out = []
        while c != 0xFFFFFFFF:
            t.assertTrue(2 <= c < count + 2 and c not in out and len(out) < 100_000, f"bad chain at {c}")
            out.append(c)
            c = entries[c]
        return out

    def clusters(cl):
        return b"".join(dev.read(start + heap + (c - 2) * cs, cs) for c in cl)

    root_chain = chain(root)
    rd = clusters(root_chain)
    found = {}
    for o in range(0, len(rd), 32):
        if rd[o] == 0:
            t.assertFalse(any(rd[o:]), "data after the end of the root directory")
            break
        t.assertNotIn(rd[o], found, "duplicate root entry")
        found[rd[o]] = rd[o : o + 32]
    t.assertEqual({0x81, 0x82, 0x83}, set(found))
    lab = found[0x83]
    t.assertEqual(label, lab[2 : 2 + lab[1] * 2].decode("utf-16-le"))
    bm_first, bm_len = struct.unpack_from("<IQ", found[0x81], 20)
    t.assertEqual((count + 7) // 8, bm_len)
    t.assertEqual(0, found[0x81][1] & 1)
    up_sum, = struct.unpack_from("<I", found[0x82], 4)
    up_first, up_len = struct.unpack_from("<IQ", found[0x82], 20)
    bm_chain, up_chain = chain(bm_first), chain(up_first)
    t.assertEqual(-(-bm_len // (cs * 512)), len(bm_chain))
    t.assertEqual(-(-up_len // (cs * 512)), len(up_chain))
    t.assertEqual(up_sum, table_sum(clusters(up_chain)[:up_len]), "up-case table checksum")
    used = sorted(bm_chain + up_chain + root_chain)
    t.assertEqual(len(used), len(set(used)), "two files share a cluster")
    bitmap = clusters(bm_chain)
    t.assertFalse(any(bitmap[bm_len:]))
    head = bitmap[: len(used) // 8 + 9]
    marked = [2 + i * 8 + b for i, x in enumerate(head) for b in range(8) if x >> b & 1]
    t.assertEqual(used, marked, "allocation bitmap doesn't match the FAT chains")
    t.assertEqual(len(used), sum(bin(x).count("1") for x in bitmap[:bm_len]))
    t.assertFalse(any(entries[c] for c in range(2, len(entries)) if c not in used), "stray FAT entries")
    t.assertIn(boot[112], (0xFF, len(used) * 100 // count))
    t.assertFalse(any(dev.read(start + max(heap + len(used) * cs, vlen - 2048), min(2048, vlen - heap))),
                  "old data in the volume's last megabyte")
    return vlen, cs, count


def dirty_disk(path, size):
    """A sparse disk whose first 4 MiB and last 4 MiB hold junk, as a used drive would."""
    with open(path, "wb") as f:
        f.truncate(size)
        f.write(b"\x55" * (4 * MB))
        f.seek(size - 4 * MB)
        f.write(b"\xaa" * (4 * MB))
    return FileDisk(path)


class ExFat(Case):
    def test_plain_format_over_many_sizes(self):
        sizes = [16 * MB, 16 * MB + 512 * 7, 100_000_000 - 512 * 3, 1 << 30, 31_990_000_128, 32_100_000_256,
                 64 << 30, (512 << 30) + 512, (2 << 40) - 512, 2 << 40]
        for size in sizes:
            dev = dirty_disk(self.dir / "d", size)
            bounded(lambda dev=dev: exfat.plain_format(dev, log=lambda m: None), 60)
            mbr = dev.read(0, 1)
            self.assertEqual(b"\x55\xaa", mbr[510:512], size)
            self.assertEqual(bytes(48), mbr[462:510])
            start, count = struct.unpack_from("<II", mbr, 454)
            self.assertEqual((0x07, 2048), (mbr[450], start))
            self.assertLessEqual(start + count, dev.block_count, size)
            self.assertGreater(start + count, dev.block_count - 4096, size)
            self.assertFalse(any(dev.read(1, 2047)), "old data before the partition")
            self.assertFalse(any(dev.read(dev.block_count - 2048, 2048)), "old data at the end of the drive")
            vlen, cs, _ = check_exfat(self, dev, start, count, "USB DRIVE")
            self.assertEqual(256 if vlen * 512 > 32_000_000_000 else 64, cs, size)
            dev.close()
            (self.dir / "d").unlink()

    def test_format_volume_cluster_sizes_and_offsets(self):
        r = random.Random(2)
        dev = dirty_disk(self.dir / "v", 300 * MB)
        cases = [(0, 256 * 2048, spc) for spc in (1, 2, 8, 64, 256, 2048, 16384)]
        cases += [(r.choice((0, 63, 2048, 4097)), r.randint(200, 130_000), 1 << r.randint(0, 9))
                  for _ in range(150)]
        made = 0
        for off, sectors, spc in cases:
            os.pwrite(dev.fd, b"\x33" * (sectors * 512), off * 512)
            try:
                exfat.format_volume(dev, off, sectors, "Label 1", spc)
            except ValueError:
                continue  # too small for this cluster size
            check_exfat(self, dev, off, sectors, "Label 1", spc)
            made += 1
        self.assertGreater(made, 80)
        dev.close()

    def too_big(self, work):
        dev = dirty_disk(self.dir / "huge", 4 << 40)
        try:
            bounded(lambda: work(dev), 120)
            parts = ventoy.read_partitions(dev)
            self.assertTrue(parts and all(p.start + p.sectors <= dev.block_count for p in parts))
        except (OSError, ValueError, rawwrite.RawWriteError):
            self.assertEqual(b"\x55" * 512, dev.read(0, 1), "refused, but only after erasing the drive")
        except struct.error as e:
            wiped = dev.read(0, 1) != b"\x55" * 512
            self.fail(f"a 4 TiB drive crashes with struct.error ({e})"
                      + (" after the old partition table was already erased" if wiped else ""))
        finally:
            dev.close()

    def test_plain_format_on_a_drive_over_2_tib(self):
        self.too_big(lambda dev: exfat.plain_format(dev, log=lambda m: None))

    def test_ventoy_on_a_drive_over_2_tib(self):
        self.too_big(lambda dev: ventoy.install(dev, VentoyInstall.files(), log=lambda m: None))


class VentoyInstall(Case):
    _files = None

    @classmethod
    def files(cls):
        if VentoyInstall._files is None:
            VentoyInstall._files = ventoy_files()
        return VentoyInstall._files

    def test_install_then_detect_from_the_minimum_size_up(self):
        files = self.files()
        part2 = lzma.decompress(files.disk_img_xz)
        core = lzma.decompress(files.core_img_xz)
        low = ventoy.MIN_BYTES // 512
        for sectors in (low, low + 1, low + 3, low + 7, low + 8, (4 << 30) // 512, (33 << 30) // 512 + 5,
                        (34 << 30) // 512, (64 << 30) // 512 + 1):
            dev = dirty_disk(self.dir / "v", sectors * 512)
            bounded(lambda dev=dev: ventoy.install(dev, files, log=lambda m: None), 60)
            found = ventoy.detect(dev)
            self.assertIsNotNone(found, sectors)
            p1, p2 = found
            self.assertEqual((2048, 0, ventoy.PART2_SECTORS), (p1.start, p2.start % 8, p2.sectors), sectors)
            self.assertEqual(p1.start + p1.sectors, p2.start)
            self.assertLessEqual(p2.start + p2.sectors, sectors)
            self.assertGreater(p2.start + p2.sectors, sectors - 9)
            self.assertEqual(part2, ventoy.read_efi(dev, p2))
            self.assertEqual("9.9.9", ventoy.version_of(ventoy.read_efi(dev, p2)))
            self.assertTrue(ventoy.secure_boot_enabled(ventoy.read_efi(dev, p2)))
            self.assertEqual(core, dev.read(1, 2047)[: len(core)])
            mbr = dev.read(0, 1)
            self.assertEqual(files.boot_img[:384] + files.boot_img[400:440], mbr[:384] + mbr[400:440])
            self.assertEqual((0x80, 0x07, 0x00, 0xEF), (mbr[446], mbr[450], mbr[462], mbr[466]))
            _, cs, _ = check_exfat(self, dev, p1.start, p1.sectors, "Ventoy")
            self.assertEqual(256 if sectors // 2097152 > 32 else 64, cs)
            dev.close()
            (self.dir / "v").unlink()
        small = FileDisk(self.dir / "small", low - 1)
        with self.assertRaises(rawwrite.RawWriteError):
            ventoy.install(small, files, log=lambda m: None)
        small.close()

    def test_secure_boot_off(self):
        dev = FileDisk(self.dir / "s", (2 << 30) // 512)
        ventoy.install(dev, self.files(), log=lambda m: None, secure_boot=False)
        _, p2 = ventoy.detect(dev)
        efi = ventoy.read_efi(dev, p2)
        self.assertFalse(ventoy.secure_boot_enabled(efi))
        fs = fat16.Fat16(bytearray(efi))
        self.assertEqual(bytes(i % 7 for i in range(150_000)), fs.read("/EFI/BOOT/BOOTX64.EFI"))
        self.assertEqual(bytes(i % 5 for i in range(90_000)), fs.read("/EFI/BOOT/BOOTIA32.EFI"))
        self.assertEqual(efi[fs.reserved * 512 : (fs.reserved + fs.fat_sectors) * 512],
                         efi[(fs.reserved + fs.fat_sectors) * 512 : (fs.reserved + 2 * fs.fat_sectors) * 512])
        dev.close()

    def test_read_back_catches_a_flipped_bit_anywhere_ventoy_wrote(self):
        sectors = (2 << 30) // 512
        p = ventoy.plan(sectors)
        spots = {"sector 0": 100, "boot code": 512 * 700 + 3, "boot code end": 512 * 1000 + 511,
                 "EFI partition start": p.part2_start * 512,
                 "EFI partition middle": p.part2_start * 512 + 9 * MB + 5,
                 "EFI partition end": (p.part2_start + ventoy.PART2_SECTORS) * 512 - 1}
        bad = []
        for what, at in spots.items():
            dev = OddDisk(self.dir / "f", sectors, flip=at)
            try:
                ventoy.install(dev, self.files(), log=lambda m: None)
                bad.append(f"{what}: installed without noticing")
            except rawwrite.RawWriteError as e:
                if "didn't keep" not in str(e):
                    bad.append(f"{what}: {e}")
            dev.close()
        self.assertEqual([], bad)

    def test_read_back_covers_the_exfat_structures(self):
        # install() says it reads back everything except the empty exFAT space; the boot sector, FAT,
        # bitmap and root directory of the images partition are not empty space.
        sectors = (2 << 30) // 512
        bad = []
        for what, at in {"exFAT boot sector": 2048 * 512 + 3, "exFAT FAT": (2048 + 128) * 512}.items():
            dev = OddDisk(self.dir / "f", sectors, flip=at)
            try:
                ventoy.install(dev, self.files(), log=lambda m: None)
                bad.append(f"a bit that reads back flipped in the {what} went unnoticed")
            except rawwrite.RawWriteError:
                pass
            dev.close()
        self.assertEqual([], bad)

    def test_cancel_at_every_stage(self):
        dev = FileDisk(self.dir / "c", (2 << 30) // 512)
        seq = []
        ventoy.install(dev, self.files(), lambda *a: seq.append(a), lambda m: None)
        self.assertFalse([s for s in seq if s[1] > s[2]], "progress past its total")
        for k in range(len(seq) - 2):
            cancel = threading.Event()
            count = [0]

            def stage(*_, k=k, cancel=cancel, count=count):
                if count[0] == k:
                    cancel.set()
                count[0] += 1

            with self.assertRaises(rawwrite.Cancelled, msg=f"cancel at stage call {k} {seq[k]}"):
                bounded(lambda stage=stage, cancel=cancel: ventoy.install(
                    dev, self.files(), stage, lambda m: None, cancel), 30)
        dev.close()


class Fat16Replace(Case):
    def image(self):
        r = random.Random(3)
        self.tree = {f"{n}.bin": r.randbytes(size) for n, size in
                     (("a", 300_000), ("b", 5000), ("c", 700_001), ("d", 2048), ("e", 123_456))}
        self.tree["EFI/BOOT/deep.efi"] = r.randbytes(40_000)
        return fat16.Fat16(fat16_image(self.tree))

    def free(self, fs):
        return sum(1 for c in range(2, fs.cluster_count + 2) if fs.fat(c) == 0)

    def check(self, fs, changed=None):
        for path, data in {**self.tree, **(changed or {})}.items():
            self.assertEqual(data, fs.read("/" + path), path)
        a = fs.reserved * fs.bps
        n = fs.fat_sectors * fs.bps
        self.assertEqual(fs.image[a : a + n], fs.image[a + n : a + 2 * n], "the two FAT copies differ")
        owners = {}
        for path in {**self.tree, **(changed or {})}:
            e = fs.find("/" + path)
            for c in fs.chain(e.first_cluster) if e.first_cluster else []:
                self.assertNotIn(c, owners, f"{path} shares cluster {c} with {owners.get(c)}")
                owners[c] = path

    def test_exactly_the_room_there_is(self):
        fs = self.image()
        old = len(fs.chain(fs.find("/e.bin").first_cluster))
        data = random.Random(4).randbytes((self.free(fs) + old) * fs.cluster_bytes - 5)
        fs.replace("/e.bin", data)
        self.check(fs, {"e.bin": data})
        self.assertEqual(0, self.free(fs))
        fs.replace("/e.bin", b"")
        self.assertEqual(b"", fs.read("/e.bin"))
        self.assertEqual(0, struct.unpack_from("<H", fs.image, fs.find("/e.bin").offset + 26)[0])
        fs.replace("/e.bin", data[:1])
        self.check(fs, {"e.bin": data[:1]})

    def test_one_cluster_too_many_leaves_the_old_file(self):
        fs = self.image()
        old = len(fs.chain(fs.find("/e.bin").first_cluster))
        before = bytes(fs.image)
        with self.assertRaises(OSError):
            fs.replace("/e.bin", bytes((self.free(fs) + old) * fs.cluster_bytes + 1))
        try:
            self.assertEqual(self.tree["e.bin"], fs.read("/e.bin"))
        except OSError as e:
            self.fail(f"after a replace() that failed for lack of room, the old file is unreadable: {e}")
        self.assertEqual(before, bytes(fs.image), "a failed replace() changed the image")

    def test_fragmented_free_space(self):
        fs = self.image()
        self.assertTrue(fs.delete("/a.bin") and fs.delete("/c.bin"))
        del self.tree["a.bin"], self.tree["c.bin"]
        r = random.Random(5)
        for size in (290_000, 900_000, 1_100_000, 10, 2_000_000, 0, 1_050_000):
            data = r.randbytes(size)
            fs.replace("/e.bin", data)
            self.check(fs, {"e.bin": data})
            deep = r.randbytes(r.randint(0, 200_000))
            fs.replace("/EFI/BOOT/deep.efi", deep)
            self.tree["EFI/BOOT/deep.efi"] = deep
            self.check(fs, {"e.bin": data})


# --------------------------------------------------------------------------- builder read-back, FdDisk


class BuilderVerify(Case):
    def test_read_back_hashing(self):
        root = self.dir / "data"
        root.mkdir()
        record = {}
        sizes = (0, 1, MB - 1, MB, MB + 1, CHUNK, CHUNK + 1, 2 * CHUNK + 5)
        for size in sizes:
            data = mixed(size, size % 89)
            (root / f"f{size}").write_bytes(data)
            record[f"f{size}"] = {"data": (hashlib.sha256(data).hexdigest(), size)}
        stages = []
        builder._verify(record, {"data": root}, lambda *a: stages.append(a), threading.Event())
        self.assertEqual(("check", sum(sizes), sum(sizes)), stages[-1])
        for size in sizes[1:]:
            for at in {0, size - 1, size // 2}:
                f = root / f"f{size}"
                good = f.read_bytes()
                f.write_bytes(good[:at] + bytes([good[at] ^ 1]) + good[at + 1 :])
                with self.assertRaises(builder.BuildError, msg=f"{size} bytes, byte {at}"):
                    builder._verify(record, {"data": root}, lambda *_: None, threading.Event())
                f.write_bytes(good)
        cancel = threading.Event()
        cancel.set()
        with self.assertRaises(builder.Cancelled):
            builder._verify(record, {"data": root}, lambda *_: None, cancel)
        self.assert_no_leaks()


class FdDiskIo(Case):
    def test_reads_and_writes_across_chunks(self):
        path = self.dir / "disk"
        path.write_bytes(mixed(11 * MB, 1, 0.9))
        fd = os.open(path, os.O_RDWR)
        disk = FdDisk(fd)
        try:
            self.assertEqual((512, 11 * MB // 512), (disk.block_size, disk.block_count))
            r = random.Random(2)
            for _ in range(12):
                lba = r.randrange(0, 4000)
                data = r.randbytes(r.choice((1, 7, 8192, 8193, 17_000)) * 512)
                disk.write(lba, r.choice((bytes, bytearray, memoryview))(data))
                self.assertEqual(data, path.read_bytes()[lba * 512 : lba * 512 + len(data)])
            whole = path.read_bytes()
            first = disk.read(3, 9)
            second = disk.read(5000, 8192 + 5)
            third = disk.read(1, 17_001)
            self.assertEqual(whole[3 * 512 : 12 * 512], first, "an earlier read changed after a later one")
            self.assertEqual(whole[5000 * 512 : (5000 + 8197) * 512], second)
            self.assertEqual(whole[512 : 17_002 * 512], third)
            with self.assertRaises(ValueError):
                disk.write(0, b"x" * 100)
            with self.assertRaises(OSError):
                disk.read(disk.block_count - 1, 2)
        finally:
            disk.close()
            os.close(fd)
