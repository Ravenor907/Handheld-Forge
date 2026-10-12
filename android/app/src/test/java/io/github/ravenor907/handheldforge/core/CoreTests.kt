package io.github.ravenor907.handheldforge.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.random.Random

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).toHex()

private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.simpleName} but got $e", e)
    }
    throw AssertionError("Expected ${T::class.simpleName}")
}

/** Finds the repo's shared catalog whether tests run from android/app or elsewhere. */
fun repoFile(rel: String): File {
    var dir: File? = File("").absoluteFile
    while (dir != null) {
        val f = File(dir, rel)
        if (f.exists()) return f
        dir = dir.parentFile
    }
    error("$rel not found")
}

class CatalogTest {
    @Test
    fun parsesSharedCatalog() {
        val cat = Catalog.parse(repoFile("handheld_forge/distros.json").readText())
        assertEquals(listOf("bazzite-deck"), cat.filter { it.enabled }.map { it.id })
        val b = cat.first()
        assertEquals("Bazzite", b.name)
        assertEquals("Handheld KDE", b.edition)
        assertEquals("bazzite-deck-stable-live-amd64.iso", b.images[0].filename)
        assertTrue(b.images[0].requireSignature)
        assertEquals("keys/bazzite-cosign.pub", b.publicKey)
    }

    @Test
    fun rejectsPlainHttp() {
        val bad = """{"schema":1,"distros":[{"id":"x","name":"X","enabled":true,
            "images":[{"label":"a","iso_url":"http://e/x.iso","checksum_url":"https://e/c"}]}]}"""
        assertThrows<CatalogException> { Catalog.parse(bad) }
    }

    @Test
    fun jsonEdgeCases() {
        @Suppress("UNCHECKED_CAST")
        val m = Json.parse("""{"a":"q\"é\n","b":[1,-2.5,true,null],"c":{}}""") as Map<String, Any?>
        assertEquals("q\"é\n", m["a"])
        assertEquals(listOf(1L, -2.5, true, null), m["b"])
        assertThrows<IllegalArgumentException> { Json.parse("{\"a\":1,}") }
    }
}

class VerifyTest {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    private fun cosignSig(data: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(data) }
        return Base64.getEncoder().encodeToString(s.sign())
    }

    private fun tmp(data: ByteArray) = Files.createTempFile("hf", ".iso").toFile().apply { writeBytes(data) }

    @Test
    fun checksumFormats() {
        val h = "a".repeat(64)
        assertEquals(h, Verify.parseChecksum("$h  bazzite.iso\n", "bazzite.iso"))
        assertEquals(h, Verify.parseChecksum("$h */upload/bazzite.iso", "bazzite.iso"))
        assertEquals(h, Verify.parseChecksum(h.uppercase(), "x.iso"))
        assertThrows<VerificationException> { Verify.parseChecksum("<html>404</html>", "bazzite.iso") }
    }

    @Test
    fun goodSignature() {
        val data = Random.nextBytes(300_000)
        val (hex, signed) = Verify.hashAndVerify(tmp(data), cosignSig(data), kp.public)
        assertEquals(sha(data), hex)
        assertTrue(signed)
    }

    @Test
    fun tamperedFileRejected() {
        val sig = cosignSig(byteArrayOf(1, 2, 3))
        assertThrows<VerificationException> { Verify.hashAndVerify(tmp(byteArrayOf(1, 2, 4)), sig, kp.public) }
    }

    @Test
    fun pinnedBazziteKeyLoads() {
        val key = Verify.loadPublicKey(repoFile("handheld_forge/keys/bazzite-cosign.pub").readText())
        assertEquals("EC", key.algorithm)
    }
}

