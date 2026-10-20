package io.github.ravenor907.ravendisk.core.health

import io.github.ravenor907.ravendisk.core.io.ScsiDisk
import io.github.ravenor907.ravendisk.core.io.ScsiException
import io.github.ravenor907.ravendisk.core.util.u16le
import io.github.ravenor907.ravendisk.core.util.u32le
import io.github.ravenor907.ravendisk.core.util.u8
import java.io.IOException

enum class HealthStatus(val label: String) {
    GOOD("Healthy"),
    WARNING("Needs attention"),
    FAILING("Failing"),
    UNKNOWN("Not available"),
}

data class HealthItem(
    val name: String,
    val value: String,
    val warn: Boolean = false,
    /** Set when the value is vendor-specific or interpreted rather than standard. */
    val note: String? = null,
)

data class HealthReport(
    val status: HealthStatus,
    /** Where the data came from, or why there is none. */
    val source: String,
    val items: List<HealthItem> = emptyList(),
    val temperatureC: Int? = null,
    val powerOnHours: Long? = null,
    val selfTest: String? = null,
) {
    companion object {
        fun unavailable(reason: String) = HealthReport(HealthStatus.UNKNOWN, reason)
    }
}

/** The ATA commands health needs, so the parser can be tested without a bridge. */
interface AtaChannel {
    fun identify(): ByteArray
    fun smartData(): ByteArray
    fun smartThresholds(): ByteArray

    /** True = passed, false = threshold exceeded, null = the bridge didn't return the registers. */
    fun smartReturnStatus(): Boolean?
}

/** ATA over a USB-SATA bridge with SCSI/ATA Translation (ATA PASS-THROUGH 16). */
class SatChannel(private val disk: ScsiDisk) : AtaChannel {
    override fun identify(): ByteArray = ByteArray(512).also { disk.ataPassThrough(0xEC, sectorCount = 1, dataIn = it) }

    override fun smartData(): ByteArray =
        ByteArray(512).also { disk.ataPassThrough(0xB0, feature = 0xD0, lbaMid = 0x4F, lbaHigh = 0xC2, sectorCount = 1, dataIn = it) }

    override fun smartThresholds(): ByteArray =
        ByteArray(512).also { disk.ataPassThrough(0xB0, feature = 0xD1, lbaMid = 0x4F, lbaHigh = 0xC2, sectorCount = 1, dataIn = it) }

    override fun smartReturnStatus(): Boolean? {
        val d = disk.ataPassThrough(0xB0, feature = 0xDA, lbaMid = 0x4F, lbaHigh = 0xC2, checkCondition = true)
        if (d.size < 14) return null
        val mid = d.u8(9)
        val high = d.u8(11)
        return when {
            mid == 0x4F && high == 0xC2 -> true
            mid == 0xF4 && high == 0x2C -> false
            else -> null
        }
    }
}

data class AtaIdentity(
    val model: String,
    val serial: String,
    val firmware: String,
    val smartSupported: Boolean,
    val smartEnabled: Boolean,
    val trim: Boolean,
    /** 1 = SSD, >1 = RPM, null = not reported. */
    val rotationRate: Int?,
)

object Smart {
    fun parseIdentify(id: ByteArray): AtaIdentity {
        fun str(fromWord: Int, words: Int): String {
            val sb = StringBuilder()
            for (w in fromWord until fromWord + words) {
                sb.append((id.u8(w * 2 + 1)).toChar())
                sb.append((id.u8(w * 2)).toChar())
            }
            return sb.toString().trim()
        }
        fun word(n: Int) = id.u16le(n * 2)
        val rotation = word(217).takeIf { it == 1 || it in 0x0401..0xFFFE }
        return AtaIdentity(
            model = str(27, 20),
            serial = str(10, 10),
            firmware = str(23, 4),
            smartSupported = word(82) and 1 != 0,
            smartEnabled = word(85) and 1 != 0,
            trim = word(169) and 1 != 0,
            rotationRate = rotation,
        )
    }

    data class Attribute(val id: Int, val value: Int, val worst: Int, val raw: Long, val threshold: Int?)

    fun parseAttributes(data: ByteArray, thresholds: ByteArray?): List<Attribute> {
        val th = HashMap<Int, Int>()
        if (thresholds != null) for (i in 0 until 30) {
            val o = 2 + i * 12
            val id = thresholds.u8(o)
            if (id != 0) th[id] = thresholds.u8(o + 1)
        }
        val out = ArrayList<Attribute>()
        for (i in 0 until 30) {
            val o = 2 + i * 12
            val id = data.u8(o)
            if (id == 0) continue
            val raw = data.u32le(o + 5) or (data.u16le(o + 9).toLong() shl 32)
            out.add(Attribute(id, data.u8(o + 3), data.u8(o + 4), raw, th[id]))
        }
        return out
    }

