"""Find, download (resumable, cached) and verify the latest ISO for a distro."""

from __future__ import annotations

import hashlib
import http.client
import os
import re
import time
import shutil
import threading
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from . import __version__
from .config import Distro, ImageSource
from .verify import CHUNK, VerificationError, parse_checksum, sha256_file, verify_signature

USER_AGENT = f"HandheldForge/{__version__} (+https://github.com/Ravenor907/Handheld-Forge)"
TIMEOUT = 30
# Resume as long as each try gets somewhere; give up after this many in a row that
# get nothing (about a minute and a half of backoff).
MAX_STALLED_TRIES = 8
SPACE_MARGIN = 200 * 1024 * 1024

StageFn = Callable[[str, int, int], None]  # (stage, done, total)
LogFn = Callable[[str], None]


class Cancelled(Exception):
    pass


class DownloadError(Exception):
    pass


@dataclass
class PreparedImage:
    path: Path
    sha256: str
    source: ImageSource
    signature_verified: bool
    from_cache: bool
    # Labs "write while downloading": still arriving; write from live.open(), then live.finish().
    live: LiveDownload | None = None


class LiveDownload:
    """Downloads into the cache in the background while the writer reads the same file as it
    grows (Labs). Every byte is hashed as it's read, so the published checksum is still checked
    when the download ends; finish() moves the file into the cache like a normal download."""

    def __init__(self, url: str, part: Path, done_path: Path, stamp: Path, part_stamp: Path,
                 expected: str, checksummed: bool, size: int, stage: StageFn, cancel: threading.Event,
                 finalize: Callable[[], bool]) -> None:
        self.url, self.part, self.done_path = url, part, done_path
        self.stamp, self.part_stamp = stamp, part_stamp
        self.expected, self.checksummed, self.size = expected, checksummed, size
        self.cancel, self._finalize = cancel, finalize
        self.error: BaseException | None = None
        self.finished = threading.Event()
        self.md = hashlib.sha256()
        self.read_bytes = 0
        if part.exists():
            part.unlink()  # streaming has to read from the first byte
        part.touch()

        def run() -> None:
            try:
                _download(url, part, stage, cancel)
            except BaseException as e:  # reported to the writer
                self.error = e
            finally:
                self.finished.set()

        self.thread = threading.Thread(target=run, name="live-download", daemon=True)
        self.thread.start()

    def open(self) -> _Tail:
        return _Tail(self)

    def finish(self, log: LogFn = print) -> bool:
        """After writing: waits for the download, checks it, moves it into the cache. Returns
        whether the signature was verified."""
        self.finished.wait()
        if self.error:
            raise self.error
        with self.part.open("rb") as f:  # anything the writer didn't need to read
            f.seek(self.read_bytes)
            while chunk := f.read(CHUNK):
                self.md.update(chunk)
        actual = self.md.hexdigest()
        if self.checksummed and actual != self.expected:
            self.part.unlink(missing_ok=True)
            self.part_stamp.unlink(missing_ok=True)
            raise VerificationError("The download didn't match its published checksum, so the drive "
                                    "isn't usable. Try again.")
        if not self.checksummed and self.size and self.part.stat().st_size != self.size:
            raise VerificationError("The download came out the wrong size. Try again.")
        log("Checksum OK" if self.checksummed else "Download complete")
        signed = self._finalize()
        self.stamp.unlink(missing_ok=True)
        os.replace(self.part, self.done_path)
        self.part_stamp.unlink(missing_ok=True)
        self.stamp.write_text(self.expected)
        return signed


class _Tail:
    """Reads a file that's still being downloaded, waiting at its end until more arrives."""

    def __init__(self, live: LiveDownload) -> None:
        self.live = live
        self.f = live.part.open("rb")
        self.waited = 0.0

    def readable(self) -> bool:
        return True

    def readinto(self, b) -> int:
        while True:
            n = self.f.readinto(b)
            if n:
                self.live.md.update(memoryview(b)[:n])
                self.live.read_bytes += n
                return n
            if self.live.finished.is_set():
                if self.live.error:
                    raise OSError(f"The download failed: {self.live.error}")
                n = self.f.readinto(b)  # the last bytes before it finished
                if n:
                    self.live.md.update(memoryview(b)[:n])
                    self.live.read_bytes += n
                return n or 0
            if self.live.cancel.is_set():
                raise Cancelled()
            if self.live.part.stat().st_size < self.live.read_bytes:
                raise OSError("The server restarted the download. Turn off writing while downloading "
                              "in Labs and try again.")
            t = time.monotonic()
            self.live.finished.wait(0.05)
            self.waited += time.monotonic() - t

    def read(self, n: int = -1) -> bytes:
        if n is None or n < 0:
            out = bytearray()
            while chunk := self.read(CHUNK):
                out += chunk
            return bytes(out)
        b = bytearray(n)
        got = self.readinto(b)
        return bytes(b[:got])

    def close(self) -> None:
        self.f.close()

    def __enter__(self):
        return self

    def __exit__(self, *_) -> None:
        self.close()


