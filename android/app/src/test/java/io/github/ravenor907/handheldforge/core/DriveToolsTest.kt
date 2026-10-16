package io.github.ravenor907.handheldforge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

private fun le32(b: ByteArray, o: Int) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(o).toLong() and 0xFFFFFFFFL

class ExFatTest {
    private fun tmp() = Files.createTempFile("disk", ".img").toFile().apply { deleteOnExit() }

    @Test
    fun plainDriveLayout() {
        val sectors = 4L * 1024 * 1024 * 1024 / 512
        FileDisk(tmp(), sectors).use { d ->
            PlainDrive.format(d, stage = { _, _, _ -> }, log = {}, cancel = { false })
            val mbr = ByteArray(512).also { d.read(0, 1, it) }
            assertEquals(0x55, mbr[510].toInt() and 0xFF)
            assertEquals(0x07, mbr[450].toInt() and 0xFF)
            assertEquals(2048L, le32(mbr, 454))
            val vbr = ByteArray(512).also { d.read(2048, 1, it) }
            assertEquals("EXFAT   ", String(vbr, 3, 8))
            assertEquals(2048L, ByteBuffer.wrap(vbr).order(ByteOrder.LITTLE_ENDIAN).getLong(64))
            val backup = ByteArray(512).also { d.read(2048 + 12, 1, it) }
            assertArrayEquals(vbr, backup)
        }
    }

    @Test
    fun bootChecksumMatchesSpec() {
        val l = ExFat.layout(2048, 2_000_000)
        val r = ExFat.bootRegion(l, 0x1234)
        var sum = 0
        for (i in 0 until 11 * 512) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = (if (sum and 1 != 0) 0x80000000.toInt() else 0) + (sum ushr 1) + (r[i].toInt() and 0xFF)
        }
        assertEquals(sum.toLong() and 0xFFFFFFFFL, le32(r, 11 * 512))
        assertEquals(sum.toLong() and 0xFFFFFFFFL, le32(r, 11 * 512 + 508))
    }

    @Test
    fun clusterSizesMatchVentoy() {
        assertEquals(64, ExFat.defaultSectorsPerCluster(16_000_000_000 / 512))
        assertEquals(256, ExFat.defaultSectorsPerCluster(64_000_000_000 / 512))
    }

    @Test
    fun formatsOverAFlakyStick() {
        val f = FakeUsbDrive(blocks = 65536, maxTransfer = 64 * 1024, writeCommandLimit = 64 * 1024)
        val d = ScsiDisk(BulkOnly(f)).init()
        PlainDrive.format(d, stage = { _, _, _ -> }, log = {}, cancel = { false })
        assertEquals("EXFAT   ", String(f.disk, 2048 * 512 + 3, 8))
    }
}

class VentoyTest {
    private fun xz(b: ByteArray) = ByteArrayOutputStream().also { o ->
        org.tukaani.xz.XZOutputStream(o, org.tukaani.xz.LZMA2Options(0)).use { it.write(b) }
    }.toByteArray()

    private val bootImg = ByteArray(512) { (it % 251).toByte() }
    private val core = ByteArray(1000 * 512) { (it % 241).toByte() }
    private val part2 = ByteArray((Ventoy.PART2_SECTORS * 512).toInt()).also { "VTOYEFI".toByteArray().copyInto(it, 43) }

