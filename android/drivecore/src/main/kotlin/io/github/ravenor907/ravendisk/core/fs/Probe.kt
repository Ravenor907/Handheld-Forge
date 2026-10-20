package io.github.ravenor907.ravendisk.core.fs

import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.util.regionEquals
import io.github.ravenor907.ravendisk.core.util.u16le
import io.github.ravenor907.ravendisk.core.util.u32le
import io.github.ravenor907.ravendisk.core.util.u64le
import io.github.ravenor907.ravendisk.core.util.u8
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale

/** What a probe found on a partition. Fields the probe couldn't read stay null. */
data class FsInfo(
    val type: FsType,
    val label: String? = null,
    /** The identifier blkid would print (UUID, or the FAT/exFAT/NTFS serial). */
    val uuid: String? = null,
    val sizeBytes: Long? = null,
    val freeBytes: Long? = null,
    /** True when [freeBytes] is the filesystem's own hint (FAT32 FSInfo), not a count. */
    val freeIsHint: Boolean = false,
    val detail: String? = null,
    val clusterBytes: Int? = null,
)

/**
 * Identifies filesystems from their on-disk signatures and reads labels, identifiers and sizes.
 * Read-only. Everything is bounds-checked: a probe never throws on garbage, it returns null.
 */
object Probe {
    private const val WINDOW = 72 * 1024

    /** Probes the filesystem that starts at block [firstLba] and spans [blocks] blocks. */
    fun probe(dev: BlockDevice, firstLba: Long, blocks: Long): FsInfo? {
        val bytes = blocks * dev.blockSize
        if (bytes < 4096) return null
        val win = try {
            readAt(dev, firstLba, 0, minOf(WINDOW.toLong(), bytes).toInt())
        } catch (e: IOException) {
            return null
        }
        val r = Reader(dev, firstLba, bytes, win)
        return try {
            r.luks() ?: r.bitlocker() ?: r.exfat() ?: r.ntfs() ?: r.fat() ?: r.ext() ?: r.f2fs() ?: r.xfs() ?: r.btrfs()
                ?: r.swap() ?: r.hfs() ?: r.apfs() ?: r.lvm() ?: r.mdraid() ?: r.iso9660() ?: r.udf()
        } catch (e: Exception) {
            null
        }
    }

    private fun readAt(dev: BlockDevice, firstLba: Long, offset: Long, length: Int): ByteArray =
        dev.readBytes(firstLba * dev.blockSize + offset, length)

    private class Reader(val dev: BlockDevice, val first: Long, val size: Long, val w: ByteArray) {
        fun has(at: Int, s: String) = w.regionEquals(at, s.toByteArray(Charsets.ISO_8859_1))
        fun at(off: Long, len: Int): ByteArray? =
            if (off + len <= size) try {
                readAt(dev, first, off, len)
            } catch (e: IOException) {
                null
            } else null

        fun ascii(b: ByteArray, off: Int, len: Int): String =
            String(b, off, len, Charsets.ISO_8859_1).substringBefore('\u0000').trimEnd()

        fun utf8(b: ByteArray, off: Int, len: Int): String? {
            val raw = b.copyOfRange(off, off + len)
            val n = raw.indexOf(0).let { if (it < 0) len else it }
            return String(raw, 0, n, Charsets.UTF_8).ifEmpty { null }
        }

        fun uuid(b: ByteArray, off: Int): String {
            val h = (0 until 16).joinToString("") { "%02x".format(b[off + it]) }
            return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
        }

        fun luks(): FsInfo? {
            if (!w.regionEquals(0, byteArrayOf(0x4C, 0x55, 0x4B, 0x53, 0xBA.toByte(), 0xBE.toByte()))) return null
            val version = w.u8(6) shl 8 or w.u8(7)
            val label = if (version == 2) ascii(w, 24, 48).ifEmpty { null } else null
            return FsInfo(FsType.LUKS, label, ascii(w, 168, 40).ifEmpty { null }, size, detail = "LUKS$version")
        }

        fun bitlocker(): FsInfo? = if (has(3, "-FVE-FS-")) FsInfo(FsType.BITLOCKER, sizeBytes = size) else null

