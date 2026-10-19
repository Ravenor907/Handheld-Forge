"""bzip2 unpacked on every CPU core, giving exactly what a normal bzip2 decoder gives (the same
method as the Android app and lbzip2).

A .bz2 file is a series of independent blocks, each starting with the 48-bit marker
0x314159265359 (not byte aligned); a stream ends with 0x177245385090 and a checksum of the block
checksums. Each block is cut out, re-aligned and wrapped as a one-block .bz2 of its own, then
unpacked on a worker thread (Python's bz2 lets go of the GIL while it works, and checks the
block's CRC). Blocks come out in order and the stream checksum is checked, so a missing block is
caught. A marker that turns up by chance inside a block makes that piece fail alone; it's joined
with the next piece and unpacked again.
"""

from __future__ import annotations

import bz2
import io
import os
import queue
import threading
from concurrent.futures import ThreadPoolExecutor

BLOCK_MAGIC = 0x314159265359
END_MAGIC = 0x177245385090
READ = 4 << 20


def _cores() -> list[tuple[int, bytes]]:
    """For each bit offset k (0..7) of a marker within its first byte: (k, the whole bytes it covers)."""
    out = []
    for magic in (BLOCK_MAGIC, END_MAGIC):
        for k in range(8):
            if k == 0:
                core = magic.to_bytes(6, "big")
            else:
                core = (magic << (8 - k)).to_bytes(7, "big")[1:6]
            out.append((k, core))
    return out


_CORES = _cores()


class _Range:
    __slots__ = ("is_block", "data", "bit_off", "bit_len")

    def __init__(self, is_block: bool, data: bytes, bit_off: int, bit_len: int) -> None:
        self.is_block, self.data, self.bit_off, self.bit_len = is_block, data, bit_off, bit_len

    def bits(self, start: int, n: int) -> int:
        """n bits starting at bit `start` of this range."""
        first = (self.bit_off + start) // 8
        last = (self.bit_off + start + n + 7) // 8
        v = int.from_bytes(self.data[first:last], "big")
        drop = last * 8 - (self.bit_off + start + n)
        return (v >> drop) & ((1 << n) - 1)

    @property
    def crc(self) -> int:
        return self.bits(48, 32) if self.bit_len >= 80 else -1


