"""Checks that every enabled system in distros.json has a download that exists upstream.

Run by CI (network needed): python tests/check_catalog_links.py
Every image and checksum URL is reported; it fails only when a system has no
working image at all, since later images are fallbacks by design.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "handheld_forge" / "distros.json"
# Labs systems (Batocera), shown once Labs is on.
LABS = ROOT / "handheld_forge" / "labs.json"


def status(url: str) -> str:
    """'ok (size)' or the failure, trying HEAD first and a one-byte GET if HEAD is refused."""
    for method, headers in (("HEAD", {}), ("GET", {"Range": "bytes=0-0"})):
        headers = {"User-Agent": "HandheldForge-CI", **headers}
        req = urllib.request.Request(url, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                size = r.headers.get("Content-Range", "").rpartition("/")[2]
                size = size or r.headers.get("Content-Length", "?")
                return f"ok ({size} bytes)"
        except urllib.error.HTTPError as e:
            if method == "HEAD" and e.code in (403, 405, 501):
                continue
            return f"HTTP {e.code}"
        except OSError as e:
            return f"error: {e}"
    return "refused"


def main() -> int:
    failed = []
    distros = json.loads(CATALOG.read_text())["distros"]
    if LABS.exists():
        distros += json.loads(LABS.read_text())["distros"]
    for d in distros:
        if not d.get("enabled"):
            continue
        working = False
        for img in d.get("images", []):
            if img.get("latest_page"):
                try:
                    ua = {"User-Agent": "HandheldForge-CI"}
                    req = urllib.request.Request(img["latest_page"], headers=ua)
                    text = urllib.request.urlopen(req, timeout=30).read(4 << 20).decode("utf-8", "replace")
                    own = re.search(img["latest_pattern"], img["iso_url"]).group(1)
                    newest = max((m.group(1) for m in re.finditer(img["latest_pattern"], text)), default=None)
                    if newest is None:
                        note = f"no build on {img['latest_page']} ({len(text)} bytes)"
                    else:
                        url = img["iso_url"].replace(own, newest)
                        note = f"newest build on the page {newest} (catalog has {own}): {status(url)}"
                except OSError as e:
                    note = f"couldn't read {img['latest_page']}: {e}"
                print(f"::notice title={d['id']} latest build::{note}")
                if "newest build" not in note:
                    failed.append(f"{d['id']}: {note}")
            iso = status(img["iso_url"])
            line = f"{d['id']}: {img['label']}: image {iso}"
            ok = iso.startswith("ok")
            if img.get("checksum_url"):
                chk = status(img["checksum_url"])
                line += f", checksum {chk}"
                ok = ok and chk.startswith("ok")
            print(f"::notice title={d['id']}::{line}")
            working = working or ok
        if not working:
            failed.append(d["id"])
            msg = f"{d['id']} has no working download. Update its download link."
            print(f"::error title=Catalog::{msg}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
