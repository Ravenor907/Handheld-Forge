package io.github.ravenor907.ravendisk.core.io

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Raw bulk pipe to a USB mass-storage interface. The Android app implements this with
 * UsbDeviceConnection; tests use [FakeUsbDrive]-style fakes. Nothing here touches Android.
 */
interface UsbTransport {
    /** Largest single bulk transfer known to work everywhere. */
    val maxTransfer: Int

    /** Returns bytes transferred, or a negative number on failure. */
    fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun bulkIn(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int

    /** Bulk-Only Mass Storage Reset plus clearing both endpoint halts. */
    fun resetRecovery()

    /** GET MAX LUN class request. Devices that stall it have a single LUN (0). */
    fun maxLun(): Int = 0

    /** CLEAR_FEATURE(ENDPOINT_HALT) on the bulk-in or bulk-out pipe. Returns the control transfer result. */
    fun clearHalt(input: Boolean): Int = -1

    /** Whether [streamOut]/[streamIn] keep several transfers in flight at once. */
    val canPipeline: Boolean get() = false

    /**
     * Moves [length] bytes as [piece]-sized transfers, ideally several queued at once so the bus never
     * waits on the phone between pieces (most of the speed on big reads and writes). Returns bytes
     * moved (less than [length] on a short read), or -1 on failure.
     */
    fun streamOut(data: ByteArray, offset: Int, length: Int, piece: Int, timeoutMs: Int): Int {
        var done = 0
        while (done < length) {
            val r = bulkOut(data, offset + done, minOf(piece, length - done), timeoutMs)
            if (r < 0) return -1
            done += r
        }
        return done
    }

    fun streamIn(data: ByteArray, offset: Int, length: Int, piece: Int, timeoutMs: Int): Int {
        var done = 0
        while (done < length) {
            val n = minOf(piece, length - done)
            val r = bulkIn(data, offset + done, n, timeoutMs)
            if (r < 0) return -1
            done += r
            if (r < n) break
        }
        return done
    }
}

open class ScsiException(msg: String, val senseKey: Int = -1, val asc: Int = -1, val ascq: Int = -1) : IOException(msg)

/** The LUN has no medium (empty card reader slot). */
class NoMediumException : ScsiException("No card or medium in this slot", 0x2, 0x3A)

/** USB Mass Storage Class, Bulk-Only Transport: CBW, optional data phase, CSW. */
class BulkOnly(val t: UsbTransport, val lun: Int = 0) {
    private var tag = 1

    /** Size of each bulk transfer in a data phase. Steps down on trouble. */
    var transferSize = t.maxTransfer

    /**
     * Queue several transfers at once for big data phases (reads and writes). Switched off by the
     * first sign of trouble. Small answers (INQUIRY, VPD, sense) always go one transfer at a time,
     * because that's where drives stall and need the halt cleared.
     */
    var pipelined = t.canPipeline

    /** Runs one SCSI command. At most one of [dataIn]/[dataOut]. Returns the CSW status (0 passed, 1 failed). */
    fun command(
        cdb: ByteArray,
        dataIn: ByteArray? = null,
        dataOut: ByteArray? = null,
        offset: Int = 0,
        length: Int = dataIn?.size ?: dataOut?.size ?: 0,
        timeoutMs: Int = 20_000,
    ): Int = synchronized(t) {
        // One command at a time per device: card readers share one pipe between their LUNs.
        commandLocked(cdb, dataIn, dataOut, offset, length, timeoutMs)
    }

