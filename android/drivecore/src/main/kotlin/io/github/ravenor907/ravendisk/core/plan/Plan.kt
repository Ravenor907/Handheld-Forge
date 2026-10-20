package io.github.ravenor907.ravendisk.core.plan

import io.github.ravenor907.ravendisk.core.fs.FsInfo
import io.github.ravenor907.ravendisk.core.fs.FsOp
import io.github.ravenor907.ravendisk.core.fs.FsSupport
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.fs.Support
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.table.Align
import io.github.ravenor907.ravendisk.core.table.Partition
import io.github.ravenor907.ravendisk.core.table.PartitionTable
import io.github.ravenor907.ravendisk.core.table.PartitionTypes
import io.github.ravenor907.ravendisk.core.table.TableKind
import io.github.ravenor907.ravendisk.core.table.TableRead
import io.github.ravenor907.ravendisk.core.util.Crc
import io.github.ravenor907.ravendisk.core.util.Guid
import io.github.ravenor907.ravendisk.core.util.Units

/** Identifies a device well enough to notice if it was swapped or changed before we write. */
data class DiskIdentity(
    val id: String,
    val model: String,
    val serial: String?,
    val blockSize: Int,
    val blockCount: Long,
    /** CRC32 over the first and last 34 blocks, where partition tables live. */
    val fingerprint: Long,
) {
    fun describe() = "$model, ${Units.size(blockSize * blockCount)}" + (serial?.let { ", serial $it" } ?: "")

    /** Why [now] isn't the device this identity was taken from, or null if it is. */
    fun mismatch(now: DiskIdentity): String? = when {
        now.id != id -> "A different device is connected"
        now.blockSize != blockSize || now.blockCount != blockCount -> "The drive's size changed (${Units.size(now.blockSize * now.blockCount)} now)"
        now.model != model || now.serial != serial -> "A different drive is connected (${now.describe()})"
        now.fingerprint != fingerprint -> "The drive's partition table changed since the plan was made"
        else -> null
    }

    companion object {
        fun fingerprint(dev: BlockDevice): Long {
            val n = minOf(34L, dev.blockCount).toInt()
            val crc = java.util.zip.CRC32()
            crc.update(dev.readBlocks(0, n))
            crc.update(dev.readBlocks(dev.blockCount - n, n))
            return crc.value
        }

        fun of(dev: BlockDevice, id: String, model: String, serial: String?) =
            DiskIdentity(id, model, serial, dev.blockSize, dev.blockCount, fingerprint(dev))
    }
}

/** Everything the planner needs to know about a device, captured when it was read. */
data class DiskSnapshot(
    val identity: DiskIdentity,
    val physicalBlockSize: Int?,
    val table: TableRead,
    val fs: Map<Int, FsInfo?>,
    /** Partition numbers Android has mounted (destructive operations on them are refused). */
    val mounted: Set<Int> = emptySet(),
    /** The whole device is in use by Android (e.g. a mounted superfloppy). */
    val deviceMounted: Boolean = false,
    val readOnly: Boolean = false,
    val discardSupported: Boolean = false,
) {
    val blockSize get() = identity.blockSize
    val blockCount get() = identity.blockCount
}

enum class EraseMethod(val label: String, val description: String) {
    ZERO("Overwrite with zeros", "Writes zeros over every block. On SSDs and flash drives, wear leveling can keep old copies of data in spare blocks, so this isn't guaranteed destruction there."),
    DISCARD("Discard (TRIM)", "Tells the drive the blocks are unused. Fast; most SSDs then return zeros, but the drive decides when data is physically erased."),
}

/** One queued change. Operations name partitions by table number, as of the moment they're queued. */
sealed class Op {
    abstract fun describe(blockSize: Int): String

    data class NewTable(val kind: TableKind) : Op() {
        override fun describe(blockSize: Int) = "Create a new ${kind.label} partition table (erases every partition)"
    }

