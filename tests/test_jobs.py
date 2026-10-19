"""The desktop jobs that match the Android app: every image kind through the raw writer, the
timing line, the Labs settings, writing while downloading, your own file, Format drive (udisks
and the fallback), Ventoy install, adding images (unpacked, renamed, read back), starts on its
own, remove, the drive test and the drive check. udisks is faked; drives are files."""

import bz2
import gzip
import http.server
import io
import json
import lzma
import os
import random
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest import mock

from handheld_forge import download, jobs, maker, rawwrite, settings, ventoy, ventoy_config
from handheld_forge.blockdev import FileDisk
from handheld_forge.config import Distro, ImageSource, load_labs
from handheld_forge.drives import Drive
from handheld_forge.imagekind import ImageKind
from handheld_forge.udisks import UDisksError
from tests.test_drive_tools import ventoy_files

MB = 1024 * 1024


def disk_like(size, seed=5):
    r = random.Random(seed)
    out = io.BytesIO()
    while out.tell() < size:
        n = min(r.randint(1, 200_000), size - out.tell())
        out.write(r.randbytes(n) if r.random() < 0.5 else bytes(n))
    return out.getvalue()


def iso_like(size, seed=6):
    data = bytearray(disk_like(size, seed))
    data[32769:32774] = b"CD001"
    data[510:512] = b"\x55\xaa"
    return bytes(data)


class FakeUD:
    """Just enough udisks: mounts are folders, formatting can be made to fail."""

    def __init__(self, root: Path, fail_format=False, system=False):
        self.root, self.fail_format, self.system = root, fail_format, system
        self.mounted: dict[str, Path] = {}
        self.calls: list[tuple] = []

    def is_system_device(self, block):
        return self.system

    def mount_points(self, block):
        return [str(self.mounted[block])] if block in self.mounted else []

    def mount(self, block, read_only=False):
        p = self.root / block.rsplit("/", 1)[-1]
        p.mkdir(exist_ok=True)
        self.mounted[block] = p
        self.calls.append(("mount", block, read_only))
        return str(p)

    def unmount(self, block):
        self.calls.append(("unmount", block))
        self.mounted.pop(block, None)

    def format_disk(self, block, table="gpt"):
        self.calls.append(("format", block, table))
        if self.fail_format:
            raise UDisksError("mkfs.exfat missing")

    def create_partition(self, *a):
        self.calls.append(("create", *a))
        return block_of("sdz1")

    def drive_of(self, block):
        return "/drive"

    def power_off(self, d):
        self.calls.append(("poweroff", d))


def block_of(name):
    return f"/org/freedesktop/UDisks2/block_devices/{name}"


def lsblk(parts):
    """A fake lsblk for the Ventoy partition lookup."""

    def run(cmd, **_):
        out = {
            "blockdevices": [
                {
                    "name": "sdz",
                    "type": "disk",
                    "children": [
                        {
                            "name": n,
                            "label": label,
                            "fstype": fs,
                            "size": size,
                            "partn": i + 1,
                            "type": "part",
                        }
                        for i, (n, label, fs, size) in enumerate(parts)
                    ],
                }
            ]
        }
        return mock.Mock(returncode=0, stdout=json.dumps(out))

    return run


VENTOY_PARTS = [("sdz1", "Ventoy", "exfat", 30 << 30), ("sdz2", "VTOYEFI", "vfat", 32 * MB)]


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.drive = Drive("/dev/sdz", "sdz", 64 * MB, "Test Stick", ("sdz1",), True, "ok")

    def tearDown(self):
        self.tmp.cleanup()

    def device(self, size):
        p = self.dir / "device"
        p.write_bytes(b"\x07" * size)
        return p

    def opener_for(self, dev: Path):
        return {"opener": lambda block: os.open(dev, os.O_RDWR), "recheck": False}


