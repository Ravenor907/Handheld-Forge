package io.github.ravenor907.ravendisk.core.fs

import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.util.Crc
import io.github.ravenor907.ravendisk.core.util.Guid
import io.github.ravenor907.ravendisk.core.util.le
import io.github.ravenor907.ravendisk.core.util.u16le
import io.github.ravenor907.ravendisk.core.util.u32le
import io.github.ravenor907.ravendisk.core.util.u8
import java.io.IOException
import java.security.SecureRandom

/** Progress callback: blocks done, blocks total. */
typealias ProgressFn = (Long, Long) -> Unit

/** Read-modify-write of arbitrary bytes on a block device. */
fun BlockDevice.writeBytes(offset: Long, data: ByteArray) {
    val first = offset / blockSize
    val last = (offset + data.size + blockSize - 1) / blockSize
    val n = (last - first).toInt()
    val buf = readBlocks(first, n)
    data.copyInto(buf, (offset - first * blockSize).toInt())
    write(first, n, buf)
}

/** Writes zeros over [blocks] blocks from [lba], in chunks. */
fun BlockDevice.zero(lba: Long, blocks: Long, progress: ProgressFn = { _, _ -> }) {
    val chunkBlocks = maxOf(1, (1 shl 20) / blockSize)
    val zeros = ByteArray(chunkBlocks * blockSize)
    var done = 0L
    while (done < blocks) {
        val n = minOf(chunkBlocks.toLong(), blocks - done).toInt()
        write(lba + done, n, zeros)
        done += n
        progress(done, blocks)
    }
}

object Wipe {
    /**
     * Clears old filesystem signatures inside a partition before formatting: the first MiB (boot
     * sectors and superblocks of every common filesystem live there) and the last 64 KiB
     * (end-of-device RAID superblocks, NTFS backup boot sector).
     */
    fun signatures(dev: BlockDevice, firstLba: Long, blocks: Long) {
        val head = minOf(blocks, (1L shl 20) / dev.blockSize)
        dev.zero(firstLba, head)
        val tail = minOf(blocks - head, (64L shl 10) / dev.blockSize)
        if (tail > 0) dev.zero(firstLba + blocks - tail, tail)
    }
}

object Fat32 {
    data class Layout(
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val reserved: Int,
        val fatSectors: Long,
        val clusters: Long,
        val totalSectors: Long,
    ) {
        val dataStart get() = reserved + 2 * fatSectors
        val clusterBytes get() = sectorsPerCluster * bytesPerSector
    }

    /** Microsoft's FAT32 cluster sizes: 4 KiB up to 8 GB, 8 KiB to 16 GB, 16 KiB to 32 GB, 32 KiB above. */
    fun defaultClusterBytes(volumeBytes: Long): Int = when {
        volumeBytes <= 8L shl 30 -> 4096
        volumeBytes <= 16L shl 30 -> 8192
        volumeBytes <= 32L shl 30 -> 16384
        else -> 32768
    }

    fun layout(bytesPerSector: Int, totalSectors: Long, clusterBytes: Int = defaultClusterBytes(totalSectors * bytesPerSector)): Layout {
        require(totalSectors <= 0xFFFFFFFFL) { "FAT32 volumes are limited to 2^32 sectors" }
        var cb = maxOf(clusterBytes, bytesPerSector)
        while (true) {
            val spc = cb / bytesPerSector
            fun reservedFor(fat: Long) = 32 + ((spc - (32 + 2 * fat) % spc) % spc).toInt()
            var fat = 1L
            var reserved = reservedFor(fat)
            var clusters: Long
            // Grow the FAT until it covers the clusters it describes; the reserved area pads the
            // data region (reserved + 2 FATs) to a whole number of clusters. The FAT only grows,
            // so this converges in a few rounds.
            while (true) {
                clusters = (totalSectors - reserved - 2 * fat) / spc
                val need = ((clusters + 2) * 4 + bytesPerSector - 1) / bytesPerSector
                if (need <= fat) break
                fat = need
                reserved = reservedFor(fat)
            }
            clusters = (totalSectors - reserved - 2 * fat) / spc
            check((clusters + 2) * 4 <= fat * bytesPerSector)
            check((reserved + 2 * fat) % spc == 0L)
            if (clusters >= MIN_CLUSTERS && clusters <= MAX_CLUSTERS) {
                return Layout(bytesPerSector, spc, reserved, fat, clusters, totalSectors)
            }
            if (clusters < MIN_CLUSTERS && cb > bytesPerSector) {
                cb /= 2
                continue
            }
            if (clusters > MAX_CLUSTERS && cb < 65536) {
                cb *= 2
                continue
            }
            throw IllegalArgumentException(
                if (clusters < MIN_CLUSTERS) "The partition is too small for FAT32 (needs about 33 MB)" else "The partition is too large for FAT32",
            )
        }
    }