    data class Create(
        val firstLba: Long,
        val lastLba: Long,
        val fs: FsType?,
        val label: String = "",
        val name: String = "",
        val gptType: Guid? = null,
        val mbrType: Int? = null,
    ) : Op() {
        override fun describe(blockSize: Int) =
            "Create a ${Units.sizeBinary((lastLba - firstLba + 1) * blockSize)} partition" +
                (fs?.let { " formatted ${it.label}" } ?: " (unformatted)") + (if (label.isNotEmpty()) " labeled \"$label\"" else "")
    }

    data class Delete(val number: Int) : Op() {
        override fun describe(blockSize: Int) = "Delete partition $number"
    }

    data class Resize(val number: Int, val firstLba: Long, val lastLba: Long) : Op() {
        override fun describe(blockSize: Int) = "Resize partition $number to ${Units.sizeBinary((lastLba - firstLba + 1) * blockSize)}"
    }

    data class Format(val number: Int, val fs: FsType, val label: String = "") : Op() {
        override fun describe(blockSize: Int) = "Format partition $number as ${fs.label}" + (if (label.isNotEmpty()) " labeled \"$label\"" else "")
    }

    data class SetLabel(val number: Int, val label: String) : Op() {
        override fun describe(blockSize: Int) = if (label.isEmpty()) "Clear partition $number's filesystem label" else "Label partition $number's filesystem \"$label\""
    }

    data class SetName(val number: Int, val name: String) : Op() {
        override fun describe(blockSize: Int) = "Name partition $number \"$name\""
    }

    data class SetGptType(val number: Int, val type: Guid) : Op() {
        override fun describe(blockSize: Int) = "Change partition $number's type to ${PartitionTypes.gptName(type)}"
    }

    data class SetMbrType(val number: Int, val type: Int) : Op() {
        override fun describe(blockSize: Int) = "Change partition $number's type to ${PartitionTypes.mbrName(type)}"
    }

    data class SetAttributes(val number: Int, val attributes: Long) : Op() {
        override fun describe(blockSize: Int) = "Change partition $number's flags"
    }

    data class SetBootable(val number: Int, val bootable: Boolean) : Op() {
        override fun describe(blockSize: Int) = if (bootable) "Mark partition $number as active (bootable)" else "Clear partition $number's active flag"
    }

    /** Erase a partition's contents, or the whole device when [number] is null. */
    data class Erase(val number: Int?, val method: EraseMethod) : Op() {
        override fun describe(blockSize: Int) = (if (number == null) "Erase the whole drive" else "Erase partition $number") + ": ${method.label.lowercase()}"
    }

    /** Whether this op destroys existing data. */
    val destructive: Boolean get() = when (this) {
        is NewTable, is Delete, is Format, is Erase, is Resize -> true
        is Create -> false
        else -> false
    }
}

enum class VolumeChange { KEPT, NEW, FORMATTED, MODIFIED, ERASED }

/** A partition in the planned layout. */
data class PlannedVolume(
    val partition: Partition,
    /** Filesystem after the plan: what's there now, or what will be created. */
    val fs: FsType?,
    val label: String?,
    val change: VolumeChange,
    /** What the probe found before the plan, for "will be lost" warnings. */
    val before: FsInfo?,
    /** Set when the filesystem label is changed in place. */
    val relabel: Boolean = false,
    val erase: EraseMethod? = null,
)

data class PlanState(
    val table: PartitionTable?,
    val volumes: List<PlannedVolume>,
    val newTable: Boolean = false,
    val wholeErase: EraseMethod? = null,
) {
    fun volume(n: Int) = volumes.firstOrNull { it.partition.number == n }
}

class PlanException(msg: String) : Exception(msg)

/**
 * Builds and validates a queue of operations against a snapshot. Nothing here touches a device:
 * the planner only computes the layout the queue would produce, refusing anything invalid with a
 * reason the person can act on.
 */
