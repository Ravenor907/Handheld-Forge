package io.github.ravenor907.ravendisk.core.io

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Random access to a disk in whole logical blocks. Everything above this layer is backend-agnostic. */
interface BlockDevice {
    val blockSize: Int
    val blockCount: Long
    val sizeBytes: Long get() = blockSize.toLong() * blockCount

    /** A short human name for logs ("SanDisk Ultra"). */
    val name: String get() = "Drive"

    /** True when the device refuses writes (lock switch, read-only backend). */
    val readOnly: Boolean get() = false

    fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int = 0)
    fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int = 0)
    fun sync()

    fun readBlocks(lba: Long, blocks: Int): ByteArray = ByteArray(blocks * blockSize).also { read(lba, blocks, it) }

    /** Reads [length] bytes at byte [offset], for probes that don't care about block boundaries. */
    fun readBytes(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0)
        val first = offset / blockSize
        val last = (offset + length + blockSize - 1) / blockSize
        if (last > blockCount) throw IOException("Read past the end of the device")
        val n = (last - first).toInt()
        if (n == 0) return ByteArray(0)
        val buf = readBlocks(first, n)
        val skip = (offset - first * blockSize).toInt()
        return buf.copyOfRange(skip, skip + length)
    }
}

class DeviceReadOnlyException(msg: String = "The device is read-only") : IOException(msg)

/** An in-memory disk. Used by tests and by the debug demo. */
open class MemoryDisk(
    override val blockSize: Int = 512,
    override val blockCount: Long,
    override var readOnly: Boolean = false,
) : BlockDevice {
    init {
        require(blockSize in 512..4096 && blockSize and (blockSize - 1) == 0)
        require(blockCount * blockSize <= Int.MAX_VALUE) { "MemoryDisk is limited to 2 GiB" }
    }

    val data = ByteArray((blockCount * blockSize).toInt())
    var writes = 0
        private set
    var syncs = 0
        private set

    /** Writes fail after this many more write calls (simulates an unplugged drive). -1 = never. */
    var failWritesAfter = -1

    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        check(lba, blocks)
        System.arraycopy(data, (lba * blockSize).toInt(), buf, offset, blocks * blockSize)
    }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        if (readOnly) throw DeviceReadOnlyException()
        check(lba, blocks)
        if (failWritesAfter == 0) throw IOException("Device disconnected")
        if (failWritesAfter > 0) failWritesAfter--
        System.arraycopy(buf, offset, data, (lba * blockSize).toInt(), blocks * blockSize)
        writes++
    }

    override fun sync() {
        syncs++
    }

    private fun check(lba: Long, blocks: Int) {
        if (lba < 0 || blocks < 0 || lba + blocks > blockCount) {
            throw IOException("Access out of range: $lba+$blocks of $blockCount")
        }
    }
}

/** A disk backed by a (sparse) file: big-drive tests and images that CI checks with real tools. */
class FileDisk(
    file: File,
    override val blockCount: Long,
    override val blockSize: Int = 512,
    create: Boolean = true,
) : BlockDevice, AutoCloseable {
    private val f = RandomAccessFile(file, "rw").also { if (create) it.setLength(blockCount * blockSize) }

    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        require(lba >= 0 && lba + blocks <= blockCount) { "read past the end: $lba+$blocks of $blockCount" }
        f.seek(lba * blockSize)
        f.readFully(buf, offset, blocks * blockSize)
    }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        require(lba >= 0 && lba + blocks <= blockCount) { "write past the end: $lba+$blocks of $blockCount" }
        f.seek(lba * blockSize)
        f.write(buf, offset, blocks * blockSize)
    }

    override fun sync() = f.fd.sync()
    override fun close() = f.close()

    companion object {
        fun open(file: File, blockSize: Int = 512) = FileDisk(file, file.length() / blockSize, blockSize, create = false)
    }
}
