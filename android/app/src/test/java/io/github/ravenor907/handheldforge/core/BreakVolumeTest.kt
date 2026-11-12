package io.github.ravenor907.handheldforge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.BitSet
import java.util.Random
import java.util.SplittableRandom
import java.util.TreeMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// ------------------------------------------------------------------------------------ helpers

private fun bvLe16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
private fun bvLe32(b: ByteArray, o: Int) = (bvLe16(b, o).toLong()) or (bvLe16(b, o + 2).toLong() shl 16)
private fun bvLe64(b: ByteArray, o: Int) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong(o)
private fun bvSha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

private fun bvSetChecksum(set: ByteArray): Int {
    var sum = 0
    for (i in set.indices) {
        if (i == 2 || i == 3) continue
        sum = ((if (sum and 1 != 0) 0x8000 else 0) + (sum ushr 1) + (set[i].toInt() and 0xFF)) and 0xFFFF
    }
    return sum
}

private fun bvUp(c: Char): Char = if (c.code in 0xD800..0xDFFF) c else Character.toUpperCase(c)
private fun bvKey(s: String) = String(CharArray(s.length) { bvUp(s[it]) })

/** Sparse in-memory disk: 32 KB pieces, kept only once something other than the fill byte is written. */
internal class BvDisk(
    override val blockCount: Long,
    private val fill: Byte = 0,
    private val softEnd: Boolean = false,
) : BlockDevice {
    override val blockSize = 512
    override val name = "test disk"
    private val per = 64
    private var chunks = HashMap<Long, ByteArray>()
    var writeCalls = 0L

    private fun range(lba: Long, blocks: Int) {
        if (lba < 0 || blocks < 0 || lba + blocks > blockCount) {
            if (softEnd) throw IOException("sector $lba+$blocks is past the end of the drive ($blockCount)")
            throw IllegalStateException("access past the end of the device: sector $lba+$blocks of $blockCount")
        }
    }

    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        range(lba, blocks)
        var s = lba
        var o = offset
        var left = blocks
        while (left > 0) {
            val inC = (s % per).toInt()
            val n = minOf(left, per - inC)
            val c = chunks[s / per]
            if (c == null) java.util.Arrays.fill(buf, o, o + n * 512, fill) else System.arraycopy(c, inC * 512, buf, o, n * 512)
            s += n; o += n * 512; left -= n
        }
    }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        range(lba, blocks)
        writeCalls++
        var s = lba
        var o = offset
        var left = blocks
        while (left > 0) {
            val inC = (s % per).toInt()
            val n = minOf(left, per - inC)
            var c = chunks[s / per]
            if (c == null) {
                var same = true
                for (i in o until o + n * 512) if (buf[i] != fill) { same = false; break }
                if (!same) {
                    c = ByteArray(per * 512)
                    if (fill != 0.toByte()) java.util.Arrays.fill(c, fill)
                    chunks[s / per] = c
                }
            }
            if (c != null) System.arraycopy(buf, o, c, inC * 512, n * 512)
            s += n; o += n * 512; left -= n
        }
    }

    override fun sync() {}

    fun copy(): BvDisk {
        val d = BvDisk(blockCount, fill, softEnd)
        for ((k, v) in chunks) d.chunks[k] = v.copyOf()
        return d
    }

    /** Bytes at a byte offset on the device. */
    fun bytes(off: Long, len: Int): ByteArray {
        val first = off / 512
        val sectors = ((off + len + 511) / 512 - first).toInt()
        val b = ByteArray(sectors * 512)
        read(first, sectors, b, 0)
        return b.copyOfRange((off - first * 512).toInt(), (off - first * 512).toInt() + len)
    }

    fun poke(off: Long, data: ByteArray) {
        val first = off / 512
        val sectors = ((off + data.size + 511) / 512 - first).toInt()
        val b = ByteArray(sectors * 512)
        read(first, sectors, b, 0)
        data.copyInto(b, (off - first * 512).toInt())
        val calls = writeCalls
        write(first, sectors, b, 0)
        writeCalls = calls
    }
}

/** Wraps a device to make writes fail, drop them, or flip a byte of one sector when it is read. */
internal class BvFault(val d: BlockDevice) : BlockDevice {
    override val blockSize get() = d.blockSize
    override val blockCount get() = d.blockCount
    override val name get() = d.name
    var writes = 0L
    var failAt = -1L
    var failFor = 1L
    var partial = false
    var flipLba = -1L
    var dropFrom = Long.MAX_VALUE

    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        d.read(lba, blocks, buf, offset)
        if (flipLba in lba until lba + blocks) {
            val i = offset + ((flipLba - lba) * blockSize).toInt() + 77
            buf[i] = (buf[i].toInt() xor 0x10).toByte()
        }
    }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        writes++
        if (failAt >= 0 && writes >= failAt && writes < failAt + failFor) {
            if (partial && blocks > 1 && writes == failAt) d.write(lba, 1, buf, offset)
            throw IOException("USB transfer failed (injected on write $writes)")
        }
        if (lba >= dropFrom) return
        if (lba + blocks > dropFrom) {
            d.write(lba, (dropFrom - lba).toInt(), buf, offset)
            return
        }
        d.write(lba, blocks, buf, offset)
    }

    override fun sync() = d.sync()
}

/** A drive that claims [blockCount] sectors but only has [real]'s; addresses past the real end wrap around. */
internal class BvWrap(val real: BvDisk, override val blockCount: Long) : BlockDevice {
    override val blockSize = 512
    override val name = "wrapping fake"
    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        for (i in 0 until blocks) real.read((lba + i) % real.blockCount, 1, buf, offset + i * 512)
    }
    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        for (i in 0 until blocks) real.write((lba + i) % real.blockCount, 1, buf, offset + i * 512)
    }
    override fun sync() {}
}

/** A stream that hands its bytes out in short, uneven reads. */
private class BvDribble(private val data: ByteArray, private val r: Random) : InputStream() {
    private var at = 0
    override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (at >= data.size) return -1
        val n = minOf(len, data.size - at, 1 + r.nextInt(70_000))
        System.arraycopy(data, at, b, off, n)
        at += n
        return n
    }
}

// ------------------------------------------------------------------------------------ checker

/**
 * Independent exFAT consistency check, reading the device directly. [fatal] holds structural
 * errors, [torn] holds broken or orphaned directory entries, [leaked] counts clusters marked
 * used in the bitmap that nothing owns, [minor] holds deviations that harm nothing.
 */
internal class BvFsck(private val dev: BlockDevice, private val part: Long) {
    class Node(
        val path: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val first: Long,
        val noFatChain: Boolean,
        val runs: List<LongArray>,
        val entryAt: LongArray,
    ) {
        val clusters: Long get() = runs.sumOf { it[1] }
    }

    val fatal = ArrayList<String>()
    val torn = ArrayList<String>()
    val minor = ArrayList<String>()
    var leaked = 0L
    val nodes = LinkedHashMap<String, Node>()
    var spc = 0
    var cc = 0L
    var fatOffset = 0L
    var fatLength = 0L
    var heap = 0L
    var rootCluster = 0L
    var volumeLength = 0L
    val cb: Int get() = spc * 512
    var label: String? = null
    var alloc = BitSet()
    val owner = BitSet()
    var rootRuns: List<LongArray> = emptyList()
    var bitmapRuns: List<LongArray> = emptyList()
    var upcaseRuns: List<LongArray> = emptyList()
    var bitmapSize = 0L
    var bitmapEntryAt = -1L
    var upcaseEntryAt = -1L
    var labelEntryAt = -1L
    private var upcase = CharArray(0x10000) { it.toChar() }
    private val fatCache = HashMap<Long, ByteArray>()
    val used: Long get() = alloc.cardinality().toLong()
    val free: Long get() = cc - used
    val problems: List<String> get() = fatal + torn + (if (leaked != 0L) listOf("$leaked clusters are marked used but belong to nothing") else emptyList())

    fun sec(n: Long, count: Int): ByteArray {
        val b = ByteArray(count * 512)
        var at = 0
        while (at < count) {
            val k = minOf(count - at, 2048)
            dev.read(part + n + at, k, b, at * 512)
            at += k
        }
        return b
    }

    fun fat(c: Long): Long {
        val s = fatOffset + c * 4 / 512
        return bvLe32(fatCache.getOrPut(s) { sec(s, 1) }, (c * 4 % 512).toInt())
    }

    fun sectorOf(c: Long) = heap + (c - 2) * spc

    fun readRuns(runs: List<LongArray>): ByteArray {
        val total = runs.sumOf { it[1] } * cb
        if (total > 400L shl 20) throw IOException("checker: $total bytes is too much to read")
        val out = ByteArray(total.toInt())
        var at = 0
        for (run in runs) {
            val b = sec(sectorOf(run[0]), (run[1] * spc).toInt())
            b.copyInto(out, at)
            at += b.size
        }
        return out
    }

    fun read(n: Node): ByteArray = readRuns(n.runs).copyOf(n.size.toInt())

    /** Byte offset on the volume of byte [o] of something stored in [runs]. */
    fun offsetIn(runs: List<LongArray>, o: Long): Long {
        var idx = o / cb
        for (run in runs) {
            if (idx < run[1]) return sectorOf(run[0] + idx) * 512 + o % cb
            idx -= run[1]
        }
        throw IOException("checker: offset $o outside its clusters")
    }

    private fun chainRuns(first: Long, who: String): List<LongArray> {
        val runs = ArrayList<LongArray>()
        var c = first
        var n = 0L
        while (true) {
            if (c < 2 || c > cc + 1) { fatal.add("$who: its FAT chain reaches invalid cluster $c"); break }
            if (n > cc) { fatal.add("$who: its FAT chain loops"); break }
            if (runs.isNotEmpty() && runs.last()[0] + runs.last()[1] == c) runs.last()[1]++ else runs.add(longArrayOf(c, 1))
            n++
            val v = fat(c)
            if (v == 0xFFFFFFFFL) break
            c = v
        }
        return runs
    }

    private fun claim(runs: List<LongArray>, who: String): Boolean {
        var ok = true
        for (run in runs) {
            if (run[0] < 2 || run[0] + run[1] > cc + 2) {
                fatal.add("$who: clusters ${run[0]}+${run[1]} are outside the volume")
                ok = false
                continue
            }
            val s = (run[0] - 2).toInt()
            val e = (run[0] - 2 + run[1]).toInt()
            val nx = owner.nextSetBit(s)
            if (nx in s until e) {
                fatal.add("cross-link: cluster ${nx + 2} of $who already belongs to something else")
                ok = false
            }
            owner.set(s, e)
        }
        return ok
    }

    fun run(): BvFsck {
        try {
            doRun()
        } catch (e: Exception) {
            fatal.add("checker stopped: $e")
        }
        return this
    }

    private fun doRun() {
        val boot = sec(0, 12)
        if (!boot.contentEquals(sec(12, 12))) fatal.add("backup boot region differs from the main one")
        if ((boot[0].toInt() and 0xFF) != 0xEB || boot[1].toInt() != 0x76 || (boot[2].toInt() and 0xFF) != 0x90) fatal.add("boot jump bytes wrong")
        if (String(boot, 3, 8, Charsets.US_ASCII) != "EXFAT   ") { fatal.add("not exFAT"); return }
        for (i in 11 until 64) if (boot[i] != 0.toByte()) { fatal.add("boot sector byte $i must be zero"); break }
        val po = bvLe64(boot, 64)
        volumeLength = bvLe64(boot, 72)
        fatOffset = bvLe32(boot, 80)
        fatLength = bvLe32(boot, 84)
        heap = bvLe32(boot, 88)
        cc = bvLe32(boot, 92)
        rootCluster = bvLe32(boot, 96)
        if (po != part && po != 0L) fatal.add("boot sector partition offset $po, volume is at $part")
        if (bvLe16(boot, 104) != 0x0100) fatal.add("revision is not 1.00")
        if (boot[108].toInt() != 9) fatal.add("bytes per sector shift ${boot[108]}")
        val shift = boot[109].toInt() and 0xFF
        if (shift > 16) { fatal.add("sectors per cluster shift $shift"); return }
        spc = 1 shl shift
        if (boot[110].toInt() != 1) fatal.add("number of FATs ${boot[110]}")
        if (bvLe16(boot, 510) != 0xAA55) fatal.add("boot signature missing")
        for (s in 1..8) if (bvLe16(boot, s * 512 + 510) != 0xAA55) fatal.add("extended boot sector $s has no signature")
        var sum = 0
        for (i in 0 until 11 * 512) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = (if (sum and 1 != 0) 0x80000000.toInt() else 0) + (sum ushr 1) + (boot[i].toInt() and 0xFF)
        }
        for (i in 0 until 128) if (bvLe32(boot, 11 * 512 + i * 4) != (sum.toLong() and 0xFFFFFFFFL)) { fatal.add("boot checksum sector wrong at word $i"); break }
        if (fatOffset < 24) fatal.add("FAT offset $fatOffset is inside the boot regions")
        if (fatOffset + fatLength > heap) fatal.add("FAT runs into the cluster heap")
        if (fatLength * 512 / 4 < cc + 2) fatal.add("FAT too short: $fatLength sectors for $cc clusters")
        if (heap + cc * spc > volumeLength) fatal.add("cluster heap runs past the volume end")
        if (part + volumeLength > dev.blockCount) fatal.add("volume runs past the device end")
        if (cc > Int.MAX_VALUE - 16 || cc < 1) { fatal.add("cluster count $cc"); return }
        if (cc != minOf((volumeLength - heap) / spc, 0xFFFFFFF5L)) minor.add("cluster count $cc is not the most that fit (${(volumeLength - heap) / spc})")
        if (rootCluster < 2 || rootCluster > cc + 1) { fatal.add("root cluster $rootCluster out of range"); return }
        if (fat(0) != 0xFFFFFFF8L) fatal.add("FAT entry 0 is ${fat(0).toString(16)}")
        if (fat(1) != 0xFFFFFFFFL) fatal.add("FAT entry 1 is ${fat(1).toString(16)}")

        rootRuns = chainRuns(rootCluster, "root folder")
        claim(rootRuns, "root folder")
        val root = readRuns(rootRuns)

        // The bitmap and up-case table first; names can't be checked without the table.
        var bitmaps = 0
        var upcases = 0
        var labels = 0
        var o = 0
        var bitmap = ByteArray(0)
        while (o + 32 <= root.size) {
            val t = root[o].toInt() and 0xFF
            if (t == 0) break
            when (t) {
                0x81 -> if (bitmaps++ == 0) {
                    bitmapEntryAt = offsetIn(rootRuns, o.toLong())
                    if (root[o + 1].toInt() and 1 != 0) fatal.add("bitmap entry is marked as the second bitmap")
                    val first = bvLe32(root, o + 20)
                    bitmapSize = bvLe64(root, o + 24)
                    bitmapRuns = chainRuns(first, "allocation bitmap")
                    if (bitmapSize != (cc + 7) / 8) fatal.add("bitmap is $bitmapSize bytes, $cc clusters need ${(cc + 7) / 8}")
                    if (bitmapRuns.sumOf { it[1] } != (bitmapSize + cb - 1) / cb) fatal.add("bitmap chain length doesn't match its size")
                    claim(bitmapRuns, "allocation bitmap")
                    val all = readRuns(bitmapRuns)
                    bitmap = all.copyOf(minOf(all.size.toLong(), maxOf(0L, bitmapSize)).toInt())
                }
                0x82 -> if (upcases++ == 0) {
                    upcaseEntryAt = offsetIn(rootRuns, o.toLong())
                    val first = bvLe32(root, o + 20)
                    val size = bvLe64(root, o + 24)
                    upcaseRuns = chainRuns(first, "up-case table")
                    if (upcaseRuns.sumOf { it[1] } != (size + cb - 1) / cb) fatal.add("up-case chain length doesn't match its size")
                    claim(upcaseRuns, "up-case table")
                    val table = readRuns(upcaseRuns).copyOf(size.toInt())
                    var ts = 0
                    for (x in table) ts = (if (ts and 1 != 0) 0x80000000.toInt() else 0) + (ts ushr 1) + (x.toInt() and 0xFF)
                    if ((ts.toLong() and 0xFFFFFFFFL) != bvLe32(root, o + 4)) fatal.add("up-case table checksum wrong")
                    var c = 0
                    var i = 0
                    while (i + 2 <= table.size && c < 0x10000) {
                        val v = bvLe16(table, i); i += 2
                        if (v == 0xFFFF && i + 2 <= table.size) { c += bvLe16(table, i); i += 2 } else upcase[c++] = v.toChar()
                    }
                    if (c != 0x10000) fatal.add("up-case table covers $c characters")
                }
                0x83 -> {
                    labels++
                    labelEntryAt = offsetIn(rootRuns, o.toLong())
                    val n = root[o + 1].toInt() and 0xFF
                    if (n > 11) fatal.add("label length $n") else label = String(CharArray(n) { bvLe16(root, o + 2 + it * 2).toChar() })
                }
            }
            o += 32
        }
        if (bitmaps != 1) fatal.add("$bitmaps allocation bitmap entries")
        if (upcases != 1) fatal.add("$upcases up-case table entries")
        if (labels > 1) fatal.add("$labels volume labels")