class WriterKinds(Base):
    def test_every_kind_is_unpacked_and_timed(self):
        data = disk_like(9 * MB + 77)
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("__MACOSX/._x.img", b"junk")
            z.writestr("x.img", data)
        packs = {
            ImageKind.DISK: data,
            ImageKind.BZIP2: bz2.compress(data, 1),
            ImageKind.GZIP: gzip.compress(data, 1),
            ImageKind.XZ: lzma.compress(data, preset=0),
            ImageKind.ZIP: buf.getvalue(),
        }
        for kind, packed in packs.items():
            with self.subTest(kind=kind):
                img = self.dir / f"img.{kind.name}"
                img.write_bytes(packed)
                dev = self.device(16 * MB)
                logs = []
                fd = os.open(dev, os.O_RDWR)
                try:
                    n = rawwrite.write_image(fd, img, kind, log=logs.append)
                finally:
                    os.close(fd)
                self.assertEqual(len(data), n)
                self.assertEqual(data, dev.read_bytes()[: len(data)])
                self.assertTrue(
                    any(m.startswith("Wrote ") and "% of the time waiting on the drive" in m for m in logs)
                )
                self.assertTrue(any("Every byte on the drive matches" in m for m in logs))

    def test_bool_still_means_bzip2_and_skip_check(self):
        data = disk_like(3 * MB)
        img = self.dir / "a.img.bz2"
        img.write_bytes(bz2.compress(data))
        dev = self.device(8 * MB)
        logs = []
        fd = os.open(dev, os.O_RDWR)
        try:
            rawwrite.write_image(fd, img, True, log=logs.append, verify=False)
        finally:
            os.close(fd)
        self.assertEqual(data, dev.read_bytes()[: len(data)])
        self.assertIn("Skipped reading the drive back (Labs setting)", logs)

    def test_damaged_gzip_is_reported_as_damaged(self):
        packed = bytearray(gzip.compress(disk_like(2 * MB)))
        packed[len(packed) // 2] ^= 0xFF
        img = self.dir / "bad.gz"
        img.write_bytes(bytes(packed))
        fd = os.open(self.device(8 * MB), os.O_RDWR)
        try:
            with self.assertRaises(rawwrite.CorruptImage):
                rawwrite.write_image(fd, img, ImageKind.GZIP, log=lambda m: None)
        finally:
            os.close(fd)

    def test_maker_sniffs_the_file(self):
        d = Distro("x", "X", "", True, 8, None, format="raw-gz", write="raw")
        img = self.dir / "b.img.gz"
        img.write_bytes(gzip.compress(disk_like(MB)))
        self.assertIs(ImageKind.GZIP, maker.image_kind(d, img))
        img.write_bytes(b"\0" * 4096)
        self.assertIs(ImageKind.GZIP, maker.image_kind(d, img))  # unrecognized: trust the catalog
        img.write_bytes(bz2.compress(b"x" * 9000))
        self.assertIs(ImageKind.BZIP2, maker.image_kind(d, img))

    def test_labs_catalog_is_shared_with_android(self):
        labs = load_labs()
        self.assertEqual(["batocera"], [d.id for d in labs])
        self.assertTrue(all(d.labs and d.needs_password and d.format == "raw-gz" for d in labs))


class SettingsTests(Base):
    def test_round_trip_and_labs_gate(self):
        p = self.dir / "cfg" / "settings.json"
        s = settings.load(p)
        self.assertFalse(s.labs or s.stream or s.skip_check)
        s.stream = s.skip_check = True
        self.assertTrue(s.verify and not s.streaming)  # nothing applies while Labs is off
        s.labs = True
        settings.save(s, p)
        s2 = settings.load(p)
        self.assertEqual(s, s2)
        self.assertTrue(s2.streaming and not s2.verify)
        p.write_text("{broken")
        self.assertEqual(settings.Settings(), settings.load(p))


class Server:
    """Serves bytes slowly, so the writer catches up with the download; can drop once."""

    def __init__(self, data: bytes, drop_at: int | None = None):
        self.data, self.drop_at = data, drop_at
        outer = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_HEAD(self):
                self.send_response(200)
                self.send_header("Content-Length", str(len(outer.data)))
                self.send_header("ETag", '"v1"')
                self.end_headers()

            def do_GET(self):
                start = 0
                rng = self.headers.get("Range")
                if rng:
                    start = int(rng.split("=")[1].split("-")[0])
                    self.send_response(206)
                else:
                    self.send_response(200)
                body = outer.data[start:]
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                for i in range(0, len(body), 256 * 1024):
                    if outer.drop_at is not None and start + i >= outer.drop_at:
                        outer.drop_at = None
                        return
                    self.wfile.write(body[i : i + 256 * 1024])
                    threading.Event().wait(0.002)

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.httpd.server_port}/sys.img.gz"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class LiveDownloadTests(Base):
    def run_live(self, data, packed, drop_at=None, checksum=None):
        srv = Server(packed, drop_at)
        self.addCleanup(srv.close)
        img = ImageSource("Image", srv.url, "https://x/sha" if checksum else None, None, False)
        d = Distro("t", "T", "", True, 8, None, images=[img], format="raw-gz", write="raw")
        dev = self.device(32 * MB)
        logs = []
        with (
            mock.patch.dict(os.environ, {"XDG_CACHE_HOME": str(self.dir / "cache")}),
            mock.patch.object(download, "fetch_text", return_value=f"{checksum} sys.img.gz\n"),
            mock.patch.object(download, "_backoff", return_value=0.01),
        ):
            prep = download.prepare_iso(d, log=logs.append, stream=True)
            self.assertIsNotNone(prep.live)
            fd = os.open(dev, os.O_RDWR)
            try:
                rawwrite.write_image(
                    fd, prep.live.open, ImageKind.GZIP, log=logs.append, raw_size=prep.live.size
                )
            finally:
                os.close(fd)
            prep.live.finish(logs.append)
            cached = download.cache_dir() / "sys.img.gz"
        return dev, cached, logs

    def test_writes_while_downloading_and_keeps_the_download(self):
        data = disk_like(12 * MB)
        packed = gzip.compress(data, 1)
        dev, cached, logs = self.run_live(data, packed)
        self.assertEqual(data, dev.read_bytes()[: len(data)])
        self.assertEqual(packed, cached.read_bytes())
        self.assertIn("Download complete", logs)

    def test_checksum_is_still_checked(self):
        import hashlib

        data = disk_like(4 * MB)
        packed = gzip.compress(data, 1)
        good = hashlib.sha256(packed).hexdigest()
        _, cached, logs = self.run_live(data, packed, checksum=good)
        self.assertIn("Checksum OK", logs)
        with self.assertRaises(download.VerificationError):
            self.run_live(data, packed, checksum="0" * 64)

    def test_resumes_a_dropped_connection(self):
        data = disk_like(8 * MB)
        packed = gzip.compress(data, 1)
        dev, cached, _ = self.run_live(data, packed, drop_at=len(packed) // 2)
        self.assertEqual(data, dev.read_bytes()[: len(data)])
        self.assertEqual(packed, cached.read_bytes())


class OwnFileAndFormat(Base):
    def test_own_file_sniffs_and_writes(self):
        data = iso_like(5 * MB)
        src = self.dir / "mine.iso.xz"
        src.write_bytes(lzma.compress(data, preset=0))
        f = jobs.look_at(src)
        self.assertIs(ImageKind.XZ, f.kind)
        dev = self.device(16 * MB)
        ud = FakeUD(self.dir)
        jobs.write_own_file(self.drive, f, log=lambda m: None, udisks=ud, **self.opener_for(dev))
        self.assertEqual(data, dev.read_bytes()[: len(data)])
        self.assertIn(("poweroff", "/drive"), ud.calls)
        src.write_bytes(b"\x28\xb5\x2f\xfd" + b"\0" * 100)
        with self.assertRaises(rawwrite.RawWriteError):
            jobs.look_at(src)

    def test_format_uses_udisks_first(self):
        ud = FakeUD(self.dir)
        jobs.format_drive(self.drive, log=lambda m: None, udisks=ud)
        self.assertIn(("format", block_of("sdz"), "dos"), ud.calls)
        create = next(c for c in ud.calls if c[0] == "create")
        self.assertEqual((0, "0x07", "", "exfat", {"label": "USB DRIVE"}), create[3:])

    def test_format_falls_back_to_its_own_formatter(self):
        dev = self.device(64 * MB)
        ud = FakeUD(self.dir, fail_format=True)
        logs = []
        jobs.format_drive(self.drive, log=logs.append, udisks=ud, **self.opener_for(dev))
        disk = dev.read_bytes()
        self.assertEqual(b"\x55\xaa", disk[510:512])
        self.assertEqual(0x07, disk[446 + 4])
        self.assertEqual(b"EXFAT   ", disk[2048 * 512 + 3 : 2048 * 512 + 11])
        self.assertTrue(any("formatting it here instead" in m for m in logs))

    def test_refuses_an_internal_drive(self):
        with self.assertRaises(rawwrite.RawWriteError):
            jobs.format_drive(self.drive, log=lambda m: None, udisks=FakeUD(self.dir, system=True))


class VentoyJobs(Base):
    def test_install_with_and_without_secure_boot(self):
        files = ventoy_files()
        rel = ventoy.Release("1.1.18", "v.tar.gz", "https://x", None)
        for sb in (True, False):
            with self.subTest(secure_boot=sb):
                dev = self.device(2048 * MB)
                logs = []
                with mock.patch.dict(os.environ, {"XDG_CACHE_HOME": str(self.dir / "cache")}):
                    big = Drive("/dev/sdz", "sdz", 2048 * MB, "Test Stick", ("sdz1",), True, "ok")
                    v = jobs.install_ventoy(
                        big,
                        sb,
                        log=logs.append,
                        udisks=FakeUD(self.dir),
                        fetch=lambda *a: (rel, files),
                        **self.opener_for(dev),
                    )
                self.assertEqual("1.1.18", v)
                with FileDisk(dev) as d:
                    p1, p2 = ventoy.detect(d)
                    self.assertEqual(sb, ventoy.secure_boot_enabled(ventoy.read_efi(d, p2)))

    def mounted_ventoy(self):
        ud = FakeUD(self.dir)
        run = lsblk(VENTOY_PARTS)
        efi = Path(ud.mount(block_of("sdz2")))
        (efi / "grub").mkdir()
        (efi / "grub" / "grub.cfg").write_text('set VENTOY_VERSION="1.1.18"\n')
        (efi / "EFI" / "BOOT").mkdir(parents=True)
        (efi / "EFI" / "BOOT" / "grubx64_real.efi").write_bytes(b"x")
        ud.unmount(block_of("sdz2"))
        ud.calls.clear()
        return ud, run

    def test_add_images_unpacks_renames_and_reads_back(self):
        ud, run = self.mounted_ventoy()
        iso = iso_like(3 * MB, 1)
        disk = bytearray(disk_like(2 * MB, 2))
        disk[510:512] = b"\x55\xaa"
        disk = bytes(disk)
        files = {
            "Plain.iso": iso,
            "steamos.img.bz2": bz2.compress(disk),
            "tool.raw.gz": gzip.compress(disk),
            "live.iso.xz": lzma.compress(iso, preset=0),
            "odd": iso,
        }
        for n, b in files.items():
            (self.dir / n).write_bytes(b)
        plans = [jobs.plan_import(self.dir / n) for n in files]
        self.assertEqual(
            ["Plain.iso", "steamos.img", "tool.img", "live.iso", "odd.iso"], [p.target for p in plans]
        )
        self.assertEqual([3 * MB, -1, -1, -1, 3 * MB], [p.size for p in plans])
        logs = []
        names = jobs.import_images(
            self.drive, plans, log=logs.append, udisks=ud, run=run, boot_next="steamos.img"
        )
        mp = self.dir / "sdz1"
        self.assertNotIn(block_of("sdz1"), ud.mounted)  # unmounted, so it can be unplugged
        self.assertEqual(iso, (mp / "live.iso").read_bytes())
        self.assertEqual(disk, (mp / "steamos.img").read_bytes())
        self.assertEqual(disk, (mp / "tool.img").read_bytes())
        self.assertEqual(5, len(names))
        self.assertTrue(any("Every image matches" in m for m in logs))
        self.assertTrue(any(m.startswith("steamos.img: ") and "MB/s" in m for m in logs))
        self.assertEqual("steamos.img", ventoy_config.read_boot_next(mp))
        self.assertFalse([p for p in mp.iterdir() if p.name.startswith(".")])

        info = jobs.ventoy_info(self.drive, ud, run)
        self.assertEqual("1.1.18", info.version)
        self.assertTrue(info.secure_boot)
        self.assertEqual("steamos.img", info.boot_next)
        self.assertIn(jobs.VentoyFile("steamos.img", len(disk)), info.files)
        self.assertNotIn(("mount", block_of("sdz2"), False), ud.calls)  # Ventoy's own partition: read only

        # Same name again replaces; the same name twice in one go gets a number.
        twice = [jobs.plan_import(self.dir / "Plain.iso"), jobs.plan_import(self.dir / "Plain.iso")]
        self.assertEqual(
            ["Plain.iso", "Plain (2).iso"],
            jobs.import_images(self.drive, twice, log=lambda m: None, udisks=ud, run=run, verify=False),
        )

        jobs.remove_image(self.drive, "steamos.img", log=lambda m: None, udisks=ud, run=run)
        self.assertFalse((mp / "steamos.img").exists())
        self.assertIsNone(ventoy_config.read_boot_next(mp))  # nothing to start any more
        jobs.set_boot_next(self.drive, "live.iso", log=lambda m: None, udisks=ud, run=run)
        self.assertEqual("live.iso", ventoy_config.read_boot_next(mp))
        jobs.set_boot_next(self.drive, None, log=lambda m: None, udisks=ud, run=run)
        self.assertFalse((mp / "ventoy" / "ventoy.json").exists())
        with self.assertRaises(rawwrite.RawWriteError):
            jobs.set_boot_next(self.drive, "gone.iso", udisks=ud, run=run)

    def test_bad_read_back_is_caught(self):
        ud, run = self.mounted_ventoy()
        (self.dir / "a.iso").write_bytes(iso_like(MB))
        real = jobs._read_back

        def corrupting(path, *a, **k):
            with open(path, "r+b") as f:
                f.seek(100)
                f.write(b"!")
            return real(path, *a, **k)

        with mock.patch.object(jobs, "_read_back", corrupting), self.assertRaises(rawwrite.RawWriteError):
            jobs.import_images(
                self.drive, [jobs.plan_import(self.dir / "a.iso")], log=lambda m: None, udisks=ud, run=run
            )

    def test_not_enough_space_and_no_ventoy(self):
        ud, run = self.mounted_ventoy()
        (self.dir / "a.iso").write_bytes(iso_like(MB))
        p = jobs.plan_import(self.dir / "a.iso")
        p.size = 1 << 50
        with self.assertRaises(rawwrite.RawWriteError):
            jobs.import_images(self.drive, [p], udisks=ud, run=run, log=lambda m: None)
        self.assertIsNone(jobs.ventoy_info(self.drive, ud, lsblk([("sdz1", "USB", "exfat", 1 << 30)])))

    def test_damaged_archive_is_named(self):
        packed = bytearray(gzip.compress(disk_like(2 * MB)))
        (self.dir / "x.img.gz").write_bytes(bytes(packed[:200]))
        with self.assertRaises(OSError):
            jobs.plan_import(self.dir / "x.img.gz")


class LabsToolJobs(Base):
    def test_drive_test_and_check(self):
        dev = self.device(512 * MB)
        logs = []
        orig = jobs.drivetest.run
        with mock.patch.object(
            jobs.drivetest,
            "run",
            side_effect=lambda d, s, lg, c: orig(d, s, lg, c, min_bytes=64 * MB, max_bytes=64 * MB, spots=16),
        ):
            r = jobs.test_drive(self.drive, log=logs.append, udisks=FakeUD(self.dir), **self.opener_for(dev))
        self.assertFalse(r.fake or r.failing)
        self.assertTrue(any(m.startswith("A SteamOS image") for m in logs))
        self.assertEqual(b"EXFAT   ", dev.read_bytes()[2048 * 512 + 3 : 2048 * 512 + 11])  # usable again

        data = disk_like(4 * MB)
        img = self.dir / "s.img.bz2"
        img.write_bytes(bz2.compress(data))
        dev.write_bytes(data + bytes(MB))
        ud = FakeUD(self.dir)
        self.assertIsNone(
            jobs.check_drive(self.drive, img, log=lambda m: None, udisks=ud, **self.opener_for(dev))
        )
        with open(dev, "r+b") as f:
            f.seek(123456)
            f.write(bytes([data[123456] ^ 1]))
        self.assertEqual(
            123456, jobs.check_drive(self.drive, img, log=lambda m: None, udisks=ud, **self.opener_for(dev))
        )

    def test_link_speed(self):
        sysb = self.dir / "sys"
        usb = sysb / "devices" / "usb2" / "2-1"
        blk = usb / "2-1:1.0" / "host0" / "block" / "sdz"
        blk.mkdir(parents=True)
        (usb / "idVendor").write_text("0781")
        (usb / "speed").write_text("5000\n")
        (sysb / "block").mkdir()
        (sysb / "block" / "sdz").symlink_to(blk)
        self.assertEqual("USB 3 (5 Gbps)", jobs.link_speed(self.drive, sysb / "block"))
        (usb / "speed").write_text("480\n")
        self.assertTrue(jobs.link_speed(self.drive, sysb / "block").startswith("USB 2"))


if __name__ == "__main__":
    unittest.main()
