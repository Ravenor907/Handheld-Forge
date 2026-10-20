package io.github.ravenor907.ravendisk.core.table

import io.github.ravenor907.ravendisk.core.util.Guid

enum class TableKind(val label: String) {
    GPT("GPT"),
    MBR("MBR"),
}

/**
 * One partition as stored in the table. LBAs are inclusive and in the device's logical blocks.
 * GPT-only and MBR-only fields are null/zero for the other kind.
 */
data class Partition(
    /** GPT entry slot (1-based) or MBR partition number (1-4 primary, 5+ logical). */
    val number: Int,
    val firstLba: Long,
    val lastLba: Long,
    val typeGuid: Guid? = null,
    val uniqueGuid: Guid? = null,
    val name: String = "",
    val attributes: Long = 0,
    val mbrType: Int? = null,
    val bootable: Boolean = false,
    val logical: Boolean = false,
) {
    init {
        require(firstLba in 0..lastLba) { "Partition $number has its end before its start" }
    }

    val sectors: Long get() = lastLba - firstLba + 1
    fun sizeBytes(blockSize: Int) = sectors * blockSize
    fun overlaps(o: Partition) = firstLba <= o.lastLba && o.firstLba <= lastLba

    /** A human name for the partition type. */
    fun typeName(): String = typeGuid?.let { PartitionTypes.gptName(it) } ?: mbrType?.let { PartitionTypes.mbrName(it) } ?: "Unknown"
}

/** A parsed (or planned) partition table. */
data class PartitionTable(
    val kind: TableKind,
    val partitions: List<Partition>,
    /** First and last LBA partitions may use. */
    val firstUsable: Long,
    val lastUsable: Long,
    val diskGuid: Guid? = null,
    /** MBR disk signature (bytes 440-443), or null. */
    val diskSignature: Long? = null,
    /** GPT: number of entry slots in the array (usually 128). */
    val entryCount: Int = 128,
    val entrySize: Int = 128,
    /** Problems found while reading (bad CRC, missing backup). Empty for a healthy table. */
    val issues: List<String> = emptyList(),
    /** GPT: whether LBA 0 holds a proper protective MBR. */
    val protectiveMbr: Boolean = true,
    /** GPT: the protective MBR also lists real partitions (hybrid MBR). */
    val hybridMbr: Boolean = false,
    /**
     * Entries were skipped, overlap or failed their checksum: rewriting the table could lose
     * partitions, so editing is refused until a new table is created. Issues without this flag
     * (missing backup, misplaced backup) are repaired by any write.
     */
    val damaged: Boolean = false,
) {
    val sorted: List<Partition> get() = partitions.sortedBy { it.firstLba }
    val hasLogical: Boolean get() = partitions.any { it.logical }

    fun byNumber(n: Int): Partition? = partitions.firstOrNull { it.number == n }

    /** Next free slot number for a new partition, or null when the table is full. */
    fun nextNumber(): Int? = when (kind) {
        TableKind.GPT -> (1..entryCount).firstOrNull { n -> partitions.none { it.number == n } }
        TableKind.MBR -> (1..4).firstOrNull { n -> partitions.none { it.number == n } }
    }

    /** Unallocated regions between [firstUsable] and [lastUsable], inclusive LBAs. */
    fun freeRegions(): List<LongRange> {
        val out = ArrayList<LongRange>()
        var cursor = firstUsable
        for (p in sorted) {
            if (p.firstLba > cursor) out.add(cursor until p.firstLba)
            cursor = maxOf(cursor, p.lastLba + 1)
        }
        if (cursor <= lastUsable) out.add(cursor..lastUsable)
        return out.filter { !it.isEmpty() }
    }

    companion object {
        /** Blocks the GPT entry array takes. */
        fun gptEntryBlocks(blockSize: Int, entryCount: Int = 128, entrySize: Int = 128): Long =
            (entryCount.toLong() * entrySize + blockSize - 1) / blockSize

        /** An empty GPT for a device of [blockCount] blocks. */
        fun emptyGpt(blockSize: Int, blockCount: Long, diskGuid: Guid = Guid.random()): PartitionTable {
            val eb = gptEntryBlocks(blockSize)
            return PartitionTable(
                TableKind.GPT, emptyList(), firstUsable = 2 + eb, lastUsable = blockCount - 2 - eb, diskGuid = diskGuid,
            )
        }

        /** An empty MBR table. Partitions may start after LBA 0 and must end within 2^32 sectors. */
        fun emptyMbr(blockCount: Long, signature: Long = java.security.SecureRandom().nextInt().toLong() and 0xFFFFFFFFL): PartitionTable =
            PartitionTable(
                TableKind.MBR, emptyList(), firstUsable = 1, lastUsable = minOf(blockCount - 1, 0xFFFFFFFFL - 1),
                diskSignature = signature,
            )
    }
}

