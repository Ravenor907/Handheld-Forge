package io.github.ravenor907.handheldforge.core

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
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
        assertEquals(listOf("bazzite-deck", "steamos", "cachyos-handheld"), cat.filter { it.enabled }.map { it.id })
        val b = cat.first()
        assertEquals("Bazzite", b.name)
        assertEquals("Handheld KDE", b.edition)
        assertEquals("bazzite-deck-stable-live-amd64.iso", b.images[0].filename)
        assertTrue(b.images[0].requireSignature)
        assertEquals("keys/bazzite-cosign.pub", b.publicKey)
        assertEquals("iso", b.format)

        val steam = cat.first { it.id == "steamos" }
        assertEquals("raw-bz2", steam.format)
        assertEquals("steamdeck-repair-latest.img.bz2", steam.images[0].filename)
        assertEquals(null, steam.images[0].checksumUrl)
        assertFalse(steam.images[0].requireSignature)

        val cachy = cat.first { it.id == "cachyos-handheld" }
        assertEquals("iso", cachy.format)
        assertTrue(cachy.images.isNotEmpty())
        // CachyOS's installer has no controller support on the Ally: warn, and say how to get through it.
        assertTrue(cachy.warning!!.contains("touchscreen"))
        assertTrue(cachy.installSteps.any { "150%" in it })
        assertEquals(null, b.warning)
        cachy.images.forEach {
            assertTrue(it.filename.matches(Regex("cachyos-handheld-linux-\\d{6}\\.iso")))
            assertEquals(it.isoUrl + ".sha256", it.checksumUrl)
        }
    }

    @Test
    fun labsCatalogIsSeparateAndHidden() {
        val main = Catalog.parse(repoFile("handheld_forge/distros.json").readText())
        assertTrue(main.none { it.hidden || it.id == "batocera" })
        val labs = io.github.ravenor907.handheldforge.Labs.parse(
            repoFile("handheld_forge/labs.json").readText(), main.map { it.id }.toSet(),
        )
        assertEquals(listOf("batocera"), labs.map { it.id })
        val bat = labs.single()
        assertTrue(bat.enabled && bat.hidden)
        assertEquals("raw-gz", bat.format)
        assertEquals(32, bat.minDriveGb)
        assertTrue(bat.warning!!.startsWith("Experimental"))
        assertTrue(bat.installSteps.size >= 2)
        assertTrue((bat.warning + bat.installSteps.joinToString()).none { it == '\u2014' })
        bat.images.forEach {
            assertTrue(it.filename, it.filename.matches(Regex("batocera-zen3-x86-64-v3-[\\d.]+-\\d{8}\\.img\\.gz")))
            assertEquals(null, it.checksumUrl)
        }
        // A labs entry can never replace a regular system.
        assertTrue(io.github.ravenor907.handheldforge.Labs.parse(
            repoFile("handheld_forge/labs.json").readText(), setOf("batocera"),
        ).isEmpty())
    }

    @Test
    fun rejectsUnknownFormat() {
        val bad = """{"schema":1,"distros":[{"id":"x","name":"X","enabled":true,"format":"raw-xz",
            "images":[{"label":"a","iso_url":"https://e/x.img.xz","checksum_url":null}]}]}"""
        assertThrows<CatalogException> { Catalog.parse(bad) }
    }

    @Test
    fun everyEnabledSystemHasInstallSteps() {
        val text = repoFile("handheld_forge/distros.json").readText()
        Catalog.parse(text).filter { it.enabled }.forEach { assertTrue(it.id, it.installSteps.size >= 2) }
        val menu = Catalog.bootMenu(text)
        assertTrue(menu.any { "ROG Ally" in it } && menu.any { "Steam Deck" in it })
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
    private val hits: MutableList<String> = ArrayList()
    private val server = MiniHttp(files, hits)
    private val base = "http://127.0.0.1:${server.port}"
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
    fun newestBuildFromThePublishersPage() {
        // CachyOS names each build by date: the download page's newest link wins, and the catalog's
        // own build is used when the page is unreadable or names a build that isn't uploaded yet.
        for (build in listOf("260628", "260801")) {
            publish("h/$build/os-$build.iso", build.toByteArray().let { b -> ByteArray(5000) { b[it % b.size] } }, sign = false)
            files["/h/$build/os-$build.iso.sha256"] = files.remove("/h/$build/os-$build.iso-CHECKSUM")!!
        }
        val old = "https://dl/h/260628/os-260628.iso"
        files["/download"] = "<a href=\"https://mirror.example/x/h/260801/os-260801.iso\">new</a> $old".toByteArray()
        val pinned = ImageSource("Handheld", old, "$old.sha256", null, false, "https://dl/download", """/h/(\d{6})/os-\1\.iso""")
        val d = Distro("t", "Test OS", "", true, 1, null, listOf(pinned))
        val logs = ArrayList<String>()
        assertEquals("os-260801.iso", dl.prepare(d, { _, _, _ -> }, { logs.add(it) }, { false }).source.filename)
        assertTrue(logs.any { it.contains("Newest build") && it.contains("260801") })
        files["/download"] = "https://dl/h/260901/os-260901.iso".toByteArray()
        assertEquals("os-260628.iso", prepare(d).source.filename)
        files.remove("/download")
        assertEquals("os-260628.iso", prepare(d).source.filename)
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
    fun noChecksumUrlUsesSizeAndCache() {
        val data = Random.nextBytes(1_500_000)
        files["/recovery.img.bz2"] = data
        val d = Distro(
            "s", "SteamOS", "", true, 1, null,
            listOf(ImageSource("Recovery", "https://dl/recovery.img.bz2", null, null, false)),
            format = "raw-bz2",
        )
        val img = prepare(d)
        assertFalse(img.fromCache)
        assertFalse(img.signatureVerified)
        assertArrayEquals(data, img.file.readBytes())
        assertFalse(hits.any { it.endsWith("CHECKSUM") })

        hits.clear()
        assertTrue(prepare(d).fromCache)
        assertFalse("/recovery.img.bz2" in hits)

        // A new upstream image (different size) is fetched again.
        val newer = Random.nextBytes(1_600_000)
        files["/recovery.img.bz2"] = newer
        val fresh = prepare(d)
        assertFalse(fresh.fromCache)
        assertArrayEquals(newer, fresh.file.readBytes())
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
    fun neverSendsHugeTransfers() {
        val f = FakeUsbDrive(blocks = 16384, maxTransfer = 64 * 1024, largestTransfer = 1024 * 1024)
        val d = disk(f)
        val data = Random.nextBytes(4 * 1024 * 1024)
        d.write(0, data.size / 512, data)
        assertTrue(f.biggestTransfer <= 64 * 1024)
        assertArrayEquals(data, f.disk.copyOfRange(0, data.size))
    }

    @Test
    fun stickThatChokesOnBigWritesStillGetsWritten() {
        // Like the failure on the owner's phone: reads are fine, big write commands stall.
        val f = FakeUsbDrive(blocks = 16384, maxTransfer = 64 * 1024, writeCommandLimit = 120 * 1024)
        val d = disk(f)
        val notes = ArrayList<String>()
        d.onNote = { notes.add(it) }
        val data = Random.nextBytes(4 * 1024 * 1024)
        d.write(8, data.size / 512, data)
        assertArrayEquals(data, f.disk.copyOfRange(8 * 512, 8 * 512 + data.size))
        assertTrue(d.commandBytes <= 120 * 1024)
        assertTrue(f.resets >= 1)
        assertTrue(notes.any { it.contains("KB per command") })
    }

    @Test
    fun flakyConnectionIsRetried() {
        val f = FakeUsbDrive(blocks = 16384, maxTransfer = 64 * 1024)
        val d = disk(f)
        f.flakyTransfers = 3
        val data = Random.nextBytes(2 * 1024 * 1024)
        d.write(0, data.size / 512, data)
        assertArrayEquals(data, f.disk.copyOfRange(0, data.size))
    }

    @Test
    fun queuedTransfersWriteCorrectlyAndSwitchOffOnTrouble() {
        val f = FakeUsbDrive(blocks = 16384, maxTransfer = 64 * 1024, canPipeline = true)
        val d = disk(f)
        assertTrue(d.pipelined)
        val data = Random.nextBytes(3 * 1024 * 1024)
        d.write(0, data.size / 512, data)
        val back = ByteArray(data.size)
        d.read(0, data.size / 512, back)
        assertArrayEquals(data, back)
        f.flakyTransfers = 1
        d.write(0, data.size / 512, data)
        assertFalse(d.pipelined) // first hiccup turns queuing off before anything else
        assertEquals(1 shl 20, d.commandBytes)
        assertArrayEquals(data, f.disk.copyOfRange(0, data.size))
    }

    @Test
    fun deadConnectionGivesAdvice() {
        val f = FakeUsbDrive(blocks = 16384, maxTransfer = 64 * 1024)
        val d = disk(f)
        f.flakyTransfers = Int.MAX_VALUE
        val e = assertThrows<java.io.IOException> { d.write(0, 64, ByteArray(64 * 512)) }
        assertTrue(e.message!!.contains("adapter"))
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
        val f = FakeUsbDrive(blocks = 65536)
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
        val f = FakeUsbDrive(blocks = 65536)
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
        val d = ScsiDisk(BulkOnly(FakeUsbDrive(blocks = 65536))).init()
        val img = iso(3 * ImageWriter.CHUNK)
        assertThrows<CancelledException> { ImageWriter.write(img, d, sha(img.readBytes()), { _, _, _ -> }, {}, { true }) }
    }

    @Test
    fun wrongExpectedHashFails() {
        val d = ScsiDisk(BulkOnly(FakeUsbDrive(blocks = 65536))).init()
        val img = iso(ImageWriter.CHUNK)
        try {
            ImageWriter.write(img, d, "0".repeat(64), { _, _, _ -> }, {}, { false })
            fail("should have thrown")
        } catch (e: WriteException) {
            assertTrue(e.message!!.contains("changed"))
        }
    }

    /** Disk-image-like data: random stretches between runs of zeros, so bzip2 has something to do. */
    private fun diskLike(size: Int): ByteArray {
        val r = Random(42)
        val b = ByteArray(size)
        var i = 0
        while (i < size) {
            val n = minOf(r.nextInt(1, 200_000), size - i)
            if (r.nextBoolean()) r.nextBytes(b, i, i + n)
            i += n
        }
        return b
    }

    private fun bz2(vararg parts: ByteArray): File {
        val out = ByteArrayOutputStream()
        // Each part is its own bzip2 stream, like pbzip2 output; the decoder must read them all.
        for (p in parts) BZip2CompressorOutputStream(out).apply { write(p); finish() }
        return Files.createTempFile("img", ".img.bz2").toFile().apply { writeBytes(out.toByteArray()) }
    }

    private fun writeBz2(file: File, f: FakeUsbDrive, stage: StageFn = { _, _, _ -> }) =
        ImageWriter.writeStream(
            file, { BZip2CompressorInputStream(it, true) }, ScsiDisk(BulkOnly(f)).init(),
            null, stage, {}, { false },
        )

    @Test
    fun writesDecompressedRawBz2Image() {
        val f = FakeUsbDrive(blocks = 65536)
        f.disk.fill(7)
        val data = diskLike(3 * ImageWriter.CHUNK + 777)
        val split = ImageWriter.CHUNK + 100
        val file = bz2(data.copyOfRange(0, split), data.copyOfRange(split, data.size))
        val progress = ArrayList<Triple<Stage, Long, Long>>()
        writeBz2(file, f) { s, d, t -> progress.add(Triple(s, d, t)) }

        assertArrayEquals(data, f.disk.copyOfRange(0, data.size))
        // Padding to the block boundary is zeros, and the stale tail is cleared.
        assertTrue(f.disk.copyOfRange(data.size, (data.size + 511) / 512 * 512).all { it == 0.toByte() })
        assertTrue(f.disk.copyOfRange(f.disk.size - ImageWriter.CHUNK, f.disk.size).all { it == 0.toByte() })
        // Write progress counts compressed bytes and ends at the file size; read-back covers the decoded size.
        val writes = progress.filter { it.first == Stage.WRITE }
        assertEquals(file.length(), writes.last().second)
        assertTrue(writes.all { it.third == file.length() })
        assertEquals(data.size.toLong(), progress.last { it.first == Stage.CHECK }.second)
        assertTrue(0x35 in f.commands)
    }

    @Test
    fun corruptBz2Rejected() {
        val good = bz2(diskLike(2 * ImageWriter.CHUNK)).readBytes()
        val flipped = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x10).toByte() }
        val truncated = good.copyOfRange(0, good.size * 2 / 3)
        val notBz2 = Random.nextBytes(10_000)
        for (bad in listOf(flipped, truncated, notBz2)) {
            val file = Files.createTempFile("bad", ".img.bz2").toFile().apply { writeBytes(bad) }
            assertThrows<CorruptImageException> { writeBz2(file, FakeUsbDrive(blocks = 65536)) }
        }
    }

    @Test
    fun rawBz2TooBigForDrive() {
        val file = bz2(ByteArray(2 * ImageWriter.CHUNK))
        val e = assertThrows<WriteException> { writeBz2(file, FakeUsbDrive(blocks = 2048)) }
        assertTrue(e.message!!.contains("too small"))
    }
}

