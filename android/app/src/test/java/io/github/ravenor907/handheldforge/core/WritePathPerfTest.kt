package io.github.ravenor907.handheldforge.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** The write path's helper threads and reused buffers must not change a single byte or message. */
class WritePathPerfTest {
    private val rnd = Random(7)

    /** Runs of noise, repeated text and zeros, like a disk image. */
    private fun diskLike(size: Int): ByteArray {
        val out = ByteArray(size)
        var at = 0
        while (at < size) {
            val n = minOf(size - at, 1 + rnd.nextInt(60_000))
            when (rnd.nextInt(3)) {
                0 -> rnd.nextBytes(out, at, at + n)
                1 -> for (i in at until at + n) out[i] = ('a' + i % 23).code.toByte()
            }
            at += n
        }
        return out
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    private fun tmp(data: ByteArray) = Files.createTempFile("wp", ".img").toFile().apply {
        deleteOnExit()
        writeBytes(data)
    }

    private fun disk(blocks: Long) = FileDisk(Files.createTempFile("wp", ".disk").toFile().apply { deleteOnExit() }, blocks)

    private fun helpersGone() {
        val until = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < until) {
            if (Thread.getAllStackTraces().keys.none { it.name == "image-io" && it.isAlive }) return
            Thread.sleep(20)
        }
        fail("helper threads are still running")
    }