object PartitionTypes {
    val EFI_SYSTEM = Guid("C12A7328-F81F-11D2-BA4B-00A0C93EC93B")
    val MS_BASIC_DATA = Guid("EBD0A0A2-B9E5-4433-87C0-68B6B72699C7")
    val MS_RESERVED = Guid("E3C9E316-0B5C-4DB8-817D-F92DF00215AE")
    val WINDOWS_RECOVERY = Guid("DE94BBA4-06D1-4D40-A16A-BFD50179D6AC")
    val LINUX_FS = Guid("0FC63DAF-8483-4772-8E79-3D69D8477DE4")
    val LINUX_SWAP = Guid("0657FD6D-A4AB-43C4-84E5-0933C84B4F4F")
    val LINUX_LVM = Guid("E6D6D379-F507-44C2-A23C-238F2A3DF928")
    val LINUX_RAID = Guid("A19D880F-05FC-4D3B-A006-743F0F84911E")
    val LINUX_ROOT_X86_64 = Guid("4F68BCE3-E8CD-4DB1-96E7-FBCAF984B709")
    val LINUX_ROOT_ARM64 = Guid("B921B045-1DF0-41C3-AF44-4C6F280D3FAE")
    val LINUX_HOME = Guid("933AC7E1-2EB4-4F13-B844-0E14E2AEF915")
    val LINUX_LUKS = Guid("CA7D7CCB-63ED-4C53-861C-1742536059CC")
    val LINUX_XBOOTLDR = Guid("BC13C2FF-59E6-4262-A352-B275FD6F7172")
    val BIOS_BOOT = Guid("21686148-6449-6E6F-744E-656564454649")
    val APPLE_APFS = Guid("7C3457EF-0000-11AA-AA11-00306543ECAC")
    val APPLE_HFS = Guid("48465300-0000-11AA-AA11-00306543ECAC")
    val CHROMEOS_KERNEL = Guid("FE3A2A5D-4F32-41A7-B725-ACCC3285A309")
    val CHROMEOS_ROOT = Guid("3CB8E202-3B7E-47DD-8A3C-7FF2A13CFCEC")

    /** Types offered when creating or retyping a GPT partition, most useful first. */
    val GPT_CHOICES: List<Pair<Guid, String>> = listOf(
        MS_BASIC_DATA to "Basic data (Windows, macOS, Android)",
        LINUX_FS to "Linux filesystem",
        EFI_SYSTEM to "EFI system partition",
        LINUX_SWAP to "Linux swap",
        LINUX_HOME to "Linux /home",
        LINUX_ROOT_X86_64 to "Linux root (x86-64)",
        LINUX_LUKS to "Linux LUKS",
        LINUX_LVM to "Linux LVM",
        BIOS_BOOT to "BIOS boot",
        MS_RESERVED to "Microsoft reserved",
        WINDOWS_RECOVERY to "Windows recovery",
    )