    fun selfTestStatus(data: ByteArray): String {
        val b = data.u8(363)
        val pct = (b and 0x0F) * 10
        return when (b ushr 4) {
            0 -> "Last self-test completed without error"
            1 -> "Last self-test was aborted"
            2 -> "Last self-test was interrupted by a reset"
            3 -> "Last self-test couldn't run (fatal error)"
            4, 5, 6, 7 -> "Last self-test failed"
            8 -> "Last self-test failed (handling damage)"
            15 -> "Self-test in progress, $pct% remaining"
            else -> "Self-test status code ${b ushr 4}"
        }
    }

    /** Reads everything over [ch] and turns it into a report. Never throws; failures become "unavailable". */
    fun report(ch: AtaChannel, sourceName: String): HealthReport {
        val ident = try {
            parseIdentify(ch.identify())
        } catch (e: IOException) {
            return HealthReport.unavailable(bridgeReason(e))
        }
        if (!ident.smartSupported) return HealthReport.unavailable("The drive doesn't support SMART")
        if (!ident.smartEnabled) return HealthReport.unavailable("SMART is turned off on this drive")
        val data = try {
            ch.smartData()
        } catch (e: IOException) {
            return HealthReport.unavailable(bridgeReason(e))
        }
        val th = try {
            ch.smartThresholds()
        } catch (e: IOException) {
            null
        }
        val passed = try {
            ch.smartReturnStatus()
        } catch (e: IOException) {
            null
        }
        return build(ident, parseAttributes(data, th), selfTestStatus(data), passed, sourceName)
    }

    fun build(ident: AtaIdentity, attrs: List<Attribute>, selfTest: String, passed: Boolean?, source: String): HealthReport {
        val items = ArrayList<HealthItem>()
        var warn = false
        var failing = passed == false
        val byId = attrs.associateBy { it.id }
        fun raw32(id: Int) = byId[id]?.raw?.and(0xFFFFFFFFL)

        val temp = (byId[194] ?: byId[190])?.let { (it.raw and 0xFF).toInt() }?.takeIf { it in 1..125 }
        val hours = raw32(9)
        temp?.let { items.add(HealthItem("Temperature", "$it °C", warn = it >= 60)) }
        hours?.let { items.add(HealthItem("Power-on time", "$it hours")) }
        raw32(12)?.let { items.add(HealthItem("Power cycles", "$it")) }
        for ((id, name) in listOf(5 to "Reallocated sectors", 197 to "Pending sectors", 198 to "Uncorrectable sectors", 187 to "Reported uncorrectable errors")) {
            val v = raw32(id) ?: continue
            val bad = v > 0
            if (bad) warn = true
            items.add(HealthItem(name, "$v", warn = bad))
        }
        raw32(199)?.let { items.add(HealthItem("Interface CRC errors", "$it", warn = it > 0, note = if (it > 0) "Usually a cable or bridge problem, not the drive" else null)) }
        for ((id, name) in listOf(231 to "Life left", 233 to "Media wear indicator", 177 to "Wear leveling", 202 to "Life used")) {
            val a = byId[id] ?: continue
            items.add(HealthItem(name, "${a.value} (normalized)", warn = a.value <= 10, note = "Vendor-specific; 100 is new"))
            break
        }
        for (a in attrs) {
            val t = a.threshold ?: continue
            if (t in 1..0xFD && a.value <= t) failing = true
        }
        val status = when {
            failing -> HealthStatus.FAILING
            warn -> HealthStatus.WARNING
            else -> HealthStatus.GOOD
        }
        val src = buildString {
            append(source)
            if (passed == null) append("; overall verdict not returned by the bridge, judged from attributes")
        }
        items.add(0, HealthItem("Overall", if (passed == false) "SMART says the drive is failing" else status.label, warn = status != HealthStatus.GOOD))
        items.add(HealthItem("Firmware", ident.firmware))
        return HealthReport(status, src, items, temp, hours, selfTest)
    }

    private fun bridgeReason(e: IOException): String = when (e) {
        is ScsiException -> "The USB bridge doesn't pass health (SMART) commands through"
        else -> "Couldn't read health data: ${e.message}"
    }
}
