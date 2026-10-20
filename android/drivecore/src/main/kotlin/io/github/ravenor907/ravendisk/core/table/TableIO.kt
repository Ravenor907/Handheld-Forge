package io.github.ravenor907.ravendisk.core.table

import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.util.Crc
import io.github.ravenor907.ravendisk.core.util.Guid
import io.github.ravenor907.ravendisk.core.util.le
import io.github.ravenor907.ravendisk.core.util.regionEquals
import io.github.ravenor907.ravendisk.core.util.u32le
import io.github.ravenor907.ravendisk.core.util.u64le
import io.github.ravenor907.ravendisk.core.util.u8
import java.io.IOException

/** What sits at the start of a device. */
sealed class TableRead {
    data class Found(val table: PartitionTable) : TableRead()

    /** No partition table and nothing recognizable: a blank or wiped device. */
    object Blank : TableRead()

    /** A filesystem directly on the device, no partition table ("superfloppy"). */
    object Superfloppy : TableRead()

    /** Something is there, but it isn't a table this app understands. */
    data class Unreadable(val reason: String) : TableRead()
}

object TableReader {
    private val EFI_PART = "EFI PART".toByteArray(Charsets.US_ASCII)

    fun read(dev: BlockDevice): TableRead {
        if (dev.blockCount < 3) return TableRead.Unreadable("The device is too small to hold a partition table")
        val lba0 = dev.readBlocks(0, 1)
        val hasSig = lba0.u8(510) == 0x55 && lba0.u8(511) == 0xAA
        val mbrEntries = if (hasSig) parseMbrEntries(lba0) else null
        val protective = mbrEntries != null && mbrEntries.any { it.type == 0xEE }

        val gpt = readGpt(dev, lba0, protective)
        if (gpt != null) return TableRead.Found(gpt)

        if (!hasSig) {
            return if (looksLikeVbr(lba0)) TableRead.Superfloppy else TableRead.Blank
        }
        if (mbrEntries == null) {
            return if (looksLikeVbr(lba0)) TableRead.Superfloppy else TableRead.Unreadable("Sector 0 has a boot signature but no valid partition table")
        }
        if (protective) return TableRead.Unreadable("The protective MBR says GPT, but neither GPT header could be read")
        if (mbrEntries.isEmpty() && looksLikeVbr(lba0)) return TableRead.Superfloppy
        return TableRead.Found(readMbr(dev, lba0, mbrEntries))
    }

    private class RawMbrEntry(val slot: Int, val status: Int, val type: Int, val start: Long, val sectors: Long)

    /** The four primary entries, or null when they can't be an MBR (bad status bytes, overlaps). */
    private fun parseMbrEntries(s: ByteArray): List<RawMbrEntry>? {
        val out = ArrayList<RawMbrEntry>()
        for (i in 0 until 4) {
            val o = 446 + i * 16
            val status = s.u8(o)
            if (status != 0 && status != 0x80) return null
            val type = s.u8(o + 4)
            val start = s.u32le(o + 8)
            val count = s.u32le(o + 12)
            if (type == 0 || count == 0L) continue
            out.add(RawMbrEntry(i + 1, status, type, start, count))
        }
        return out
    }

    /** A FAT/exFAT/NTFS boot sector at LBA 0 rather than an MBR. */
    private fun looksLikeVbr(s: ByteArray): Boolean {
        val jump = s.u8(0) == 0xEB && s.u8(2) == 0x90 || s.u8(0) == 0xE9
        if (!jump) return false
        val oem = String(s, 3, 8, Charsets.US_ASCII)
        if (oem == "EXFAT   " || oem == "NTFS    ") return true
        val bps = s[11].toInt() and 0xFF or ((s[12].toInt() and 0xFF) shl 8)
        return bps in setOf(512, 1024, 2048, 4096) && s.u8(13) != 0
    }

