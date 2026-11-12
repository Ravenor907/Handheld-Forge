"""The fast paths give exactly what the plain ones would: the folded marker search, the one-range
block wrapper, pattern and compare helpers, reading ahead while writing, and hashing a download as
it arrives (with a resumed download still hashed from its first byte)."""

import hashlib
import http.server
import io
import lzma
import os
import random
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from handheld_forge import download, drivetest, jobs, pbzip2, rawwrite, verify
from handheld_forge.blockdev import FdDisk, FileDisk
from handheld_forge.config import Distro, ImageSource
from handheld_forge.imagekind import ImageKind
from handheld_forge.verify import VerificationError


class MarkerSearchTests(unittest.TestCase):
    def plain(self, window):
        """A search per core, the slow and obvious way."""
        out = []
        for k, core in pbzip2._CORES:
            i = window.find(core)
            while i >= 0:
                out.append((i, k))
                i = window.find(core, i + 1)
        return sorted(out)

    def test_finds_every_marker_at_every_bit_offset(self):
        r = random.Random(5)
        buf = bytearray(r.randbytes(200_000))
        at = 100
        for magic in (pbzip2.BLOCK_MAGIC, pbzip2.END_MAGIC):
            for k in range(8):
                for _ in range(3):
                    v = int.from_bytes(buf[at : at + 7], "big")
                    shift = 56 - 48 - k
                    v = (v & ~(((1 << 48) - 1) << shift)) | (magic << shift)
                    buf[at : at + 7] = v.to_bytes(7, "big")
                    at += r.randint(7, 3000)
        cores = [c for _, c in pbzip2._CORES]
        for _ in range(300):  # near misses: a core with one byte off, and cores cut short
            core = bytearray(r.choice(cores))
            core[r.randrange(len(core))] ^= 1 << r.randrange(8)
            p = r.randrange(len(buf) - 8)
            buf[p : p + len(core)] = core
        buf += cores[0][:4] + cores[9] + cores[3][:-1]
        want = self.plain(bytes(buf))
        self.assertGreaterEqual(len(want), 48)
        self.assertEqual(sorted(pbzip2._find_cores(buf)), want)
        self.assertEqual(sorted(pbzip2._find_cores(bytes(buf))), want)
        for cut in (0, 1, 4, 5, 6, 7):
            self.assertEqual(sorted(pbzip2._find_cores(buf[:cut])), self.plain(bytes(buf[:cut])))

    def test_cores_packed_together(self):
        cores = [c for _, c in pbzip2._CORES]
        r = random.Random(6)
        buf = b"".join(r.choice(cores) for _ in range(2000))
        self.assertEqual(sorted(pbzip2._find_cores(buf)), self.plain(buf))


