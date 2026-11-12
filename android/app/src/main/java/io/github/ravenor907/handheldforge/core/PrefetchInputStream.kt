package io.github.ravenor907.handheldforge.core

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads [inner] on its own thread, a few chunks ahead of whoever reads this stream. Used so
 * unpacking an image (CPU) and writing it to the USB drive (I/O) happen at the same time
 * instead of taking turns. Errors from [inner] surface on the next read here.
 */
class PrefetchInputStream(
    private val inner: InputStream,
    private val chunk: Int = 4 shl 20,
    depth: Int = 3,
) : InputStream() {
    private class Chunk(val data: ByteArray, val len: Int, val end: Boolean = false, val error: Throwable? = null)

    private val queue = ArrayBlockingQueue<Chunk>(depth)

    // Buffers the reader has finished with, for the worker to fill again instead of allocating new ones.
    private val spare = ArrayBlockingQueue<ByteArray>(depth + 2)
    @Volatile private var closed = false
    private var cur: Chunk? = null
    private var pos = 0
    private var finished = false
    private var failure: Exception? = null

    private val worker = Thread({
        try {
            while (!closed) {
                val b = spare.poll() ?: ByteArray(chunk)
                var n = 0
                while (n < chunk) {
                    val r = inner.read(b, n, chunk - n)
                    if (r < 0) break
                    n += r
                }
                if (n > 0) put(Chunk(b, n))
                if (n < chunk) {
                    put(Chunk(b, 0, end = true))
                    return@Thread
                }
            }
        } catch (t: Throwable) {
            if (!closed) put(Chunk(ByteArray(0), 0, error = t))
        }
    }, "prefetch").apply {
        isDaemon = true
        start()
    }

    private fun put(c: Chunk) {
        while (!closed) {
            try {
                if (queue.offer(c, 200, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        failure?.let { throw it }
        while (true) {
            if (closed) throw IOException("closed")
            if (finished) return -1
            val c = cur
            if (c != null && pos < c.len) {
                val n = minOf(len, c.len - pos)
                System.arraycopy(c.data, pos, b, off, n)
                pos += n
                return n
            }
            if (c != null) {
                spare.offer(c.data)
                cur = null
            }
            // Waits in short turns so a close from another thread is noticed.
            val next = try {
                queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("interrupted")
            }
            // A chunk the worker got in while the stream was being closed isn't handed out.
            if (closed) throw IOException("closed")
            next.error?.let { e ->
                // A cancel from the source stays a cancel; anything else is a read error.
                val io = e as? IOException ?: e as? CancelledException ?: IOException(e.message ?: e.javaClass.simpleName, e)
                failure = io
                throw io
            }
            if (next.end) {
                finished = true
                return -1
            }
            cur = next
            pos = 0
        }
    }

    override fun close() {
        closed = true
        worker.interrupt()
        queue.clear()
        inner.close()
    }
}