    private fun commandLocked(cdb: ByteArray, dataIn: ByteArray?, dataOut: ByteArray?, offset: Int, length: Int, timeoutMs: Int): Int {
        require(cdb.size in 1..16)
        require(dataIn == null || dataOut == null)
        val myTag = tag++
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(CBW_SIGNATURE).putInt(myTag).putInt(length)
        cbw.put(if (dataIn != null) 0x80.toByte() else 0)
        cbw.put(lun.toByte()).put(cdb.size.toByte()).put(cdb)
        if (t.bulkOut(cbw.array(), 0, 31, timeoutMs) != 31) fail("The drive did not accept a command")

        if (length > transferSize && pipelined) {
            val r = if (dataIn != null) t.streamIn(dataIn, offset, length, transferSize, timeoutMs) else t.streamOut(dataOut!!, offset, length, transferSize, timeoutMs)
            if (r < 0) fail("A USB transfer failed")
        } else if (length > 0) {
            var done = 0
            while (done < length) {
                val n = minOf(transferSize, length - done)
                val r = if (dataIn != null) t.bulkIn(dataIn, offset + done, n, timeoutMs) else t.bulkOut(dataOut!!, offset + done, n, timeoutMs)
                if (r < 0) {
                    // Sending: the drive choked on the data (some sticks do on big writes). Reset
                    // and let the caller retry in smaller pieces.
                    if (dataIn == null) fail("A USB transfer failed")
                    // Receiving: the drive stalled because it had less to say than asked for
                    // (allowed by the spec). Clear the stall; the status still follows.
                    t.clearHalt(true)
                    break
                }
                done += r
                if (r < n) break // short packet ends the data phase
            }
        }

        val csw = ByteArray(13)
        var got = t.bulkIn(csw, 0, 13, timeoutMs)
        if (got < 0) {
            // Bulk-Only "case 4": after a short data phase the drive may stall bulk-in before the
            // status. Clearing the stall and reading again is the procedure the spec gives.
            t.clearHalt(true)
            got = t.bulkIn(csw, 0, 13, timeoutMs)
        }
        if (got != 13) fail("The drive did not report command status")
        val b = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != CSW_SIGNATURE || b.getInt(4) != myTag) fail("The drive sent a garbled status")
        val status = csw[12].toInt() and 0xFF
        if (status == 2) fail("The drive reported a phase error")
        return status
    }

    private fun fail(msg: String): Nothing {
        try {
            t.resetRecovery()
        } catch (_: Exception) {
        }
        throw IOException(msg)
    }

    companion object {
        const val CBW_SIGNATURE = 0x43425355 // "USBC"
        const val CSW_SIGNATURE = 0x53425355 // "USBS"
    }
}

/** What INQUIRY and the VPD pages said. Only fields the device reported are non-null. */
data class ScsiIdentity(
    val vendor: String,
    val product: String,
    val revision: String,
    val removableMedium: Boolean,
    val peripheralType: Int,
    val serial: String?,
    /** 1 = non-rotating (SSD/flash), >1 = RPM, null = not reported. */
    val rotationRate: Int?,
)

/** Thin-provisioning (discard) support from READ CAPACITY(16) and the Block Limits VPD page. */
data class DiscardSupport(val supported: Boolean, val maxUnmapBlocks: Long, val maxDescriptors: Int)

/** A SCSI direct-access block device (USB stick, card reader slot, USB-SATA/NVMe bridge) behind Bulk-Only Transport. */
class ScsiDisk(private val bot: BulkOnly) : BlockDevice {
    override var blockSize = 512
        private set
    override var blockCount = 0L
        private set

    /** Physical block size from READ CAPACITY(16), when reported. */
    var physicalBlockSize: Int? = null
        private set
    override var readOnly = false
        private set
    lateinit var identity: ScsiIdentity
        private set
    var discard = DiscardSupport(false, 0, 0)
        private set

    val lun: Int get() = bot.lun

