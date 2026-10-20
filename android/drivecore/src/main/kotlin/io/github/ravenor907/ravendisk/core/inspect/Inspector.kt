package io.github.ravenor907.ravendisk.core.inspect

import io.github.ravenor907.ravendisk.core.fs.FsInfo
import io.github.ravenor907.ravendisk.core.fs.Probe
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableRead
import io.github.ravenor907.ravendisk.core.table.TableReader

/** What's on a device: its table and the filesystem inside each partition. Read-only. */
data class Inspection(
    val table: TableRead,
    val fs: Map<Int, FsInfo?>,
    /** Filesystem on the whole device when there's no partition table. */
    val wholeDeviceFs: FsInfo? = null,
)

object Inspector {
    fun inspect(dev: BlockDevice): Inspection {
        val table = TableReader.read(dev)
        return when (table) {
            is TableRead.Found -> {
                val fs = table.table.partitions.associate { p ->
                    p.number to if (p.mbrType != null && PartitionTypes.isExtended(p.mbrType)) null else Probe.probe(dev, p.firstLba, p.sectors)
                }
                Inspection(table, fs)
            }
            TableRead.Superfloppy, TableRead.Blank, is TableRead.Unreadable -> {
                val whole = Probe.probe(dev, 0, dev.blockCount)
                val t = if (table == TableRead.Blank && whole != null) TableRead.Superfloppy else table
                Inspection(t, emptyMap(), whole)
            }
        }
    }
}
