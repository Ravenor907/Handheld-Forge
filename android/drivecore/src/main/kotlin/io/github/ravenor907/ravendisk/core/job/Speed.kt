package io.github.ravenor907.ravendisk.core.job

/** What a speed sample measured. */
enum class SpeedPhase(val label: String) {
    /** Long sequential write from the start of the drive. */
    WRITE("Write"),

    /** 1 MB markers written across the rest of the drive. */
    SCATTER_WRITE("Scattered write"),

    /** Reading the sequential run back. */
    READ("Read"),

    /** Reading the markers back. */
    SCATTER_READ("Scattered read"),
}

/**
 * One point on the live speed graph: the speed over the last short window (not an average since
 * the start), so drops and spikes show as they happen.
 */
data class SpeedSample(val phase: SpeedPhase, val seconds: Double, val mbps: Double, val atBytes: Long)

typealias SampleFn = (SpeedSample) -> Unit

/**
 * Turns "this many bytes just moved" into [SpeedSample]s, one per [windowMs] or so. Time runs from
 * the meter's creation, so samples from every phase share one clock and one graph.
 */
class SpeedMeter(
    private val onSample: SampleFn,
    private val windowMs: Long = 250,
    private val clock: () -> Long = System::nanoTime,
) {
    private val t0 = clock()
    private var phase: SpeedPhase? = null
    private var winStart = t0
    private var winBytes = 0L
    private var lastEvent = t0
    private var at = 0L

    /** Fastest moment seen, and every sample so far (for the summary and the result screen). */
    var peak = 0.0
        private set
    val samples = ArrayList<SpeedSample>()

    /** [bytes] just finished moving. The time since the previous call is what they took. */
    fun add(p: SpeedPhase, bytes: Long) {
        val now = clock()
        if (p != phase) {
            flush()
            phase = p
            winStart = lastEvent
            winBytes = 0
        }
        winBytes += bytes
        at += bytes
        lastEvent = now
        if (now - winStart >= windowMs * 1_000_000) emit(now)
    }

    /** Emits whatever the current window holds (call at the end of a phase). */
    fun flush() = emit(lastEvent)

    private fun emit(end: Long) {
        val p = phase ?: return
        val ns = end - winStart
        if (winBytes > 0 && ns > 0) {
            val s = SpeedSample(p, (end - t0) / 1e9, winBytes / 1e6 / (ns / 1e9), at)
            if (s.mbps > peak) peak = s.mbps
            samples.add(s)
            onSample(s)
        }
        winStart = end
        winBytes = 0
    }
}
