package io.github.ravenor907.ravendisk.core.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Locale
import java.util.zip.CRC32

fun ByteArray.le(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
fun ByteArray.be(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.BIG_ENDIAN)

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.u32le(i: Int): Long = (u16le(i).toLong()) or (u16le(i + 2).toLong() shl 16)
fun ByteArray.u64le(i: Int): Long = u32le(i) or (u32le(i + 4) shl 32)
fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
fun ByteArray.u32be(i: Int): Long = (u16be(i).toLong() shl 16) or u16be(i + 2).toLong()

fun ByteArray.regionEquals(at: Int, other: ByteArray): Boolean {
    if (at < 0 || at + other.size > size) return false
    for (i in other.indices) if (this[at + i] != other[i]) return false
    return true
}

/** Whether bytes [from, to) are all zero. Checks eight bytes at a time; this runs over megabytes. */
fun ByteArray.isAllZero(from: Int = 0, to: Int = size): Boolean {
    var i = from
    if (to - from >= 16) {
        val bb = java.nio.ByteBuffer.wrap(this)
        val end8 = from + (to - from) / 8 * 8
        while (i < end8) {
            if (bb.getLong(i) != 0L) return false
            i += 8
        }
    }
    while (i < to) {
        if (this[i].toInt() != 0) return false
        i++
    }
    return true
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

object Crc {
    fun crc32(b: ByteArray, off: Int = 0, len: Int = b.size - off): Long = CRC32().apply { update(b, off, len) }.value

    private val table32c = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0x82F63B78L else c ushr 1 }
        c
    }

    /**
     * Raw CRC32C update (no pre/post inversion), as ext4's metadata checksums use it:
     * the kernel's crc32c(~0, ...) seeds with all ones and stores the result uninverted.
     */
    fun crc32cRaw(seed: Long, b: ByteArray, off: Int = 0, len: Int = b.size - off): Long {
        var c = seed and 0xFFFFFFFFL
        for (i in off until off + len) c = table32c[((c xor b[i].toLong()) and 0xFF).toInt()] xor (c ushr 8)
        return c
    }

    /** Standard CRC-32C (iSCSI). */
    fun crc32c(b: ByteArray, off: Int = 0, len: Int = b.size - off): Long = crc32cRaw(0xFFFFFFFFL, b, off, len) xor 0xFFFFFFFFL
}

/** GUIDs in GPT's mixed-endian on-disk form. */
data class Guid(val text: String) {
    init {
        require(PATTERN.matches(text)) { "Not a GUID: $text" }
    }

    override fun toString() = text

    fun toBytes(): ByteArray {
        val h = text.replace("-", "")
        val raw = ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        // First three groups are little-endian on disk.
        return byteArrayOf(
            raw[3], raw[2], raw[1], raw[0], raw[5], raw[4], raw[7], raw[6],
            raw[8], raw[9], raw[10], raw[11], raw[12], raw[13], raw[14], raw[15],
        )
    }

    val isZero get() = text == ZERO.text

    companion object {
        private val PATTERN = Regex("^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}$")
        val ZERO = Guid("00000000-0000-0000-0000-000000000000")

        fun parse(s: String): Guid = Guid(s.trim().uppercase(Locale.ROOT))

        fun fromBytes(b: ByteArray, off: Int = 0): Guid {
            fun x(i: Int) = "%02X".format(b[off + i])
            val t = x(3) + x(2) + x(1) + x(0) + "-" + x(5) + x(4) + "-" + x(7) + x(6) + "-" + x(8) + x(9) + "-" +
                (10..15).joinToString("") { x(it) }
            return Guid(t)
        }

        /** Random version-4 GUID. */
        fun random(rng: java.util.Random = SecureRandom()): Guid {
            val b = ByteArray(16).also { rng.nextBytes(it) }
            b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte()
            val h = b.joinToString("") { "%02X".format(it) }
            return Guid("${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}")
        }
    }
}

object Units {
    const val KiB = 1024L
    const val MiB = 1024L * 1024
    const val GiB = 1024L * MiB
    const val TiB = 1024L * GiB

    /** Decimal sizes, the way drives are sold (64 GB). */
    fun size(bytes: Long): String {
        val b = bytes.toDouble()
        return when {
            bytes >= 1_000_000_000_000L -> fmt(b / 1e12, "TB")
            bytes >= 1_000_000_000L -> fmt(b / 1e9, "GB")
            bytes >= 1_000_000L -> fmt(b / 1e6, "MB")
            bytes >= 1_000L -> fmt(b / 1e3, "kB")
            else -> "$bytes B"
        }
    }

    /** Binary sizes for partition work (512 MiB). */
    fun sizeBinary(bytes: Long): String {
        val b = bytes.toDouble()
        return when {
            bytes >= TiB -> fmt(b / TiB, "TiB")
            bytes >= GiB -> fmt(b / GiB, "GiB")
            bytes >= MiB -> fmt(b / MiB, "MiB")
            bytes >= KiB -> fmt(b / KiB, "KiB")
            else -> "$bytes B"
        }
    }

    private fun fmt(v: Double, unit: String): String =
        if (v >= 100 || v == Math.floor(v)) String.format(Locale.US, "%.0f %s", v, unit) else String.format(Locale.US, "%.1f %s", v, unit)
}
