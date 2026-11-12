"""Labs: test a drive's real speed and capacity, and compare a drive with an image. Same method
and report as the Android app.

The test writes a long sequential run from the start (1 to 4 GB, about 90 seconds' worth),
timing each 256 MB so a stick that slows down once its fast cache fills shows it, then 1 MB
markers across the rest of the drive up to its last megabyte, and reads it all back. Every block
holds a pattern made from its address, so a block that lands somewhere else can't pass. A fake
drive that claims more than it holds loses markers or overwrites the start.
"""

from __future__ import annotations

import random
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field

from .blockdev import cancelled

CHUNK = 4 << 20
SEGMENT = 256 << 20
SPOT = 1 << 20


def pattern(length: int, byte_offset: int, seed: int) -> bytes:
    """Deterministic bytes for this address: a generator per 64 KB keyed by seed and address."""
    key = (seed & (2**64 - 1)) << 64
    end = byte_offset + length
    rnd = random.Random
    return b"".join(rnd(key | o).randbytes(min(end - o, 65536)) for o in range(byte_offset, end, 65536))


class _Ahead:
    """Makes the next block's test data on a worker thread while the drive is busy with this one.
    take() gives exactly pattern(n, offset, seed), from the worker if that block was planned."""

    def __init__(self, seed: int) -> None:
        self.seed = seed
        self.pool = ThreadPoolExecutor(1, thread_name_prefix="drivetest")
        self.planned = None
        self.future = None

    def plan(self, n: int, byte_offset: int) -> None:
        self.planned = (n, byte_offset)
        self.future = self.pool.submit(pattern, n, byte_offset, self.seed)

    def take(self, n: int, byte_offset: int) -> bytes:
        if self.planned == (n, byte_offset):
            self.planned = None
            return self.future.result()
        return pattern(n, byte_offset, self.seed)

    def close(self) -> None:
        self.pool.shutdown(wait=True, cancel_futures=True)


def _wrong_bytes(got, want) -> int:
    """How many bytes differ, position by position, over the length both have."""
    n = min(len(got), len(want))
    bad = 0
    for at in range(0, n, 65536):
        a, b = got[at : min(at + 65536, n)], want[at : min(at + 65536, n)]
        if a != b:
            x = int.from_bytes(a, "little") ^ int.from_bytes(b, "little")
            bad += len(a) - x.to_bytes(len(a), "little").count(0)
    return bad


def _first_difference(chunk, disk) -> int:
    """Index of the first byte where the two differ."""
    at = 0
    while at < len(chunk) and chunk[at : at + 65536] == disk[at : at + 65536]:
        at += 65536
    return next(k for k in range(at, len(chunk)) if chunk[k] != disk[k])