class Planner(val snap: DiskSnapshot) {
    val base: PlanState = run {
        val t = (snap.table as? TableRead.Found)?.table
        PlanState(
            t,
            t?.partitions?.map { p ->
                val f = snap.fs[p.number]
                PlannedVolume(p, f?.type, f?.label, VolumeChange.KEPT, f)
            } ?: emptyList(),
        )
    }

    private val ops = ArrayList<Op>()
    private val states = ArrayList<PlanState>()

    val queue: List<Op> get() = ops
    val current: PlanState get() = states.lastOrNull() ?: base

    /** Validates [op] against the current planned layout and queues it, or throws [PlanException]. */
    fun add(op: Op): PlanState {
        val next = apply(current, op)
        ops.add(op)
        states.add(next)
        return next
    }

    /** What [op] would produce, without queuing it. Throws [PlanException] when invalid. */
    fun preview(op: Op): PlanState = apply(current, op)

    /** The reason [op] can't be queued now, or null if it can. */
    fun problem(op: Op): String? = try {
        apply(current, op)
        null
    } catch (e: PlanException) {
        e.message
    }

    fun undo(): Boolean {
        if (ops.isEmpty()) return false
        ops.removeAt(ops.size - 1)
        states.removeAt(states.size - 1)
        return true
    }

    fun clear() {
        ops.clear()
        states.clear()
    }

    /** Replays [list] from the base, e.g. to rebuild a plan after an edit. */
    fun replay(list: List<Op>) {
        clear()
        list.forEach { add(it) }
    }

    private fun fail(msg: String): Nothing = throw PlanException(msg)

    private fun apply(s: PlanState, op: Op): PlanState {
        if (snap.readOnly) fail("The drive is write-protected")
        if (snap.deviceMounted && op.destructive) fail("Android has this drive mounted. Tap Unmount at the top of the drive page first.")
        return when (op) {
            is Op.NewTable -> newTable(op)
            is Op.Create -> create(s, op)
            is Op.Delete -> delete(s, op)
            is Op.Resize -> resize(s, op)
            is Op.Format -> format(s, op)
            is Op.SetLabel -> setLabel(s, op)
            is Op.SetName -> edit(s, op.number, gptOnly = true) {
                if (op.name.length > 36) fail("GPT names are at most 36 characters")
                if (op.name.any { it.isISOControl() }) fail("Names can't contain control characters")
                it.copy(name = op.name)
            }
            is Op.SetGptType -> edit(s, op.number, gptOnly = true) { it.copy(typeGuid = op.type) }
            is Op.SetMbrType -> edit(s, op.number, mbrOnly = true) {
                if (op.type == 0 || PartitionTypes.isExtended(op.type) || op.type == 0xEE) fail("That type can't be set here")
                it.copy(mbrType = op.type)
            }
            is Op.SetAttributes -> edit(s, op.number, gptOnly = true) { it.copy(attributes = op.attributes) }
            is Op.SetBootable -> {
                val t = edit(s, op.number, mbrOnly = true) { it.copy(bootable = op.bootable) }
                // Only one active partition on an MBR disk.
                if (op.bootable) {
                    t.copy(
                        table = t.table!!.copy(partitions = t.table.partitions.map { if (it.number != op.number) it.copy(bootable = false) else it }),
                        volumes = t.volumes.map { v ->
                            if (v.partition.number != op.number && v.partition.bootable) v.copy(partition = v.partition.copy(bootable = false), change = if (v.change == VolumeChange.KEPT) VolumeChange.MODIFIED else v.change) else v
                        },
                    )
                } else t
            }
            is Op.Erase -> erase(s, op)
        }
    }

    private fun newTable(op: Op.NewTable): PlanState {
        if (snap.mounted.isNotEmpty()) fail("Android has partitions on this drive mounted. Tap Unmount at the top of the drive page first.")
        val t = when (op.kind) {
            TableKind.GPT -> {
                if (snap.blockCount < 128) fail("The drive is too small for GPT")
                PartitionTable.emptyGpt(snap.blockSize, snap.blockCount)
            }
            TableKind.MBR -> {
                if (snap.blockCount > 0xFFFFFFFFL) fail("MBR can't address drives over 2 TiB; use GPT")
                PartitionTable.emptyMbr(snap.blockCount)
            }
        }
        return PlanState(t, emptyList(), newTable = true, wholeErase = current.wholeErase)
    }

