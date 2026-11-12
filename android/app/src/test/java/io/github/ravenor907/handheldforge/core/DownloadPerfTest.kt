package io.github.ravenor907.handheldforge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random

private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

/** The download is hashed while it's saved: these pin down that the result is the same as reading it back. */
class DownloadPerfTest {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val files = HashMap<String, ByteArray>()
    private val hits: MutableList<String> = ArrayList()
    private val server = MiniHttp(files, hits)
    private val base = "http://127.0.0.1:${server.port}"
    private val cache = Files.createTempDirectory("hfperf").toFile()
    private val pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(kp.public.encoded) + "\n-----END PUBLIC KEY-----\n"
    private val dl = Downloader(cache, { pem }, rewriteUrl = { it.replace("https://dl", base) })
    private val stages = ArrayList<Triple<Stage, Long, Long>>()
    private val logs = ArrayList<String>()

    private fun sign(data: ByteArray): ByteArray =
        Base64.getEncoder().encode(Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(data) }.sign())

    private fun publish(data: ByteArray, sig: Boolean) {
        files["/live.iso"] = data
        files["/live.iso-CHECKSUM"] = "${sha256Hex(data)}  live.iso\n".toByteArray()
        if (sig) files["/live.iso.sig"] = sign(data)
    }

    private fun distro(sig: Boolean) = Distro(
        "t", "Test OS", "", true, 1, "k.pub",
        listOf(ImageSource("live.iso", "https://dl/live.iso", "https://dl/live.iso-CHECKSUM", if (sig) "https://dl/live.iso.sig" else null, sig)),
    )

    private fun prepare(sig: Boolean) = dl.prepare(distro(sig), { s, d, t -> stages.add(Triple(s, d, t)) }, { logs.add(it) }, { false })

    private fun leavePart(data: ByteArray, bytes: ByteArray) {
        File(cache, "live.iso.part").writeBytes(bytes)
        File(cache, "live.iso.part.expected").writeText(sha256Hex(data))
    }

    private fun assertKept(data: ByteArray, signed: Boolean) {
        assertArrayEquals(data, File(cache, "live.iso").readBytes())
        assertEquals(sha256Hex(data) + if (signed) "\nsigned" else "", File(cache, "live.iso.verified").readText())
        assertFalse(File(cache, "live.iso.part").exists())
        assertFalse(File(cache, "live.iso.part.expected").exists())
    }

    @Test
    fun freshDownloadIsVerifiedWithoutReadingItBack() {
        val data = Random.nextBytes(6_000_000)
        publish(data, sig = true)
        val img = prepare(sig = true)
        assertTrue(img.signatureVerified)
        assertEquals(sha256Hex(data), img.sha256)
        assertKept(data, signed = true)
        assertTrue("Checksum and signature verified" in logs)
        // Verify still starts with nothing measured and ends complete.
        val verify = stages.filter { it.first == Stage.VERIFY }
        assertEquals(Triple(Stage.VERIFY, 0L, 0L), verify.first())
        assertEquals(Triple(Stage.VERIFY, data.size.toLong(), data.size.toLong()), verify.last())
        assertEquals(listOf("/live.iso-CHECKSUM", "/live.iso.sig", "/live.iso"), hits)
    }

    @Test
    fun resumedPartIsHashedFromItsFirstByte() {
        val data = Random.nextBytes(3_000_000)
        publish(data, sig = true)
        leavePart(data, data.copyOfRange(0, 1_234_567))
        assertTrue(prepare(sig = true).signatureVerified)
        assertKept(data, signed = true)
        // Progress carries on from where the file left off.
        assertTrue(stages.filter { it.first == Stage.DOWNLOAD }.all { it.second > 1_234_567 })
    }

    @Test
    fun damagedBytesInAResumedPartAreCaught() {
        val data = Random.nextBytes(3_000_000)
        publish(data, sig = false)
        leavePart(data, data.copyOfRange(0, 1_000_000).also { it[4321] = (it[4321].toInt() xor 1).toByte() })
        try {
            prepare(sig = false)
            fail("should reject")
        } catch (e: VerificationException) {
            assertEquals("The download was corrupted, so it was deleted. Try again.", e.message)
        }
        assertFalse(File(cache, "live.iso").exists())
        assertFalse(File(cache, "live.iso.part").exists())
        assertFalse(File(cache, "live.iso.part.expected").exists())
    }

    @Test
    fun damagedSignedPartFailsTheSignature() {
        val data = Random.nextBytes(2_000_000)
        publish(data, sig = true)
        leavePart(data, data.copyOfRange(0, 500_000).also { it[0] = (it[0].toInt() xor 1).toByte() })
        try {
            prepare(sig = true)
            fail("should reject")
        } catch (e: VerificationException) {
            assertEquals("Signature does NOT match the pinned key. Refusing this image.", e.message)
        }
        assertFalse(File(cache, "live.iso").exists())
    }

    @Test
    fun aPartThatIsAlreadyCompleteIsVerifiedFromDisk() {
        val data = Random.nextBytes(2_000_000)
        publish(data, sig = true)
        leavePart(data, data)
        assertTrue(prepare(sig = true).signatureVerified)
        assertKept(data, signed = true)
    }

    @Test
    fun aDroppedConnectionStillVerifies() {
        val data = Random.nextBytes(3_000_000)
        publish(data, sig = true)
        // The image is asked for twice, a HEAD for its size and then the GET: cut the GET short.
        var asked = 0
        val dropping = Downloader(cache, { pem }, rewriteUrl = { url ->
            if (url.endsWith("/live.iso") && ++asked == 2) server.dropNextAfter = 800_000
            url.replace("https://dl", base)
        })
        val img = dropping.prepare(distro(sig = true), { _, _, _ -> }, { logs.add(it) }, { false })
        assertTrue(img.signatureVerified)
        assertKept(data, signed = true)
        // Resumed from where it stopped, not started over.
        assertEquals(2, hits.count { it == "/live.iso" })
        assertEquals(3, asked)
    }

    @Test
    fun fileHashReportsProgressEveryFourMegabytes() {
        val data = Random.nextBytes((9 shl 20) + 123)
        val f = Files.createTempFile("hf", ".bin").toFile().apply { writeBytes(data) }
        val seen = ArrayList<Pair<Long, Long>>()
        val (hex, signed) = Verify.hashAndVerify(f, String(sign(data)), kp.public) { d, t -> seen.add(d to t) }
        assertEquals(sha256Hex(data), hex)
        assertTrue(signed)
        val total = data.size.toLong()
        assertEquals(listOf((4L shl 20) to total, (8L shl 20) to total, total to total), seen)
        f.delete()
    }

    @Test
    fun runningCheckMatchesTheFileCheck() {
        val data = Random.nextBytes(700_001)
        val sig = String(sign(data))
        val r = Verify.Running(sig, kp.public)
        var off = 0
        while (off < data.size) {
            val n = minOf(65_537, data.size - off)
            r.update(data, off, n)
            off += n
        }
        assertEquals(sha256Hex(data) to true, r.finish())
        assertEquals(sha256Hex(data) to false, Verify.Running(null, null).also { it.update(data, 0, data.size) }.finish())

        val bad = Verify.Running(sig, kp.public).also { it.update(data, 0, data.size - 1) }
        try {
            bad.finish()
            fail("should reject")
        } catch (e: VerificationException) {
            assertEquals("Signature does NOT match the pinned key. Refusing this image.", e.message)
        }
        try {
            Verify.Running("not base64!", kp.public).also { it.update(data, 0, data.size) }.finish()
            fail("should reject")
        } catch (e: VerificationException) {
            assertEquals("Signature file is not valid base64", e.message)
        }
    }
}