def _stream(ranges: list[_Range]) -> bytes:
    """A one-block .bz2 made of these consecutive bit ranges."""
    v = int.from_bytes(b"BZh9", "big")
    total = 32
    for r in ranges:
        v = (v << r.bit_len) | r.bits(0, r.bit_len)
        total += r.bit_len
    v = (v << 48) | END_MAGIC
    v = (v << 32) | ranges[0].crc
    total += 80
    pad = -total % 8
    return (v << pad).to_bytes((total + pad) // 8, "big")


class ParallelBz2Reader(io.RawIOBase):
    def __init__(self, raw, threads: int | None = None, fake_splits: set[int] | None = None) -> None:
        super().__init__()
        self.raw = raw
        self.threads = threads or max(1, min(os.cpu_count() or 1, 8))
        self.pool = ThreadPoolExecutor(self.threads, thread_name_prefix="bzip2")
        self.items: queue.Queue = queue.Queue(maxsize=self.threads + 2)
        self.fake_splits = sorted(fake_splits or ())
        self.closed_flag = threading.Event()
        self.joins = 0
        self._cur = b""
        self._pos = 0
        self._done = False
        self._combined = 0
        self._blocks = 0
        self._pending = None
        self._reader = threading.Thread(target=self._read_all, daemon=True, name="bzip2-reader")
        self._reader.start()

    # ------------------------------------------------------------ reader: find the blocks

    def _put(self, item) -> None:
        while not self.closed_flag.is_set():
            try:
                self.items.put(item, timeout=0.2)
                return
            except queue.Full:
                continue

    def _emit(self, buf: bytearray, base: int, is_block: bool, s: int, e: int) -> None:
        data = bytes(buf[s // 8 - base : (e + 7) // 8 - base])
        r = _Range(is_block, data, s % 8, e - s)
        fut = self.pool.submit(lambda: bz2.decompress(_stream([r]))) if is_block else None
        self._put(("range", r, fut))

    def _read_all(self) -> None:
        try:
            buf = bytearray()
            base = 0  # absolute byte index of buf[0]
            scanned = 0  # absolute byte index up to which markers have been looked for
            cur = -1  # bit position of the current range's marker
            cur_block = False
            fakes = list(self.fake_splits)
            first = True
            while not self.closed_flag.is_set():
                chunk = self.raw.read(READ)
                eof = not chunk
                if chunk:
                    buf += chunk
                if first and len(buf) >= 4:
                    first = False
                    if buf[:3] != b"BZh" or not (0x31 <= buf[3] <= 0x39):
                        raise OSError("not a bzip2 file")
                # Look for markers whose whole 48 bits are already here.
                end = base + len(buf) - (0 if eof else 8)
                found = []
                lo = max(0, scanned - 8 - base)
                window = bytes(buf[lo : end - base]) if end > base + lo else b""
                for k, core in _CORES:
                    i = window.find(core)
                    while i >= 0:
                        byte = base + lo + i
                        start = byte * 8 if k == 0 else (byte - 1) * 8 + k
                        if start >= 0 and start + 48 <= (base + len(buf)) * 8:
                            found.append(start)
                        i = window.find(core, i + 1)
                for f in fakes:
                    if base * 8 <= f < end * 8:
                        found.append(f)
                scanned = end
                for p in sorted(set(found)):
                    if cur >= 0 and p - cur < 80:
                        continue
                    if p < cur:
                        continue
                    off = p // 8 - base
                    v = int.from_bytes(buf[off : off + 8].ljust(8, b"\0"), "big")
                    sh = 64 - (p % 8) - 48
                    m = (v >> sh) & ((1 << 48) - 1)
                    is_fake = p in fakes
                    if m not in (BLOCK_MAGIC, END_MAGIC) and not is_fake:
                        continue
                    if cur >= 0:
                        self._emit(buf, base, cur_block, cur, p)
                        keep = p // 8 - base
                        del buf[:keep]
                        base += keep
                    cur = p
                    cur_block = is_fake or m == BLOCK_MAGIC
                if cur < 0 and base + len(buf) > (1 << 20):
                    raise OSError("no bzip2 blocks found")
                if cur >= 0 and base + len(buf) - cur // 8 > (64 << 20):
                    raise OSError("a block is far too long (damaged file)")
                if eof:
                    break
            if self.closed_flag.is_set():
                return
            if cur < 0:
                raise OSError("no bzip2 blocks found")
            self._emit(buf, base, cur_block, cur, (base + len(buf)) * 8)
            self._put(("end", None, None))
        except BaseException as e:  # noqa: BLE001 - handed to the reading side
            if not self.closed_flag.is_set():
                self._put(("error", e, None))

    # ------------------------------------------------------------ consumer: hand out in order

    def _next(self):
        if self._pending is not None:
            item, self._pending = self._pending, None
            return item
        return self.items.get()

    def _advance(self) -> bool:
        while True:
            kind, r, fut = self._next()
            if kind == "error":
                raise r if isinstance(r, OSError) else OSError(str(r))
            if kind == "end":
                self.pool.shutdown(wait=False)
                if self._blocks:
                    raise OSError("the file ends partway (no end-of-stream marker)")
                return False
            if not r.is_block:
                if r.crc < 0:
                    raise OSError("the file ends partway")
                if r.crc != self._combined:
                    raise OSError("stream checksum mismatch (a block is missing or damaged)")
                self._combined = 0
                self._blocks = 0
                continue
            try:
                data = fut.result()
            except Exception as e:  # noqa: BLE001
                data = self._retry(r, e)
            self._combined = (((self._combined << 1) | (self._combined >> 31)) & 0xFFFFFFFF) ^ r.crc
            self._blocks += 1
            if data:
                self._cur, self._pos = data, 0
                return True

    def _retry(self, first: _Range, err: Exception) -> bytes:
        ranges = [first]
        for _ in range(4):
            item = self._next()
            kind, r, fut = item
            if kind != "range":
                self._pending = item
                break
            if fut is not None:
                try:
                    fut.result()
                except Exception:  # noqa: BLE001, S110 - its own result isn't wanted
                    pass
            ranges.append(r)
            try:
                out = bz2.decompress(_stream(ranges))
                self.joins += 1
                return out
            except Exception:  # noqa: BLE001, S112
                continue
        raise OSError(f"damaged block ({err})")

    def readable(self) -> bool:
        return True

    def readinto(self, b) -> int:
        if self._done:
            return 0
        while self._pos >= len(self._cur):
            if not self._advance():
                self._done = True
                return 0
        n = min(len(b), len(self._cur) - self._pos)
        b[:n] = self._cur[self._pos : self._pos + n]
        self._pos += n
        return n

    def close(self) -> None:
        if not self.closed:
            self.closed_flag.set()
            self.pool.shutdown(wait=False, cancel_futures=True)
            try:
                while True:
                    self.items.get_nowait()
            except queue.Empty:
                pass
        super().close()