def cache_dir() -> Path:
    base = os.environ.get("XDG_CACHE_HOME") or str(Path.home() / ".cache")
    d = Path(base) / "handheld-forge"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _request(url: str, method: str = "GET", headers: dict | None = None) -> urllib.request.Request:
    h = {"User-Agent": USER_AGENT}
    h.update(headers or {})
    return urllib.request.Request(url, method=method, headers=h)


def fetch_text(url: str, max_bytes: int = 64 * 1024) -> str:
    with urllib.request.urlopen(_request(url), timeout=TIMEOUT) as r:
        return r.read(max_bytes).decode("utf-8", "replace")


def remote_meta(url: str) -> tuple[int, str] | None:
    """(size, version tag from ETag or Last-Modified) from a HEAD request, or None if it isn't there."""
    try:
        with urllib.request.urlopen(_request(url, "HEAD"), timeout=TIMEOUT) as r:
            length = r.headers.get("Content-Length")
            tag = (r.headers.get("ETag") or r.headers.get("Last-Modified") or "").strip('" ')
            return (int(length) if length else 0), tag
    except urllib.error.HTTPError as e:
        if e.code in (403, 404, 410):
            return None
        raise


def remote_size(url: str) -> int | None:
    """Size from a HEAD request, or None if the file isn't there."""
    meta = remote_meta(url)
    return None if meta is None else meta[0]


def newest_build(img: ImageSource, log: LogFn = print) -> ImageSource:
    """The newest build named on the publisher's page, or the catalog's own when there's none."""
    if not img.latest_page:
        return img
    try:
        page = fetch_text(img.latest_page, 4 * 1024 * 1024)
        newest = max((m.group(1) for m in re.finditer(img.latest_pattern, page)), default=None)
    except (urllib.error.URLError, OSError, re.error) as e:
        log(f"Couldn't read {img.latest_page} ({e}); using the known build")
        return img
    if newest is None or newest <= img.build:
        return img
    log(f"Newest build on {img.latest_page}: {newest}")
    return img.at(newest)


def resolve_image(distro: Distro, log: LogFn = print) -> ImageSource:
    """First image in the catalog whose ISO actually exists upstream, newest build first."""
    if not distro.enabled:
        raise DownloadError(f"{distro.name} is not available yet")
    for img in [newest_build(i, log) for i in distro.images] + [i for i in distro.images if i.latest_page]:
        try:
            if remote_size(img.iso_url) is not None:
                log(f"Using {img.label}: {img.filename}")
                return img
            log(f"{img.label} not found upstream, trying next")
        except (urllib.error.URLError, OSError) as e:
            log(f"Could not reach {img.iso_url}: {e}")
    raise DownloadError(f"No downloadable image found for {distro.name}")


def _download(url: str, part: Path, stage: StageFn, cancel: threading.Event) -> None:
    """Download with resume; retries dropped connections like the Android app does."""
    attempt = 0
    while True:
        before = part.stat().st_size if part.exists() else 0
        try:
            _download_once(url, part, stage, cancel)
            return
        except urllib.error.HTTPError as e:
            if 400 <= e.code < 500 and e.code not in (408, 429):
                raise
            err: Exception = e
        except (OSError, http.client.HTTPException) as e:  # URLError, timeouts, resets, "closed early"
            err = e
        if part.exists() and part.stat().st_size > before:
            attempt = 0
        attempt += 1
        if attempt > MAX_STALLED_TRIES or cancel.is_set():
            raise err
        if cancel.wait(_backoff(attempt)):
            raise Cancelled()


def _backoff(attempt: int) -> float:
    return min(2 * attempt, 15)