        parseDir(root, rootRuns, "", 0)

        alloc = BitSet.valueOf(bitmap)
        if (alloc.length() > cc) {
            minor.add("bitmap has bits set past the last cluster")
            alloc.clear(cc.toInt(), alloc.length())
        }
        val leak = alloc.clone() as BitSet
        leak.andNot(owner)
        leaked = leak.cardinality().toLong()
        val bad = owner.clone() as BitSet
        bad.andNot(alloc)
        if (!bad.isEmpty) fatal.add("cluster ${bad.nextSetBit(0) + 2} (and ${bad.cardinality() - 1} more) is in use by a file or folder but free in the bitmap")
    }

    private fun parseDir(bytes: ByteArray, runs: List<LongArray>, path: String, depth: Int) {
        val seen = HashSet<String>()
        var o = 0
        while (o + 32 <= bytes.size) {
            val t = bytes[o].toInt() and 0xFF
            if (t == 0) {
                for (i in o until bytes.size) if (bytes[i] != 0.toByte()) { fatal.add("folder '$path': data after the end-of-folder marker, at byte $i"); break }
                return
            }
            if (t and 0x80 == 0) { o += 32; continue }
            when (t) {
                0x81, 0x82, 0x83, 0xA0 -> {
                    if (path.isNotEmpty()) fatal.add("folder '$path': root-only entry type ${t.toString(16)}")
                    o += 32
                }
                0xC0, 0xC1 -> {
                    torn.add("folder '$path': in-use stream or name entry with no file entry before it, at byte $o")
                    o += 32
                }
                0x85 -> o = parseSet(bytes, runs, path, depth, o, seen)
                else -> {
                    fatal.add("folder '$path': unknown entry type ${t.toString(16)} at byte $o")
                    o += 32
                }
            }
        }
    }

    private fun parseSet(bytes: ByteArray, runs: List<LongArray>, path: String, depth: Int, o: Int, seen: HashSet<String>): Int {
        val where = "folder '$path' entry at byte $o"
        val count = bytes[o + 1].toInt() and 0xFF
        if (count < 2 || count > 18) { torn.add("$where: secondary count $count"); return o + 32 }
        if (o + (count + 1) * 32 > bytes.size) { torn.add("$where: entry set runs past the end of the folder"); return o + 32 }
        if ((bytes[o + 32].toInt() and 0xFF) != 0xC0) { torn.add("$where: file entry not followed by a stream entry"); return o + 32 }
        for (k in 2..count) if ((bytes[o + k * 32].toInt() and 0xFF) != 0xC1) { torn.add("$where: entry set is missing its name entries"); return o + 32 }
        val set = bytes.copyOfRange(o, o + (count + 1) * 32)
        if (bvSetChecksum(set) != bvLe16(set, 2)) fatal.add("$where: entry set checksum wrong")
        val isDir = bvLe16(set, 4) and 0x10 != 0
        val flags = set[33].toInt()
        val nameLen = set[35].toInt() and 0xFF
        val vdl = bvLe64(set, 40)
        val first = bvLe32(set, 52)
        val size = bvLe64(set, 56)
        if (nameLen == 0 || (nameLen + 14) / 15 != count - 1) fatal.add("$where: name length $nameLen with ${count - 1} name entries")
        val sb = StringBuilder()
        for (k in 2..count) for (j in 0 until 15) if (sb.length < nameLen) sb.append(bvLe16(set, k * 32 + 2 + j * 2).toChar())
        val name = sb.toString()
        var h = 0
        for (ch in name) {
            val u = upcase[ch.code].code
            h = ((if (h and 1 != 0) 0x8000 else 0) + (h ushr 1) + (u and 0xFF)) and 0xFFFF
            h = ((if (h and 1 != 0) 0x8000 else 0) + (h ushr 1) + (u ushr 8)) and 0xFFFF
        }
        if (h != bvLe16(set, 36)) fatal.add("$where ($name): name hash wrong")
        if (name.any { it.code < 0x20 || it in "\"*/:<>?\\|" }) fatal.add("$where: illegal character in name")
        if (flags and 1 == 0) fatal.add("$where ($name): AllocationPossible is clear")
        val noFat = flags and 2 != 0
        if (size < 0 || vdl > size) fatal.add("$where ($name): valid length $vdl, length $size")
        val full = if (path.isEmpty()) name else "$path/$name"
        if (!seen.add(String(CharArray(name.length) { upcase[name[it].code] }))) fatal.add("folder '$path': two entries named $name")
        var fileRuns: List<LongArray> = emptyList()
        var ok = true
        if (first == 0L) {
            if (size != 0L) fatal.add("$full: $size bytes but no first cluster")
            if (noFat) fatal.add("$full: NoFatChain set on a file with no clusters")
            if (isDir) fatal.add("$full: folder with no cluster")
        } else {
            val n = (size + cb - 1) / cb
            if (size == 0L) fatal.add("$full: has a first cluster but no length")
            if (isDir && size % cb != 0L) fatal.add("$full: folder length $size is not whole clusters")
            if (noFat) {
                fileRuns = listOf(longArrayOf(first, maxOf(1L, n)))
            } else {
                fileRuns = chainRuns(first, full)
                val got = fileRuns.sumOf { it[1] }
                if (got != n) fatal.add("$full: $size bytes need $n clusters but its FAT chain has $got")
            }
            if (!claim(fileRuns, full)) ok = false
        }
        nodes[full] = Node(full, name, isDir, size, first, noFat, fileRuns, LongArray(count + 1) { offsetIn(runs, (o + it * 32).toLong()) })
        if (isDir && first != 0L && ok && depth < 4) parseDir(readRuns(fileRuns), fileRuns, full, depth + 1)
        return o + (count + 1) * 32
    }

    /** First cluster of the first run of [n] free clusters, straight from the bitmap. */
    fun firstFit(n: Long, bits: BitSet = alloc): Long? {
        var i = bits.nextClearBit(0)
        while (i < cc) {
            var j = bits.nextSetBit(i)
            if (j < 0 || j > cc) j = cc.toInt()
            if (j - i >= n) return i + 2L
            i = bits.nextClearBit(j)
        }
        return null
    }

    /** First cluster and length of the first longest free run. */
    fun largest(): Pair<Long, Long> {
        var best = 0L
        var bestStart = 0L
        var i = alloc.nextClearBit(0)
        while (i < cc) {
            var j = alloc.nextSetBit(i)
            if (j < 0 || j > cc) j = cc.toInt()
            if (j - i > best) { best = (j - i).toLong(); bestStart = i.toLong() }
            i = alloc.nextClearBit(j)
        }
        return (bestStart + 2) to best
    }
}

/**
 * Rewrites a file the way another computer might: its data moved to [clusters] (any order),
 * linked through the FAT, NoFatChain cleared, the bitmap and entry set updated.
 */
private fun bvRelocate(disk: BvDisk, part: Long, k: BvFsck, node: BvFsck.Node, data: ByteArray, clusters: LongArray) {
    val cb = k.cb
    val base = part * 512
    val padded = data.copyOf(clusters.size * cb)
    for ((i, c) in clusters.withIndex()) disk.poke(base + k.sectorOf(c) * 512, padded.copyOfRange(i * cb, (i + 1) * cb))
    val four = ByteArray(4)
    for ((i, c) in clusters.withIndex()) {
        val v = if (i == clusters.lastIndex) 0xFFFFFFFFL else clusters[i + 1]
        ByteBuffer.wrap(four).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt())
        disk.poke(base + k.fatOffset * 512 + c * 4, four)
    }
    fun bit(c: Long, on: Boolean) {
        val at = base + k.offsetIn(k.bitmapRuns, (c - 2) / 8)
        val b = disk.bytes(at, 1)
        val m = 1 shl ((c - 2) % 8).toInt()
        b[0] = (if (on) b[0].toInt() or m else b[0].toInt() and m.inv()).toByte()
        disk.poke(at, b)
    }
    for (run in node.runs) for (c in run[0] until run[0] + run[1]) bit(c, false)
    for (c in clusters) bit(c, true)
    val set = ByteArray(node.entryAt.size * 32)
    node.entryAt.forEachIndexed { i, off -> disk.bytes(base + off, 32).copyInto(set, i * 32) }
    set[33] = 0x01
    val bb = ByteBuffer.wrap(set).order(ByteOrder.LITTLE_ENDIAN)
    bb.putInt(52, clusters[0].toInt())
    bb.putShort(2, bvSetChecksum(set).toShort())
    node.entryAt.forEachIndexed { i, off -> disk.poke(base + off, set.copyOfRange(i * 32, i * 32 + 32)) }
}

/** Formats a volume of at least [want] clusters at [part] on a new disk. */
private fun bvFormat(want: Long, spc: Int, part: Long = 2048, extra: Int = 0, fill: Byte = 0, softEnd: Boolean = false, pad: Int = 0): Pair<BvDisk, ExFat.Layout> {
    var vs = maxOf(128, spc) * 2L + want * spc + pad
    while (true) {
        val l = try { ExFat.layout(part, vs, spc) } catch (e: IllegalArgumentException) { null }
        if (l != null && l.clusterCount >= want) break
        vs += spc
    }
    val disk = BvDisk(part + vs + extra, fill, softEnd)
    val l = ExFat.format(disk, part, vs, "BREAK", spc, serial = 0x1234)
    return disk to l
}

// ------------------------------------------------------------------------------------ random operations

private typealias BvFiles = TreeMap<String, Pair<String, ByteArray>>

/** One volume, one [ExFatVolume] instance, a model of what should be on it, and the checker run after every step. */
private class BvHarness(val seed: Long, val spc: Int, maxClusters: Int) {
    val r = Random(seed)
    val part = longArrayOf(0L, 63L, 2048L)[r.nextInt(3)]
    val disk: BvDisk
    var vol: ExFatVolume
    val rootFiles = BvFiles()
    val dirs = TreeMap<String, Pair<String, BvFiles>>()
    var k: BvFsck
    val log = ArrayDeque<String>()
    val cb = spc * 512
    var checkEvery = 1
    private var step = 0

    init {
        val (d, _) = bvFormat(17L + r.nextInt(maxClusters), spc, part, r.nextInt(100), if (r.nextInt(4) == 0) 0x5A else 0, pad = r.nextInt(spc))
        disk = d
        vol = ExFatVolume.open(disk, part)
        k = BvFsck(disk, part).run()
        k = check("format")
    }

    fun ctx() = "seed=$seed spc=$spc clusters=${k.cc} last ops=[${log.joinToString("; ")}]"

    fun note(op: String) {
        log.addLast(op)
        if (log.size > 8) log.removeFirst()
    }

    /** Runs a volume call; an IOException is returned, anything else thrown is a failure. */
    fun <T> io(what: String, f: () -> T): Pair<T?, IOException?> = try {
        f() to null
    } catch (e: IOException) {
        if (e.message.isNullOrBlank()) fail("${ctx()}: $what threw an IOException with no message")
        null to e
    } catch (e: AssertionError) {
        throw e
    } catch (e: Throwable) {
        throw AssertionError("${ctx()}: $what threw $e at ${e.stackTrace.take(4).joinToString(" < ")}", e)
    }

    fun flipCase(s: String) = String(CharArray(s.length) { if (r.nextBoolean()) Character.toLowerCase(s[it]).let { c -> if (bvUp(c) == bvUp(s[it])) c else s[it] } else s[it] })

    fun newName(): String {
        val stems = listOf(
            "a", "B", "image", "Ubuntu 24.04 desktop", "ſtraße", "Éclair", "x".repeat(15), "y".repeat(16),
            "long name ".repeat(12), "z".repeat(251), "q".repeat(249), "emoji\uD83D\uDE00", "dot.in.name", "config",
        )
        val s = stems[r.nextInt(stems.size)] + (if (r.nextInt(3) == 0) "" else "-" + r.nextInt(5))
        return flipCase(s) + listOf(".iso", ".img", ".bin", "")[r.nextInt(4)]
    }

    fun newSize(): Int {
        val many = if (cb <= 4096) 2 + r.nextInt(40) else 2 + r.nextInt(5)
        return when (r.nextInt(12)) {
            0 -> 0
            1 -> 1
            2 -> cb - 1
            3 -> cb
            4 -> cb + 1
            5 -> 2 * cb
            6 -> many * cb
            7 -> many * cb - 1
            8 -> many * cb + 1
            9 -> 511 + r.nextInt(3)
            else -> r.nextInt(many * cb)
        }
    }

    fun data(size: Int) = ByteArray(size).also { r.nextBytes(it) }

    fun clustersOf(size: Long) = (size + cb - 1) / cb

    /** Full check of the device and of the open instance against the model. */
    fun check(op: String): BvFsck {
        note(op)
        step++
        val f = BvFsck(disk, part).run()
        if (f.problems.isNotEmpty()) fail("${ctx()}: checker: ${f.problems.take(4)}")
        k = f
        if (checkEvery > 1 && step % checkEvery != 0) return f
        val want = HashMap<String, ByteArray?>()
        for ((_, v) in rootFiles) want[v.first] = v.second
        for ((_, d) in dirs) {
            want[d.first] = null
            for ((_, v) in d.second) want[d.first + "/" + v.first] = v.second
        }
        assertEquals("${ctx()}: paths on the disk", want.keys.toSortedSet(), f.nodes.keys.toSortedSet())
        var fileClusters = 0L
        for ((p, bytes) in want) {
            val n = f.nodes.getValue(p)
            assertEquals("${ctx()}: $p is a folder", bytes == null, n.isDir)
            if (bytes == null) continue
            assertEquals("${ctx()}: size of $p", bytes.size.toLong(), n.size)
            if (!f.read(n).contentEquals(bytes)) fail("${ctx()}: $p has the wrong bytes on the disk")
            fileClusters += n.clusters
            val (got, e) = io("readFile($p)") { vol.readFile(flipCase(p), Int.MAX_VALUE) }
            if (e != null) fail("${ctx()}: readFile($p) failed: ${e.message}")
            if (got == null) fail("${ctx()}: readFile($p) found nothing")
            if (!got!!.contentEquals(bytes)) fail("${ctx()}: readFile($p) returned the wrong bytes (${got.size} of ${bytes.size})")
        }
        assertEquals("${ctx()}: clusters held by files", want.values.filterNotNull().sumOf { clustersOf(it.size.toLong()) }, fileClusters)
        val (listed, le) = io("entries(root)") { vol.entries(null) }
        if (le != null) fail("${ctx()}: entries(root) failed: ${le.message}")
        assertEquals(
            "${ctx()}: root listing",
            (rootFiles.values.map { it.first + ":" + it.second.size } + dirs.values.map { it.first + "/" }).sorted(),
            listed!!.map { if (it.isDir) it.name + "/" else it.name + ":" + it.size }.sorted(),
        )
        assertEquals("${ctx()}: files()", rootFiles.values.map { it.first }.sorted(), vol.files().map { it.name }.sorted())
        for ((_, d) in dirs) {
            val dir = vol.lookup(flipCase(d.first))
            if (dir == null || !dir.isDir) fail("${ctx()}: lookup(${d.first}) didn't find the folder")
            assertEquals(
                "${ctx()}: listing of ${d.first}",
                d.second.values.map { it.first + ":" + it.second.size }.sorted(),
                vol.entries(dir).map { it.name + ":" + it.size }.sorted(),
            )
        }
        assertEquals("${ctx()}: freeClusters()", f.free, vol.freeClusters())
        assertEquals("${ctx()}: freeBytes", f.free * cb, vol.freeBytes)
        assertEquals("${ctx()}: largestFreeRun()", f.largest().second * cb, vol.largestFreeRun())
        assertEquals("${ctx()}: label", "BREAK", vol.label)
        return f
    }

    fun pickDir(): Pair<String, BvFiles>? = if (dirs.isEmpty()) null else dirs.values.toList()[r.nextInt(dirs.size)]

