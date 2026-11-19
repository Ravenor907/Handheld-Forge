"""Keeps downloaded images from sitting unused in the download folder (~/.cache/handheld-forge).
The same rules as the Android app's ImageStore:

- once a drive has been made from an image, it's deleted, unless its system is set to Keep;
- an image not used for UNUSED_DAYS days is deleted (kept ones stay);
- a newer, verified image of a system replaces the older one, so there are never two copies;
- leftovers (old partial downloads, stamps without their image, temp files) go when the app opens.

Each image belongs to an owner: the system's id (several builds of one system share it), "ventoy"
for Ventoy's download folder, or "file:<name>" for a file no system in the catalog claims. Keep is
set per owner, so it holds across new versions. The index is images.txt, in the same format as
Android's: "use<TAB>name<TAB>owner<TAB>ms" and "keep<TAB>owner" lines.
"""

from __future__ import annotations

import os
import re
import shutil
import threading
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

UNUSED_DAYS = 14
DAY_MS = 24 * 60 * 60 * 1000
PART_STALE_MS = DAY_MS  # a newer partial download is kept, so a paused download picks up again
TMP_STALE_MS = 60 * 60 * 1000
INDEX = "images.txt"
VENTOY = "ventoy"  # Ventoy's download folder, handled as one image
LEFTOVER_SUFFIXES = (".verified", ".part", ".part.expected", ".tmp")
_LOCK = threading.RLock()


@dataclass(frozen=True)
class Entry:
    path: Path
    owner: str
    bytes: int
    last_used: int  # ms
    keep: bool


def shape(name: str) -> str:
    """A file name with its version numbers blanked, so two builds of one system look alike."""
    return re.sub(r"\d+(\.\d+)*", "#", name)


def owner_from(distros: Iterable) -> Callable[[str], str | None]:
    """Which system a file belongs to: the one whose catalog file name has the same shape."""
    by_shape: dict[str, str] = {}
    for d in distros:
        for img in d.images:
            by_shape.setdefault(shape(img.filename), d.id)
    return lambda name: by_shape.get(shape(name))


def size(n: int) -> str:
    if n >= 10e9:
        return f"{n / 1e9:.0f} GB"
    if n >= 1e9:
        return f"{n / 1e9:.1f} GB"
    return f"{max(1.0, n / 1e6):.0f} MB"


def freed_note(n: int, why: str) -> str:
    """The one line shown after a clean-up."""
    return f"Freed {size(n)} by deleting {why}. It downloads again when needed."


def _size_of(p: Path) -> int:
    if p.is_dir():
        return sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
    return p.stat().st_size


def _newest_ms(p: Path) -> int:
    times = [p.stat().st_mtime]
    if p.is_dir():
        times += [f.stat().st_mtime for f in p.rglob("*")]
    return int(max(times) * 1000)


