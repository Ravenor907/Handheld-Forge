"""The all-cores bzip2 reader gives exactly what bz2 gives, handles odd files, and fails on damage."""

import bz2
import io
import random
import shutil
import subprocess
import unittest

from handheld_forge.imagekind import ImageKind, decoder, sniff
from handheld_forge.pbzip2 import ParallelBz2Reader


def image_like(size, seed=1):
    r = random.Random(seed)
    out = bytearray()
    while len(out) < size:
        n = min(size - len(out), r.randint(1, 400_000))
        k = r.randint(0, 2)
        if k == 0:
            out += r.randbytes(n)
        elif k == 1:
            out += bytes(n)
        else:
            t = f"SteamOS partition data {r.random()} ".encode()
            out += (t * (n // len(t) + 1))[:n]
    return bytes(out)


def unpack(packed, **kw):
    with ParallelBz2Reader(io.BytesIO(packed), **kw) as r:
        out = bytearray()
        buf = bytearray(70_001)
        while True:
            n = r.readinto(buf)
            if not n:
                break
            out += buf[:n]
        return bytes(out)


class ParallelBz2Tests(unittest.TestCase):
    def test_same_bytes_as_bz2(self):
        for data, level in (
            (image_like(4_000_000), 1),
            (image_like(2_500_000, 2), 9),
            (random.Random(3).randbytes(1_500_000), 3),
        ):
            packed = bz2.compress(data, level)
            self.assertEqual(data, unpack(packed))
            self.assertEqual(data, unpack(packed, threads=1))

    def test_tiny_empty_and_concatenated(self):
        self.assertEqual(b"", unpack(bz2.compress(b"")))
        self.assertEqual(b"*", unpack(bz2.compress(b"*")))
        a, b = image_like(900_000, 4), image_like(400_000, 5)
        self.assertEqual(a + b, unpack(bz2.compress(a, 1) + bz2.compress(b"", 9) + bz2.compress(b, 5)))

    def test_zeros_that_expand_hugely(self):
        data = bytearray(40_000_000)
        data[12_345_678] = 7
        self.assertEqual(bytes(data), unpack(bz2.compress(bytes(data), 1)))

    def test_marker_by_chance_inside_a_block_is_handled(self):
        data = image_like(1_500_000, 6)
        packed = bz2.compress(data, 1)
        fakes = {len(packed) * 8 // 5 + 3, len(packed) * 8 // 2 + 5, len(packed) * 8 * 4 // 5 + 1}
        r = ParallelBz2Reader(io.BytesIO(packed), fake_splits=fakes)
        self.assertEqual(data, r.read())
        self.assertEqual(len(fakes), r.joins)
        r.close()

    def test_damaged_cut_or_not_bzip2_fails(self):
        data = image_like(2_000_000, 7)
        packed = bytearray(bz2.compress(data, 1))
        damaged = (
            bytes(packed[: len(packed) // 2])
            + bytes([packed[len(packed) // 2] ^ 0x10])
            + bytes(packed[len(packed) // 2 + 1 :])
        )
        for bad in (
            damaged,
            bytes(packed[: len(packed) * 2 // 3]),
            bytes(packed[:-6]),
            random.Random(1).randbytes(5000),
        ):
            with self.assertRaises(OSError):
                unpack(bad)

    @unittest.skipUnless(shutil.which("bzip2"), "no bzip2 tool")
    def test_matches_the_bzip2_tool(self):
        data = image_like(3_000_000, 8)
        packed = subprocess.run(["bzip2", "-9", "-c"], input=data, capture_output=True, check=True).stdout
        self.assertEqual(data, unpack(packed))

    def test_decoder_picks_it_for_bzip2(self):
        packed = bz2.compress(b"hello" * 1000)
        self.assertIs(ImageKind.BZIP2, sniff(packed))
        self.assertEqual(b"hello" * 1000, decoder(ImageKind.BZIP2, io.BytesIO(packed)).read())


class SniffTests(unittest.TestCase):
    def test_kinds(self):
        iso = bytearray(40_000)
        iso[32_769:32_774] = b"CD001"
        self.assertIs(ImageKind.ISO_DVD_ONLY, sniff(bytes(iso)))
        iso[510:512] = b"\x55\xaa"
        self.assertIs(ImageKind.ISO_HYBRID, sniff(bytes(iso)))
        disk = bytearray(1024)
        disk[512:520] = b"EFI PART"
        self.assertIs(ImageKind.DISK, sniff(bytes(disk)))
        self.assertIs(ImageKind.GZIP, sniff(b"\x1f\x8b\x08"))
        self.assertIs(ImageKind.XZ, sniff(bytes([0xFD, 0x37, 0x7A, 0x58, 0x5A, 0])))
        self.assertIs(ImageKind.ZIP, sniff(b"PK\x03\x04"))
        self.assertFalse(sniff(bytes([0x28, 0xB5, 0x2F, 0xFD])).writable)
        self.assertIs(ImageKind.UNKNOWN, sniff(b"hello"))