    /** Formats [blocks] blocks at [firstLba] as FAT32. [label] may be empty. */
    fun format(
        dev: BlockDevice,
        firstLba: Long,
        blocks: Long,
        label: String,
        serial: Int = SecureRandom().nextInt(),
        progress: ProgressFn = { _, _ -> },
    ): Layout {
        val bps = dev.blockSize
        val l = layout(bps, blocks)
        val lab = label.uppercase().take(11)
        val total = l.reserved + 2 * l.fatSectors + l.sectorsPerCluster
        var done = 0L
        val tick: ProgressFn = { d, _ -> progress(done + d, total) }

        dev.zero(firstLba, l.reserved.toLong(), tick)
        done += l.reserved
        // FATs: zeros, then the first sector of each with the reserved and root entries.
        for (f in 0 until 2) {
            val at = firstLba + l.reserved + f * l.fatSectors
            dev.zero(at, l.fatSectors, tick)
            done += l.fatSectors
            val first = ByteArray(bps)
            val b = first.le()
            b.putInt(0, 0x0FFFFFF8)
            b.putInt(4, 0x0FFFFFFF)
            b.putInt(8, 0x0FFFFFFF) // root directory, one cluster
            dev.write(at, 1, first)
        }
        val root = ByteArray(l.clusterBytes)
        if (lab.isNotEmpty()) {
            lab.padEnd(11).toByteArray(Charsets.ISO_8859_1).copyInto(root, 0)
            root[11] = 0x08
        }
        dev.write(firstLba + l.dataStart, l.sectorsPerCluster, root)
        done += l.sectorsPerCluster
        progress(done, total)

        val boot = bootSector(l, firstLba, lab, serial)
        val fsinfo = fsInfo(bps, l.clusters - 1)
        val third = ByteArray(bps).also { it[510] = 0x55; it[511] = 0xAA.toByte() }
        // Backup boot region (sectors 6-8), then the primary last so the volume only looks
        // valid once everything else is written.
        dev.write(firstLba + 6, 1, boot)
        dev.write(firstLba + 7, 1, fsinfo)
        dev.write(firstLba + 8, 1, third)
        dev.write(firstLba + 2, 1, third)
        dev.write(firstLba + 1, 1, fsinfo)
        dev.write(firstLba, 1, boot)
        return l
    }

    fun bootSector(l: Layout, hidden: Long, label: String, serial: Int): ByteArray {
        val s = ByteArray(l.bytesPerSector)
        val b = s.le()
        s[0] = 0xEB.toByte(); s[1] = 0x58; s[2] = 0x90.toByte()
        "RAVENDSK".toByteArray().copyInto(s, 3)
        b.putShort(11, l.bytesPerSector.toShort())
        s[13] = l.sectorsPerCluster.toByte()
        b.putShort(14, l.reserved.toShort())
        s[16] = 2
        b.putShort(17, 0)
        b.putShort(19, 0)
        s[21] = 0xF8.toByte()
        b.putShort(22, 0)
        b.putShort(24, 63)
        b.putShort(26, 255)
        b.putInt(28, if (hidden <= 0xFFFFFFFFL) hidden.toInt() else 0)
        b.putInt(32, l.totalSectors.toInt())
        b.putInt(36, l.fatSectors.toInt())
        b.putShort(40, 0)
        b.putShort(42, 0)
        b.putInt(44, 2)
        b.putShort(48, 1)
        b.putShort(50, 6)
        s[64] = 0x80.toByte()
        s[66] = 0x29
        b.putInt(67, serial)
        (label.ifEmpty { "NO NAME" }).padEnd(11).toByteArray(Charsets.ISO_8859_1).copyInto(s, 71)
        "FAT32   ".toByteArray().copyInto(s, 82)
        // Boot code: print nothing, just halt; this volume isn't meant to boot by itself.
        s[90] = 0xF4.toByte()
        s[510] = 0x55; s[511] = 0xAA.toByte()
        return s
    }