class PreflightTest {
    private val good = PhoneState(80, charging = false, online = true, unmetered = true, backgroundAllowed = true)

    @Test
    fun allGoodNeedsOnlyTheTick() {
        val checks = Preflight.checks(good)
        assertTrue(checks.all { it.ok })
        assertFalse(Preflight.canStart(checks, acknowledged = false))
        assertTrue(Preflight.canStart(checks, acknowledged = true))
    }

    @Test
    fun lowBatteryBlocksUnlessCharging() {
        assertFalse(Preflight.canStart(Preflight.checks(good.copy(batteryPercent = 12)), true))
        assertTrue(Preflight.canStart(Preflight.checks(good.copy(batteryPercent = 12, charging = true)), true))
        assertTrue(Preflight.canStart(Preflight.checks(good.copy(batteryPercent = Preflight.MIN_BATTERY)), true))
        // A phone that doesn't report its battery isn't locked out.
        assertTrue(Preflight.canStart(Preflight.checks(good.copy(batteryPercent = null)), true))
    }

    @Test
    fun offlineBlocks() {
        assertFalse(Preflight.canStart(Preflight.checks(good.copy(online = false)), true))
    }

    @Test
    fun mobileDataAndBatteryOptimizationOnlyWarn() {
        val checks = Preflight.checks(good.copy(unmetered = false, backgroundAllowed = false))
        assertEquals(2, checks.count { !it.ok })
        assertTrue(checks.filter { !it.ok }.none { it.required })
        assertEquals(Fix.BACKGROUND, checks.single { it.fix != null }.fix)
        assertTrue(Preflight.canStart(checks, true))
    }

