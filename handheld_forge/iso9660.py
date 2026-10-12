"""Read files out of an ISO image with plain Python. No root, no mounting.

Supports ISO 9660 with Rock Ridge names (what xorriso -R produces, which is how
Bazzite builds its ISO), multi-extent files (needed for files over 4 GiB such as
LiveOS/squashfs.img), SUSP continuation areas and Rock Ridge deep-directory
relocation.
"""

from __future__ import annotations

import struct
from collections.abc import Iterator
from dataclasses import dataclass, field
from typing import BinaryIO

SECTOR = 2048
FLAG_DIR = 0x02
FLAG_MULTI_EXTENT = 0x80


class IsoError(Exception):
    pass


@dataclass
class IsoEntry:
    path: str  # relative, "/" separated, no leading slash
    is_dir: bool
    extents: list[tuple[int, int]] = field(default_factory=list)  # (byte offset, length)
    symlink: str | None = None

    @property
    def size(self) -> int:
        return sum(length for _, length in self.extents)


@dataclass
class _Record:
    lba: int
    length: int
    flags: int
    raw_name: bytes
    susp: bytes


class IsoReader:
    def __init__(self, f: BinaryIO) -> None:
        self.f = f
        self.volume_id, root = self._read_pvd()
        self._susp_skip: int | None = None
        self._rock_ridge = False
        self._detect_rock_ridge(root)
        self._root = root

    # ------------------------------------------------------------ low level

    def _read(self, offset: int, length: int) -> bytes:
        self.f.seek(offset)
        data = self.f.read(length)
        if len(data) != length:
            raise IsoError("ISO is truncated")
        return data

    def _read_pvd(self) -> tuple[str, _Record]:
        for sector in range(16, 64):
            vd = self._read(sector * SECTOR, SECTOR)
            if vd[1:6] != b"CD001":
                raise IsoError("Not an ISO 9660 image")
            if vd[0] == 1:
                block = struct.unpack_from("<H", vd, 128)[0]
                if block != SECTOR:
                    raise IsoError(f"Unsupported block size {block}")
                volume_id = vd[40:72].decode("ascii", "replace").rstrip(" \x00")
                root = self._parse_record(vd[156:190], 0)
                if root is None:
                    raise IsoError("Bad root directory record")
                return volume_id, root
            if vd[0] == 255:
                break
        raise IsoError("No primary volume descriptor")

    def _parse_record(self, buf: bytes, pos: int) -> _Record | None:
        length = buf[pos]
        if length == 0:
            return None
        lba = struct.unpack_from("<I", buf, pos + 2)[0]
        size = struct.unpack_from("<I", buf, pos + 10)[0]
        flags = buf[pos + 25]
        name_len = buf[pos + 32]
        name = buf[pos + 33: pos + 33 + name_len]
        susp_start = pos + 33 + name_len + (1 - name_len % 2)
        return _Record(lba, size, flags, name, buf[susp_start: pos + length])

    def _dir_records(self, rec: _Record) -> Iterator[_Record]:
        data = self._read(rec.lba * SECTOR, rec.length)
        pos = 0
        while pos < len(data):
            if data[pos] == 0:  # records never cross sectors: jump to next one
                pos = (pos // SECTOR + 1) * SECTOR
                continue
            r = self._parse_record(data, pos)
            pos += data[pos]
            if r is not None:
                yield r

    # ------------------------------------------------------------ SUSP / Rock Ridge

    def _susp_entries(self, area: bytes) -> Iterator[tuple[bytes, bytes]]:
        """Yield (signature, payload), following CE continuation areas."""
        pending = [area[self._susp_skip or 0:]]
        seen = 0
        while pending and seen < 64:
            buf = pending.pop(0)
            seen += 1
            pos = 0
            while pos + 4 <= len(buf):
                sig, length = buf[pos:pos + 2], buf[pos + 2]
                if length < 4 or pos + length > len(buf):
                    break
                payload = buf[pos + 4: pos + length]
                if sig == b"ST":
                    break
                if sig == b"CE" and len(payload) >= 24:
                    blk = struct.unpack_from("<I", payload, 0)[0]
                    off = struct.unpack_from("<I", payload, 8)[0]
                    ln = struct.unpack_from("<I", payload, 16)[0]
                    pending.append(self._read(blk * SECTOR + off, ln))
                else:
                    yield sig, payload
                pos += length

    def _detect_rock_ridge(self, root: _Record) -> None:
        first = next(self._dir_records(root), None)
        if first is None:
            return
        area = first.susp
        # An SP entry at the very start of the root "." record means SUSP is in
        # use. Its LEN_SKP byte says how many bytes to skip in every other record.
        if len(area) >= 7 and area[0:2] == b"SP" and area[4:6] == b"\xbe\xef":
            self._susp_skip = area[6]
            self._rock_ridge = True

    def _name_and_info(self, rec: _Record) -> tuple[str, dict]:
        info: dict = {}
        if self._rock_ridge:
            name_parts: list[bytes] = []
            link_parts: list[str] = []
            for sig, payload in self._susp_entries(rec.susp):
                if sig == b"NM" and payload:
                    if payload[0] & 0x06:  # "." or ".."
                        continue
                    name_parts.append(payload[1:])
                elif sig == b"SL" and payload:
                    link_parts.append(_decode_sl(payload[1:]))
                elif sig == b"CL" and len(payload) >= 4:
                    info["child_lba"] = struct.unpack_from("<I", payload, 0)[0]
                elif sig == b"RE":
                    info["relocated"] = True
            if link_parts:
                info["symlink"] = "".join(link_parts)
            if name_parts:
                return b"".join(name_parts).decode("utf-8", "replace"), info
        name = rec.raw_name.decode("ascii", "replace")
        name = name.split(";", 1)[0]
        if name.endswith(".") and not rec.flags & FLAG_DIR:
            name = name[:-1]
        return name, info

    # ------------------------------------------------------------ public

    def walk(self) -> Iterator[IsoEntry]:
        """Every file and directory, parents before children."""
        yield from self._walk(self._root, "", depth=0)

    def _walk(self, dir_rec: _Record, prefix: str, depth: int) -> Iterator[IsoEntry]:
        if depth > 64:
            raise IsoError("Directory tree too deep")
        pending: IsoEntry | None = None
        for rec in self._dir_records(dir_rec):
            if rec.raw_name in (b"\x00", b"\x01"):
                continue
            if pending is not None:  # continuing a multi-extent file
                pending.extents.append((rec.lba * SECTOR, rec.length))
                if not rec.flags & FLAG_MULTI_EXTENT:
                    yield pending
                    pending = None
                continue

            name, info = self._name_and_info(rec)
            if info.get("relocated") or not name or "/" in name or name in (".", ".."):
                continue
            path = f"{prefix}{name}"

            if "child_lba" in info:  # Rock Ridge relocated deep directory
                child_dot = next(self._dir_records(_Record(info["child_lba"], SECTOR, FLAG_DIR, b"", b"")))
                yield IsoEntry(path, True)
                yield from self._walk(child_dot, f"{path}/", depth + 1)
            elif rec.flags & FLAG_DIR:
                yield IsoEntry(path, True)
                yield from self._walk(rec, f"{path}/", depth + 1)
            else:
                entry = IsoEntry(path, False, [(rec.lba * SECTOR, rec.length)], info.get("symlink"))
                if rec.flags & FLAG_MULTI_EXTENT:
                    pending = entry
                else:
                    yield entry
        if pending is not None:
            raise IsoError(f"Unterminated multi-extent file {pending.path}")

    def read_chunks(self, entry: IsoEntry, chunk: int = 4 * 1024 * 1024) -> Iterator[bytes]:
        for offset, length in entry.extents:
            self.f.seek(offset)
            left = length
            while left:
                data = self.f.read(min(chunk, left))
                if not data:
                    raise IsoError(f"ISO is truncated inside {entry.path}")
                left -= len(data)
                yield data

    def read_file(self, path: str) -> bytes:
        for e in self.walk():
            if e.path == path and not e.is_dir:
                return b"".join(self.read_chunks(e))
        raise FileNotFoundError(path)


def _decode_sl(data: bytes) -> str:
    parts, pos, cur = [], 0, ""
    while pos + 2 <= len(data):
        flags, ln = data[pos], data[pos + 1]
        comp = data[pos + 2: pos + 2 + ln].decode("utf-8", "replace")
        if flags & 0x02:
            comp = "."
        elif flags & 0x04:
            comp = ".."
        elif flags & 0x08:
            parts.append("")  # root
            pos += 2 + ln
            continue
        cur += comp
        if not flags & 0x01:
            parts.append(cur)
            cur = ""
        pos += 2 + ln
    return "/".join(parts) or "/"