    private val gptNames = mapOf(
        EFI_SYSTEM to "EFI system",
        MS_BASIC_DATA to "Basic data",
        MS_RESERVED to "Microsoft reserved",
        WINDOWS_RECOVERY to "Windows recovery",
        LINUX_FS to "Linux filesystem",
        LINUX_SWAP to "Linux swap",
        LINUX_LVM to "Linux LVM",
        LINUX_RAID to "Linux RAID",
        LINUX_ROOT_X86_64 to "Linux root (x86-64)",
        LINUX_ROOT_ARM64 to "Linux root (ARM64)",
        LINUX_HOME to "Linux /home",
        LINUX_LUKS to "Linux LUKS",
        LINUX_XBOOTLDR to "Linux extended boot",
        BIOS_BOOT to "BIOS boot",
        APPLE_APFS to "Apple APFS",
        APPLE_HFS to "Apple HFS+",
        CHROMEOS_KERNEL to "ChromeOS kernel",
        CHROMEOS_ROOT to "ChromeOS root",
    )

    fun gptName(g: Guid): String = gptNames[g] ?: "Unknown type"

    val MBR_CHOICES: List<Pair<Int, String>> = listOf(
        0x0C to "FAT32 (LBA)",
        0x07 to "NTFS / exFAT",
        0x83 to "Linux",
        0x82 to "Linux swap",
        0xEF to "EFI system",
        0x0E to "FAT16 (LBA)",
        0x8E to "Linux LVM",
        0xFD to "Linux RAID",
    )

    private val mbrNames = mapOf(
        0x01 to "FAT12", 0x04 to "FAT16 (small)", 0x06 to "FAT16", 0x07 to "NTFS / exFAT", 0x0B to "FAT32",
        0x0C to "FAT32 (LBA)", 0x0E to "FAT16 (LBA)", 0x05 to "Extended", 0x0F to "Extended (LBA)",
        0x85 to "Linux extended", 0x82 to "Linux swap", 0x83 to "Linux", 0x8E to "Linux LVM", 0xEE to "GPT protective",
        0xEF to "EFI system", 0xFD to "Linux RAID", 0xA5 to "FreeBSD", 0xAF to "Apple HFS+", 0x17 to "Hidden NTFS",
        0x27 to "Windows recovery", 0x1C to "Hidden FAT32",
    )

    fun mbrName(t: Int): String = mbrNames[t] ?: "Type 0x%02X".format(t)

    fun isExtended(t: Int) = t == 0x05 || t == 0x0F || t == 0x85

    /** GPT attribute bits worth naming. */
    val GPT_ATTRIBUTES: List<Pair<Int, String>> = listOf(
        0 to "Required by the platform",
        1 to "Ignored by EFI firmware",
        2 to "Legacy BIOS bootable",
        60 to "Read-only",
        62 to "Hidden",
        63 to "Don't mount automatically",
    )
}

/** Alignment helpers. 1 MiB is what every modern partitioner uses. */
object Align {
    const val MIB = 1024L * 1024

    fun mibBlocks(blockSize: Int): Long = MIB / blockSize

    fun up(v: Long, a: Long): Long = if (a <= 1) v else (v + a - 1) / a * a
    fun down(v: Long, a: Long): Long = if (a <= 1) v else v / a * a
    fun isAligned(v: Long, a: Long) = a <= 1 || v % a == 0L

    /**
     * Whether [firstLba] sits on a physical block boundary, and on a MiB one. Misalignment to the
     * physical block size costs real performance on 4K drives; MiB alignment is the convention.
     */
    fun describe(firstLba: Long, blockSize: Int, physicalBlockSize: Int?): String {
        val phys = (physicalBlockSize ?: blockSize) / blockSize
        return when {
            isAligned(firstLba, mibBlocks(blockSize)) -> "1 MiB aligned"
            isAligned(firstLba, phys.toLong()) -> "Aligned to the physical sector, not to 1 MiB"
            else -> "Not aligned to the physical sector (slower writes)"
        }
    }
}