        fun exfat(): FsInfo? {
            if (!has(3, "EXFAT   ")) return null
            val bpsShift = w.u8(108)
            val spcShift = w.u8(109)
            if (bpsShift !in 9..12 || spcShift > 25 - bpsShift) return null
            val sector = 1L shl bpsShift
            val cluster = sector shl spcShift
            val fatOffset = w.u32le(80)
            val heap = w.u32le(88)
            val clusters = w.u32le(92)
            val rootCluster = w.u32le(96)
            val serial = w.u32le(100)
            val volLen = w.u64le(72) * sector
            fun clusterOffset(c: Long) = heap * sector + (c - 2) * cluster
            fun fatNext(c: Long): Long {
                val b = at(fatOffset * sector + c * 4, 4) ?: return 0xFFFFFFFFL
                return b.u32le(0)
            }
            var label: String? = null
            var bitmapCluster = -1L
            var bitmapLen = 0L
            // The root directory's first cluster holds the label and allocation-bitmap entries.
            at(clusterOffset(rootCluster), minOf(cluster, 64 * 1024L).toInt())?.let { root ->
                var i = 0
                while (i + 32 <= root.size) {
                    val t = root.u8(i)
                    if (t == 0) break
                    if (t == 0x83) {
                        val n = minOf(root.u8(i + 1), 11)
                        label = String(root, i + 2, n * 2, Charsets.UTF_16LE)
                    }
                    if (t == 0x81 && bitmapCluster < 0) {
                        bitmapCluster = root.u32le(i + 20)
                        bitmapLen = root.u64le(i + 24)
                    }
                    i += 32
                }
            }
            var free: Long? = null
            if (bitmapCluster >= 2 && bitmapLen in 1..(8L shl 20)) {
                val bits = ByteArray(bitmapLen.toInt())
                var c = bitmapCluster
                var got = 0
                var guard = 0
                while (got < bits.size && c in 2..(clusters + 1) && guard++ < 1_000_000) {
                    val n = minOf(cluster.toInt(), bits.size - got)
                    val chunk = at(clusterOffset(c), n) ?: break
                    chunk.copyInto(bits, got)
                    got += n
                    // mkfs tools chain the bitmap in the FAT; a zero entry means "contiguous".
                    c = fatNext(c).let { if (it == 0L) c + 1 else it }
                }
                if (got == bits.size) {
                    var used = 0L
                    val whole = (clusters / 8).toInt()
                    for (k in 0 until whole) used += Integer.bitCount(bits[k].toInt() and 0xFF)
                    for (k in whole * 8 until clusters.toInt()) if (bits[k ushr 3].toInt() and (1 shl (k and 7)) != 0) used++
                    free = (clusters - used) * cluster
                }
            }
            return FsInfo(
                FsType.EXFAT, label, serialDash(serial), volLen, free, clusterBytes = cluster.toInt(),
                detail = "Revision ${w.u8(105)}.${"%02d".format(w.u8(104))}",
            )
        }

        fun ntfs(): FsInfo? {
            if (!has(3, "NTFS    ")) return null
            val bps = w.u16le(11)
            val spc = w.u8(13)
            if (bps !in setOf(512, 1024, 2048, 4096) || spc == 0) return null
            val clusterBytes = if (spc > 128) 1L shl (256 - spc) else spc.toLong() * bps
            val total = w.u64le(0x28) * bps
            val serial = w.u64le(0x48)
            val mftCluster = w.u64le(0x30)
            val rec = w[0x40].toInt().let { if (it < 0) 1 shl -it else it * clusterBytes.toInt() }
            var label: String? = null
            if (rec in 1024..4096) {
                at(mftCluster * clusterBytes + 3L * rec, rec)?.let { r -> label = ntfsVolumeName(r, bps) }
            }
            val uuid = "%016X".format(serial)
            return FsInfo(FsType.NTFS, label, uuid, total, clusterBytes = clusterBytes.toInt())
        }

        /** $Volume's VOLUME_NAME attribute, after applying the record's update sequence fixups. */
        private fun ntfsVolumeName(r: ByteArray, bps: Int): String? {
            if (!r.regionEquals(0, "FILE".toByteArray())) return null
            val usaOff = r.u16le(4)
            val usaCount = r.u16le(6)
            for (i in 1 until usaCount) {
                val end = i * bps - 2
                val src = usaOff + i * 2
                if (end + 1 >= r.size || src + 1 >= r.size) break
                r[end] = r[src]
                r[end + 1] = r[src + 1]
            }
            var a = r.u16le(20)
            var guard = 0
            while (a + 16 < r.size && guard++ < 64) {
                val type = r.u32le(a)
                if (type == 0xFFFFFFFFL) break
                val len = r.u32le(a + 4).toInt()
                if (len <= 0) break
                if (type == 0x60L && r.u8(a + 8) == 0) {
                    val vlen = r.u32le(a + 16).toInt()
                    val voff = r.u16le(a + 20)
                    if (a + voff + vlen <= r.size) return String(r, a + voff, vlen, Charsets.UTF_16LE)
                }
                a += len
            }
            return null
        }

