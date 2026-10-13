"""Find, download (resumable, cached) and verify the latest ISO for a distro."""

from __future__ import annotations

import http.client
import os
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


def resolve_image(distro: Distro, log: LogFn = print) -> ImageSource:
    """First image in the catalog whose ISO actually exists upstream."""
    if not distro.enabled:
        raise DownloadError(f"{distro.name} is not available yet")
    for img in distro.images:
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
) -> PreparedImage:
    """Return a verified local ISO, downloading only if the cache is stale."""
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
