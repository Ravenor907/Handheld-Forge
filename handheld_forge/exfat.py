"""exFAT formatting (Microsoft's published spec), MBR tables and "Format drive", ported from
the Android app so both make the same drives. The up-case table is the very file the Android
app generates (exfat_upcase.bin), so the two apps' volumes match byte for byte."""

from __future__ import annotations

import secrets
import struct
from dataclasses import dataclass
from importlib import resources

from .blockdev import cancelled

SECTOR = 512
FIRST_CLUSTER = 2
ENTRY = 32


def upcase_table() -> bytes:
    return resources.files(__package__).joinpath("exfat_upcase.bin").read_bytes()


def _align(v: int, to: int) -> int:
    return (v + to - 1) // to * to


@dataclass(frozen=True)
class Layout:
    partition_offset: int
    volume_length: int
    sectors_per_cluster: int
    fat_offset: int
    fat_length: int
    heap_offset: int
    cluster_count: int
    bitmap_cluster: int
    bitmap_bytes: int
    upcase_cluster: int
    upcase_bytes: int
    root_cluster: int

    @property
    def cluster_bytes(self) -> int:
        return self.sectors_per_cluster * SECTOR

    def clusters_for(self, n: int) -> int:
        return (n + self.cluster_bytes - 1) // self.cluster_bytes

    def sector_of(self, cluster: int) -> int:
        return self.heap_offset + (cluster - FIRST_CLUSTER) * self.sectors_per_cluster

    @property
    def used_clusters(self) -> int:
        return self.root_cluster + 1 - FIRST_CLUSTER


def default_sectors_per_cluster(volume_sectors: int) -> int:
    """32 KB clusters up to 32 GB, 128 KB above (like Ventoy and mkfs.exfat)."""
    return 256 if volume_sectors * SECTOR > 32_000_000_000 else 64