        fun fat(): FsInfo? {
            val jump = (w.u8(0) == 0xEB && w.u8(2) == 0x90) || w.u8(0) == 0xE9
            if (!jump) return null
            val bps = w.u16le(11)
            val spc = w.u8(13)
            val reserved = w.u16le(14)
            val fats = w.u8(16)
            val rootEntries = w.u16le(17)
            val total16 = w.u16le(19)
            val fatSize16 = w.u16le(22)
            val total32 = w.u32le(32)
            if (bps !in setOf(512, 1024, 2048, 4096) || spc == 0 || Integer.bitCount(spc) != 1 || reserved == 0 || fats !in 1..2) return null
            if (w.u8(510) != 0x55 || w.u8(511) != 0xAA) return null
            val fat32 = fatSize16 == 0
            val fatSize = if (fat32) w.u32le(36) else fatSize16.toLong()
            val totalSectors = if (total16 != 0) total16.toLong() else total32
            if (fatSize == 0L || totalSectors == 0L) return null
            val rootDirSectors = ((rootEntries * 32L) + bps - 1) / bps
            val dataStart = reserved + fats * fatSize + rootDirSectors
            val clusters = (totalSectors - dataStart) / spc
            val kind = when {
                fat32 -> FsType.FAT32
                clusters < 4085 -> FsType.FAT12
                else -> FsType.FAT16
            }
            val extOff = if (fat32) 64 else 36
            val hasExt = w.u8(extOff + 2) == 0x29
            val serial = if (hasExt) w.u32le(extOff + 3) else null
            var label = if (hasExt) ascii(w, extOff + 7, 11).trim().takeIf { it.isNotEmpty() && it != "NO NAME" } else null
            // Windows and Linux show the root directory's label entry; prefer it.
            val clusterBytes = spc * bps
            val rootBytes = if (fat32) {
                val rootCluster = w.u32le(44)
                at((dataStart + (rootCluster - 2) * spc) * bps, minOf(clusterBytes, 32 * 1024))
            } else {
                at((reserved + fats * fatSize) * bps, minOf((rootDirSectors * bps).toInt(), 32 * 1024))
            }
            rootBytes?.let { label = fatRootLabel(it) ?: label }
            var free: Long? = null
            if (fat32) {
                val fsinfo = w.u16le(48)
                at(fsinfo.toLong() * bps, 512)?.let { s ->
                    if (s.u32le(0) == 0x41615252L && s.u32le(484) == 0x61417272L) {
                        val n = s.u32le(488)
                        if (n != 0xFFFFFFFFL && n <= clusters) free = n * clusterBytes
                    }
                }
            }
            return FsInfo(
                kind, label, serial?.let { serialDash(it) }, totalSectors * bps, free, freeIsHint = fat32,
                clusterBytes = clusterBytes,
            )
        }

        fun ext(): FsInfo? {
            if (w.size < 2048 || w.u16le(1024 + 56) != 0xEF53) return null
            val sb = 1024
            val logBlock = w.u32le(sb + 24).toInt()
            if (logBlock > 6) return null
            val block = 1024L shl logBlock
            val compat = w.u32le(sb + 92)
            val incompat = w.u32le(sb + 96)
            val roCompat = w.u32le(sb + 100)
            val is64 = incompat and 0x80 != 0L
            val blocks = w.u32le(sb + 4) or (if (is64) w.u32le(sb + 0x150) shl 32 else 0)
            val freeBlocks = w.u32le(sb + 12) or (if (is64) w.u32le(sb + 0x158) shl 32 else 0)
            val type = when {
                incompat and 0x2C0 != 0L || roCompat and 0x478 != 0L -> FsType.EXT4 // extents, 64bit, flex_bg, huge_file, dir_nlink, extra_isize, metadata_csum
                compat and 0x4 != 0L -> FsType.EXT3
                else -> FsType.EXT2
            }
            val label = utf8(w, sb + 120, 16)
            return FsInfo(
                type, label, uuid(w, sb + 104), blocks * block, freeBlocks * block, clusterBytes = block.toInt(),
                detail = if (roCompat and 0x400 != 0L) "Metadata checksums" else null,
            )
        }

