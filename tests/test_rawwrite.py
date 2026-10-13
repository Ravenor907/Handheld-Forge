"""Byte-for-byte writing (SteamOS, CachyOS): the writer on a temp file, and the whole path,
including the password-capable OpenDevice call, against the fake udisks on a private bus."""

import bz2
import hashlib
import io
import json
import os
import random
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from handheld_forge import rawwrite
from handheld_forge.drives import Drive
from handheld_forge.udisks import NotAuthorized
from tests.test_builder import CAN_RUN, HERE, PY_GI

MB = 1024 * 1024


def disk_like(size, seed=7):
    """Stretches of random bytes between runs of zeros, like a disk image."""
    r = random.Random(seed)
    out = io.BytesIO()
    while out.tell() < size:
        n = min(r.randint(1, 300_000), size - out.tell())
        out.write(r.randbytes(n) if r.random() < 0.5 else bytes(n))
    return out.getvalue()


class WriterTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.dev = self.dir / "device"

    def tearDown(self):
        self.tmp.cleanup()

    def device(self, size, fill=b"\x07"):
        self.dev.write_bytes(fill * size)
        return os.open(self.dev, os.O_RDWR)

    def image(self, data, name="img.iso"):
        p = self.dir / name
        p.write_bytes(data)
        return p

    def test_writes_an_iso_and_clears_the_tail(self):
        data = disk_like(3 * rawwrite.CHUNK + 1234)  # not block aligned on purpose
        fd = self.device(32 * MB)
        stages = []
        try:
            n = rawwrite.write_image(fd, self.image(data), False, lambda s, d, t: stages.append((s, d, t)),
                                     log=lambda m: None)
        finally:
            os.close(fd)
        disk = self.dev.read_bytes()
        self.assertEqual(n, len(data))
        self.assertEqual(disk[:len(data)], data)
        pad_end = (len(data) + 4095) // 4096 * 4096
        self.assertEqual(disk[len(data):pad_end], bytes(pad_end - len(data)))
        self.assertEqual(disk[-rawwrite.TAIL:], bytes(rawwrite.TAIL))
        self.assertEqual(disk[pad_end:pad_end + 10], b"\x07" * 10)  # nothing else touched
        self.assertEqual([s for s, _, _ in stages if s == "check"][-1:], ["check"])
        self.assertEqual(stages[-1], ("check", len(data), len(data)))

    def test_decompresses_bzip2_including_several_streams(self):
        data = disk_like(2 * rawwrite.CHUNK + 777)
        half = len(data) // 2
        packed = bz2.compress(data[:half]) + bz2.compress(data[half:])  # like pbzip2 output
        img = self.image(packed, "steamos.img.bz2")
        fd = self.device(16 * MB)
        writes = []
        try:
            rawwrite.write_image(fd, img, True, lambda s, d, t: s == "write" and writes.append((s, d, t)),
                                 log=lambda m: None)
        finally:
            os.close(fd)
        self.assertEqual(self.dev.read_bytes()[:len(data)], data)
        # Progress follows the compressed file, which is what was downloaded.
        self.assertTrue(all(t == len(packed) for _, _, t in writes))
        self.assertEqual(writes[-1][1], len(packed))

    def test_damaged_bzip2_is_reported_as_a_bad_download(self):
        good = bz2.compress(disk_like(2 * MB))
        flipped = bytearray(good)
        flipped[len(good) // 2] ^= 0x10
        for bad in (bytes(flipped), good[: len(good) * 2 // 3], os.urandom(10_000)):
            fd = self.device(8 * MB)
            try:
                with self.assertRaises(rawwrite.CorruptImage):
                    rawwrite.write_image(fd, self.image(bad, "x.img.bz2"), True, log=lambda m: None)
            finally:
                os.close(fd)

    def test_too_small(self):
        fd = self.device(2 * MB)
        try:
            with self.assertRaises(rawwrite.RawWriteError) as e:
                rawwrite.write_image(fd, self.image(disk_like(3 * MB)), False, log=lambda m: None)
        finally:
            os.close(fd)
        self.assertIn("too small", str(e.exception))

    def test_a_drive_that_stores_the_wrong_bytes_is_caught(self):
        real = os.preadv

        def bad_read(fd, bufs, offset):
            n = real(fd, bufs, offset)
            if offset == 0:
                bufs[0][100] ^= 0xFF
            return n

        fd = self.device(16 * MB)
        try:
            with mock.patch.object(rawwrite.os, "preadv", bad_read), \
                    self.assertRaises(rawwrite.RawWriteError) as e:
                rawwrite.write_image(fd, self.image(disk_like(2 * MB)), False, log=lambda m: None)
        finally:
            os.close(fd)
        self.assertIn("counterfeit", str(e.exception))

    def test_cancel(self):
        ev = threading.Event()
        ev.set()
        fd = self.device(16 * MB)
        try:
            with self.assertRaises(rawwrite.Cancelled):
                rawwrite.write_image(fd, self.image(disk_like(2 * MB)), False, log=lambda m: None, cancel=ev)
        finally:
            os.close(fd)


@unittest.skipUnless(CAN_RUN, "needs dbus-daemon, gdbus and a Python with PyGObject")
class PasswordPathTests(unittest.TestCase):
    """write_drive through the real OpenDevice call, against the fake udisks."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.bus = subprocess.Popen(["dbus-daemon", "--session", "--nofork", "--print-address"],
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        self.addr = self.bus.stdout.readline().strip()
        self.env = mock.patch.dict(os.environ, {"DBUS_SYSTEM_BUS_ADDRESS": self.addr})
        self.env.start()
        self.fake = None

    def tearDown(self):
        for proc in (self.fake, self.bus):
            if proc:
                proc.kill()
                proc.wait()
                proc.stdout.close()
        self.env.stop()
        self.tmp.cleanup()

    def start_fake(self, **env):
        self.fake = subprocess.Popen([PY_GI, str(HERE / "fake_udisks.py"), str(self.base)],
                                     stdout=subprocess.PIPE, text=True,
                                     env={**os.environ, "FAKE_SIZE": str(64 * MB), **env})
        self.assertEqual(self.fake.stdout.readline().strip(), "READY")

    def calls(self):
        return [json.loads(x) for x in (self.base / "calls.jsonl").read_text().splitlines()]

    def write(self, data, compressed=False):
        img = self.base / ("img.bz2" if compressed else "img.iso")
        img.write_bytes(bz2.compress(data) if compressed else data)
        drive = Drive("/dev/sdz", "sdz", 64 * MB, "Fake Stick", (), True, "ok")
        rawwrite.write_drive(drive, img, compressed, log=lambda m: None, recheck=False)

    def test_writes_through_the_password_call_then_ejects(self):
        self.start_fake()
        data = disk_like(5 * MB)
        self.write(data, compressed=True)
        disk = (self.base / "disk.img").read_bytes()
        self.assertEqual(hashlib.sha256(disk[:len(data)]).hexdigest(), hashlib.sha256(data).hexdigest())
        opened = [c for c in self.calls() if c["method"] == "OpenDevice"]
        self.assertEqual(len(opened), 1)
        self.assertEqual(opened[0]["mode"], "rw")
        # The one call that may show the password window must not forbid it...
        self.assertNotEqual(opened[0]["options"].get("auth.no_user_interaction"), True)
        # ...and asks for exclusive, uncached access so the read-back checks the drive itself.
        flags = opened[0]["options"]["flags"]
        self.assertTrue(flags & os.O_EXCL and flags & os.O_DIRECT)
        self.assertEqual(self.calls()[-1]["method"], "PowerOff")

    def test_closing_the_password_window_leaves_the_drive_alone(self):
        self.start_fake(FAKE_DISMISS="1")
        with self.assertRaises(NotAuthorized) as e:
            self.write(disk_like(MB))
        self.assertIn("password window was closed", str(e.exception))
        self.assertFalse((self.base / "disk.img").exists())

    def test_password_refused(self):
        self.start_fake(FAKE_DENY="OpenDevice")
        with self.assertRaises(NotAuthorized) as e:
            self.write(disk_like(MB))
        self.assertIn("admin password", str(e.exception))


if __name__ == "__main__":
    unittest.main()