    private fun table(s: PlanState): PartitionTable = s.table ?: fail("Create a partition table first")

    private fun requireEditable(s: PlanState) {
        val t = table(s)
        if (t.kind == TableKind.MBR && t.hasLogical) fail("This drive has logical (extended) partitions, which can't be edited yet")
        if (t.damaged && !s.newTable) fail("The partition table is damaged (${t.issues.first()}). Back up the data and create a new table.")
    }

    private fun create(s: PlanState, op: Op.Create): PlanState {
        val t = table(s)
        requireEditable(s)
        val number = t.nextNumber() ?: fail(if (t.kind == TableKind.MBR) "MBR allows four primary partitions" else "The partition table is full")
        validateRange(t, op.firstLba, op.lastLba, ignore = null)
        val bytes = (op.lastLba - op.firstLba + 1) * snap.blockSize
        op.fs?.let { fs ->
            checkCreatable(fs, op.lastLba - op.firstLba + 1)
            FsSupport.labelProblem(fs, op.label)?.let { fail(it) }
        }
        if (t.kind == TableKind.GPT && op.name.length > 36) fail("GPT names are at most 36 characters")
        val p = when (t.kind) {
            TableKind.GPT -> Partition(
                number, op.firstLba, op.lastLba,
                typeGuid = op.gptType ?: op.fs?.gptType ?: PartitionTypes.LINUX_FS, uniqueGuid = Guid.random(), name = op.name,
            )
            TableKind.MBR -> Partition(number, op.firstLba, op.lastLba, mbrType = op.mbrType ?: op.fs?.mbrType ?: 0x83)
        }
        return s.copy(
            table = t.copy(partitions = t.partitions + p),
            volumes = s.volumes + PlannedVolume(p, op.fs, op.label.ifEmpty { null }, VolumeChange.NEW, null),
        )
    }

    private fun checkCreatable(fs: FsType, blocks: Long) {
        val bytes = blocks * snap.blockSize
        if (FsSupport.of(fs, FsOp.CREATE) != Support.Native) fail("Creating ${fs.label} isn't available in this version")
        if (bytes < FsSupport.minBytes(fs)) fail("${fs.label} needs at least ${Units.sizeBinary(FsSupport.minBytes(fs))}")
        if (bytes > FsSupport.maxBytes(fs)) fail("${fs.label} can't be larger than ${Units.sizeBinary(FsSupport.maxBytes(fs))}")
        if (fs == FsType.FAT32 && blocks > 0xFFFFFFFFL) fail("FAT32 can't be that large")
        FsSupport.layoutProblem(fs, snap.blockSize, blocks)?.let { fail(it) }
    }

    private fun validateRange(t: PartitionTable, first: Long, last: Long, ignore: Int?) {
        if (first > last) fail("The partition ends before it starts")
        if (first < t.firstUsable) fail("The partition starts inside the partition table area (first usable block is ${t.firstUsable})")
        if (last > t.lastUsable) fail("The partition runs past the end of the usable area")
        if (t.kind == TableKind.MBR && last > 0xFFFFFFFFL) fail("MBR can't describe space beyond 2 TiB")
        val phys = ((snap.physicalBlockSize ?: snap.blockSize) / snap.blockSize).toLong()
        if (!Align.isAligned(first, phys)) fail("The start isn't aligned to the drive's ${snap.physicalBlockSize}-byte physical sectors")
        if (last - first + 1 < Align.mibBlocks(snap.blockSize)) fail("Partitions must be at least 1 MiB")
        for (p in t.partitions) {
            if (p.number == ignore) continue
            if (p.mbrType != null && PartitionTypes.isExtended(p.mbrType)) {
                if (first <= p.lastLba && p.firstLba <= last) fail("That space belongs to the extended partition")
                continue
            }
            if (first <= p.lastLba && p.firstLba <= last) fail("That space overlaps partition ${p.number}")
        }
    }