    private fun fsInfo(bps: Int, free: Long): ByteArray {
        val s = ByteArray(bps)
        val b = s.le()
        b.putInt(0, 0x41615252)
        b.putInt(484, 0x61417272)
        b.putInt(488, free.toInt())
        b.putInt(492, 3)
        b.putInt(508, 0xAA550000.toInt())
        return s
    }

    const val MIN_CLUSTERS = 65525L
    const val MAX_CLUSTERS = 0x0FFFFFF5L
}

/**
 * exFAT formatter following Microsoft's published specification. Sector size follows the device
 * (512 or 4096). Writes only the filesystem's own regions, like mkfs.exfat.
 */
object ExFat {
    private const val FIRST_CLUSTER = 2
    private const val ENTRY = 32

    data class Layout(
        val sectorBytes: Int,
        val partitionOffset: Long,
        val volumeLength: Long,
        val sectorsPerCluster: Int,
        val fatOffset: Long,
        val fatLength: Long,
        val heapOffset: Long,
        val clusterCount: Long,
        val bitmapCluster: Long,
        val bitmapBytes: Long,
        val upcaseCluster: Long,
        val upcaseBytes: Long,
        val rootCluster: Long,
    ) {
        val clusterBytes get() = sectorsPerCluster * sectorBytes
        fun clustersFor(bytes: Long) = (bytes + clusterBytes - 1) / clusterBytes
        fun sectorOf(cluster: Long) = heapOffset + (cluster - FIRST_CLUSTER) * sectorsPerCluster
        val usedClusters get() = rootCluster + 1 - FIRST_CLUSTER
    }

    /** Cluster size as mkfs.exfat and Windows pick it: 4 KiB to 256 MB, 32 KiB to 32 GB, 128 KiB above. */
    fun defaultClusterBytes(volumeBytes: Long): Int = when {
        volumeBytes <= 256L * 1024 * 1024 -> 4096
        volumeBytes <= 32L * 1024 * 1024 * 1024 -> 32768
        else -> 131072
    }

    fun layout(sectorBytes: Int, partitionOffset: Long, volumeSectors: Long, clusterBytes: Int = defaultClusterBytes(volumeSectors * sectorBytes)): Layout {
        require(sectorBytes in setOf(512, 1024, 2048, 4096))
        val spc = maxOf(1, clusterBytes / sectorBytes)
        require(Integer.bitCount(spc) == 1)
        // 64 KiB in (128 sectors at 512 bytes, as mkfs.exfat does), past both 12-sector boot regions.
        val fatOffset = align(maxOf(24L, (64L * 1024) / sectorBytes), spc.toLong())
        var clusters = (volumeSectors - fatOffset) / spc
        var fatLength: Long
        var heap: Long
        while (true) {
            fatLength = align(((clusters + 2) * 4 + sectorBytes - 1) / sectorBytes, spc.toLong())
            heap = align(fatOffset + fatLength, spc.toLong())
            val fits = (volumeSectors - heap) / spc
            if (fits >= clusters) break
            clusters = fits
        }
        require(clusters > 16) { "The partition is too small for exFAT" }
        require(clusters <= 0xFFFFFFF5L) { "The partition is too large for this cluster size" }
        val bitmapBytes = (clusters + 7) / 8
        val cb = spc.toLong() * sectorBytes
        val bitmapClusters = (bitmapBytes + cb - 1) / cb
        val upcaseBytes = upcaseTable().size.toLong()
        val upcaseClusters = (upcaseBytes + cb - 1) / cb
        return Layout(
            sectorBytes, partitionOffset, volumeSectors, spc, fatOffset, fatLength, heap, clusters,
            bitmapCluster = 2, bitmapBytes = bitmapBytes,
            upcaseCluster = 2 + bitmapClusters, upcaseBytes = upcaseBytes,
            rootCluster = 2 + bitmapClusters + upcaseClusters,
        )
    }

