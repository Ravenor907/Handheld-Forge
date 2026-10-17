package io.github.ravenor907.handheldforge.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** Counts reads, to prove a skipped check really reads nothing. */
private class CountingDisk(val inner: BlockDevice) : BlockDevice by inner {
    var reads = 0
    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        reads++
        inner.read(lba, blocks, buf, offset)
    }
}

/** A fake drive: claims [claimed] blocks but only stores [real]; addresses wrap around like cheap counterfeits. */
private class FakeCapacityDisk(val store: FileDisk, override val blockCount: Long) : BlockDevice {
    override val blockSize = store.blockSize
    override val name = "fake"
    private fun map(lba: Long) = lba % store.blockCount
    override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        for (i in 0 until blocks) store.read(map(lba + i), 1, buf, offset + i * blockSize)
    }
    override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
        for (i in 0 until blocks) store.write(map(lba + i), 1, buf, offset + i * blockSize)
    }
    override fun sync() = store.sync()
}

private fun tmp() = Files.createTempFile("hf", ".img").toFile().apply { deleteOnExit() }

class SpeedFixTest {
    /** The one-core decoder must never come back: every bzip2 image goes through the parallel one. */
    @Test
    fun noPathUsesTheSingleCoreBzip2Decoder() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.name != "ParallelBzip2InputStream.kt" }
            .filter { "BZip2CompressorInputStream" in it.readText() }.map { it.name }.toList()
        assertEquals("files using the single-core bzip2 decoder: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun catalogImagesUseTheParallelDecoder() {
        val packed = ByteArrayOutputStream().also { o -> BZip2CompressorOutputStream(o, 1).use { it.write(ByteArray(1000) { 7 }) } }.toByteArray()
        assertTrue(ImageKind.decoder(ImageKind.BZIP2)(ByteArrayInputStream(packed)) is ParallelBzip2InputStream)
    }

    @Test
    fun writerLogsWhereTheTimeWent() {
        val img = Random.nextBytes(6 * 1024 * 1024)
        FileDisk(tmp(), 32L * 2048).use { d ->
            val log = ArrayList<String>()
            ImageWriter.writeStream({ ByteArrayInputStream(img) }, img.size.toLong(), { it }, d, sha(img), { _, _, _ -> }, { log.add(it) }, { false })
            assertTrue(log.toString(), log.any { it.startsWith("Wrote 0.01 GB in") && "waiting on the drive" in it })
            assertTrue(log.any { it.startsWith("Every byte on the drive matches the image (read back at") })
        }
    }

    @Test
    fun skippingTheCheckReadsNothingBack() {
        val img = Random.nextBytes(5 * 1024 * 1024)
        FileDisk(tmp(), 32L * 2048).use { f ->
            val d = CountingDisk(f)
            val log = ArrayList<String>()
            ImageWriter.writeStream({ ByteArrayInputStream(img) }, img.size.toLong(), { it }, d, null, { _, _, _ -> }, { log.add(it) }, { false }, verify = false)
            assertEquals(0, d.reads)
            assertTrue(log.any { "Skipped reading the drive back" in it })
            // The data still landed.
            val back = ByteArray(img.size)
            f.read(0, img.size / 512, back)
            assertArrayEquals(img, back)
        }
    }
}

class CheckDriveTest {
    @Test
    fun matchingDriveAndFirstDifference() {
        val disk = Random.nextBytes(5 * 1024 * 1024).also { it[510] = 0x55; it[511] = 0xAA.toByte() }
        val packed = ByteArrayOutputStream().also { o -> BZip2CompressorOutputStream(o, 1).use { it.write(disk) } }.toByteArray()
        FileDisk(tmp(), 16L * 2048).use { d ->
            d.write(0, disk.size / 512, disk)
            val dec = ImageKind.decoder(ImageKind.BZIP2)
            assertNull(ImageWriter.compare({ ByteArrayInputStream(packed) }, packed.size.toLong(), dec, d, { _, _, _ -> }, {}, { false }))
            // One flipped byte deep in: reported exactly.
            val at = 4_321_987L
            val one = ByteArray(512)
            d.read(at / 512, 1, one)
            one[(at % 512).toInt()] = (one[(at % 512).toInt()].toInt() xor 1).toByte()
            d.write(at / 512, 1, one)
            assertEquals(at, ImageWriter.compare({ ByteArrayInputStream(packed) }, packed.size.toLong(), dec, d, { _, _, _ -> }, {}, { false }))
        }
    }
}

class DriveTestTest {
    private fun run(dev: BlockDevice) = DriveTest.run(dev, { _, _, _ -> }, {}, { false }, minBytes = 8L shl 20, maxBytes = 16L shl 20, spots = 16, seed = 42)

    @Test
    fun realDrivePasses() {
        FileDisk(tmp(), 64L * 2048).use { d ->
            val r = run(d)
            assertFalse(r.fake)
            assertFalse(r.failing)
            assertEquals(16, r.spots)
            assertTrue(r.testedBytes >= 8L shl 20)
            assertTrue(r.writeMBs > 0 && r.readMBs > 0)
            assertTrue(r.lines().joinToString("\n"), r.lines().any { it.startsWith("Capacity: all 16 markers") })
        }
    }

    @Test
    fun wrapAroundFakeIsCaught() {
        // Claims 64 MB, really holds 24 MB; addresses past that land back at the start.
        FileDisk(tmp(), 24L * 2048).use { store ->
            val r = run(FakeCapacityDisk(store, 64L * 2048))
            assertTrue(r.fake || r.failing)
            assertTrue(r.lines().joinToString("\n"), r.lines().any { "Don't trust it" in it })
            assertFalse(r.lines().any { it.startsWith("Capacity: all") })
        }
    }