@dataclass
class Report:
    drive_bytes: int
    tested_bytes: int
    segments: list[float] = field(default_factory=list)
    write_mbs: float = 0.0
    read_mbs: float = 0.0
    bad_bytes: int = 0
    spots: int = 0
    bad_spots: int = 0
    first_bad_spot: int | None = None

    @property
    def fake(self) -> bool:
        return self.bad_spots > 0

    @property
    def failing(self) -> bool:
        return self.bad_bytes > 0

    @property
    def slowdown(self) -> tuple[float, float] | None:
        if len(self.segments) < 3:
            return None
        first = self.segments[0]
        tail_n = max(1, len(self.segments) // 3)
        tail = sum(self.segments[-tail_n:]) / tail_n
        return (first, tail) if tail < first * 0.5 else None

    def lines(self) -> list[str]:
        out = [f"Write: {self.write_mbs:.1f} MB/s over {self.tested_bytes / 1e9:.1f} GB"]
        if self.slowdown:
            a, b = self.slowdown
            out.append(f"It starts at {a:.0f} MB/s, then drops to {b:.0f} MB/s once its fast cache is full")
        out.append(f"Read: {self.read_mbs:.1f} MB/s")
        if self.fake:
            where = (
                f", starting at {self.first_bad_spot / 1e9:.1f} GB" if self.first_bad_spot is not None else ""
            )
            out.append(
                f"FAKE CAPACITY: {self.bad_spots} of {self.spots} markers spread over the drive were lost"
                f"{where}. It doesn't really hold {self.drive_bytes / 1e9:.0f} GB. "
                "Don't trust it with anything."
            )
        elif not self.failing:
            out.append(
                f"Capacity: all {self.spots} markers up to the last megabyte came back intact, "
                f"so the full {self.drive_bytes / 1e9:.0f} GB is real"
            )
        if self.failing:
            out.append(
                f"LOST DATA: {self.bad_bytes / 1e6:.1f} MB of the test data came back wrong. The drive is "
                "failing, or it's fake and smaller than it claims (writing near its end overwrote the "
                "start). Don't trust it with anything."
            )
        speed = self.slowdown[1] if self.slowdown else self.write_mbs
        secs = 8.12e9 / max(speed, 0.01) / 1e6
        took = f"{secs:.0f} seconds" if secs < 90 else f"{secs / 60:.0f} minutes"
        out.append(f"A SteamOS image (8.1 GB) would take about {took} to write")
        return out


def run(
    dev,
    stage=lambda *_: None,
    log=print,
    cancel=None,
    min_bytes: int = 1 << 30,
    max_bytes: int = 4 << 30,
    budget_seconds: float = 90.0,
    spots: int = 128,
    seed: int | None = None,
) -> Report:
    seed = time.time_ns() if seed is None else seed
    bs = dev.block_size
    size = dev.block_count * bs
    spot_area = SPOT * spots
    if size < min_bytes // 4 + spot_area:
        raise OSError("This drive is too small to test")

    ahead = _Ahead(seed)
    try:
        return _run(dev, stage, log, cancel, min_bytes, max_bytes, budget_seconds, spots, seed, ahead)
    finally:
        ahead.close()


def _run(dev, stage, log, cancel, min_bytes, max_bytes, budget_seconds, spots, seed, ahead) -> Report:
    from .rawwrite import Cancelled

    bs = dev.block_size
    size = dev.block_count * bs
    spot_area = SPOT * spots
    target = min(min_bytes, size - spot_area) // CHUNK * CHUNK // bs * bs  # whole sectors
    segments: list[float] = []
    written = 0
    seg_start = time.monotonic()
    seg_bytes = 0
    t0 = time.monotonic()
    log("Writing test data from the start of the drive")
    while written < target:
        if cancelled(cancel):
            raise Cancelled()
        n = min(CHUNK, target - written) // bs * bs
        data = ahead.take(n, written)
        if n and written + n < target:
            ahead.plan(min(CHUNK, target - written - n) // bs * bs, written + n)
        dev.write(written // bs, data)
        written += n
        seg_bytes += n
        if seg_bytes >= SEGMENT or written >= target:
            secs = max(time.monotonic() - seg_start, 1e-6)
            mbs = seg_bytes / 1e6 / secs
            segments.append(mbs)
            log(f"{written / 1e9:.2f} GB written, {mbs:.1f} MB/s")
            if len(segments) == 1:
                target = int(mbs * 1e6 * budget_seconds)
                target = max(min_bytes, min(max_bytes, target))
                target = min(target, size - spot_area) // CHUNK * CHUNK // bs * bs
                target = max(target, written)
            seg_start = time.monotonic()
            seg_bytes = 0
        stage("write", written, target + spot_area)

    start = written
    spot = SPOT // bs * bs  # a marker is whole sectors
    span = size - start - SPOT
    positions = sorted({(start + span * i // max(1, spots - 1)) // bs * bs for i in range(spots)})
    for i, p in enumerate(positions):
        if cancelled(cancel):
            raise Cancelled()
        dev.write(p // bs, pattern(spot, p, ~seed))
        stage("write", written + (i + 1) * SPOT, target + spot_area)
    dev.sync()
    write_mbs = (written + len(positions) * SPOT) / 1e6 / max(time.monotonic() - t0, 1e-6)

    log("Reading it back")
    bad = 0
    read = 0
    r0 = time.monotonic()
    total = written + len(positions) * SPOT
    while read < written:
        if cancelled(cancel):
            raise Cancelled()
        n = min(CHUNK, written - read) // bs * bs
        want = ahead.take(n, read)
        if read + n < written:
            ahead.plan(min(CHUNK, written - read - n) // bs * bs, read + n)
        got = dev.read(read // bs, n // bs)
        if got != want:
            bad += _wrong_bytes(got, want)
        read += n
        stage("check", read, total)
    read_mbs = written / 1e6 / max(time.monotonic() - r0, 1e-6)
    bad_spots = 0
    first_bad = None
    for i, p in enumerate(positions):
        if cancelled(cancel):
            raise Cancelled()
        if dev.read(p // bs, spot // bs) != pattern(spot, p, ~seed):
            bad_spots += 1
            if first_bad is None:
                first_bad = p
        stage("check", written + (i + 1) * SPOT, total)
    return Report(size, written, segments, write_mbs, read_mbs, bad, len(positions), bad_spots, first_bad)


def compare(
    image, dev, stage=lambda *_: None, log=print, cancel=None, raw_size: int = 0, counted=None
) -> int | None:
    """Compares a drive with an image stream without writing. None if they match, else the first
    differing byte offset."""
    from .rawwrite import Cancelled

    bs = dev.block_size
    at = 0
    t0 = time.monotonic()
    while True:
        if cancelled(cancel):
            raise Cancelled()
        chunk = image.read(CHUNK)
        if not chunk:
            break
        if at + len(chunk) > dev.block_count * bs:
            log("The image is bigger than the drive")
            return dev.block_count * bs
        padded = (len(chunk) + bs - 1) // bs
        disk = dev.read(at // bs, padded)[: len(chunk)]
        if disk != chunk:
            i = _first_difference(chunk, disk)
            log(f"First difference at byte {at + i}")
            return at + i
        at += len(chunk)
        stage("check", counted.count if counted is not None else at, raw_size or at)
    log(
        f"All {at / 1e9:.2f} GB match the image "
        f"(read at {at / 1e6 / max(time.monotonic() - t0, 1e-6):.1f} MB/s)"
    )
    return None