class DownloaderTest {
    private val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    private val files = HashMap<String, ByteArray>()
    private val hits = ArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = files[ex.requestURI.path]
            if (ex.requestMethod == "GET") hits.add(ex.requestURI.path)
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                val range = ex.requestHeaders.getFirst("Range")
                val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val chunk = body.copyOfRange(start, body.size)
                if (ex.requestMethod == "HEAD") {
                    ex.responseHeaders.add("Content-Length", body.size.toString())
                    ex.sendResponseHeaders(200, -1)
                } else {
                    ex.sendResponseHeaders(if (range != null) 206 else 200, chunk.size.toLong())
                    ex.responseBody.use { it.write(chunk) }
                }
            }
            ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val cache = Files.createTempDirectory("hfcache").toFile()
    private val pem = "-----BEGIN PUBLIC KEY-----\n" +
        Base64.getEncoder().encodeToString(kp.public.encoded) + "\n-----END PUBLIC KEY-----\n"

    private fun publish(name: String, data: ByteArray, sign: Boolean = true) {
        files["/$name"] = data
        files["/$name-CHECKSUM"] = "${sha(data)}  $name\n".toByteArray()
        if (sign) {
            val s = Signature.getInstance("SHA256withECDSA").apply { initSign(kp.private); update(data) }
            files["/$name.sig"] = Base64.getEncoder().encode(s.sign())
        }
    }

    private fun distro(vararg names: String, sig: Boolean = true) = Distro(
        "t", "Test OS", "", true, 1, "k.pub",
        names.map { ImageSource(it, "https://dl/$it", "https://dl/$it-CHECKSUM", if (sig) "https://dl/$it.sig" else null, sig) },
    )

    private val dl = Downloader(cache, { pem }, rewriteUrl = { it.replace("https://dl", base) })
    private fun prepare(d: Distro, cancel: () -> Boolean = { false }) = dl.prepare(d, { _, _, _ -> }, {}, cancel)

    @Test
    fun downloadsVerifiesThenUsesCache() {
        val data = Random.nextBytes(3_000_000)
        publish("live.iso", data)
        val img = prepare(distro("live.iso"))
        assertTrue(img.signatureVerified)
        assertFalse(img.fromCache)
        assertArrayEquals(data, img.file.readBytes())
        hits.clear()
        val again = prepare(distro("live.iso"))
        assertTrue(again.fromCache)
        assertTrue(again.signatureVerified)
        assertFalse("/live.iso" in hits)
    }

    @Test
    fun resumesPartialDownload() {
        val data = Random.nextBytes(2_000_000)
        publish("live.iso", data)
        File(cache, "live.iso.part").writeBytes(data.copyOfRange(0, 700_000))
        File(cache, "live.iso.part.expected").writeText(sha(data))
        assertArrayEquals(data, prepare(distro("live.iso")).file.readBytes())
    }

    @Test
    fun fallsBackToSecondImage() {
        publish("legacy.iso", Random.nextBytes(10_000), sign = false)
        val img = prepare(distro("live.iso", "legacy.iso", sig = false))
        assertEquals("legacy.iso", img.source.filename)
    }

    @Test
    fun corruptDownloadRejected() {
        publish("live.iso", ByteArray(5000) { 1 })
        files["/live.iso"] = ByteArray(5000) { 2 }
        assertThrows<VerificationException> { prepare(distro("live.iso")) }
        assertFalse(File(cache, "live.iso").exists())
    }

    @Test
    fun forgedSignatureRejected() {
        publish("live.iso", Random.nextBytes(5000))
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(other.private); update(files["/live.iso"]) }
        files["/live.iso.sig"] = Base64.getEncoder().encode(s.sign())
        assertThrows<VerificationException> { prepare(distro("live.iso")) }
    }

    @Test
    fun cancel() {
        publish("live.iso", Random.nextBytes(9_000_000))
        assertThrows<CancelledException> { prepare(distro("live.iso")) { true } }
    }
}

class ScsiTest {
    private fun disk(d: FakeUsbDrive) = ScsiDisk(BulkOnly(d)).init()

    @Test
    fun identifiesDrive() {
        val d = disk(FakeUsbDrive(blocks = 4096))
        assertEquals("FakeCo Test Stick 3000", d.name)
        assertEquals(512, d.blockSize)
        assertEquals(4096L, d.blockCount)
    }

    @Test
    fun waitsForSlowStick() {
        val f = FakeUsbDrive(notReadyCount = 3)
        disk(f)
        assertEquals(4, f.commands.count { it == 0x00 })
    }

