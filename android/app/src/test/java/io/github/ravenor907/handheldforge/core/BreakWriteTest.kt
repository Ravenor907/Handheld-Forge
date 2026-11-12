package io.github.ravenor907.handheldforge.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Arrays
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Hostile inputs, faulty drives, cancels and odd servers thrown at the write, decode and
 * download code. Tests that pass are regression checks; a failing test names the fault it found.
 */
class BreakWriteTest {
    private companion object {
        const val CHUNK = ImageWriter.CHUNK
        val HELPERS = setOf("image-io", "prefetch", "bzip2", "bzip2-reader")
        val packed = HashMap<String, ByteArray>()
        val images = HashMap<Int, ByteArray>()
        val keys: KeyPair by lazy {
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun hex(b: ByteArray, len: Int = b.size) =
        MessageDigest.getInstance("SHA-256").also { it.update(b, 0, len) }.digest().toHex()

    private inline fun <reified T : Throwable> expect(what: String, block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("$what: expected ${T::class.simpleName} but got $e", e)
        }
        throw AssertionError("$what: expected ${T::class.simpleName} but nothing was thrown")
    }

    private fun roundUp(n: Long, to: Int) = (n + to - 1) / to * to

    /** Something like a disk image: random runs, zero runs and numbered text lines. */
    private fun imageLike(size: Int, seed: Int = 7): ByteArray {
        val r = java.util.Random(seed.toLong())
        val out = ByteArray(size)
        var i = 0
        var line = 0
        while (i < size) {
            val n = minOf(size - i, 1 + r.nextInt(120_000))
            when (r.nextInt(3)) {
                0 -> {
                    val t = ByteArray(n)
                    r.nextBytes(t)
                    System.arraycopy(t, 0, out, i, n)
                }
                1 -> Unit
                else -> {
                    var k = 0
                    while (k < n) {
                        val t = "sector ${line++} of the image ${r.nextInt()}\n".toByteArray()
                        val m = minOf(t.size, n - k)
                        System.arraycopy(t, 0, out, i + k, m)
                        k += m
                    }
                }
            }
            i += n
        }
        return out
    }

    private fun noise(size: Int) = ByteArray(size).also { java.util.Random(size.toLong()).nextBytes(it) }

    private fun image(size: Int): ByteArray = synchronized(images) { images.getOrPut(size) { imageLike(size, size % 1000 + 1) } }

    private fun gz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o ->
        object : java.util.zip.GZIPOutputStream(o) {
            init {
                def.setLevel(1)
            }
        }.use { it.write(b) }
    }.toByteArray()

    private fun xz(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o ->
        org.tukaani.xz.XZOutputStream(o, org.tukaani.xz.LZMA2Options(0)).use { it.write(b) }
    }.toByteArray()