    fun format(
        dev: BlockDevice,
        firstLba: Long,
        blocks: Long,
        label: String,
        serial: Int = SecureRandom().nextInt(),
        progress: ProgressFn = { _, _ -> },
    ): Layout {
        val ss = dev.blockSize
        require(label.length <= 11) { "exFAT labels are at most 11 characters" }
        val l = layout(ss, firstLba, blocks)
        val total = l.fatLength + l.usedClusters * l.sectorsPerCluster + 24
        var done = 0L
        fun put(sectorInVolume: Long, data: ByteArray) {
            val sectors = data.size / ss
            var at = 0
            while (at < sectors) {
                val n = minOf(sectors - at, (1 shl 20) / ss)
                dev.write(firstLba + sectorInVolume + at, n, data, at * ss)
                at += n
                done += n
                progress(done, total)
            }
        }

        val chunk = 1 shl 20
        var fatWritten = 0L
        val fatBytes = l.fatLength * ss
        while (fatWritten < fatBytes) {
            val buf = ByteArray(minOf(chunk.toLong(), fatBytes - fatWritten).toInt())
            val bb = buf.le()
            val firstEntry = fatWritten / 4
            fun set(cluster: Long, value: Int) {
                val i = cluster - firstEntry
                if (i >= 0 && i * 4 < buf.size) bb.putInt((i * 4).toInt(), value)
            }
            set(0, 0xFFFFFFF8.toInt())
            set(1, 0xFFFFFFFF.toInt())
            fun chain(start: Long, count: Long) {
                for (c in start until start + count) set(c, if (c == start + count - 1) -1 else (c + 1).toInt())
            }
            chain(l.bitmapCluster, l.upcaseCluster - l.bitmapCluster)
            chain(l.upcaseCluster, l.rootCluster - l.upcaseCluster)
            chain(l.rootCluster, 1)
            put(l.fatOffset + fatWritten / ss, buf)
            fatWritten += buf.size
        }

        val bitmap = ByteArray((l.clustersFor(l.bitmapBytes) * l.clusterBytes).toInt())
        for (i in 0 until l.usedClusters) bitmap[(i / 8).toInt()] = (bitmap[(i / 8).toInt()].toInt() or (1 shl (i % 8).toInt())).toByte()
        put(l.sectorOf(l.bitmapCluster), bitmap)

        val upcase = upcaseTable()
        put(l.sectorOf(l.upcaseCluster), upcase.copyOf((l.clustersFor(l.upcaseBytes) * l.clusterBytes).toInt()))

        val root = ByteArray(l.clusterBytes)
        val rb = root.le()
        root[0] = 0x83.toByte()
        root[1] = label.length.toByte()
        label.forEachIndexed { i, ch -> rb.putShort(2 + i * 2, ch.code.toShort()) }
        root[ENTRY] = 0x81.toByte()
        rb.putInt(ENTRY + 20, l.bitmapCluster.toInt())
        rb.putLong(ENTRY + 24, l.bitmapBytes)
        root[2 * ENTRY] = 0x82.toByte()
        rb.putInt(2 * ENTRY + 4, checksum(upcase))
        rb.putInt(2 * ENTRY + 20, l.upcaseCluster.toInt())
        rb.putLong(2 * ENTRY + 24, l.upcaseBytes)
        put(l.sectorOf(l.rootCluster), root)

        val boot = bootRegion(l, serial)
        put(12, boot)
        put(0, boot)
        return l
    }