    @Test
    fun explainsBackgroundFailures() {
        assertTrue(Preflight.explain("Software caused connection abort", true, true).contains("in the background"))
        assertTrue(Preflight.explain("timeout", false, true).contains("connection dropped"))
        val usb = Preflight.explain("The USB drive stopped responding", true, false)
        assertTrue(usb.startsWith("The USB drive stopped responding") && usb.contains("background"))
        assertEquals("Drive is write-protected", Preflight.explain("Drive is write-protected", false, false))
    }
}

class ProgressTest {
    private var now = 0L
    private fun fresh() = Progress { now }.also { now = 0 }

    @Test
    fun onlyCountsUpAndStaysWithin0To100() {
        val p = fresh()
        val seen = mutableListOf<Int>()
        fun at(s: Stage, d: Long, t: Long) = p.update(s, d, t).also { seen += p.percent }
        at(Stage.LOOKUP, 0, 0)
        for (i in 0..10L) at(Stage.DOWNLOAD, i * 100, 1000)
        at(Stage.DOWNLOAD, 200, 1000) // a restarted download
        at(Stage.VERIFY, 0, 0)
        at(Stage.VERIFY, 500, 1000)
        at(Stage.DOWNLOAD, 0, 1000) // a late report from an earlier step
        at(Stage.WRITE, -5, 1000) // odd numbers
        at(Stage.WRITE, 5000, 1000)
        at(Stage.CHECK, 1000, 1000)
        assertEquals(seen, seen.sorted())
        assertTrue(seen.all { it in 0..100 })
        assertEquals(100, seen.last())
        assertEquals(3, p.step)
    }