    private fun zip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o ->
        java.util.zip.ZipOutputStream(o).use { z ->
            z.setLevel(1)
            z.putNextEntry(java.util.zip.ZipEntry("image.img"))
            z.write(b)
            z.closeEntry()
        }
    }.toByteArray()

    private fun bz2(b: ByteArray, level: Int = 1): ByteArray = ByteArrayOutputStream().also { o ->
        BZip2CompressorOutputStream(o, level).use { it.write(b) }
    }.toByteArray()

    /** Three bzip2 streams back to back (levels 1, 9, 1), with an empty stream in between. */
    private fun bz2Multi(b: ByteArray): ByteArray {
        val a = b.size / 3
        val c = b.size * 2 / 3
        return bz2(b.copyOfRange(0, a), 1) + bz2(ByteArray(0), 5) + bz2(b.copyOfRange(a, c), 9) + bz2(b.copyOfRange(c, b.size), 1)
    }

    private class Form(val name: String, val kind: ImageKind?, val pack: (ByteArray) -> ByteArray) {
        val decoder: (InputStream) -> InputStream get() = kind?.let { ImageKind.decoder(it) } ?: { it }
    }

    private val raw = Form("raw", null) { it }
    private val gzip = Form("gzip", ImageKind.GZIP, ::gz)
    private val xzForm = Form("xz", ImageKind.XZ, ::xz)
    private val zipForm = Form("zip", ImageKind.ZIP, ::zip)
    private val bz1 = Form("bzip2 -1", ImageKind.BZIP2) { bz2(it, 1) }
    private val bz9 = Form("bzip2 -9", ImageKind.BZIP2) { bz2(it, 9) }
    private val bzMulti = Form("bzip2 multi stream", ImageKind.BZIP2, ::bz2Multi)

    private fun packedFor(f: Form, size: Int): ByteArray =
        if (f.kind == null) image(size) else synchronized(packed) { packed.getOrPut("${f.name}/$size") { f.pack(image(size)) } }

    /** A source that hands out its bytes in short reads, like a pipe or a network stream. */
    private class Trickle(
        private val data: ByteArray,
        seed: Long,
        private val max: Int,
        private val reportsAvailable: Boolean = false,
    ) : InputStream() {
        private val r = java.util.Random(seed)
        private var pos = 0

        @Volatile var closed = false

        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= data.size) return -1
            val want = if (max == Int.MAX_VALUE) len else if (max == 1) 1 else 1 + r.nextInt(max)
            val n = minOf(len, data.size - pos, want)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }

        override fun available(): Int = if (reportsAvailable) data.size - pos else 0

        override fun close() {
            closed = true
        }
    }

    /** A drive held in memory, pre-filled with 0xAA so untouched sectors show, with fault hooks. */
    private class MemDisk(size: Long, override val blockSize: Int = 512) : BlockDevice {
        override val blockCount = size / blockSize
        override val name = "mem disk"
        val bytes = ByteArray(size.toInt()).also { Arrays.fill(it, 0xAA.toByte()) }
        var writes = 0
        var reads = 0
        var syncs = 0

        /** Called before write number n (0 is the clearing of the drive's end). */
        var onWrite: (Int) -> Unit = {}

        /** Called after read number n with the buffer and the bytes read; false leaves the buffer untouched. */
        var beforeRead: (Int) -> Boolean = { true }
        var afterRead: (Int, ByteArray, Int) -> Unit = { _, _, _ -> }

        override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
            require(lba >= 0 && lba + blocks <= blockCount) { "read past the end: $lba+$blocks of $blockCount" }
            val n = reads++
            if (!beforeRead(n)) return
            System.arraycopy(bytes, (lba * blockSize).toInt(), buf, offset, blocks * blockSize)
            afterRead(n, buf, blocks * blockSize)
        }

        override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
            onWrite(writes++)
            require(lba >= 0 && lba + blocks <= blockCount) { "write past the end: $lba+$blocks of $blockCount" }
            System.arraycopy(buf, offset, bytes, (lba * blockSize).toInt(), blocks * blockSize)
        }

        override fun sync() {
            syncs++
        }
    }

    /** The drive holds [data] from byte 0, zero padding to the sector, a cleared end, and nothing else was touched. */
    private fun checkDisk(label: String, dev: MemDisk, data: ByteArray) {
        val mm = Arrays.mismatch(dev.bytes, 0, data.size, data, 0, data.size)
        if (mm >= 0) fail("$label: drive differs from the image at byte $mm of ${data.size}")
        val total = dev.bytes.size
        val padded = roundUp(data.size.toLong(), dev.blockSize).toInt()
        val tail = maxOf(padded, total - minOf(CHUNK, total))
        for (i in data.size until padded) if (dev.bytes[i] != 0.toByte()) fail("$label: sector padding at byte $i is not zero")
        for (i in padded until tail) if (dev.bytes[i] != 0xAA.toByte()) fail("$label: byte $i past the image was overwritten")
        for (i in tail until total) if (dev.bytes[i] != 0.toByte()) fail("$label: the drive's end at byte $i was not cleared")
    }

    private fun liveHelpers() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name in HELPERS }

    private fun assertNoHelperThreads(where: String) {
        val until = System.nanoTime() + 3_000_000_000L
        var left = liveHelpers()
        while (left.isNotEmpty() && System.nanoTime() < until) {
            Thread.sleep(10)
            left = liveHelpers()
        }
        if (left.isNotEmpty()) {
            fail("$where: helper threads still alive after 3 s: " + left.joinToString { "${it.name} ${it.state} at ${it.stackTrace.firstOrNull()}" })
        }
    }

    private fun devSizes(size: Int, bs: Int): Triple<Long, Long, Long> {
        val fit = maxOf(roundUp(size.toLong(), bs), bs.toLong())
        return Triple(fit, fit + CHUNK + 8L * bs, fit + bs)
    }

    /** One full write checked against the plain reference: contents, length, hash, stages, cleanup. */
    private fun checkWrite(
        label: String,
        data: ByteArray,
        input: ByteArray,
        decoder: (InputStream) -> InputStream,
        maxRead: Int,
        devSize: Long,
        bs: Int = 512,
        seed: Long = 1,
        tune: (MemDisk) -> Unit = {},
    ) {
        val dev = MemDisk(devSize, bs).also(tune)
        val opened = ArrayList<Trickle>()
        val logs = ArrayList<String>()
        val bad = ArrayList<String>()
        val got = ImageWriter.writeStream(
            { Trickle(input, seed, maxRead, reportsAvailable = maxRead == Int.MAX_VALUE).also { opened.add(it) } },
            input.size.toLong(), decoder, dev, hex(data),
            { s, d, t -> if (d < 0 || d > t) bad.add("$s $d/$t") },
            { logs.add(it) }, { false },
        )
        assertEquals("$label: returned length", data.size.toLong(), got)
        checkDisk(label, dev, data)
        assertTrue("$label: no read-back confirmation in the log: $logs", logs.any { it.startsWith("Every byte on the drive matches") })
        assertEquals("$label: sync calls", 1, dev.syncs)
        assertTrue("$label: progress out of range: $bad", bad.isEmpty())
        assertTrue("$label: the source was opened ${opened.size} times and closed=${opened.map { it.closed }}", opened.size == 1 && opened[0].closed)
    }

    private fun differential(f: Form, small: List<Int>, big: List<Int>) {
        for (size in small + big) {
            val data = image(size)
            val input = packedFor(f, size)
            val (fit, roomy, snug) = devSizes(size, 512)
            checkWrite("${f.name} $size bytes, whole reads, exact drive", data, input, f.decoder, Int.MAX_VALUE, fit)
            checkWrite("${f.name} $size bytes, random short reads, roomy drive", data, input, f.decoder, 70_000, roomy, seed = size.toLong())
            if (size in small) {
                checkWrite("${f.name} $size bytes, whole reads, drive one sector bigger", data, input, f.decoder, Int.MAX_VALUE, snug)
                checkWrite("${f.name} $size bytes, 1 byte reads", data, input, f.decoder, 1, roomy)
            }
        }
        assertNoHelperThreads("after ${f.name} writes")
    }

    private val smallSizes = listOf(0, 1, 511, 512, 513, 65_537)

    // ------------------------------------------------------------------ 1. differential

    @Test(timeout = 120_000)
    fun rawImagesOfEveryBoundarySizeAreWrittenExactly() {
        val sizes = listOf(
            0, 1, 511, 512, 513, 4095, 4096, 4097, 1_234_567, CHUNK - 513, CHUNK - 512, CHUNK - 1, CHUNK, CHUNK + 1,
            CHUNK + 511, CHUNK + 512, 7_777_777, 2 * CHUNK - 1, 2 * CHUNK, 2 * CHUNK + 1, 2 * CHUNK + 513, 3 * CHUNK,
        )
        for (size in sizes) {
            val data = image(size)
            val (fit, roomy, snug) = devSizes(size, 512)
            checkWrite("raw $size bytes, whole reads, exact drive", data, data, { it }, Int.MAX_VALUE, fit)
            checkWrite("raw $size bytes, whole reads, drive one sector bigger", data, data, { it }, Int.MAX_VALUE, snug)
            checkWrite("raw $size bytes, random short reads", data, data, { it }, 70_000, roomy, seed = size.toLong())
            checkWrite("raw $size bytes, random short reads, exact drive", data, data, { it }, 900_000, fit, seed = size + 1L)
            if (size <= 4097 || size == CHUNK + 1) checkWrite("raw $size bytes, 1 byte reads", data, data, { it }, 1, roomy)
            val (fit4k, roomy4k, _) = devSizes(size, 4096)
            checkWrite("raw $size bytes, 4 KB sectors", data, data, { it }, 300_000, if (size % 2 == 0) fit4k else roomy4k, bs = 4096)
        }
        assertNoHelperThreads("after raw writes")
    }

    @Test(timeout = 60_000)
    fun rawFileWriteMatchesTheFileAndChecksItsHash() {
        for (size in listOf(0, 1, 513, CHUNK, CHUNK + 1, 2 * CHUNK + 513)) {
            val data = image(size)
            val file = Files.createTempFile("brk", ".iso").toFile().apply { writeBytes(data) }
            try {
                val dev = MemDisk(devSizes(size, 512).second)
                ImageWriter.write(file, dev, hex(data), { _, _, _ -> }, {}, { false })
                checkDisk("file of $size bytes", dev, data)
                val wrong = expect<WriteException>("wrong expected hash, $size bytes") {
                    ImageWriter.write(file, MemDisk(devSizes(size, 512).second), hex(data + 1), { _, _, _ -> }, {}, { false })
                }
                assertEquals("The image changed while it was being written. Try again.", wrong.message)
            } finally {
                file.delete()
            }
        }
        assertNoHelperThreads("after file writes")
    }

    @Test(timeout = 60_000)
    fun gzipImagesAreWrittenExactly() = differential(gzip, smallSizes, listOf(CHUNK - 1, CHUNK, CHUNK + 1, 2 * CHUNK + 513))

    @Test(timeout = 60_000)
    fun zipImagesAreWrittenExactly() = differential(zipForm, smallSizes, listOf(CHUNK - 1, CHUNK, CHUNK + 1, 2 * CHUNK + 513))

    @Test(timeout = 90_000)
    fun xzImagesAreWrittenExactly() = differential(xzForm, smallSizes, listOf(CHUNK - 1, CHUNK, CHUNK + 1, 2 * CHUNK + 513))

    @Test(timeout = 120_000)
    fun bzip2TinyBlockImagesAreWrittenExactly() = differential(bz1, smallSizes, listOf(CHUNK - 1, CHUNK, CHUNK + 1, 2 * CHUNK + 513))

    @Test(timeout = 90_000)
    fun bzip2BigBlockImagesAreWrittenExactly() = differential(bz9, smallSizes, listOf(CHUNK + 1, 2 * CHUNK + 513))

    @Test(timeout = 90_000)
    fun bzip2MultiStreamImagesAreWrittenExactly() = differential(bzMulti, smallSizes, listOf(CHUNK, 2 * CHUNK + 513))

    @Test(timeout = 30_000)
    fun xzOfTwoStreamsIsWrittenWhole() {
        val data = image(1_234_567)
        val input = xz(data.copyOfRange(0, 500_000)) + xz(data.copyOfRange(500_000, data.size))
        checkWrite("xz two streams, whole reads", data, input, xzForm.decoder, Int.MAX_VALUE, devSizes(data.size, 512).second)
        checkWrite("xz two streams, 1 byte reads", data, input, xzForm.decoder, 1, devSizes(data.size, 512).second)
    }

    @Test(timeout = 30_000)
    fun gzipOfTwoMembersIsWrittenWholeFromAnInMemorySource() {
        val data = image(1_234_567)
        val input = gz(data.copyOfRange(0, 500_000)) + gz(data.copyOfRange(500_000, data.size))
        checkWrite("gzip two members, whole reads", data, input, gzip.decoder, Int.MAX_VALUE, devSizes(data.size, 512).second)
    }

    /**
     * A .gz made of two members (pigz, or two files joined) read from a source that can't say how
     * much is available (a network stream or a pipe). The second member must not be dropped.
     */
    @Test(timeout = 30_000)
    fun gzipOfTwoMembersFromAStreamWithoutAvailableIsNotCutShort() {
        val data = image(1_234_567)
        val input = gz(data.copyOfRange(0, 500_000)) + gz(data.copyOfRange(500_000, data.size))
        for (maxRead in listOf(1, 70_000)) {
            val dev = MemDisk(devSizes(data.size, 512).second)
            val got = try {
                ImageWriter.writeStream({ Trickle(input, 3, maxRead) }, input.size.toLong(), gzip.decoder, dev, null, { _, _, _ -> }, {}, { false })
            } catch (e: CorruptImageException) {
                continue // refusing is acceptable; a silent short image is not
            }
            assertEquals(
                "two-member gzip read in pieces of up to $maxRead bytes: the write reported success but only $got of " +
                    "${data.size} bytes reached the drive (everything after the first member was dropped)",
                data.size.toLong(), got,
            )
        }
    }

    // ------------------------------------------------------------------ 2. device faults

    @Test(timeout = 60_000)
    fun aWriteThatThrowsOnAnyCallStopsTheJobWithThatError() {
        val size = 2 * CHUNK + 513
        for (f in listOf(raw, gzip, bz1)) {
            val input = packedFor(f, size)
            for (n in 0..3) {
                val dev = MemDisk(devSizes(size, 512).second)
                val boom = IOException("write $n failed")
                dev.onWrite = { if (it == n) throw boom }
                val t0 = System.nanoTime()
                val e = expect<IOException>("${f.name}, write call $n throws") {
                    ImageWriter.writeStream({ ByteArrayInputStream(input) }, input.size.toLong(), f.decoder, dev, null, { _, _, _ -> }, {}, { false })
                }
                assertTrue("${f.name}, write call $n: got $e instead of the drive's own error", e === boom)
                assertTrue("${f.name}, write call $n: took too long", System.nanoTime() - t0 < 5_000_000_000L)
                assertNoHelperThreads("${f.name}, write call $n throws")
            }
        }
    }

    @Test(timeout = 60_000)
    fun aReadThatThrowsDuringTheReadBackStopsTheJobWithThatError() {
        val size = 2 * CHUNK + 513
        val data = image(size)
        for (n in 0..2) {
            val dev = MemDisk(devSizes(size, 512).second)
            val boom = IOException("read $n failed")
            dev.beforeRead = { if (it == n) throw boom else true }
            val e = expect<IOException>("read call $n throws") {
                ImageWriter.writeStream({ ByteArrayInputStream(data) }, size.toLong(), { it }, dev, hex(data), { _, _, _ -> }, {}, { false })
            }
            assertTrue("read call $n: got $e", e === boom)
            assertNoHelperThreads("read call $n throws")
        }
    }

    @Test(timeout = 60_000)
    fun aSyncThatThrowsStopsTheJob() {
        val data = image(CHUNK + 1)
        val boom = IOException("sync failed")
        val dev = object : BlockDevice {
            val inner = MemDisk(devSizes(data.size, 512).first)
            override val blockSize get() = inner.blockSize
            override val blockCount get() = inner.blockCount
            override val name get() = inner.name
            override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) = inner.read(lba, blocks, buf, offset)
            override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) = inner.write(lba, blocks, buf, offset)
            override fun sync() = throw boom
        }
        val e = expect<IOException>("sync throws") {
            ImageWriter.writeStream({ ByteArrayInputStream(data) }, data.size.toLong(), { it }, dev, hex(data), { _, _, _ -> }, {}, { false })
        }
        assertTrue(e === boom)
        assertNoHelperThreads("sync throws")
    }

    @Test(timeout = 60_000)
    fun wrongBytesReadBackInAnyChunkAreCaught() {
        val size = 2 * CHUNK + 513
        val data = image(size)
        // (read call, offset inside that read) of the flipped byte: first, middle and last byte of each chunk.
        val spots = listOf(0 to 0, 0 to CHUNK - 1, 1 to 0, 1 to 2_000_000, 1 to CHUNK - 1, 2 to 0, 2 to 512)
        for (f in listOf(raw, gzip)) {
            val input = packedFor(f, size)
            for ((call, at) in spots) {
                val dev = MemDisk(devSizes(size, 512).second)
                dev.afterRead = { n, buf, _ -> if (n == call) buf[at] = (buf[at].toInt() xor 0x10).toByte() }
                val e = expect<WriteException>("${f.name}: byte $at of read $call comes back wrong") {
                    ImageWriter.writeStream({ ByteArrayInputStream(input) }, input.size.toLong(), f.decoder, dev, hex(data), { _, _, _ -> }, {}, { false })
                }
                assertEquals("The drive didn't store the image correctly. It may be failing or counterfeit. Try another drive.", e.message)
            }
        }
        // A wrong byte in the last sector's padding is past the image and doesn't count.
        val dev = MemDisk(devSizes(size, 512).second)
        dev.afterRead = { n, buf, _ -> if (n == 2) buf[1000] = 1 }
        ImageWriter.writeStream({ ByteArrayInputStream(data) }, size.toLong(), { it }, dev, hex(data), { _, _, _ -> }, {}, { false })
        assertNoHelperThreads("after wrong read-backs")
    }

    @Test(timeout = 60_000)
    fun aDriveThatStoresOneChunkWrongIsCaught() {
        val size = 3 * CHUNK + 5
        val data = image(size)
        for (n in 1..4) {
            val dev = MemDisk(devSizes(size, 512).second)
            // The drive accepts write n but keeps something else.
            val real = dev.bytes
            dev.onWrite = { if (it == n + 1) real[(n - 1) * CHUNK + 77] = (real[(n - 1) * CHUNK + 77].toInt() xor 1).toByte() }
            if (n == 4) dev.onWrite = { }
            if (n == 4) {
                // Nothing more is written after the last chunk: damage it from the sync call instead.
                val wrapped = object : BlockDevice by dev {
                    override fun sync() {
                        real[3 * CHUNK + 4] = (real[3 * CHUNK + 4].toInt() xor 1).toByte()
                    }
                }
                expect<WriteException>("last chunk stored wrong") {
                    ImageWriter.writeStream({ ByteArrayInputStream(data) }, size.toLong(), { it }, wrapped, hex(data), { _, _, _ -> }, {}, { false })
                }
            } else {
                expect<WriteException>("chunk $n stored wrong") {
                    ImageWriter.writeStream({ ByteArrayInputStream(data) }, size.toLong(), { it }, dev, hex(data), { _, _, _ -> }, {}, { false })
                }
            }
        }
    }

    /**
     * The read-back reuses the two buffers that held the last chunks written. A drive whose reads
     * return without delivering data (BulkOnly.command accepts a short data phase with a good
     * status) must not pass because the buffers still hold the image.
     */
    @Test(timeout = 60_000)
    fun aDriveWhoseReadsDeliverNothingMustNotPassTheReadBack() {
        val passed = ArrayList<Int>()
        for (size in listOf(CHUNK / 2, CHUNK, CHUNK + 1, 2 * CHUNK - 512, 2 * CHUNK, 3 * CHUNK)) {
            val data = image(size)
            val dev = MemDisk(devSizes(size, 512).second)
            dev.beforeRead = { false }
            // The drive also stored nothing of the image, so only the read-back can notice.
            dev.onWrite = { if (it > 0) throw SkipWrite }
            val quiet = object : BlockDevice by dev {
                override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
                    try {
                        dev.write(lba, blocks, buf, offset)
                    } catch (_: SkipWrite) {
                    }
                }
            }
            try {
                ImageWriter.writeStream({ ByteArrayInputStream(data) }, size.toLong(), { it }, quiet, hex(data), { _, _, _ -> }, {}, { false })
                passed.add(size)
            } catch (_: WriteException) {
            }
        }
        assertTrue(
            "images of $passed bytes were reported as verified on a drive that stored nothing and whose reads returned no data: " +
                "the read-back hashed what was left in the write buffers",
            passed.isEmpty(),
        )
    }

    private object SkipWrite : RuntimeException() {
        private fun readResolve(): Any = SkipWrite
    }

    @Test(timeout = 60_000)
    fun aDriveOneSectorTooSmallIsRefused() {
        for (size in listOf(513, 1 shl 20, CHUNK, CHUNK + 512, 2 * CHUNK + 513)) {
            val data = image(size)
            val need = roundUp(size.toLong(), 512)
            for (f in listOf(raw, gzip)) {
                val input = packedFor(f, size)
                val e = expect<WriteException>("${f.name} image of $size bytes on a drive of ${need - 512}") {
                    ImageWriter.writeStream({ ByteArrayInputStream(input) }, input.size.toLong(), f.decoder, MemDisk(need - 512), null, { _, _, _ -> }, {}, { false })
                }
                assertEquals("This drive is too small for the image.", e.message)
                // And one that fits exactly is fine.
                checkWrite("${f.name} $size exact", data, input, f.decoder, Int.MAX_VALUE, need)
            }
            val file = Files.createTempFile("brk", ".iso").toFile().apply { writeBytes(data) }
            val small = MemDisk(need - 512)
            expect<WriteException>("file of $size bytes on a drive of ${need - 512}") {
                ImageWriter.write(file, small, hex(data), { _, _, _ -> }, {}, { false })
            }
            assertEquals("a drive refused up front must not be touched", 0, small.writes)
            file.delete()
        }
        assertNoHelperThreads("after too-small drives")
    }

    @Test(timeout = 30_000)
    fun compareFindsTheFirstDifferentByte() {
        val size = CHUNK + 1000
        val data = image(size)
        val input = packedFor(gzip, size)
        val dev = MemDisk(devSizes(size, 512).second)
        ImageWriter.writeStream({ ByteArrayInputStream(input) }, input.size.toLong(), gzip.decoder, dev, null, { _, _, _ -> }, {}, { false })
        val same = ImageWriter.compare({ Trickle(input, 1, 5000) }, input.size.toLong(), gzip.decoder, dev, { _, _, _ -> }, {}, { false })
        assertEquals(null, same)
        for (at in listOf(0, CHUNK - 1, CHUNK, size - 1)) {
            dev.bytes[at] = (dev.bytes[at].toInt() xor 4).toByte()
            val diff = ImageWriter.compare({ Trickle(input, 1, 5000) }, input.size.toLong(), gzip.decoder, dev, { _, _, _ -> }, {}, { false })
            assertEquals(at.toLong(), diff)
            dev.bytes[at] = (dev.bytes[at].toInt() xor 4).toByte()
        }
        assertNoHelperThreads("after compare")
    }

    // ------------------------------------------------------------------ 3. cancel

    @Test(timeout = 150_000)
    fun cancelAtEveryCheckStopsPromptlyAndLeavesNoThreads() {
        val size = 2 * CHUNK + 513
        for (f in listOf(raw, gzip, zipForm, xzForm, bz1)) {
            val input = packedFor(f, size)
            // How many times a full run asks.
            val asks = AtomicInteger()
            ImageWriter.writeStream({ ByteArrayInputStream(input) }, input.size.toLong(), f.decoder, MemDisk(devSizes(size, 512).first), null, { _, _, _ -> }, {}, { asks.incrementAndGet(); false })
            assertTrue("${f.name}: cancel was only asked ${asks.get()} times for 3 chunks written and 3 read back", asks.get() >= 6)
            for (k in 0 until asks.get()) {
                val seen = AtomicInteger()
                val t0 = System.nanoTime()
                expect<CancelledException>("${f.name}: cancel at check $k of ${asks.get()}") {
                    ImageWriter.writeStream({ Trickle(input, k.toLong(), 200_000) }, input.size.toLong(), f.decoder, MemDisk(devSizes(size, 512).first), null, { _, _, _ -> }, {}, { seen.getAndIncrement() >= k })
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                assertTrue("${f.name}: cancel at check $k took $ms ms", ms < 5000)
                assertNoHelperThreads("${f.name}: cancel at check $k")
            }
        }
    }

    /** A source that stops with CancelledException the way a live download does, once [after] is reached. */
    private class CancellingSource(private val data: ByteArray, private val mainAsked: CountDownLatch, private val cancelled: AtomicBoolean) : InputStream() {
        private var pos = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (mainAsked.count == 0L) {
                // The person pressed cancel while the writer was waiting for its next chunk.
                cancelled.set(true)
                throw CancelledException()
            }
            Thread.sleep(1)
            if (pos >= data.size) return -1
            val n = minOf(len, data.size - pos, 1000)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }

    /**
     * A live download's stream throws CancelledException from read when the person cancels. The
     * writer must pass that on as a cancel for every kind of image; ForgeService treats
     * CorruptImageException as a damaged download and deletes the file.
     */
    @Test(timeout = 60_000)
    fun aCancelRaisedInsideTheSourceIsReportedAsACancelForEveryKind() {
        val wrong = ArrayList<String>()
        for (f in listOf(raw, gzip, zipForm, xzForm, bz1)) {
            val input = packedFor(f, CHUNK + 1)
            val asked = CountDownLatch(1)
            val cancelled = AtomicBoolean()
            try {
                ImageWriter.writeStream({ CancellingSource(input, asked, cancelled) }, input.size.toLong(), f.decoder, MemDisk(devSizes(CHUNK + 1, 512).first), null, { _, _, _ -> }, {}, { asked.countDown(); cancelled.get() })
                wrong.add("${f.name}: finished without any error")
            } catch (_: CancelledException) {
            } catch (e: Throwable) {
                wrong.add("${f.name}: ${e.javaClass.simpleName}(${e.message})")
            }
            assertNoHelperThreads("${f.name}: cancel inside the source")
        }
        assertTrue("a cancel raised by the source while a chunk was being read came out as: $wrong", wrong.isEmpty())
    }

    // ------------------------------------------------------------------ 4. corrupt and truncated inputs

    /** The write must either refuse with CorruptImageException or put exactly [data] on the drive. */
    private fun corruptOrExact(label: String, f: Form, input: ByteArray, data: ByteArray, maxRead: Int = Int.MAX_VALUE, mustFail: Boolean = false): String? {
        val dev = MemDisk(devSizes(data.size, 512).second)
        val got = try {
            ImageWriter.writeStream(
                { Trickle(input, 5, maxRead, reportsAvailable = maxRead == Int.MAX_VALUE) }, input.size.toLong(), f.decoder, dev, null,
                { _, _, _ -> }, {}, { false },
            )
        } catch (e: CorruptImageException) {
            return null
        } catch (e: WriteException) {
            return "$label: WriteException(${e.message}) instead of CorruptImageException"
        } catch (e: Throwable) {
            return "$label: ${e.javaClass.name}(${e.message}) instead of CorruptImageException"
        }
        if (mustFail) return "$label: reported success ($got bytes written) for an input that can't be a valid image"
        if (got != data.size.toLong()) return "$label: reported success with $got bytes written instead of ${data.size}"
        val mm = Arrays.mismatch(dev.bytes, 0, data.size, data, 0, data.size)
        return if (mm >= 0) "$label: reported success with wrong data on the drive from byte $mm" else null
    }

    private fun attackForm(f: Form, size: Int = 1_300_000) {
        val data = image(size)
        val good = packedFor(f, size)
        val bad = ArrayList<String>()
        fun note(s: String?) {
            if (s != null) bad.add(s)
        }
        note(corruptOrExact("${f.name} untouched", f, good, data))
        val r = java.util.Random(99)
        val spots = (0 until 14).map { r.nextInt(good.size) } + listOf(0, 1, 2, 3, 4, 9, 10, 20, good.size / 2) +
            (1..12).map { good.size - it }
        for (at in spots.distinct()) {
            for (bit in listOf(0, 7)) {
                val flipped = good.copyOf().also { it[at] = (it[at].toInt() xor (1 shl bit)).toByte() }
                note(corruptOrExact("${f.name} bit $bit of byte $at of ${good.size} flipped", f, flipped, data))
            }
        }
        val cuts = listOf(1, 2, 3, 4, 5, 10, 14, 100, good.size / 3, good.size / 2, good.size - 100) + (1..12).map { good.size - it } +
            (0 until 6).map { 1 + r.nextInt(good.size - 1) }
        for (cut in cuts.distinct()) {
            note(corruptOrExact("${f.name} cut to $cut of ${good.size} bytes", f, good.copyOf(cut), data))
        }
        note(corruptOrExact("${f.name} cut to half, 1 byte reads", f, good.copyOf(good.size / 2 + 1), data, maxRead = if (good.size < 400_000) 1 else 3000))
        val junk = ByteArray(5000).also { r.nextBytes(it) }
        for ((what, extra) in listOf("1 zero byte" to ByteArray(1), "5000 random bytes" to junk, "5000 sevens" to ByteArray(5000) { 7 }, "its own first 100 bytes" to good.copyOf(100))) {
            note(corruptOrExact("${f.name} with $what appended", f, good + extra, data))
            note(corruptOrExact("${f.name} with $what appended, short reads", f, good + extra, data, maxRead = 4000))
        }
        note(corruptOrExact("${f.name} empty file", f, ByteArray(0), data, mustFail = true))
        note(corruptOrExact("${f.name} only its first 4 bytes", f, good.copyOf(4), data, mustFail = true))
        assertNoHelperThreads("after ${f.name} damage")
        assertTrue("${bad.size} damaged ${f.name} inputs were mishandled:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test(timeout = 90_000)
    fun damagedGzipIsRefusedOrWrittenExactly() = attackForm(gzip)

    @Test(timeout = 90_000)
    fun damagedZipIsRefusedOrWrittenExactly() = attackForm(zipForm)

    @Test(timeout = 90_000)
    fun damagedXzIsRefusedOrWrittenExactly() = attackForm(xzForm)

    @Test(timeout = 120_000)
    fun damagedBzip2TinyBlocksIsRefusedOrWrittenExactly() = attackForm(bz1)

    @Test(timeout = 120_000)
    fun damagedBzip2BigBlocksIsRefusedOrWrittenExactly() = attackForm(bz9)

    @Test(timeout = 60_000)
    fun bzip2CutAtBlockAndStreamEdgesIsRefused() {
        val data = image(700_000)
        val one = bz2(data, 1)
        val bad = ArrayList<String>()
        // The end marker and stream checksum are the last 10 bytes (plus padding bits).
        for (cut in (one.size - 16)..(one.size - 1)) {
            corruptOrExact("bzip2 cut to ${cut - one.size} bytes from the end", bz1, one.copyOf(cut), data, mustFail = true)?.let { bad.add(it) }
        }
        // A second stream cut anywhere past its header must fail too.
        val two = one + bz2(image(300_000), 1)
        val both = data + image(300_000)
        for (cut in listOf(one.size + 10, one.size + 11, one.size + 40, two.size - 11, two.size - 1)) {
            corruptOrExact("two bzip2 streams cut at byte ${cut - one.size} of the second", bzMulti, two.copyOf(cut), both, mustFail = true)?.let { bad.add(it) }
        }
        assertNoHelperThreads("after bzip2 cuts")
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * A file of several bzip2 streams (what pbzip2 writes) cut just after the next stream's "BZh1"
     * header. The bzip2 tool refuses that as an unexpected end of file.
     */
    @Test(timeout = 60_000)
    fun bzip2CutJustInsideTheNextStreamsHeaderIsRefused() {
        val data = image(700_000)
        val one = bz2(data, 1)
        val two = one + bz2(image(300_000), 1)
        val both = data + image(300_000)
        val bad = ArrayList<String>()
        for (cut in listOf(one.size + 4, one.size + 5, one.size + 9)) {
            corruptOrExact("two bzip2 streams cut at byte ${cut - one.size} of the second", bzMulti, two.copyOf(cut), both, mustFail = true)?.let { bad.add(it) }
        }
        assertNoHelperThreads("after bzip2 header cuts")
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    private fun unpackBz2(input: InputStream, threads: Int = 2, limit: Long = 256L shl 20, fake: Set<Long> = emptySet(), readSize: Int = 70_001): Pair<ByteArray, Int> {
        val s = ParallelBzip2InputStream(input, threads, limit, fake)
        s.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(readSize)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray() to s.joins
        }
    }

    /** The "BZh1".."BZh9" header check must not depend on how many bytes the first read returns. */
    @Test(timeout = 30_000)
    fun bzip2HeaderIsCheckedEvenWhenTheFirstReadIsShort() {
        val data = image(200_000)
        val good = bz2(data, 1)
        val accepted = ArrayList<String>()
        for ((what, change) in listOf<Pair<String, (ByteArray) -> Unit>>(
            "'XZh1'" to { b -> b[0] = 'X'.code.toByte() },
            "'BZx1'" to { b -> b[2] = 'x'.code.toByte() },
            "'BZh0'" to { b -> b[3] = '0'.code.toByte() },
        )) {
            val broken = good.copyOf().also(change)
            // Read whole, the header is refused.
            expect<IOException>("header $what, whole reads") { unpackBz2(ByteArrayInputStream(broken)) }
            for (first in listOf(1, 3)) {
                try {
                    unpackBz2(Trickle(broken, 1, first))
                    accepted.add("header $what with reads of up to $first bytes")
                } catch (_: IOException) {
                }
            }
        }
        assertNoHelperThreads("after bzip2 headers")
        assertTrue("a file that is refused as 'not a bzip2 file' when read whole was unpacked without complaint: $accepted", accepted.isEmpty())
    }

    @Test(timeout = 60_000)
    fun bzip2WithAChanceBlockMarkerInsideItsDataStillUnpacksExactly() {
        val data = image(900_000, )
        val good = bz2(data, 9)
        for (fake in listOf(setOf(8L * 100_000 + 3), setOf(8L * 50_000, 8L * 200_000 + 5), setOf(8L * 40 + 1, 8L * (good.size - 40) + 7), setOf(8L * 60_000 + 1, 8L * 60_020 + 2, 8L * 60_040 + 3))) {
            val (out, joins) = unpackBz2(Trickle(good, 2, 9000), fake = fake, readSize = 12_345)
            assertTrue("markers at $fake were never used", joins > 0)
            assertArrayEquals("markers at bits $fake", data, out)
            checkWrite("bzip2 with markers at $fake", data, good, { s -> ParallelBzip2InputStream(s, 2, 256L shl 20, fake) }, 50_000, devSizes(data.size, 512).second)
        }
        // Tiny blocks, markers scattered through many of them, and almost no memory allowance.
        val small = bz2(data, 1)
        val many = (1..9).map { 8L * (small.size / 10 * it) + it % 8 }.toSet()
        for (threads in 1..3) {
            val (out, _) = unpackBz2(Trickle(small, 4, 3000), threads = threads, limit = 0, fake = many, readSize = 1 + threads * 4999)
            assertArrayEquals("markers in many blocks, $threads threads", data, out)
        }
        assertNoHelperThreads("after chance markers")
    }

    @Test(timeout = 60_000)
    fun aDecoderThatFailsToStartStillClosesTheSource() {
        val notClosed = ArrayList<String>()
        for (f in listOf(gzip, xzForm, zipForm, bz1)) {
            val src = Trickle(ByteArray(0), 1, 100)
            expect<CorruptImageException>("${f.name} empty") {
                ImageWriter.writeStream({ src }, 0, f.decoder, MemDisk(1 shl 20), null, { _, _, _ -> }, {}, { false })
            }
            Thread.sleep(50)
            if (!src.closed) notClosed.add(f.name)
        }
        assertTrue("the source stream was left open after the decoder refused the file: $notClosed", notClosed.isEmpty())
    }

    // ------------------------------------------------------------------ 5. close and interrupt from other threads

    /** Never returns from read until it's closed, like a stalled network stream. */
    private class Stalled(private val first: ByteArray = ByteArray(0)) : InputStream() {
        private val gate = CountDownLatch(1)
        private var pos = 0
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos < first.size) {
                val n = minOf(len, first.size - pos)
                System.arraycopy(first, pos, b, off, n)
                pos += n
                return n
            }
            try {
                gate.await()
            } catch (e: InterruptedException) {
                throw java.io.InterruptedIOException()
            }
            throw IOException("closed")
        }

        override fun close() = gate.countDown()
    }

    /** Starts a reader blocked in [stream].read, runs [poke], and reports what the reader ended with (null if it hangs). */
    private fun blockedReader(stream: InputStream, poke: (Thread) -> Unit): Any? {
        val result = AtomicReference<Any?>(null)
        val started = CountDownLatch(1)
        val t = thread(isDaemon = true, name = "blocked-reader") {
            try {
                started.countDown()
                val buf = ByteArray(100)
                var total = 0
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                }
                result.set("ended after $total bytes")
            } catch (e: Throwable) {
                result.set(e)
            }
        }
        started.await()
        // Give it time to reach the blocking call.
        val until = System.nanoTime() + 2_000_000_000L
        while (t.state == Thread.State.RUNNABLE && System.nanoTime() < until) Thread.sleep(10)
        Thread.sleep(100)
        poke(t)
        t.join(3000)
        val hung = t.isAlive
        if (hung) {
            t.interrupt()
            t.join(2000)
        }
        runCatching { stream.close() }
        return if (hung) null else result.get()
    }

    @Test(timeout = 30_000)
    fun closingPrefetchFromAnotherThreadReleasesABlockedReader() {
        val s = PrefetchInputStream(Stalled(), 1000, 2)
        val end = blockedReader(s) { s.close() }
        assertNoHelperThreads("prefetch closed under a reader")
        assertTrue("a read blocked in PrefetchInputStream was still blocked 3 s after close() from another thread", end != null)
    }

    @Test(timeout = 30_000)
    fun closingBzip2FromAnotherThreadReleasesABlockedReader() {
        val s = ParallelBzip2InputStream(Stalled(bz2(noise(50_000)).copyOf(2000)), 2)
        val end = blockedReader(s) { s.close() }
        assertNoHelperThreads("bzip2 closed under a reader")
        assertTrue("a read blocked in ParallelBzip2InputStream was still blocked 3 s after close() from another thread", end != null)
    }

    @Test(timeout = 30_000)
    fun readingPrefetchAfterCloseDoesNotHang() {
        val s = PrefetchInputStream(ByteArrayInputStream(ByteArray(50_000)), 1000, 2)
        assertEquals(10, s.read(ByteArray(10)))
        s.close()
        val end = blockedReader(s) { }
        assertNoHelperThreads("prefetch read after close")
        assertTrue("reading on after close() on PrefetchInputStream blocked for 3 s instead of failing or ending", end != null)
    }

    @Test(timeout = 30_000)
    fun interruptingAReaderOfPrefetchGivesAnIOException() {
        val s = PrefetchInputStream(Stalled(), 1000, 2)
        val end = blockedReader(s) { it.interrupt() }
        assertNoHelperThreads("prefetch reader interrupted")
        assertTrue("an interrupted read of PrefetchInputStream ended with $end; an InputStream should throw an IOException", end is IOException)
    }

    @Test(timeout = 30_000)
    fun interruptingAReaderOfBzip2GivesAnIOException() {
        val s = ParallelBzip2InputStream(Stalled(bz2(noise(50_000)).copyOf(2000)), 2)
        val end = blockedReader(s) { it.interrupt() }
        assertNoHelperThreads("bzip2 reader interrupted")
        assertTrue("an interrupted read of ParallelBzip2InputStream ended with $end; an InputStream should throw an IOException", end is IOException)
    }

    @Test(timeout = 60_000)
    fun prefetchGivesTheSameBytesWhateverTheChunkAndReadSizes() {
        val base = System.nanoTime()
        for (i in 0 until 400) {
            val seed = base + i
            val r = java.util.Random(seed)
            val data = ByteArray(r.nextInt(6000)).also { r.nextBytes(it) }
            val chunk = 1 + r.nextInt(64)
            val depth = 1 + r.nextInt(3)
            val s = PrefetchInputStream(Trickle(data, seed, 1 + r.nextInt(100)), chunk, depth)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(300)
            while (true) {
                if (r.nextInt(10) == 0) {
                    val b = s.read()
                    if (b < 0) break
                    out.write(b)
                    continue
                }
                val off = r.nextInt(50)
                val n = s.read(buf, off, 1 + r.nextInt(200))
                if (n < 0) break
                out.write(buf, off, n)
                if (r.nextInt(50) == 0) Thread.yield()
            }
            assertEquals("seed $seed: read after the end", -1, s.read(buf))
            s.close()
            assertArrayEquals("seed $seed: chunk $chunk depth $depth", data, out.toByteArray())
        }
        assertNoHelperThreads("after prefetch runs")
    }

    @Test(timeout = 30_000)
    fun prefetchPassesOnAnErrorFromItsSourceAfterTheDataBeforeIt() {
        val data = ByteArray(10_000) { it.toByte() }
        for (failAt in listOf(0, 1, 999, 1000, 1001, 5500)) {
            val src = object : InputStream() {
                var pos = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (pos >= failAt) throw IOException("source broke at $failAt")
                    val n = minOf(len, failAt - pos, 300)
                    System.arraycopy(data, pos, b, off, n)
                    pos += n
                    return n
                }
            }
            val s = PrefetchInputStream(src, 1000, 2)
            val out = ByteArrayOutputStream()
            val e = expect<IOException>("source fails at $failAt") {
                val buf = ByteArray(777)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            assertEquals("source broke at $failAt", e.message)
            assertTrue("bytes before the error must be a prefix of the data", out.size() <= failAt && Arrays.equals(out.toByteArray(), data.copyOf(out.size())))
            // The error stays.
            expect<IOException>("second read after failure") { s.read(ByteArray(10)) }
            s.close()
        }
        assertNoHelperThreads("after prefetch errors")
    }

    @Test(timeout = 90_000)
    fun bzip2GivesTheSameBytesWhateverTheThreadsMemoryAndReadSizes() {
        val base = System.nanoTime()
        for (i in 0 until 14) {
            val seed = base + i
            val r = java.util.Random(seed)
            val data = imageLike(r.nextInt(1_500_000), i + 50)
            val input = when (i % 3) {
                0 -> bz2(data, 1)
                1 -> bz2Multi(data)
                else -> bz2(data, 1 + r.nextInt(9))
            }
            val threads = 1 + r.nextInt(3)
            val limit = listOf(0L, 1L shl 20, 256L shl 20)[r.nextInt(3)]
            val (out, _) = unpackBz2(Trickle(input, seed, 1 + r.nextInt(200_000)), threads, limit, readSize = 1 + r.nextInt(300_000))
            assertArrayEquals("seed $seed: $threads threads, limit $limit", data, out)
        }
        assertNoHelperThreads("after bzip2 runs")
    }

    // ------------------------------------------------------------------ 6. many random runs

    @Test(timeout = 170_000)
    fun twoHundredFiftyRandomWritesAllComeOutExact() {
        val base = System.getProperty("break.seed")?.toLong() ?: System.nanoTime()
        val master = imageLike(2 * CHUNK + 600_000, 3)
        val forms = listOf(raw, raw, raw, raw, gzip, gzip, zipForm, xzForm, bz1, bzMulti)
        for (i in 0 until 250) {
            val seed = base + i
            val r = java.util.Random(seed)
            val f = forms[r.nextInt(forms.size)]
            val slow = f === xzForm || f === bz1 || f === bzMulti
            val size = when (r.nextInt(if (slow) 6 else 10)) {
                0, 1, 2 -> r.nextInt(70_000)
                3, 4 -> r.nextInt(700_000)
                5 -> listOf(0, 1, 511, 512, 513)[r.nextInt(5)]
                6, 7 -> (1 + r.nextInt(2)) * CHUNK + r.nextInt(1025) - 512
                else -> r.nextInt(2 * CHUNK + 500_000)
            }
            val from = r.nextInt(master.size - size + 1)
            val data = master.copyOfRange(from, from + size)
            val input = f.pack(data)
            val (fit, roomy, snug) = devSizes(size, 512)
            val maxRead = listOf(1 + r.nextInt(5000), 1 + r.nextInt(300_000), 1 + r.nextInt(3 * CHUNK), Int.MAX_VALUE)[r.nextInt(4)]
            val label = "seed $seed (run $i, base $base): ${f.name}, $size bytes, reads up to $maxRead"
            try {
                checkWrite(label, data, input, f.decoder, maxRead, listOf(fit, roomy, snug)[r.nextInt(3)], seed = seed) { dev ->
                    val jitter = r.nextInt(4)
                    dev.onWrite = { if (jitter == 1) Thread.yield() else if (jitter == 2 && it % 2 == 0) Thread.sleep(1) }
                    dev.afterRead = { n, _, _ -> if (jitter == 3 && n % 2 == 1) Thread.sleep(1) }
                }
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                throw AssertionError("$label: $e", e)
            }
        }
        assertNoHelperThreads("after 250 writes (base seed $base)")
    }

    // ------------------------------------------------------------------ 7. downloader

    /** Like MiniHttp, with the bad manners MiniHttp doesn't have: ignoring Range, answering 416, dropping on a schedule. */
    private class QuirkHttp(private val files: Map<String, ByteArray>, private val ignoreRange: Boolean, private val status416: Boolean) : Closeable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        val gets: MutableList<String> = Collections.synchronizedList(ArrayList<String>())

        /** Body bytes to send before hanging up; each GET of [dropPath] uses one entry. */
        val drops = ConcurrentLinkedQueue<Int>()

        @Volatile var dropPath = ""

        init {
            thread(isDaemon = true) {
                while (true) {
                    val sock = try {
                        server.accept()
                    } catch (e: Exception) {
                        break
                    }
                    thread(isDaemon = true) {
                        try {
                            sock.use { serve(it) }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

        private fun serve(s: Socket) {
            val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val request = reader.readLine() ?: return
            val method = request.split(" ")[0]
            val path = request.split(" ")[1]
            var range: Long? = null
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Range:", ignoreCase = true)) range = h.substringAfter("bytes=").substringBefore('-').trim().toLong()
            }
            val out = s.getOutputStream()
            val body = files[path]
            if (method == "GET") gets.add("$path@${range ?: 0}")
            if (body == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            if (ignoreRange) range = null
            if (range != null && range >= body.size && status416) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val start = minOf(range ?: 0L, body.size.toLong()).toInt()
            val len = body.size - start
            val status = if (range != null) "206 Partial Content" else "200 OK"
            out.write("HTTP/1.1 $status\r\nContent-Length: ${if (method == "HEAD") body.size else len}\r\nConnection: close\r\n\r\n".toByteArray())
            if (method == "GET") {
                val drop = if (path == dropPath) drops.poll() else null
                out.write(body, start, minOf(drop ?: len, len))
            }
            out.flush()
        }

        override fun close() = server.close()
    }

    /** A download server, a cache folder and a Downloader pointed at them. */
    private inner class Rig(val signed: Boolean, ignoreRange: Boolean = false, status416: Boolean = false, quirk: Boolean = ignoreRange || status416) : Closeable {
        val files = ConcurrentHashMap<String, ByteArray>()
        private val hits: MutableList<String> = ArrayList()
        val mini = if (quirk) null else MiniHttp(files, hits)
        val odd = if (quirk) QuirkHttp(files, ignoreRange, status416) else null
        private val base = "http://127.0.0.1:${mini?.port ?: odd!!.port}"
        val cache: File = Files.createTempDirectory("brkcache").toFile()
        private val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(keys.public.encoded) + "\n-----END PUBLIC KEY-----\n"
        var onOpen: (String) -> Unit = {}
        val dl = Downloader(cache, { pem }, rewriteUrl = { onOpen(it); it.replace("https://dl", base) })
        val iso = File(cache, "live.iso")
        val part = File(cache, "live.iso.part")
        val partStamp = File(cache, "live.iso.part.expected")
        val stamp = File(cache, "live.iso.verified")

        fun sign(data: ByteArray, with: KeyPair = keys): ByteArray =
            Base64.getEncoder().encode(Signature.getInstance("SHA256withECDSA").apply { initSign(with.private); update(data) }.sign())

        fun publish(data: ByteArray) {
            files["/live.iso"] = data
            files["/live.iso-CHECKSUM"] = "${hex(data)}  live.iso\n".toByteArray()
            if (signed) files["/live.iso.sig"] = sign(data)
        }

        val distro get() = Distro(
            "t", "Test OS", "", true, 1, "k.pub",
            listOf(ImageSource("live.iso", "https://dl/live.iso", "https://dl/live.iso-CHECKSUM", if (signed) "https://dl/live.iso.sig" else null, signed)),
        )

        fun leavePart(bytes: ByteArray, of: ByteArray) {
            part.writeBytes(bytes)
            partStamp.writeText(hex(of))
        }

        fun prepare(stream: Boolean = false, cancel: () -> Boolean = { false }) = dl.prepare(distro, { _, _, _ -> }, {}, cancel, stream)

        /** GETs of the image so far, as the byte offsets they asked from. */
        fun imageGets(): Int = odd?.gets?.count { it.startsWith("/live.iso@") } ?: synchronized(hits) { hits.count { it == "/live.iso" } }

        fun assertKept(what: String, data: ByteArray) {
            assertTrue("$what: no finished image", iso.exists())
            assertArrayEquals("$what: the kept image", data, iso.readBytes())
            assertEquals("$what: stamp", hex(data) + if (signed) "\nsigned" else "", stamp.readText())
            assertFalse("$what: .part left behind", part.exists())
            assertFalse("$what: .part.expected left behind", partStamp.exists())
        }

        fun live(cancel: () -> Boolean = { false }): ByteArray {
            val img = prepare(stream = true, cancel = cancel)
            val l = img.live ?: error("no live download offered")
            val got = l.open().readBytes()
            l.finish {}
            return got
        }

        override fun close() {
            mini?.close()
            odd?.close()
            cache.deleteRecursively()
        }
    }

    private val dlData: ByteArray by lazy { imageLike(300_000, 11) }

    @Test(timeout = 60_000)
    fun downloadResumesFromEveryCutPoint() {
        val data = dlData
        for (signed in listOf(false, true)) {
            for (cut in listOf(0, 1, 150_000, data.size - 1, data.size)) {
                for (use416 in listOf(false, true)) {
                    Rig(signed, status416 = use416, quirk = use416).use { rig ->
                        rig.publish(data)
                        rig.leavePart(data.copyOf(cut), data)
                        val img = rig.prepare()
                        assertEquals(signed, img.signatureVerified)
                        rig.assertKept("signed=$signed, .part of $cut bytes, 416=$use416", data)
                    }
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun aPartLongerThanTheRemoteFileIsNeverAcceptedAndTheNextTryRecovers() {
        val data = dlData
        for (extra in listOf(1, 5000)) {
            for (use416 in listOf(false, true)) {
                Rig(signed = false, status416 = use416, quirk = use416).use { rig ->
                    rig.publish(data)
                    rig.leavePart(data + ByteArray(extra) { 9 }, data)
                    try {
                        rig.prepare()
                    } catch (e: VerificationException) {
                        assertFalse("a refused download must not be kept", rig.iso.exists())
                        rig.prepare()
                    }
                    rig.assertKept(".part $extra bytes too long, 416=$use416", data)
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun aPartWithDamagedFirstBytesIsRejectedThenFetchedAgain() {
        val data = dlData
        for (len in listOf(1, 150_000, data.size)) {
            Rig(signed = false).use { rig ->
                rig.publish(data)
                rig.leavePart(data.copyOf(len).also { it[0] = (it[0].toInt() xor 1).toByte() }, data)
                expect<VerificationException>("damaged .part of $len bytes") { rig.prepare() }
                assertFalse(rig.iso.exists())
                assertFalse("the damaged .part was kept", rig.part.exists())
                rig.prepare()
                rig.assertKept("after the damaged .part of $len bytes was thrown away", data)
            }
        }
    }

    /**
     * The same damaged .part when the image is signed. The first failure is right; after it, the
     * damaged bytes must be gone so that trying again downloads a good copy.
     */
    @Test(timeout = 60_000)
    fun aDamagedPartOfASignedImageIsThrownAwaySoTheNextTryRecovers() {
        val data = dlData
        val stuck = ArrayList<String>()
        for (len in listOf(1, 150_000, data.size, data.size + 1)) {
            Rig(signed = true).use { rig ->
                rig.publish(data)
                val bytes = if (len > data.size) data + ByteArray(1) else data.copyOf(len).also { it[0] = (it[0].toInt() xor 1).toByte() }
                rig.leavePart(bytes, data)
                expect<VerificationException>("damaged signed .part of $len bytes") { rig.prepare() }
                assertFalse(rig.iso.exists())
                var last: Throwable? = null
                var ok = false
                for (again in 1..3) {
                    try {
                        rig.prepare()
                        ok = true
                        break
                    } catch (e: VerificationException) {
                        last = e
                    }
                }
                if (ok) rig.assertKept("signed, after the damaged .part of $len bytes", data) else stuck.add("damaged .part of $len bytes (.part still there: ${rig.part.exists()}, size ${rig.part.length()}; error: ${last?.message})")
            }
        }
        assertTrue(
            "with a signed image, a damaged .part is never deleted: three more tries against a healthy server all failed the same way " +
                "without downloading again. Stuck cases: $stuck",
            stuck.isEmpty(),
        )
    }

    /** A download that arrives corrupted once (signed image): the next try, with the server healthy, must work. */
    @Test(timeout = 60_000)
    fun aCorruptedDownloadOfASignedImageIsNotReusedForever() {
        val data = dlData
        Rig(signed = true).use { rig ->
            rig.publish(data)
            rig.files["/live.iso"] = data.copyOf().also { it[777] = (it[777].toInt() xor 1).toByte() }
            expect<VerificationException>("corrupted download") { rig.prepare() }
            assertFalse(rig.iso.exists())
            rig.files["/live.iso"] = data
            var last: Throwable? = null
            for (again in 1..3) {
                try {
                    rig.prepare()
                    rig.assertKept("after the corrupted download", data)
                    return
                } catch (e: VerificationException) {
                    last = e
                }
            }
            fail(
                "a download that arrived corrupted was kept as live.iso.part (exists=${rig.part.exists()}, ${rig.part.length()} bytes) and reused: " +
                    "three more tries against the now healthy server all failed with '${last?.message}'",
            )
        }
    }

    /** Same thing with no signature: the checksum path deletes the bad file, so the retry works. */
    @Test(timeout = 60_000)
    fun aCorruptedDownloadOfAnUnsignedImageIsDeletedAndTheRetryWorks() {
        val data = dlData
        Rig(signed = false).use { rig ->
            rig.publish(data)
            rig.files["/live.iso"] = data.copyOf().also { it[777] = (it[777].toInt() xor 1).toByte() }
            val e = expect<VerificationException>("corrupted download") { rig.prepare() }
            assertEquals("The download was corrupted, so it was deleted. Try again.", e.message)
            assertFalse(rig.part.exists())
            assertFalse(rig.partStamp.exists())
            rig.files["/live.iso"] = data
            rig.prepare()
            rig.assertKept("after the corrupted download", data)
        }
    }

    /** A wrong signature over a good image: refused every time, and accepted once the publisher's signature is right. */
    @Test(timeout = 60_000)
    fun aBadSignatureIsRefusedEveryTimeAndAGoodOneThenWorks() {
        val data = dlData
        Rig(signed = true).use { rig ->
            rig.publish(data)
            val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            rig.files["/live.iso.sig"] = rig.sign(data, other)
            repeat(3) {
                val e = expect<VerificationException>("forged signature, try $it") { rig.prepare() }
                assertEquals("Signature does NOT match the pinned key. Refusing this image.", e.message)
                assertFalse(rig.iso.exists())
                assertFalse(rig.stamp.exists())
            }
            for (junk in listOf("not base64 !!!", "", "AAAA", Base64.getEncoder().encodeToString(ByteArray(70) { 0x30 }))) {
                rig.files["/live.iso.sig"] = junk.toByteArray()
                expect<VerificationException>("signature file '$junk'") { rig.prepare() }
                assertFalse(rig.iso.exists())
            }
            rig.files["/live.iso.sig"] = rig.sign(data)
            assertTrue(rig.prepare().signatureVerified)
            rig.assertKept("once the signature is right", data)
        }
    }

    @Test(timeout = 60_000)
    fun aServerThatIgnoresRangeStillGivesACorrectFile() {
        val data = dlData
        for (signed in listOf(false, true)) {
            for (cut in listOf(1, 150_000, data.size)) {
                Rig(signed, ignoreRange = true).use { rig ->
                    rig.publish(data)
                    // Even a .part with a wrong first byte is fine here: the file is rewritten from byte 0.
                    rig.leavePart(data.copyOf(cut).also { it[0] = (it[0].toInt() xor 1).toByte() }, data)
                    rig.prepare()
                    rig.assertKept("server ignores Range, signed=$signed, .part of $cut", data)
                }
            }
        }
    }

    @Test(timeout = 90_000)
    fun aServerThatKeepsDroppingMidBodyStillGivesACorrectFile() {
        val data = dlData
        for (ignoreRange in listOf(false, true)) {
            Rig(signed = true, ignoreRange = ignoreRange, quirk = true).use { rig ->
                rig.publish(data)
                rig.odd!!.dropPath = "/live.iso"
                rig.odd.drops.addAll(listOf(100_000, 1, 120_000))
                assertTrue(rig.prepare().signatureVerified)
                rig.assertKept("three drops, ignoreRange=$ignoreRange", data)
                assertEquals("image requests", 4, rig.imageGets())
            }
        }
    }

    @Test(timeout = 60_000)
    fun liveDownloadResumesFromEveryCutPoint() {
        val data = dlData
        for (signed in listOf(false, true)) {
            for (cut in listOf(0, 1, 150_000, data.size - 1, data.size)) {
                Rig(signed).use { rig ->
                    rig.publish(data)
                    rig.leavePart(data.copyOf(cut), data)
                    assertArrayEquals("live, .part of $cut bytes: bytes handed to the writer", data, rig.live())
                    rig.assertKept("live, signed=$signed, .part of $cut bytes", data)
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun liveDownloadWithADamagedPartIsRefusedAndTheNextTryRecovers() {
        val data = dlData
        for (signed in listOf(false, true)) {
            Rig(signed).use { rig ->
                rig.publish(data)
                rig.leavePart(data.copyOf(150_000).also { it[0] = (it[0].toInt() xor 1).toByte() }, data)
                expect<VerificationException>("live with a damaged .part") { rig.live() }
                assertFalse(rig.iso.exists())
                assertFalse(rig.part.exists())
                assertArrayEquals(data, rig.live())
                rig.assertKept("live after a damaged .part, signed=$signed", data)
            }
        }
    }

    /** A stale .part longer than the published file: what's kept and stamped as verified must be exactly the published file. */
    @Test(timeout = 60_000)
    fun liveDownloadWithAPartLongerThanTheRemoteKeepsOnlyTheVerifiedBytes() {
        val data = dlData
        Rig(signed = true).use { rig ->
            rig.publish(data)
            rig.leavePart(data + ByteArray(4096) { 0x55 }, data)
            val got = try {
                rig.live()
            } catch (e: VerificationException) {
                assertFalse("refused, so nothing may be kept", rig.iso.exists())
                return
            }
            assertArrayEquals("bytes handed to the writer", data, got)
            assertTrue(rig.iso.exists())
            val kept = rig.iso.readBytes()
            assertTrue(
                "live.iso was stamped verified (${rig.stamp.readText().replace("\n", " ")}) but holds ${kept.size} bytes with sha256 " +
                    "${hex(kept).take(16)}..., not the ${data.size} published bytes with sha256 ${hex(data).take(16)}...: " +
                    "the stale bytes past the end were kept, so every later write of this cached file fails its hash check",
                kept.contentEquals(data),
            )
        }
    }

    @Test(timeout = 60_000)
    fun liveDownloadSurvivesDropsAndAServerThatIgnoresRange() {
        val data = dlData
        for (ignoreRange in listOf(false, true)) {
            Rig(signed = true, ignoreRange = ignoreRange, quirk = true).use { rig ->
                rig.publish(data)
                rig.leavePart(data.copyOf(50_000), data)
                rig.odd!!.dropPath = "/live.iso"
                rig.odd.drops.add(if (ignoreRange) 120_000 else 70_000)
                assertArrayEquals("live with a drop, ignoreRange=$ignoreRange", data, rig.live())
                rig.assertKept("live with a drop, ignoreRange=$ignoreRange", data)
            }
        }
    }

    /**
     * The real thing: a compressed image written while it downloads, cancelled while the writer
     * waits for its next chunk. It must end as a cancel, with the partial download kept.
     */
    @Test(timeout = 60_000)
    fun cancellingALiveDownloadOfACompressedImageIsACancelNotADamagedImage() {
        val body = bz2(ByteArray(3_000_000).also { java.util.Random(5).nextBytes(it) }, 1)
        Rig(signed = false).use { rig ->
            rig.publish(body)
            val cancelled = AtomicBoolean()
            val asked = CountDownLatch(1)
            val img = rig.prepare(stream = true, cancel = { cancelled.get() })
            val live = img.live!!
            val open = {
                val inner = live.open()
                object : InputStream() {
                    override fun read(): Int = throw UnsupportedOperationException()
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        // Cancel is pressed once the writer has started waiting for a chunk.
                        if (asked.await(10, TimeUnit.SECONDS)) cancelled.set(true)
                        return inner.read(b, off, minOf(len, 50_000))
                    }

                    override fun close() = inner.close()
                }
            }
            try {
                ImageWriter.writeStream(open, live.total, ImageKind.decoder(ImageKind.BZIP2), MemDisk(8L shl 20), null, { _, _, _ -> }, {}, { asked.countDown(); cancelled.get() })
                fail("the write finished although it was cancelled")
            } catch (_: CancelledException) {
            } catch (e: CorruptImageException) {
                fail(
                    "cancelling during a live download of a .bz2 image came out as CorruptImageException(\"${e.message}\"); " +
                        "ForgeService answers that by deleting the partial download and saying it was corrupted",
                )
            }
            assertNoHelperThreads("live cancel")
        }
    }

    // ------------------------------------------------------------------ 8. Verify.Running

    private fun sigOver(data: ByteArray, kp: KeyPair = keys): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(data) }.sign())

    private fun feed(v: Verify.Running, data: ByteArray, seed: Long) {
        val r = java.util.Random(seed)
        var at = 0
        while (at < data.size) {
            val n = minOf(data.size - at, 1 + r.nextInt(700_000))
            // From inside a bigger buffer at an offset, as a caller with a reused buffer would.
            val buf = ByteArray(n + 13)
            System.arraycopy(data, at, buf, 13, n)
            v.update(buf, 13, n)
            at += n
        }
        v.update(ByteArray(5), 2, 0)
    }

    @Test(timeout = 60_000)
    fun runningVerifyAcceptsOnlyTheRightSignature() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        for (kp in listOf(keys, p384)) {
            for (size in listOf(0, 1, 31, 32, 33, 64, 65, 9_000_000)) {
                val data = image(size)
                val what = "$size bytes, ${kp.public.algorithm} ${(kp.public as java.security.interfaces.ECPublicKey).params.order.bitLength()} bit"
                val good = Verify.Running(sigOver(data, kp) + "\n", kp.public).also { feed(it, data, size.toLong()) }.finish()
                assertEquals("$what: hash", hex(data), good.first)
                assertTrue("$what: signature should count as checked", good.second)

                val none = Verify.Running(null, kp.public).also { feed(it, data, 1) }.finish()
                assertEquals(hex(data) to false, none)
                assertEquals(hex(data) to false, Verify.Running(sigOver(data, kp), null).also { feed(it, data, 2) }.finish())

                val other = if (size == 0) byteArrayOf(0) else data.copyOf().also { it[size - 1] = (it[size - 1].toInt() xor 1).toByte() }
                val wrongs = linkedMapOf(
                    "a signature over other data" to sigOver(other, kp),
                    "a signature by another key" to sigOver(data, KeyPairGenerator.getInstance("EC").apply { initialize(kp.public.let { (it as java.security.interfaces.ECPublicKey).params }) }.generateKeyPair()),
                    "a signature over the hash text" to sigOver(hex(data).toByteArray(), kp),
                    "a signature over the raw digest" to sigOver(MessageDigest.getInstance("SHA-256").digest(data), kp),
                    "an empty signature" to "",
                    "text that isn't base64" to "this is not base64 ***",
                    "four zero bytes" to "AAAAAA==",
                    "a cut signature" to sigOver(data, kp).let { it.substring(0, it.length / 2 / 4 * 4) },
                    "random bytes" to Base64.getEncoder().encodeToString(ByteArray(71).also { java.util.Random(4).nextBytes(it) }),
                    "a DER signature of two zeros" to Base64.getEncoder().encodeToString(byteArrayOf(0x30, 6, 2, 1, 0, 2, 1, 0)),
                    "the good signature with a byte appended" to Base64.getEncoder().encodeToString(Base64.getDecoder().decode(sigOver(data, kp)) + 0),
                )
                for ((name, sig) in wrongs) {
                    val v = Verify.Running(sig, kp.public).also { feed(it, data, 3) }
                    try {
                        val r = v.finish()
                        fail("$what: $name was accepted: $r")
                    } catch (_: VerificationException) {
                    } catch (e: Throwable) {
                        throw AssertionError("$what: $name crashed with $e instead of VerificationException", e)
                    }
                }
            }
        }
    }

    @Test(timeout = 60_000)
    fun runningVerifyAgreesWithHashAndVerifyOnAFile() {
        for (size in listOf(0, 1, Verify.HASH_BUFFER - 1, Verify.HASH_BUFFER, Verify.HASH_BUFFER + 1, 5_000_001)) {
            val data = image(size)
            val file = Files.createTempFile("brk", ".bin").toFile().apply { writeBytes(data) }
            try {
                assertEquals(hex(data) to true, Verify.hashAndVerify(file, sigOver(data), keys.public))
                assertEquals(hex(data) to false, Verify.hashAndVerify(file, null, null))
                expect<VerificationException>("file of $size bytes, signature over other data") {
                    Verify.hashAndVerify(file, sigOver(data + 1), keys.public)
                }
                val calls = AtomicInteger()
                expect<CancelledException>("cancel while hashing") { Verify.hashAndVerify(file, null, null, { calls.incrementAndGet() > size / Verify.HASH_BUFFER }) }
            } finally {
                file.delete()
            }
        }
    }

    /** A NONEwithECDSA that takes any key and then never verifies: it says no, or throws. */
    class BrokenRawEcdsa : java.security.SignatureSpi() {
        companion object {
            @Volatile var throws = false
            val asked = AtomicInteger()
        }

        override fun engineInitVerify(publicKey: java.security.PublicKey?) = Unit
        override fun engineInitSign(privateKey: java.security.PrivateKey?) = throw java.security.InvalidKeyException("verify only")
        override fun engineUpdate(b: Byte) = Unit
        override fun engineUpdate(b: ByteArray?, off: Int, len: Int) = Unit
        override fun engineSign(): ByteArray = throw java.security.SignatureException("verify only")
        override fun engineVerify(sigBytes: ByteArray?): Boolean {
            asked.incrementAndGet()
            if (throws) throw IllegalStateException("raw ECDSA is broken here")
            return false
        }

        @Deprecated("unused")
        override fun engineSetParameter(param: String?, value: Any?) = Unit

        @Deprecated("unused")
        override fun engineGetParameter(param: String?): Any? = null
    }

    private class BrokenProvider : java.security.Provider("BrokenRaw", 1.0, "raw ECDSA that never verifies") {
        init {
            put("Signature.NONEwithECDSA", BrokenRawEcdsa::class.java.name)
        }
    }

    /**
     * Where the raw ECDSA check is offered but doesn't work, a good image must still verify (by
     * the signature hashing for itself) and a bad signature must still be refused.
     */
    @Test(timeout = 60_000)
    fun aRawSignatureCheckThatDoesNotWorkFallsBackToHashingTheFile() {
        val data = image(700_001)
        val file = Files.createTempFile("brk", ".bin").toFile().apply { writeBytes(data) }
        try {
            // A raw check that passes is final: the file isn't hashed a second time.
            assertEquals(hex(data) to true, Verify.Running(sigOver(data), keys.public).also { feed(it, data, 1) }.finish { throw AssertionError("hashed again") })

            java.security.Security.insertProviderAt(BrokenProvider(), 1)
            for (throws in listOf(false, true)) {
                BrokenRawEcdsa.throws = throws
                val before = BrokenRawEcdsa.asked.get()
                assertEquals("raw check throws=$throws", hex(data) to true, Verify.hashAndVerify(file, sigOver(data), keys.public))
                assertTrue("the broken raw check was not the one used", BrokenRawEcdsa.asked.get() > before)
                val e = expect<VerificationException>("signature over other data, raw check throws=$throws") {
                    Verify.hashAndVerify(file, sigOver(data + 1), keys.public)
                }
                assertEquals("Signature does NOT match the pinned key. Refusing this image.", e.message)
                expect<VerificationException>("signature by another key") {
                    val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
                    Verify.hashAndVerify(file, sigOver(data, other), keys.public)
                }
                // Fed piece by piece: the caller's second look at the file decides.
                val v = Verify.Running(sigOver(data), keys.public).also { feed(it, data, 2) }
                assertEquals(hex(data) to true, v.finish { Verify.recheckFile(file, sigOver(data), keys.public) })
                // A second look at a file that isn't what was fed in doesn't count.
                val shorter = Files.createTempFile("brk", ".bin").toFile().apply { writeBytes(data.copyOf(data.size - 1)) }
                try {
                    expect<VerificationException>("recheck of a different file") {
                        Verify.Running(sigOver(data), keys.public).also { feed(it, data, 3) }
                            .finish { Verify.recheckFile(shorter, sigOver(shorter.readBytes()), keys.public) }
                    }
                } finally {
                    shorter.delete()
                }

                Rig(signed = true).use { rig ->
                    rig.publish(dlData)
                    assertTrue(rig.prepare().signatureVerified)
                    rig.assertKept("download, raw check throws=$throws", dlData)
                }
                Rig(signed = true).use { rig ->
                    rig.publish(dlData)
                    assertArrayEquals(dlData, rig.live())
                    rig.assertKept("live download, raw check throws=$throws", dlData)
                }
                Rig(signed = true).use { rig ->
                    rig.publish(dlData)
                    rig.files["/live.iso"] = dlData.copyOf().also { it[777] = (it[777].toInt() xor 1).toByte() }
                    expect<VerificationException>("corrupted download, raw check throws=$throws") { rig.prepare() }
                    assertFalse(rig.part.exists())
                    rig.files["/live.iso"] = dlData
                    rig.prepare()
                    rig.assertKept("after the corrupted download, raw check throws=$throws", dlData)
                }
            }
        } finally {
            java.security.Security.removeProvider("BrokenRaw")
            BrokenRawEcdsa.throws = false
            file.delete()
        }
    }
}