class ImageStore:
    def __init__(self, folder: Path, owner_of: Callable[[str], str | None] = lambda _: None,
                 now: Callable[[], int] = lambda: int(time.time() * 1000)) -> None:
        self.dir = Path(folder)
        self.owner_of = owner_of
        self.now = now
        self.index = self.dir / INDEX

    # ---------------------------------------------------------------- index

    def _read(self) -> tuple[dict[str, tuple[str, int]], set[str]]:
        used: dict[str, tuple[str, int]] = {}
        keep: set[str] = set()
        try:
            lines = self.index.read_text("utf-8").splitlines()
        except OSError:
            lines = []
        for line in lines:
            f = line.split("\t")
            if len(f) == 4 and f[0] == "use" and f[3].lstrip("-").isdigit():
                used[f[1]] = (f[2], int(f[3]))
            elif len(f) == 2 and f[0] == "keep":
                keep.add(f[1])
        return used, keep

    def _write(self, used: dict[str, tuple[str, int]], keep: set[str]) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        text = "".join(f"use\t{n}\t{o}\t{t}\n" for n, (o, t) in sorted(used.items()))
        text += "".join(f"keep\t{o}\n" for o in sorted(keep))
        tmp = self.dir / f"{INDEX}.tmp"
        tmp.write_text(text, "utf-8")
        os.replace(tmp, self.index)

    def _is_image(self, p: Path) -> bool:
        if p.is_dir():
            return p.name == VENTOY
        if not p.is_file() or p.name == INDEX:
            return False
        return not p.name.endswith(LEFTOVER_SUFFIXES)

    def _owner(self, name: str, used: dict[str, tuple[str, int]]) -> str:
        if name in used:
            return used[name][0]
        if name == VENTOY:
            return VENTOY
        return self.owner_of(name) or f"file:{name}"

    def _children(self) -> list[Path]:
        try:
            return list(self.dir.iterdir())
        except OSError:
            return []

    # ---------------------------------------------------------------- reading

    def entries(self) -> list[Entry]:
        """Every downloaded image, newest use first. A file seen for the first time counts as used
        when it was saved."""
        with _LOCK:
            used, keep = self._read()
            out = []
            for p in self._children():
                try:
                    if not self._is_image(p):
                        continue
                    owner = self._owner(p.name, used)
                    last = used[p.name][1] if p.name in used else _newest_ms(p)
                    out.append(Entry(p, owner, _size_of(p), last, owner in keep))
                except OSError:
                    continue  # went away while looking
            return sorted(out, key=lambda e: e.last_used, reverse=True)

    def total_bytes(self) -> int:
        return sum(e.bytes for e in self.entries())

    def kept(self) -> set[str]:
        with _LOCK:
            return set(self._read()[1])

    def is_kept(self, owner: str) -> bool:
        return owner in self.kept()

    # ---------------------------------------------------------------- changes

    def used(self, owner: str, path: Path) -> None:
        """`path` (an image in the folder) belongs to `owner` and was just used."""
        with _LOCK:
            used, keep = self._read()
            used[Path(path).name] = (owner, self.now())
            self._write(used, keep)

    def set_keep(self, owner: str, on: bool) -> None:
        with _LOCK:
            used, keep = self._read()
            (keep.add if on else keep.discard)(owner)
            self._write(used, keep)

    def replace_older(self, owner: str, current: Path) -> int:
        """A newer `current` of `owner` is downloaded and verified: every other image of it goes."""
        with _LOCK:
            name = Path(current).name
            older = [e.path for e in self.entries() if e.owner == owner and e.path.name != name]
            return sum(self._delete(p) for p in older)

    def after_write(self, owner: str, path: Path) -> int:
        """A drive was made from `path`: delete it unless `owner` is kept. Returns bytes freed."""
        with _LOCK:
            path = Path(path)
            if self.is_kept(owner) or not path.exists():
                return 0
            return self._delete(path)

    def remove_unused(self) -> int:
        """Deletes images not used for UNUSED_DAYS days, except kept ones."""
        with _LOCK:
            cutoff = self.now() - UNUSED_DAYS * DAY_MS
            return sum(self._delete(e.path) for e in self.entries() if not e.keep and e.last_used < cutoff)

    def clean_leftovers(self) -> int:
        """Deletes what nothing will use: partial downloads untouched for a day, stamps whose file is
        gone, temp files, and index lines for images that are gone. Only while no job runs."""
        with _LOCK:
            freed = 0
            stale, now = self.now() - PART_STALE_MS, self.now()

            def mtime(p: Path) -> int:
                try:
                    return int(p.stat().st_mtime * 1000)
                except OSError:
                    return -1

            def sweep(d: Path) -> None:
                nonlocal freed
                try:
                    files = [p for p in d.iterdir() if p.is_file()]
                except OSError:
                    return
                for p in files:
                    n = p.name
                    if n.endswith(".part"):
                        gone = mtime(p) < stale
                    elif n.endswith(".part.expected"):
                        gone = mtime(d / n.removesuffix(".expected")) < stale
                    elif n.endswith(".verified"):
                        gone = not (d / n.removesuffix(".verified")).exists()
                    elif n.endswith(".tmp"):
                        gone = mtime(p) < now - TMP_STALE_MS
                    else:
                        gone = False
                    if gone:
                        freed += _unlink(p)

            sweep(self.dir)
            v = self.dir / VENTOY
            if v.is_dir():
                sweep(v)
                if not any(v.iterdir()):
                    v.rmdir()
            used, keep = self._read()
            gone = [n for n in used if not (self.dir / n).exists()]
            if gone:
                for n in gone:
                    del used[n]
                self._write(used, keep)
            return freed

    def remove_all(self) -> int:
        """Deletes every downloaded image and partial download. Keep choices stay."""
        with _LOCK:
            freed = sum(self._delete(e.path) for e in self.entries())
            for p in self._children():
                if p.is_file() and p.name != INDEX:
                    freed += _unlink(p)
            return freed

    def _delete(self, p: Path) -> int:
        """Deletes an image with its stamps and partial download. Returns bytes freed."""
        try:
            n = _size_of(p)
        except OSError:
            n = 0
        if p.is_dir():
            shutil.rmtree(p, ignore_errors=True)
        else:
            p.unlink(missing_ok=True)
        for s in LEFTOVER_SUFFIXES:
            (self.dir / (p.name + s)).unlink(missing_ok=True)
        used, keep = self._read()
        if used.pop(p.name, None) is not None:
            self._write(used, keep)
        return n


def _unlink(p: Path) -> int:
    try:
        n = p.stat().st_size
        p.unlink()
        return n
    except OSError:
        return 0


# -------------------------------------------------------------------- the app's side

def for_catalog(distros: Iterable, folder: Path | None = None) -> ImageStore:
    """The store for the app's download folder, owning files by the catalog."""
    if folder is None:
        from .download import cache_dir
        folder = cache_dir()
    return ImageStore(folder, owner_from(list(distros)))


def clean_on_start(s: ImageStore) -> str:
    """Leftovers and images unused for two weeks. Returns the note to show, or ""."""
    s.clean_leftovers()
    unused = s.remove_unused()
    return freed_note(unused, f"images not used for {UNUSED_DAYS} days") if unused else ""


def after_download(s: ImageStore, distro, path: Path) -> list[str]:
    """A system's image is downloaded and verified: mark it used, and let it replace older ones."""
    try:
        s.used(distro.id, path)
        freed = s.replace_older(distro.id, path)
    except OSError:  # a clean-up that can't run never fails the job
        return []
    return [freed_note(freed, f"the older {distro.name} image")] if freed else []


def after_drive(s: ImageStore, owner: str, path: Path, what: str) -> list[str]:
    """A drive is made from `path`: its download has done its job, unless it's kept."""
    try:
        s.used(owner, path)
        freed = s.after_write(owner, path)
    except OSError:
        return []
    return [freed_note(freed, what)] if freed else []