        fun f2fs(): FsInfo? {
            if (w.size < 2048 || w.u32le(1024) != 0xF2F52010L) return null
            val sb = 1024
            val logBlock = w.u32le(sb + 0x10).toInt()
            val blockCount = w.u64le(sb + 0x24)
            val nameBytes = w.copyOfRange(sb + 0x7C, minOf(w.size, sb + 0x7C + 1024))
            val label = String(nameBytes, Charsets.UTF_16LE).substringBefore('\u0000').ifEmpty { null }
            return FsInfo(
                FsType.F2FS, label, uuid(w, sb + 0x6C), blockCount shl logBlock,
                detail = "Version ${w.u16le(sb + 4)}.${w.u16le(sb + 6)}",
            )
        }

        fun xfs(): FsInfo? {
            if (!has(0, "XFSB")) return null
            val bb = ByteBuffer.wrap(w)
            val bsize = bb.getInt(4).toLong()
            val dblocks = bb.getLong(8)
            val free = bb.getLong(144)
            return FsInfo(FsType.XFS, utf8(w, 108, 12), uuid(w, 32), dblocks * bsize, free * bsize)
        }

        fun btrfs(): FsInfo? {
            val s = at(65536, 4096) ?: return null
            if (!s.regionEquals(64, "_BHRfS_M".toByteArray())) return null
            val total = s.u64le(0x70)
            val used = s.u64le(0x78)
            val label = String(s, 0x12B, 256, Charsets.UTF_8).substringBefore('\u0000').ifEmpty { null }
            return FsInfo(FsType.BTRFS, label, uuid(s, 32), total, (total - used).coerceAtLeast(0))
        }

        fun swap(): FsInfo? {
            for (page in intArrayOf(4096, 16384, 65536)) {
                val b = if (page <= w.size) w else at(0, page) ?: continue
                if (b.size < page) continue
                val sig = String(b, page - 10, 10, Charsets.ISO_8859_1)
                if (sig != "SWAPSPACE2" && sig != "SWAP-SPACE") continue
                val label = utf8(b, 1024 + 28, 16)
                val lastPage = b.u32le(1024 + 4)
                return FsInfo(
                    FsType.SWAP, label, if (sig == "SWAPSPACE2") uuid(b, 1024 + 12) else null,
                    (lastPage + 1) * page, detail = "${page / 1024} KiB pages",
                )
            }
            return null
        }

        fun hfs(): FsInfo? = if (has(1024, "H+") || has(1024, "HX")) FsInfo(FsType.HFSPLUS, sizeBytes = size) else null
        fun apfs(): FsInfo? = if (has(32, "NXSB")) FsInfo(FsType.APFS, sizeBytes = size) else null
        fun lvm(): FsInfo? = if ((0..3).any { has(it * 512, "LABELONE") && has(it * 512 + 24, "LVM2 001") }) FsInfo(FsType.LVM2, sizeBytes = size) else null
        fun mdraid(): FsInfo? = if (w.size > 4100 && w.u32le(4096) == 0xA92B4EFCL) FsInfo(FsType.MDRAID, sizeBytes = size) else null

        fun iso9660(): FsInfo? {
            if (w.size < 32768 + 2048 || !has(32769, "CD001")) return null
            val label = ascii(w, 32768 + 40, 32).trim().ifEmpty { null }
            return FsInfo(FsType.ISO9660, label, sizeBytes = size)
        }

        fun udf(): FsInfo? {
            var off = 32768
            var nsr = false
            while (off + 6 <= w.size && off < 32768 + 2048 * 16) {
                val id = String(w, off + 1, 5, Charsets.ISO_8859_1)
                if (id == "NSR02" || id == "NSR03") nsr = true
                if (id !in setOf("BEA01", "NSR02", "NSR03", "TEA01", "CD001", "CDW02", "BOOT2")) break
                off += 2048
            }
            return if (nsr) FsInfo(FsType.UDF, sizeBytes = size) else null
        }
    }

    /** FAT's volume label directory entry (attribute 0x08, not a long-name entry). */
    fun fatRootLabel(root: ByteArray): String? {
        var i = 0
        while (i + 32 <= root.size) {
            val first = root.u8(i)
            if (first == 0) break
            val attr = root.u8(i + 11)
            if (first != 0xE5 && attr and 0x0F != 0x0F && attr and 0x08 != 0) {
                return String(root, i, 11, Charsets.ISO_8859_1).trimEnd().ifEmpty { null }
            }
            i += 32
        }
        return null
    }

    fun serialDash(v: Long): String = "%04X-%04X".format(Locale.ROOT, (v ushr 16) and 0xFFFF, v and 0xFFFF)
}