    @Test
    fun timeLeftFromTheStepsAverageSpeed() {
        val p = fresh()
        p.update(Stage.WRITE, 0, 1000)
        now = 2000
        assertEquals("", p.timeLeft(100, 1000)) // too early for a fair estimate
        now = 10_000
        assertEquals("about 2 min left", p.timeLeft(100, 1000)) // 10/s, 900 to go
        assertEquals("less than a minute left", p.timeLeft(900, 1000))
        assertEquals("", p.timeLeft(2000, 1000))
    }

    @Test
    fun describeSaysWhichStepAndWhatsHappening() {
        val (h, d) = Progress.describe(Stage.WRITE, 4_100_000_000, 8_300_000_000, 0, "Bazzite", "about 7 min left")
        assertEquals("Step 3 of 4: Writing to the drive", h)
        assertEquals("4.1 of 8.3 GB, about 7 min left", d)
        assertEquals("Step 1 of 4: Downloading CachyOS", Progress.describe(Stage.DOWNLOAD, 1, 2, 0, "CachyOS").first)
        val quiet = Progress.describe(Stage.LOOKUP, 0, 0, 83_000, "Bazzite").second
        assertTrue(quiet, quiet.endsWith("(1:23)"))
        assertFalse(Progress.describe(Stage.LOOKUP, 0, 0, 2_000, "Bazzite").second.contains("("))
    }
}

