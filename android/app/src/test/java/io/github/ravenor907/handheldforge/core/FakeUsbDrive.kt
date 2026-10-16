package io.github.ravenor907.handheldforge.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB stick seen from the wire: parses Bulk-Only CBWs, runs the SCSI
 * commands against an in-memory disk and answers with CSWs.
 */
class FakeUsbDrive(
    val blockSize: Int = 512,
    blocks: Long = 8192,
    /** Report this many blocks via READ CAPACITY instead (to fake huge drives). */
    private val reportedBlocks: Long = blocks,
    var writeProtected: Boolean = false,
    /** TEST UNIT READY fails this many times first, like a freshly plugged stick. */
    var notReadyCount: Int = 0,
    /** Flip a bit in data returned by reads at or after this byte offset (fake/failing stick). */
    var corruptReadsFrom: Long = -1,
    override val maxTransfer: Int = 16384,
    override val largestTransfer: Int = maxTransfer,
    /** Bulk transfers longer than this fail, like a phone whose kernel caps them. */
    private val refuseAbove: Int = Int.MAX_VALUE,
    /** WRITE commands carrying more than this many bytes stall partway, like a stick that chokes on big writes. */
    var writeCommandLimit: Int = Int.MAX_VALUE,
    /** The next this-many data-phase transfers fail once each, like a flaky cable or adapter. */
    var flakyTransfers: Int = 0,
    /** Take the queued-transfers path (the fake still moves them one by one). */
    override val canPipeline: Boolean = false,
) : UsbTransport {
    /** Largest WRITE command (bytes) that succeeded. */
    var biggestWriteCommand = 0
    /** The biggest bulk transfer that went through. */
    var biggestTransfer = 0
    val disk = ByteArray((blocks * blockSize).toInt())
    val commands = ArrayList<Int>()
    var resets = 0

    private var pending: Cbw? = null
    private var dataPos = 0
    private var outBuf = ByteArray(0)
    private var inBuf = ByteArray(0)
    private var status = 0
    private var sense = byteArrayOf(0, 0) // key, asc
    private var cswReady = false

    private class Cbw(val tag: Int, val length: Int, val dirIn: Boolean, val cdb: ByteArray)

    override fun bulkOut(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (length > refuseAbove) return -1
        biggestTransfer = maxOf(biggestTransfer, length)
        val p = pending
        if (p == null) {
            if (length != 31) return -1
            val b = ByteBuffer.wrap(data, offset, 31).order(ByteOrder.LITTLE_ENDIAN)
            if (b.getInt(offset) != BulkOnly.CBW_SIGNATURE) return -1
            val tag = b.getInt(offset + 4)
            val len = b.getInt(offset + 8)
            val dirIn = data[offset + 12].toInt() and 0x80 != 0
            val cbLen = data[offset + 14].toInt()
            val cdb = data.copyOfRange(offset + 15, offset + 15 + cbLen)
            val cbw = Cbw(tag, len, dirIn, cdb)
            commands.add(cdb[0].toInt() and 0xFF)
            pending = cbw
            dataPos = 0
            cswReady = false
            if (dirIn) {
                execute(cbw, null)
                if (len == 0) cswReady = true
            } else {
                outBuf = ByteArray(len)
                if (len == 0) {
                    execute(cbw, outBuf)
                    cswReady = true
                }
            }
            return 31
        }
        if (p.dirIn) return -1
        if (flakyTransfers > 0) {
            flakyTransfers--
            return -1
        }
        val op = p.cdb[0].toInt() and 0xFF
        if ((op == 0x2A || op == 0x8A) && p.length > writeCommandLimit && dataPos + length > writeCommandLimit) return -1
        System.arraycopy(data, offset, outBuf, dataPos, length)
        dataPos += length
        if (dataPos >= p.length) {
            execute(p, outBuf)
            cswReady = true
        }
        return length
    }

    override fun bulkIn(data: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
        if (length > refuseAbove) return -1
        biggestTransfer = maxOf(biggestTransfer, length)
        val p = pending ?: return -1
        if (!cswReady) {
            val n = minOf(length, inBuf.size - dataPos)
            System.arraycopy(inBuf, dataPos, data, offset, n)
            dataPos += n
            if (dataPos >= p.length || dataPos >= inBuf.size) cswReady = true
            return n
        }
        if (length < 13) return -1
        val residue = (p.length - (if (p.dirIn) inBuf.size.coerceAtMost(p.length) else p.length)).coerceAtLeast(0)
        val csw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(BulkOnly.CSW_SIGNATURE).putInt(p.tag).putInt(residue).put(status.toByte()).array()
        System.arraycopy(csw, 0, data, offset, 13)
        pending = null
        return 13
    }

    override fun resetRecovery() {
        resets++
        pending = null
    }

    private fun fail(key: Int, asc: Int) {
        status = 1
        sense = byteArrayOf(key.toByte(), asc.toByte())
    }

    private fun execute(c: Cbw, out: ByteArray?) {
        status = 0
        inBuf = ByteArray(0)
        val cdb = ByteBuffer.wrap(c.cdb)
        when (c.cdb[0].toInt() and 0xFF) {
            0x00 -> if (notReadyCount > 0) {
                notReadyCount--
                fail(0x2, 0x04)
            }
            0x03 -> {
                inBuf = ByteArray(18).also {
                    it[0] = 0x70
                    it[2] = sense[0]
                    it[12] = sense[1]
                }
                sense = byteArrayOf(0, 0)
            }
            0x12 -> inBuf = ByteArray(36).also {
                "FakeCo  ".toByteArray().copyInto(it, 8)
                "Test Stick 3000 ".toByteArray().copyInto(it, 16)
            }
            0x25 -> inBuf = ByteBuffer.allocate(8)
                .putInt(if (reportedBlocks - 1 > 0xFFFFFFFFL) -1 else (reportedBlocks - 1).toInt())
                .putInt(blockSize).array()
            0x9E -> inBuf = ByteBuffer.allocate(32).putLong(reportedBlocks - 1).putInt(blockSize).array()
            0x28, 0x88 -> {
                val (lba, n) = if (c.cdb[0] == 0x28.toByte()) {
                    (cdb.getInt(2).toLong() and 0xFFFFFFFFL) to (cdb.getShort(7).toInt() and 0xFFFF)
                } else {
                    cdb.getLong(2) to cdb.getInt(10)
                }
                val from = (lba * blockSize).toInt()
                inBuf = disk.copyOfRange(from, from + n * blockSize)
                if (corruptReadsFrom >= 0 && from + inBuf.size > corruptReadsFrom) {
                    val i = maxOf(0, (corruptReadsFrom - from).toInt())
                    inBuf[i] = (inBuf[i].toInt() xor 1).toByte()
                }
            }
            0x2A, 0x8A -> {
                if (writeProtected) {
                    fail(0x7, 0x27)
                    return
                }
                val lba = if (c.cdb[0] == 0x2A.toByte()) cdb.getInt(2).toLong() and 0xFFFFFFFFL else cdb.getLong(2)
                System.arraycopy(out!!, 0, disk, (lba * blockSize).toInt(), out.size)
            }
            0x35 -> {}
            else -> fail(0x5, 0x20) // invalid command
        }
    }
}
