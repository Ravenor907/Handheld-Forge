package io.github.ravenor907.handheldforge.core

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone

/**
 * An existing exFAT volume on a [BlockDevice]: list the root folder, see free space, add a file
 * and delete one. Written to Microsoft's exFAT specification.
 *
 * Added files are always one contiguous run of clusters (marked NoFatChain, as Windows does for
 * contiguous files). Ventoy needs that: it boots an ISO by reading it straight off the disk.
 *
 * Order of writes when adding: file data, then the allocation bitmap, then the directory entries.
 * If anything stops partway, the volume never points at half-written data.
 */
class ExFatVolume private constructor(private val dev: BlockDevice, val partitionStart: Long) {
    private val bs = dev.blockSize
    var sectorsPerCluster = 0; private set
    var clusterCount = 0L; private set
    private var fatOffset = 0L
    private var heapOffset = 0L
    private var rootCluster = 0L
    private var bitmapCluster = 0L
    private var bitmapBytes = 0L
    private lateinit var bitmap: ByteArray
    private lateinit var bitmapChain: List<Long>
    private var rootChain: List<Long>? = null
    private lateinit var upcase: CharArray
    val clusterBytes get() = sectorsPerCluster.toLong() * bs
    var label = ""; private set

    /** Set when a failed [writeEntrySet] may have left its file entry on the drive. */
    private var entryLeftBehind = false

    data class FileInfo(
        val name: String,
        val size: Long,
        val firstCluster: Long,
        val contiguous: Boolean,
        internal val entryOffsets: List<Long>,
        val isDir: Boolean = false,
    )

    companion object {
        private const val FIRST = 2L

        /** The most a folder may hold, per the exFAT specification. */
        private const val MAX_DIR_BYTES = 256L shl 20

        /** How many failed writes cleaning up after a failed change may cost before giving up. */
        private const val UNDO_FAILURES = 4

        fun open(dev: BlockDevice, partitionStart: Long): ExFatVolume = ExFatVolume(dev, partitionStart).also { it.load() }

        /** exFAT name rules: no control characters or " * / : < > ? \ |, at most 255 characters, not ending in a space or dot. */
        fun cleanName(name: String): String {
            val bad = "\"*/:<>?\\|"
            var n = name.map { if (it.code < 0x20 || it in bad) '_' else it }.joinToString("").trim().trimEnd('.')
            if (n.isEmpty()) n = "image"
            if (n.length > 255) {
                val ext = n.substringAfterLast('.', "").take(10)
                n = n.take(255 - ext.length - 1) + "." + ext
            }
            return n
        }
    }

    // ---------------------------------------------------------------- low level

    private fun readBytes(sectorInVolume: Long, length: Int): ByteArray {
        val sectors = (length + bs - 1) / bs
        val buf = ByteArray(sectors * bs)
        readInto(sectorInVolume, sectors, buf, 0)
        return if (buf.size == length) buf else buf.copyOf(length)
    }

    private fun readInto(sectorInVolume: Long, sectors: Int, buf: ByteArray, offset: Int) {
        var at = 0
        while (at < sectors) {
            val n = minOf(sectors - at, 2048)
            dev.read(partitionStart + sectorInVolume + at, n, buf, offset + at * bs)
            at += n
        }
    }

    private fun writeSectors(sectorInVolume: Long, data: ByteArray) {
        require(data.size % bs == 0)
        writeFrom(sectorInVolume, data.size / bs, data, 0)
    }

    private fun writeFrom(sectorInVolume: Long, sectors: Int, data: ByteArray, offset: Int) {
        var at = 0
        while (at < sectors) {
            val n = minOf(sectors - at, 2048)
            dev.write(partitionStart + sectorInVolume + at, n, data, offset + at * bs)
            at += n
        }
    }

    private fun sectorOf(cluster: Long) = heapOffset + (cluster - FIRST) * sectorsPerCluster

    private fun damaged(what: String): Nothing =
        throw IOException("The drive's file system is damaged ($what); run a disk check on a computer.")

    /**
     * How many clusters a contiguous file or folder of [size] bytes starting at [first] holds,
     * after checking that the whole run lies inside the volume.
     */
    private fun runLength(first: Long, size: Long, name: String): Long {
        if (size < 0) damaged("$name has an impossible size")
        val n = if (size == 0L) 1L else (size - 1) / clusterBytes + 1
        if (first < FIRST || n > clusterCount || first - FIRST > clusterCount - n) damaged("$name reaches past the end of the volume")
        return n
    }

    /**
     * Steps that put things back after a failed write. A USB error is often momentary, so a step
     * that fails is tried again, until [UNDO_FAILURES] attempts in all have failed.
     */
    private inner class Undo {
        private var left = UNDO_FAILURES

        /** True once [step] went through. */
        fun run(step: () -> Unit): Boolean {
            while (left > 0) {
                try {
                    step()
                    return true
                } catch (e: IOException) {
                    left--
                }
            }
            return false
        }
    }