    fun bootRegion(l: Layout, serial: Int): ByteArray {
        val ss = l.sectorBytes
        val r = ByteArray(12 * ss)
        val b = r.le()
        r[0] = 0xEB.toByte(); r[1] = 0x76; r[2] = 0x90.toByte()
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(r, 3)
        b.putLong(64, l.partitionOffset)
        b.putLong(72, l.volumeLength)
        b.putInt(80, l.fatOffset.toInt())
        b.putInt(84, l.fatLength.toInt())
        b.putInt(88, l.heapOffset.toInt())
        b.putInt(92, l.clusterCount.toInt())
        b.putInt(96, l.rootCluster.toInt())
        b.putInt(100, serial)
        b.putShort(104, 0x0100)
        b.putShort(106, 0)
        r[108] = Integer.numberOfTrailingZeros(ss).toByte()
        r[109] = Integer.numberOfTrailingZeros(l.sectorsPerCluster).toByte()
        r[110] = 1
        r[111] = 0x80.toByte()
        r[112] = ((l.usedClusters * 100) / l.clusterCount).toInt().toByte()
        for (i in 120 until 510) r[i] = 0xF4.toByte()
        r[510] = 0x55; r[511] = 0xAA.toByte()
        for (s in 1..8) {
            r[s * ss + ss - 2] = 0x55; r[s * ss + ss - 1] = 0xAA.toByte()
        }
        var sum = 0
        for (i in 0 until 11 * ss) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = (if (sum and 1 != 0) 0x80000000.toInt() else 0) + (sum ushr 1) + (r[i].toInt() and 0xFF)
        }
        for (i in 0 until ss / 4) b.putInt(11 * ss + i * 4, sum)
        return r
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (x in data) sum = (if (sum and 1 != 0) 0x80000000.toInt() else 0) + (sum ushr 1) + (x.toInt() and 0xFF)
        return sum
    }

    @Volatile
    private var upcaseCache: ByteArray? = null

    /** Up-case table in the spec's compressed form, as Windows and mkfs.exfat write it. */
    fun upcaseTable(): ByteArray = upcaseCache ?: run {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }
        fun map(c: Int): Int {
            val up = Character.toUpperCase(c.toChar()).code
            return if (c in 0xD800..0xDFFF) c else up
        }
        var c = 0
        while (c < 0x10000) {
            if (map(c) == c) {
                var run = 1
                while (c + run < 0x10000 && map(c + run) == c + run) run++
                if (run > 2 || c + run == 0x10000) {
                    u16(0xFFFF)
                    u16(run)
                } else {
                    for (i in 0 until run) u16(c + i)
                }
                c += run
            } else {
                u16(map(c))
                c++
            }
        }
        out.toByteArray().also { upcaseCache = it }
    }

    private fun align(v: Long, to: Long) = (v + to - 1) / to * to
}

object Swap {
    const val PAGE = 4096

    /** mkswap equivalent: version-1 header with UUID and label, 4 KiB pages. */
    fun format(dev: BlockDevice, firstLba: Long, blocks: Long, label: String, uuid: Guid = Guid.random()): Long {
        val bytes = blocks * dev.blockSize
        val pages = bytes / PAGE
        require(pages >= 10) { "The partition is too small for swap" }
        require(label.toByteArray(Charsets.UTF_8).size <= 16)
        val p = ByteArray(PAGE)
        val b = p.le()
        b.putInt(1024, 1)
        b.putInt(1028, (minOf(pages, 0xFFFFFFFFL) - 1).toInt())
        b.putInt(1032, 0)
        // The swap header stores the UUID in plain byte order (like libuuid), not GPT's mixed order.
        val h = uuid.text.replace("-", "")
        for (i in 0 until 16) p[1036 + i] = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        label.toByteArray(Charsets.UTF_8).copyInto(p, 1052)
        "SWAPSPACE2".toByteArray().copyInto(p, PAGE - 10)
        dev.writeBytes(firstLba * dev.blockSize, p)
        return pages
    }
}

/** Changes filesystem labels in place. Only the filesystems [FsSupport] lists as native. */
object Labels {
    fun set(dev: BlockDevice, firstLba: Long, blocks: Long, fs: FsType, label: String) {
        FsSupport.labelProblem(fs, label)?.let { throw IllegalArgumentException(it) }
        when (fs) {
            FsType.FAT12, FsType.FAT16, FsType.FAT32 -> fat(dev, firstLba, label.uppercase())
            FsType.EXFAT -> exfat(dev, firstLba, label)
            FsType.EXT2, FsType.EXT3, FsType.EXT4 -> ext(dev, firstLba, label)
            FsType.SWAP -> swap(dev, firstLba, label)
            else -> throw IllegalArgumentException("Changing ${fs.label} labels isn't supported")
        }
        dev.sync()
    }

    private fun base(dev: BlockDevice, firstLba: Long) = firstLba * dev.blockSize