    fun pickFile(): Triple<Pair<String, BvFiles>?, String, Pair<String, ByteArray>>? {
        val all = ArrayList<Triple<Pair<String, BvFiles>?, String, Pair<String, ByteArray>>>()
        for ((key, v) in rootFiles) all.add(Triple(null, key, v))
        for ((_, d) in dirs) for ((key, v) in d.second) all.add(Triple(d, key, v))
        return if (all.isEmpty()) null else all[r.nextInt(all.size)]
    }

    fun taken(dir: Pair<String, BvFiles>?, key: String) =
        if (dir == null) rootFiles.containsKey(key) || dirs.containsKey(key) else dir.second.containsKey(key)

    fun opAdd(streaming: Boolean, forceExisting: Boolean = false) {
        val dir = if (r.nextInt(4) == 0) pickDir() else null
        var name = newName()
        if (forceExisting) {
            val names = if (dir == null) rootFiles.values.map { it.first } + dirs.values.map { it.first } else dir.second.values.map { it.first }
            if (names.isEmpty()) return
            name = flipCase(names[r.nextInt(names.size)])
        }
        val stored = ExFatVolume.cleanName(name)
        val key = bvKey(stored)
        val bytes = data(newSize())
        val n = clustersOf(bytes.size.toLong())
        val exists = taken(dir, key)
        val pre = k
        val what = (if (streaming) "addStreaming" else "add") + "(${dir?.first ?: ""}/${name.take(30)}${if (name.length > 30) "..(${name.length})" else ""}, ${bytes.size})"
        note(what)
        val folder = dir?.let { vol.lookup(it.first) }
        var hash: ByteArray? = null
        val (_, e) = io(what) {
            if (streaming) {
                val (len, h) = vol.addStreaming(name, BvDribble(bytes, r), { }, { false }, folder)
                assertEquals("${ctx()}: addStreaming length", bytes.size.toLong(), len)
                hash = h
            } else {
                hash = vol.add(name, bytes.size.toLong(), BvDribble(bytes, r), { _, _ -> }, { false }, folder)
            }
        }
        if (exists) {
            if (e == null) fail("${ctx()}: $what succeeded though the name is taken")
            check("$what -> refused, name taken")
            return
        }
        val (bigStart, bigLen) = pre.largest()
        val fit = if (streaming) (if (bytes.size <= bigLen * cb) bigStart else null) else pre.firstFit(n)
        if (e != null) {
            val folderGrowth = pre.free == n || (cb == 512 && stored.length > 195 && e.message!!.contains("make room"))
            if (n > 0 && fit != null && !folderGrowth) fail("${ctx()}: $what refused (${e.message}) though clusters $fit+$n were free")
            if (n == 0L && pre.free != 0L && !folderGrowth) fail("${ctx()}: $what refused (${e.message}) though an empty file needs no cluster and ${pre.free} are free")
            check("$what -> refused: ${e.message!!.take(40)}")
            return
        }
        if (n > 0 && fit == null) fail("${ctx()}: $what succeeded though no run of $n free clusters existed")
        assertArrayEquals("${ctx()}: hash returned by $what", bvSha(bytes), hash)
        (dir?.second ?: rootFiles)[key] = stored to bytes
        val f = check("$what ok")
        val node = f.nodes.getValue(if (dir == null) stored else dir.first + "/" + stored)
        if (n == 0L) {
            if (node.first != 0L || node.noFatChain) fail("${ctx()}: empty file $stored has first cluster ${node.first}, NoFatChain ${node.noFatChain}")
        } else {
            if (!node.noFatChain) fail("${ctx()}: $stored was not written as a contiguous NoFatChain file")
            assertEquals("${ctx()}: first cluster of $stored (first fit / longest run)", fit, node.first)
        }
        if (r.nextInt(4) == 0) {
            val info = vol.lookup(if (dir == null) stored else dir.first + "/" + stored)!!
            val (_, ve) = io("verify") { vol.verify(info, hash!!, { _, _ -> }, { false }) }
            if (ve != null) fail("${ctx()}: verify of $stored failed: ${ve.message}")
            val wrong = hash!!.copyOf().also { it[5] = (it[5] + 1).toByte() }
            val (_, we) = io("verify with a wrong hash") { vol.verify(info, wrong, { _, _ -> }, { false }) }
            if (we !is WriteException) fail("${ctx()}: verify accepted a wrong hash for $stored")
        }
    }

    fun opWriteFile() {
        val names = listOf("ventoy.json", "Config", "notes.txt", "a.cfg", "image")
        val dirNames = listOf("ventoy", "Data", "image", "a.cfg")
        val name = flipCase(names[r.nextInt(names.size)])
        val dirName = if (r.nextInt(3) == 0) null else flipCase(dirNames[r.nextInt(dirNames.size)])
        val bytes = data(if (r.nextBoolean()) r.nextInt(3 * cb + 1) else r.nextInt(300))
        val n = clustersOf(bytes.size.toLong())
        val path = if (dirName == null) name else "$dirName/$name"
        val what = "writeFile($path, ${bytes.size})"
        note(what)
        val pre = k
        val dirIsFile = dirName != null && rootFiles.containsKey(bvKey(dirName))
        val dirEntry = dirName?.let { dirs[bvKey(it)] }
        val targetIsDir = dirName == null && dirs.containsKey(bvKey(name))
        val (_, e) = io(what) { vol.writeFile(path, bytes) }
        if (dirIsFile || targetIsDir) {
            if (e == null) fail("${ctx()}: $what succeeded though ${if (dirIsFile) "its folder is a file" else "the name is a folder"}")
            check("$what -> refused")
            return
        }
        if (e != null) {
            if (pre.free > n + 2 && pre.firstFit(n + 1) != null) fail("${ctx()}: $what failed (${e.message}) with ${pre.free} clusters free")
            // Not atomic: the old file may be gone and the folder may have been made.
            val now = BvFsck(disk, part).run()
            if (dirName != null && dirEntry == null) now.nodes.values.firstOrNull { it.isDir && bvKey(it.name) == bvKey(dirName) }?.let { dirs[bvKey(dirName)] = it.name to BvFiles() }
            val files = if (dirName == null) rootFiles else dirs[bvKey(dirName)]?.second
            val old = files?.get(bvKey(name))
            if (old != null) {
                val p = if (dirName == null) old.first else dirs[bvKey(dirName)]!!.first + "/" + old.first
                if (!now.nodes.containsKey(p)) files.remove(bvKey(name))
            }
            check("$what -> failed: ${e.message!!.take(40)}")
            return
        }
        val files = if (dirName == null) rootFiles else (dirEntry ?: (dirName to BvFiles()).also { dirs[bvKey(dirName)] = it }).second
        files[bvKey(name)] = name to bytes
        check("$what ok")
    }

    fun opDelete() {
        val (dir, key, v) = pickFile() ?: return
        val path = if (dir == null) v.first else dir.first + "/" + v.first
        val byPath = r.nextBoolean()
        val what = (if (byPath) "deleteFile" else "delete") + "($path)"
        note(what)
        val (_, e) = io(what) {
            if (byPath) assertTrue("${ctx()}: deleteFile said the file wasn't there", vol.deleteFile(flipCase(path)))
            else vol.delete(vol.lookup(path) ?: throw AssertionError("${ctx()}: lookup($path) found nothing"))
        }
        if (e != null) fail("${ctx()}: $what failed: ${e.message}")
        (dir?.second ?: rootFiles).remove(key)
        check("$what ok")
        assertFalse("${ctx()}: deleteFile of a missing file", vol.deleteFile(path))
        if (dirs.isNotEmpty()) assertFalse("${ctx()}: deleteFile of a folder", vol.deleteFile(dirs.values.first().first))
    }

    fun opMkdir() {
        val name = flipCase(listOf("ventoy", "Data", "folder " + r.nextInt(4), "image", "B")[r.nextInt(5)])
        val key = bvKey(name)
        val exists = taken(null, key)
        val what = "createFolder($name)"
        note(what)
        val pre = k
        val (info, e) = io(what) { vol.createFolder(name) }
        if (exists) {
            if (e == null) fail("${ctx()}: $what succeeded though the name is taken")
        } else if (e != null) {
            if (pre.free > 1) fail("${ctx()}: $what failed (${e.message}) with ${pre.free} clusters free")
        } else {
            assertTrue("${ctx()}: createFolder returned a file", info!!.isDir)
            dirs[key] = name to BvFiles()
        }
        check("$what ${if (e == null) "ok" else "refused"}")
    }

    fun opReopen() {
        vol = ExFatVolume.open(disk, part)
        check("reopen")
    }

    /** Moves a file to scattered clusters with a FAT chain, then reopens. */
    fun opFragment() {
        val (dir, _, v) = pickFile() ?: return
        if (v.second.isEmpty()) return
        val path = if (dir == null) v.first else dir.first + "/" + v.first
        val node = k.nodes.getValue(path)
        val n = node.clusters.toInt()
        if (n > 600) return
        val pool = ArrayList<Long>()
        for (run in node.runs) for (c in run[0] until run[0] + run[1]) pool.add(c)
        var i = k.alloc.nextClearBit(0)
        while (i < k.cc && pool.size < 4000) { pool.add(i + 2L); i = k.alloc.nextClearBit(i + 1) }
        java.util.Collections.shuffle(pool, r)
        var chosen = pool.take(n)
        when (r.nextInt(3)) {
            0 -> chosen = chosen.sorted()
            1 -> chosen = chosen.sortedDescending()
        }
        bvRelocate(disk, part, k, node, v.second, chosen.toLongArray())
        vol = ExFatVolume.open(disk, part)
        val f = check("fragment($path into $n clusters)")
        val info = vol.lookup(path)!!
        if (info.contiguous) fail("${ctx()}: $path still reported contiguous after being chained")
        val (_, ve) = io("verify of a chained file") { vol.verify(info, bvSha(v.second), { _, _ -> }, { false }) }
        if (ve != null) fail("${ctx()}: verify of chained $path failed: ${ve.message}")
        assertEquals("${ctx()}: clusters of chained $path", n.toLong(), f.nodes.getValue(path).clusters)
    }

    fun opMisfit() {
        val sizes = List(r.nextInt(5)) { if (r.nextInt(6) == 0) 0L else (1 + r.nextInt(maxOf(1, (k.free * cb).toInt().coerceIn(1, 80 * cb)))).toLong() }
        val infos = vol.files().filter { r.nextInt(3) == 0 }
        val bits = k.alloc.clone() as BitSet
        for (f in infos) for (run in k.nodes.getValue(f.name).runs) bits.clear((run[0] - 2).toInt(), (run[0] - 2 + run[1]).toInt())
        var expect: Long? = null
        for (s in sizes) {
            if (s == 0L) continue
            val n = clustersOf(s)
            val at = k.firstFit(n, bits)
            if (at == null) { expect = s; break }
            bits.set((at - 2).toInt(), (at - 2 + n).toInt())
        }
        val what = "firstMisfit($sizes, removing ${infos.size})"
        note(what)
        val (got, e) = io(what) { vol.firstMisfit(sizes, infos) }
        if (e != null) fail("${ctx()}: $what failed: ${e.message}")
        assertEquals("${ctx()}: $what", expect, got)
        check("$what = $got")
    }

    fun step() {
        val x = r.nextInt(100)
        when {
            x < 30 -> opAdd(false)
            x < 42 -> opAdd(true)
            x < 46 -> opAdd(r.nextBoolean(), forceExisting = true)
            x < 58 -> opWriteFile()
            x < 78 -> opDelete()
            x < 82 -> opMkdir()
            x < 86 -> opReopen()
            x < 93 -> opFragment()
            else -> opMisfit()
        }
    }
}

/** Runs [f] for each seed and fails with the first few distinct failures, each carrying its seed. */
private fun bvSeeds(seeds: LongRange, f: (Long) -> Unit) {
    val failures = ArrayList<String>()
    var count = 0
    for (s in seeds) {
        try {
            f(s)
        } catch (e: AssertionError) {
            count++
            if (failures.size < 4) failures.add(e.message ?: e.toString())
        } catch (e: Throwable) {
            count++
            if (failures.size < 4) failures.add("seed=$s: $e at ${e.stackTrace.take(5).joinToString(" < ")}")
        }
    }
    if (count > 0) fail("$count of ${seeds.count()} seeds failed. First ones:\n" + failures.joinToString("\n"))
}

