"""The drive tools ported from the Android app: exFAT, Format drive, Ventoy (with and without
Secure Boot), "starts on its own", the drive test and the drive check. The Android app's own
tests cover the same cases; CI also checks these drives with the real tools and boots them."""

import bz2
import io
import lzma
import os
import shutil
import struct
import subprocess
import tempfile
import unittest
from pathlib import Path

from handheld_forge import drivetest, ventoy, ventoy_config
from handheld_forge.blockdev import FileDisk
from handheld_forge.exfat import format_volume, plain_format, upcase_table
from handheld_forge.fat16 import Fat16
from handheld_forge.imagekind import ImageKind, decoder

FSCK = shutil.which("fsck.exfat")


def fat16_image(files: dict) -> bytearray:
    """A 32 MB FAT16 image with long names for every file (test fixture, like the Android one)."""
    img = bytearray(32 * 1024 * 1024)
    spc, reserved, fat_sectors, root_entries = 4, 4, 64, 512
    img[0:3] = b"\xeb\x3c\x90"
    img[3:11] = b"MSWIN4.1"
    struct.pack_into("<HBHBHH", img, 11, 512, spc, reserved, 2, root_entries, 0)
    img[21] = 0xF8
    struct.pack_into("<H", img, 22, fat_sectors)
    struct.pack_into("<I", img, 32, 65536)
    img[38] = 0x29
    img[43:54] = b"VTOYEFI    "
    img[54:62] = b"FAT16   "
    img[510:512] = b"\x55\xaa"
    root_start = (reserved + 2 * fat_sectors) * 512
    data_start = root_start + root_entries * 32
    nxt = [2]

    def set_fat(c, v):
        for k in range(2):
            struct.pack_into("<H", img, (reserved + k * fat_sectors) * 512 + c * 2, v)

    set_fat(0, 0xFFF8)
    set_fat(1, 0xFFFF)

    def coff(c):
        return data_start + (c - 2) * spc * 512

    def alloc(data):
        n = max(1, (len(data) + spc * 512 - 1) // (spc * 512))
        first = nxt[0]
        for i in range(n):
            set_fat(first + i, 0xFFFF if i == n - 1 else first + i + 1)
        img[coff(first) : coff(first) + len(data)] = data
        nxt[0] += n
        return first

    dirs, fill, serial = {}, {}, [0]

    def add(dc, name, attr, cluster, size):
        base = root_start if dc == 0 else coff(dc)
        at = base + fill.get(dc, 0)
        short = (f"F{serial[0]:06d}".ljust(8) + "BIN").encode()
        serial[0] += 1
        s = 0
        for ch in short:
            s = (((s & 1) << 7) + (s >> 1) + ch) & 0xFF
        parts = [name[i : i + 13] for i in range(0, len(name), 13)]
        for i in reversed(range(len(parts))):
            part = parts[i]
            img[at] = (i + 1) | (0x40 if i == len(parts) - 1 else 0)
            img[at + 11] = 0x0F
            img[at + 13] = s
            chars = [ord(c) for c in part] + ([0] if len(part) < 13 else [])
            chars = (chars + [0xFFFF] * 13)[:13]
            for k, pos in enumerate((1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)):
                struct.pack_into("<H", img, at + pos, chars[k])
            at += 32
        img[at : at + 11] = short
        img[at + 11] = attr
        struct.pack_into("<HI", img, at + 26, cluster, size)
        fill[dc] = at + 32 - base

    def dir_(path):
        if not path:
            return 0
        if path in dirs:
            return dirs[path]
        parent = dir_(path.rsplit("/", 1)[0] if "/" in path else "")
        c = alloc(bytes(spc * 512))
        dirs[path] = c
        add(parent, path.rsplit("/", 1)[-1], 0x10, c, 0)
        return c

    for path, data in files.items():
        d = dir_(path.rsplit("/", 1)[0] if "/" in path else "")
        add(d, path.rsplit("/", 1)[-1], 0x20, alloc(data), len(data))
    return img


SHIM = bytes([1]) * 70_000
GRUB_REAL = bytes(i % 7 for i in range(150_000))
GRUB_IA32_REAL = bytes(i % 5 for i in range(90_000))


def ventoy_files() -> ventoy.Files:
    tree = {
        "EFI/BOOT/BOOTX64.EFI": SHIM,
        "EFI/BOOT/grubx64.efi": bytes([2]) * 20_000,
        "EFI/BOOT/BOOTIA32.EFI": bytes([3]) * 30_000,
        "EFI/BOOT/grubia32.efi": bytes([4]) * 10_000,
        "grub/grub.cfg": b'set VENTOY_VERSION="9.9.9"\nmenuentry x {}\n',
        "EFI/BOOT/grubx64_real.efi": GRUB_REAL,
        "EFI/BOOT/grubia32_real.efi": GRUB_IA32_REAL,
        "EFI/BOOT/MokManager.efi": bytes([5]) * 5000,
        "EFI/BOOT/mmx64.efi": bytes([6]) * 6000,
        "EFI/BOOT/mmia32.efi": bytes([7]) * 4000,
        "ENROLL_THIS_KEY_IN_MOKMANAGER.cer": bytes([8]) * 900,
    }
    return ventoy.Files(
        bytes(i % 251 for i in range(512)),
        lzma.compress(bytes([9]) * 512_000),
        lzma.compress(bytes(fat16_image(tree))),
    )


class Tmp(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def disk(self, mb: int, name="d.img") -> FileDisk:
        d = FileDisk(self.dir / name, mb * 2048)
        self.addCleanup(d.close)
        return d


class ExFatTests(Tmp):
    def test_upcase_table_is_the_shared_compressed_one(self):
        t = upcase_table()
        self.assertLess(len(t), 0x10000)
        full, c, i = [0] * 0x10000, 0, 0
        while i < len(t):
            v = struct.unpack_from("<H", t, i)[0]
            i += 2
            if v == 0xFFFF:
                run = struct.unpack_from("<H", t, i)[0]
                i += 2
                for k in range(run):
                    full[c + k] = c + k
                c += run
            else:
                full[c] = v
                c += 1
        self.assertEqual(0x10000, c)
        self.assertEqual(ord("A"), full[ord("a")])
        self.assertEqual(0x0410, full[0x0430])

    def test_format_is_a_valid_volume(self):
        d = self.disk(64)
        lay = format_volume(d, 2048, d.block_count - 2048, "Ventoy", serial=1)
        boot = d.read(2048, 1)
        self.assertEqual(b"EXFAT   ", boot[3:11])
        self.assertEqual(lay.cluster_count, struct.unpack_from("<I", boot, 92)[0])
        if FSCK:
            self._fsck(self.dir / "d.img", 2048)

    def test_plain_format(self):
        d = self.disk(80)
        plain_format(d, log=lambda m: None)
        mbr = d.read(0, 1)
        self.assertEqual(b"\x55\xaa", mbr[510:512])
        self.assertEqual(0x07, mbr[450])
        start, length = struct.unpack_from("<II", mbr, 454)
        self.assertEqual(2048, start)
        self.assertEqual(0, length % 2048)
        if FSCK:
            self._fsck(self.dir / "d.img", 2048)

    def test_plain_format_clears_old_markers(self):
        """An old ISO marker in the gap before the FAT made Linux see two filesystems."""
        d = self.disk(80)
        d.write(2048 + 64, b"\x01CD001\x01".ljust(512, b"\0"))
        d.write(d.block_count - 4096, b"\xAA" * 512)
        plain_format(d, log=lambda m: None)
        self.assertEqual(bytes(512), d.read(2048 + 64, 1))
        if shutil.which("blkid"):
            out = subprocess.run(["blkid", "-p", "-O", str(2048 * 512), str(self.dir / "d.img")],
                                 capture_output=True, text=True)
            self.assertIn('TYPE="exfat"', out.stdout)

    def _fsck(self, image, start):
        """fsck.exfat on the volume alone (older exfatprogs can't take an offset)."""
        vol = Path(str(image) + ".vol")
        with open(image, "rb") as src, open(vol, "wb") as dst:
            src.seek(start * 512)
            shutil.copyfileobj(src, dst, 1 << 20)
        out = subprocess.run([FSCK, "-n", str(vol)], capture_output=True, text=True)
        vol.unlink()
        self.assertEqual(0, out.returncode, out.stdout + out.stderr)


class VentoyTests(Tmp):
    def test_layout_matches_ventoy2disk(self):
        p = ventoy.plan(62_914_560)  # 30 GiB-ish drive
        self.assertEqual(0, p.part2_start % 8)
        self.assertLessEqual(p.part2_start + ventoy.PART2_SECTORS, 62_914_560)
        self.assertEqual(ventoy.PART1_START + p.part1_sectors, p.part2_start)
        self.assertEqual(64, p.sectors_per_cluster)
        self.assertEqual(256, ventoy.plan(128 * 2097152).sectors_per_cluster)

    def test_install_detect_and_secure_boot_choice(self):
        for sb in (True, False):
            d = self.disk(3 * 1024, f"v{sb}.img")
            ventoy.install(d, ventoy_files(), log=lambda m: None, secure_boot=sb)
            data, efi = ventoy.detect(d)
            self.assertEqual(ventoy.PART1_START, data.start)
            img = ventoy.read_efi(d, efi)
            self.assertEqual(sb, ventoy.secure_boot_enabled(img))
            self.assertEqual("9.9.9", ventoy.version_of(img))
            fs = Fat16(bytearray(img))
            if sb:
                self.assertEqual(SHIM, fs.read("/EFI/BOOT/BOOTX64.EFI"))
            else:
                self.assertEqual(GRUB_REAL, fs.read("/EFI/BOOT/BOOTX64.EFI"))
                self.assertEqual(GRUB_IA32_REAL, fs.read("/EFI/BOOT/BOOTIA32.EFI"))
                for gone in (
                    "grubx64.efi",
                    "grubx64_real.efi",
                    "MokManager.efi",
                    "mmx64.efi",
                    "grubia32.efi",
                    "grubia32_real.efi",
                    "mmia32.efi",
                ):
                    self.assertIsNone(fs.find(f"/EFI/BOOT/{gone}"), gone)
                self.assertIsNone(fs.find("/ENROLL_THIS_KEY_IN_MOKMANAGER.cer"))
                self.assertIsNotNone(fs.find("/grub/grub.cfg"))
            mbr = d.read(0, 1)
            boot = ventoy_files().boot_img
            self.assertEqual(boot[:384], mbr[:384])  # 384..399 is the random disk UUID
            self.assertEqual(boot[400:440], mbr[400:440])
            self.assertEqual(0x80, mbr[446])  # partition 1 active
            self.assertEqual(0xEF, mbr[466])
            d.close()

    def test_not_a_ventoy_drive(self):
        d = self.disk(64)
        plain_format(d, log=lambda m: None)
        self.assertIsNone(ventoy.detect(d))

    def test_release_answer(self):
        rel = ventoy.parse_release(
            '{"tag_name": "v1.1.18", "assets": ['
            '{"name": "ventoy-1.1.18-windows.zip", "browser_download_url": "w"},'
            '{"name": "ventoy-1.1.18-linux.tar.gz", "browser_download_url": "l"},'
            '{"name": "sha256.txt", "browser_download_url": "s"}]}'
        )
        self.assertEqual(
            ("1.1.18", "ventoy-1.1.18-linux.tar.gz", "l", "s"),
            (rel.version, rel.tar_name, rel.tar_url, rel.sha_url),
        )


class BootNextTests(Tmp):
    def test_set_and_clear_on_a_mounted_partition(self):
        ventoy_config.set_boot_next(self.dir, "forge-test.iso")
        text = (self.dir / ventoy_config.PATH).read_text()
        self.assertIn('"VTOY_DEFAULT_IMAGE": "/forge-test.iso"', text)
        self.assertIn('"VTOY_MENU_TIMEOUT": "5"', text)
        self.assertIn('"VTOY_SECONDARY_BOOT_MENU": "0"', text)
        self.assertEqual("forge-test.iso", ventoy_config.read_boot_next(self.dir))
        ventoy_config.set_boot_next(self.dir, None)
        self.assertFalse((self.dir / ventoy_config.PATH).exists())

    def test_keeps_everything_else(self):
        existing = (
            '{\n "theme": {"file": "/ventoy/theme/blur/theme.txt"},\n "control": [\n'
            '  {"VTOY_DEFAULT_MENU_MODE": "1"}, {"VTOY_MENU_TIMEOUT": "30"}],\n'
            ' "menu_alias": [{"image": "/a.iso", "alias": "Ünïcode ✓", "n": 1.50e3}]\n}'
        )
        new = ventoy_config.with_boot_next(existing, "Bazzite Deck.iso")
        self.assertEqual("Bazzite Deck.iso", ventoy_config.boot_next(new))
        self.assertIn("1.50e3", new)
        self.assertIn("Ünïcode ✓", new)
        self.assertNotIn('"30"', new)
        cleared = ventoy_config.with_boot_next(new, None)
        self.assertIsNone(ventoy_config.boot_next(cleared))
        self.assertIn("VTOY_DEFAULT_MENU_MODE", cleared)
        self.assertEqual(new, ventoy_config.write(ventoy_config.parse(new)) + "\n")

    def test_broken_file_is_never_overwritten(self):
        with self.assertRaises(OSError):
            ventoy_config.with_boot_next('{ "control": [ ', "a.iso")
        self.assertEqual("a.iso", ventoy_config.boot_next(ventoy_config.with_boot_next("﻿{}", "a.iso")))


class FakeCapacity:
    """Claims `claimed` sectors, stores `real`; past that, addresses wrap (wrap=True) or vanish."""

    def __init__(self, store: FileDisk, claimed: int, wrap: bool) -> None:
        self.store, self.block_count, self.wrap = store, claimed, wrap
        self.block_size, self.name = 512, "fake"

    def read(self, lba, count):
        out = bytearray()
        for i in range(count):
            a = lba + i
            if a < self.store.block_count:
                out += self.store.read(a, 1)
            elif self.wrap:
                out += self.store.read(a % self.store.block_count, 1)
            else:
                out += bytes(512)
        return bytes(out)

    def write(self, lba, data):
        for i in range(len(data) // 512):
            a = lba + i
            if a < self.store.block_count or self.wrap:
                self.store.write(a % self.store.block_count, data[i * 512 : (i + 1) * 512])

    def sync(self):
        pass


class DriveTestTests(Tmp):
    def run_test(self, dev):
        return drivetest.run(
            dev, log=lambda m: None, min_bytes=8 << 20, max_bytes=16 << 20, spots=16, seed=42
        )

    def test_real_drive_passes(self):
        r = self.run_test(self.disk(64))
        self.assertFalse(r.fake or r.failing)
        self.assertTrue(any(line.startswith("Capacity: all 16 markers") for line in r.lines()))

    def test_fakes_are_caught(self):
        for wrap in (True, False):
            store = self.disk(24, f"s{wrap}.img")
            r = self.run_test(FakeCapacity(store, 64 * 2048, wrap))
            self.assertTrue(r.fake or r.failing)
            self.assertTrue(any("Don't trust it" in line for line in r.lines()), r.lines())
            if not wrap:
                self.assertTrue(r.fake)
                self.assertGreaterEqual(r.first_bad_spot, 24 << 20)

    def test_slowdown_is_reported(self):
        r = drivetest.Report(64_000_000_000, 1 << 30, [80.0, 79.0, 12.0, 4.0], 30.0, 90.0, 0, 128, 0, None)
        self.assertIsNotNone(r.slowdown)
        self.assertTrue(any("drops to 4 MB/s" in line for line in r.lines()))


class CompareTests(Tmp):
    def test_match_and_first_difference(self):
        data = os.urandom(5 << 20)
        packed = bz2.compress(data, 1)
        d = self.disk(16)
        d.write(0, data)
        img = decoder(ImageKind.BZIP2, io.BytesIO(packed))
        self.assertIsNone(drivetest.compare(img, d, log=lambda m: None))
        at = 4_321_987
        sector = bytearray(d.read(at // 512, 1))
        sector[at % 512] ^= 1
        d.write(at // 512, bytes(sector))
        img = decoder(ImageKind.BZIP2, io.BytesIO(packed))
        self.assertEqual(at, drivetest.compare(img, d, log=lambda m: None))


if __name__ == "__main__":
    unittest.main()