    /** "Vendor Product" from INQUIRY, for logs and reports. */
    override val name: String get() = if (!::identity.isInitialized) "USB drive" else listOf(identity.vendor, identity.product).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "USB drive" }

    /** Bytes per bulk transfer, and whether several are queued at once (for logs). */
    val transferSize: Int get() = bot.transferSize
    val pipelined: Boolean get() = bot.pipelined

    /** Notes about the connection (slowing down after an error), for the operation log. */
    var onNote: (String) -> Unit = {}

    var commandBytes = COMMAND_LADDER.first()
        private set

    fun init(): ScsiDisk {
        identity = inquiry()
        var ready = false
        for (i in 0 until 20) {
            if (bot.command(byteArrayOf(0, 0, 0, 0, 0, 0)) == 0) {
                ready = true
                break
            }
            val s = requestSense()
            if (s.key == 0x2 && s.asc == 0x3A) throw NoMediumException()
            Thread.sleep(SETTLE_MS)
        }
        if (!ready) throw ScsiException("The drive isn't ready. Unplug it, plug it back in and try again.")
        readCapacity()
        readOnly = modeSenseWriteProtect()
        return this
    }

    private fun inquiry(): ScsiIdentity {
        val buf = ByteArray(36)
        var vendor = ""
        var product = ""
        var rev = ""
        var rmb = false
        var type = 0
        if (bot.command(byteArrayOf(0x12, 0, 0, 0, 36, 0), dataIn = buf) == 0) {
            type = buf[0].toInt() and 0x1F
            rmb = buf[1].toInt() and 0x80 != 0
            vendor = ascii(buf, 8, 8)
            product = ascii(buf, 16, 16)
            rev = ascii(buf, 32, 4)
        } else {
            requestSense()
        }
        val pages = supportedVpdPages()
        val serial = if (0x80 in pages) vpd(0x80)?.let { p ->
            val len = p[3].toInt() and 0xFF
            ascii(p, 4, minOf(len, p.size - 4)).ifEmpty { null }
        } else null
        val rotation = if (0xB1 in pages) vpd(0xB1)?.let { p ->
            if (p.size >= 6) ((p[4].toInt() and 0xFF) shl 8) or (p[5].toInt() and 0xFF) else null
        }?.takeIf { it != 0 } else null
        if (0xB0 in pages) {
            vpd(0xB0)?.let { p ->
                if (p.size >= 32) {
                    val bb = ByteBuffer.wrap(p)
                    val maxUnmap = bb.getInt(20).toLong() and 0xFFFFFFFFL
                    val maxDesc = bb.getInt(24)
                    discard = DiscardSupport(false, maxUnmap, maxDesc)
                }
            }
        }
        return ScsiIdentity(vendor, product, rev, rmb, type, serial, rotation)
    }

    private fun supportedVpdPages(): Set<Int> {
        val p = vpd(0x00) ?: return emptySet()
        val n = minOf(p[3].toInt() and 0xFF, p.size - 4)
        return (0 until n).map { p[4 + it].toInt() and 0xFF }.toSet()
    }

    private fun vpd(page: Int): ByteArray? {
        val buf = ByteArray(252)
        return try {
            if (bot.command(byteArrayOf(0x12, 1, page.toByte(), 0, buf.size.toByte(), 0), dataIn = buf) == 0 &&
                (buf[1].toInt() and 0xFF) == page
            ) buf else {
                requestSense()
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun readCapacity() {
        val buf = ByteArray(8)
        check(bot.command(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), dataIn = buf), "read the drive size")
        val bb = ByteBuffer.wrap(buf)
        val lastLba = bb.getInt(0).toLong() and 0xFFFFFFFFL
        blockSize = bb.getInt(4)
        blockCount = lastLba + 1
        // READ CAPACITY(16) adds the physical block size and thin provisioning; required past 2 TiB.
        val b16 = ByteArray(32)
        val cdb = ByteArray(16).also {
            it[0] = 0x9E.toByte()
            it[1] = 0x10
            it[13] = 32
        }
        val ok16 = try {
            bot.command(cdb, dataIn = b16) == 0
        } catch (e: IOException) {
            false
        }
        if (ok16) {
            val bb16 = ByteBuffer.wrap(b16)
            val count = bb16.getLong(0) + 1
            val size = bb16.getInt(8)
            if (count > 0 && size == blockSize || lastLba == 0xFFFFFFFFL) {
                blockCount = count
                blockSize = size
                val exp = b16[13].toInt() and 0x0F
                physicalBlockSize = blockSize shl exp
                val lbpme = b16[14].toInt() and 0x80 != 0
                discard = discard.copy(supported = lbpme && discard.maxUnmapBlocks > 0)
            }
        } else {
            requestSense()
            if (lastLba == 0xFFFFFFFFL) throw ScsiException("The drive is larger than 2 TiB but won't report its full size")
        }
        if (blockSize !in 512..4096 || blockSize and (blockSize - 1) != 0) {
            throw ScsiException("The drive reports an unusual block size ($blockSize)")
        }
        if (blockCount <= 0) throw NoMediumException()
    }

    /** MODE SENSE(6): the WP bit in the header's device-specific parameter. */
    private fun modeSenseWriteProtect(): Boolean {
        val buf = ByteArray(192)
        return try {
            if (bot.command(byteArrayOf(0x1A, 0, 0x3F, 0, buf.size.toByte(), 0), dataIn = buf) == 0) {
                buf[2].toInt() and 0x80 != 0
            } else {
                requestSense()
                false
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun stepDown(): Boolean {
        if (bot.pipelined) {
            bot.pipelined = false
            onNote("USB hiccup: sending one transfer at a time from now on")
            return true
        }
        val smallerCmd = COMMAND_LADDER.firstOrNull { it < commandBytes && it >= blockSize }
        val smallerXfer = if (bot.transferSize > MIN_TRANSFER) MIN_TRANSFER else null
        if (smallerCmd == null && smallerXfer == null) return false
        smallerCmd?.let { commandBytes = it }
        smallerXfer?.let { bot.transferSize = it }
        onNote("USB hiccup: now ${commandBytes / 1024} KB per command, ${bot.transferSize / 1024} KB per transfer")
        return true
    }

    private fun recover(attempt: Int) {
        Thread.sleep(SETTLE_MS * attempt)
        for (i in 0 until 20) {
            try {
                if (bot.command(byteArrayOf(0, 0, 0, 0, 0, 0), timeoutMs = 5_000) == 0) return
                requestSense()
            } catch (_: IOException) {
            }
            Thread.sleep(SETTLE_MS)
        }
    }

    private fun <T> retrying(what: String, op: () -> T): T {
        var last: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return op()
            } catch (e: ScsiException) {
                if (e.senseKey in FINAL_SENSE_KEYS) throw e // real answers, not hiccups
                last = e
            } catch (e: IOException) {
                last = e
                stepDown()
            }
            onNote("Retrying $what (attempt ${attempt + 1} of $MAX_ATTEMPTS): ${last?.message}")
            recover(attempt)
        }
        throw IOException(
            "The USB connection to the drive kept failing while trying to $what. Try a different USB-C adapter " +
                "or port, plug the phone into a charger (some phones can't power some drives), power the drive " +
                "separately, or use another drive.",
            last,
        )
    }

    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        bounds(lba, blocks)
        pieces("read from the drive", lba, blocks) { at, n, skip ->
            check(
                bot.command(rwCdb(READ10, READ16, at, n), dataIn = buf, offset = offset + skip, length = n * blockSize),
                "read from the drive",
            )
        }
    }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        if (readOnly) throw DeviceReadOnlyException("This drive is write-protected. Check for a lock switch.")
        bounds(lba, blocks)
        pieces("write to the drive", lba, blocks) { at, n, skip ->
            check(
                bot.command(rwCdb(WRITE10, WRITE16, at, n), dataOut = buf, offset = offset + skip, length = n * blockSize, timeoutMs = 60_000),
                "write to the drive",
            )
        }
    }

    private fun bounds(lba: Long, blocks: Int) {
        if (lba < 0 || blocks < 0 || lba + blocks > blockCount) throw IOException("Access out of range: $lba+$blocks of $blockCount")
    }

    private fun pieces(what: String, lba: Long, blocks: Int, each: (Long, Int, Int) -> Unit) {
        var done = 0
        while (done < blocks) {
            retrying(what) {
                val n = minOf(blocks - done, maxOf(1, commandBytes / blockSize))
                each(lba + done, n, done * blockSize)
                done += n
            }
        }
    }

    override fun sync() {
        if (bot.command(byteArrayOf(0x35, 0, 0, 0, 0, 0, 0, 0, 0, 0), timeoutMs = 120_000) != 0) requestSense()
    }

    /** SCSI UNMAP of [blocks] blocks from [lba]. Only call when [discard] says it's supported. */
    fun unmap(lba: Long, blocks: Long) {
        if (!discard.supported) throw ScsiException("This drive doesn't support discard (UNMAP)")
        if (readOnly) throw DeviceReadOnlyException()
        val perCmd = minOf(discard.maxUnmapBlocks, 0xFFFFFFFFL).coerceAtLeast(1)
        var at = lba
        val end = lba + blocks
        while (at < end) {
            val n = minOf(perCmd, end - at)
            val param = ByteBuffer.allocate(24)
            param.putShort(22).putShort(16).putInt(0).putLong(at).putInt(n.toInt()).putInt(0)
            val cdb = ByteArray(10).also {
                it[0] = 0x42
                it[7] = 0
                it[8] = 24
            }
            check(bot.command(cdb, dataOut = param.array(), timeoutMs = 120_000), "discard blocks")
            at += n
        }
    }

    /**
     * ATA PASS-THROUGH(16) (SAT) for USB-SATA bridges. [protocol] 4 = PIO data-in, 3 = non-data.
     * Returns the 14-byte ATA status return descriptor when [checkCondition] is set and the bridge
     * supplies it, otherwise an empty array.
     */
    fun ataPassThrough(
        command: Int,
        feature: Int = 0,
        lbaLow: Int = 0,
        lbaMid: Int = 0,
        lbaHigh: Int = 0,
        sectorCount: Int = 0,
        dataIn: ByteArray? = null,
        checkCondition: Boolean = false,
    ): ByteArray {
        val protocol = if (dataIn != null) 4 else 3
        val cdb = ByteArray(16)
        cdb[0] = 0x85.toByte()
        cdb[1] = (protocol shl 1).toByte()
        // T_DIR=1 (from device), BYT_BLOK=1, T_LENGTH=2 (sector count field); CK_COND bit 5.
        cdb[2] = ((if (checkCondition) 0x20 else 0) or (if (dataIn != null) 0x0E else 0)).toByte()
        cdb[4] = feature.toByte()
        cdb[6] = sectorCount.toByte()
        cdb[8] = lbaLow.toByte()
        cdb[10] = lbaMid.toByte()
        cdb[12] = lbaHigh.toByte()
        cdb[13] = 0xA0.toByte() // device register: LBA mode
        cdb[14] = command.toByte()
        val status = bot.command(cdb, dataIn = dataIn, timeoutMs = 30_000)
        if (status == 0) return ByteArray(0)
        val sense = requestSenseRaw()
        val s = parseSense(sense)
        // CK_COND answers with "ATA pass-through information available" and the registers attached.
        if (checkCondition) {
            val d = findAtaDescriptor(sense)
            if (d.isNotEmpty()) return d
        }
        throw ScsiException("The USB bridge rejected the ATA command (sense 0x%x/0x%02x)".format(s.key, s.asc), s.key, s.asc, s.ascq)
    }

    private fun findAtaDescriptor(sense: ByteArray): ByteArray {
        if ((sense[0].toInt() and 0x7F) != 0x72) return ByteArray(0)
        var i = 8
        val end = minOf(sense.size, 8 + (sense[7].toInt() and 0xFF))
        while (i + 1 < end) {
            val type = sense[i].toInt() and 0xFF
            val len = sense[i + 1].toInt() and 0xFF
            if (type == 0x09 && i + 2 + len <= sense.size) return sense.copyOfRange(i, i + 2 + len)
            i += 2 + len
        }
        return ByteArray(0)
    }

    private fun rwCdb(op10: Int, op16: Int, lba: Long, blocks: Int): ByteArray {
        require(blocks in 1..0xFFFF)
        return if (lba + blocks <= 0xFFFFFFFFL) {
            ByteBuffer.allocate(10).put(op10.toByte()).put(0).putInt(lba.toInt()).put(0)
                .putShort(blocks.toShort()).put(0).array()
        } else {
            ByteBuffer.allocate(16).put(op16.toByte()).put(0).putLong(lba).putInt(blocks).put(0).put(0).array()
        }
    }

    private fun check(status: Int, what: String) {
        if (status == 0) return
        val s = requestSense()
        val msg = when (s.key) {
            0x7 -> "This drive is write-protected. Check for a lock switch, or use another drive."
            0x2 -> if (s.asc == 0x3A) "There's no card or medium in the drive." else "The drive isn't ready. Unplug it and plug it back in."
            0x3 -> "The drive reported a media error while trying to $what. It may be failing."
            0x5 -> "The drive rejected the request to $what (sense code 0x%02x).".format(s.asc)
            else -> "Couldn't $what (sense key 0x%x, code 0x%02x).".format(s.key, s.asc)
        }
        throw ScsiException(msg, s.key, s.asc, s.ascq)
    }

    data class Sense(val key: Int, val asc: Int, val ascq: Int)

    private fun requestSenseRaw(): ByteArray {
        val buf = ByteArray(64)
        return try {
            bot.command(byteArrayOf(0x03, 0, 0, 0, buf.size.toByte(), 0), dataIn = buf)
            buf
        } catch (e: IOException) {
            ByteArray(18)
        }
    }

    private fun requestSense(): Sense = parseSense(requestSenseRaw())

    private fun parseSense(b: ByteArray): Sense {
        return if ((b[0].toInt() and 0x7F) >= 0x72) {
            Sense(b[1].toInt() and 0x0F, b[2].toInt() and 0xFF, b[3].toInt() and 0xFF)
        } else {
            Sense(b[2].toInt() and 0x0F, b[12].toInt() and 0xFF, b[13].toInt() and 0xFF)
        }
    }

    companion object {
        val COMMAND_LADDER = listOf(1 shl 20, 256 shl 10, 120 shl 10, 64 shl 10, 16 shl 10, 4 shl 10)
        const val MIN_TRANSFER = 16 * 1024
        const val MAX_ATTEMPTS = 8
        const val READ10 = 0x28
        const val WRITE10 = 0x2A
        const val READ16 = 0x88
        const val WRITE16 = 0x8A

        /** Not ready, media error, illegal request, data protect: real answers that retrying won't change. */
        val FINAL_SENSE_KEYS = setOf(0x2, 0x3, 0x5, 0x7)

        /** Pause between readiness polls; tests shrink it. */
        @Volatile
        var SETTLE_MS = 250L

        fun ascii(b: ByteArray, off: Int, len: Int): String {
            if (off >= b.size) return ""
            val s = String(b, off, minOf(len, b.size - off), Charsets.US_ASCII)
            return s.filter { it.code in 0x20..0x7E }.trim()
        }
    }
}
