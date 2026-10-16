package io.github.ravenor907.handheldforge.core

import java.io.File
import java.io.IOException
import java.security.MessageDigest

class WriteException(msg: String) : IOException(msg)

/** The image file couldn't be read or decoded (a damaged download), as opposed to a drive failure. */
class CorruptImageException(cause: Throwable) : IOException("The image file is damaged: ${cause.message}", cause)

/**
 * Writes an ISO to a block device byte for byte (Bazzite's ISO is a hybrid
 * image, so this is a bootable drive), then reads the drive back and compares.
 */
object ImageWriter {
    /** Bytes per write command. Big commands let the stick write in long runs, like a PC does. */
    const val CHUNK = 4 shl 20

    /** Writes a plain image file (an ISO); [expectedSha256] must match what's written. */
    fun write(
        iso: File,
        dev: BlockDevice,
        expectedSha256: String,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
    ) {
        if (dev.sizeBytes < iso.length()) throw WriteException("This drive is too small for the image.")
        writeStream(iso, { it }, dev, expectedSha256, stage, log, cancel)
    }

    /**
     * Writes whatever [decode] produces from [file] (identity for an ISO, a
     * bzip2 decoder for SteamOS). Progress follows bytes read from [file], so it's
     * accurate even when the decoded size isn't known in advance. The drive is
     * read back and compared with the hash of what was actually written.
     */
    fun writeStream(
        file: File,
        decode: (java.io.InputStream) -> java.io.InputStream,
        dev: BlockDevice,
        expectedSha256: String?,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
    ) = writeStream({ file.inputStream() }, file.length(), decode, dev, expectedSha256, stage, log, cancel)

    /** Same, reading from [open] (for example a file the person picked); [rawSize] drives progress. */
    fun writeStream(
        open: () -> java.io.InputStream,
        rawSize: Long,
        decode: (java.io.InputStream) -> java.io.InputStream,
        dev: BlockDevice,
        expectedSha256: String?,
        stage: StageFn,
        log: LogFn,
        cancel: () -> Boolean,
    ) {
        val fileSize = rawSize
        val bs = dev.blockSize
        require(CHUNK % bs == 0)
        val buf = ByteArray(CHUNK)
        val md = MessageDigest.getInstance("SHA-256")

        // Clear the end of the drive: a stale backup partition table there from an
        // earlier use can confuse some firmware about which layout is real.
        val tailBlocks = minOf(CHUNK / bs.toLong(), dev.blockCount)
        java.util.Arrays.fill(buf, 0)
        dev.write(dev.blockCount - tailBlocks, tailBlocks.toInt(), buf)

        log("Writing ${dev.name}")
        if (dev is ScsiDisk) {
            log("USB: ${dev.commandBytes / 1024} KB per command, ${dev.transferSize / 1024} KB transfers" + if (dev.pipelined) ", several in flight" else "")
        }
        var done = 0L
        val raw = CountingStream(open().buffered(CHUNK))
        decoding { decode(raw) }.use { input ->
            while (true) {
                if (cancel()) throw CancelledException()
                var got = 0
                while (got < CHUNK) {
                    val n = decoding { input.read(buf, got, CHUNK - got) }
                    if (n < 0) break
                    got += n
                }
                if (got == 0) break
                md.update(buf, 0, got)
                val padded = roundUp(got, bs)
                if (padded > got) java.util.Arrays.fill(buf, got, padded, 0)
                if (done / bs + padded / bs > dev.blockCount) {
                    throw WriteException("This drive is too small for the image.")
                }
                dev.write(done / bs, padded / bs, buf)
                done += got
                stage(Stage.WRITE, raw.count, fileSize)
                if (got < CHUNK) break
            }
        }
        val size = done
        val written = md.digest().toHex()
        if (expectedSha256 != null && written != expectedSha256) {
            throw WriteException("The image changed while it was being written. Try again.")
        }
        stage(Stage.WRITE, fileSize, fileSize)
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
        if (check.digest().toHex() != written) {
            throw WriteException(
                "The drive didn't store the image correctly. It may be failing or counterfeit. Try another drive.",
            )
        }
        log("Every byte on the drive matches the image")
    }

    /** Decoders report bad input as IOException or, for some malformed data, a RuntimeException. */
    private inline fun <T> decoding(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw CorruptImageException(e)
    } catch (e: RuntimeException) {
        throw CorruptImageException(e)
    }

    private fun roundUp(n: Int, to: Int) = (n + to - 1) / to * to

    private class CountingStream(inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}
