package io.github.ravenor907.ravendisk.core.io

import io.github.ravenor907.ravendisk.core.util.isAllZero
import java.io.IOException

/**
 * A large simulated disk that only stores chunks holding non-zero data. Lets tests and the debug
 * demo work with realistic drive sizes (hundreds of GB) in a few megabytes of memory.
 */
class SparseDisk(
    override val blockSize: Int = 512,
    override val blockCount: Long,
    override var readOnly: Boolean = false,
) : BlockDevice {
    private val chunkBytes = 64 * 1024
    private val blocksPerChunk = chunkBytes / blockSize
    private val chunks = HashMap<Long, ByteArray>()

    /** Called before each write; throw from it to simulate an unplugged drive. */
    var beforeWrite: (lba: Long, blocks: Int) -> Unit = { _, _ -> }

    /** Artificial delay per write call, so the demo's progress is visible. */
    var writeDelayMs = 0L

    val storedChunks: Int get() = synchronized(chunks) { chunks.size }

    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        check(lba, blocks)
        synchronized(chunks) {
            var done = 0
            while (done < blocks) {
                val at = lba + done
                val c = at / blocksPerChunk
                val inChunk = (at % blocksPerChunk).toInt()
                val n = minOf(blocks - done, blocksPerChunk - inChunk)
                val data = chunks[c]
                val dst = offset + done * blockSize
                if (data == null) java.util.Arrays.fill(buf, dst, dst + n * blockSize, 0)
                else System.arraycopy(data, inChunk * blockSize, buf, dst, n * blockSize)
                done += n
            }
        }
    }

    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        if (readOnly) throw DeviceReadOnlyException()
        check(lba, blocks)
        beforeWrite(lba, blocks)
        if (writeDelayMs > 0) Thread.sleep(writeDelayMs)
        synchronized(chunks) {
            var done = 0
            while (done < blocks) {
                val at = lba + done
                val c = at / blocksPerChunk
                val inChunk = (at % blocksPerChunk).toInt()
                val n = minOf(blocks - done, blocksPerChunk - inChunk)
                val src = offset + done * blockSize
                val existing = chunks[c]
                if (existing == null) {
                    if (!buf.isAllZero(src, src + n * blockSize)) {
                        val fresh = ByteArray(chunkBytes)
                        System.arraycopy(buf, src, fresh, inChunk * blockSize, n * blockSize)
                        chunks[c] = fresh
                    }
                } else {
                    System.arraycopy(buf, src, existing, inChunk * blockSize, n * blockSize)
                    if (existing.isAllZero()) chunks.remove(c)
                }
                done += n
            }
        }
    }

    override fun sync() {}

    private fun check(lba: Long, blocks: Int) {
        if (lba < 0 || blocks < 0 || lba + blocks > blockCount) throw IOException("Access out of range: $lba+$blocks of $blockCount")
    }
}
