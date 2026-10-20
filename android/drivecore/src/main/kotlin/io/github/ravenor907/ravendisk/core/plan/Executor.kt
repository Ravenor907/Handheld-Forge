package io.github.ravenor907.ravendisk.core.plan

import io.github.ravenor907.ravendisk.core.job.CancelledException
import io.github.ravenor907.ravendisk.core.fs.Ext4
import io.github.ravenor907.ravendisk.core.fs.ExFat
import io.github.ravenor907.ravendisk.core.fs.Fat32
import io.github.ravenor907.ravendisk.core.fs.FsSupport
import io.github.ravenor907.ravendisk.core.fs.FsType
import io.github.ravenor907.ravendisk.core.fs.Labels
import io.github.ravenor907.ravendisk.core.fs.Probe
import io.github.ravenor907.ravendisk.core.fs.Swap
import io.github.ravenor907.ravendisk.core.fs.Wipe
import io.github.ravenor907.ravendisk.core.fs.zero
import io.github.ravenor907.ravendisk.core.io.BlockDevice
import io.github.ravenor907.ravendisk.core.table.Partition
import io.github.ravenor907.ravendisk.core.table.PartitionTable
import io.github.ravenor907.ravendisk.core.table.TableRead
import io.github.ravenor907.ravendisk.core.table.TableReader
import io.github.ravenor907.ravendisk.core.table.TableWriter
import io.github.ravenor907.ravendisk.core.util.isAllZero
import java.io.IOException

/** An open device the executor can write to. Implemented per backend. */
interface DiskSession : AutoCloseable {
    val dev: BlockDevice

    /** Reads the identity fresh from the device (not from a cache). */
    fun identity(): DiskIdentity

    /** Discards (TRIM/UNMAP) a block range. Only called when the snapshot said discard works. */
    fun discard(lba: Long, blocks: Long) {
        throw IOException("This drive doesn't support discard")
    }
}


enum class Outcome(val label: String) {
    SUCCESS("Done"),
    PARTIAL("Done, with warnings"),
    FAILED("Failed"),
    CANCELLED("Stopped"),
}

data class StepResult(val title: String, val ok: Boolean, val detail: String)

data class RunResult(
    val outcome: Outcome,
    val summary: String,
    val steps: List<StepResult>,
    /** What verification found; shown on the result screen. */
    val checks: List<String>,
)

interface RunListener {
    fun step(index: Int, count: Int, title: String) {}
    fun progress(done: Long, total: Long) {}
    fun log(line: String) {}
}

/** Persists what's running, so an interrupted operation is reported on the next launch. */
interface Journal {
    fun begin(device: String, ops: List<String>)
    fun step(title: String)
    fun end(result: RunResult)
}

object NoJournal : Journal {
    override fun begin(device: String, ops: List<String>) {}
    override fun step(title: String) {}
    override fun end(result: RunResult) {}
}

/**
 * Carries out a validated plan. Order is chosen so a failure part-way leaves the drive readable:
 * identity check, erases, filesystems inside their partitions, label changes, then the partition
 * table last, then a read-back verification. Nothing is rolled back; the result says exactly which
 * steps completed.
 */