    @Test
    fun everyKindPutsTheSameBytesOnTheDrive() {
        val data = diskLike(3 * ImageWriter.CHUNK + 777)
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { o -> o.write(data) } }.toByteArray()
        val bz = ByteArrayOutputStream().also { BZip2CompressorOutputStream(it, 1).use { o -> o.write(data) } }.toByteArray()
        for ((kind, packed) in listOf(ImageKind.DISK to data, ImageKind.GZIP to gz, ImageKind.BZIP2 to bz)) {
            val blocks = 5L * ImageWriter.CHUNK / 512
            disk(blocks).use { d ->
                val logs = ArrayList<String>()
                val stages = ArrayList<Triple<Stage, Long, Long>>()
                val n = ImageWriter.writeStream(
                    tmp(packed), ImageKind.decoder(kind), d, sha(data), { s, a, b -> stages.add(Triple(s, a, b)) }, { logs.add(it) }, { false },
                )
                assertEquals(data.size.toLong(), n)
                val back = ByteArray(4 * ImageWriter.CHUNK)
                d.read(0, back.size / 512, back)
                assertArrayEquals(kind.name, data, back.copyOf(data.size))
                assertTrue(back.copyOfRange(data.size, back.size).all { it == 0.toByte() })
                assertEquals(4, logs.size)
                assertEquals("Writing file disk", logs[0])
                assertTrue(logs[1], Regex("Wrote 0\\.01 GB in 0:\\d\\d \\([0-9.]+ MB/s\\)\\. \\d+% of the time waiting on the drive, \\d+% on the image: .+").matches(logs[1]))
                assertEquals("Reading the drive back to confirm", logs[2])
                assertTrue(logs[3], Regex("Every byte on the drive matches the image \\(read back at [0-9.]+ MB/s\\)").matches(logs[3]))
                val writes = stages.filter { it.first == Stage.WRITE }
                assertEquals(packed.size.toLong(), writes.last().second)
                assertEquals(data.size.toLong(), stages.last().second)
                assertEquals(Stage.CHECK, stages.last().first)
            }
        }
        helpersGone()
    }

    @Test
    fun damagedImagePartwayIsReportedAsBefore() {
        val data = diskLike(3 * ImageWriter.CHUNK)
        val failing = object : InputStream() {
            val inner = ByteArrayInputStream(data)
            var left = ImageWriter.CHUNK + 5000
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) throw IOException("boom")
                return inner.read(b, off, minOf(len, left)).also { left -= it }
            }
        }
        disk(4L * ImageWriter.CHUNK / 512).use { d ->
            try {
                ImageWriter.writeStream({ failing }, data.size.toLong(), { it }, d, null, { _, _, _ -> }, {}, { false })
                fail("should have thrown")
            } catch (e: CorruptImageException) {
                assertEquals("The image file is damaged: boom", e.message)
            }
            // The whole chunk before the damage was still written.
            val back = ByteArray(ImageWriter.CHUNK)
            d.read(0, back.size / 512, back)
            assertArrayEquals(data.copyOf(ImageWriter.CHUNK), back)
        }
        helpersGone()
    }

    @Test
    fun driveTooSmallAndCancelLeaveNoThreads() {
        val data = diskLike(3 * ImageWriter.CHUNK)
        disk(2L * ImageWriter.CHUNK / 512).use { d ->
            try {
                ImageWriter.writeStream(tmp(data), { it }, d, null, { _, _, _ -> }, {}, { false })
                fail("should have thrown")
            } catch (e: WriteException) {
                assertEquals("This drive is too small for the image.", e.message)
            }
        }
        disk(4L * ImageWriter.CHUNK / 512).use { d ->
            var calls = 0
            try {
                ImageWriter.writeStream(tmp(data), { it }, d, null, { _, _, _ -> }, {}, { ++calls > 2 })
                fail("should have thrown")
            } catch (e: CancelledException) {
                assertEquals("Cancelled", e.message)
            }
        }
        helpersGone()
    }

    @Test
    fun wrongByteOnTheDriveIsCaughtInAnyChunk() {
        val data = diskLike(3 * ImageWriter.CHUNK + 100)
        for (bad in listOf(10L, ImageWriter.CHUNK + 10L, 3L * ImageWriter.CHUNK + 50)) {
            val inner = disk(5L * ImageWriter.CHUNK / 512)
            val lying = object : BlockDevice by inner {
                override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
                    inner.read(lba, blocks, buf, offset)
                    val i = bad - lba * 512
                    if (i >= 0 && i < blocks * 512L) buf[offset + i.toInt()] = (buf[offset + i.toInt()] + 1).toByte()
                }
            }
            try {
                ImageWriter.writeStream(tmp(data), { it }, lying, sha(data), { _, _, _ -> }, {}, { false })
                fail("should have thrown")
            } catch (e: WriteException) {
                assertTrue(e.message!!.contains("counterfeit"))
            } finally {
                inner.close()
            }
        }
    }

    @Test
    fun prefetchReusesBuffersWithoutMixingChunks() {
        val data = diskLike(1_000_003)
        val out = ByteArrayOutputStream()
        PrefetchInputStream(ByteArrayInputStream(data), chunk = 4096, depth = 2).use { s ->
            val b = ByteArray(7001)
            var i = 0
            while (true) {
                val n = s.read(b, 0, 1 + (i++ * 37) % b.size)
                if (n < 0) break
                out.write(b, 0, n)
                if (i % 50 == 0) Thread.sleep(1) // let the worker run ahead and wait for a free buffer
            }
        }
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun bzip2ReusesBuffersAcrossManyBlocks() {
        // Small blocks plus a long run of zeros, which unpacks to several buffers from one block.
        val data = diskLike(4_000_000) + ByteArray(5_000_000) + diskLike(1_500_000)
        val packed = ByteArrayOutputStream().also { BZip2CompressorOutputStream(it, 1).use { o -> o.write(data) } }.toByteArray()
        for (limit in listOf(256L shl 20, 1L shl 20)) {
            val out = ByteArrayOutputStream()
            ParallelBzip2InputStream(ByteArrayInputStream(packed), 4, limit).use { s ->
                val b = ByteArray(300_000)
                var i = 0
                while (true) {
                    val n = s.read(b, 0, 1 + (i++ * 7919) % b.size)
                    if (n < 0) break
                    out.write(b, 0, n)
                    if (i % 40 == 0) Thread.sleep(2) // workers finish blocks well ahead of the reader
                }
            }
            assertArrayEquals(data, out.toByteArray())
        }
    }
}