def _download_once(url: str, part: Path, stage: StageFn, cancel: threading.Event) -> None:
    have = part.stat().st_size if part.exists() else 0
    headers = {"Range": f"bytes={have}-"} if have else {}
    try:
        resp = urllib.request.urlopen(_request(url, headers=headers), timeout=TIMEOUT)
    except urllib.error.HTTPError as e:
        if e.code == 416 and have:  # already complete
            return
        raise
    with resp:
        if have and resp.status != 206:  # server ignored Range: start over
            have = 0
        length = int(resp.headers.get("Content-Length") or 0)
        total = have + length if length else 0
        with part.open("ab" if have else "wb") as f:
            done = have
            while chunk := resp.read(CHUNK):
                if cancel.is_set():
                    raise Cancelled()
                f.write(chunk)
                done += len(chunk)
                stage("download", done, total)
        if total and done < total:
            raise ConnectionError("Connection closed early")


def prepare_iso(
    distro: Distro,
    stage: StageFn = lambda *_: None,
    log: LogFn = print,
    cancel: threading.Event | None = None,
    stream: bool = False,
) -> PreparedImage:
    """Return a verified local ISO, downloading only if the cache is stale. With stream (Labs),
    a fresh download is returned while it's still arriving: write from .live, then finish it."""
    cancel = cancel or threading.Event()
    img = resolve_image(distro, log)

    stage("lookup", 0, 0)
    checksummed = img.checksum_url is not None
    if checksummed:
        expected = parse_checksum(fetch_text(img.checksum_url), img.filename)
        log(f"Latest {img.filename} sha256: {expected[:16]}...")
    else:
        # No published checksum (SteamOS): the cache is keyed on the server's size and
        # version tag, and the image's own bzip2 checksums catch damage while writing.
        meta = remote_meta(img.iso_url) or (0, "")
        expected = f"unchecked:{meta[0]}:{meta[1]}"
        log(f"{distro.name} publishes no checksum; the image's own checks and a read-back are used")

    signature = None
    if img.signature_url:
        try:
            signature = fetch_text(img.signature_url).encode()
        except urllib.error.HTTPError as e:
            if img.require_signature:
                raise VerificationError(f"Signature required but missing ({e.code})") from e
            log("No signature published for this image")

    def check_sig() -> bool:
        if signature is None:
            return False
        verify_signature(expected, signature, distro.public_key.read_bytes())
        log("Signature verified against pinned Bazzite key")
        return True

    cdir = cache_dir()
    iso = cdir / img.filename
    stamp = cdir / f"{img.filename}.verified"
    part = cdir / f"{img.filename}.part"
    part_stamp = cdir / f"{img.filename}.part.expected"

    # Reuse cache if we already verified this exact release.
    if iso.exists() and stamp.exists() and stamp.read_text().strip() == expected:
        log("Cached ISO is already the latest release, skipping download")
        signed = check_sig()
        return PreparedImage(iso, expected, img, signed, from_cache=True)

    # A partial download of an older release can't be resumed into a new one.
    if part.exists() and (not part_stamp.exists() or part_stamp.read_text().strip() != expected):
        part.unlink()
    part_stamp.write_text(expected)

    try:
        size = remote_size(img.iso_url) or 0
    except OSError:
        size = 0  # the download itself will report the problem
    need = size - (part.stat().st_size if part.exists() else 0)
    free = shutil.disk_usage(cdir).free
    if need > 0 and free < need + SPACE_MARGIN:
        raise DownloadError(f"Not enough space. Free up {(need + SPACE_MARGIN - free) / 1e9:.1f} GB "
                            f"in {cdir} and try again.")

    if stream:
        log(f"Downloading {img.iso_url} and writing at the same time (Labs)")
        live = LiveDownload(img.iso_url, part, iso, stamp, part_stamp, expected, checksummed, size,
                            lambda *_: None, cancel, check_sig)  # progress follows the writer
        return PreparedImage(part, expected, img, False, from_cache=False, live=live)

    log(f"Downloading {img.iso_url}")
    _download(img.iso_url, part, stage, cancel)

    stage("verify", 0, 0)
    if checksummed:
        actual = sha256_file(part, lambda d, t: stage("verify", d, t))
    else:
        actual = expected if not size or part.stat().st_size == size else "size-mismatch"
    if actual != expected:
        part.unlink(missing_ok=True)
        part_stamp.unlink(missing_ok=True)
        raise VerificationError("Downloaded ISO checksum mismatch, deleted it. Try again.")
    log("Checksum OK" if checksummed else "Download complete")
    signed = check_sig()

    stamp.unlink(missing_ok=True)
    os.replace(part, iso)
    part_stamp.unlink(missing_ok=True)
    stamp.write_text(expected)
    return PreparedImage(iso, expected, img, signed, from_cache=False)