    private fun readGpt(dev: BlockDevice, lba0: ByteArray, protective: Boolean): PartitionTable? {
        val issues = ArrayList<String>()
        var damaged = false
        val primary = gptHeaderAt(dev, 1)
        val backupLba = primary?.alternateLba?.takeIf { it in 2 until dev.blockCount } ?: (dev.blockCount - 1)
        val backup = gptHeaderAt(dev, backupLba)
        val use = when {
            primary != null && primary.entriesOk -> primary
            backup != null && backup.entriesOk -> backup.also { issues.add("The primary GPT is damaged; read the backup at the end of the drive") }
            primary != null -> primary.also {
                issues.add("The partition entry array's checksum doesn't match")
                damaged = true
            }
            else -> return null
        }
        if (primary != null && backup == null) issues.add("The backup GPT at the end of the drive is missing or damaged")
        if (primary != null && primary.alternateLba != dev.blockCount - 1) {
            issues.add("The backup GPT isn't at the end of the drive (the drive may have been cloned from a different size)")
        }
        if (!protective) issues.add("LBA 0 has no protective MBR")
        val hybrid = protective && parseMbrEntries(lba0)?.any { it.type != 0xEE } == true
        if (hybrid) issues.add("The protective MBR is a hybrid MBR; writing the table will replace it with a plain protective MBR")

        val parts = ArrayList<Partition>()
        val e = use.entries
        for (i in 0 until use.entryCount) {
            val o = i * use.entrySize
            val type = Guid.fromBytes(e, o)
            if (type.isZero) continue
            val first = e.u64le(o + 32)
            val last = e.u64le(o + 40)
            if (first > last || last >= dev.blockCount) {
                issues.add("Entry ${i + 1} has an invalid range and was skipped")
                damaged = true
                continue
            }
            val nameBytes = e.copyOfRange(o + 56, o + 56 + 72)
            val name = String(nameBytes, Charsets.UTF_16LE).substringBefore('\u0000')
            parts.add(
                Partition(
                    number = i + 1, firstLba = first, lastLba = last, typeGuid = type,
                    uniqueGuid = Guid.fromBytes(e, o + 16), name = name, attributes = e.u64le(o + 48),
                ),
            )
        }
        for (a in parts) for (b in parts) if (a.number < b.number && a.overlaps(b)) {
            issues.add("Partitions ${a.number} and ${b.number} overlap")
            damaged = true
        }
        return PartitionTable(
            TableKind.GPT, parts, use.firstUsable, use.lastUsable, diskGuid = use.diskGuid,
            entryCount = use.entryCount, entrySize = use.entrySize, issues = issues,
            protectiveMbr = protective, hybridMbr = hybrid, damaged = damaged,
        )
    }

    class GptHeader(
        val myLba: Long,
        val alternateLba: Long,
        val firstUsable: Long,
        val lastUsable: Long,
        val diskGuid: Guid,
        val entriesLba: Long,
        val entryCount: Int,
        val entrySize: Int,
        val entries: ByteArray,
        val entriesOk: Boolean,
    )

    fun gptHeaderAt(dev: BlockDevice, lba: Long): GptHeader? {
        if (lba < 1 || lba >= dev.blockCount) return null
        val h = try {
            dev.readBlocks(lba, 1)
        } catch (e: IOException) {
            return null
        }
        if (!h.regionEquals(0, EFI_PART)) return null
        val size = h.u32le(12).toInt()
        if (size < 92 || size > dev.blockSize) return null
        val crc = h.u32le(16)
        val copy = h.copyOf(size)
        for (i in 16 until 20) copy[i] = 0
        if (Crc.crc32(copy) != crc) return null
        if (h.u64le(24) != lba) return null
        val first = h.u64le(40)
        val last = h.u64le(48)
        val count = h.u32le(80).toInt()
        val esize = h.u32le(84).toInt()
        if (esize < 128 || esize % 8 != 0 || count <= 0 || count > 4096 || first > last || last >= dev.blockCount) return null
        val entriesLba = h.u64le(72)
        val bytes = count.toLong() * esize
        val blocks = ((bytes + dev.blockSize - 1) / dev.blockSize).toInt()
        if (entriesLba < 1 || entriesLba + blocks > dev.blockCount) return null
        val entries = try {
            dev.readBlocks(entriesLba, blocks).copyOf(bytes.toInt())
        } catch (e: IOException) {
            return null
        }
        return GptHeader(
            lba, h.u64le(32), first, last, Guid.fromBytes(h, 56), entriesLba, count, esize, entries,
            entriesOk = Crc.crc32(entries) == h.u32le(88),
        )
    }

    private fun readMbr(dev: BlockDevice, lba0: ByteArray, entries: List<RawMbrEntry>): PartitionTable {
        val issues = ArrayList<String>()
        val parts = ArrayList<Partition>()
        var damaged = false
        for (e in entries) {
            val last = e.start + e.sectors - 1
            if (e.start == 0L || last >= dev.blockCount) {
                issues.add("Partition ${e.slot} reaches past the end of the drive")
                damaged = true
                continue
            }
            parts.add(Partition(e.slot, e.start, last, mbrType = e.type, bootable = e.status == 0x80))
            if (PartitionTypes.isExtended(e.type)) parts.addAll(readLogicals(dev, e.start, last, issues))
        }
        val sorted = parts.filter { !PartitionTypes.isExtended(it.mbrType ?: 0) }
        for (a in sorted) for (b in sorted) if (a.number < b.number && a.overlaps(b)) {
            issues.add("Partitions ${a.number} and ${b.number} overlap")
            damaged = true
        }
        val sig = lba0.u32le(440)
        return PartitionTable(
            TableKind.MBR, parts, firstUsable = 1, lastUsable = minOf(dev.blockCount - 1, 0xFFFFFFFEL),
            diskSignature = sig, issues = issues, damaged = damaged,
        )
    }