    /** A package laid out like ventoy-x.y.z-linux.tar.gz. */
    private fun tarball(): ByteArray = ByteArrayOutputStream().also { o ->
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(
            org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(o),
        ).use { t ->
            t.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, data) in listOf(
                "ventoy-9.9.9/Ventoy2Disk.sh" to "#!/bin/sh".toByteArray(),
                "ventoy-9.9.9/boot/boot.img" to bootImg,
                "ventoy-9.9.9/boot/core.img.xz" to xz(core),
                "ventoy-9.9.9/ventoy/ventoy.disk.img.xz" to xz(part2),
            )) {
                val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
                e.size = data.size.toLong()
                t.putArchiveEntry(e)
                t.write(data)
                t.closeArchiveEntry()
            }
        }
    }.toByteArray()

    @Test
    fun partitionMathMatchesVentoy2Disk() {
        // 16 GB stick: values from running Ventoy2Disk.sh's formulas by hand.
        val p = Ventoy.plan(31_266_816)
        val p1End = 31_266_816L - 65536 - 1
        val mod = (p1End + 1) % 8
        assertEquals(p1End - mod + 1, p.part2Start)
        assertEquals(0L, p.part2Start % 8)
        assertEquals(p.part2Start - 2048, p.part1Sectors)
        assertEquals(64, p.sectorsPerCluster)
        assertEquals(256, Ventoy.plan(128_000_000_000 / 512).sectorsPerCluster)
    }

    @Test
    fun parsesGithubRelease() {
        val json = """{"tag_name":"v1.1.07","assets":[
            {"name":"ventoy-1.1.07-windows.zip","browser_download_url":"https://x/w.zip"},
            {"name":"ventoy-1.1.07-linux.tar.gz","browser_download_url":"https://x/l.tar.gz"},
            {"name":"ventoy-1.1.07-livecd.iso","browser_download_url":"https://x/c.iso"},
            {"name":"sha256.txt","browser_download_url":"https://x/sha256.txt"}]}"""
        val r = Ventoy.parseRelease(json)
        assertEquals("1.1.07", r.version)
        assertEquals("ventoy-1.1.07-linux.tar.gz", r.tarName)
        assertEquals("https://x/l.tar.gz", r.tarUrl)
        assertEquals("https://x/sha256.txt", r.shaUrl)
    }

    @Test
    fun installsLikeVentoy2Disk() {
        val files = Ventoy.extract(ByteArrayInputStream(tarball()))
        val sectors = 3L * 1024 * 1024 * 1024 / 512
        val img = Files.createTempFile("ventoy", ".img").toFile().apply { deleteOnExit() }
        FileDisk(img, sectors).use { d ->
            Ventoy.install(d, files, { _, _, _ -> }, {}, { false })
            val p = Ventoy.plan(sectors)
            val mbr = ByteArray(512).also { d.read(0, 1, it) }
            assertArrayEquals(bootImg.copyOf(384), mbr.copyOf(384))
            assertArrayEquals(bootImg.copyOfRange(400, 440), mbr.copyOfRange(400, 440))
            // partition 1: active, NTFS/exFAT type, from 2048
            assertEquals(0x80, mbr[446].toInt() and 0xFF)
            assertEquals(0x07, mbr[450].toInt() and 0xFF)
            assertEquals(2048L, le32(mbr, 454))
            assertEquals(p.part1Sectors, le32(mbr, 458))
            // partition 2: EFI, 65536 sectors, at the aligned start
            assertEquals(0xEF, mbr[466].toInt() and 0xFF)
            assertEquals(p.part2Start, le32(mbr, 470))
            assertEquals(65536L, le32(mbr, 474))
            assertEquals(0xAA, mbr[511].toInt() and 0xFF)
            val c = ByteArray(core.size).also { d.read(1, core.size / 512, it) }
            assertArrayEquals(core, c)
            val e = ByteArray(part2.size).also { d.read(p.part2Start, 65536, it) }
            assertArrayEquals(part2, e)
            val vbr = ByteArray(512).also { d.read(2048, 1, it) }
            assertEquals("EXFAT   ", String(vbr, 3, 8))
        }
    }

    /** CI sets these to write real images (real Ventoy release, plain drive) for fsck, mount and QEMU. */
    @Test
    fun exportImagesForCi() {
        val out = System.getenv("HF_IMAGE_OUT") ?: return
        val dir = File(out).apply { mkdirs() }
        val sectors = (System.getenv("HF_IMAGE_SECTORS") ?: "${8L * 1024 * 1024 * 1024 / 512}").toLong()
        FileDisk(File(dir, "plain.img"), sectors).use { PlainDrive.format(it, stage = { _, _, _ -> }, log = ::println, cancel = { false }) }
        System.getenv("HF_VENTOY_TAR")?.let { tar ->
            val files = File(tar).inputStream().use { Ventoy.extract(it) }
            FileDisk(File(dir, "ventoy.img"), sectors).use { Ventoy.install(it, files, { _, _, _ -> }, ::println, { false }) }
            // Without Secure Boot support (CI compares it with Ventoy2Disk.sh -I -S).
            FileDisk(File(dir, "ventoy-nosb.img"), sectors).use {
                Ventoy.install(it, files, { _, _, _ -> }, ::println, { false }, secureBoot = false)
            }
            // Ventoy plus images added by the importer, plain and compressed (CI boots one from Ventoy's menu).
            System.getenv("HF_IMPORT_DIR")?.let { src ->
                FileDisk(File(dir, "ventoy-import.img"), sectors).use { d ->
                    Ventoy.install(d, files, { _, _, _ -> }, ::println, { false })
                    val vol = VentoyDrive.detect(d)!!.openData(d)
                    val sources = File(src).listFiles()!!.filter { it.isFile }.sortedBy { it.name }
                        .map { f -> VentoyImport.Source(f.name, f.length()) { f.inputStream() } }
                    val plans = sources.map { VentoyImport.plan(it, { _, _ -> }, { false }) }
                    println("Imported: " + VentoyImport.run(vol, plans, { _, _, _ -> }, ::println, { false }))
                }
            }
        }
    }
}

class UpcaseTest {
    /** Expands the compressed table the way readers do, then checks it against the case rules. */
    @Test
    fun compressedTableExpandsToFullMapping() {
        val t = ExFat.upcaseTable()
        assertTrue("table is ${t.size} bytes", t.size < 0x10000) // fits old tools' 16-bit size
        val bb = ByteBuffer.wrap(t).order(ByteOrder.LITTLE_ENDIAN)
        val full = IntArray(0x10000)
        var c = 0
        while (bb.hasRemaining()) {
            val v = bb.short.toInt() and 0xFFFF
            if (v == 0xFFFF) {
                val run = bb.short.toInt() and 0xFFFF
                for (i in 0 until run) full[c + i] = c + i
                c += run
            } else {
                full[c++] = v
            }
        }
        assertEquals(0x10000, c)
        assertEquals('A'.code, full['a'.code])
        assertEquals('Z'.code, full['z'.code])
        assertEquals(0x0410, full[0x0430]) // Cyrillic а -> А
        assertEquals('5'.code, full['5'.code])
    }
}