class BlockWrapperTests(unittest.TestCase):
    def plain(self, ranges):
        """Bit by bit, as text: header, each range's bits, end marker, first CRC, padding."""
        bits = format(int.from_bytes(b"BZh9", "big"), "032b")
        for r in ranges:
            all_bits = "".join(format(b, "08b") for b in r.data)
            bits += all_bits[r.bit_off : r.bit_off + r.bit_len]
        bits += format(pbzip2.END_MAGIC, "048b") + format(ranges[0].crc, "032b")
        bits += "0" * (-len(bits) % 8)
        return int(bits, 2).to_bytes(len(bits) // 8, "big")

    def test_one_range_and_several(self):
        r = random.Random(8)
        made = []
        for _ in range(400):
            off = r.randrange(8)
            n = r.choice([80, 81, 87, 88, 89, 95, 96, 120, r.randrange(80, 5000)])
            data = r.randbytes((off + n + 7) // 8 + r.choice([0, 0, 0, 2]))
            made.append(pbzip2._Range(True, data, off, n))
            self.assertEqual(pbzip2._stream([made[-1]]), self.plain([made[-1]]))
        for i in range(0, len(made) - 3, 3):
            self.assertEqual(pbzip2._stream(made[i : i + 3]), self.plain(made[i : i + 3]))

    def test_bits(self):
        r = random.Random(9)
        for _ in range(300):
            data = r.randbytes(r.randrange(12, 60))
            off = r.randrange(8)
            rng = pbzip2._Range(True, data, off, len(data) * 8 - off - r.randrange(8))
            start = r.randrange(rng.bit_len - 1)
            n = r.randrange(1, rng.bit_len - start + 1)
            all_bits = "".join(format(b, "08b") for b in data)
            self.assertEqual(rng.bits(start, n), int(all_bits[off + start : off + start + n], 2))


class DriveTestHelperTests(unittest.TestCase):
    def test_pattern_is_a_generator_per_64k_keyed_by_seed_and_address(self):
        for length, offset, seed in [(0, 0, 1), (1, 5, 2), (65536, 0, 3), (65537, 512, 4),
                                     (200_001, 2**40, -7), (300_000, 4096, ~(2**70 + 1))]:
            want = bytearray()
            o = 0
            while o < length:
                n = min(length - o, 65536)
                want += random.Random(((seed & (2**64 - 1)) << 64) | (offset + o)).randbytes(n)
                o += n
            self.assertEqual(drivetest.pattern(length, offset, seed), bytes(want))

    def test_wrong_bytes_counts_like_a_byte_by_byte_walk(self):
        r = random.Random(3)
        base = r.randbytes(200_000)
        cases = [(base, base), (b"", b""), (base, bytes(len(base))), (base[:70_000], base),
                 (base, base[:65_536])]
        for flips in (1, 7, 5000):
            b = bytearray(base)
            for _ in range(flips):
                b[r.randrange(len(b))] ^= r.randrange(1, 256)
            cases += [(base, bytes(b)), (bytes(b), base[:131_073]), (bytearray(b), base)]
        edge = bytearray(base)
        edge[0] ^= 1
        edge[65_535] ^= 1
        edge[65_536] ^= 1
        edge[-1] ^= 1
        cases.append((base, bytes(edge)))
        for got, want in cases:
            slow = sum(1 for a, b in zip(got, want, strict=False) if a != b)
            self.assertEqual(drivetest._wrong_bytes(got, want), slow)

    def test_first_difference(self):
        r = random.Random(4)
        base = r.randbytes(300_000)
        for at in (0, 1, 65_535, 65_536, 65_537, 131_072, 299_999):
            other = bytearray(base)
            other[at] ^= 0x80
            other[-1] ^= 1
            self.assertEqual(drivetest._first_difference(base, bytes(other)), at)
            self.assertEqual(drivetest._first_difference(base[: at + 1], bytes(other)), at)

    def test_planned_blocks_are_the_same_data(self):
        ahead = drivetest._Ahead(77)
        try:
            ahead.plan(70_000, 512)
            self.assertEqual(ahead.take(70_000, 512), drivetest.pattern(70_000, 512, 77))
            ahead.plan(70_000, 1024)  # planned, then something else is asked for
            self.assertEqual(ahead.take(4096, 0), drivetest.pattern(4096, 0, 77))
            self.assertEqual(ahead.take(70_000, 1024), drivetest.pattern(70_000, 1024, 77))
            self.assertEqual(ahead.take(70_000, 1024), drivetest.pattern(70_000, 1024, 77))
        finally:
            ahead.close()

    def test_run_writes_the_pattern_and_leaves_no_thread(self):
        before = threading.active_count()
        with tempfile.TemporaryDirectory() as d:
            for cls in (FileDisk, None):
                p = Path(d) / "disk"
                p.write_bytes(b"")
                os.truncate(p, 40 << 20)
                fd = os.open(p, os.O_RDWR)
                dev = FdDisk(fd) if cls is None else FileDisk(p)
                try:
                    rep = drivetest.run(dev, log=lambda m: None, min_bytes=8 << 20, max_bytes=24 << 20,
                                        budget_seconds=1e9, spots=4, seed=99)
                finally:
                    dev.close()
                    os.close(fd)
                self.assertEqual((rep.tested_bytes, rep.bad_bytes, rep.bad_spots), (24 << 20, 0, 0))
                with open(p, "rb") as f:
                    for at in range(0, rep.tested_bytes, 4 << 20):
                        self.assertEqual(f.read(4 << 20), drivetest.pattern(4 << 20, at, 99))
        self.assertEqual(threading.active_count(), before)

    def test_cancel_and_drive_errors_leave_no_thread(self):
        before = threading.active_count()

        class Dies(FileDisk):
            def write(self, lba, data):
                if lba >= 8192:
                    raise OSError("gone")
                super().write(lba, data)

        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "disk"
            p.write_bytes(b"")
            os.truncate(p, 40 << 20)
            with Dies(p) as dev, self.assertRaises(OSError):
                drivetest.run(dev, log=lambda m: None, min_bytes=16 << 20, spots=4, seed=1)
            calls = []
            with FileDisk(p) as dev, self.assertRaises(rawwrite.Cancelled):
                drivetest.run(dev, log=lambda m: None, min_bytes=16 << 20, spots=4, seed=1,
                              cancel=lambda: calls.append(1) or len(calls) > 2)
        self.assertEqual(threading.active_count(), before)


class WriteAheadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        r = random.Random(11)
        self.data = b"".join(r.randbytes(300_000) + bytes(200_000) for _ in range(19)) + b"end"  # 9.5 MB, odd

    def write(self, image, kind, size=12 << 20, **kw):
        dev = self.dir / "dev"
        dev.write_bytes(b"\xaa" * size)
        fd = os.open(dev, os.O_RDWR)
        stages, logs = [], []
        try:
            n = rawwrite.write_image(fd, image, kind, lambda *a: stages.append(a), logs.append, **kw)
        finally:
            os.close(fd)
        return n, dev.read_bytes(), stages, len(logs)

    def test_a_file_and_a_stream_give_the_same_drive_and_progress(self):
        packed = {ImageKind.DISK: self.data, ImageKind.XZ: lzma.compress(self.data, preset=0)}
        for kind, blob in packed.items():
            src = self.dir / "image"
            src.write_bytes(blob)
            from_file = self.write(src, kind)
            from_stream = self.write(lambda b=blob: io.BytesIO(b), kind, raw_size=len(blob))
            self.assertEqual(from_file, from_stream)
            n, drive, stages, _ = from_file
            self.assertEqual(n, len(self.data))
            padded = -len(self.data) % 4096
            self.assertEqual(drive[: n + padded], self.data + bytes(padded))
            untouched = len(drive) - n - padded - rawwrite.TAIL
            self.assertEqual(drive[n + padded : -rawwrite.TAIL], b"\xaa" * untouched)
            self.assertEqual(drive[-rawwrite.TAIL :], bytes(rawwrite.TAIL))
            self.assertEqual([s for s in stages if s[0] == "check"],
                             [("check", min(len(self.data), (i + 1) * rawwrite.CHUNK), len(self.data))
                              for i in range(3)])
        raw_stages = self.write(self.dir / "image", ImageKind.XZ)[2]
        self.assertEqual(raw_stages[-1], ("check", len(self.data), len(self.data)))

    def test_whole_chunks_and_nothing(self):
        for size in (0, 1, rawwrite.CHUNK, 2 * rawwrite.CHUNK):
            src = self.dir / "image"
            src.write_bytes(self.data[:size])
            n, drive, stages, _ = self.write(src, False)
            self.assertEqual((n, drive[:size]), (size, self.data[:size]))
            stream = self.write(lambda s=size: io.BytesIO(self.data[:s]), False, raw_size=size)
            self.assertEqual(stages, stream[2])

    def test_the_image_hash_is_still_checked(self):
        src = self.dir / "image"
        src.write_bytes(self.data)
        self.write(src, False, expected_sha256=hashlib.sha256(self.data).hexdigest())
        with self.assertRaises(rawwrite.RawWriteError):
            self.write(src, False, expected_sha256=hashlib.sha256(self.data[:-1]).hexdigest())

    def test_stopping_early_leaves_no_thread_and_the_same_drive(self):
        before = threading.active_count()
        src = self.dir / "image"
        src.write_bytes(self.data)
        results = []
        for image, kw in ((src, {}), (lambda: io.BytesIO(self.data), {"raw_size": len(self.data)})):
            dev = self.dir / "dev"
            dev.write_bytes(b"\xaa" * (12 << 20))
            cancel = threading.Event()
            fd = os.open(dev, os.O_RDWR)
            try:
                with self.assertRaises(rawwrite.Cancelled):
                    rawwrite.write_image(fd, image, False, lambda s, d, t, c=cancel: d and c.set(),
                                         lambda m: None, cancel, **kw)
                with self.assertRaises(rawwrite.RawWriteError):
                    os.ftruncate(fd, 6 << 20)
                    rawwrite.write_image(fd, image, False, log=lambda m: None, **kw)
            finally:
                os.close(fd)
            results.append(dev.read_bytes())
        self.assertEqual(results[0], results[1])
        self.assertEqual(results[0][: rawwrite.CHUNK], self.data[: rawwrite.CHUNK])
        self.assertEqual(threading.active_count(), before)

    def test_damage_is_reported_after_the_good_part_is_written(self):
        blob = lzma.compress(self.data, preset=0)
        src = self.dir / "image.xz"
        src.write_bytes(blob[:-50_000])
        outcomes = []
        for image in (src, lambda: io.BytesIO(blob[:-50_000])):
            dev = self.dir / "dev"
            dev.write_bytes(b"\xaa" * (12 << 20))
            fd = os.open(dev, os.O_RDWR)
            stages = []
            try:
                with self.assertRaises(rawwrite.CorruptImage) as e:
                    rawwrite.write_image(fd, image, ImageKind.XZ, lambda *a, s=stages: s.append(a),
                                         lambda m: None, raw_size=len(blob) - 50_000)
            finally:
                os.close(fd)
            outcomes.append((str(e.exception), dev.read_bytes(), stages))
        self.assertEqual(outcomes[0], outcomes[1])
        self.assertEqual(outcomes[0][1][: rawwrite.CHUNK], self.data[: rawwrite.CHUNK])


class ImportCopyTests(unittest.TestCase):
    def test_copies_unpacks_and_reads_back(self):
        r = random.Random(12)
        body = b"".join(r.randbytes(250_000) + bytes(250_000) for _ in range(18))
        disk = bytes(510) + b"\x55\xaa" + body + b"x"
        before = threading.active_count()
        with tempfile.TemporaryDirectory() as d:
            mp, src = Path(d) / "mp", Path(d) / "src"
            mp.mkdir()
            src.mkdir()
            (src / "a.img").write_bytes(disk)
            (src / "b.img.xz").write_bytes(lzma.compress(disk, preset=0))
            (src / "c.img").write_bytes(disk[: jobs.CHUNK])
            (src / "bad.img.xz").write_bytes(lzma.compress(disk, preset=0)[:-30_000])
            plans = [jobs.plan_import(src / n) for n in ("a.img", "b.img.xz", "c.img")]
            stages = []
            names = jobs._import(mp, plans, lambda *a: stages.append(a), lambda m: None, threading.Event(),
                                 True, None)
            self.assertEqual(names, ["a.img", "b.img", "c.img"])
            self.assertEqual((mp / "a.img").read_bytes(), disk)
            self.assertEqual((mp / "b.img").read_bytes(), disk)
            self.assertEqual((mp / "c.img").read_bytes(), disk[: jobs.CHUNK])
            total_in = sum(p.source.stat().st_size for p in plans)
            writes = [s for s in stages if s[0] == "write"]
            self.assertEqual(writes[:5], [("write", 0, total_in), ("write", jobs.CHUNK, total_in),
                                          ("write", 2 * jobs.CHUNK, total_in), ("write", len(disk), total_in),
                                          ("write", len(disk), total_in)])
            self.assertEqual(writes[-1], ("write", total_in, total_in))
            total_out = 2 * len(disk) + jobs.CHUNK
            checks = [s for s in stages if s[0] == "check"]
            self.assertEqual(checks[:4], [("check", n, total_out) for n in (0, jobs.CHUNK, 2 * jobs.CHUNK,
                                                                           len(disk))])
            self.assertEqual(checks[-1], ("check", total_out, total_out))
            with self.assertRaises(rawwrite.RawWriteError) as e:
                jobs._import(mp, [jobs.plan_import(src / "bad.img.xz")], lambda *a: None, lambda m: None,
                             threading.Event(), True, None)
            self.assertIn("is damaged or didn't finish downloading", str(e.exception))
            self.assertEqual(sorted(f.name for f in mp.iterdir()), ["a.img", "b.img", "c.img"])
            sha = hashlib.sha256(disk).hexdigest()
            with self.assertRaises(rawwrite.RawWriteError):
                jobs._read_back(mp / "a.img", len(disk), "0" * 64, lambda *a: None, 0, len(disk), None)
            with self.assertRaises(rawwrite.RawWriteError):
                jobs._read_back(mp / "a.img", len(disk) - 1, sha, lambda *a: None, 0, len(disk), None)
            jobs._read_back(mp / "a.img", len(disk), sha, lambda *a: None, 0, len(disk), None)
        self.assertEqual(threading.active_count(), before)


FILES: dict[str, bytes] = {}
DROPS: dict[str, int] = {}
RANGES = [True]


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_HEAD(self):
        body = FILES.get(self.path)
        if body is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()

    def do_GET(self):
        body = FILES.get(self.path)
        if body is None:
            self.send_error(404)
            return
        rng = self.headers.get("Range") if RANGES[0] else None
        if rng:
            start = int(rng.split("=")[1].split("-")[0])
            if start >= len(body):
                self.send_error(416)
                return
            body = body[start:]
            self.send_response(206)
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if DROPS.get(self.path, 0) > 0:  # a flaky connection: send half, then hang up
            DROPS[self.path] -= 1
            self.wfile.write(body[: len(body) // 2])
            self.close_connection = True
            return
        self.wfile.write(body)


class HashWhileDownloadingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        FILES.clear()
        DROPS.clear()
        RANGES[0] = True
        for p in (mock.patch.object(download, "_backoff", return_value=0),
                  mock.patch.object(download, "CHUNK", 256 * 1024)):
            p.start()
            self.addCleanup(p.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        env = mock.patch.dict(os.environ, {"XDG_CACHE_HOME": self.tmp.name, "NO_PROXY": "*", "no_proxy": "*"})
        env.start()
        self.addCleanup(env.stop)
        self.cache = Path(self.tmp.name) / "handheld-forge"
        self.data = os.urandom(1_500_000)
        self.sha = hashlib.sha256(self.data).hexdigest()
        FILES["/live.iso"] = self.data
        FILES["/live.iso-CHECKSUM"] = f"{self.sha}  live.iso\n".encode()
        self.distro = Distro("t", "Test OS", "", True, 1, None, [
            ImageSource("live.iso", f"{self.base}/live.iso", f"{self.base}/live.iso-CHECKSUM", None, False)])
        self.reads = []
        real = verify.sha256_file

        def spy(path, progress=None):
            self.reads.append(Path(path).name)
            return real(path, progress)

        p = mock.patch.object(download, "sha256_file", spy)
        p.start()
        self.addCleanup(p.stop)

    def prepare(self):
        stages = []
        img = download.prepare_iso(self.distro, lambda *a: stages.append(a), lambda m: None)
        return img, stages

    def leftover(self, data):
        self.cache.mkdir(exist_ok=True)
        (self.cache / "live.iso.part").write_bytes(data)
        (self.cache / "live.iso.part.expected").write_text(self.sha)

    def test_a_fresh_download_is_not_read_again(self):
        before = threading.active_count()
        img, stages = self.prepare()
        self.assertEqual((img.sha256, img.path.read_bytes(), img.from_cache), (self.sha, self.data, False))
        self.assertEqual(self.reads, [])
        self.assertEqual([s for s in stages if s[0] == "verify"],
                         [("verify", 0, 0), ("verify", len(self.data), len(self.data))])
        self.assertEqual(threading.active_count(), before)

    def test_a_download_that_does_not_match_is_deleted(self):
        FILES["/live.iso"] = self.data[:-1] + bytes([self.data[-1] ^ 1])
        with self.assertRaises(VerificationError):
            self.prepare()
        self.assertEqual(self.reads, [])
        self.assertFalse((self.cache / "live.iso.part").exists())
        self.assertFalse((self.cache / "live.iso").exists())

    def test_a_resumed_download_is_hashed_from_its_first_byte(self):
        self.leftover(self.data[:600_000])
        img, stages = self.prepare()
        self.assertEqual(img.path.read_bytes(), self.data)
        self.assertEqual(self.reads, ["live.iso.part"])
        self.assertEqual(stages[-1], ("verify", len(self.data), len(self.data)))

    def test_a_resumed_download_with_a_damaged_start_is_rejected(self):
        self.leftover(bytes(600_000))
        with self.assertRaises(VerificationError):
            self.prepare()
        self.assertEqual(self.reads, ["live.iso.part"])
        self.assertFalse((self.cache / "live.iso.part").exists())
        self.assertFalse((self.cache / "live.iso").exists())

    def test_a_complete_leftover_is_hashed_from_the_disk(self):
        self.leftover(self.data)
        img, _ = self.prepare()
        self.assertEqual(self.reads, ["live.iso.part"])
        self.assertEqual(img.path.read_bytes(), self.data)
        self.leftover(bytes(len(self.data)))
        (self.cache / "live.iso").unlink()
        with self.assertRaises(VerificationError):
            self.prepare()

    def test_dropped_connections_are_resumed_without_reading_again(self):
        DROPS["/live.iso"] = 3
        img, _ = self.prepare()
        self.assertEqual(img.path.read_bytes(), self.data)
        self.assertEqual(self.reads, [])

    def test_a_server_that_starts_over_is_hashed_from_the_new_start(self):
        RANGES[0] = False
        self.leftover(b"stale" * 50_000)
        DROPS["/live.iso"] = 1
        img, _ = self.prepare()
        self.assertEqual(img.path.read_bytes(), self.data)
        self.assertEqual(self.reads, [])

    def test_the_hash_only_counts_for_bytes_it_saw(self):
        part = Path(self.tmp.name) / "x.part"
        h = download._Hashed()
        try:
            h.start_at(0)
            h.update(b"abc")
            part.write_bytes(b"abc")
            self.assertEqual(h.digest_of(part), hashlib.sha256(b"abc").hexdigest())
            part.write_bytes(b"abcd")
            self.assertIsNone(h.digest_of(part))
            h.start_at(3)
            h.update(b"d")
            self.assertEqual(h.digest_of(part), hashlib.sha256(b"abcd").hexdigest())
            h.start_at(2)  # the file is shorter than what was hashed: no longer trusted
            h.update(b"cd")
            self.assertIsNone(h.digest_of(part))
            h.start_at(0)
            h.update(b"abcd")
            self.assertEqual(h.digest_of(part), hashlib.sha256(b"abcd").hexdigest())
        finally:
            h.close()

    def test_reading_a_live_download_in_pieces(self):
        part = Path(self.tmp.name) / "tail.part"
        part.write_bytes(self.data)
        live = mock.Mock(part=part, md=hashlib.sha256(), read_bytes=0, error=None)
        live.finished = threading.Event()
        live.finished.set()
        live.cancel = threading.Event()
        with download._Tail(live) as t:
            out = t.read(700_000) + t.read(0) + t.read(1_000_000) + t.read(10)
            self.assertEqual(t.read(), b"")
        self.assertEqual(out, self.data)
        self.assertEqual((live.read_bytes, live.md.hexdigest()), (len(self.data), self.sha))


if __name__ == "__main__":
    unittest.main()