    /** Walks the EBR chain inside an extended partition. */
    private fun readLogicals(dev: BlockDevice, extStart: Long, extLast: Long, issues: MutableList<String>): List<Partition> {
        val out = ArrayList<Partition>()
        var ebr = extStart
        val seen = HashSet<Long>()
        var number = 5
        while (number < 5 + 128) {
            if (!seen.add(ebr) || ebr > extLast) {
                issues.add("The logical partition chain loops or leaves the extended partition")
                break
            }
            val s = dev.readBlocks(ebr, 1)
            if (s.u8(510) != 0x55 || s.u8(511) != 0xAA) {
                if (ebr != extStart) issues.add("A logical partition's boot record is missing its signature")
                break
            }
            val type = s.u8(446 + 4)
            val rel = s.u32le(446 + 8)
            val count = s.u32le(446 + 12)
            if (type != 0 && count != 0L) {
                val first = ebr + rel
                val last = first + count - 1
                if (last > extLast) issues.add("Logical partition $number reaches past the extended partition")
                else out.add(Partition(number, first, last, mbrType = type, bootable = s.u8(446) == 0x80, logical = true))
                number++
            }
            val nextType = s.u8(462 + 4)
            val nextRel = s.u32le(462 + 8)
            if (nextType == 0 || nextRel == 0L) break
            ebr = extStart + nextRel
        }
        return out
    }
}

object TableWriter {
    /**
     * Writes [t] to [dev]. GPT: protective MBR, primary header and array, backup array and header.
     * MBR: the four primary entries, keeping boot code and disk signature.
     * Logical MBR partitions are not written (the planner refuses tables that contain them).
     */
    fun write(dev: BlockDevice, t: PartitionTable) {
        when (t.kind) {
            TableKind.GPT -> writeGpt(dev, t)
            TableKind.MBR -> writeMbr(dev, t)
        }
    }

    fun gptImage(dev: BlockDevice, t: PartitionTable): Map<Long, ByteArray> {
        require(t.kind == TableKind.GPT)
        val bs = dev.blockSize
        val eb = PartitionTable.gptEntryBlocks(bs, t.entryCount, t.entrySize)
        val primaryEntriesLba = 2L
        val backupHeaderLba = dev.blockCount - 1
        val backupEntriesLba = backupHeaderLba - eb
        require(t.firstUsable >= primaryEntriesLba + eb) { "First usable LBA overlaps the GPT entry array" }
        require(t.lastUsable < backupEntriesLba) { "Last usable LBA overlaps the backup GPT" }
        require(t.partitions.size <= t.entryCount)

        val entries = ByteArray((eb * bs).toInt())
        for (p in t.partitions) {
            require(p.number in 1..t.entryCount) { "Entry number ${p.number} out of range" }
            require(p.firstLba >= t.firstUsable && p.lastLba <= t.lastUsable) { "Partition ${p.number} is outside the usable area" }
            val o = (p.number - 1) * t.entrySize
            val b = entries.le()
            (p.typeGuid ?: error("GPT partition ${p.number} has no type")).toBytes().copyInto(entries, o)
            (p.uniqueGuid ?: Guid.random()).toBytes().copyInto(entries, o + 16)
            b.putLong(o + 32, p.firstLba)
            b.putLong(o + 40, p.lastLba)
            b.putLong(o + 48, p.attributes)
            val name = p.name.take(36).toByteArray(Charsets.UTF_16LE)
            name.copyInto(entries, o + 56)
        }
        val arrayCrc = Crc.crc32(entries, 0, t.entryCount * t.entrySize)
        val disk = t.diskGuid ?: Guid.random()
        fun header(my: Long, alt: Long, entriesLba: Long): ByteArray {
            val h = ByteArray(bs)
            val b = h.le()
            EFI_PART_BYTES.copyInto(h, 0)
            b.putInt(8, 0x00010000)
            b.putInt(12, 92)
            b.putLong(24, my)
            b.putLong(32, alt)
            b.putLong(40, t.firstUsable)
            b.putLong(48, t.lastUsable)
            disk.toBytes().copyInto(h, 56)
            b.putLong(72, entriesLba)
            b.putInt(80, t.entryCount)
            b.putInt(84, t.entrySize)
            b.putInt(88, arrayCrc.toInt())
            b.putInt(16, Crc.crc32(h, 0, 92).toInt())
            return h
        }
        return linkedMapOf(
            primaryEntriesLba to entries,
            1L to header(1, backupHeaderLba, primaryEntriesLba),
            backupEntriesLba to entries,
            backupHeaderLba to header(backupHeaderLba, 1, backupEntriesLba),
        )
    }

