package io.github.ravenor907.handheldforge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.SplittableRandom

/**
 * Checks for the fast paths in the exFAT, Ventoy and drive test code: each is compared with the
 * slow, obvious way of doing the same thing, and the batched device access is counted.
 */
class VolumePerfTest {
    private fun tmp() = Files.createTempFile("perf", ".img").toFile().apply { deleteOnExit() }

    /** Counts device calls and remembers which sectors were written. */
    private class Counting(val d: BlockDevice) : BlockDevice {
        override val blockSize get() = d.blockSize
        override val blockCount get() = d.blockCount
        override val name get() = d.name
        var reads = 0
        var writes = 0
        val written = HashSet<Long>()
        var corruptReadsAt = -1L
        override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
            reads++
            d.read(lba, blocks, buf, offset)
            if (corruptReadsAt in lba until lba + blocks) {
                val i = offset + ((corruptReadsAt - lba) * blockSize).toInt() + 17
                buf[i] = (buf[i].toInt() xor 0x40).toByte()
            }
        }
        override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
            writes++
            for (b in lba until lba + blocks) written.add(b)
            d.write(lba, blocks, buf, offset)
        }
        override fun sync() = d.sync()
        fun reset() { reads = 0; writes = 0; written.clear() }
    }

    private class Vol(val dev: Counting, val layout: ExFat.Layout) {
        fun open() = ExFatVolume.open(dev, 2048)

        /** The allocation bitmap as it is on the disk. */
        fun bitmapOnDisk(): ByteArray {
            val b = ByteArray((layout.clustersFor(layout.bitmapBytes) * layout.clusterBytes).toInt())
            dev.d.read(2048 + layout.sectorOf(layout.bitmapCluster), b.size / 512, b)
            return b.copyOf(layout.bitmapBytes.toInt())
        }

        fun used(bitmap: ByteArray, i: Long) = (bitmap[(i / 8).toInt()].toInt() shr (i % 8).toInt()) and 1 == 1
    }

    private fun volume(sizeMb: Long, spc: Int): Vol {
        val d = Counting(FileDisk(tmp(), sizeMb * 2048))
        return Vol(d, ExFat.format(d, 2048, d.blockCount - 2048, "Ventoy", spc, serial = 7))
    }

    private fun setChecksum(set: ByteArray): Int {
        var sum = 0
        for (i in set.indices) {
            if (i == 2 || i == 3) continue
            sum = ((if (sum and 1 != 0) 0x8000 else 0) + (sum ushr 1) + (set[i].toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    /** Turns a contiguous file in the root's first cluster into a FAT-chained one that visits its clusters in [order]. */
    private fun chainFile(v: Vol, f: ExFatVolume.FileInfo, order: List<Long>) {
        val l = v.layout
        val root = ByteArray(l.clusterBytes)
        v.dev.d.read(2048 + l.sectorOf(l.rootCluster), l.sectorsPerCluster, root)
        val bb = ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN)
        var o = 0
        while ((root[o].toInt() and 0xFF) != 0x85 || (bb.getInt(o + 52).toLong() and 0xFFFFFFFFL) != f.firstCluster) o += 32
        val count = (root[o + 1].toInt() and 0xFF) + 1
        root[o + 33] = 0x01 // NoFatChain off
        bb.putShort(o + 2, setChecksum(root.copyOfRange(o, o + count * 32)).toShort())
        v.dev.d.write(2048 + l.sectorOf(l.rootCluster), l.sectorsPerCluster, root)
        val fat = ByteArray((l.fatLength * 512).toInt())
        v.dev.d.read(2048 + l.fatOffset, l.fatLength.toInt(), fat)
        val fb = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN)
        for (i in order.indices) fb.putInt((order[i] * 4).toInt(), if (i == order.lastIndex) -1 else order[i + 1].toInt())
        v.dev.d.write(2048 + l.fatOffset, l.fatLength.toInt(), fat)
    }

    // ---------------------------------------------------------------- drive test pattern

    private fun referencePattern(buf: ByteArray, len: Int, byteOffset: Long, seed: Long) {
        val bb = ByteBuffer.wrap(buf)
        var o = 0
        while (o < len) {
            val r = SplittableRandom(seed xor ((byteOffset + o) * -0x61c8864680b583ebL))
            val end = minOf(len, o + 65536)
            while (o + 8 <= end) {
                bb.putLong(o, r.nextLong())
                o += 8
            }
            while (o < end) buf[o++] = r.nextInt().toByte()
        }
    }

    @Test
    fun patternIsWhatSplittableRandomGives() {
        val a = ByteArray(300_000)
        val b = ByteArray(300_000)
        for (len in intArrayOf(0, 1, 7, 8, 512, 65536, 65536 + 13, 131072 + 5, 300_000)) {
            for (off in longArrayOf(0, 512, 4L shl 20, 123_456_789L * 512, 1L shl 45)) {
                for (seed in longArrayOf(0, 42, 42L.inv(), -1, Long.MIN_VALUE, 0x123456789ABCDEFL)) {
                    a.fill(0x55); b.fill(0x55)
                    referencePattern(a, len, off, seed)
                    DriveTest.pattern(b, len, off, seed)
                    assertArrayEquals("len $len offset $off seed $seed", a, b)
                    assertTrue(DriveTest.matches(a, len, off, seed))
                }
            }
        }
    }

    @Test
    fun matchesSeesAnySingleWrongBit() {
        val len = 131072 + 13
        val a = ByteArray(len + 100)
        DriveTest.pattern(a, len, 1L shl 30, 99)
        assertTrue(DriveTest.matches(a, len, 1L shl 30, 99))
        assertFalse(DriveTest.matches(a, len, (1L shl 30) + 512, 99))
        assertFalse(DriveTest.matches(a, len, 1L shl 30, 98))
        val r = Random(5)
        val places = intArrayOf(0, 7, 8, 65535, 65536, 131071, 131072, len - 1) + IntArray(200) { r.nextInt(len) }
        for (p in places) {
            val bit = 1 shl r.nextInt(8)
            a[p] = (a[p].toInt() xor bit).toByte()
            assertFalse("byte $p", DriveTest.matches(a, len, 1L shl 30, 99))
            a[p] = (a[p].toInt() xor bit).toByte()
        }
        // Bytes past the length don't count.
        a[len] = (a[len] + 1).toByte()
        assertTrue(DriveTest.matches(a, len, 1L shl 30, 99))
    }

    @Test
    fun driveTestCountsBadBytesOfADamagedChunk() {
        val d = Counting(FileDisk(tmp(), 64L * 2048))
        d.corruptReadsAt = 5000 // one flipped bit in the sequential run
        val r = DriveTest.run(d, { _, _, _ -> }, {}, { false }, minBytes = 8L shl 20, maxBytes = 16L shl 20, spots = 16, seed = 42)
        assertEquals(1L, r.badBytes)
        assertEquals(0, r.badSpots)
        d.corruptReadsAt = d.blockCount - 1 // and one in the last marker
        val r2 = DriveTest.run(d, { _, _, _ -> }, {}, { false }, minBytes = 8L shl 20, maxBytes = 16L shl 20, spots = 16, seed = 43)
        assertEquals(0L, r2.badBytes)
        assertEquals(1, r2.badSpots)
        assertEquals((d.blockCount - 2048) * 512, r2.firstBadSpot)
    }

    // ---------------------------------------------------------------- exFAT bitmap

    /** Many small files, some deleted again, so free space is in pieces of many sizes. */
    private fun fragmented(v: Vol, vol: ExFatVolume, r: Random) {
        val names = ArrayList<String>()
        for (i in 0 until 120) {
            val size = 1 + r.nextInt(40 * v.layout.clusterBytes)
            vol.add("f$i.bin", size.toLong(), ByteArrayInputStream(ByteArray(size) { i.toByte() }), { _, _ -> }, { false })
            names.add("f$i.bin")
        }
        names.shuffle(r)
        for (n in names.take(70)) vol.delete(vol.find(n)!!)
    }

    @Test
    fun freeSpaceScansAgreeWithCheckingEveryCluster() {
        val v = volume(48, 1)
        val vol = v.open()
        fragmented(v, vol, Random(1))
        val bitmap = v.bitmapOnDisk()
        val n = v.layout.clusterCount
        var free = 0L
        var best = 0L
        var run = 0L
        for (i in 0 until n) {
            if (v.used(bitmap, i)) run = 0 else { free++; run++; best = maxOf(best, run) }
        }
        fun firstFit(want: Long): Long? {
            var len = 0L
            for (i in 0 until n) {
                if (v.used(bitmap, i)) len = 0 else if (++len == want) return i - want + 1 + 2
            }
            return null
        }
        assertEquals(free, vol.freeClusters())
        assertEquals(best * v.layout.clusterBytes, vol.largestFreeRun())
        // The same answers after a reopen (the bitmap in memory is the one on the disk).
        assertEquals(free, v.open().freeClusters())
        assertEquals(best * v.layout.clusterBytes, v.open().largestFreeRun())
        val cb = v.layout.clusterBytes.toLong()
        for (want in longArrayOf(1, 2, 7, 8, 9, 63, 64, 65, 127, 128, 129, 200, 1000, best - 1, best, best + 1)) {
            val fits = firstFit(want) != null
            assertEquals("run of $want", if (fits) null else want * cb, vol.firstMisfit(listOf(want * cb)))
        }
        assertEquals(free, vol.freeClusters()) // firstMisfit changes nothing
        // A new file lands in the first run that holds it.
        for (want in longArrayOf(3, 65, 130)) {
            val at = firstFit(want)!!
            val fresh = v.open()
            fresh.add("fit$want.bin", want * cb, ByteArrayInputStream(ByteArray((want * cb).toInt())), { _, _ -> }, { false })
            assertEquals(at, fresh.find("fit$want.bin")!!.firstCluster)
            fresh.delete(fresh.find("fit$want.bin")!!)
            assertArrayEquals(bitmap, v.bitmapOnDisk())
        }
    }

    @Test
    fun bitmapOnDiskFollowsEveryAddAndDelete() {
        // 512-byte clusters: a 5 MB file covers three bitmap sectors, written in one go.
        val v = volume(48, 1)
        val vol = v.open()
        val before = v.bitmapOnDisk()
        val data = ByteArray(5 * 1024 * 1024 + 77).also { Random(2).nextBytes(it) }
        v.dev.reset()
        val hash = vol.add("five.bin", data.size.toLong(), ByteArrayInputStream(data), { _, _ -> }, { false })
        val f = vol.find("five.bin")!!
        val clusters = (data.size + 511) / 512
        val expected = before.copyOf()
        for (c in f.firstCluster until f.firstCluster + clusters) {
            val i = c - 2
            expected[(i / 8).toInt()] = (expected[(i / 8).toInt()].toInt() or (1 shl (i % 8).toInt())).toByte()
        }
        assertArrayEquals(expected, v.bitmapOnDisk())
        assertTrue("the data in 1 MB writes, then a few for bitmap and entries: ${v.dev.writes}", v.dev.writes <= 6 + 4)
        vol.verify(f, hash, { _, _ -> }, { false })
        v.open().verify(v.open().find("five.bin")!!, hash, { _, _ -> }, { false })
        vol.delete(f)
        assertArrayEquals(before, v.bitmapOnDisk())
        assertNull(v.open().find("five.bin"))
    }

    @Test
    fun bitmapTailSectorKeepsWhatFollowsTheBitmap() {
        // The bitmap ends partway through a sector; the bytes after it in that sector must survive a flush.
        val v = volume(48, 1)
        val l = v.layout
        assertTrue(l.bitmapBytes % 512 != 0L)
        val lastSector = 2048 + l.sectorOf(l.bitmapCluster) + l.bitmapBytes / 512
        val s = ByteArray(512)
        v.dev.d.read(lastSector, 1, s)
        val tailStart = (l.bitmapBytes % 512).toInt()
        for (i in tailStart until 512) s[i] = 0x5A
        v.dev.d.write(lastSector, 1, s)
        val vol = v.open()
        // Fill the volume so the last bitmap byte is set, then free it again.
        val free = vol.largestFreeRun()
        vol.add("all.bin", free, object : java.io.InputStream() {
            var left = free
            override fun read(): Int = if (left-- > 0) 1 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val k = minOf(len.toLong(), left).toInt()
                left -= k
                return k
            }
        }, { _, _ -> }, { false })
        assertEquals(0L, vol.freeClusters())
        val after = ByteArray(512)
        v.dev.d.read(lastSector, 1, after)
        for (i in tailStart until 512) assertEquals(0x5A.toByte(), after[i])
        assertEquals(0L, v.open().freeClusters())
        vol.delete(vol.find("all.bin")!!)
        v.dev.d.read(lastSector, 1, after)
        for (i in tailStart until 512) assertEquals(0x5A.toByte(), after[i])
        assertEquals(free, v.open().largestFreeRun())
    }

    // ---------------------------------------------------------------- chained files

    @Test
    fun chainedFileReadsBackAndFreesInFewDeviceCalls() {
        val v = volume(48, 1)
        var vol = v.open()
        val data = ByteArray(3 * 1024 * 1024).also { Random(3).nextBytes(it) }
        vol.add("frag.bin", data.size.toLong(), ByteArrayInputStream(data), { _, _ -> }, { false })
        vol.add("keep.bin", 100_000, ByteArrayInputStream(ByteArray(100_000) { 9 }), { _, _ -> }, { false })
        val keepHash = MessageDigest.getInstance("SHA-256").digest(ByteArray(100_000) { 9 })
        val f = vol.find("frag.bin")!!
        val n = data.size / 512
        // The chain visits the file's clusters in shuffled runs of 1 to 30.
        val r = Random(4)
        val runs = ArrayList<List<Long>>()
        var c = f.firstCluster
        while (c < f.firstCluster + n) {
            val len = minOf(1L + r.nextInt(30), f.firstCluster + n - c)
            runs.add((c until c + len).toList())
            c += len
        }
        val head = runs.removeAt(0)
        runs.shuffle(r)
        val order = head + runs.flatten()
        chainFile(v, f, order)
        val md = MessageDigest.getInstance("SHA-256")
        for (cl in order) md.update(data, ((cl - f.firstCluster) * 512).toInt(), 512)
        val expected = md.digest()

        vol = v.open()
        val chained = vol.find("frag.bin")!!
        assertFalse(chained.contiguous)
        val freeBefore = vol.freeClusters()
        v.dev.reset()
        vol.verify(chained, expected, { _, _ -> }, { false })
        assertTrue("reads for ${runs.size + 1} runs: ${v.dev.reads}", v.dev.reads <= runs.size + 1 + 8)
        try {
            vol.verify(chained, ByteArray(32), { _, _ -> }, { false })
            fail()
        } catch (e: WriteException) {
            assertTrue(e.message!!.contains("didn't read back correctly"))
        }
        // Space check with the chained file going away, done without touching the drive's bitmap.
        val bitmap = v.bitmapOnDisk()
        // One file takes the free space at the end; a second fits only where the chained file was.
        val two = listOf(vol.largestFreeRun(), data.size.toLong())
        assertEquals(data.size.toLong(), vol.firstMisfit(two))
        assertNull(vol.firstMisfit(two, listOf(chained)))
        assertEquals(freeBefore, vol.freeClusters())

        v.dev.reset()
        vol.delete(chained)
        assertTrue("writes: ${v.dev.writes}", v.dev.writes <= 4)
        assertTrue("reads: ${v.dev.reads}", v.dev.reads <= 12)
        // Only the entry's sector and bitmap sectors holding the file's bits were written.
        val bitmapFirst = 2048 + v.layout.sectorOf(v.layout.bitmapCluster)
        val allowed = order.map { bitmapFirst + (it - 2) / 8 / 512 }.toSet()
        val rootFirst = 2048 + v.layout.sectorOf(v.layout.rootCluster)
        for (s in v.dev.written) assertTrue("sector $s", s in allowed || s in rootFirst until rootFirst + v.layout.sectorsPerCluster)
        assertEquals(freeBefore + n, vol.freeClusters())
        val after = v.bitmapOnDisk()
        for (i in 0 until v.layout.clusterCount) {
            val wasFile = (i + 2) in f.firstCluster until f.firstCluster + n
            assertEquals("cluster ${i + 2}", if (wasFile) false else v.used(bitmap, i), v.used(after, i))
        }
        val again = v.open()
        assertNull(again.find("frag.bin"))
        assertEquals(freeBefore + n, again.freeClusters())
        again.verify(again.find("keep.bin")!!, keepHash, { _, _ -> }, { false })
    }

    @Test
    fun loopingChainIsReported() {
        val v = volume(48, 1)
        val vol = v.open()
        vol.add("loop.bin", 4096, ByteArrayInputStream(ByteArray(4096)), { _, _ -> }, { false })
        val f = vol.find("loop.bin")!!
        val cl = (f.firstCluster until f.firstCluster + 8).toList()
        chainFile(v, f, cl)
        // Point the last cluster back at the first.
        val fat = ByteArray(512)
        val sector = 2048 + v.layout.fatOffset + cl.last() * 4 / 512
        v.dev.d.read(sector, 1, fat)
        ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN).putInt((cl.last() * 4 % 512).toInt(), cl.first().toInt())
        v.dev.d.write(sector, 1, fat)
        val reopened = v.open()
        try {
            reopened.delete(reopened.find("loop.bin")!!)
            fail()
        } catch (e: java.io.IOException) {
            assertEquals("The drive's file table loops; run a disk check on a computer.", e.message)
        }
    }

    // ---------------------------------------------------------------- folders

    @Test
    fun manySmallFilesInRootAndFolderSurviveReopen() {
        // Enough entries to grow the root and the folder several times (512-byte clusters), with
        // entry sets crossing sector and cluster boundaries.
        val v = volume(48, 1)
        val vol = v.open()
        val content = HashMap<String, ByteArray>()
        for (i in 0 until 60) {
            val root = "root file number $i " + "x".repeat(i % 40) + ".cfg"
            val inDir = "ventoy/entry $i " + "y".repeat((i * 7) % 50) + ".json"
            content[root] = ByteArray(10 + i * 31) { (it + i).toByte() }
            content[inDir] = ByteArray(700 + i) { (it * 3 + i).toByte() }
            vol.writeFile(root, content[root]!!)
            vol.writeFile(inDir, content[inDir]!!)
        }
        for (i in 0 until 60 step 3) {
            val root = "root file number $i " + "x".repeat(i % 40) + ".cfg"
            assertTrue(vol.deleteFile(root))
            content.remove(root)
            val inDir = "ventoy/entry $i " + "y".repeat((i * 7) % 50) + ".json"
            content[inDir] = ByteArray(1500 - i) { (it * 5 + i).toByte() }
            vol.writeFile(inDir, content[inDir]!!)
        }
        for (check in listOf(vol, v.open())) {
            for ((path, bytes) in content) assertArrayEquals(path, bytes, check.readFile(path))
            assertEquals(content.keys.count { !it.startsWith("ventoy/") }, check.files().size)
            assertEquals(content.keys.count { it.startsWith("ventoy/") }, check.entries(check.lookup("ventoy")).size)
        }
        // Every entry set on the disk carries a valid checksum.
        val reopened = v.open()
        for (folder in listOf(null, reopened.lookup("ventoy"))) {
            for (e in reopened.entries(folder)) {
                val set = ByteArray(e.entryOffsets.size * 32)
                e.entryOffsets.forEachIndexed { i, off ->
                    val s = ByteArray(512)
                    v.dev.d.read(2048 + off / 512, 1, s)
                    s.copyInto(set, i * 32, (off % 512).toInt(), (off % 512).toInt() + 32)
                }
                val stored = ByteBuffer.wrap(set).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt() and 0xFFFF
                assertEquals(e.name, setChecksum(set), stored)
            }
        }
        // The bitmap on the disk matches what the files and folders use.
        var used = 0L
        val bitmap = v.bitmapOnDisk()
        for (i in 0 until v.layout.clusterCount) if (v.used(bitmap, i)) used++
        assertEquals(v.layout.clusterCount - used, reopened.freeClusters())
    }

    // ---------------------------------------------------------------- format and Ventoy

    @Test
    fun fatOfABigVolumeIsEmptyPastItsFirstEntries() {
        // 100 GB at 32 KB clusters: a 12.5 MB FAT, written in 13 chunks from one buffer.
        val d = FileDisk(tmp(), 100L * 2097152)
        d.use {
            val l = ExFat.format(d, 2048, d.blockCount - 2048, "BIG", 64, serial = 1)
            assertTrue(l.fatLength > 12 * 2048)
            val fat = ByteArray((l.fatLength * 512).toInt())
            var at = 0
            while (at < l.fatLength) {
                val n = minOf(l.fatLength - at, 2048L).toInt()
                d.read(2048 + l.fatOffset + at, n, fat, at * 512)
                at += n
            }
            val bb = ByteBuffer.wrap(fat).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0xFFFFFFF8.toInt(), bb.getInt(0))
            assertEquals(-1, bb.getInt(4))
            for (c in 2..l.rootCluster) {
                val end = c == l.upcaseCluster - 1 || c == l.rootCluster - 1 || c == l.rootCluster
                assertEquals("cluster $c", if (end) -1 else (c + 1).toInt(), bb.getInt((c * 4).toInt()))
            }
            for (i in ((l.rootCluster + 1) * 4).toInt() until fat.size) if (fat[i].toInt() != 0) fail("FAT byte $i isn't zero")
            assertEquals(l.clusterCount - l.usedClusters, ExFatVolume.open(d, 2048).freeClusters())
        }
    }

    @Test
    fun ventoyInstallNoticesOneWrongBitOnReadBack() {
        val files = VentoyTestPackage.files(secureBootFiles = true)
        val sectors = 3L * 1024 * 1024 * 1024 / 512
        val plan = Ventoy.plan(sectors)
        // In the EFI partition, in the boot code and in sector 0.
        for (bad in longArrayOf(plan.part2Start + 40_000, plan.part2Start + Ventoy.PART2_SECTORS - 1, 500, 0)) {
            val d = Counting(FileDisk(tmp(), sectors))
            d.corruptReadsAt = bad
            val stages = ArrayList<Stage>()
            try {
                Ventoy.install(d, files, { s, _, _ -> stages.add(s) }, {}, { false })
                fail("sector $bad")
            } catch (e: WriteException) {
                assertEquals("The drive didn't keep what was written. It may be failing or counterfeit; try another drive.", e.message)
            }
            assertTrue(Stage.CHECK in stages)
        }
        val good = Counting(FileDisk(tmp(), sectors))
        Ventoy.install(good, files, { _, _, _ -> }, {}, { false })
        assertTrue(Ventoy.secureBootEnabled(VentoyDrive.detect(good)!!.readEfi(good)))
    }
}
