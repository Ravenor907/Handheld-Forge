package io.github.ravenor907.handheldforge.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Unpacks a .bz2 file on several CPU cores at once, giving exactly the bytes a normal bzip2
 * decoder would (the same idea as lbzip2 and pbzip2).
 *
 * A bzip2 file is a series of blocks (up to 900 KB of input each) that don't depend on one
 * another. Each block starts with a 48-bit marker (0x314159265359) and the stream ends with
 * another (0x177245385090) followed by a checksum of all the block checksums. Blocks aren't
 * byte aligned, so the marker is searched for at every bit position.
 *
 * Each block is cut out, re-aligned and wrapped as a tiny one-block .bz2 of its own, unpacked by
 * the regular decoder on a worker thread (which checks the block's own CRC), and handed out in
 * order. The stream checksum is checked too, so a missing or duplicated block is caught.
 *
 * The marker can, very rarely, also appear by chance inside a block. That block then fails to
 * unpack on its own; it's joined with the piece after it and unpacked again, which always works
 * for a real file. A damaged file still fails, as it should.
 */
class ParallelBzip2InputStream(
    private val raw: InputStream,
    // Each worker needs about 6 MB for the decoder plus the pieces it holds; stay well inside the heap.
    threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        .coerceAtMost(maxOf(1, (Runtime.getRuntime().maxMemory() / (24L shl 20)).toInt())),
    /** Unpacked bytes allowed to wait in memory for their turn (the block being read is exempt). */
    private val memoryLimit: Long = minOf(256L shl 20, Runtime.getRuntime().maxMemory() / 4),
    /** For tests: extra bit positions to treat as block starts, as if the marker turned up by chance. */
    private val fakeSplits: Set<Long> = emptySet(),
) : InputStream() {
    private companion object {
        const val BLOCK_MAGIC = 0x314159265359L
        const val END_MAGIC = 0x177245385090L
        const val MASK48 = (1L shl 48) - 1
        const val CHUNK = 1 shl 20
        val BLOCK_TABLE = table(BLOCK_MAGIC)
        val END_TABLE = table(END_MAGIC)

        /** For each byte value: the shifts k (bits) at which it can be the second-to-last byte of [magic]. */
        fun table(magic: Long) = IntArray(256).also { t ->
            for (k in 0..7) t[((magic ushr (8 - k)) and 0xFF).toInt()] = t[((magic ushr (8 - k)) and 0xFF).toInt()] or (1 shl k)
        }
    }

    /** A run of bits from the file: from one marker up to the next. */
    private class Range(val isBlock: Boolean, val bytes: ByteArray, val bitOff: Int, val bitLen: Long) {
        fun bits(from: Long, n: Int): Long {
            var v = 0L
            for (i in 0 until n) {
                val b = bitOff + from + i
                v = (v shl 1) or ((bytes[(b ushr 3).toInt()].toInt() ushr (7 - (b and 7).toInt())) and 1).toLong()
            }
            return v
        }
        /** The CRC after the marker: the block's CRC, or for the end marker the stream's. */
        val crc: Long get() = if (bitLen >= 80) bits(48, 32) else -1

        /** Whether the bytes after an end marker and its CRC begin another stream ("BZh1" to "BZh9"). */
        val headerFollows: Boolean
            get() {
                val at = (bitOff + 80 + 7) / 8
                return at + 4 <= bytes.size && bytes[at] == 'B'.code.toByte() && bytes[at + 1] == 'Z'.code.toByte() &&
                    bytes[at + 2] == 'h'.code.toByte() && bytes[at + 3] in '1'.code.toByte()..'9'.code.toByte()
            }
    }

    private class Piece(val chunks: List<ByteArray>, val lastLen: Int) {
        val size: Long get() = if (chunks.isEmpty()) 0 else (chunks.size - 1).toLong() * CHUNK + lastLen
    }

    private class Item(val range: Range?, val seq: Int, val future: Future<Piece>?, val error: Throwable? = null, val end: Boolean = false)

    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { r -> Thread(r, "bzip2").apply { isDaemon = true } }
    private val items = ArrayBlockingQueue<Item>(threads * 2 + 2)
    @Volatile private var closed = false

    // 1 MB buffers whose bytes have been handed out, kept for the next blocks instead of allocating new ones.
    private val spare = ArrayBlockingQueue<ByteArray>(threads * 2 + 2)

    // Memory gate for unpacked pieces waiting their turn.
    private val gate = Object()
    private var used = 0L
    private var bypassUpTo = 0

    private val reader = Thread({ readAll() }, "bzip2-reader").apply {
        isDaemon = true
        start()
    }

    // ---------------------------------------------------------------- reader: find blocks

    private fun readAll() {
        var seq = 0
        try {
            var acc = ByteArray(4 * CHUNK)
            var accBase = 0L // absolute byte index of acc[0]
            var accLen = 0
            var w = 0L
            var j = 0L // absolute index of the next byte to scan
            var curStart = -1L
            var curBlock = false
            var sawAny = false
            var headerChecked = false
            val buf = ByteArray(CHUNK)

            fun emit(isBlock: Boolean, s: Long, e: Long) {
                val from = (s ushr 3) - accBase
                val to = ((e + 7) ushr 3) - accBase
                val r = Range(isBlock, acc.copyOfRange(from.toInt(), to.toInt()), (s and 7).toInt(), e - s)
                if (isBlock) {
                    val mySeq = seq++
                    put(Item(r, mySeq, pool.submit<Piece> { decode(listOf(r), mySeq) }))
                } else {
                    put(Item(r, -1, null))
                }
            }

            while (!closed) {
                val n = raw.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (accLen + n > acc.size) acc = acc.copyOf(maxOf(acc.size * 2, accLen + n))
                System.arraycopy(buf, 0, acc, accLen, n)
                accLen += n
                if (!headerChecked && accLen >= 4) {
                    headerChecked = true
                    if (acc[0] != 'B'.code.toByte() || acc[1] != 'Z'.code.toByte() || acc[2] != 'h'.code.toByte() || acc[3] !in '1'.code.toByte()..'9'.code.toByte()) {
                        throw IOException("not a bzip2 file")
                    }
                }
                while (j < accBase + accLen) {
                    val prev = (w and 0xFF).toInt()
                    w = (w shl 8) or (acc[(j - accBase).toInt()].toLong() and 0xFF)
                    val mb = BLOCK_TABLE[prev]
                    val me = END_TABLE[prev]
                    if ((mb or me) != 0 && j >= 5) {
                        for (k in 0..7) {
                            val bit = 1 shl k
                            if ((mb or me) and bit == 0) continue
                            val v = (w ushr k) and MASK48
                            val isBlock = v == BLOCK_MAGIC && mb and bit != 0
                            val isEnd = v == END_MAGIC && me and bit != 0
                            if (!isBlock && !isEnd) continue
                            val p = j * 8 - 40 - k
                            if (p < 0 || (curStart >= 0 && p - curStart < 80)) continue
                            if (curStart >= 0) {
                                emit(curBlock, curStart, p)
                                // Keep only what the next range needs.
                                val keep = (p ushr 3) - accBase
                                System.arraycopy(acc, keep.toInt(), acc, 0, (accLen - keep).toInt())
                                accLen -= keep.toInt()
                                accBase += keep
                            }
                            curStart = p
                            curBlock = isBlock
                            sawAny = true
                        }
                    }
                    for (fake in fakeSplits) {
                        // Fake markers are checked once their whole range is past (tests only).
                        if (curStart in 0 until fake && fake / 8 == j - 6 && fake - curStart >= 80) {
                            emit(curBlock, curStart, fake)
                            val keep = (fake ushr 3) - accBase
                            System.arraycopy(acc, keep.toInt(), acc, 0, (accLen - keep).toInt())
                            accLen -= keep.toInt()
                            accBase += keep
                            curStart = fake
                            curBlock = true
                        }
                    }
                    j++
                }
                // A real block is at most about 1 MB packed; far more without a marker means a damaged file.
                if (curStart < 0 && j > CHUNK) throw IOException("no bzip2 blocks found")
                if (curStart >= 0 && j - (curStart ushr 3) > 16L * CHUNK) throw IOException("a block is far too long (damaged file)")
            }
            if (closed) return
            if (!sawAny) throw IOException("no bzip2 blocks found")
            emit(curBlock, curStart, (accBase + accLen) * 8)
            put(Item(null, -1, null, end = true))
        } catch (t: Throwable) {
            if (!closed) put(Item(null, -1, null, error = t))
        }
    }

    private fun put(item: Item) {
        while (!closed) {
            try {
                if (items.offer(item, 200, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    // ---------------------------------------------------------------- workers: unpack one piece

    /** Joins [ranges] into a one-block .bz2 and unpacks it. Throws if it isn't a valid block. */
    private fun decode(ranges: List<Range>, seq: Int, gated: Boolean = true): Piece {
        val enc = BitWriter((ranges.sumOf { it.bitLen } / 8 + 32).toInt())
        enc.bytes("BZh9".toByteArray())
        for (r in ranges) enc.copy(r.bytes, r.bitOff.toLong(), r.bitLen)
        enc.bits(END_MAGIC, 48)
        enc.bits(ranges[0].crc, 32)
        val chunks = ArrayList<ByteArray>()
        var counted = 0L
        var last = 0
        try {
            BZip2CompressorInputStream(enc.finish(), false).use { d ->
                while (true) {
                    if (closed) throw IOException("closed")
                    val c = spare.poll() ?: ByteArray(CHUNK)
                    if (gated) waitForMemory(seq)
                    synchronized(gate) { used += CHUNK }
                    counted += CHUNK
                    var n = 0
                    while (n < CHUNK) {
                        val r = d.read(c, n, CHUNK - n)
                        if (r < 0) break
                        n += r
                    }
                    if (n > 0) {
                        chunks.add(c)
                        last = n
                    } else {
                        spare.offer(c)
                    }
                    if (n < CHUNK) break
                }
            }
        } catch (t: Throwable) {
            release(counted)
            throw t
        }
        // Give back the empty tail allocation, if any.
        val size = if (chunks.isEmpty()) 0L else (chunks.size - 1).toLong() * CHUNK + CHUNK
        if (counted > size) release(counted - size)
        return Piece(chunks, last)
    }

    private fun waitForMemory(seq: Int) {
        synchronized(gate) {
            while (!closed && seq > bypassUpTo && used > memoryLimit) gate.wait(200)
        }
    }

    private fun release(bytes: Long) {
        synchronized(gate) {
            used -= bytes
            gate.notifyAll()
        }
    }

    /** Gives back a piece that has been read out (or isn't wanted): its memory allowance and its buffers. */
    private fun release(p: Piece) {
        release(p.chunks.size.toLong() * CHUNK)
        for (c in p.chunks) if (!spare.offer(c)) break
    }

    private fun allow(seq: Int) {
        synchronized(gate) {
            if (seq > bypassUpTo) bypassUpTo = seq
            gate.notifyAll()
        }
    }

    // ---------------------------------------------------------------- consumer: hand out in order

    private var cur: Piece? = null
    private var curChunk = 0
    private var curPos = 0
    private var finished = false
    private var failure: Exception? = null
    private var combined = 0L
    private var blocksInStream = 0
    private var pending: Item? = null

    // The last stream ended and the header of another followed it, with no block or end marker yet.
    private var headerOpen = false

    /** How many pieces had to be joined with the next (a marker that turned up by chance). */
    var joins = 0
        private set

    private fun next(): Item {
        pending?.let { pending = null; return it }
        // Waits in short turns so a close from another thread is noticed.
        while (true) {
            if (closed) throw IOException("closed")
            items.poll(200, TimeUnit.MILLISECONDS)?.let { return it }
        }
    }

    /** Moves to the next unpacked piece; false at the end of the file. */
    private fun advance(): Boolean {
        cur?.let { release(it) }
        cur = null
        while (true) {
            val item = next()
            // A cancel from the source stays a cancel; anything else is a read error.
            item.error?.let { throw it as? IOException ?: it as? CancelledException ?: IOException(it.message ?: it.javaClass.simpleName, it) }
            if (item.end) {
                pool.shutdown()
                if (blocksInStream > 0 || headerOpen) throw IOException("the file ends partway (no end-of-stream marker)")
                return false
            }
            val r = item.range!!
            if (!r.isBlock) {
                // End of a stream: its checksum covers every block in it.
                if (r.crc < 0) throw IOException("the file ends partway")
                if (r.crc != combined) throw IOException("stream checksum mismatch (a block is missing or damaged)")
                combined = 0
                blocksInStream = 0
                headerOpen = r.headerFollows
                continue
            }
            headerOpen = false
            allow(item.seq)
            val piece = try {
                item.future!!.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                retryJoined(item, e.cause ?: e)
            } catch (e: java.util.concurrent.CancellationException) {
                throw IOException("closed")
            }
            combined = ((combined shl 1) or (combined ushr 31)) and 0xFFFFFFFFL xor r.crc
            blocksInStream++
            if (piece.size == 0L) {
                release(piece.chunks.size.toLong() * CHUNK)
                continue
            }
            cur = piece
            curChunk = 0
            curPos = 0
            return true
        }
    }

    /** A piece that didn't unpack alone: join it with the pieces after it (the marker turned up by chance). */
    private fun retryJoined(first: Item, firstError: Throwable): Piece {
        val ranges = arrayListOf(first.range!!)
        repeat(4) {
            val more = next()
            if (more.error != null || more.end || more.range == null) {
                pending = more
                throw IOException("damaged block (${firstError.message})", firstError)
            }
            // Its own result (if it got one) is no longer wanted.
            if (more.future != null) {
                allow(more.seq)
                try {
                    release(more.future.get())
                } catch (_: Exception) {
                }
            }
            ranges.add(more.range)
            try {
                return decode(ranges, first.seq, gated = false).also { joins++ }
            } catch (_: Exception) {
            }
        }
        throw IOException("damaged block (${firstError.message})", firstError)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        failure?.let { throw it }
        if (finished) return -1
        try {
            while (true) {
                val p = cur
                if (p != null && curChunk < p.chunks.size) {
                    val avail = (if (curChunk == p.chunks.size - 1) p.lastLen else CHUNK) - curPos
                    if (avail > 0) {
                        val n = minOf(len, avail)
                        System.arraycopy(p.chunks[curChunk], curPos, b, off, n)
                        curPos += n
                        return n
                    }
                    curChunk++
                    curPos = 0
                    continue
                }
                if (!advance()) {
                    finished = true
                    return -1
                }
            }
        } catch (e: IOException) {
            failure = e
            throw e
        } catch (e: CancelledException) {
            failure = e
            throw e
        } catch (e: InterruptedException) {
            throw IOException("interrupted", e).also { failure = it }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(gate) { gate.notifyAll() }
        reader.interrupt()
        // Blocks that never started would leave a reader waiting on them for good.
        for (r in pool.shutdownNow()) (r as? Future<*>)?.cancel(false)
        items.clear()
        raw.close()
    }

    /** Writes bits MSB first, the way bzip2 lays them out. */
    private class BitWriter(capacity: Int) {
        private var out = ByteArray(maxOf(capacity, 16))
        private var len = 0
        private var acc = 0L
        private var n = 0 // bits waiting in acc

        private fun ensure(extra: Int) {
            if (len + extra > out.size) out = out.copyOf(maxOf(out.size * 2, len + extra))
        }

        fun bits(v: Long, count: Int) {
            var c = count
            while (c > 0) {
                val take = minOf(c, 32)
                c -= take
                acc = (acc shl take) or ((v ushr c) and ((1L shl take) - 1))
                n += take
                ensure(8)
                while (n >= 8) {
                    out[len++] = (acc ushr (n - 8)).toByte()
                    n -= 8
                }
            }
        }

        fun bytes(b: ByteArray) = b.forEach { bits(it.toLong() and 0xFF, 8) }

        /** Appends [bitLen] bits of [src] starting at bit [from]. */
        fun copy(src: ByteArray, from: Long, bitLen: Long) {
            ensure((bitLen / 8 + 16).toInt())
            var pos = from
            var left = bitLen
            val shift = (from and 7).toInt()
            // Whole bytes, 8 bits at a time from any bit offset.
            while (left >= 8) {
                val i = (pos ushr 3).toInt()
                val v = if (shift == 0) {
                    src[i].toInt() and 0xFF
                } else {
                    (((src[i].toInt() and 0xFF) shl shift) or ((src[i + 1].toInt() and 0xFF) ushr (8 - shift))) and 0xFF
                }
                if (n == 0) {
                    out[len++] = v.toByte()
                } else {
                    acc = (acc shl 8) or v.toLong()
                    out[len++] = (acc ushr n).toByte()
                }
                pos += 8
                left -= 8
            }
            while (left > 0) {
                val b = pos
                bits(((src[(b ushr 3).toInt()].toInt() ushr (7 - (b and 7).toInt())) and 1).toLong(), 1)
                pos++
                left--
            }
        }

        /** What was written, as a stream over the buffer itself (no copy). */
        fun finish(): InputStream {
            if (n > 0) {
                ensure(1)
                out[len++] = (acc shl (8 - n)).toByte()
                n = 0
            }
            return Bytes(out, len)
        }
    }

    /**
     * ByteArrayInputStream without its lock: the decoder asks for one byte at a time, and the
     * lock on every one of those calls costs more than the read itself.
     */
    private class Bytes(private val data: ByteArray, private val end: Int) : InputStream() {
        private var at = 0

        override fun read(): Int = if (at < end) data[at++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (at >= end) return -1
            val n = minOf(len, end - at)
            System.arraycopy(data, at, b, off, n)
            at += n
            return n
        }

        override fun available(): Int = end - at
    }
}
