package io.github.ravenor907.handheldforge.core
import io.github.ravenor907.ravendisk.core.image.ParallelBzip2InputStream

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.random.Random

class ParallelBzip2Test {
    private fun bz2(b: ByteArray, level: Int = 1) = ByteArrayOutputStream().also { o ->
        BZip2CompressorOutputStream(o, level).use { it.write(b) }
    }.toByteArray()

    private fun unpack(packed: ByteArray, threads: Int = 4, limit: Long = 256L shl 20, fake: Set<Long> = emptySet(), readSize: Int = 70_001) =
        ParallelBzip2InputStream(ByteArrayInputStream(packed), threads, limit, fake).use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(readSize)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }

    private fun reference(packed: ByteArray) = BZip2CompressorInputStream(ByteArrayInputStream(packed), true).use { it.readBytes() }

    /** Something like a disk image: random data, long runs of zeros, repeated text. */
    private fun imageLike(size: Int, seed: Int = 1): ByteArray {
        val r = Random(seed)
        val out = ByteArray(size)
        var i = 0
        while (i < size) {
            val n = minOf(size - i, r.nextInt(1, 400_000))
            when (r.nextInt(3)) {
                0 -> r.nextBytes(out, i, i + n)
                1 -> {} // zeros
                else -> { val t = "SteamOS partition data ${r.nextInt()} ".toByteArray(); for (k in 0 until n) out[i + k] = t[k % t.size] }
            }
            i += n
        }
        return out
    }

    @Test
    fun sameBytesAsTheRegularDecoder() {
        for ((data, level) in listOf(imageLike(6_000_000) to 1, imageLike(3_000_000, 2) to 9, Random.nextBytes(2_500_000) to 3)) {
            val packed = bz2(data, level)
            assertArrayEquals(data, unpack(packed))
            assertArrayEquals(reference(packed), unpack(packed, threads = 1))
        }
    }

    @Test
    fun tinyAndEmpty() {
        assertArrayEquals(ByteArray(0), unpack(bz2(ByteArray(0))))
        assertArrayEquals(byteArrayOf(42), unpack(bz2(byteArrayOf(42))))
        val small = "hello handheld".toByteArray()
        assertArrayEquals(small, unpack(bz2(small), readSize = 1))
    }

    @Test
    fun zerosThatExpandHugelyStayWithinTheMemoryGate() {
        // 100 KB blocks of zeros unpack to megabytes each; a tiny limit forces pieces to wait their turn.
        val data = ByteArray(60_000_000)
        data[12_345_678] = 7
        val packed = bz2(data, 1)
        assertArrayEquals(data, unpack(packed, threads = 4, limit = 1))
    }

    @Test
    fun severalStreamsJoinedLikePbzip2Output() {
        val a = imageLike(1_500_000, 3)
        val b = imageLike(700_000, 4)
        val c = ByteArray(0)
        val packed = bz2(a) + bz2(c, 9) + bz2(b, 5)
        assertArrayEquals(a + b, unpack(packed))
    }

    @Test
    fun markerAppearingByChanceInsideABlockIsHandled() {
        val data = imageLike(2_000_000, 5)
        val packed = bz2(data, 1)
        // Pretend the block marker turned up in the middle of blocks, at odd bit positions.
        val fakes = setOf(packed.size * 8L / 5 + 3, packed.size * 8L / 2 + 5, packed.size * 8L * 4 / 5 + 1)
        ParallelBzip2InputStream(ByteArrayInputStream(packed), 4, 256L shl 20, fakes).use { s ->
            assertArrayEquals(data, s.readBytes())
            assertTrue("joins: ${s.joins}", s.joins == fakes.size)
        }
    }

    @Test
    fun damagedOrCutShortFilesFail() {
        val data = imageLike(3_000_000, 6)
        val packed = bz2(data, 1)
        val damaged = packed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x10).toByte() }
        val cut = packed.copyOf(packed.size * 2 / 3)
        val noEnd = packed.copyOf(packed.size - 6)
        for ((what, bad) in listOf("damaged" to damaged, "cut" to cut, "no end" to noEnd, "not bzip2" to Random.nextBytes(5000))) {
            try {
                unpack(bad)
                fail("$what should fail")
            } catch (e: IOException) {
                // expected
            }
        }
    }

    @Test
    fun matchesTheSystemBzip2Tool() {
        val tool = listOf("/usr/bin/bzip2", "/bin/bzip2").firstOrNull { java.io.File(it).canExecute() } ?: return
        val data = imageLike(5_000_000, 7)
        val p = ProcessBuilder(tool, "-9", "-c").start()
        val writer = Thread { p.outputStream.use { it.write(data) } }.apply { start() }
        val packed = p.inputStream.readBytes()
        writer.join()
        assertTrue(p.waitFor() == 0)
        assertArrayEquals(data, unpack(packed))
    }

    @Test
    fun closingEarlyStopsCleanly() {
        val packed = bz2(imageLike(5_000_000, 8), 1)
        ParallelBzip2InputStream(ByteArrayInputStream(packed)).use { s -> s.read(ByteArray(1000)) }
        // No hang, no leak worth worrying about: threads are daemons and stop on close.
    }

    /** CI: a real file (Valve's SteamOS image) must unpack to exactly what bzip2 -dc gives. */
    @Test
    fun realFileMatchesBzip2() {
        val path = System.getenv("HF_BZ2_FILE") ?: return
        val want = System.getenv("HF_BZ2_SHA256")!!
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val start = System.nanoTime()
        var n = 0L
        ParallelBzip2InputStream(java.io.File(path).inputStream().buffered(1 shl 20)).use { s ->
            val buf = ByteArray(4 shl 20)
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
                n += r
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        val sec = (System.nanoTime() - start) / 1e9
        val line = (if (got == want) "match" else "MISMATCH") +
            " %s: %.2f GB in %.0f s (%.0f MB/s) on %d cores".format(got, n / 1e9, sec, n / 1e6 / sec, Runtime.getRuntime().availableProcessors())
        java.io.File("/tmp/bz2-result.txt").writeText(line + "\n")
        println(line)
        org.junit.Assert.assertEquals(want, got)
    }
}