class Executor(
    private val session: DiskSession,
    private val planner: Planner,
    private val listener: RunListener = object : RunListener {},
    private val journal: Journal = NoJournal,
    private val cancelled: () -> Boolean = { false },
) {
    private class Step(val title: String, val run: () -> String)

    private val snap = planner.snap
    private val final = planner.current
    private val dev get() = session.dev
    private val bs = snap.blockSize

    fun run(): RunResult {
        val steps = try {
            compile()
        } catch (e: Exception) {
            val r = RunResult(Outcome.FAILED, "The plan can't be carried out: ${e.message}. Nothing was written to the drive.", emptyList(), emptyList())
            listener.log(r.summary)
            return r
        }
        val done = ArrayList<StepResult>()
        journal.begin(snap.identity.describe(), planner.queue.map { it.describe(bs) })
        var tableWritten = false
        val tablePlanned = steps.any { it.title == TABLE_STEP }
        try {
            for ((i, s) in steps.withIndex()) {
                if (cancelled()) throw CancelledException()
                listener.step(i, steps.size, s.title)
                journal.step(s.title)
                listener.log(s.title)
                val detail = s.run()
                if (s.title == TABLE_STEP) tableWritten = true
                done.add(StepResult(s.title, true, detail))
                if (detail.isNotEmpty()) listener.log("  $detail")
            }
        } catch (e: CancelledException) {
            return finish(RunResult(Outcome.CANCELLED, stopSummary(done, tablePlanned && !tableWritten, "You stopped it"), done, emptyList()))
        } catch (e: Exception) {
            val title = steps.getOrNull(done.size)?.title ?: "Unknown step"
            done.add(StepResult(title, false, e.message ?: e.javaClass.simpleName))
            listener.log("Failed: ${e.message}")
            return finish(RunResult(Outcome.FAILED, stopSummary(done.dropLast(1), tablePlanned && !tableWritten, e.message ?: "An error occurred"), done, emptyList()))
        }
        val checks = ArrayList<String>()
        val problems = verify(checks)
        val outcome = if (problems == 0) Outcome.SUCCESS else Outcome.PARTIAL
        val summary = if (problems == 0) "All changes were written and read back correctly." else "The changes were written, but $problems check(s) didn't match. See the details below."
        return finish(RunResult(outcome, summary, done, checks))
    }

    private fun finish(r: RunResult): RunResult {
        journal.end(r)
        return r
    }

    private fun stopSummary(done: List<StepResult>, tableMissing: Boolean, why: String): String {
        if (done.isEmpty() || done.size == 1 && done[0].title == IDENTITY_STEP) return "$why. Nothing was written to the drive."
        val list = done.filter { it.title != IDENTITY_STEP }.joinToString("; ") { it.title.lowercase() }
        val table = if (tableMissing) " The new partition table was not written." else ""
        return "$why. Already written: $list. These can't be undone.$table"
    }

    private fun check(): Unit = if (cancelled()) throw CancelledException() else Unit

    private fun progress(): (Long, Long) -> Unit = { d, t ->
        listener.progress(d, t)
        check()
    }

    private fun compile(): List<Step> {
        val out = ArrayList<Step>()
        // Everything that can be checked without writing is checked before the first write.
        for (v in final.volumes) {
            val fs = v.fs ?: continue
            if (v.change != VolumeChange.NEW && v.change != VolumeChange.FORMATTED) continue
            FsSupport.layoutProblem(fs, bs, v.partition.sectors)?.let {
                throw IllegalStateException("Partition ${v.partition.number}: $it")
            }
        }
        final.table?.let { t ->
            if (t.kind == io.github.ravenor907.ravendisk.core.table.TableKind.GPT) TableWriter.gptImage(dev, t)
            else TableWriter.mbrSector(ByteArray(bs), t, dev.blockCount)
        }
        out.add(Step(IDENTITY_STEP) {
            val now = session.identity()
            snap.identity.mismatch(now)?.let { throw IOException("$it. Nothing was written; reload the drive and review the plan again.") }
            if (dev.readOnly) throw IOException("The drive is write-protected. Nothing was written.")
            "Same drive, unchanged since the plan was made"
        })
        final.wholeErase?.let { m ->
            out.add(Step("Erase the whole drive (${m.label.lowercase()})") { eraseRange(0, dev.blockCount, m) })
        }
        if (final.newTable && final.wholeErase != EraseMethod.ZERO) {
            out.add(Step("Clear the old partition table") {
                val n = minOf(dev.blockCount / 2, (1L shl 20) / bs)
                dev.zero(0, n)
                dev.zero(dev.blockCount - n, n)
                "Cleared the first and last ${n * bs / 1024} KiB"
            })
        }
        for (v in final.volumes.sortedBy { it.partition.firstLba }) {
            val p = v.partition
            val m = v.erase
            if (m != null) out.add(Step("Erase partition ${p.number} (${m.label.lowercase()})") { eraseRange(p.firstLba, p.sectors, m) })
            when (v.change) {
                VolumeChange.NEW, VolumeChange.FORMATTED -> {
                    val fs = v.fs
                    if (fs == null) {
                        out.add(Step("Clear old signatures in partition ${p.number}") {
                            Wipe.signatures(dev, p.firstLba, p.sectors)
                            ""
                        })
                    } else {
                        out.add(Step("Format partition ${p.number} as ${fs.label}") { format(p, fs, v.label ?: "") })
                    }
                }
                else -> if (v.relabel && v.fs != null) {
                    out.add(Step("Label partition ${p.number}") {
                        Labels.set(dev, p.firstLba, p.sectors, v.fs, v.label ?: "")
                        "Label set to \"${v.label ?: ""}\""
                    })
                }
            }
        }
        val t = final.table
        if (t != null && (final.newTable || tableChanged(t))) {
            out.add(Step(TABLE_STEP) {
                TableWriter.write(dev, t)
                "${t.kind.label}, ${t.partitions.size} partition(s)"
            })
        }
        out.add(Step("Flush the drive's cache") {
            dev.sync()
            ""
        })
        return out
    }

    private fun tableChanged(t: PartitionTable): Boolean {
        val old = planner.base.table ?: return true
        if (old.kind != t.kind) return true
        // Rewriting also repairs a missing or misplaced backup GPT.
        if (old.issues.isNotEmpty() && planner.queue.isNotEmpty() && final.volumes.any { it.change != VolumeChange.KEPT }) return true
        return Planner.tableDigest(old) != Planner.tableDigest(t)
    }

    private fun eraseRange(first: Long, blocks: Long, m: EraseMethod): String = when (m) {
        EraseMethod.ZERO -> {
            dev.zero(first, blocks, progress())
            "Wrote zeros over ${blocks} blocks"
        }
        EraseMethod.DISCARD -> {
            session.discard(first, blocks)
            "Discarded ${blocks} blocks"
        }
    }

    private fun format(p: Partition, fs: FsType, label: String): String {
        Wipe.signatures(dev, p.firstLba, p.sectors)
        check()
        return when (fs) {
            FsType.FAT32 -> {
                val l = Fat32.format(dev, p.firstLba, p.sectors, label, progress = progress())
                "${l.clusters} clusters of ${l.clusterBytes / 1024} KiB"
            }
            FsType.EXFAT -> {
                val l = ExFat.format(dev, p.firstLba, p.sectors, label, progress = progress())
                "${l.clusterCount} clusters of ${l.clusterBytes / 1024} KiB"
            }
            FsType.EXT4 -> {
                val l = Ext4.format(dev, p.firstLba, p.sectors, label, progress = progress())
                "${l.groups} block group(s), ${l.inodes} inodes, ${l.journalBlocks * 4 / 1024} MiB journal"
            }
            FsType.SWAP -> {
                val pages = Swap.format(dev, p.firstLba, p.sectors, label)
                "$pages pages of 4 KiB"
            }
            else -> throw IOException("${fs.label} can't be created by this version")
        }
    }

    /** Reads the drive back and compares it with the plan. Returns the number of mismatches. */
    private fun verify(checks: MutableList<String>): Int {
        var bad = 0
        fun ok(s: String) = checks.add("✓ $s")
        fun no(s: String) {
            checks.add("✗ $s")
            bad++
        }
        listener.log("Reading the drive back to verify")
        val t = final.table
        val read = try {
            TableReader.read(dev)
        } catch (e: IOException) {
            no("Couldn't read the drive back: ${e.message}")
            return bad
        }
        if (t != null) {
            val got = (read as? TableRead.Found)?.table
            when {
                got == null -> no("The partition table didn't read back")
                got.kind != t.kind -> no("Read back a ${got.kind.label} table instead of ${t.kind.label}")
                Planner.tableDigest(got) != Planner.tableDigest(t) -> no("The partition table read back differently than planned")
                got.issues.isNotEmpty() -> no("The partition table reads back with problems: ${got.issues.first()}")
                else -> ok("${t.kind.label} partition table with ${t.partitions.size} partition(s) reads back as planned")
            }
        }
        for (v in final.volumes) {
            val p = v.partition
            val needs = v.change == VolumeChange.NEW || v.change == VolumeChange.FORMATTED || v.relabel
            if (needs && v.fs != null) {
                val info = Probe.probe(dev, p.firstLba, p.sectors)
                val labelMatches = (info?.label ?: "").equals(v.label ?: "", ignoreCase = v.fs in FAT_TYPES)
                when {
                    info == null -> no("Partition ${p.number}: no filesystem found after formatting")
                    info.type != v.fs -> no("Partition ${p.number}: found ${info.type.label}, expected ${v.fs.label}")
                    !labelMatches -> no("Partition ${p.number}: label reads \"${info.label ?: ""}\", expected \"${v.label ?: ""}\"")
                    else -> ok("Partition ${p.number}: ${info.type.label}" + (info.label?.let { " \"$it\"" } ?: ""))
                }
            }
            if (v.erase == EraseMethod.ZERO) {
                if (sampleZero(p.firstLba, p.sectors)) ok("Partition ${p.number}: sampled blocks read back as zeros") else no("Partition ${p.number}: sampled blocks aren't zero")
            }
            if (v.erase == EraseMethod.DISCARD) checks.add("• Partition ${p.number}: discard sent; the drive decides when the data is physically erased")
        }
        when (final.wholeErase) {
            EraseMethod.ZERO -> if (final.table == null) {
                if (sampleZero(0, dev.blockCount)) ok("Sampled blocks across the drive read back as zeros") else no("Sampled blocks aren't zero")
            }
            EraseMethod.DISCARD -> checks.add("• Discard sent for the whole drive; the drive decides when the data is physically erased")
            null -> {}
        }
        return bad
    }

    private fun sampleZero(first: Long, blocks: Long): Boolean {
        val n = minOf(blocks, 64).toInt()
        val points = listOf(first, first + blocks / 2 - n / 2, first + blocks - n).map { it.coerceIn(first, first + blocks - n) }.distinct()
        return points.all { dev.readBlocks(it, n).isAllZero() }
    }

    companion object {
        const val IDENTITY_STEP = "Check it's the same drive"
        const val TABLE_STEP = "Write the partition table"
        private val FAT_TYPES = setOf(FsType.FAT12, FsType.FAT16, FsType.FAT32)
    }
}
