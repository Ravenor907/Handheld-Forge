package io.github.ravenor907.handheldforge.core

import java.io.File
import java.io.IOException
import java.security.MessageDigest

class WriteException(msg: String) : IOException(msg)

/**
 * Writes an ISO to a block device byte for byte (Bazzite's ISO is a hybrid
 * image, so this is a bootable drive), then reads the drive back and compares.
 */
object ImageWriter {
    const val CHUNK = 1 shl 20

    fun write(
        iso: File,
        dev: BlockDevice,
        expectedSha256: String,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
    ) {
        val size = iso.length()
        val bs = dev.blockSize
        require(CHUNK % bs == 0)
        if (dev.sizeBytes < size) throw WriteException("This drive is too small for the image.")
        val buf = ByteArray(CHUNK)
        val md = MessageDigest.getInstance("SHA-256")

        // Clear the end of the drive: a stale backup partition table there from an
        // earlier use can confuse some firmware about which layout is real.
        val tailBlocks = minOf(CHUNK / bs.toLong(), dev.blockCount)
        java.util.Arrays.fill(buf, 0)
        dev.write(dev.blockCount - tailBlocks, tailBlocks.toInt(), buf)

        log("Writing ${"%.1f".format(size / 1e9)} GB to ${dev.name}")
        var done = 0L
        iso.inputStream().use { input ->
            while (done < size) {
                if (cancel()) throw CancelledException()
                val want = minOf(CHUNK.toLong(), size - done).toInt()
                var got = 0
                while (got < want) {
                    val n = input.read(buf, got, want - got)
                    if (n < 0) throw WriteException("The image file ended early")
                    got += n
                }
                md.update(buf, 0, got)
                val padded = roundUp(got, bs)
                if (padded > got) java.util.Arrays.fill(buf, got, padded, 0)
                dev.write(done / bs, padded / bs, buf)
                done += got
                stage(Stage.WRITE, done, size)
            }
        }
        if (md.digest().toHex() != expectedSha256) {
            throw WriteException("The image changed while it was being written. Try again.")
        }
        stage(Stage.WRITE, size, size)
        dev.sync()

        log("Reading the drive back to confirm")
        val check = MessageDigest.getInstance("SHA-256")
        var read = 0L
        while (read < size) {
            if (cancel()) throw CancelledException()
            val want = minOf(CHUNK.toLong(), size - read).toInt()
            val padded = roundUp(want, bs)
            dev.read(read / bs, padded / bs, buf)
            check.update(buf, 0, want)
            read += want
            stage(Stage.CHECK, read, size)
        }
        if (check.digest().toHex() != expectedSha256) {
            throw WriteException(
                "The drive didn't store the image correctly. It may be failing or counterfeit. Try another drive.",
            )
        }
        log("Every byte on the drive matches the image")
    }

    private fun roundUp(n: Int, to: Int) = (n + to - 1) / to * to
}