    private fun fatEntry(cluster: Long): Long {
        val s = readBytes(fatOffset + cluster * 4 / bs, bs)
        return ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN).getInt(((cluster * 4) % bs).toInt()).toLong() and 0xFFFFFFFFL
    }

    /** Writes FAT entries. Sectors go out in the order their first entry appears in [values]. */
    private fun setFatEntries(values: Map<Long, Long>) {
        // Group by FAT sector so each sector is read and written once.
        for ((sector, entries) in values.entries.groupBy { fatOffset + it.key * 4 / bs }) {
            val s = readBytes(sector, bs)
            val bb = ByteBuffer.wrap(s).order(ByteOrder.LITTLE_ENDIAN)
            for ((c, v) in entries) bb.putInt(((c * 4) % bs).toInt(), v.toInt())
            writeSectors(sector, s)
        }
    }

    /**
     * The clusters of a file or folder. A chain in the FAT is followed to its end, or for at most
     * [max] clusters.
     */
    private fun chain(first: Long, contiguous: Boolean, length: Long, max: Long = Long.MAX_VALUE): List<Long> {
        if (first < FIRST) return emptyList()
        if (contiguous) {
            val n = runLength(first, length, "a file")
            return (first until first + n).toList()
        }
        val out = ArrayList<Long>()
        // The FAT is read in aligned windows that grow (1, 8, then 64 sectors) while the chain goes
        // on, never past the sector holding the last cluster's entry. The 64-sector windows are
        // kept (up to 2 MB of them), so a chain that jumps back and forth doesn't read them again.
        val lastFatSector = fatOffset + (clusterCount + FIRST - 1) * 4 / bs
        var win = ByteArray(0)
        var winSector = 0L
        var winSectors = 0
        var nextSectors = 1
        var kept: HashMap<Long, ByteArray>? = null
        var c = first
        while (c in FIRST until clusterCount + FIRST && out.size < max) {
            if (out.size > clusterCount) throw IOException("The drive's file table loops; run a disk check on a computer.")
            out.add(c)
            val byteOff = c * 4
            val sector = fatOffset + byteOff / bs
            if (sector < winSector || sector >= winSector + winSectors) {
                winSector = fatOffset + (sector - fatOffset) / nextSectors * nextSectors
                winSectors = minOf(nextSectors.toLong(), lastFatSector - winSector + 1).toInt()
                if (nextSectors == 64) {
                    val map = kept ?: HashMap<Long, ByteArray>().also { kept = it }
                    win = map[winSector] ?: readBytes(winSector, winSectors * bs).also {
                        if (map.size >= 64) map.clear()
                        map[winSector] = it
                    }
                } else {
                    win = readBytes(winSector, winSectors * bs)
                }
                nextSectors = minOf(nextSectors * 8, 64)
            }
            val o = ((sector - winSector) * bs + byteOff % bs).toInt()
            c = ((win[o].toInt() and 0xFF) or ((win[o + 1].toInt() and 0xFF) shl 8) or
                ((win[o + 2].toInt() and 0xFF) shl 16) or ((win[o + 3].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
        }
        return out
    }

    /** A file's first [length] bytes; only the clusters that hold them are read. */
    private fun readChain(first: Long, contiguous: Boolean, length: Long): ByteArray {
        if (length < 0) damaged("a file has an impossible size")
        val need = if (length == 0L) 0L else (length - 1) / clusterBytes + 1
        return readClusters(if (need == 0L) emptyList() else chain(first, contiguous, length, need), length)
    }

    /** The clusters' contents, cut to [length] bytes; a negative [length] means all of them. */
    private fun readClusters(clusters: List<Long>, length: Long): ByteArray {
        val cb = clusterBytes.toInt()
        val total = clusters.size * clusterBytes
        if (length > total) damaged("a file's chain of clusters ends before the file does")
        if (total > Int.MAX_VALUE - 16) throw IOException("A file or folder on the drive is too large to load.")
        val out = ByteArray(total.toInt())
        // Clusters that follow each other on the disk are read in one go, straight into place.
        var i = 0
        while (i < clusters.size) {
            var j = i + 1
            while (j < clusters.size && clusters[j] == clusters[j - 1] + 1) j++
            readInto(sectorOf(clusters[i]), (j - i) * sectorsPerCluster, out, i * cb)
            i = j
        }
        return if (length in 0 until out.size) out.copyOf(length.toInt()) else out
    }

    // ---------------------------------------------------------------- loading

    private fun load() {
        val boot = readBytes(0, 512)
        if (String(boot, 3, 8, Charsets.US_ASCII) != "EXFAT   ") throw IOException("This partition isn't exFAT.")
        val b = ByteBuffer.wrap(boot).order(ByteOrder.LITTLE_ENDIAN)
        if ((boot[108].toInt() and 0xFF) != Integer.numberOfTrailingZeros(bs)) {
            throw IOException("This exFAT volume uses a sector size the drive doesn't report.")
        }
        fatOffset = b.getInt(80).toLong() and 0xFFFFFFFFL
        heapOffset = b.getInt(88).toLong() and 0xFFFFFFFFL
        clusterCount = b.getInt(92).toLong() and 0xFFFFFFFFL
        rootCluster = b.getInt(96).toLong() and 0xFFFFFFFFL
        // The specification allows clusters of up to 32 MB.
        val clusterShift = boot[109].toInt() and 0xFF
        if (clusterShift > 25 - Integer.numberOfTrailingZeros(bs)) damaged("its cluster size is impossible")
        sectorsPerCluster = 1 shl clusterShift
        // Everything read or written later lies in the FAT or the cluster heap, so checking the
        // two here keeps every later access inside the volume and the drive.
        val volumeSectors = minOf(b.getLong(72), dev.blockCount - partitionStart)
        if (clusterCount < 1 || heapOffset > volumeSectors || clusterCount > (volumeSectors - heapOffset) / sectorsPerCluster) {
            damaged("it says it has more clusters than fit on the volume")
        }
        if (fatOffset < 24 || fatOffset >= heapOffset || (clusterCount + FIRST) * 4 > (heapOffset - fatOffset) * bs) {
            damaged("its file table is not where it should be")
        }
        if (rootCluster < FIRST || rootCluster >= clusterCount + FIRST) damaged("its main folder is outside the volume")
        val root = rootEntries()
        var upcaseCluster = 0L
        var upcaseLen = 0L
        forEachEntry(root) { _, e ->
            val bb = ByteBuffer.wrap(e).order(ByteOrder.LITTLE_ENDIAN)
            when (e[0].toInt() and 0xFF) {
                0x81 -> if (bitmapCluster == 0L) {
                    bitmapCluster = bb.getInt(20).toLong() and 0xFFFFFFFFL
                    bitmapBytes = bb.getLong(24)
                }
                0x82 -> {
                    upcaseCluster = bb.getInt(20).toLong() and 0xFFFFFFFFL
                    upcaseLen = bb.getLong(24)
                }
                0x83 -> {
                    val n = e[1].toInt() and 0xFF
                    if (n > 15) damaged("its label is longer than a label can be")
                    label = String(CharArray(n) { bb.getShort(2 + it * 2).toInt().toChar() })
                }
            }
        }
        if (bitmapCluster == 0L || upcaseCluster == 0L) throw IOException("This exFAT volume is missing its bitmap or up-case table.")
        // The bitmap needs a bit for every cluster, and its clusters must hold that many.
        val bitmapNeeded = (clusterCount + 7) / 8
        if (bitmapBytes < bitmapNeeded) damaged("its map of used space is too short")
        bitmapChain = chain(bitmapCluster, false, bitmapBytes, (bitmapBytes - 1) / clusterBytes + 1)
        if (bitmapChain.size * clusterBytes < bitmapNeeded) damaged("its map of used space is too short")
        bitmap = readClusters(bitmapChain, minOf(bitmapBytes, bitmapChain.size * clusterBytes))
        if (upcaseLen < 2 || upcaseLen > (1L shl 20)) damaged("its up-case table has an impossible size")
        upcase = expandUpcase(readChain(upcaseCluster, false, upcaseLen))
    }

    private fun expandUpcase(t: ByteArray): CharArray {
        val out = CharArray(0x10000) { it.toChar() }
        val bb = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN)
        var c = 0
        while (bb.remaining() >= 2 && c < 0x10000) {
            val v = bb.short.toInt() and 0xFFFF
            if (v == 0xFFFF && bb.remaining() >= 2) {
                c += bb.short.toInt() and 0xFFFF
            } else {
                out[c++] = v.toChar()
            }
        }
        return out
    }

    /** A directory's clusters and its raw bytes; [folder] is null for the root. */
    private class Dir(val clusters: List<Long>, val bytes: ByteArray, val folder: FileInfo? = null)

    /** The root folder's cluster chain is kept between calls; [growRoot] is the only thing that changes it. */
    private fun rootEntries(): Dir = readDir(rootChain ?: chain(rootCluster, false, -1).also { rootChain = it }, null)

    private fun readDir(clusters: List<Long>, folder: FileInfo?): Dir {
        if (clusters.size * clusterBytes > MAX_DIR_BYTES) throw IOException("A folder on the drive is too large to load.")
        return Dir(clusters, readClusters(clusters, -1), folder)
    }

    private fun dirOf(folder: FileInfo?): Dir {
        if (folder == null) return rootEntries()
        checkCurrent(folder)
        if (folder.contiguous && runLength(folder.firstCluster, folder.size, "the ${folder.name} folder") * clusterBytes > MAX_DIR_BYTES) {
            throw IOException("A folder on the drive is too large to load.")
        }
        return readDir(chain(folder.firstCluster, folder.contiguous, folder.size), folder)
    }

    /**
     * Checks that [folder] still describes what is on the drive. Its entry is rewritten when the
     * folder grows, so a [FileInfo] looked up before that no longer covers the whole folder.
     */
    private fun checkCurrent(folder: FileInfo) {
        if (folder.entryOffsets.size < 3) damaged("the ${folder.name} folder has no entry")
        val file = folder.entryOffsets[0]
        val stream = folder.entryOffsets[1]
        val s0 = readBytes(file / bs, bs)
        val s1 = if (stream / bs == file / bs) s0 else readBytes(stream / bs, bs)
        val o = (stream % bs).toInt()
        val bb = ByteBuffer.wrap(s1).order(ByteOrder.LITTLE_ENDIAN)
        val same = (s0[(file % bs).toInt()].toInt() and 0xFF) == 0x85 && (s1[o].toInt() and 0xFF) == 0xC0 &&
            (s1[o + 1].toInt() and 0x02 != 0) == folder.contiguous &&
            (bb.getInt(o + 20).toLong() and 0xFFFFFFFFL) == folder.firstCluster && bb.getLong(o + 24) == folder.size
        if (!same) throw IOException("The ${folder.name} folder changed on the drive since it was looked up. Try again.")
    }

    private inline fun forEachEntry(d: Dir, f: (Int, ByteArray) -> Unit) {
        var o = 0
        while (o + 32 <= d.bytes.size) {
            if (d.bytes[o].toInt() == 0) return
            f(o, d.bytes.copyOfRange(o, o + 32))
            o += 32
        }
    }

    /** Byte offset on the volume (from its first sector) of directory byte [o]. */
    private fun dirOffsetOnVolume(d: Dir, o: Int): Long {
        val cluster = d.clusters[(o / clusterBytes).toInt()]
        return sectorOf(cluster) * bs + o % clusterBytes
    }

    // ---------------------------------------------------------------- reading

    /** Files in the root folder (what Ventoy lists by default). */
    fun files(): List<FileInfo> = entries(null).filter { !it.isDir }

    /** Files and folders directly in [folder] (null: the root). */
    fun entries(folder: FileInfo?): List<FileInfo> {
        val d = dirOf(folder)
        val out = ArrayList<FileInfo>()
        var o = 0
        while (o + 32 <= d.bytes.size) {
            val type = d.bytes[o].toInt() and 0xFF
            if (type == 0) break
            if (type == 0x85) {
                val count = d.bytes[o + 1].toInt() and 0xFF
                if (o + 32 * (count + 1) > d.bytes.size) damaged("an entry in a folder runs past the folder's end")
                val attrs = ByteBuffer.wrap(d.bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(o + 4).toInt()
                val s = o + 32
                // A whole set is a file entry, a stream entry, then name entries. A file entry
                // without them is passed over on its own, so the entries after it are still seen.
                var whole = count >= 2 && (d.bytes[s].toInt() and 0xFF) == 0xC0
                if (whole) for (k in 2..count) if ((d.bytes[o + k * 32].toInt() and 0xFF) != 0xC1) { whole = false; break }
                if (!whole) {
                    o += 32
                    continue
                }
                val bb = ByteBuffer.wrap(d.bytes).order(ByteOrder.LITTLE_ENDIAN)
                val flags = d.bytes[s + 1].toInt()
                val nameLen = d.bytes[s + 3].toInt() and 0xFF
                val first = bb.getInt(s + 20).toLong() and 0xFFFFFFFFL
                val size = bb.getLong(s + 24)
                val sb = StringBuilder()
                for (k in 2..count) {
                    val e = o + k * 32
                    for (j in 0 until 15) if (sb.length < nameLen) sb.append(bb.getShort(e + 2 + j * 2).toInt().toChar())
                }
                out.add(
                    FileInfo(sb.toString(), size, first, flags and 0x02 != 0, (0..count).map { dirOffsetOnVolume(d, o + it * 32) }, attrs and 0x10 != 0),
                )
                o += 32 * (count + 1)
                continue
            }
            o += 32
        }
        return out
    }

    fun freeClusters(): Long {
        // Counted 64 clusters at a time; the last few one by one.
        var used = 0L
        val bb = ByteBuffer.wrap(bitmap)
        var i = 0L
        while (i + 64 <= clusterCount) {
            used += java.lang.Long.bitCount(bb.getLong((i ushr 3).toInt()))
            i += 64
        }
        while (i < clusterCount) {
            if (bit(i)) used++
            i++
        }
        return clusterCount - used
    }

    val freeBytes: Long get() = freeClusters() * clusterBytes

    private fun bit(i: Long) = (bitmap[(i / 8).toInt()].toInt() shr (i % 8).toInt()) and 1 == 1

    private fun setBits(first: Long, count: Long, on: Boolean) {
        var i = first - FIRST
        val end = i + count
        while (i < end) {
            if (i and 7 == 0L && end - i >= 8) {
                // Whole bytes at once.
                val from = (i / 8).toInt()
                val to = (end / 8).toInt()
                java.util.Arrays.fill(bitmap, from, to, if (on) 0xFF.toByte() else 0)
                i = to * 8L
                continue
            }
            val idx = (i / 8).toInt()
            val m = 1 shl (i % 8).toInt()
            bitmap[idx] = (if (on) bitmap[idx].toInt() or m else bitmap[idx].toInt() and m.inv()).toByte()
            i++
        }
    }

    /** Writes back the bitmap sectors covering clusters [first, first+count). */
    private fun flushBitmap(first: Long, count: Long) {
        if (count <= 0) return
        flushBitmapSectors((first - FIRST) / 8 / bs, (first - FIRST + count - 1) / 8 / bs)
    }

    /** Where the bitmap's sector [k] (counted from the bitmap's start) is on the volume. */
    private fun bitmapSector(k: Long): Long {
        val sectorByte = k * bs
        return sectorOf(bitmapChain[(sectorByte / clusterBytes).toInt()]) + (sectorByte % clusterBytes) / bs
    }

    /** Writes back the bitmap's sectors [from]..[to]; ones that follow each other on the disk go out in one write. */
    private fun flushBitmapSectors(from: Long, to: Long) {
        var k = from
        while (k <= to) {
            val sector = bitmapSector(k)
            var n = 1
            while (k + n <= to && n < 2048 && bitmapSector(k + n) == sector + n) n++
            val chunk = ByteArray(n * bs)
            val have = minOf(chunk.size.toLong(), bitmap.size - k * bs).toInt()
            System.arraycopy(bitmap, (k * bs).toInt(), chunk, 0, have)
            if (have < chunk.size) {
                // Keep whatever is past the bitmap's end in its last sector.
                val last = (n - 1) * bs
                readBytes(sector + n - 1, bs).copyInto(chunk, have, have - last, bs)
            }
            writeSectors(sector, chunk)
            k += n
        }
    }

    /** First run of [n] free clusters, or null. */
    private fun findRun(n: Long): Long? {
        var run = 0L
        var start = 0L
        val bb = ByteBuffer.wrap(bitmap)
        var i = 0L
        while (i < clusterCount) {
            // 64 clusters at a time where they're all used, or all free and the run can't end among them.
            if (i and 7 == 0L && i + 64 <= clusterCount) {
                val w = bb.getLong((i ushr 3).toInt())
                if (w == -1L) {
                    run = 0
                    i += 64
                    continue
                }
                if (w == 0L && run + 64 < n) {
                    if (run == 0L) start = i
                    run += 64
                    i += 64
                    continue
                }
            }
            if (bit(i)) {
                run = 0
            } else {
                if (run == 0L) start = i
                if (++run == n) return start + FIRST
            }
            i++
        }
        return null
    }

    /**
     * Whether files of these sizes, added in this order, each get one unbroken run (as [add]
     * places them: first fit), after [removing] files are deleted. Returns the first size that
     * wouldn't fit, or null if all fit. Changes nothing on the drive.
     */
    fun firstMisfit(sizes: List<Long>, removing: List<FileInfo> = emptyList()): Long? {
        val saved = bitmap.copyOf()
        try {
            for (f in removing) {
                if (f.firstCluster < FIRST) continue
                if (f.contiguous) setBits(f.firstCluster, runLength(f.firstCluster, f.size, f.name), false)
                else chain(f.firstCluster, false, f.size).forEach { setBits(it, 1, false) }
            }
            for (size in sizes) {
                if (size <= 0L) continue
                val n = (size - 1) / clusterBytes + 1
                val at = findRun(n) ?: return size
                setBits(at, n, true)
            }
            return null
        } finally {
            saved.copyInto(bitmap)
        }
    }

    /** Longest run of free space in bytes: the biggest file that can be added. */
    fun largestFreeRun(): Long = largestRun().second * clusterBytes

    /** First cluster and length (in clusters) of the longest free run. */
    private fun largestRun(): Pair<Long, Long> {
        var best = 0L
        var bestStart = 0L
        var run = 0L
        var start = 0L
        val bb = ByteBuffer.wrap(bitmap)
        var i = 0L
        while (i < clusterCount) {
            // 64 clusters at a time where they're all used or all free.
            if (i and 7 == 0L && i + 64 <= clusterCount) {
                val w = bb.getLong((i ushr 3).toInt())
                if (w == -1L) {
                    run = 0
                    i += 64
                    continue
                }
                if (w == 0L) {
                    if (run == 0L) start = i
                    run += 64
                    if (run > best) { best = run; bestStart = start }
                    i += 64
                    continue
                }
            }
            if (bit(i)) {
                run = 0
            } else {
                if (run == 0L) start = i
                run++
                if (run > best) { best = run; bestStart = start }
            }
            i++
        }
        return (bestStart + FIRST) to best
    }

    // ---------------------------------------------------------------- names

    private fun nameHash(name: String): Int {
        var h = 0
        for (ch in name) {
            val u = upcase[ch.code].code
            for (byte in intArrayOf(u and 0xFF, u ushr 8)) h = ((if (h and 1 != 0) 0x8000 else 0) + (h ushr 1) + byte) and 0xFFFF
        }
        return h
    }

    private fun sameName(a: String, b: String) = a.length == b.length && a.indices.all { upcase[a[it].code] == upcase[b[it].code] }

    fun find(name: String): FileInfo? = files().firstOrNull { sameName(it.name, name) }

    /** A file or folder by path from the root ("ventoy/ventoy.json"), matched the way exFAT does (ignoring case). */
    fun lookup(path: String): FileInfo? {
        var folder: FileInfo? = null
        val parts = path.split('/').filter { it.isNotEmpty() }
        for ((i, p) in parts.withIndex()) {
            val e = entries(folder).firstOrNull { sameName(it.name, p) } ?: return null
            if (i == parts.lastIndex) return e
            if (!e.isDir) return null
            folder = e
        }
        return null
    }

    /** A small file's contents by path, or null if it isn't there. */
    fun readFile(path: String, limit: Int = 1 shl 20): ByteArray? {
        val f = lookup(path)?.takeIf { !it.isDir } ?: return null
        if (f.size > limit) throw IOException("$path is unexpectedly large.")
        return readChain(f.firstCluster, f.contiguous, f.size)
    }

    /**
     * Writes a small file at [path] (at most one folder deep), replacing any file already there
     * and creating the folder if needed.
     */
    fun writeFile(path: String, data: ByteArray) {
        val parts = path.split('/').filter { it.isNotEmpty() }
        require(parts.size in 1..2) { "only one folder level is supported" }
        val folder = if (parts.size == 2) {
            lookup(parts[0])?.also { if (!it.isDir) throw IOException("${parts[0]} on the drive is a file, not a folder.") }
                ?: createFolder(parts[0])
        } else {
            null
        }
        entries(folder).firstOrNull { sameName(it.name, parts.last()) }?.let {
            if (it.isDir) throw IOException("$path on the drive is a folder.")
            delete(it)
        }
        add(parts.last(), data.size.toLong(), data.inputStream(), { _, _ -> }, { false }, folder)
    }

    /** Deletes the file at [path]; false if it wasn't there. */
    fun deleteFile(path: String): Boolean {
        val f = lookup(path)?.takeIf { !it.isDir } ?: return false
        delete(f)
        return true
    }

    /** Makes an empty folder in the root: one zeroed cluster, contiguous. */
    fun createFolder(name: String): FileInfo {
        val clean = cleanName(name)
        if (entries(null).any { sameName(it.name, clean) }) throw IOException("$clean is already on the drive.")
        val c = findRun(1) ?: throw IOException("The drive is full.")
        writeSectors(sectorOf(c), ByteArray(clusterBytes.toInt()))
        setBits(c, 1, true)
        flushBitmap(c, 1)
        try {
            writeEntrySet(clean, c, clusterBytes, null, folder = true)
        } catch (e: IOException) {
            release(c, 1)
            throw e
        }
        dev.sync()
        return lookup(clean) ?: throw IOException("Couldn't create the $clean folder.")
    }

    // ---------------------------------------------------------------- changes

    /**
     * Deletes a file: entries marked unused, clusters freed in the bitmap. The sector holding
     * the file entry is written first, so the file is gone from that one write on; a failure
     * later leaves at worst unused name entries or clusters still marked used.
     */
    fun delete(f: FileInfo) {
        if (f.entryOffsets.isEmpty()) damaged("${f.name} has no entry")
        // What to free is worked out before anything is written.
        val run = if (f.firstCluster >= FIRST && f.contiguous) runLength(f.firstCluster, f.size, f.name) else 0L
        val chained = if (f.firstCluster >= FIRST && !f.contiguous) chain(f.firstCluster, false, f.size) else emptyList()
        // Entries that share a sector are cleared with one read and one write of it.
        var failure: IOException? = null
        var unfinished = false
        val undo = Undo()
        var k = 0
        while (k < f.entryOffsets.size) {
            val sector = f.entryOffsets[k] / bs
            val s = readBytes(sector, bs)
            if (k == 0 && (s[(f.entryOffsets[0] % bs).toInt()].toInt() and 0xFF) != 0x85) {
                throw IOException("${f.name} is no longer where it was on the drive. Try again.")
            }
            while (k < f.entryOffsets.size && f.entryOffsets[k] / bs == sector) {
                val i = (f.entryOffsets[k] % bs).toInt()
                s[i] = (s[i].toInt() and 0x7F).toByte() // InUse bit off
                k++
            }
            val written = if (failure == null) {
                try {
                    writeSectors(sector, s)
                    true
                } catch (e: IOException) {
                    // Nothing has changed if this was the file entry's sector. After it, the
                    // file is already gone, so the rest is finished where the drive allows.
                    if (sector == f.entryOffsets[0] / bs) throw e
                    failure = e
                    undo.run { writeSectors(sector, s) }
                }
            } else {
                undo.run { writeSectors(sector, s) }
            }
            if (!written) {
                unfinished = true
                break
            }
        }
        if (f.firstCluster >= FIRST) {
            if (f.contiguous) {
                val n = run
                setBits(f.firstCluster, n, false)
                flushBitmap(f.firstCluster, n)
            } else {
                // Every cluster of the chain is freed in memory, then each bitmap sector that changed is written once.
                val dirty = BooleanArray((bitmap.size + bs - 1) / bs)
                for (c in chained) {
                    setBits(c, 1, false)
                    dirty[((c - FIRST) / 8 / bs).toInt()] = true
                }
                var from = 0
                while (from < dirty.size) {
                    if (!dirty[from]) { from++; continue }
                    var to = from
                    while (to + 1 < dirty.size && dirty[to + 1]) to++
                    flushBitmapSectors(from.toLong(), to.toLong())
                    from = to + 1
                }
            }
        }
        if (unfinished) failure?.let { throw it }
    }

    /**
     * Adds [name] holding exactly [size] bytes read from [source], as one contiguous run.
     * Returns the SHA-256 of what was written; [verify] then re-reads it from the drive.
     */
    fun add(
        name: String,
        size: Long,
        source: InputStream,
        progress: (Long, Long) -> Unit,
        cancel: () -> Boolean,
        folder: FileInfo? = null,
    ): ByteArray {
        val clean = cleanName(name)
        if (entries(folder).any { sameName(it.name, clean) }) throw IOException("$clean is already on the drive.")
        if (size == 0L) {
            // An empty file owns no clusters (first cluster 0), per the spec.
            if (source.read() != -1) throw IOException("$clean is bigger than expected; it may still be changing.")
            writeEntrySet(clean, 0, 0, folder)
            dev.sync()
            return MessageDigest.getInstance("SHA-256").digest()
        }
        val clusters = maxOf(1L, (size + clusterBytes - 1) / clusterBytes)
        val first = findRun(clusters)
            ?: throw IOException(
                "Not enough free space in one piece for $clean (%.1f GB needed, %.1f GB available in one piece).".format(
                    size / 1e9, largestFreeRun() / 1e9,
                ),
            )

        // 1. Data.
        val md = MessageDigest.getInstance("SHA-256")
        val chunkSectors = 2048
        // A small file doesn't need the full chunk: just its own size, rounded up to whole sectors.
        val buf = ByteArray(minOf(chunkSectors.toLong() * bs, (size + bs - 1) / bs * bs).toInt())
        var written = 0L
        var sector = sectorOf(first)
        while (written < size) {
            if (cancel()) throw CancelledException()
            val want = minOf(buf.size.toLong(), size - written).toInt()
            var got = 0
            while (got < want) {
                val n = source.read(buf, got, want - got)
                if (n < 0) throw IOException("The file ended early (${written + got} of $size bytes). Is it still being downloaded?")
                got += n
            }
            md.update(buf, 0, got)
            val padded = (got + bs - 1) / bs * bs
            java.util.Arrays.fill(buf, got, padded, 0)
            var at = 0
            while (at < padded / bs) {
                val n = minOf(padded / bs - at, 2048)
                dev.write(partitionStart + sector + at, n, buf, at * bs)
                at += n
            }
            sector += padded / bs
            written += got
            progress(written, size)
        }
        if (source.read() != -1) throw IOException("$clean is bigger than expected; it may still be changing.")

        commit(clean, first, size, folder)
        return md.digest()
    }

    /** Steps 2 and 3 of adding a file whose data is already on the disk: bitmap, then entries. */
    private fun commit(name: String, first: Long, size: Long, folder: FileInfo?) {
        val clusters = maxOf(1L, (size + clusterBytes - 1) / clusterBytes)
        // 2. Allocation bitmap.
        setBits(first, clusters, true)
        flushBitmap(first, clusters)
        // 3. Directory entries, last. If they can't be written, give the clusters back.
        try {
            writeEntrySet(name, first, size, folder)
        } catch (e: IOException) {
            release(first, clusters)
            throw e
        }
        dev.sync()
    }

    /**
     * Gives back clusters claimed for an entry set that couldn't be written, unless its file entry
     * may be on the drive after all: clusters an entry points at are never marked free.
     */
    private fun release(first: Long, clusters: Long) {
        if (entryLeftBehind) return
        setBits(first, clusters, false)
        try {
            flushBitmap(first, clusters)
        } catch (e: IOException) {
            // Still marked used on the drive: wasted space at worst.
        }
    }

    /**
     * Adds a file whose size is only known once [source] ends, such as an image unpacked on the
     * way in: it's written into the longest free run, and only the clusters it used are claimed.
     * [progress] gets the bytes written so far. Returns the size and SHA-256 of what was written.
     * Nothing is recorded on the drive unless the whole file fit and was written.
     */
    fun addStreaming(
        name: String,
        source: InputStream,
        progress: (Long) -> Unit,
        cancel: () -> Boolean,
        folder: FileInfo? = null,
    ): Pair<Long, ByteArray> {
        val clean = cleanName(name)
        if (entries(folder).any { sameName(it.name, clean) }) throw IOException("$clean is already on the drive.")
        val (first, runClusters) = largestRun()
        val limit = runClusters * clusterBytes
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(2048 * bs)
        var written = 0L
        var sector = sectorOf(first)
        while (true) {
            if (cancel()) throw CancelledException()
            var got = 0
            while (got < buf.size) {
                val n = source.read(buf, got, buf.size - got)
                if (n < 0) break
                got += n
            }
            if (got == 0) break
            if (written + got > limit) {
                throw IOException(
                    "$clean unpacks to more than the %.1f GB free in one piece on the drive. ".format(limit / 1e9) +
                        "Free up space on the drive and try again.",
                )
            }
            md.update(buf, 0, got)
            val padded = (got + bs - 1) / bs * bs
            java.util.Arrays.fill(buf, got, padded, 0)
            var at = 0
            while (at < padded / bs) {
                val n = minOf(padded / bs - at, 2048)
                dev.write(partitionStart + sector + at, n, buf, at * bs)
                at += n
            }
            sector += padded / bs
            written += got
            progress(written)
            if (got < buf.size) break
        }
        if (written == 0L) {
            writeEntrySet(clean, 0, 0, folder)
            dev.sync()
        } else {
            commit(clean, first, written, folder)
        }
        return written to md.digest()
    }

    /** Reads a file back from the drive and compares it with the hash [add] returned. */
    fun verify(f: FileInfo, expected: ByteArray, progress: (Long, Long) -> Unit, cancel: () -> Boolean) {
        val md = MessageDigest.getInstance("SHA-256")
        var left = f.size
        var done = 0L
        // Contiguous files are one big run; chained ones are read in the runs their clusters form.
        val runs = ArrayList<Pair<Long, Long>>()
        if (f.size < 0) damaged("${f.name} has an impossible size")
        if (f.contiguous) {
            if (f.firstCluster >= FIRST) runs.add(f.firstCluster to runLength(f.firstCluster, f.size, f.name))
        } else {
            var start = 0L
            var count = 0L
            for (c in chain(f.firstCluster, false, f.size)) {
                if (count > 0 && start + count == c) {
                    count++
                } else {
                    if (count > 0) runs.add(start to count)
                    start = c
                    count = 1
                }
            }
            if (count > 0) runs.add(start to count)
        }
        val buf = ByteArray(2048 * bs)
        for ((start, count) in runs) {
            var sector = sectorOf(start)
            var sectorsLeft = count * sectorsPerCluster
            while (sectorsLeft > 0 && left > 0) {
                if (cancel()) throw CancelledException()
                val n = minOf(sectorsLeft, 2048L).toInt()
                dev.read(partitionStart + sector, n, buf, 0)
                val use = minOf(left, n.toLong() * bs).toInt()
                md.update(buf, 0, use)
                left -= use
                done += use
                sector += n
                sectorsLeft -= n
                progress(done, f.size)
            }
        }
        if (left > 0) damaged("${f.name}'s chain of clusters ends before the file does")
        if (!MessageDigest.isEqual(md.digest(), expected)) {
            throw WriteException("${f.name} didn't read back correctly from the drive. The drive may be failing or counterfeit.")
        }
    }

    private fun writeEntrySet(name: String, first: Long, size: Long, parent: FileInfo?, folder: Boolean = false) {
        val nameEntries = (name.length + 14) / 15
        val count = 2 + nameEntries
        val set = ByteArray(count * 32)
        val bb = ByteBuffer.wrap(set).order(ByteOrder.LITTLE_ENDIAN)
        val ts = timestamp()
        set[0] = 0x85.toByte()
        set[1] = (count - 1).toByte()
        bb.putShort(4, if (folder) 0x10 else 0x20) // directory, or archive
        bb.putInt(8, ts); bb.putInt(12, ts); bb.putInt(16, ts)
        set[22] = 0x80.toByte(); set[23] = 0x80.toByte(); set[24] = 0x80.toByte() // times are UTC
        set[32] = 0xC0.toByte()
        set[33] = if (size == 0L) 0x01 else 0x03 // AllocationPossible (+ NoFatChain when it has clusters)
        set[35] = name.length.toByte()
        bb.putShort(36, nameHash(name).toShort())
        bb.putLong(40, size) // ValidDataLength
        bb.putInt(52, first.toInt())
        bb.putLong(56, size) // DataLength
        for (k in 0 until nameEntries) {
            val o = 64 + k * 32
            set[o] = 0xC1.toByte()
            for (j in 0 until 15) {
                val idx = k * 15 + j
                if (idx < name.length) bb.putShort(o + 2 + j * 2, name[idx].code.toShort())
            }
        }
        bb.putShort(2, setChecksum(set).toShort())

        // A run of free slots: unused (InUse bit clear) or past the end marker.
        entryLeftBehind = false
        var d = dirOf(parent)
        var slot = findSlots(d, count)
        if (slot == null) {
            d = if (parent == null) {
                growRoot(d)
                rootEntries()
            } else {
                dirOf(growFolder(parent))
            }
            slot = findSlots(d, count) ?: throw IOException("Couldn't make room in the drive's folder.")
        }
        // Put the set into the folder's bytes (just read from the drive) and write back the sectors
        // it touches; it may cross a sector or cluster boundary. The sector holding the file entry
        // goes last: until it is written the slots still read as free, so the set is only seen
        // once it is whole.
        val dir = d
        val before = dir.bytes.copyOfRange(slot, slot + set.size)
        set.copyInto(dir.bytes, slot)
        val head = slot / bs * bs
        val rest = head + bs
        val end = slot + set.size
        var headTried = false
        try {
            writeDirBytes(dir, rest, end)
            headTried = true
            writeDirBytes(dir, head, rest)
        } catch (e: IOException) {
            // Put the slots back as they were, the file entry's sector first.
            before.copyInto(dir.bytes, slot)
            val undo = Undo()
            if (headTried && !undo.run { writeDirBytes(dir, head, rest) }) {
                // The failed write may have landed all the same. Look, if the drive still answers.
                val at = dirOffsetOnVolume(dir, slot)
                entryLeftBehind = try {
                    (readBytes(at / bs, bs)[(at % bs).toInt()].toInt() and 0xFF) == 0x85
                } catch (e2: IOException) {
                    true
                }
            }
            undo.run { writeDirBytes(dir, rest, end) }
            throw e
        }
    }

    /** Writes back the sectors holding a folder's bytes [from] until [to]; ones that follow each other on the disk go out in one write. */
    private fun writeDirBytes(d: Dir, from: Int, to: Int) {
        var o = from
        while (o < to) {
            val sector = dirOffsetOnVolume(d, o) / bs
            var n = 1
            while (o + n * bs < to && dirOffsetOnVolume(d, o + n * bs) / bs == sector + n) n++
            writeFrom(sector, n, d.bytes, o)
            o += n * bs
        }
    }

    private fun findSlots(d: Dir, count: Int): Int? {
        var run = 0
        var start = 0
        var o = 0
        while (o + 32 <= d.bytes.size) {
            val t = d.bytes[o].toInt() and 0xFF
            val free = t == 0 || t and 0x80 == 0
            if (free) {
                if (run == 0) start = o
                if (++run == count) {
                    // If we use slots at the end marker, the slot after must stay an end marker (0x00), which
                    // holds as long as there's room; growing the folder zero-fills the new cluster.
                    return start
                }
            } else {
                run = 0
            }
            o += 32
        }
        return null
    }

    /**
     * Adds one zeroed cluster to a folder in the root and updates its entry set (size, and
     * NoFatChain cleared with a FAT chain written if the next cluster isn't free). Returns it fresh.
     */
    private fun growFolder(f: FileInfo): FileInfo {
        val clusters = chain(f.firstCluster, f.contiguous, f.size)
        if (clusters.isEmpty()) damaged("the ${f.name} folder has no clusters")
        val c = findRun(1) ?: throw IOException("The drive is full.")
        writeSectors(sectorOf(c), ByteArray(clusterBytes.toInt()))
        setBits(c, 1, true)
        flushBitmap(c, 1)
        val stillContiguous = f.contiguous && c == clusters.last() + 1
        // The folder's entry set with its new length, flags and checksum. Only the file entry
        // (checksum) and the stream entry (length, flags) change, so only their sectors are written.
        val sectors = LinkedHashMap<Long, ByteArray>()
        for (off in f.entryOffsets) sectors.getOrPut(off / bs) { readBytes(off / bs, bs) }
        val set = ByteArray(f.entryOffsets.size * 32)
        f.entryOffsets.forEachIndexed { i, off -> sectors.getValue(off / bs).copyInto(set, i * 32, (off % bs).toInt(), (off % bs).toInt() + 32) }
        val bb = ByteBuffer.wrap(set).order(ByteOrder.LITTLE_ENDIAN)
        val newSize = f.size + clusterBytes
        set[33] = if (stillContiguous) 0x03 else 0x01
        bb.putLong(40, newSize)
        bb.putLong(56, newSize)
        bb.putShort(2, setChecksum(set).toShort())
        val fileSector = f.entryOffsets[0] / bs
        val streamSector = f.entryOffsets[1] / bs
        val oldFile = sectors.getValue(fileSector).copyOf()
        val oldStream = sectors.getValue(streamSector).copyOf()
        for (i in 0..1) set.copyInto(sectors.getValue(f.entryOffsets[i] / bs), (f.entryOffsets[i] % bs).toInt(), i * 32, i * 32 + 32)

        // A folder already chained through the FAT gets longer the moment its last cluster is
        // linked to the new one, so that link is taken back if the entry set can't follow it.
        val oldLink = if (f.contiguous) 0L else fatEntry(clusters.last())
        var linked = false
        var streamTried = false
        var fileTried = false
        try {
            if (!stillContiguous) {
                // The new cluster's end mark goes first and the link to it last; while the folder
                // is still marked contiguous the FAT isn't looked at.
                val links = LinkedHashMap<Long, Long>()
                links[c] = 0xFFFFFFFFL
                if (f.contiguous) clusters.zipWithNext().forEach { (a, b) -> links[a] = b }
                links[clusters.last()] = c
                linked = !f.contiguous
                setFatEntries(links)
            }
            // The stream entry's sector, then the file entry's if it is another one.
            streamTried = true
            writeSectors(streamSector, sectors.getValue(streamSector))
            if (fileSector != streamSector) {
                fileTried = true
                writeSectors(fileSector, sectors.getValue(fileSector))
            }
        } catch (e: IOException) {
            val undo = Undo()
            if (fileTried) undo.run { writeSectors(fileSector, oldFile) }
            if (streamTried) undo.run { writeSectors(streamSector, oldStream) }
            if (linked) undo.run { setFatEntries(mapOf(clusters.last() to oldLink)) }
            throw e
        }
        dev.sync()
        return entries(null).firstOrNull { it.entryOffsets.first() == f.entryOffsets.first() }
            ?: throw IOException("Lost track of the ${f.name} folder while growing it.")
    }

    private fun setChecksum(set: ByteArray): Int {
        var sum = 0
        for (i in set.indices) {
            if (i == 2 || i == 3) continue
            sum = ((if (sum and 1 != 0) 0x8000 else 0) + (sum ushr 1) + (set[i].toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    /** Adds one zeroed cluster to the root folder's chain. */
    private fun growRoot(d: Dir) {
        val c = findRun(1) ?: throw IOException("The drive is full.")
        writeSectors(sectorOf(c), ByteArray(clusterBytes.toInt()))
        setBits(c, 1, true)
        flushBitmap(c, 1)
        rootChain = null
        // The new cluster's end mark first, then the link that makes it part of the folder: the
        // chain is whole after either write, and a failure leaves at worst the cluster unused.
        val links = LinkedHashMap<Long, Long>()
        links[c] = 0xFFFFFFFFL
        links[d.clusters.last()] = c
        setFatEntries(links)
    }

    /** exFAT timestamp: DOS date and time fields, in UTC. */
    private fun timestamp(): Int {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val year = (c.get(Calendar.YEAR) - 1980).coerceIn(0, 127)
        return (year shl 25) or ((c.get(Calendar.MONTH) + 1) shl 21) or (c.get(Calendar.DAY_OF_MONTH) shl 16) or
            (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
    }
}