    private fun existing(s: PlanState, n: Int): PlannedVolume {
        val v = s.volume(n) ?: fail("Partition $n doesn't exist")
        if (v.partition.logical) fail("Logical partitions can't be changed yet")
        if (v.partition.mbrType != null && PartitionTypes.isExtended(v.partition.mbrType)) fail("Extended partitions can't be changed yet")
        return v
    }

    private fun notMounted(v: PlannedVolume) {
        if (v.change == VolumeChange.KEPT || v.change == VolumeChange.MODIFIED) {
            if (v.partition.number in snap.mounted) fail("Android has partition ${v.partition.number} mounted. Tap Unmount at the top of the drive page first.")
        }
    }

    private fun delete(s: PlanState, op: Op.Delete): PlanState {
        requireEditable(s)
        val v = existing(s, op.number)
        notMounted(v)
        val t = table(s)
        return s.copy(
            table = t.copy(partitions = t.partitions.filter { it.number != op.number }),
            volumes = s.volumes.filter { it.partition.number != op.number },
        )
    }

    private fun resize(s: PlanState, op: Op.Resize): PlanState {
        requireEditable(s)
        val v = existing(s, op.number)
        notMounted(v)
        val hasData = v.change == VolumeChange.KEPT || v.change == VolumeChange.MODIFIED
        if (hasData && v.before != null) {
            val why = when (val sup = FsSupport.of(v.before.type, FsOp.RESIZE)) {
                is Support.Planned -> "${sup.what}. Until then a partition with data keeps its size."
                is Support.No -> sup.reason
                Support.Native -> null
            }
            if (why != null) fail(why)
        }
        if (hasData && op.firstLba != v.partition.firstLba) fail("Moving a partition that holds data isn't supported yet")
        val t = table(s)
        validateRange(t, op.firstLba, op.lastLba, ignore = op.number)
        v.fs?.let { if (v.change == VolumeChange.NEW || v.change == VolumeChange.FORMATTED) checkCreatable(it, op.lastLba - op.firstLba + 1) }
        val p = v.partition.copy(firstLba = op.firstLba, lastLba = op.lastLba)
        return replace(s, p, v.copy(partition = p, change = if (v.change == VolumeChange.KEPT) VolumeChange.MODIFIED else v.change))
    }

    private fun format(s: PlanState, op: Op.Format): PlanState {
        val v = existing(s, op.number)
        notMounted(v)
        checkCreatable(op.fs, v.partition.sectors)
        FsSupport.labelProblem(op.fs, op.label)?.let { fail(it) }
        val t = table(s)
        // Keep an EFI system partition's type when it stays FAT; otherwise match the filesystem.
        val p = when (t.kind) {
            TableKind.GPT -> {
                val keep = v.partition.typeGuid == PartitionTypes.EFI_SYSTEM && op.fs == FsType.FAT32
                v.partition.copy(typeGuid = if (keep) v.partition.typeGuid else op.fs.gptType ?: v.partition.typeGuid)
            }
            TableKind.MBR -> {
                val keep = v.partition.mbrType == 0xEF && op.fs == FsType.FAT32
                v.partition.copy(mbrType = if (keep) 0xEF else op.fs.mbrType ?: v.partition.mbrType)
            }
        }
        val change = if (v.change == VolumeChange.NEW) VolumeChange.NEW else VolumeChange.FORMATTED
        return replace(s, p, v.copy(partition = p, fs = op.fs, label = op.label.ifEmpty { null }, change = change, relabel = false))
    }

