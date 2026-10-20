package io.github.ravenor907.ravendisk.core.demo

import io.github.ravenor907.ravendisk.core.fs.ExFat
import io.github.ravenor907.ravendisk.core.fs.Fat32
import io.github.ravenor907.ravendisk.core.fs.Swap
import io.github.ravenor907.ravendisk.core.fs.writeBytes
import io.github.ravenor907.ravendisk.core.health.AtaIdentity
import io.github.ravenor907.ravendisk.core.health.HealthReport
import io.github.ravenor907.ravendisk.core.health.Smart
import io.github.ravenor907.ravendisk.core.io.SparseDisk
import io.github.ravenor907.ravendisk.core.model.Connection
import io.github.ravenor907.ravendisk.core.model.MediaKind
import io.github.ravenor907.ravendisk.core.table.Partition
import io.github.ravenor907.ravendisk.core.table.PartitionTable
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableWriter
import io.github.ravenor907.ravendisk.core.util.Guid
import io.github.ravenor907.ravendisk.core.util.le
import java.util.Random

/** A simulated drive for the debug demo and tests. Its contents are real, written by the app's own code. */
class DemoDrive(
    val id: String,
    val vendor: String,
    val model: String,
    val serial: String?,
    val connection: Connection,
    val media: MediaKind,
    val disk: SparseDisk,
    val health: HealthReport,
    val physicalBlockSize: Int = 512,
    val discard: Boolean = false,
)

object Demo {
    private const val GB = 1_000_000_000L
    private const val MIB = 1024L * 1024

    fun drives(): List<DemoDrive> = listOf(flashDrive(), portableSsd(), sataEnclosure())

    /** A 64 GB stick formatted the way it ships: MBR, one exFAT partition. */
    fun flashDrive(): DemoDrive {
        val d = SparseDisk(512, 64 * GB / 512 / 2048 * 2048)
        val start = 2048L
        val end = d.blockCount - 1
        ExFat.format(d, start, end - start + 1, "ULTRA FIT", serial = 0x5A3C11F0)
        TableWriter.write(d, PartitionTable.emptyMbr(d.blockCount, 0x1E2D3C4BL).copy(partitions = listOf(Partition(1, start, end, mbrType = 0x07))))
        return DemoDrive(
            "demo:flash", "SanDisk", "Ultra Fit", "4C530001230817114522", Connection.USB, MediaKind.FLASH_DRIVE, d,
            HealthReport.unavailable("USB flash drives don't report health data"),
        )
    }

    /** A 1 TB USB-C SSD set up for a handheld: EFI, swap, Linux, games. */
    fun portableSsd(): DemoDrive {
        val d = SparseDisk(512, 1000 * GB / 512)
        val rng = Random(7)
        var t = PartitionTable.emptyGpt(512, d.blockCount, Guid.random(rng))
        fun mib(n: Long) = n * MIB / 512
        val efi = Partition(1, mib(1), mib(513) - 1, PartitionTypes.EFI_SYSTEM, Guid.random(rng), "EFI system")
        val swap = Partition(2, mib(513), mib(513 + 8192) - 1, PartitionTypes.LINUX_SWAP, Guid.random(rng), "swap")
        val root = Partition(3, mib(8705), mib(8705 + 131072) - 1, PartitionTypes.LINUX_FS, Guid.random(rng), "root")
        val games = Partition(4, mib(139777), mib(139777 + 240_000) - 1, PartitionTypes.MS_BASIC_DATA, Guid.random(rng), "games")
        t = t.copy(partitions = listOf(efi, swap, root, games))
        Fat32.format(d, efi.firstLba, efi.sectors, "EFI", serial = 0x0C1A2B3C)
        Swap.format(d, swap.firstLba, swap.sectors, "swap", Guid.random(rng))
        fakeExt4(d, root.firstLba, root.sectors, "rootfs", rng)
        ExFat.format(d, games.firstLba, games.sectors, "GAMES", serial = 0x7A11E5D0)
        TableWriter.write(d, t)
        return DemoDrive(
            "demo:ssd", "Samsung", "Portable SSD T7", "S5SXNG0R812345", Connection.USB, MediaKind.SSD, d,
            HealthReport.unavailable("This USB bridge doesn't pass health (SMART) commands through"),
            discard = true,
        )
    }

    /** A 2 TB hard drive in a USB-SATA enclosure, no partition table yet, with SMART data. */
    fun sataEnclosure(): DemoDrive {
        val d = SparseDisk(512, 2000 * GB / 512)
        val ident = AtaIdentity("WDC WD20EZBX-00AYRA0", "WD-WX12A3456789", "01.01A01", true, true, false, 7200)
        val attrs = listOf(
            Smart.Attribute(5, 200, 200, 0, 140),
            Smart.Attribute(9, 97, 97, 2_431, 0),
            Smart.Attribute(12, 100, 100, 318, 0),
            Smart.Attribute(194, 116, 104, 34, 0),
            Smart.Attribute(197, 200, 200, 0, 0),
            Smart.Attribute(198, 100, 253, 0, 0),
            Smart.Attribute(199, 200, 200, 0, 0),
        )
        val health = Smart.build(ident, attrs, "Last self-test completed without error", true, "ATA SMART through the USB-SATA bridge")
        return DemoDrive(
            "demo:hdd", "WD", "Blue 2TB (USB-SATA)", "WD-WX12A3456789", Connection.USB, MediaKind.HDD, d, health,
            physicalBlockSize = 4096,
        )
    }

    /**
     * Enough of an ext4 superblock for the probe to recognize (magic, sizes, label, UUID, features).
     * Demo only: the app can't create ext4 itself, and this isn't a mountable filesystem.
     */
    private fun fakeExt4(d: SparseDisk, first: Long, sectors: Long, label: String, rng: Random) {
        val sb = ByteArray(1024)
        val b = sb.le()
        val blocks = sectors * 512 / 4096
        b.putInt(4, blocks.toInt())
        b.putInt(12, (blocks * 7 / 10).toInt())
        b.putInt(24, 2) // 4 KiB blocks
        b.putShort(56, 0xEF53.toShort())
        b.putInt(92, 0x3C)
        b.putInt(96, 0x2C2)
        b.putInt(100, 0x46B)
        val u = ByteArray(16).also { rng.nextBytes(it) }
        u.copyInto(sb, 104)
        label.toByteArray().copyInto(sb, 120)
        d.writeBytes(first * 512 + 1024, sb)
    }
}
