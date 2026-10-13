"""End-to-end download tests against a local HTTP server with Range support."""

import base64
import hashlib
import http.server
import os
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from handheld_forge import download
from handheld_forge.config import Distro, ImageSource
from handheld_forge.verify import VerificationError

FILES: dict[str, bytes] = {}
HITS: list[str] = []
DROPS: dict[str, int] = {}  # path -> how many more GETs to cut off halfway


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _body(self):
        return FILES.get(self.path)

    def do_HEAD(self):
        body = self._body()
        if body is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()

    def do_GET(self):
        HITS.append(self.path)
        body = self._body()
        if body is None:
            self.send_error(404)
            return
        rng = self.headers.get("Range")
        if rng:
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
        if DROPS.get(self.path, 0) > 0:  # a flaky connection: send half, then hang up
            DROPS[self.path] -= 1
            self.wfile.write(chunk[: len(chunk) // 2])
            self.close_connection = True
            return
        self.wfile.write(chunk)


class DownloadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.base = f"http://127.0.0.1:{cls.server.server_port}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def setUp(self):
        FILES.clear()
        HITS.clear()
        DROPS.clear()
        self.no_wait = mock.patch.object(download, "_backoff", return_value=0)
        self.no_wait.start()
        self.addCleanup(self.no_wait.stop)
        self.tmp = tempfile.TemporaryDirectory()
        self.env = mock.patch.dict(os.environ, {"XDG_CACHE_HOME": self.tmp.name,
                                                "NO_PROXY": "*", "no_proxy": "*"})
        self.env.start()
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.keyfile = Path(self.tmp.name) / "k.pub"
        self.keyfile.write_bytes(self.key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    def publish(self, name, data, sign=True, signer=None):
        FILES[f"/{name}"] = data
        FILES[f"/{name}-CHECKSUM"] = f"{hashlib.sha256(data).hexdigest()}  {name}\n".encode()
        if sign:
            FILES[f"/{name}.sig"] = base64.b64encode(
                (signer or self.key).sign(data, ec.ECDSA(hashes.SHA256())))

    def distro(self, *names, require_sig=True):
        imgs = [ImageSource(n, f"{self.base}/{n}", f"{self.base}/{n}-CHECKSUM",
                            f"{self.base}/{n}.sig" if require_sig else None, require_sig)
                for n in names]
        return Distro("t", "Test OS", "", True, 1, self.keyfile, imgs)

    def test_fresh_download_then_cache(self):
        data = os.urandom(3 * 1024 * 1024)
        self.publish("live.iso", data)
        d = self.distro("live.iso")
        img = download.prepare_iso(d, log=lambda m: None)
        self.assertTrue(img.signature_verified)
        self.assertFalse(img.from_cache)
        self.assertEqual(img.path.read_bytes(), data)

        HITS.clear()
        again = download.prepare_iso(d, log=lambda m: None)
        self.assertTrue(again.from_cache)
        self.assertNotIn("/live.iso", HITS)  # no re-download

    def test_new_release_redownloads(self):
        self.publish("live.iso", b"old" * 1000)
        d = self.distro("live.iso")
        download.prepare_iso(d, log=lambda m: None)
        self.publish("live.iso", b"new" * 1000)
        img = download.prepare_iso(d, log=lambda m: None)
        self.assertFalse(img.from_cache)
        self.assertEqual(img.path.read_bytes(), b"new" * 1000)

    def test_resume_partial(self):
        data = os.urandom(2 * 1024 * 1024)
        self.publish("live.iso", data)
        cdir = Path(self.tmp.name) / "handheld-forge"
        cdir.mkdir()
        (cdir / "live.iso.part").write_bytes(data[:500_000])
        (cdir / "live.iso.part.expected").write_text(hashlib.sha256(data).hexdigest())
        img = download.prepare_iso(self.distro("live.iso"), log=lambda m: None)
        self.assertEqual(img.path.read_bytes(), data)

    def test_falls_back_to_second_image(self):
        self.publish("legacy.iso", b"legacy" * 100, sign=False)
        img = download.prepare_iso(self.distro("live.iso", "legacy.iso", require_sig=False),
                                   log=lambda m: None)
        self.assertEqual(img.source.filename, "legacy.iso")
        self.assertFalse(img.signature_verified)

    def test_corrupt_download_rejected_and_deleted(self):
        self.publish("live.iso", b"good" * 1000)
        FILES["/live.iso"] = b"evil" * 1000
        with self.assertRaises(VerificationError):
            download.prepare_iso(self.distro("live.iso"), log=lambda m: None)
        cdir = Path(self.tmp.name) / "handheld-forge"
        self.assertFalse((cdir / "live.iso").exists())
        self.assertFalse((cdir / "live.iso.part").exists())

    def test_bad_signature_rejected(self):
        self.publish("live.iso", b"data" * 1000, signer=ec.generate_private_key(ec.SECP256R1()))
        with self.assertRaises(VerificationError):
            download.prepare_iso(self.distro("live.iso"), log=lambda m: None)

    def test_missing_required_signature(self):
        self.publish("live.iso", b"data" * 1000, sign=False)
        with self.assertRaises(VerificationError):
            download.prepare_iso(self.distro("live.iso"), log=lambda m: None)

    def test_resumes_after_dropped_connections(self):
        data = os.urandom(2 * 1024 * 1024)
        self.publish("live.iso", data)
        DROPS["/live.iso"] = 3
        img = download.prepare_iso(self.distro("live.iso"), log=lambda m: None)
        self.assertEqual(img.path.read_bytes(), data)
        self.assertEqual(HITS.count("/live.iso"), 4)

    def test_gives_up_when_nothing_arrives(self):
        calls = []

        def stalled(url, part, stage, cancel):
            calls.append(url)
            raise ConnectionResetError("reset by peer")

        with mock.patch.object(download, "_download_once", stalled), self.assertRaises(ConnectionResetError):
            download._download(f"{self.base}/live.iso", Path(self.tmp.name) / "x.part",
                               lambda *a: None, threading.Event())
        self.assertEqual(len(calls), download.MAX_STALLED_TRIES + 1)

    def test_client_errors_are_not_retried(self):
        calls = []

        def forbidden(url, part, stage, cancel):
            calls.append(url)
            raise download.urllib.error.HTTPError(url, 403, "Forbidden", {}, None)

        with mock.patch.object(download, "_download_once", forbidden), \
                self.assertRaises(download.urllib.error.HTTPError):
            download._download("http://x/y", Path(self.tmp.name) / "y.part",
                               lambda *a: None, threading.Event())
        self.assertEqual(len(calls), 1)

    def test_not_enough_space(self):
        self.publish("live.iso", os.urandom(1024 * 1024))
        usage = mock.Mock(free=10)
        with mock.patch.object(download.shutil, "disk_usage", return_value=usage), \
                self.assertRaises(download.DownloadError) as e:
            download.prepare_iso(self.distro("live.iso"), log=lambda m: None)
        self.assertIn("Not enough space", str(e.exception))

    def test_cancel(self):
        self.publish("live.iso", os.urandom(9 * 1024 * 1024))
        ev = threading.Event()
        ev.set()
        with self.assertRaises(download.Cancelled):
            download.prepare_iso(self.distro("live.iso"), log=lambda m: None, cancel=ev)


if __name__ == "__main__":
    unittest.main()