    private fun setLabel(s: PlanState, op: Op.SetLabel): PlanState {
        val v = existing(s, op.number)
        notMounted(v)
        val fs = v.fs ?: fail("Partition ${op.number} has no filesystem to label")
        if (v.change == VolumeChange.ERASED) fail("Partition ${op.number} will be erased")
        val pending = v.change == VolumeChange.NEW || v.change == VolumeChange.FORMATTED
        if (!pending && FsSupport.of(fs, FsOp.LABEL) != Support.Native) {
            fail((FsSupport.of(fs, FsOp.LABEL) as? Support.No)?.reason ?: "Changing ${fs.label} labels isn't supported")
        }
        FsSupport.labelProblem(fs, op.label)?.let { fail(it) }
        val change = if (v.change == VolumeChange.KEPT) VolumeChange.MODIFIED else v.change
        return replace(s, v.partition, v.copy(label = op.label.ifEmpty { null }, change = change, relabel = v.relabel || !pending))
    }

    private fun edit(s: PlanState, n: Int, gptOnly: Boolean = false, mbrOnly: Boolean = false, f: (Partition) -> Partition): PlanState {
        val t = table(s)
        if (gptOnly && t.kind != TableKind.GPT) fail("Only GPT partitions have that")
        if (mbrOnly && t.kind != TableKind.MBR) fail("Only MBR partitions have that")
        requireEditable(s)
        val v = existing(s, n)
        val p = f(v.partition)
        return replace(s, p, v.copy(partition = p, change = if (v.change == VolumeChange.KEPT) VolumeChange.MODIFIED else v.change))
    }

    private fun erase(s: PlanState, op: Op.Erase): PlanState {
        if (op.method == EraseMethod.DISCARD && !snap.discardSupported) fail("This drive doesn't support discard")
        if (op.number == null) {
            if (snap.mounted.isNotEmpty()) fail("Android has partitions on this drive mounted. Tap Unmount at the top of the drive page first.")
            // Erasing everything leaves no table; the person can create one in the same plan.
            return PlanState(null, emptyList(), newTable = false, wholeErase = op.method)
        }
        val v = existing(s, op.number)
        notMounted(v)
        return replace(s, v.partition, v.copy(fs = null, label = null, change = VolumeChange.ERASED, erase = op.method, relabel = false))
    }

    private fun replace(s: PlanState, p: Partition, v: PlannedVolume): PlanState {
        val t = table(s)
        return s.copy(
            table = t.copy(partitions = t.partitions.map { if (it.number == p.number) p else it }),
            volumes = s.volumes.map { if (it.partition.number == p.number) v else it },
        )
    }

    companion object {
        /**
         * Suggests where a new partition goes inside [free]: start rounded up to 1 MiB, end rounded
         * down to 1 MiB (or the region's end when it's the last MiB), at most [sizeBytes].
         */
        fun placeInFree(free: LongRange, blockSize: Int, sizeBytes: Long?, physicalBlockSize: Int? = null): LongRange? {
            val mib = Align.mibBlocks(blockSize)
            val start = Align.up(free.first, mib)
            if (start > free.last) return null
            val wanted = sizeBytes?.let { (it + blockSize - 1) / blockSize } ?: (free.last - start + 1)
            var end = minOf(free.last, start + wanted - 1)
            val alignedEnd = Align.up(end + 1, mib) - 1
            end = if (alignedEnd <= free.last) alignedEnd else Align.down(end + 1, mib) - 1
            if (end < start) end = free.last
            if (end - start + 1 < mib) return null
            physicalBlockSize?.let { if (!Align.isAligned(start, (it / blockSize).toLong())) return null }
            return start..end
        }

        /** CRC of the planned table, so the executor can check what landed on disk. */
        fun tableDigest(t: PartitionTable): Long = Crc.crc32(t.sorted.joinToString("|") { "${it.number}:${it.firstLba}:${it.lastLba}:${it.typeGuid}:${it.mbrType}:${it.name}:${it.attributes}:${it.bootable}" }.toByteArray())
    }
}