class CustomFileTest {
    @Test
    fun writesFromAStreamWithGzip() {
        val f = FakeUsbDrive(blocks = 32768)
        val d = ScsiDisk(BulkOnly(f)).init()
        val data = kotlin.random.Random.nextBytes(3 * 1024 * 1024 + 77)
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(data) } }.toByteArray()
        ImageWriter.writeStream(
            { java.io.ByteArrayInputStream(gz) }, gz.size.toLong(), { java.util.zip.GZIPInputStream(it) },
            d, null, { _, _, _ -> }, {}, { false },
        )
        assertArrayEquals(data, f.disk.copyOfRange(0, data.size))
    }

    @Test
    fun localJobProgressStartsAtWrite() {
        val p = Progress(Progress.LOCAL_WEIGHTS) { 0L }
        p.update(Stage.WRITE, 0, 100)
        assertEquals(0, p.percent)
        p.update(Stage.WRITE, 50, 100)
        assertEquals(40, p.percent)
        p.update(Stage.CHECK, 100, 100)
        assertEquals(100, p.percent)
        assertEquals("Step 1 of 2: Writing to the drive", Progress.describe(Stage.WRITE, 1, 2, 0, "x.img", local = true).first)
    }

    @Test
    fun noInternetNeededForALocalFile() {
        val offline = PhoneState(80, charging = false, online = false, unmetered = false, backgroundAllowed = true)
        assertFalse(Preflight.canStart(Preflight.checks(offline)))
        assertTrue(Preflight.canStart(Preflight.checks(offline, download = false)))
    }
}