/** Runs [f] on its own thread; null when it returns or throws an IOException with a message, else what went wrong. */
private fun bvProbe(seconds: Long, f: () -> Unit): String? {
    val ex = Executors.newSingleThreadExecutor { r -> Thread(r).also { it.isDaemon = true } }
    try {
        val fut = ex.submit(Callable<String?> {
            try {
                f()
                null
            } catch (e: IOException) {
                if (e.message.isNullOrBlank()) "IOException with no message at ${e.stackTrace.take(3).joinToString(" < ")}" else null
            } catch (e: Throwable) {
                "$e at ${e.stackTrace.take(3).joinToString(" < ")}"
            }
        })
        return try {
            fut.get(seconds, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            fut.cancel(true)
            "hang: no answer after $seconds seconds"
        }
    } finally {
        ex.shutdownNow()
    }
}

private val bvVentoyFiles: Ventoy.Files by lazy { VentoyTestPackage.files(true) }

class BreakVolumeTest {

    // -------------------------------------------------------------------------------- 2. random sequences

    private fun randomRun(seeds: LongRange, spcs: IntArray, maxClusters: (Int) -> Int, ops: Int) = bvSeeds(seeds) { seed ->
        val spc = spcs[(seed % spcs.size).toInt()]
        val h = BvHarness(seed, spc, maxClusters(spc))
        repeat(ops) { h.step() }
        h.opReopen()
    }

    @Test(timeout = 120_000)
    fun randomOperations512ByteClusters() = randomRun(1000L..1079L, intArrayOf(1), { 6000 }, 45)

    @Test(timeout = 120_000)
    fun randomOperationsSmallClusters() = randomRun(2000L..2089L, intArrayOf(2, 4, 8, 16), { 3000 / it }, 45)

    @Test(timeout = 120_000)
    fun randomOperationsLargeClusters() = randomRun(3000L..3079L, intArrayOf(32, 64, 128, 256), { 6000 / it + 20 }, 40)

    @Test(timeout = 120_000)
    fun randomOperationsOnNearlyFullTinyVolumes() = randomRun(4000L..4059L, intArrayOf(1, 2, 8, 64), { 40 }, 60)

    @Test(timeout = 120_000)
    fun hundredsOfFilesGrowRootAndFolderWithListingInBetween() = bvSeeds(5000L..5005L) { seed ->
        val spc = intArrayOf(1, 1, 2, 8, 64, 1)[(seed % 6).toInt()]
        val h = BvHarness(seed, spc, 3000)
        h.checkEvery = 9
        val r = h.r
        val folder = "ventoy"
        var made = 0
        repeat(330) { i ->
            when (r.nextInt(10)) {
                in 0..4 -> {
                    val name = "file $i " + "n".repeat(r.nextInt(40)) + ".cfg"
                    val bytes = h.data(if (r.nextInt(3) == 0) 0 else r.nextInt(2 * h.cb))
                    h.note("add($name)")
                    val (_, e) = h.io("add") { h.vol.add(name, bytes.size.toLong(), ByteArrayInputStream(bytes), { _, _ -> }, { false }) }
                    if (e == null) h.rootFiles[bvKey(name)] = name to bytes else if (h.k.free > 3 + bytes.size / h.cb) fail("${h.ctx()}: add failed: ${e.message}")
                    made++
                }
                in 5..7 -> {
                    val name = "Entry $i " + "m".repeat(r.nextInt(30)) + ".json"
                    val bytes = h.data(r.nextInt(900))
                    h.note("writeFile($folder/$name)")
                    val (_, e) = h.io("writeFile") { h.vol.writeFile("$folder/$name", bytes) }
                    if (e == null) h.dirs.getOrPut(bvKey(folder)) { folder to BvFiles() }.second[bvKey(name)] = name to bytes
                    else if (h.k.free > 4) fail("${h.ctx()}: writeFile failed: ${e.message}")
                }
                else -> if (made > 20) h.opDelete()
            }
            // Listing between changes: the cached root chain must follow every growth.
            val listed = h.vol.files().size
            if (listed != h.rootFiles.size) fail("${h.ctx()}: files() lists $listed, expected ${h.rootFiles.size}")
            h.check("step $i")
        }
        h.checkEvery = 1
        h.check("end")
        h.opReopen()
    }

    // Accepted: a folder grows one cluster at a time, and on 512-byte clusters (never used on Ventoy drives) that is less than the longest names need.
    @Test(timeout = 60_000)
    fun longNameThatNeedsMoreThanOneNewClusterIsRefusedCleanlyOn512ByteClusters() {
        val bad = ArrayList<String>()
        val refused = ArrayList<Int>()
        for (len in listOf(200, 226, 240, 255)) {
            val (disk, _) = bvFormat(500, 1, 2048)
            val vol = ExFatVolume.open(disk, 2048)
            // Fill the root's first cluster so the next entry set has to go into new clusters.
            for (i in 0 until 4) vol.add("f$i", 0, ByteArrayInputStream(ByteArray(0)), { _, _ -> }, { false })
            try {
                vol.add("n".repeat(len), 600, ByteArrayInputStream(ByteArray(600)), { _, _ -> }, { false })
            } catch (e: IOException) {
                refused.add(len)
                if (e.message != "Couldn't make room in the drive's folder.") bad.add("a $len-character name: ${e.message}")
                if (vol.find("n".repeat(len)) != null) bad.add("a refused $len-character name is listed")
            }
            assertEquals(emptyList<String>(), BvFsck(disk, 2048).run().problems)
        }
        if (bad.isNotEmpty()) fail(bad.joinToString("; "))
        assertEquals("name lengths refused", listOf(226, 240, 255), refused)
    }

    // -------------------------------------------------------------------------------- 3. fragmentation

    @Test(timeout = 120_000)
    fun chainsAcrossBitmapWordFatSectorAndFatWindowBoundaries() = bvSeeds(6000L..6011L) { seed ->
        val r = Random(seed)
        val want = 20000L + r.nextInt(30000) + (if (seed % 2 == 0L) 3 else 0)
        val (disk, l) = bvFormat(want, 1, 2048)
        var vol = ExFatVolume.open(disk, 2048)
        val cc = l.clusterCount
        fun ctx() = "seed=$seed clusters=$cc"
        val keep = HashMap<String, ByteArray>()
        for (i in 0 until 12) {
            val b = ByteArray(1 + r.nextInt(3000)).also { r.nextBytes(it) }
            keep["keep $i.bin"] = b
            vol.add("keep $i.bin", b.size.toLong(), ByteArrayInputStream(b), { _, _ -> }, { false })
        }
        for (i in 0 until 12 step 2) { vol.deleteFile("keep $i.bin"); keep.remove("keep $i.bin") }
        var k = BvFsck(disk, 2048).run()
        assertEquals("${ctx()}: fresh", emptyList<String>(), k.problems)

        // Chains built from clusters right at the boundaries, in several orders.
        fun freeNear(c: Long): List<Long> = (c - 2..c + 2).filter { it in 2..cc + 1 && !k.alloc.get((it - 2).toInt()) }
        val edges = ArrayList<Long>()
        for (b in listOf(64L, 128, 4096, 8192, 4096 * 2, 4096 * 3)) edges.addAll(freeNear(b + 2)) // bitmap word and bitmap sector edges
        for (b in listOf(128L, 256, 1024, 2048, 8192, 16384, 8192 * 3)) edges.addAll(freeNear(b)) // FAT sector, 8 and 64 sector windows
        edges.addAll(freeNear(cc + 1))
        edges.addAll(freeNear(cc - 62))
        val distinct = edges.distinct()
        val orders = listOf(
            distinct.sorted(), distinct.sortedDescending(), distinct.shuffled(r),
            (distinct.sorted().filterIndexed { i, _ -> i % 2 == 0 } + distinct.sortedDescending().filterIndexed { i, _ -> i % 2 == 0 }).distinct(),
        )
        val scattered = ArrayList<Long>()
        var i = k.alloc.nextClearBit(0)
        while (i < cc) { if (r.nextInt(5) == 0) scattered.add(i + 2L); i = k.alloc.nextClearBit(i + 1) }
        val all = orders + listOf(scattered.shuffled(r).take(3500), scattered.take(5000), scattered.reversed().take(2000))
        for ((n, order) in all.withIndex()) {
            val name = "chained $n.img"
            val bytes = ByteArray(order.size * 512 - r.nextInt(512)).also { r.nextBytes(it) }
            vol.add(name, bytes.size.toLong(), ByteArrayInputStream(bytes), { _, _ -> }, { false })
            k = BvFsck(disk, 2048).run()
            bvRelocate(disk, 2048, k, k.nodes.getValue(name), bytes, order.toLongArray())
            vol = ExFatVolume.open(disk, 2048)
            k = BvFsck(disk, 2048).run()
            assertEquals("${ctx()}: after chaining $name", emptyList<String>(), k.problems)
            val info = vol.find(name) ?: throw AssertionError("${ctx()}: $name not found")
            assertFalse("${ctx()}: $name contiguous", info.contiguous)
            if (!vol.readFile(name, Int.MAX_VALUE)!!.contentEquals(bytes)) fail("${ctx()}: chained file $n (order ${order.take(12)}...) read back wrong")
            vol.verify(info, bvSha(bytes), { _, _ -> }, { false })
            assertEquals("${ctx()}: free with $name", k.free, vol.freeClusters())
            assertEquals("${ctx()}: largest run with $name", k.largest().second * 512, vol.largestFreeRun())
            // Removing it in a dry run must free exactly its clusters.
            val bits = k.alloc.clone() as BitSet
            for (c in order) bits.clear((c - 2).toInt())
            val probe = (1 + r.nextInt(3000)).toLong()
            assertEquals("${ctx()}: firstMisfit removing $name", if (k.firstFit(probe, bits) == null) probe * 512 else null, vol.firstMisfit(listOf(probe * 512), listOf(info)))
            val freeBefore = k.free
            run {
                vol.delete(info)
                k = BvFsck(disk, 2048).run()
                assertEquals("${ctx()}: after deleting chained file $n (order ${order.take(12)}...)", emptyList<String>(), k.problems)
                assertEquals("${ctx()}: clusters freed by deleting $name", freeBefore + order.size, k.free)
                assertEquals("${ctx()}: freeClusters after delete", k.free, vol.freeClusters())
                assertEquals("${ctx()}: fresh open after delete", k.free, ExFatVolume.open(disk, 2048).freeClusters())
            }
            for ((p, b) in keep) if (!vol.readFile(p, Int.MAX_VALUE)!!.contentEquals(b)) fail("${ctx()}: $p damaged after working on $name")
        }
        // New contiguous files go in around the chained ones that are left.
        for (j in 0 until 6) {
            val bytes = ByteArray(r.nextInt(200) * 512 + 1).also { r.nextBytes(it) }
            val fit = k.firstFit((bytes.size + 511L) / 512)
            val out = runCatching { vol.add("after $j.bin", bytes.size.toLong(), ByteArrayInputStream(bytes), { _, _ -> }, { false }) }
            assertEquals("${ctx()}: add after $j", fit != null, out.isSuccess)
            k = BvFsck(disk, 2048).run()
            assertEquals("${ctx()}: after add $j", emptyList<String>(), k.problems)
            if (fit != null) assertEquals("${ctx()}: first fit", fit, k.nodes.getValue("after $j.bin").first)
        }
    }

    @Test(timeout = 120_000)
    fun volumeBecomesExactlyFullAndOneClusterShortFailsCleanly() = bvSeeds(7000L..7023L) { seed ->
        val r = Random(seed)
        val spc = intArrayOf(1, 2, 8, 64)[(seed % 4).toInt()]
        val cb = spc * 512
        val (disk, l) = bvFormat(17L + r.nextInt(if (spc == 1) 9000 else 700), spc, 2048)
        val vol = ExFatVolume.open(disk, 2048)
        fun ctx() = "seed=$seed spc=$spc clusters=${l.clusterCount}"
        fun clean(what: String): BvFsck = BvFsck(disk, 2048).run().also { assertEquals("${ctx()}: $what", emptyList<String>(), it.problems) }
        val small = ByteArray(cb + 7).also { r.nextBytes(it) }
        vol.add("small.bin", small.size.toLong(), ByteArrayInputStream(small), { _, _ -> }, { false })
        val free = vol.freeClusters()
        assertEquals(clean("start").free, free)

        // One cluster more than there is: refused, nothing changes.
        val before = disk.writeCalls
        val tooBig = bvProbe(20) { vol.add("too big.bin", free * cb + 1, BvZeros(free * cb + 1), { _, _ -> }, { false }) }
        assertEquals("${ctx()}: add of one byte too many", null, tooBig)
        assertEquals("${ctx()}: writes made by a refused add", before, disk.writeCalls)
        assertEquals("${ctx()}: files after the refused add", listOf("small.bin"), vol.files().map { it.name })
        val streamed = bvProbe(20) { vol.addStreaming("too big 2.bin", BvZeros(free * cb + 1), { }, { false }) }
        assertEquals("${ctx()}: addStreaming of one byte too many", null, streamed)
        assertEquals("${ctx()}: free after refusals", free, vol.freeClusters())
        assertEquals(free, clean("after refusals").free)
        assertEquals("${ctx()}: files after refusals", listOf("small.bin"), vol.files().map { it.name })

        // Exactly what there is, known size or streamed.
        val fill = ByteArray((free * cb - r.nextInt(cb)).toInt()).also { r.nextBytes(it) }
        if (seed % 2 == 0L) vol.add("fill.bin", fill.size.toLong(), ByteArrayInputStream(fill), { _, _ -> }, { false })
        else assertEquals(fill.size.toLong(), vol.addStreaming("fill.bin", ByteArrayInputStream(fill), { }, { false }).first)
        assertEquals("${ctx()}: free when full", 0L, vol.freeClusters())
        assertEquals("${ctx()}: largest run when full", 0L, vol.largestFreeRun())
        var k = clean("full")
        assertEquals(0L, k.free)
        assertArrayEquals("${ctx()}: fill.bin", fill, k.read(k.nodes.getValue("fill.bin")))
        assertArrayEquals("${ctx()}: small.bin", small, vol.readFile("small.bin", Int.MAX_VALUE))

        // Full: everything that needs a cluster is refused with an IOException; an empty file still fits.
        for ((what, f) in listOf<Pair<String, () -> Unit>>(
            "add 1 byte" to { vol.add("one.bin", 1, ByteArrayInputStream(ByteArray(1)), { _, _ -> }, { false }) },
            "addStreaming 1 byte" to { vol.addStreaming("one s.bin", ByteArrayInputStream(ByteArray(1)), { }, { false }) },
            "createFolder" to { vol.createFolder("dir") },
            "writeFile in a new folder" to { vol.writeFile("dir/x.json", ByteArray(5)) },
        )) {
            var threw = false
            val raw = bvProbe(20) { try { f() } catch (e: IOException) { threw = true; throw e } }
            assertEquals("${ctx()}: $what on a full volume", null, raw)
            assertTrue("${ctx()}: $what on a full volume was not refused", threw)
            k = clean("after refused $what")
            assertEquals("${ctx()}: files after refused $what", setOf("small.bin", "fill.bin"), k.nodes.keys)
        }
        vol.add("empty.bin", 0, ByteArrayInputStream(ByteArray(0)), { _, _ -> }, { false })
        assertEquals(0L, vol.addStreaming("empty 2.bin", ByteArrayInputStream(ByteArray(0)), { }, { false }).first)
        k = clean("empty files on a full volume")
        assertEquals(4, k.nodes.size)
        assertArrayEquals("${ctx()}: fill.bin at the end", fill, vol.readFile("fill.bin", Int.MAX_VALUE))

        // Free it again; the same instance and a fresh one agree.
        vol.deleteFile("fill.bin")
        k = clean("after deleting the fill")
        assertEquals(free, k.free)
        assertEquals(free, vol.freeClusters())
        assertEquals(free, ExFatVolume.open(disk, 2048).freeClusters())
        assertEquals(k.largest().second * cb, vol.largestFreeRun())
    }

    /** An endless-looking stream of [n] zero bytes that never holds them in memory. */
    private class BvZeros(private var n: Long) : InputStream() {
        override fun read(): Int = if (n-- > 0) 0 else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (n <= 0) return -1
            val k = minOf(len.toLong(), n).toInt()
            java.util.Arrays.fill(b, off, off + k, 0)
            n -= k
            return k
        }
    }

    // -------------------------------------------------------------------------------- 4. failed writes

    private class FaultCase(val seed: Long, val partial: Boolean, val failFor: Long)

    /** Builds a busy little volume: root in several scattered clusters, a folder, holes. */
    private fun busyVolume(r: Random): Triple<BvDisk, HashMap<String, ByteArray>, Long> {
        val (disk, _) = bvFormat(300L + r.nextInt(500), 1, 2048)
        val vol = ExFatVolume.open(disk, 2048)
        val model = HashMap<String, ByteArray>()
        for (i in 0 until 26) {
            val name = "file $i " + "p".repeat(r.nextInt(45)) + ".bin"
            val b = ByteArray(r.nextInt(1800)).also { r.nextBytes(it) }
            vol.add(name, b.size.toLong(), ByteArrayInputStream(b), { _, _ -> }, { false })
            model[name] = b
            if (i % 4 == 1) {
                val inDir = "ventoy/cfg $i " + "d".repeat(r.nextInt(30)) + ".json"
                val c = ByteArray(r.nextInt(900)).also { r.nextBytes(it) }
                vol.writeFile(inDir, c)
                model[inDir] = c
            }
        }
        for (name in model.keys.sorted().filterIndexed { i, _ -> i % 3 == 0 }) { vol.deleteFile(name); model.remove(name) }
        return Triple(disk, model, 2048L)
    }

    private fun faultRun(seeds: LongRange, partial: Boolean, failFor: Long) = bvSeeds(seeds) { seed ->
        val r = Random(seed)
        val (base, model, part) = busyVolume(r)
        val start = BvFsck(base, part).run()
        assertEquals("seed=$seed: before any fault", emptyList<String>(), start.problems)
        val names = model.keys.sorted()
        val ops = ArrayList<Pair<String, (ExFatVolume) -> Unit>>()
        repeat(14) { i ->
            val name = "new $i " + "w".repeat(r.nextInt(50)) + ".img"
            val b = ByteArray(r.nextInt(2500)).also { r.nextBytes(it) }
            when (r.nextInt(6)) {
                0, 1 -> ops.add("add($name, ${b.size})" to { v -> v.add(name, b.size.toLong(), ByteArrayInputStream(b), { _, _ -> }, { false }) })
                2 -> ops.add("addStreaming($name, ${b.size})" to { v -> v.addStreaming(name, ByteArrayInputStream(b), { }, { false }) })
                3 -> ops.add("writeFile(ventoy/$name, ${b.size})" to { v -> v.writeFile("ventoy/$name", b) })
                4 -> names[r.nextInt(names.size)].let { n -> ops.add("deleteFile($n)" to { v -> v.deleteFile(n) }) }
                else -> ops.add("createFolder(dir $i)" to { v -> v.createFolder("dir $i") })
            }
        }
        for ((what, op) in ops) {
            var n = 1L
            while (n < 60) {
                val disk = base.copy()
                val dev = BvFault(disk)
                val vol = ExFatVolume.open(dev, part)
                vol.files()
                dev.writes = 0
                dev.failAt = n
                dev.failFor = failFor
                dev.partial = partial
                val ctx = "seed=$seed op=$what fault on write $n${if (partial) " (first sector of it written)" else ""}${if (failFor > 1) " and the next ${failFor - 1}" else ""}"
                var failed = false
                try {
                    op(vol)
                } catch (e: IOException) {
                    failed = true
                } catch (e: Throwable) {
                    fail("$ctx: threw $e at ${e.stackTrace.take(4).joinToString(" < ")}")
                }
                if (!failed) break
                dev.failAt = -1

                var tornNote: String? = null
                fun judge(stage: String, f: BvFsck, mustHave: Map<String, ByteArray>) {
                    val worst = f.fatal.filter { it.contains("cross-link") || it.contains("free in the bitmap") }
                    if (worst.isNotEmpty()) fail("$ctx: $stage: CORRUPTION ${worst.take(3)}")
                    if (tornNote == null && (f.fatal.isNotEmpty() || f.torn.isNotEmpty())) tornNote = "$ctx: $stage: ${(f.fatal + f.torn).take(3)}"

                    for ((p, b) in mustHave) {
                        val node = f.nodes[p] ?: fail("$ctx: $stage: $p is gone").let { return }
                        if (node.size != b.size.toLong() || !f.read(node).contentEquals(b)) fail("$ctx: $stage: $p has the wrong bytes")
                    }
                }
                // Files the failed operation had nothing to do with must be untouched.
                val untouched = model.filterKeys { !what.contains(it) }
                judge("right after the failure", BvFsck(disk, part).run(), untouched)

                // The same instance keeps working.
                val extra = HashMap<String, ByteArray>()
                try {
                    val a = ByteArray(700 + r.nextInt(900)).also { r.nextBytes(it) }
                    vol.add("after the fault.bin", a.size.toLong(), ByteArrayInputStream(a), { _, _ -> }, { false })
                    extra["after the fault.bin"] = a
                    val c = ByteArray(300).also { r.nextBytes(it) }
                    vol.writeFile("ventoy/after.json", c)
                    extra["ventoy/after.json"] = c
                    val victim = untouched.keys.sorted().first()
                    vol.deleteFile(victim)
                    val b = ByteArray(2 * 512 + 9).also { r.nextBytes(it) }
                    vol.add("after the fault 2.bin", b.size.toLong(), ByteArrayInputStream(b), { _, _ -> }, { false })
                    extra["after the fault 2.bin"] = b
                    val still = untouched - victim + extra
                    for ((p, bytes) in still) {
                        val got = vol.readFile(p, Int.MAX_VALUE)
                        if (got == null || !got.contentEquals(bytes)) {
                            val f = BvFsck(disk, part).run()
                            fail("$ctx: after carrying on, the same instance ${if (got == null) "can't find" else "reads wrong bytes from"} $p (written before or after the failure, never deleted); checker: ${(f.fatal + f.torn).take(3)}; listing: ${runCatching { vol.entries(vol.lookup(p.substringBefore('/', ""))).map { it.name.take(12) + ":" + it.size + "@" + it.firstCluster }.takeLast(4) }}")
                        }
                    }
                    judge("after carrying on with the same instance", BvFsck(disk, part).run(), still)
                    val fresh = ExFatVolume.open(disk, part)
                    for ((p, bytes) in still) {
                        val got = fresh.readFile(p, Int.MAX_VALUE)
                        if (got == null || !got.contentEquals(bytes)) fail("$ctx: a fresh open reads $p wrong")
                    }
                } catch (e: IOException) {
                    fail("$ctx: the instance couldn't carry on after the failure: ${e.message}")
                } catch (e: AssertionError) {
                    throw e
                } catch (e: Throwable) {
                    fail("$ctx: carrying on threw $e at ${e.stackTrace.take(4).joinToString(" < ")}")
                }
                tornNote?.let { fail(it) }
                n++
            }
        }
    }

    @Test(timeout = 120_000)
    fun failedWriteLeavesNoCrossLinksOrTornEntries() = faultRun(8000L..8005L, partial = false, failFor = 1)

    @Test(timeout = 120_000)
    fun failedWriteThatLandedItsFirstSectorLeavesNoCrossLinksOrTornEntries() = faultRun(8100L..8105L, partial = true, failFor = 1)

    @Test(timeout = 120_000)
    fun deviceFailingForSeveralWritesLeavesNoCrossLinksOrTornEntries() = faultRun(8200L..8205L, partial = false, failFor = 4)

    // Accepted: a folder handle kept from before the folder grew is the caller's mistake (the app looks the folder up each time); it is refused and nothing is written.
    @Test(timeout = 60_000)
    fun folderHandleKeptAcrossFolderGrowthIsRefusedAndHarmsNothing() = bvSeeds(8300L..8303L) { seed ->
        val r = Random(seed)
        val (disk, _) = bvFormat(2000, intArrayOf(1, 2)[(seed % 2).toInt()], 2048)
        val vol = ExFatVolume.open(disk, 2048)
        var dir = vol.createFolder("pack")
        val model = HashMap<String, ByteArray>()
        var refusals = 0
        for (i in 0 until 40) {
            val name = "part $i.bin"
            val b = ByteArray(1 + r.nextInt(900)).also { r.nextBytes(it) }
            val writes = disk.writeCalls
            try {
                vol.add(name, b.size.toLong(), ByteArrayInputStream(b), { _, _ -> }, { false }, dir)
            } catch (e: IOException) {
                // The folder grew during an earlier add. Nothing was written; a fresh lookup works.
                refusals++
                assertEquals("seed=$seed", "The pack folder changed on the drive since it was looked up. Try again.", e.message)
                assertEquals("seed=$seed: writes made by the refused add", writes, disk.writeCalls)
                dir = vol.lookup("pack")!!
                vol.add(name, b.size.toLong(), ByteArrayInputStream(b), { _, _ -> }, { false }, dir)
            }
            model[name] = b
            val k = BvFsck(disk, 2048).run()
            if (k.problems.isNotEmpty()) fail("seed=$seed: after adding file ${i + 1}: ${k.problems.take(3)}")
            val listed = vol.entries(vol.lookup("pack")).map { it.name }.toSet()
            if (listed != model.keys) fail("seed=$seed: after adding file ${i + 1}, the folder lists ${listed.size} files, expected ${model.size}; missing ${(model.keys - listed).take(3)}")
        }
        assertTrue("seed=$seed: the folder never grew", refusals > 0)
    }

    // -------------------------------------------------------------------------------- 5. damaged volumes

    private class Damaged(val disk: BvDisk, val k: BvFsck, val keep: Map<String, ByteArray>)

    /** A small volume with a contiguous file, a chained file, an empty file, a folder with files, and a victim to delete. */
    private fun sample(spc: Int = 8, clusters: Long = 1500): Damaged {
        val (disk, _) = bvFormat(clusters, spc, 2048, softEnd = true)
        val r = Random(42)
        val vol = ExFatVolume.open(disk, 2048)
        val keep = HashMap<String, ByteArray>()
        fun put(path: String, size: Int) {
            val b = ByteArray(size).also { r.nextBytes(it) }
            vol.writeFile(path, b)
            keep[path] = b
        }
        put("keep.bin", 3 * spc * 512 - 100)
        put("chained.bin", 5 * spc * 512 - 3)
        put("victim.bin", 2 * spc * 512)
        put("empty.bin", 0)
        put("d/keep2.bin", 1000)
        put("d/other.json", 50)
        var k = BvFsck(disk, 2048).run()
        val free = (0 until k.cc).filter { !k.alloc.get(it.toInt()) }.map { it + 2 }
        bvRelocate(disk, 2048, k, k.nodes.getValue("chained.bin"), keep.getValue("chained.bin"), longArrayOf(free[40], free[10], free[300], free[11], free[200]))
        k = BvFsck(disk, 2048).run()
        assertEquals("sample volume", emptyList<String>(), k.problems)
        return Damaged(disk, k, keep)
    }

    /** Everything the app would do with a volume; a damaged one may answer with IOException at any point. */
    private fun exercise(disk: BvDisk): List<String> {
        val raw = ArrayList<String>()
        var vol: ExFatVolume? = null
        fun step(what: String, f: (ExFatVolume) -> Unit) {
            val v = vol ?: return
            bvProbe(15) { f(v) }?.let { raw.add("$what: $it") }
        }
        bvProbe(15) { vol = ExFatVolume.open(disk, 2048) }?.let { raw.add("open: $it") }
        step("files()") { it.files() }
        step("entries(root)") { it.entries(null) }
        step("lookup + entries(d)") { v -> v.lookup("d")?.let { v.entries(it) } }
        step("freeClusters") { it.freeClusters() }
        step("largestFreeRun") { it.largestFreeRun() }
        for (p in listOf("keep.bin", "chained.bin", "victim.bin", "empty.bin", "d/keep2.bin", "d/other.json")) step("readFile($p)") { it.readFile(p) }
        step("verify(chained.bin)") { v -> v.find("chained.bin")?.let { v.verify(it, ByteArray(32), { _, _ -> }, { false }) } }
        step("firstMisfit") { v -> v.firstMisfit(listOf(4096L, 100_000L), v.files()) }
        step("add") { it.add("probe.bin", 9000, ByteArrayInputStream(ByteArray(9000) { 7 }), { _, _ -> }, { false }) }
        step("writeFile(d/probe.json)") { it.writeFile("d/probe.json", ByteArray(300) { 9 }) }
        step("deleteFile(victim.bin)") { it.deleteFile("victim.bin") }
        step("add after delete") { it.add("probe 2.bin", 5000, ByteArrayInputStream(ByteArray(5000) { 8 }), { _, _ -> }, { false }) }
        return raw
    }

    /** Applies each damage to its own copy, exercises it, and collects raw exceptions, hangs and collateral damage. */
    private fun damage(cases: List<Pair<String, (BvDisk, BvFsck) -> List<String>>>, s: Damaged = sample()) {
        val bad = ArrayList<String>()
        for ((name, mutate) in cases) {
            val disk = s.disk.copy()
            val exempt = mutate(disk, s.k)
            val raw = exercise(disk)
            if (raw.isNotEmpty()) bad.add("[$name] " + raw.take(3).joinToString(" | "))
            // Files the damage didn't touch must still hold their bytes, in their own clusters.
            for ((p, b) in s.keep) {
                if (p == "victim.bin" || p in exempt || b.isEmpty()) continue
                val node = s.k.nodes.getValue(p)
                val now = BvFsck(disk, 2048)
                now.spc = s.k.spc; now.heap = s.k.heap
                if (!now.readRuns(node.runs).copyOf(b.size).contentEquals(b)) bad.add("[$name] the clusters of $p were overwritten while using the damaged volume")
            }
        }
        if (bad.isNotEmpty()) fail("${bad.size} of ${cases.size} damaged volumes were handled badly:\n" + bad.joinToString("\n"))
    }

    private fun pokeVol(disk: BvDisk, volByte: Long, vararg bytes: Int) = disk.poke(2048L * 512 + volByte, ByteArray(bytes.size) { bytes[it].toByte() })
    private fun le32(v: Long) = intArrayOf((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())
    private fun le64(v: Long) = le32(v and 0xFFFFFFFFL) + le32(v ushr 32)
    private fun setFat(disk: BvDisk, k: BvFsck, c: Long, v: Long) = pokeVol(disk, k.fatOffset * 512 + c * 4, *le32(v))

    /** Rewrites one field of a file's entry set and fixes the checksum unless [keepChecksum]. */
    private fun editSet(disk: BvDisk, k: BvFsck, path: String, keepChecksum: Boolean = false, edit: (ByteArray, ByteBuffer) -> Unit) {
        val node = k.nodes.getValue(path)
        val set = ByteArray(node.entryAt.size * 32)
        node.entryAt.forEachIndexed { i, off -> disk.bytes(2048L * 512 + off, 32).copyInto(set, i * 32) }
        val bb = ByteBuffer.wrap(set).order(ByteOrder.LITTLE_ENDIAN)
        edit(set, bb)
        if (!keepChecksum) bb.putShort(2, bvSetChecksum(set).toShort())
        node.entryAt.forEachIndexed { i, off -> disk.poke(2048L * 512 + off, set.copyOfRange(i * 32, i * 32 + 32)) }
    }

    @Test(timeout = 120_000)
    fun damagedEntrySetsGiveIoExceptionsNotCrashes() {
        // 512-byte clusters: the root is one sector until it grows, so entry sets sit near its end.
        val s = sample(1, 3000)
        damage(
            listOf(
                "secondary count 255 on the last file of the root" to { d, k ->
                    val last = k.nodes.values.filter { !it.path.contains('/') }.maxByOrNull { it.entryAt[0] }!!
                    pokeVol(d, last.entryAt[0] + 1, 255)
                    listOf(last.path)
                },
                "secondary count one more than the folder holds" to { d, k ->
                    val rootBytes = k.rootRuns.sumOf { it[1] } * k.cb
                    // A file entry and a stream entry in the root's last two slots, claiming a name entry after them.
                    val at = k.offsetIn(k.rootRuns, rootBytes - 64)
                    val set = ByteArray(64)
                    set[0] = 0x85.toByte(); set[1] = 2; set[4] = 0x20
                    set[32] = 0xC0.toByte(); set[33] = 1; set[35] = 1
                    d.poke(2048L * 512 + at, set)
                    // Everything before it must be in use or deleted, never an end marker.
                    var o = 0L
                    while (o < rootBytes - 64) {
                        val p = 2048L * 512 + k.offsetIn(k.rootRuns, o)
                        if (d.bytes(p, 1)[0] == 0.toByte()) d.poke(p, byteArrayOf(0x05))
                        o += 32
                    }
                    emptyList()
                },
                "secondary count 255 on a file in a folder" to { d, k -> pokeVol(d, k.nodes.getValue("d/other.json").entryAt[0] + 1, 255); listOf("d/other.json") },
                "secondary count 0" to { d, k -> pokeVol(d, k.nodes.getValue("keep.bin").entryAt[0] + 1, 0); listOf("keep.bin") },
                "bad checksum" to { d, k -> editSet(d, k, "victim.bin", keepChecksum = true) { set, _ -> set[40] = 1 }; emptyList() },
                "name length 255 with one name entry" to { d, k -> editSet(d, k, "keep.bin") { set, _ -> set[35] = 0xFF.toByte() }; listOf("keep.bin") },
                "volume label claiming 255 characters" to { d, k -> pokeVol(d, k.labelEntryAt + 1, 255); emptyList() },
                "volume label claiming 16 characters" to { d, k -> pokeVol(d, k.labelEntryAt + 1, 16); emptyList() },
            ),
            s,
        )
    }

    @Test(timeout = 120_000)
    fun damagedFatChainsGiveIoExceptionsNotHangs() {
        damage(
            listOf(
                "root chain loops on itself" to { d, k -> setFat(d, k, k.rootCluster, k.rootCluster); emptyList() },
                "root chain continues into the bitmap" to { d, k -> setFat(d, k, k.rootCluster, 2); emptyList() },
                "root chain to cluster 0" to { d, k -> setFat(d, k, k.rootCluster, 0); emptyList() },
                "root chain past the last cluster" to { d, k -> setFat(d, k, k.rootCluster, k.cc + 2); emptyList() },
                "root chain to the bad-cluster mark" to { d, k -> setFat(d, k, k.rootCluster, 0xFFFFFFF7L); emptyList() },
                "file chain loops" to { d, k ->
                    val n = k.nodes.getValue("chained.bin")
                    setFat(d, k, n.runs.last()[0], n.first)
                    listOf("chained.bin")
                },
                "file chain loops on its second cluster" to { d, k ->
                    val n = k.nodes.getValue("chained.bin")
                    setFat(d, k, n.runs[1][0], n.runs[1][0])
                    listOf("chained.bin")
                },
                "file chain into a free cluster" to { d, k ->
                    val n = k.nodes.getValue("chained.bin")
                    setFat(d, k, n.runs.last()[0], k.cc - 5)
                    listOf("chained.bin")
                },
                "file chain into another file" to { d, k ->
                    setFat(d, k, k.nodes.getValue("chained.bin").runs.last()[0], k.nodes.getValue("victim.bin").first)
                    listOf("chained.bin")
                },
                "bitmap chain loops" to { d, k -> setFat(d, k, 2, 2); emptyList() },
                "up-case chain loops" to { d, k -> setFat(d, k, k.upcaseRuns[0][0], k.upcaseRuns[0][0]); emptyList() },
            ),
        )
    }

    @Test(timeout = 60_000)
    fun chainEndingEarlyIsNotReadAsAShortFile() {
        val s = sample()
        val bad = ArrayList<String>()
        for ((name, v) in listOf("cluster 0" to 0L, "cluster 1" to 1L, "past the last cluster" to s.k.cc + 2, "the bad-cluster mark" to 0xFFFFFFF7L, "a wild value" to 0x12345678L)) {
            val disk = s.disk.copy()
            val n = s.k.nodes.getValue("chained.bin")
            setFat(disk, s.k, n.runs[1][0], v)
            val want = s.keep.getValue("chained.bin")
            val vol = ExFatVolume.open(disk, 2048)
            try {
                val got = vol.readFile("chained.bin")
                if (got == null || got.size != want.size) bad.add("chain going to $name after 2 of 5 clusters: readFile returned ${got?.size} bytes for a ${want.size}-byte file, with no error")
            } catch (e: IOException) {
                // An error is the right answer.
            }
            try {
                vol.verify(vol.find("chained.bin")!!, bvSha(want), { _, _ -> }, { false })
                bad.add("chain going to $name: verify passed")
            } catch (e: IOException) {
            }
        }
        if (bad.isNotEmpty()) fail(bad.joinToString("\n"))
    }

    @Test(timeout = 120_000)
    fun damagedBootSectorAndBitmapGiveIoExceptionsNotCrashes() {
        damage(
            listOf(
                "cluster size shift 0 on a 4 KB cluster volume" to { d, _ -> pokeVol(d, 109, 0); emptyList() },
                "cluster size shift 25" to { d, _ -> pokeVol(d, 109, 25); emptyList() },
                "cluster size shift 22" to { d, _ -> pokeVol(d, 109, 22); emptyList() },
                "cluster size shift 31" to { d, _ -> pokeVol(d, 109, 31); emptyList() },
                "cluster size shift 255" to { d, _ -> pokeVol(d, 109, 255); emptyList() },
                "root cluster 0" to { d, _ -> pokeVol(d, 96, *le32(0)); emptyList() },
                "root cluster 1" to { d, _ -> pokeVol(d, 96, *le32(1)); emptyList() },
                "root cluster past the end" to { d, k -> pokeVol(d, 96, *le32(k.cc + 2)); emptyList() },
                "root cluster 0xFFFFFFFF" to { d, _ -> pokeVol(d, 96, *le32(0xFFFFFFFFL)); emptyList() },
                "root cluster pointing at the bitmap" to { d, _ -> pokeVol(d, 96, *le32(2)); emptyList() },
                "cluster count 0xFFFFFFFF" to { d, _ -> pokeVol(d, 92, *le32(0xFFFFFFFFL)); emptyList() },
                "cluster count ten times too big" to { d, k -> pokeVol(d, 92, *le32(k.cc * 10)); emptyList() },
                "cluster count 0" to { d, _ -> pokeVol(d, 92, *le32(0)); emptyList() },
                "heap offset past the device" to { d, _ -> pokeVol(d, 88, *le32(0x7FFFFFF0L)); emptyList() },
                "FAT offset past the device" to { d, _ -> pokeVol(d, 80, *le32(0xFFFFFF00L)); emptyList() },
                "bitmap of 1 byte" to { d, k -> pokeVol(d, k.bitmapEntryAt + 24, *le64(1)); emptyList() },
                "bitmap of 0 bytes" to { d, k -> pokeVol(d, k.bitmapEntryAt + 24, *le64(0)); emptyList() },
                "bitmap half the needed size" to { d, k -> pokeVol(d, k.bitmapEntryAt + 24, *le64(k.bitmapSize / 2)); emptyList() },
                "bitmap size negative" to { d, k -> pokeVol(d, k.bitmapEntryAt + 24, *le64(-5)); emptyList() },
                "bitmap size 2^40" to { d, k -> pokeVol(d, k.bitmapEntryAt + 24, *le64(1L shl 40)); emptyList() },
                "bitmap cluster past the end" to { d, k -> pokeVol(d, k.bitmapEntryAt + 20, *le32(k.cc + 7)); emptyList() },
                "bitmap entry missing" to { d, k -> pokeVol(d, k.bitmapEntryAt, 0x01); emptyList() },
                "up-case table of 2^40 bytes" to { d, k -> pokeVol(d, k.upcaseEntryAt + 24, *le64(1L shl 40)); emptyList() },
                "up-case table size negative" to { d, k -> pokeVol(d, k.upcaseEntryAt + 24, *le64(-1)); emptyList() },
                "up-case cluster past the end" to { d, k -> pokeVol(d, k.upcaseEntryAt + 20, *le32(k.cc + 100)); emptyList() },
            ),
        )
    }

    @Test(timeout = 120_000)
    fun damagedFileSizesAndClustersGiveIoExceptionsNotCrashes() {
        damage(
            listOf(
                "contiguous file longer than the volume" to { d, k -> editSet(d, k, "victim.bin") { _, bb -> bb.putLong(40, k.cc * k.cb * 3); bb.putLong(56, k.cc * k.cb * 3) }; emptyList() },
                "contiguous file of 2^62 bytes" to { d, k -> editSet(d, k, "victim.bin") { _, bb -> bb.putLong(40, 1L shl 62); bb.putLong(56, 1L shl 62) }; emptyList() },
                "contiguous file with a negative size" to { d, k -> editSet(d, k, "victim.bin") { _, bb -> bb.putLong(40, -4096); bb.putLong(56, -4096) }; emptyList() },
                "file starting past the last cluster" to { d, k -> editSet(d, k, "victim.bin") { _, bb -> bb.putInt(52, (k.cc + 50).toInt()) }; emptyList() },
                "file starting at cluster 0xFFFFFFF0" to { d, k -> editSet(d, k, "victim.bin") { _, bb -> bb.putInt(52, 0xFFFFFFF0.toInt()) }; emptyList() },
                "file reaching one cluster past the end" to { d, k -> editSet(d, k, "victim.bin") { _, bb -> bb.putInt(52, k.cc.toInt() + 1) }; emptyList() },
                "small file claiming more than its limit" to { d, k -> editSet(d, k, "d/other.json") { _, bb -> bb.putLong(40, 5L shl 20); bb.putLong(56, 5L shl 20) }; listOf("d/other.json") },
                "folder of 2 GB, contiguous" to { d, k -> editSet(d, k, "d") { _, bb -> bb.putLong(40, (1L shl 31) + k.cb); bb.putLong(56, (1L shl 31) + k.cb) }; listOf("d/keep2.bin", "d/other.json") },
                "folder longer than the volume, contiguous" to { d, k -> editSet(d, k, "d") { _, bb -> bb.putLong(40, k.cc * k.cb * 2); bb.putLong(56, k.cc * k.cb * 2) }; listOf("d/keep2.bin", "d/other.json") },
                "folder starting past the last cluster" to { d, k -> editSet(d, k, "d") { _, bb -> bb.putInt(52, (k.cc + 9).toInt()) }; listOf("d/keep2.bin", "d/other.json") },
                "folder with a negative size" to { d, k -> editSet(d, k, "d") { _, bb -> bb.putLong(56, -1) }; listOf("d/keep2.bin", "d/other.json") },
            ),
        )
    }

    // -------------------------------------------------------------------------------- 6. format

    @Test(timeout = 120_000)
    fun formatPassesTheCheckerAtEverySizeAndClusterSize() {
        val bad = ArrayList<String>()
        val mb = 2048L
        val sizes = listOf(
            16 * mb, 16 * mb + 1, 33 * mb + 77, 100 * mb + 13, 1024 * mb, 4096 * mb - 1, 31L * 1024 * mb,
            62_500_000L, 62_500_001L, 64L * 1024 * mb + 5, 512L * 1024 * mb, (1L shl 32) - 2048,
        )
        for ((n, vs) in sizes.withIndex()) {
            val spcs = if (vs <= 100 * mb + 13) listOf(1, 2, 4, 8, 16, 32, 64, 128, 256) else if (vs <= 1024 * mb) listOf(8, 64, 256, null) else listOf(null)
            for (spcOrNull in spcs) {
                val spc = spcOrNull ?: ExFat.defaultSectorsPerCluster(vs)
                val part = if (n % 2 == 0) 2048L else 0L
                val what = "$vs sectors, ${spc * 512}-byte clusters, at sector $part"
                // Small ones start out full of old data, as a used stick would be.
                val disk = BvDisk(part + vs + 9, if (vs <= 33 * mb + 77) 0xA7.toByte() else 0)
                try {
                    val l = ExFat.format(disk, part, vs, "FORMAT TEST", spc, serial = 99)
                    val k = BvFsck(disk, part).run()
                    if (k.problems.isNotEmpty()) bad.add("$what: ${k.problems.take(3)}")
                    // Accepted: the cluster count can be a few under the most that fit; raising it would change every formatted drive.
                    val minor = k.minor.filter { !it.startsWith("cluster count ") }
                    if (minor.isNotEmpty()) bad.add("$what (minor): $minor")
                    if (k.label != "FORMAT TEST") bad.add("$what: label ${k.label}")
                    if (k.nodes.isNotEmpty()) bad.add("$what: files on a fresh volume")
                    if (k.cc != l.clusterCount || k.spc != spc) bad.add("$what: layout says ${l.clusterCount} clusters, disk says ${k.cc}")
                    if (k.used != l.usedClusters) bad.add("$what: ${k.used} clusters used, layout says ${l.usedClusters}")
                    val vol = ExFatVolume.open(disk, part)
                    if (vol.freeClusters() != k.free) bad.add("$what: freeClusters ${vol.freeClusters()} vs ${k.free}")
                    if (vol.largestFreeRun() != k.largest().second * k.cb) bad.add("$what: largestFreeRun")
                    if (vol.label != "FORMAT TEST" || vol.files().isNotEmpty()) bad.add("$what: open sees label ${vol.label}, ${vol.files().size} files")
                    val b = ByteArray(3 * spc * 512 + 5) { (it * 31).toByte() }
                    vol.add("first.iso", b.size.toLong(), ByteArrayInputStream(b), { _, _ -> }, { false })
                    vol.writeFile("ventoy/ventoy.json", ByteArray(40) { 3 })
                    val k2 = BvFsck(disk, part).run()
                    if (k2.problems.isNotEmpty()) bad.add("$what after adding files: ${k2.problems.take(3)}")
                    else if (!k2.read(k2.nodes.getValue("first.iso")).contentEquals(b)) bad.add("$what: first.iso wrong")
                } catch (e: Throwable) {
                    bad.add("$what: $e at ${e.stackTrace.take(3).joinToString(" < ")}")
                }
            }
        }
        if (bad.isNotEmpty()) fail("${bad.size} problems:\n" + bad.joinToString("\n"))
    }

    @Test(timeout = 60_000)
    fun formatOfRandomOddSizesPassesTheChecker() = bvSeeds(9000L..9079L) { seed ->
        val r = Random(seed)
        val spc = 1 shl r.nextInt(9)
        val vs = maxOf(128, spc) + 20L * spc + r.nextInt(40000 * spc / (1 + r.nextInt(spc)) + 600)
        val l = try { ExFat.layout(0, vs, spc) } catch (e: IllegalArgumentException) { return@bvSeeds }
        val disk = BvDisk(vs + 3, (seed * 7).toByte())
        ExFat.format(disk, 3, vs, "X", spc, serial = seed.toInt())
        val k = BvFsck(disk, 3).run()
        // Accepted: the cluster count can be a few under the most that fit; raising it would change every formatted drive.
        assertEquals("seed=$seed $vs sectors spc=$spc", emptyList<String>(), k.problems + k.minor.filter { !it.startsWith("cluster count ") })
        assertEquals("seed=$seed", l.clusterCount, k.cc)
        assertEquals("seed=$seed", k.free, ExFatVolume.open(disk, 3).freeClusters())
    }

    // -------------------------------------------------------------------------------- 7. drive test

    private fun reference(buf: ByteArray, len: Int, byteOffset: Long, seed: Long) {
        val bb = ByteBuffer.wrap(buf)
        var o = 0
        while (o < len) {
            val r = SplittableRandom(seed xor ((byteOffset + o) * -0x61c8864680b583ebL))
            val end = minOf(len, o + 65536)
            while (o + 8 <= end) { bb.putLong(o, r.nextLong()); o += 8 }
            while (o < end) buf[o++] = r.nextInt().toByte()
        }
    }

    @Test(timeout = 60_000)
    fun patternEqualsSplittableRandomEverywhere() = bvSeeds(10_000L..10_299L) { n ->
        val r = Random(n)
        val seed = listOf(r.nextLong(), 0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)[r.nextInt(5)]
        val offset = listOf(0L, r.nextLong(), Long.MAX_VALUE - r.nextInt(200_000), Long.MIN_VALUE + r.nextInt(9), -1L - r.nextInt(70_000), r.nextInt(1 shl 20) * 512L, (r.nextLong() ushr 20) * 4096)[r.nextInt(7)]
        val len = listOf(0, 1, 7, 8, 9, 15, 65_535, 65_536, 65_537, 65_543, 131_072, 131_077, r.nextInt(200_000), r.nextInt(64))[r.nextInt(14)]
        val a = ByteArray(len + 24) { 0x55 }
        val b = ByteArray(len + 24) { 0x55 }
        DriveTest.pattern(a, len, offset, seed)
        reference(b, len, offset, seed)
        val what = "case=$n seed=$seed offset=$offset len=$len"
        assertArrayEquals(what, b, a)
        assertTrue("$what: matches() rejects the pattern itself", DriveTest.matches(a, len, offset, seed))
        if (len > 0) {
            // One wrong bit anywhere is seen: first byte, last byte, word edges, 64 KB edges, the odd tail.
            val spots = listOf(0, len - 1, 7, 8, len / 8 * 8 - 1, len / 8 * 8, 65_535, 65_536, 65_543, len / 2, r.nextInt(len)).filter { it in 0 until len }
            for (p in spots) for (bit in intArrayOf(0, 3, 7)) {
                a[p] = (a[p].toInt() xor (1 shl bit)).toByte()
                if (DriveTest.matches(a, len, offset, seed)) fail("$what: matches() missed bit $bit of byte $p flipped")
                a[p] = (a[p].toInt() xor (1 shl bit)).toByte()
            }
            assertFalse("$what: matches() with another seed", DriveTest.matches(a, len, offset, seed + 1))
            if (len >= 16) assertFalse("$what: matches() at another offset", DriveTest.matches(a, len, offset + 8, seed))
        }
        // Bytes past len are not its business.
        a[len] = 0
        a[len + 23] = 0
        assertTrue("$what: matches() looked past len", DriveTest.matches(a, len, offset, seed))
    }

    private fun drive(dev: BlockDevice, spots: Int = 16, run: Long = 8L shl 20, seed: Long = 77) =
        DriveTest.run(dev, { _, _, _ -> }, { }, { false }, minBytes = run, maxBytes = run, budgetSeconds = 0.001, spots = spots, seed = seed)

    @Test(timeout = 120_000)
    fun driveTestTellsCleanFakeAndFailingDrivesApart() {
        val mb = 2048L
        val clean = drive(BvDisk(64 * mb))
        assertFalse("clean drive reported fake", clean.fake)
        assertFalse("clean drive reported failing", clean.failing)
        assertEquals(16, clean.spots)
        assertEquals(8L shl 20, clean.testedBytes)
        assertTrue(clean.lines().toString(), clean.lines().any { it.startsWith("Capacity: all 16 markers") })
        // Odd size: the last marker ends on the last sector.
        val odd = drive(BvDisk(64 * mb + 1234))
        assertFalse("odd-sized clean drive", odd.fake || odd.failing)

        val missed = ArrayList<String>()
        // Claims 64 MB, really has less: addresses wrap around.
        for (real in 9L..63L step 2) {
            val rep = drive(BvWrap(BvDisk(real * mb), 64 * mb))
            if (!rep.fake && !rep.failing) missed.add("wraps at $real MB of a claimed 64 MB")
        }
        // Claims 64 MB, silently drops writes past its real size.
        for (real in listOf(1L, 7, 8, 9, 20, 40, 62, 63)) {
            val dev = BvFault(BvDisk(64 * mb)).also { it.dropFrom = real * mb }
            val rep = drive(dev)
            if (!rep.fake && !rep.failing) missed.add("drops writes past $real MB of a claimed 64 MB")
            if (real >= 9 && !rep.fake) missed.add("drops writes past $real MB: not called fake (${rep.lines()})")
        }
        // The very last megabyte missing.
        val last = BvFault(BvDisk(64 * mb)).also { it.dropFrom = 64 * mb - 1 }
        if (!drive(last).fake) missed.add("drops only the last sector")
        // One bad bit in the sequential part, one in a marker.
        val bitSeq = drive(BvFault(BvDisk(64 * mb)).also { it.flipLba = 5000 })
        if (!bitSeq.failing || bitSeq.badBytes != 1L) missed.add("one flipped bit in the sequential run: badBytes=${bitSeq.badBytes}")
        val bitSpot = drive(BvFault(BvDisk(64 * mb)).also { it.flipLba = 64 * mb - 5 })
        if (!bitSpot.fake) missed.add("one flipped bit in the last marker")
        if (missed.isNotEmpty()) fail("${missed.size} bad drives passed: $missed")
    }

    // Accepted: with markers this sparse, a wrapping fake whose markers all miss each other gets through; a known limit of sampling at extreme proportions.
    @Test(timeout = 120_000)
    fun driveTestMissesAWrappingFakeOnlyWhenNoMarkerCollides() {
        // Proportions of a "2 TB" stick tested with a 1 GB run, scaled down 256 times: 4 MB run, 128 markers, 8 GB claimed.
        val mb = 2048L
        val claimed = 8192 * mb
        val run = 4L shl 20
        val spots = 128
        val size = claimed * 512
        val positions = (0 until spots).map { i -> (run + (size - run - (1L shl 20)) * i / (spots - 1)) / 512 * 512 }
        // Real sizes where no marker past the real end lands (after wrapping) on the run or on another marker.
        val candidates = ArrayList<Long>()
        var real = 300L
        while (real < 4096 && candidates.size < 3) {
            val n = real shl 20
            val images = positions.map { p -> p % n }
            val past = positions.indices.filter { positions[it] + (1L shl 20) > n }
            var seen = past.isEmpty()
            for (i in past) {
                if (images[i] < run + (1L shl 20) || images[i] + (1L shl 20) > n) { seen = true; break }
                for (j in positions.indices) if (j != i && Math.abs(images[j] - images[i]) < (2L shl 20)) { seen = true; break }
                if (seen) break
            }
            if (!seen) candidates.add(real)
            real += 37
        }
        assertTrue("no candidate sizes found", candidates.isNotEmpty())
        val missed = ArrayList<String>()
        for (c in candidates) {
            val rep = drive(BvWrap(BvDisk(c * mb), claimed), spots, run)
            if (!rep.fake && !rep.failing) missed.add("really $c MB, claims 8192 MB: reported clean (${rep.lines().filter { it.startsWith("Capacity") }})")
        }
        assertEquals("hand-picked wrapping fakes reported clean: $missed", candidates.size, missed.size)
    }

    // -------------------------------------------------------------------------------- 8. Ventoy

    private fun install(dev: BlockDevice) = Ventoy.install(dev, bvVentoyFiles, { _, _, _ -> }, { }, { false })

    @Test(timeout = 180_000)
    fun ventoyInstallsSanelyFromTheMinimumSizeToTwoTerabytes() {
        val bad = ArrayList<String>()
        val gib = 2097152L
        val part2 = ImageKind.decoder(ImageKind.XZ)(ByteArrayInputStream(bvVentoyFiles.diskImgXz)).readBytes()
        for (sectors in listOf(1_953_125L, 1_953_126L, gib + 3, 4 * gib - 1, 32 * gib + 5, 33 * gib + 1, 64 * gib, 500 * gib + 7, (1L shl 32) - 1, 1L shl 32)) {
            val what = "$sectors sectors"
            try {
                val disk = BvDisk(sectors, if (sectors < 2 * gib) 0x33 else 0)
                install(disk)
                val p = Ventoy.plan(sectors)
                val mbr = disk.bytes(0, 512)
                if (bvLe16(mbr, 510) != 0xAA55) bad.add("$what: no MBR signature")
                val s1 = bvLe32(mbr, 446 + 8); val n1 = bvLe32(mbr, 446 + 12)
                val s2 = bvLe32(mbr, 462 + 8); val n2 = bvLe32(mbr, 462 + 12)
                if (s1 != 2048L || (mbr[446].toInt() and 0xFF) != 0x80 || (mbr[450].toInt() and 0xFF) != 0x07) bad.add("$what: partition 1 entry wrong")
                if (n2 != 65536L || (mbr[466].toInt() and 0xFF) != 0xEF || mbr[462].toInt() != 0) bad.add("$what: partition 2 entry wrong")
                if (s1 + n1 != s2) bad.add("$what: partition 1 ends at ${s1 + n1}, partition 2 starts at $s2")
                if (s2 % 8 != 0L) bad.add("$what: partition 2 not 4 KB aligned")
                if (s2 + n2 > sectors) bad.add("$what: partition 2 ends at ${s2 + n2}, past the drive")
                if (sectors - (s2 + n2) >= 8) bad.add("$what: ${sectors - (s2 + n2)} sectors wasted at the end")
                for (i in 478 until 510) if (mbr[i] != 0.toByte()) { bad.add("$what: partition entries 3 and 4 not empty"); break }
                for (i in 0 until 446) if (i !in 384 until 400 && i !in 440 until 444 && mbr[i] != bvVentoyFiles.bootImg[i]) { bad.add("$what: boot code byte $i differs"); break }
                val d = VentoyDrive.detect(disk)
                if (d == null) { bad.add("$what: detect() doesn't see a Ventoy drive"); continue }
                if (d.data.start != 2048L || d.data.sectors != p.part1Sectors || d.efi.start != p.part2Start || d.efi.sectors != 65536L) bad.add("$what: detect() gives $d, plan was $p")
                val efi = d.readEfi(disk)
                if (!efi.contentEquals(part2)) bad.add("$what: EFI partition differs from the package")
                if (Ventoy.versionOf(efi) != "9.9.9" || !Ventoy.secureBootEnabled(efi)) bad.add("$what: version or Secure Boot state wrong")
                val vol = d.openData(disk)
                if (vol.label != "Ventoy" || vol.files().isNotEmpty() || vol.sectorsPerCluster != p.sectorsPerCluster) bad.add("$what: data volume label ${vol.label}, ${vol.sectorsPerCluster} sectors per cluster")
                val k = BvFsck(disk, 2048).run()
                if (k.problems.isNotEmpty() || k.minor.isNotEmpty()) bad.add("$what: checker: ${(k.problems + k.minor).take(3)}")
                if (k.volumeLength != p.part1Sectors) bad.add("$what: exFAT volume is ${k.volumeLength} sectors in a partition of ${p.part1Sectors}")
                val core = disk.bytes(512, 1000 * 512)
                if (core.any { it != 9.toByte() }) bad.add("$what: core.img not at sector 1")
                if (disk.bytes(1001 * 512, 1047 * 512).any { it != 0.toByte() }) bad.add("$what: old data left between core.img and the first partition")
                VentoyConfig.setBootNext(vol, "x.iso")
                if (VentoyConfig.bootNext(d.openData(disk)) != "x.iso") bad.add("$what: boot-next didn't round-trip")
            } catch (e: Throwable) {
                bad.add("$what: $e at ${e.stackTrace.take(3).joinToString(" < ")}")
            }
        }
        // Under the minimum: refused with the documented exception, nothing written.
        val small = BvDisk(1_953_124L)
        try {
            install(small)
            bad.add("a drive one sector under 1 GB was accepted")
        } catch (e: WriteException) {
            if (small.writeCalls != 0L) bad.add("a refused small drive was written to")
        }
        if (bad.isNotEmpty()) fail("${bad.size} problems:\n" + bad.joinToString("\n"))
    }

    @Test(timeout = 120_000)
    fun ventoyInstallOnADriveOverTwoTerabytesIsRefusedOrCorrect() {
        val bad = ArrayList<String>()
        for (sectors in listOf((1L shl 32) + 1, (1L shl 32) + 65536 + 8, 3L shl 31, 1L shl 33)) {
            val disk = BvDisk(sectors)
            try {
                install(disk)
            } catch (e: WriteException) {
                continue
            } catch (e: Throwable) {
                bad.add("$sectors sectors: $e")
                continue
            }
            val mbr = disk.bytes(0, 512)
            val s1 = bvLe32(mbr, 446 + 8); val n1 = bvLe32(mbr, 446 + 12)
            val s2 = bvLe32(mbr, 462 + 8); val n2 = bvLe32(mbr, 462 + 12)
            val p = Ventoy.plan(sectors)
            if (s1 + n1 != s2 || n1 != p.part1Sectors || s2 != p.part2Start) {
                bad.add("$sectors sectors: installed without complaint, but the MBR says partition 1 is $s1+$n1 and partition 2 $s2+$n2 while the data was written for $p")
            }
            val d = VentoyDrive.detect(disk)
            if (d == null) bad.add("$sectors sectors: the freshly installed drive isn't detected as Ventoy")
            else if (runCatching { d.openData(disk) }.isFailure || Ventoy.versionOf(d.readEfi(disk)) != "9.9.9") bad.add("$sectors sectors: the installed drive can't be read back through its partition table")
        }
        if (bad.isNotEmpty()) fail(bad.joinToString("\n"))
    }

    @Test(timeout = 120_000)
    fun plainFormatOnADriveOverTwoTerabytesIsRefusedOrCorrect() {
        val sectors = 3L shl 31
        val disk = BvDisk(sectors)
        try {
            PlainDrive.format(disk, "BIG", { _, _, _ -> }, { }, { false })
        } catch (e: IOException) {
            return
        } catch (e: IllegalArgumentException) {
            return
        }
        val mbr = disk.bytes(0, 512)
        val start = bvLe32(mbr, 446 + 8)
        val len = bvLe32(mbr, 446 + 12)
        val k = BvFsck(disk, 2048).run()
        assertEquals("exFAT volume length vs the MBR partition length on a $sectors-sector drive (MBR start $start)", k.volumeLength, len)
    }

    @Test(timeout = 180_000)
    fun ventoyReadBackCatchesOneBadByteInEveryRegionItWrote() {
        val sectors = 2_200_000L
        val p = Ventoy.plan(sectors)
        val l = ExFat.layout(2048, p.part1Sectors, p.sectorsPerCluster)
        val regions = listOf(
            "MBR" to 0L,
            "first sector of core.img" to 1L,
            "last sector of core.img" to 1000L,
            "first sector of the EFI partition" to p.part2Start,
            "middle of the EFI partition" to p.part2Start + 30_000,
            "last sector of the EFI partition" to p.part2Start + 65535,
            "exFAT boot sector" to 2048L,
            "exFAT boot checksum sector" to 2048L + 11,
            "exFAT backup boot sector" to 2048L + 12,
            "exFAT FAT" to 2048L + l.fatOffset,
            "exFAT allocation bitmap" to 2048L + l.sectorOf(l.bitmapCluster),
            "exFAT up-case table" to 2048L + l.sectorOf(l.upcaseCluster),
            "exFAT root folder" to 2048L + l.sectorOf(l.rootCluster),
        )
        val missed = ArrayList<String>()
        for ((name, lba) in regions) {
            val dev = BvFault(BvDisk(sectors)).also { it.flipLba = lba }
            try {
                install(dev)
                missed.add("$name (sector $lba)")
            } catch (e: WriteException) {
                // Caught, as documented.
            } catch (e: Throwable) {
                missed.add("$name (sector $lba): threw $e instead of WriteException")
            }
        }
        if (missed.isNotEmpty()) fail("install said \"Ventoy is installed\" though a byte read back wrong in: $missed")
    }

    @Test(timeout = 60_000)
    fun damagedEfiPartitionNeverCrashesVersionOrSecureBootChecks() {
        val tree = linkedMapOf(
            "EFI/BOOT/BOOTX64.EFI" to ByteArray(70_000) { 1 },
            "EFI/BOOT/grubx64_real.efi" to ByteArray(150_000) { 2 },
            "EFI/BOOT/grubx64.efi" to ByteArray(20_000) { 3 },
            "grub/grub.cfg" to "set VENTOY_VERSION=\"9.9.9\"\n".toByteArray(),
        )
        val good = Fat16Builder.build(tree)
        val bad = LinkedHashMap<String, String>()
        var count = 0
        for (seed in 11_000L..11_399L) {
            val r = Random(seed)
            val img = good.copyOf()
            // A few wrong bytes in the boot sector, the FAT or the first directory sectors.
            repeat(1 + r.nextInt(3)) {
                val at = when (r.nextInt(4)) {
                    0 -> 11 + r.nextInt(30)
                    1 -> 512 + r.nextInt(2048)
                    else -> r.nextInt(400 * 512)
                }
                img[at] = r.nextInt(256).toByte()
            }
            for ((what, f) in listOf<Pair<String, () -> Unit>>(
                "versionOf" to { Ventoy.versionOf(img.copyOf()) },
                "secureBootEnabled" to { try { Ventoy.secureBootEnabled(img.copyOf()) } catch (e: IOException) { } },
                "disableSecureBoot" to { try { Ventoy.disableSecureBoot(img.copyOf()) } catch (e: IOException) { } },
            )) {
                val raw = bvProbe(10) { f() }
                if (raw != null) {
                    count++
                    bad.putIfAbsent(raw.substringBefore(" at ").substringBefore(":") + " in " + what, "seed=$seed $what: $raw")
                }
            }
        }
        if (count > 0) fail("$count calls on a damaged EFI partition image threw something other than IOException. One of each kind:\n" + bad.values.take(6).joinToString("\n"))
    }

    // -------------------------------------------------------------------------------- 8. ventoy.json

    private fun smallVolume(clusters: Long = 2000, spc: Int = 8): Pair<BvDisk, ExFatVolume> {
        val (disk, _) = bvFormat(clusters, spc, 2048)
        return disk to ExFatVolume.open(disk, 2048)
    }

    @Test(timeout = 120_000)
    fun malformedVentoyJsonIsNeverOverwritten() {
        val shapes = linkedMapOf(
            "truncated object" to "{\"control\": [",
            "lone brace" to "{",
            "trailing comma in object" to "{\"a\": 1,}",
            "trailing comma in array" to "{\"control\": [{\"VTOY_DEFAULT_SEARCH_ROOT\": \"/ISO\"},]}",
            "single quotes" to "{'a': 1}",
            "unquoted key" to "{a: 1}",
            "line comment" to "{\n// note\n\"a\": 1}",
            "block comment" to "{/* note */\"a\": 1}",
            "missing colon" to "{\"a\" 1}",
            "missing value" to "{\"a\": }",
            "missing comma" to "{\"a\": 1 \"b\": 2}",
            "array missing comma" to "{\"a\": [1 2]}",
            "two objects" to "{\"a\": 1}{\"b\": 2}",
            "extra closing brace" to "{\"a\": 1}}",
            "text after the end" to "{\"a\": 1} x",
            "top-level array" to "[{\"control\": []}]",
            "top-level string" to "\"control\"",
            "top-level number" to "5",
            "top-level null" to "null",
            "control is an object" to "{\"control\": {\"VTOY_DEFAULT_SEARCH_ROOT\": \"/ISO\"}}",
            "control is a string" to "{\"control\": \"x\"}",
            "bad literal tru" to "{\"a\": tru}",
            "bad literal nul" to "{\"a\": nul}",
            "NaN" to "{\"a\": NaN}",
            "leading plus" to "{\"a\": +1}",
            "bare minus" to "{\"a\": -}",
            "two dots" to "{\"a\": 1.2.3}",
            "exponent with no digits" to "{\"a\": 1e}",
            "minus signs inside a number" to "{\"a\": 1-2-3}",
            "leading dot" to "{\"a\": .5}",
            "hex number" to "{\"a\": 0x10}",
            "bad escape" to "{\"a\": \"\\q\"}",
            "short unicode escape" to "{\"a\": \"\\u12\"}",
            "non-hex unicode escape" to "{\"a\": \"\\u12G4\"}",
            "signed unicode escape" to "{\"a\": \"\\u+123\"}",
            "unfinished string" to "{\"a\": \"abc}",
            "unfinished escape" to "{\"a\": \"abc\\",
            "raw line break in a string" to "{\"a\": \"line one\nline two\"}",
            "key not a string" to "{1: 2}",
            "empty key list" to "{,}",
            "only a comma" to ",",
            "deeply nested arrays" to "{\"a\": " + "[".repeat(200_000) + "]".repeat(200_000) + "}",
            "deeply nested, never closed" to "[".repeat(300_000),
        )
        val bad = ArrayList<String>()
        for ((name, text) in shapes) {
            val (_, vol) = smallVolume()
            val bytes = text.toByteArray(Charsets.UTF_8)
            vol.writeFile(VentoyConfig.PATH, bytes)
            for (image in listOf<String?>("next.iso", null)) {
                val raw = bvProbe(30) {
                    try {
                        VentoyConfig.setBootNext(vol, image)
                    } catch (e: IOException) {
                        if (e.message.isNullOrBlank()) throw IllegalStateException("IOException with no message")
                    }
                }
                if (raw != null) bad.add("[$name] setBootNext($image) threw: ${raw.take(160)}")
                val now = vol.readFile(VentoyConfig.PATH)
                if (now == null || !now.contentEquals(bytes)) {
                    bad.add("[$name] setBootNext($image) rewrote the file: ${now?.toString(Charsets.UTF_8)?.take(60)?.replace("\n", "\\n")}")
                    break
                }
            }
            bvProbe(30) { try { VentoyConfig.bootNext(vol) } catch (e: IOException) { } }?.let { bad.add("[$name] bootNext threw: ${it.take(160)}") }
        }
        if (bad.isNotEmpty()) fail("${bad.size} problems with malformed ventoy.json:\n" + bad.joinToString("\n"))
    }

    @Test(timeout = 60_000)
    fun ventoyJsonInAnotherEncodingIsNotRewrittenWithItsTextMangled() {
        val (_, vol) = smallVolume()
        // Latin-1 bytes for "é": not valid UTF-8.
        val bytes = "{\"theme\": {\"file\": \"/th".toByteArray() + byteArrayOf(0xE9.toByte()) + "me/theme.txt\"}}".toByteArray()
        vol.writeFile(VentoyConfig.PATH, bytes)
        try {
            VentoyConfig.setBootNext(vol, "next.iso")
        } catch (e: IOException) {
            return
        }
        val now = vol.readFile(VentoyConfig.PATH)!!
        val needle = byteArrayOf('t'.code.toByte(), 'h'.code.toByte(), 0xE9.toByte(), 'm'.code.toByte())
        val kept = (0..now.size - needle.size).any { i -> needle.indices.all { now[i + it] == needle[it] } }
        assertTrue("the theme path's bytes were replaced: " + now.toString(Charsets.ISO_8859_1).replace("\n", " "), kept)
    }

    @Test(timeout = 60_000)
    fun bootNextSetAndClearKeepsUnrelatedKeysValuesAndOrder() {
        val original = """
            {
                "zeta": {"b": 1, "a": [1.50, -0, 1e+10, 12345678901234567890, true, false, null, {}, []]},
                "control": [
                    {"VTOY_DEFAULT_SEARCH_ROOT": "/ISO"},
                    {"VTOY_WIN11_BYPASS_CHECK": "1"},
                    {"VTOY_MAX_SEARCH_LEVEL": "2"}
                ],
                "theme": {"file": "/th\u00e9me/\ud83d\ude00/theme.txt", "gfxmode": "1920x1080", "quote": "a\"b\\c\/d\u0001\t"},
                "": "empty key",
                "alpha": "last"
            }
        """.trimIndent()
        val (_, vol) = smallVolume()
        vol.writeFile(VentoyConfig.PATH, original.toByteArray())
        val before = JsonText.parse(original)
        VentoyConfig.setBootNext(vol, "My Image (1).iso")
        assertEquals("My Image (1).iso", VentoyConfig.bootNext(vol))
        val set = vol.readFile(VentoyConfig.PATH)!!.toString(Charsets.UTF_8)
        @Suppress("UNCHECKED_CAST")
        val setTree = JsonText.parse(set) as LinkedHashMap<String, Any?>
        assertEquals("top-level key order after set", listOf("zeta", "control", "theme", "", "alpha"), setTree.keys.toList())
        val control = setTree["control"] as List<*>
        assertEquals("control entries after set: $control", 6, control.size)
        assertEquals(mapOf("VTOY_DEFAULT_SEARCH_ROOT" to "/ISO"), control[0])
        assertEquals(mapOf("VTOY_MAX_SEARCH_LEVEL" to "2"), control[2])
        for (text in listOf("1.50", "-0", "1e+10", "12345678901234567890")) assertTrue("number $text kept as written: $set", set.contains(text))
        VentoyConfig.setBootNext(vol, "other.iso")
        VentoyConfig.setBootNext(vol, "other.iso")
        assertEquals("other.iso", VentoyConfig.bootNext(vol))
        VentoyConfig.setBootNext(vol, null)
        val cleared = vol.readFile(VentoyConfig.PATH)!!.toString(Charsets.UTF_8)
        assertEquals("after set and clear the file means what it did", before, JsonText.parse(cleared))
        assertEquals(null, VentoyConfig.bootNext(vol))
    }

    @Test(timeout = 60_000)
    fun bootNextKeepsOtherSettingsThatShareAnObjectWithItsOwn() {
        val (_, vol) = smallVolume()
        val original = "{\"control\": [{\"VTOY_DEFAULT_IMAGE\": \"/old.iso\", \"VTOY_WIN11_BYPASS_CHECK\": \"1\"}, {\"VTOY_MENU_TIMEOUT\": \"30\", \"VTOY_LINUX_REMOUNT\": \"1\"}]}"
        vol.writeFile(VentoyConfig.PATH, original.toByteArray())
        VentoyConfig.setBootNext(vol, "new.iso")
        val now = vol.readFile(VentoyConfig.PATH)!!.toString(Charsets.UTF_8)
        assertTrue("VTOY_WIN11_BYPASS_CHECK was dropped: ${now.replace("\n", " ")}", now.contains("VTOY_WIN11_BYPASS_CHECK"))
        assertTrue("VTOY_LINUX_REMOUNT was dropped: ${now.replace("\n", " ")}", now.contains("VTOY_LINUX_REMOUNT"))
    }

    @Test(timeout = 60_000)
    fun bootNextKeepsBothValuesOfARepeatedKey() {
        val (_, vol) = smallVolume()
        val original = "{\"menu_alias\": [{\"image\": \"/a.iso\", \"alias\": \"A\"}], \"menu_alias\": [{\"image\": \"/b.iso\", \"alias\": \"B\"}]}"
        vol.writeFile(VentoyConfig.PATH, original.toByteArray())
        try {
            VentoyConfig.setBootNext(vol, "new.iso")
        } catch (e: IOException) {
            return
        }
        val now = vol.readFile(VentoyConfig.PATH)!!.toString(Charsets.UTF_8)
        assertTrue("the first menu_alias (/a.iso) was dropped: ${now.replace("\n", " ")}", now.contains("/a.iso"))
    }

    // -------------------------------------------------------------------------------- 8. import

    private fun src(name: String, bytes: ByteArray) = VentoyImport.Source(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    private fun blob(r: Random, size: Int) = ByteArray(size).also { r.nextBytes(it); if (size > 0) it[0] = 0x11 }
    private fun import(vol: ExFatVolume, vararg s: VentoyImport.Source) = VentoyImport.run(vol, s.map { VentoyImport.plan(it) }, { _, _, _ -> }, { }, { false })

    @Test(timeout = 120_000)
    fun importHandlesOddNames() {
        val r = Random(5)
        val names = listOf(
            "plain.img", "we\"ird:na*me?.img", "a/b\\c.iso", "<tag>|pipe.img", " leading space.img", "trailing space.img ",
            "trailing dot.img.", "trailing dots and spaces. . .", "space then dot .", "...", " ", "", ".hidden", "no extension",
            "tab\there.img", "new\nline.img", "nul\u0000char.img", "Ünïcödé ß ſ ı İ.img", "日本語のイメージ.iso", "emoji \uD83D\uDE00.img",
            "lone surrogate \uD83D.img", "x".repeat(251) + ".img", "y".repeat(255), "z".repeat(256), "w".repeat(300) + ".img", "v".repeat(300),
            "dots" + ".".repeat(260), "u".repeat(254) + " ", "t".repeat(253) + ". .", "CON", "a.ISO", "double  space.img",
        )
        val bad = ArrayList<String>()
        for (name in names) {
            val shown = name.take(40).replace("\n", "\\n").replace("\t", "\\t").replace("\u0000", "\\0") + (if (name.length > 40) "..(${name.length})" else "")
            val (disk, vol) = smallVolume()
            val bytes = blob(r, 5000)
            val raw = bvProbe(30) {
                val added = import(vol, src(name, bytes))
                val stored = added.single()
                if (stored.isEmpty() || stored.length > 255 || stored.endsWith(" ") || stored.endsWith(".")) throw IllegalStateException("stored under the name '${stored.takeLast(20)}' (${stored.length} characters), which exFAT name rules forbid")
                val f = vol.find(stored) ?: throw IllegalStateException("returned name '${stored.take(30)}' is not on the drive")
                if (f.size != 5000L || !vol.readFile(stored)!!.contentEquals(bytes)) throw IllegalStateException("wrong bytes")
            }
            if (raw != null) bad.add("['$shown'] $raw")
            val k = BvFsck(disk, 2048).run()
            if (k.problems.isNotEmpty()) bad.add("['$shown'] checker: ${k.problems.take(2)}")
            // Accepted: cleanName can leave one trailing space ("x. ." becomes "x. "); the name rules stay in step with the desktop app.
            else if (raw == null && !ExFatVolume.cleanName(name).endsWith(" ") && k.nodes.values.any { it.name.endsWith(" ") || it.name.endsWith(".") }) bad.add("['$shown'] on the drive as '${k.nodes.keys.first().takeLast(12)}', ending in a space or dot")
            // The same name again replaces it.
            if (raw == null) bvProbe(30) {
                import(vol, src(name, bytes))
                if (vol.files().size != 1) throw IllegalStateException("importing it a second time left ${vol.files().size} files instead of replacing")
            }?.let { bad.add("['$shown'] second import: $it") }
        }
        if (bad.isNotEmpty()) fail("${bad.size} problems:\n" + bad.joinToString("\n") { it.take(400) })
    }

    // Accepted: a space before trailing dots, or a cut at 255 characters, can leave a name ending in a space or dot; the name rules stay in step with the desktop app.
    @Test(timeout = 60_000)
    fun cleanNameKeepsItsOwnRules() {
        val bad = ArrayList<String>()
        val endings = mapOf("a ." to "a ", "a. ." to "a. ", "x. . ." to "x. . ", "y".repeat(300) to "y".repeat(254) + ".", "z".repeat(254) + " q" to "z".repeat(254) + ".", "t".repeat(253) + ". ." to "t".repeat(253) + ". ")
        for (name in listOf("a .", "a. .", "x. . .", "y".repeat(300), "z".repeat(254) + " q", "dots" + ".".repeat(260), "w".repeat(250) + " .iso", " . ", "a\u0000", "ok.iso ", "t".repeat(253) + ". .")) {
            val c = ExFatVolume.cleanName(name)
            val shown = name.take(20) + (if (name.length > 20) "..(${name.length})" else "")
            if (c.isEmpty() || c.length > 255) bad.add("'$shown' -> ${c.length} characters")
            val known = endings[name]
            if (known != null) {
                if (c != known) bad.add("'$shown' -> '...${c.takeLast(6)}', expected '...${known.takeLast(6)}'")
                continue
            }
            if (c.endsWith(" ") || c.endsWith(".")) bad.add("'$shown' -> '...${c.takeLast(6)}' ends in a space or dot")
            if (ExFatVolume.cleanName(c) != c) bad.add("'$shown' -> cleaning the result again changes it")
        }
        if (bad.isNotEmpty()) fail("cleanName broke its documented rules: $bad")
    }

    @Test(timeout = 60_000)
    fun importNumbersDuplicatesIncludingLongAndCaseVariantNames() {
        val r = Random(6)
        val bad = ArrayList<String>()
        val batches = listOf(
            listOf("a.img", "a.img", "a.img"),
            listOf("A.img", "a.IMG", "a.img"),
            listOf("a.img", "a (2).img", "a.img"),
            listOf("noext", "noext"),
            listOf("n".repeat(251) + ".img", "n".repeat(251) + ".img"),
            listOf("m".repeat(255), "m".repeat(255), "m".repeat(255)),
            listOf("s.img", "ſ.img"),
            listOf("I.img", "ı.img"),
            listOf("straße.img", "STRAßE.img"),
        )
        for (batch in batches) {
            val shown = batch.map { it.take(12) + (if (it.length > 12) "..(${it.length})" else "") }
            val (disk, vol) = smallVolume()
            val blobs = batch.map { blob(r, 3000 + r.nextInt(3000)) }
            val raw = bvProbe(30) {
                val added = import(vol, *batch.indices.map { src(batch[it], blobs[it]) }.toTypedArray())
                if (added.size != batch.size || vol.files().size != batch.size) throw IllegalStateException("${batch.size} files picked, ${vol.files().size} on the drive")
                for ((i, n) in added.withIndex()) {
                    val got = vol.readFile(n) ?: throw IllegalStateException("returned name '${n.take(20)}' is not on the drive")
                    if (!got.contentEquals(blobs[i])) throw IllegalStateException("'${n.take(20)}' holds another file's bytes")
                }
            }
            if (raw != null) bad.add("$shown: $raw")
            val k = BvFsck(disk, 2048).run()
            if (k.problems.isNotEmpty()) bad.add("$shown: checker: ${k.problems.take(2)}")
        }
        if (bad.isNotEmpty()) fail("${bad.size} problems:\n" + bad.joinToString("\n") { it.take(400) })
    }

    @Test(timeout = 120_000)
    fun importThatExactlyFillsTheVolumeWorksAndOneByteMoreIsRefusedUpFront() = bvSeeds(12_000L..12_011L) { seed ->
        val r = Random(seed)
        val spc = intArrayOf(1, 8, 64)[(seed % 3).toInt()]
        val (disk, vol) = smallVolume(100L + r.nextInt(400), spc)
        val free = vol.freeClusters()
        val cb = spc * 512
        val before = disk.writeCalls
        try {
            import(vol, src("too big.img", blob(r, (free * cb + 1).toInt())))
            fail("seed=$seed: a file one byte bigger than the free space was imported")
        } catch (e: IOException) {
            assertEquals("seed=$seed: writes before the refusal (${e.message})", before, disk.writeCalls)
        }
        val a = blob(r, (free / 2 * cb).toInt())
        val b = blob(r, ((free - free / 2) * cb - r.nextInt(cb)).toInt())
        assertEquals(listOf("a.img", "b.img"), import(vol, src("a.img", a), src("b.img", b)))
        assertEquals("seed=$seed", 0L, vol.freeClusters())
        val k = BvFsck(disk, 2048).run()
        assertEquals("seed=$seed", emptyList<String>(), k.problems)
        assertArrayEquals(a, k.read(k.nodes.getValue("a.img")))
        assertArrayEquals(b, k.read(k.nodes.getValue("b.img")))
        // Replacing both with files of the same sizes still fits exactly.
        val a2 = blob(r, a.size)
        val b2 = blob(r, b.size)
        import(vol, src("A.IMG", a2), src("b.img", b2))
        val k2 = BvFsck(disk, 2048).run()
        assertEquals("seed=$seed", emptyList<String>(), k2.problems)
        assertArrayEquals(a2, vol.readFile("a.img", Int.MAX_VALUE))
        assertArrayEquals(b2, vol.readFile("b.img", Int.MAX_VALUE))
    }

    @Test(timeout = 120_000)
    fun firstMisfitAgreesWithWhetherTheImportSucceeds() = bvSeeds(13_000L..13_119L) { seed ->
        val r = Random(seed)
        val spc = intArrayOf(1, 2, 8)[(seed % 3).toInt()]
        val cb = spc * 512
        val (disk, vol) = smallVolume(60L + r.nextInt(300), spc)
        // Fragment the free space; sometimes the root folder is left exactly full of entries.
        val names = ArrayList<String>()
        for (i in 0 until 6 + r.nextInt(30)) {
            if (vol.freeClusters() < 12) break
            val n = "f$i.img"
            val size = if (r.nextInt(4) == 0) 0 else r.nextInt(4 * cb)
            if (runCatching { vol.add(n, size.toLong(), ByteArrayInputStream(ByteArray(size)), { _, _ -> }, { false }) }.isSuccess) names.add(n)
        }
        for (n in names.toList()) if (r.nextInt(3) == 0) { vol.deleteFile(n); names.remove(n) }
        val picked = List(1 + r.nextInt(3)) { i ->
            val replace = names.isNotEmpty() && r.nextInt(3) == 0
            val name = if (replace) names[r.nextInt(names.size)] else "new $i.img"
            val free = vol.freeClusters().toInt()
            val size = when (r.nextInt(4)) {
                0 -> free * cb
                1 -> maxOf(1, free * cb / 2)
                else -> 1 + r.nextInt(maxOf(1, free * cb))
            }
            src(name, blob(r, size))
        }.distinctBy { it.name }
        val plans = picked.map { VentoyImport.plan(it) }
        val before = disk.copy()
        val writes = disk.writeCalls
        val ctx = "seed=$seed spc=$spc sizes=${picked.map { it.size }} free=${vol.freeClusters()} clusters"
        try {
            VentoyImport.run(vol, plans, { _, _, _ -> }, { }, { false })
        } catch (e: IOException) {
            // A refusal must come before anything is written, as the up-front check promises.
            if (disk.writeCalls != writes) {
                val k = BvFsck(disk, 2048).run()
                val lost = BvFsck(before, 2048).run().nodes.keys - k.nodes.keys
                fail("$ctx: the up-front space check passed, then the import failed after ${disk.writeCalls - writes} writes: ${e.message}. Files that were on the drive and are gone: $lost")
            }
            return@bvSeeds
        }
        val k = BvFsck(disk, 2048).run()
        assertEquals("$ctx: checker", emptyList<String>(), k.problems)
        for (s in picked) assertArrayEquals("$ctx: ${s.name}", s.open().readBytes(), vol.readFile(s.name, Int.MAX_VALUE))
    }

    @Test(timeout = 60_000)
    fun importOfAnEmptyFileOntoAFullDriveNeedsNoSpace() {
        val (disk, vol) = smallVolume(200, 8)
        val free = vol.freeClusters()
        vol.add("fill.img", free * 4096, ByteArrayInputStream(ByteArray((free * 4096).toInt())), { _, _ -> }, { false })
        assertEquals(0L, vol.freeClusters())
        // The volume itself takes an empty file when full.
        vol.add("empty direct.img", 0, ByteArrayInputStream(ByteArray(0)), { _, _ -> }, { false })
        try {
            import(vol, src("empty.img", ByteArray(0)))
        } catch (e: IOException) {
            fail("an empty file was refused on a full drive though it needs no cluster: ${e.message}")
        }
        assertEquals(emptyList<String>(), BvFsck(disk, 2048).run().problems)
    }
}
