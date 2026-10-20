package io.github.ravenor907.ravendisk.core.plan

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * An append-only operation log in a plain text file. Each run starts with "BEGIN", records each
 * step as it starts, and ends with "END <outcome>". A run with no END was interrupted (app killed,
 * phone died, drive pulled) and is reported on the next launch.
 *
 * Device details are limited to model and size; serial numbers stay out of the log.
 */
class TextJournal(private val file: File, private val now: () -> Date = { Date() }) : Journal {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getDefault() }

    private fun line(s: String) {
        file.parentFile?.mkdirs()
        file.appendText("${fmt.format(now())}\t$s\n")
        trim()
    }

    override fun begin(device: String, ops: List<String>) {
        line("BEGIN\t${clean(device.substringBefore(", serial"))}")
        ops.forEach { line("OP\t${clean(it)}") }
    }

    override fun step(title: String) = line("STEP\t${clean(title)}")

    override fun end(result: RunResult) {
        result.steps.filter { !it.ok }.forEach { line("ERROR\t${clean(it.title)}: ${clean(it.detail)}") }
        result.checks.forEach { line("CHECK\t${clean(it)}") }
        line("END\t${result.outcome.name}\t${clean(result.summary)}")
    }

    /** All runs, newest first. */
    fun runs(): List<Run> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Run>()
        var cur: Run? = null
        for (raw in file.readLines()) {
            val parts = raw.split('\t')
            if (parts.size < 2) continue
            val time = parts[0]
            when (parts[1]) {
                "BEGIN" -> {
                    cur?.let { out.add(it) }
                    cur = Run(time, parts.getOrElse(2) { "" }, ArrayList(), ArrayList(), null, null, ArrayList())
                }
                "OP" -> cur?.ops?.add(parts.getOrElse(2) { "" })
                "STEP" -> cur?.steps?.add(parts.getOrElse(2) { "" })
                "ERROR", "CHECK" -> cur?.notes?.add(parts.getOrElse(2) { "" })
                "END" -> cur = cur?.copy(outcome = parts.getOrNull(2)?.let { runCatching { Outcome.valueOf(it) }.getOrNull() }, summary = parts.getOrNull(3))
            }
        }
        cur?.let { out.add(it) }
        return out.reversed()
    }

    /** The most recent run if it never finished. */
    fun interrupted(): Run? = runs().firstOrNull()?.takeIf { it.outcome == null }

    /** Marks an interrupted run as acknowledged so it isn't reported again. */
    fun acknowledgeInterrupted() {
        if (interrupted() != null) line("END\tFAILED\tInterrupted: the app stopped before the operation finished")
    }

    data class Run(
        val started: String,
        val device: String,
        val ops: MutableList<String>,
        val steps: MutableList<String>,
        val outcome: Outcome?,
        val summary: String?,
        val notes: MutableList<String>,
    )

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun trim() {
        if (file.length() < MAX_BYTES) return
        val lines = file.readLines()
        file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val MAX_BYTES = 256L * 1024
    }
}
