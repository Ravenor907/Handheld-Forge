package io.github.ravenor907.handheldforge.core

import java.io.File
import java.io.RandomAccessFile

/** A disk backed by a (sparse) file, for big-drive tests and for images CI checks with real tools. */
class FileDisk(file: File, override val blockCount: Long, override val blockSize: Int = 512) : BlockDevice, AutoCloseable {
    private val f = RandomAccessFile(file, "rw").also { it.setLength(blockCount * blockSize) }
    override val name = "file disk"
    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
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
}