class ImageKindTest {
    private fun iso(hybrid: Boolean) = ByteArray(ImageKind.SNIFF_BYTES).also {
        "CD001".toByteArray().copyInto(it, 32_769)
        if (hybrid) { it[510] = 0x55; it[511] = 0xAA.toByte() }
    }

    @Test
    fun recognizesEveryFormat() {
        assertEquals(ImageKind.ISO_HYBRID, ImageKind.sniff(iso(true)))
        assertEquals(ImageKind.ISO_DVD_ONLY, ImageKind.sniff(iso(false)))
        assertEquals(ImageKind.DISK, ImageKind.sniff(ByteArray(1024).also { it[510] = 0x55; it[511] = 0xAA.toByte() }))
        assertEquals(ImageKind.DISK, ImageKind.sniff(ByteArray(1024).also { "EFI PART".toByteArray().copyInto(it, 512) }))
        assertEquals(ImageKind.BZIP2, ImageKind.sniff("BZh91AY".toByteArray()))
        assertEquals(ImageKind.GZIP, ImageKind.sniff(byteArrayOf(0x1F, 0x8B.toByte(), 8)))
        assertEquals(ImageKind.XZ, ImageKind.sniff(byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0)))
        assertEquals(ImageKind.ZIP, ImageKind.sniff("PK\u0003\u0004".toByteArray()))
        assertEquals(ImageKind.ZSTD, ImageKind.sniff(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())))
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff("hello".toByteArray()))
        assertEquals(ImageKind.UNKNOWN, ImageKind.sniff(ByteArray(0)))
        assertFalse(ImageKind.ZSTD.writable)
        assertTrue(ImageKind.ISO_DVD_ONLY.warning!!.contains("DVD"))
        assertEquals(null, ImageKind.ISO_HYBRID.warning)
    }

    private fun roundTrip(kind: ImageKind, packed: ByteArray, data: ByteArray) {
        val f = FakeUsbDrive(blocks = 32768)
        val d = ScsiDisk(BulkOnly(f)).init()
        assertEquals(kind, ImageKind.sniff(packed.copyOf(minOf(packed.size, ImageKind.SNIFF_BYTES))))
        ImageWriter.writeStream(
            { java.io.ByteArrayInputStream(packed) }, packed.size.toLong(), ImageKind.decoder(kind),
            d, null, { _, _, _ -> }, {}, { false },
        )
        assertArrayEquals(data, f.disk.copyOfRange(0, data.size))
    }

    private val data = kotlin.random.Random.nextBytes(2 * 1024 * 1024 + 5)

    @Test
    fun writesXz() = roundTrip(
        ImageKind.XZ,
        java.io.ByteArrayOutputStream().also { o -> org.tukaani.xz.XZOutputStream(o, org.tukaani.xz.LZMA2Options(0)).use { it.write(data) } }.toByteArray(),
        data,
    )

    @Test
    fun writesZipSkippingMacClutter() = roundTrip(
        ImageKind.ZIP,
        java.io.ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("__MACOSX/._os.img")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
                z.putNextEntry(java.util.zip.ZipEntry("os.img")); z.write(data); z.closeEntry()
            }
        }.toByteArray(),
        data,
    )

    @Test
    fun writesBz2() = roundTrip(
        ImageKind.BZIP2,
        java.io.ByteArrayOutputStream().also { o ->
            org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(o).use { it.write(data) }
        }.toByteArray(),
        data,
    )

    @Test
    fun brokenXzIsReportedAsDamaged() {
        val d = ScsiDisk(BulkOnly(FakeUsbDrive(blocks = 32768))).init()
        val junk = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0) + ByteArray(5000) { 7 }
        try {
            ImageWriter.writeStream({ java.io.ByteArrayInputStream(junk) }, junk.size.toLong(), ImageKind.decoder(ImageKind.XZ), d, null, { _, _, _ -> }, {}, { false })
            fail("should have thrown")
        } catch (e: CorruptImageException) {
        }
    }
}