    @Test
    fun readWriteRoundTripAcrossTransferPieces() {
        val f = FakeUsbDrive(blocks = 4096, maxTransfer = 4096)
        val d = disk(f)
        val data = Random.nextBytes(64 * 512)
        d.write(100, 64, data)
        val back = ByteArray(64 * 512)
        d.read(100, 64, back)
        assertArrayEquals(data, back)
        assertArrayEquals(data, f.disk.copyOfRange(100 * 512, 164 * 512))
    }

    @Test
    fun fourKSectors() {
        val d = disk(FakeUsbDrive(blockSize = 4096, blocks = 512))
        assertEquals(4096, d.blockSize)
        val data = Random.nextBytes(4096 * 3)
        d.write(7, 3, data)
        val back = ByteArray(4096 * 3)
        d.read(7, 3, back)
        assertArrayEquals(data, back)
    }

    @Test
    fun hugeDriveUsesReadCapacity16() {
        val f = FakeUsbDrive(blocks = 64, reportedBlocks = 10_000_000_000L)
        val d = disk(f)
        assertEquals(10_000_000_000L, d.blockCount)
        assertTrue(0x9E in f.commands)
    }

    @Test
    fun writeProtectedStickGetsPlainMessage() {
        val d = disk(FakeUsbDrive(writeProtected = true))
        val e = assertThrows<ScsiException> { d.write(0, 1, ByteArray(512)) }
        assertTrue(e.message!!.contains("write-protected"))
    }
}

class ImageWriterTest {
    private fun iso(size: Int) = Files.createTempFile("img", ".iso").toFile().apply { writeBytes(Random.nextBytes(size)) }

    @Test
    fun writesExactImageAndClearsTail() {
        val f = FakeUsbDrive(blocks = 16384)
        f.disk.fill(7)
        val d = ScsiDisk(BulkOnly(f)).init()
        val img = iso(3 * ImageWriter.CHUNK + 1234) // not block aligned on purpose
        val stages = ArrayList<Stage>()
        ImageWriter.write(img, d, sha(img.readBytes()), { s, _, _ -> stages.add(s) }, {}, { false })
        val bytes = img.readBytes()
        assertArrayEquals(bytes, f.disk.copyOfRange(0, bytes.size))
        assertTrue(f.disk.copyOfRange(f.disk.size - ImageWriter.CHUNK, f.disk.size).all { it == 0.toByte() })
        assertTrue(Stage.WRITE in stages && Stage.CHECK in stages)
        assertTrue(0x35 in f.commands) // cache flushed before read-back
    }

    @Test
    fun counterfeitStickCaught() {
        val f = FakeUsbDrive(blocks = 16384)
        val d = ScsiDisk(BulkOnly(f)).init()
        val img = iso(2 * ImageWriter.CHUNK)
        f.corruptReadsFrom = ImageWriter.CHUNK + 5L
        val e = assertThrows<WriteException> {
            ImageWriter.write(img, d, sha(img.readBytes()), { _, _, _ -> }, {}, { false })
        }
        assertTrue(e.message!!.contains("counterfeit"))
    }

    @Test
    fun tooSmall() {
        val d = ScsiDisk(BulkOnly(FakeUsbDrive(blocks = 100))).init()
        val img = iso(200 * 512)
        assertThrows<WriteException> { ImageWriter.write(img, d, sha(img.readBytes()), { _, _, _ -> }, {}, { false }) }
    }

    @Test
    fun cancelStops() {
        val d = ScsiDisk(BulkOnly(FakeUsbDrive(blocks = 16384))).init()
        val img = iso(3 * ImageWriter.CHUNK)
        assertThrows<CancelledException> { ImageWriter.write(img, d, sha(img.readBytes()), { _, _, _ -> }, {}, { true }) }
    }

    @Test
    fun wrongExpectedHashFails() {
        val d = ScsiDisk(BulkOnly(FakeUsbDrive(blocks = 16384))).init()
        val img = iso(ImageWriter.CHUNK)
        try {
            ImageWriter.write(img, d, "0".repeat(64), { _, _, _ -> }, {}, { false })
            fail("should have thrown")
        } catch (e: WriteException) {
            assertTrue(e.message!!.contains("changed"))
        }
    }
}