    @Test
    fun fakeThatDropsWritesPastItsSizeIsCaught() {
        // Claims 64 MB, keeps 24 MB, silently drops anything written past that.
        FileDisk(tmp(), 24L * 2048).use { store ->
            val dropping = object : BlockDevice {
                override val blockSize = 512
                override val blockCount = 64L * 2048
                override val name = "fake"
                override fun read(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
                    for (i in 0 until blocks) {
                        if (lba + i < store.blockCount) store.read(lba + i, 1, buf, offset + i * 512)
                        else java.util.Arrays.fill(buf, offset + i * 512, offset + (i + 1) * 512, 0)
                    }
                }
                override fun write(lba: Long, blocks: Int, buf: ByteArray, offset: Int) {
                    for (i in 0 until blocks) if (lba + i < store.blockCount) store.write(lba + i, 1, buf, offset + i * 512)
                }
                override fun sync() = store.sync()
            }
            val r = run(dropping)
            assertTrue(r.fake)
            assertNotNull(r.firstBadSpot)
            assertTrue(r.firstBadSpot!! >= 24L shl 20)
            assertTrue(r.lines().any { it.startsWith("FAKE CAPACITY") })
        }
    }

    @Test
    fun corruptReadsAreReportedAsFailing() {
        val f = FakeUsbDrive(blocks = 64L * 2048, corruptReadsFrom = 3L shl 20)
        val disk = ScsiDisk(BulkOnly(f)).init()
        val r = run(disk)
        assertTrue(r.failing)
    }

    @Test
    fun slowdownAfterTheCacheIsSpotted() {
        val r = DriveTest.Report(64_000_000_000, 1 shl 30, listOf(80.0, 79.0, 12.0, 4.0), 30.0, 90.0, 0, 128, 0, null)
        assertNotNull(r.slowdown)
        assertTrue(r.lines().any { "drops to 4 MB/s" in it || "drops to 8 MB/s" in it })
    }
}

class LiveDownloadTest {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val files = HashMap<String, ByteArray>()
    private val hits: MutableList<String> = ArrayList()
    private val server = MiniHttp(files, hits)
    private val base = "http://127.0.0.1:${server.port}"
    private val cache = Files.createTempDirectory("hflive").toFile()
    private val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(kp.public.encoded) + "\n-----END PUBLIC KEY-----\n"
    private val dl = Downloader(cache, { pem }, rewriteUrl = { it.replace("https://dl", base) })

    private fun publish(data: ByteArray) {
        files["/live.iso"] = data
        files["/live.iso-CHECKSUM"] = "${sha(data)}  live.iso\n".toByteArray()
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(data) }
        files["/live.iso.sig"] = Base64.getEncoder().encode(s.sign())
    }

    private val distro = Distro(
        "t", "Test OS", "", true, 1, "k.pub",
        listOf(ImageSource("live.iso", "https://dl/live.iso", "https://dl/live.iso-CHECKSUM", "https://dl/live.iso.sig", true)),
    )

    private fun writeLive(): Pair<PreparedImage, ByteArray> {
        val img = dl.prepare(distro, { _, _, _ -> }, {}, { false }, stream = true)
        val live = img.live!!
        val got = live.open().readBytes()
        live.finish {}
        return img to got
    }

    @Test
    fun writesWhileDownloadingThenKeepsAVerifiedCopy() {
        val data = Random.nextBytes(3_000_000)
        publish(data)
        val (_, got) = writeLive()
        assertArrayEquals(data, got)
        // Kept as a verified, signed copy: the next run doesn't download it again.
        hits.clear()
        val again = dl.prepare(distro, { _, _, _ -> }, {}, { false }, stream = true)
        assertTrue(again.fromCache)
        assertTrue(again.signatureVerified)
        assertNull(again.live)
        assertFalse("/live.iso" in hits)
    }

    @Test
    fun resumesFromAPartialDownloadAndAfterADroppedConnection() {
        val data = Random.nextBytes(4_000_000)
        publish(data)
        File(cache, "live.iso.part").writeBytes(data.copyOfRange(0, 1_000_000))
        File(cache, "live.iso.part.expected").writeText(sha(data))
        server.dropNextAfter = 700_000
        val (_, got) = writeLive()
        assertArrayEquals(data, got)
        assertEquals(sha(data), sha(File(cache, "live.iso").readBytes()))
    }

    @Test
    fun aBadDownloadIsCaughtAtTheEndAndDeleted() {
        val data = Random.nextBytes(2_000_000)
        publish(data)
        files["/live.iso"] = data.copyOf().also { it[1_234_567] = (it[1_234_567].toInt() xor 1).toByte() }
        val img = dl.prepare(distro, { _, _, _ -> }, {}, { false }, stream = true)
        img.live!!.open().readBytes()
        try {
            img.live!!.finish {}
            fail("should reject")
        } catch (e: VerificationException) {
            // The signature or the checksum catches it.
        }
        assertFalse(File(cache, "live.iso").exists())
        assertFalse(File(cache, "live.iso.part").exists())
    }

    @Test
    fun finishReadsWhatTheWriterLeftUnread() {
        val data = Random.nextBytes(1_500_000)
        publish(data)
        val img = dl.prepare(distro, { _, _, _ -> }, {}, { false }, stream = true)
        img.live!!.open().read(ByteArray(1000))
        img.live!!.finish {}
        assertEquals(sha(data), sha(File(cache, "live.iso").readBytes()))
    }
}