    private fun writeGpt(dev: BlockDevice, t: PartitionTable) {
        val image = gptImage(dev, t)
        // Protective MBR: keep any boot code, one 0xEE entry covering the disk.
        val old = dev.readBlocks(0, 1)
        val mbr = ByteArray(dev.blockSize)
        old.copyInto(mbr, 0, 0, 440)
        val b = mbr.le()
        val o = 446
        mbr[o] = 0
        mbr[o + 1] = 0; mbr[o + 2] = 2; mbr[o + 3] = 0
        mbr[o + 4] = 0xEE.toByte()
        mbr[o + 5] = 0xFF.toByte(); mbr[o + 6] = 0xFF.toByte(); mbr[o + 7] = 0xFF.toByte()
        b.putInt(o + 8, 1)
        b.putInt(o + 12, minOf(dev.blockCount - 1, 0xFFFFFFFFL).toInt())
        mbr[510] = 0x55; mbr[511] = 0xAA.toByte()
        // Backup first, primary last, each array before its header: a crash midway leaves at least
        // one consistent (old or new) copy for tools to recover from.
        val bs = dev.blockSize
        val (pe, ph, be, bh) = image.keys.toList()
        for (lba in listOf(be, bh, pe, ph)) image.getValue(lba).let { dev.write(lba, it.size / bs, it) }
        dev.write(0, 1, mbr)
        dev.sync()
    }

    fun mbrSector(old: ByteArray, t: PartitionTable, blockCount: Long): ByteArray {
        require(t.kind == TableKind.MBR)
        require(!t.hasLogical) { "Tables with logical partitions can't be written yet" }
        require(t.partitions.size <= 4)
        val s = ByteArray(old.size)
        old.copyInto(s, 0, 0, 440)
        val b = s.le()
        t.diskSignature?.let { b.putInt(440, it.toInt()) }
        for (i in 0 until 64) s[446 + i] = 0
        for (p in t.partitions) {
            require(p.number in 1..4)
            require(p.lastLba < blockCount && p.lastLba <= 0xFFFFFFFFL && p.firstLba >= 1) { "Partition ${p.number} can't be described by an MBR" }
            val o = 446 + (p.number - 1) * 16
            s[o] = if (p.bootable) 0x80.toByte() else 0
            chs(p.firstLba).copyInto(s, o + 1)
            s[o + 4] = (p.mbrType ?: error("MBR partition ${p.number} has no type")).toByte()
            chs(p.lastLba).copyInto(s, o + 5)
            b.putInt(o + 8, p.firstLba.toInt())
            b.putInt(o + 12, p.sectors.toInt())
        }
        s[510] = 0x55; s[511] = 0xAA.toByte()
        return s
    }

    private fun writeMbr(dev: BlockDevice, t: PartitionTable) {
        val old = dev.readBlocks(0, 1)
        // Switching from GPT: clear both GPT headers so nothing mistakes the disk for GPT later.
        val zero = ByteArray(dev.blockSize)
        if (TableReader.gptHeaderAt(dev, 1) != null) dev.write(1, 1, zero)
        if (TableReader.gptHeaderAt(dev, dev.blockCount - 1) != null) dev.write(dev.blockCount - 1, 1, zero)
        dev.write(0, 1, mbrSector(old, t, dev.blockCount))
        dev.sync()
    }

    /** CHS with 255 heads / 63 sectors; far sectors get the "use LBA" marker. */
    private fun chs(lba: Long): ByteArray {
        val spt = 63
        val heads = 255
        val c = lba / (spt * heads)
        if (c > 1023) return byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val h = (lba / spt) % heads
        val s = (lba % spt) + 1
        return byteArrayOf(h.toByte(), ((s and 0x3F) or ((c shr 2) and 0xC0)).toByte(), (c and 0xFF).toByte())
    }

    private val EFI_PART_BYTES = "EFI PART".toByteArray(Charsets.US_ASCII)
}