    private fun fat(dev: BlockDevice, firstLba: Long, label: String) {
        val o = base(dev, firstLba)
        val bs = dev.readBytes(o, 512)
        val bps = bs.u16le(11)
        val spc = bs.u8(13)
        val reserved = bs.u16le(14)
        val fats = bs.u8(16)
        val rootEntries = bs.u16le(17)
        val fat32 = bs.u16le(22) == 0
        val fatSize = if (fat32) bs.u32le(36) else bs.u16le(22).toLong()
        val rootDirSectors = (rootEntries * 32L + bps - 1) / bps
        val dataStart = reserved + fats * fatSize + rootDirSectors
        val name = label.padEnd(11).toByteArray(Charsets.ISO_8859_1)
        val bootName = (label.ifEmpty { "NO NAME" }).padEnd(11).toByteArray(Charsets.ISO_8859_1)

        // Root directory entry (what Windows and blkid show first).
        val (rootOff, rootLen) = if (fat32) {
            val rootCluster = bs.u32le(44)
            (o + (dataStart + (rootCluster - 2) * spc) * bps) to spc * bps
        } else {
            (o + (reserved + fats * fatSize) * bps) to (rootDirSectors * bps).toInt()
        }
        val root = dev.readBytes(rootOff, rootLen)
        var slot = -1
        var free = -1
        var i = 0
        while (i + 32 <= root.size) {
            val first = root.u8(i)
            val attr = root.u8(i + 11)
            if (first == 0) {
                if (free < 0) free = i
                break
            }
            if (first == 0xE5 && free < 0) free = i
            if (first != 0xE5 && attr and 0x0F != 0x0F && attr and 0x08 != 0) {
                slot = i
                break
            }
            i += 32
        }
        if (label.isEmpty()) {
            if (slot >= 0) {
                root[slot] = 0xE5.toByte()
                dev.writeBytes(rootOff + slot, root.copyOfRange(slot, slot + 32))
            }
        } else {
            val at = if (slot >= 0) slot else free
            if (at < 0) throw IOException("The root directory's first cluster is full; can't add a label entry")
            val e = ByteArray(32)
            name.copyInto(e, 0)
            e[11] = 0x08
            if (slot < 0 && at + 32 < root.size && root.u8(at) == 0) {
                // Taking the end-of-directory slot: the next one must still read as the end.
                root[at + 32] = 0
            }
            dev.writeBytes(rootOff + at, e)
        }
        // Boot sector copy of the label, primary and (FAT32) backup.
        val extOff = if (fat32) 64 else 36
        if (bs.u8(extOff + 2) == 0x29) {
            dev.writeBytes(o + extOff + 7, bootName)
            if (fat32) {
                val backup = bs.u16le(50)
                if (backup in 1 until reserved) dev.writeBytes(o + backup.toLong() * bps + extOff + 7, bootName)
            }
        }
    }

    private fun exfat(dev: BlockDevice, firstLba: Long, label: String) {
        val o = base(dev, firstLba)
        val bs = dev.readBytes(o, 512)
        val sector = 1L shl bs.u8(108)
        val cluster = sector shl bs.u8(109)
        val heap = bs.u32le(88)
        val rootCluster = bs.u32le(96)
        val rootOff = o + heap * sector + (rootCluster - 2) * cluster
        val root = dev.readBytes(rootOff, cluster.toInt())
        var slot = -1
        var end = -1
        var i = 0
        while (i + 32 <= root.size) {
            val t = root.u8(i)
            if (t == 0x83 || t == 0x03) {
                slot = i
                break
            }
            if (t == 0) {
                end = i
                break
            }
            i += 32
        }
        val at = if (slot >= 0) slot else end
        if (at < 0) throw IOException("The root directory's first cluster is full; can't add a label entry")
        val e = ByteArray(32)
        e[0] = 0x83.toByte()
        e[1] = label.length.toByte()
        val eb = e.le()
        label.forEachIndexed { k, ch -> eb.putShort(2 + k * 2, ch.code.toShort()) }
        dev.writeBytes(rootOff + at, e)
    }

    private fun ext(dev: BlockDevice, firstLba: Long, label: String) {
        val o = base(dev, firstLba) + 1024
        val sb = dev.readBytes(o, 1024)
        if (sb.u16le(56) != 0xEF53) throw IOException("No ext superblock found")
        val name = ByteArray(16)
        label.toByteArray(Charsets.UTF_8).copyInto(name)
        name.copyInto(sb, 120)
        if (sb.u32le(100) and 0x400L != 0L) { // metadata_csum
            val csum = Crc.crc32cRaw(0xFFFFFFFFL, sb, 0, 0x3FC)
            sb.le().putInt(0x3FC, csum.toInt())
        }
        dev.writeBytes(o, sb)
    }

    private fun swap(dev: BlockDevice, firstLba: Long, label: String) {
        val o = base(dev, firstLba)
        val name = ByteArray(16)
        label.toByteArray(Charsets.UTF_8).copyInto(name)
        dev.writeBytes(o + 1024 + 28, name)
    }
}
