package io.github.ravenor907.ravendisk.core.plan

import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.inspect.Inspector
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.table.TableKind

/**
 * "Format drive": wipe the layout and set the drive up as one partition with one filesystem.
 * Shared by Raven Disk Utility and Handheld Forge, so both make exactly the same drive, through
 * the same planner (validation) and executor (identity check, write order, read-back checks).
 */
object QuickFormat {
    /** The filesystems offered, with who reads them. */
    val CHOICES: List<Pair<FsType, String>> = listOf(
        FsType.EXFAT to "Phones, Windows, Mac, Linux, consoles. Best choice for most people.",
        FsType.FAT32 to "Older devices, car stereos, cameras, PC firmware. Files up to 4 GB.",
        FsType.EXT4 to "Linux only: Steam Deck, Bazzite, SteamOS game drives.",
    )

    /** MBR is the most widely readable; GPT is needed past 2 TiB. */
    fun tableKind(snap: DiskSnapshot): TableKind =
        if (snap.blockSize.toLong() * snap.blockCount > 2L * 1024 * 1024 * 1024 * 1024) TableKind.GPT else TableKind.MBR

    /** The operations, or why this filesystem can't go on this drive. */
    fun plan(snap: DiskSnapshot, fs: FsType, label: String): Pair<List<Op>?, String?> {
        val kind = tableKind(snap)
        val t = Planner(snap)
        return try {
            t.add(Op.NewTable(kind))
            val free = t.current.table!!.freeRegions().maxByOrNull { it.last - it.first } ?: return null to "No space on the drive"
            val r = Planner.placeInFree(free, snap.blockSize, null, snap.physicalBlockSize) ?: return null to "The drive is too small"
            val ops = listOf(Op.NewTable(kind), Op.Create(r.first, r.last, fs, label.trim()))
            t.add(ops[1])
            ops to null
        } catch (e: PlanException) {
            null to e.message
        }
    }

    /** Reads what's on [dev] now, as the planner needs it. */
    fun snapshot(dev: BlockDevice, id: String, model: String, serial: String?, physicalBlockSize: Int? = null): DiskSnapshot {
        val ins = Inspector.inspect(dev)
        return DiskSnapshot(DiskIdentity.of(dev, id, model, serial), physicalBlockSize, ins.table, ins.fs, readOnly = dev.readOnly)
    }

    /** Formats the drive in [session] in one go: plan, validate, write, verify. */
    fun run(
        session: DiskSession,
        snap: DiskSnapshot,
        fs: FsType,
        label: String,
        listener: RunListener = object : RunListener {},
        journal: Journal = NoJournal,
        cancelled: () -> Boolean = { false },
    ): RunResult {
        val (ops, problem) = plan(snap, fs, label)
        if (ops == null) return RunResult(Outcome.FAILED, problem ?: "Can't format this drive", emptyList(), emptyList())
        val p = Planner(snap)
        ops.forEach { p.add(it) }
        return Executor(session, p, listener, journal, cancelled).run()
    }
}
