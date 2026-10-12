"""Tiny ISO 9660 + Rock Ridge writer used only by tests.

It can force multi-extent files with a tiny extent size, so the 4 GiB
splitting path in the reader is exercised without writing 4 GiB.
"""

from __future__ import annotations

import struct

SECTOR = 2048


def _both16(v):
    return struct.pack("<H", v) + struct.pack(">H", v)


def _both32(v):
    return struct.pack("<I", v) + struct.pack(">I", v)


def _record(name: bytes, lba: int, size: int, flags: int, susp: bytes = b"") -> bytes:
    pad = b"\x00" if len(name) % 2 == 0 else b""
    body_len = 33 + len(name) + len(pad) + len(susp)
    if body_len % 2:
        susp += b"\x00"
        body_len += 1
    return (bytes([body_len, 0]) + _both32(lba) + _both32(size) + bytes(7) + bytes([flags, 0, 0])
            + _both16(1) + bytes([len(name)]) + name + pad + susp)


def _nm(name: str) -> bytes:
    b = name.encode()
    return b"NM" + bytes([5 + len(b), 1, 0]) + b


def build_iso(files: dict[str, bytes], label: str = "Bazzite-Live", rock_ridge: bool = True,
              max_extent: int | None = None, ce_names: bool = False) -> bytes:
    """files: {"dir/sub/name": data}. Directories are implied."""
    dirs: dict[str, dict] = {"": {}}
    for path in files:
        parts = path.split("/")
        for i in range(1, len(parts)):
            d = "/".join(parts[:i])
            dirs.setdefault(d, {})
    for path, data in files.items():
        parent = path.rsplit("/", 1)[0] if "/" in path else ""
        dirs[parent][path.rsplit("/", 1)[-1]] = ("file", path)
    for d in dirs:
        if d:
            parent = d.rsplit("/", 1)[0] if "/" in d else ""
            dirs[parent][d.rsplit("/", 1)[-1]] = ("dir", d)

    # Layout: 16 system sectors, PVD, terminator, then one sector per dir, then
    # a CE sector, then file data.
    next_lba = 18
    dir_lba = {}
    for d in sorted(dirs, key=lambda x: (x.count("/"), x)):
        dir_lba[d] = next_lba
        next_lba += 1
    ce_lba = next_lba
    next_lba += 1
    file_extents: dict[str, list[tuple[int, int]]] = {}
    blobs: list[tuple[int, bytes]] = []
    for path, data in files.items():
        step = max_extent or max(len(data), 1)
        exts = []
        chunks = [data[i:i + step] for i in range(0, len(data), step)] or [b""]
        for chunk in chunks:
            exts.append((next_lba, len(chunk)))
            blobs.append((next_lba, chunk))
            next_lba += max(1, -(-len(chunk) // SECTOR))
        file_extents[path] = exts

    ce_area = b""
    img = bytearray(next_lba * SECTOR)

    def iso_name(name: str, is_dir: bool, idx: int) -> bytes:
        if not rock_ridge:
            return (name.upper().encode() if is_dir else name.upper().encode() + b";1")
        return f"F{idx:06d}".encode() + (b"" if is_dir else b";1")

    for d, children in dirs.items():
        parent = d.rsplit("/", 1)[0] if "/" in d else ""
        dot_susp = b""
        if rock_ridge and d == "":
            dot_susp = b"SP\x07\x01\xbe\xef\x00"
        recs = _record(b"\x00", dir_lba[d], SECTOR, 2, dot_susp)
        recs += _record(b"\x01", dir_lba[parent], SECTOR, 2)
        for idx, (name, (kind, full)) in enumerate(sorted(children.items())):
            nb = iso_name(name, kind == "dir", idx)
            if rock_ridge and ce_names:
                off = len(ce_area)
                ce_area += _nm(name)
                susp = (b"CE" + bytes([28, 1]) + _both32(ce_lba) + _both32(off) + _both32(len(_nm(name))))
            else:
                susp = _nm(name) if rock_ridge else b""
            if kind == "dir":
                recs += _record(nb, dir_lba[full], SECTOR, 2, susp)
            else:
                exts = file_extents[full]
                for i, (lba, ln) in enumerate(exts):
                    flags = 0x80 if i < len(exts) - 1 else 0
                    recs += _record(nb, lba, ln, flags, susp)
        assert len(recs) <= SECTOR, "test dir too big"
        img[dir_lba[d] * SECTOR: dir_lba[d] * SECTOR + len(recs)] = recs

    img[ce_lba * SECTOR: ce_lba * SECTOR + len(ce_area)] = ce_area
    for lba, chunk in blobs:
        img[lba * SECTOR: lba * SECTOR + len(chunk)] = chunk

    pvd = bytearray(SECTOR)
    pvd[0] = 1
    pvd[1:6] = b"CD001"
    pvd[6] = 1
    pvd[40:72] = label.encode().ljust(32)
    pvd[80:88] = _both32(next_lba)
    pvd[128:132] = _both16(SECTOR)
    pvd[156:190] = _record(b"\x00", dir_lba[""], SECTOR, 2)
    img[16 * SECTOR: 17 * SECTOR] = pvd
    term = bytearray(SECTOR)
    term[0] = 255
    term[1:6] = b"CD001"
    img[17 * SECTOR: 18 * SECTOR] = term
    return bytes(img)