def layout(partition_offset: int, volume_sectors: int, spc: int | None = None) -> Layout:
    spc = spc or default_sectors_per_cluster(volume_sectors)
    if spc & (spc - 1) or not 1 <= spc <= 65536:
        raise ValueError("sectors per cluster must be a power of two")
    fat_offset = max(128, spc)
    clusters = (volume_sectors - fat_offset) // spc
    while True:
        fat_length = _align((clusters + 2) * 4 // SECTOR + 1, spc)
        heap = _align(fat_offset + fat_length, spc)
        fits = (volume_sectors - heap) // spc
        if fits >= clusters:
            break
        clusters = fits
    if clusters <= 16:
        raise ValueError("Partition too small for exFAT")
    bitmap_bytes = (clusters + 7) // 8
    cb = spc * SECTOR
    bitmap_clusters = (bitmap_bytes + cb - 1) // cb
    up = len(upcase_table())
    up_clusters = (up + cb - 1) // cb
    return Layout(
        partition_offset,
        volume_sectors,
        spc,
        fat_offset,
        fat_length,
        heap,
        clusters,
        2,
        bitmap_bytes,
        2 + bitmap_clusters,
        up,
        2 + bitmap_clusters + up_clusters,
    )


def _checksum32(data: bytes) -> int:
    s = 0
    for x in data:
        s = (((s & 1) << 31) | (s >> 1)) + x & 0xFFFFFFFF
    return s


def boot_region(lay: Layout, serial: int) -> bytes:
    r = bytearray(12 * SECTOR)
    r[0:3] = b"\xeb\x76\x90"
    r[3:11] = b"EXFAT   "
    struct.pack_into(
        "<QQIIIIII",
        r,
        64,
        lay.partition_offset,
        lay.volume_length,
        lay.fat_offset,
        lay.fat_length,
        lay.heap_offset,
        lay.cluster_count,
        lay.root_cluster,
        serial & 0xFFFFFFFF,
    )
    struct.pack_into("<HH", r, 104, 0x0100, 0)
    r[108] = 9
    r[109] = lay.sectors_per_cluster.bit_length() - 1
    r[110] = 1
    r[111] = 0x80
    r[112] = lay.used_clusters * 100 // lay.cluster_count
    r[120:510] = b"\xf4" * 390
    for s in range(9):
        r[s * SECTOR + 510 : s * SECTOR + 512] = b"\x55\xaa"
    s = 0
    for i in range(11 * SECTOR):
        if i in (106, 107, 112):
            continue
        s = ((((s & 1) << 31) | (s >> 1)) + r[i]) & 0xFFFFFFFF
    r[11 * SECTOR : 12 * SECTOR] = struct.pack("<I", s) * (SECTOR // 4)
    return bytes(r)


def format_volume(
    dev,
    partition_offset: int,
    volume_sectors: int,
    label: str,
    spc: int | None = None,
    serial: int | None = None,
    progress=lambda d, t: None,
) -> Layout:
    """Formats volume_sectors sectors starting at partition_offset (512-byte sectors) as exFAT."""
    if dev.block_size != SECTOR:
        raise ValueError("exFAT here needs 512-byte sectors")
    if not 1 <= len(label) <= 11:
        raise ValueError("exFAT labels are 1 to 11 characters")
    serial = secrets.randbits(32) if serial is None else serial
    lay = layout(partition_offset, volume_sectors, spc)
    total = lay.fat_length + lay.used_clusters * lay.sectors_per_cluster + 24
    done = 0

    def put(sector_in_volume: int, data: bytes) -> None:
        nonlocal done
        step = 2048 * SECTOR
        for at in range(0, len(data), step):
            piece = data[at : at + step]
            dev.write(partition_offset + sector_in_volume + at // SECTOR, piece)
            done += len(piece) // SECTOR
            progress(done, total)

    # Clear what the structures below don't cover: the boot region, the gap before the FAT, the
    # gap before the cluster heap, and the volume's last 1 MB. Old filesystem markers there (an
    # ISO's at 32 KB, a UDF anchor at the end) make Linux see two filesystems and give up.
    zero = bytes(2048 * SECTOR)
    for start, end in ((0, lay.fat_offset), (lay.fat_offset + lay.fat_length, lay.heap_offset),
                       (max(lay.heap_offset, volume_sectors - 2048), volume_sectors)):
        for s in range(start, end, 2048):
            dev.write(partition_offset + s, zero[: min(2048, end - s) * SECTOR])

    # FAT: media entries, then chains for the bitmap, up-case table and root directory.
    fat_bytes = lay.fat_length * SECTOR
    head = {0: 0xFFFFFFF8, 1: 0xFFFFFFFF}

    def chain(start: int, count: int) -> None:
        for c in range(start, start + count):
            head[c] = 0xFFFFFFFF if c == start + count - 1 else c + 1

    chain(lay.bitmap_cluster, lay.upcase_cluster - lay.bitmap_cluster)
    chain(lay.upcase_cluster, lay.root_cluster - lay.upcase_cluster)
    chain(lay.root_cluster, 1)
    first = bytearray(min(fat_bytes, 2048 * SECTOR))
    for c, v in head.items():
        struct.pack_into("<I", first, c * 4, v)
    put(lay.fat_offset, bytes(first))
    zeros = bytes(2048 * SECTOR)
    at = len(first)
    while at < fat_bytes:
        n = min(len(zeros), fat_bytes - at)
        put(lay.fat_offset + at // SECTOR, zeros[:n])
        at += n

    bitmap = bytearray(lay.clusters_for(lay.bitmap_bytes) * lay.cluster_bytes)
    for i in range(lay.used_clusters):
        bitmap[i // 8] |= 1 << (i % 8)
    put(lay.sector_of(lay.bitmap_cluster), bytes(bitmap))

    up = upcase_table()
    put(
        lay.sector_of(lay.upcase_cluster),
        up.ljust(lay.clusters_for(lay.upcase_bytes) * lay.cluster_bytes, b"\0"),
    )

    root = bytearray(lay.cluster_bytes)
    root[0] = 0x83
    root[1] = len(label)
    for i, ch in enumerate(label):
        struct.pack_into("<H", root, 2 + i * 2, ord(ch))
    root[ENTRY] = 0x81
    struct.pack_into("<IQ", root, ENTRY + 20, lay.bitmap_cluster, lay.bitmap_bytes)
    root[2 * ENTRY] = 0x82
    struct.pack_into("<I", root, 2 * ENTRY + 4, _checksum32(up))
    struct.pack_into("<IQ", root, 2 * ENTRY + 20, lay.upcase_cluster, lay.upcase_bytes)
    put(lay.sector_of(lay.root_cluster), bytes(root))

    # Boot region last, so the volume only looks valid once everything else is in place.
    boot = boot_region(lay, serial)
    put(12, boot)
    put(0, boot)
    return lay


# ---------------------------------------------------------------- MBR


def _chs(lba: int) -> bytes:
    spt, heads = 63, 255
    c = lba // (spt * heads)
    if c > 1023:
        return b"\xfe\xff\xff"
    h = (lba // spt) % heads
    s = lba % spt + 1
    return bytes([h, (s & 0x3F) | ((c >> 2) & 0xC0), c & 0xFF])


def write_mbr_table(sector0: bytearray, parts: list[tuple[int, int, int, bool]]) -> None:
    """parts: (start, sectors, type, active). Bytes 0..445 are left alone."""
    sector0[446:510] = bytes(64)
    for i, (start, sectors, ptype, active) in enumerate(parts):
        o = 446 + i * 16
        sector0[o] = 0x80 if active else 0
        sector0[o + 1 : o + 4] = _chs(start)
        sector0[o + 4] = ptype
        sector0[o + 5 : o + 8] = _chs(start + sectors - 1)
        struct.pack_into("<II", sector0, o + 8, start, sectors)
    sector0[510:512] = b"\x55\xaa"


def wipe_ends(dev, sectors: int = 2048) -> None:
    n = min(sectors, dev.block_count)
    zeros = bytes(n * dev.block_size)
    dev.write(0, zeros)
    dev.write(dev.block_count - n, zeros)


PLAIN_LABEL = "USB DRIVE"
PLAIN_START = 2048


def plain_format(dev, label: str = PLAIN_LABEL, stage=lambda *_: None, log=print, cancel=None) -> None:
    """Format drive: one exFAT partition across the drive, readable everywhere."""
    if dev.block_size != 512:
        raise ValueError(
            f"This drive uses {dev.block_size}-byte sectors, which formatting doesn't support yet."
        )
    if dev.block_count <= PLAIN_START + 8192:
        raise ValueError("This drive is too small to format.")
    stage("write", 0, 0)
    log(f"Clearing the old layout from {dev.name}")
    wipe_ends(dev)
    if cancelled(cancel):
        from .rawwrite import Cancelled

        raise Cancelled()
    sectors = (dev.block_count - PLAIN_START) // 2048 * 2048
    log(f'Formatting exFAT, {sectors * 512 / 1e9:.1f} GB, label "{label}"')
    format_volume(dev, PLAIN_START, sectors, label, progress=lambda d, t: stage("write", d, t))
    mbr = bytearray(512)
    write_mbr_table(mbr, [(PLAIN_START, sectors, 0x07, False)])
    mbr[440:444] = secrets.token_bytes(4)  # disk signature, so Windows doesn't ask to "initialize" it
    dev.write(0, bytes(mbr))
    dev.sync()
    stage("check", 0, 1)
    if dev.read(0, 1) != bytes(mbr):
        raise OSError("The drive didn't keep what was written. It may be failing; try another drive.")
    stage("check", 1, 1)
    log("Done: one exFAT partition")
