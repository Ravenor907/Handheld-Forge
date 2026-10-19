"""A small in-memory FAT12/FAT16 editor for Ventoy's 32 MB EFI partition image (ported from the
Android app): find, read, delete and replace files, with long names, keeping every FAT copy in
step. That's what Ventoy's own vtoycli does to turn Secure Boot support off."""

from __future__ import annotations

import struct
from dataclasses import dataclass, field


@dataclass
class Entry:
    name: str
    offset: int
    lfn_offsets: list[int] = field(default_factory=list)
    is_dir: bool = False
    first_cluster: int = 0
    size: int = 0


class Fat16:
    def __init__(self, image: bytearray) -> None:
        self.image = image
        b = image
        self.bps = struct.unpack_from("<H", b, 11)[0]
        self.spc = b[13]
        self.reserved = struct.unpack_from("<H", b, 14)[0]
        self.fats = b[16]
        self.root_entries = struct.unpack_from("<H", b, 17)[0]
        total = struct.unpack_from("<H", b, 19)[0] or struct.unpack_from("<I", b, 32)[0]
        self.fat_sectors = struct.unpack_from("<H", b, 22)[0]
        if (
            self.bps not in (512, 1024, 2048, 4096)
            or not self.spc
            or not self.fats
            or not self.fat_sectors
            or not self.root_entries
        ):
            raise OSError("Not a FAT12/16 filesystem")
        self.root_start = (self.reserved + self.fats * self.fat_sectors) * self.bps
        self.root_bytes = self.root_entries * 32
        self.data_start = self.root_start + (self.root_bytes + self.bps - 1) // self.bps * self.bps
        self.cluster_bytes = self.spc * self.bps
        self.cluster_count = (total * self.bps - self.data_start) // self.cluster_bytes
        if self.cluster_count >= 65525:
            raise OSError("FAT32 isn't supported here")
        self.fat12 = self.cluster_count < 4085
        self.eoc = 0xFF8 if self.fat12 else 0xFFF8

    @property
    def label(self) -> str:
        return bytes(self.image[43:54]).decode("ascii", "replace").strip()

    def fat(self, c: int) -> int:
        base = self.reserved * self.bps
        if self.fat12:
            o = base + c * 3 // 2
            v = self.image[o] | (self.image[o + 1] << 8)
            return v & 0xFFF if c % 2 == 0 else v >> 4
        return struct.unpack_from("<H", self.image, base + c * 2)[0]

    def _set_fat(self, c: int, value: int) -> None:
        for copy in range(self.fats):
            base = (self.reserved + copy * self.fat_sectors) * self.bps
            if self.fat12:
                o = base + c * 3 // 2
                v = self.image[o] | (self.image[o + 1] << 8)
                v = (v & 0xF000) | (value & 0xFFF) if c % 2 == 0 else (v & 0x000F) | ((value & 0xFFF) << 4)
                self.image[o] = v & 0xFF
                self.image[o + 1] = v >> 8
            else:
                struct.pack_into("<H", self.image, base + c * 2, value & 0xFFFF)

    def chain(self, first: int) -> list[int]:
        out = []
        c = first
        while 2 <= c < self.cluster_count + 2:
            if len(out) > self.cluster_count:
                raise OSError("FAT chain loops")
            out.append(c)
            n = self.fat(c)
            if n >= self.eoc or n < 2:
                break
            c = n
        return out

    def _cluster_offset(self, c: int) -> int:
        return self.data_start + (c - 2) * self.cluster_bytes

    def _regions(self, dir_cluster: int) -> list[range]:
        if dir_cluster == 0:
            return [range(self.root_start, self.root_start + self.root_bytes)]
        return [
            range(self._cluster_offset(c), self._cluster_offset(c) + self.cluster_bytes)
            for c in self.chain(dir_cluster)
        ]

    def _lfn_part(self, o: int) -> str:
        out = []
        for pos in (1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30):
            ch = struct.unpack_from("<H", self.image, o + pos)[0]
            if ch in (0, 0xFFFF):
                break
            out.append(chr(ch))
        return "".join(out)

    def _short_name(self, o: int) -> str:
        base = bytes(self.image[o : o + 8]).decode("latin-1").rstrip()
        ext = bytes(self.image[o + 8 : o + 11]).decode("latin-1").rstrip()
        if self.image[o + 12] & 0x08:
            base = base.lower()
        if self.image[o + 12] & 0x10:
            ext = ext.lower()
        return f"{base}.{ext}" if ext else base

    def list(self, dir_cluster: int = 0) -> list[Entry]:
        out: list[Entry] = []
        lfn: list[tuple[int, str]] = []
        for r in self._regions(dir_cluster):
            for o in range(r.start, r.stop - 31, 32):
                first = self.image[o]
                if first == 0:
                    return out
                attr = self.image[o + 11]
                if first == 0xE5:
                    lfn.clear()
                elif attr == 0x0F:
                    lfn.append((o, self._lfn_part(o)))
                elif not attr & 0x08:
                    short = self._short_name(o)
                    long = "".join(p for _, p in reversed(lfn)) if lfn else None
                    cluster = struct.unpack_from("<H", self.image, o + 26)[0]
                    size = struct.unpack_from("<I", self.image, o + 28)[0]
                    if short not in (".", ".."):
                        out.append(
                            Entry(long or short, o, [x for x, _ in lfn], bool(attr & 0x10), cluster, size)
                        )
                    lfn.clear()
                else:
                    lfn.clear()
        return out

    def find(self, path: str) -> Entry | None:
        d = 0
        parts = [p for p in path.strip("/").split("/") if p]
        for i, p in enumerate(parts):
            e = next((x for x in self.list(d) if x.name.lower() == p.lower()), None)
            if e is None:
                return None
            if i == len(parts) - 1:
                return e
            if not e.is_dir:
                return None
            d = e.first_cluster
        return None

    def read(self, path: str) -> bytes:
        e = self.find(path)
        if e is None:
            raise OSError(f"{path} not found")
        out = bytearray()
        for c in self.chain(e.first_cluster):
            if len(out) >= e.size:
                break
            o = self._cluster_offset(c)
            out += self.image[o : o + min(self.cluster_bytes, e.size - len(out))]
        if len(out) < e.size:
            raise OSError(f"{path} is shorter on disk than its size says")
        return bytes(out)

    def _free_chain(self, first: int) -> None:
        for c in self.chain(first):
            self._set_fat(c, 0)

    def delete(self, path: str) -> bool:
        e = self.find(path)
        if e is None:
            return False
        if e.is_dir:
            raise OSError(f"{path} is a folder")
        if e.first_cluster >= 2:
            self._free_chain(e.first_cluster)
        for o in [*e.lfn_offsets, e.offset]:
            self.image[o] = 0xE5
        return True

    def replace(self, path: str, data: bytes) -> None:
        e = self.find(path)
        if e is None:
            raise OSError(f"{path} not found")
        if e.first_cluster >= 2:
            self._free_chain(e.first_cluster)
        need = (len(data) + self.cluster_bytes - 1) // self.cluster_bytes
        free = [c for c in range(2, self.cluster_count + 2) if self.fat(c) == 0][:need]
        if len(free) < need:
            raise OSError(f"Not enough room in the FAT partition for {path}")
        for i, c in enumerate(free):
            self._set_fat(c, 0xFFFF if i == len(free) - 1 else free[i + 1])
            o = self._cluster_offset(c)
            piece = data[i * self.cluster_bytes : (i + 1) * self.cluster_bytes]
            self.image[o : o + self.cluster_bytes] = piece.ljust(self.cluster_bytes, b"\0")
        struct.pack_into("<H", self.image, e.offset + 26, free[0] if free else 0)
        struct.pack_into("<H", self.image, e.offset + 20, 0)
        struct.pack_into("<I", self.image, e.offset + 28, len(data))
