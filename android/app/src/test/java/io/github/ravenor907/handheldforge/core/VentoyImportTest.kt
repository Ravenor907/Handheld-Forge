package io.github.ravenor907.handheldforge.core

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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.random.Random

private fun tmpFile() = Files.createTempFile("vol", ".img").toFile().apply { deleteOnExit() }

/** A disk with one exFAT partition at sector 2048, like Format drive or Ventoy's partition 1. */
private fun volume(sizeMb: Long = 512, spc: Int? = null): Pair<FileDisk, ExFatVolume> {
    val d = FileDisk(tmpFile(), sizeMb * 2048)
    val sectors = d.blockCount - 2048
    if (spc == null) ExFat.format(d, 2048, sectors, "Ventoy") else ExFat.format(d, 2048, sectors, "Ventoy", spc)
    return d to ExFatVolume.open(d, 2048)
}

private fun iso(size: Int) = Random.nextBytes(size).also {
    it.fill(0, 0, 34_816.coerceAtMost(size))
    it[510] = 0x55; it[511] = 0xAA.toByte()
    "CD001".toByteArray().copyInto(it, 32_769)
}

class ExFatVolumeTest {
    @Test
    fun freshVolumeIsEmptyWithItsLabel() {
        val (_, v) = volume()
        assertEquals("Ventoy", v.label)
        assertTrue(v.files().isEmpty())
        assertTrue(v.freeBytes > 500L * 1000 * 1000)
    }

    @Test
    fun addReadBackDeleteAndReuse() {
        val (_, v) = volume()
        val data = Random.nextBytes(3 * 1024 * 1024 + 123)
        val free0 = v.freeClusters()
        val hash = v.add("Nobara 44 Steam Handheld 2026-10-13 (long name).iso", data.size.toLong(), ByteArrayInputStream(data), { _, _ -> }, { false })
        val f = v.find("NOBARA 44 STEAM HANDHELD 2026-10-13 (LONG NAME).ISO") // exFAT names ignore case
        assertNotNull(f)
        assertEquals(data.size.toLong(), f!!.size)
        assertTrue(f.contiguous)
        v.verify(f, hash, { _, _ -> }, { false })
        assertEquals(free0 - (data.size + v.clusterBytes - 1) / v.clusterBytes, v.freeClusters())
        // Survives a reopen (everything is really on the disk).
        v.delete(f)
        assertTrue(v.files().isEmpty())
        assertEquals(free0, v.freeClusters())
        v.add("again.iso", 10, ByteArrayInputStream(ByteArray(10)), { _, _ -> }, { false })
        assertEquals(listOf("again.iso"), v.files().map { it.name })
    }

    @Test
    fun reopenSeesWhatWasWritten() {
        val d = FileDisk(tmpFile(), 256L * 2048)
        ExFat.format(d, 2048, d.blockCount - 2048, "Ventoy")
        val data = Random.nextBytes(777_777)
        val h = ExFatVolume.open(d, 2048).add("a.img", data.size.toLong(), ByteArrayInputStream(data), { _, _ -> }, { false })
        val v2 = ExFatVolume.open(d, 2048)
        val f = v2.find("a.img")!!
        v2.verify(f, h, { _, _ -> }, { false })
    }

    @Test
    fun rootFolderGrowsWhenFull() {
        // 512-byte clusters: the root folder holds 16 entries, so it has to grow many times.
        val (_, v) = volume(64, spc = 1)
        val names = (1..60).map { "image-number-$it-with-a-long-name.iso" }
        for (n in names) v.add(n, 1000, ByteArrayInputStream(ByteArray(1000)), { _, _ -> }, { false })
        assertEquals(names.toSet(), v.files().map { it.name }.toSet())
        v.delete(v.find(names[10])!!)
        v.add("reuses-the-gap.iso", 5, ByteArrayInputStream(ByteArray(5)), { _, _ -> }, { false })
        assertEquals(60, v.files().size)
    }

    @Test
    fun refusesWhenThereIsNoRoom() {
        val (_, v) = volume(64)
        try {
            v.add("huge.iso", 200L * 1024 * 1024, ByteArrayInputStream(ByteArray(0)), { _, _ -> }, { false })
            fail("should refuse")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Not enough free space"))
        }
        assertTrue(v.files().isEmpty())
    }

    @Test
    fun emptyFileOwnsNoClusters() {
        val (_, v) = volume(64)
        val free0 = v.freeClusters()
        val h = v.add("empty.iso", 0, ByteArrayInputStream(ByteArray(0)), { _, _ -> }, { false })
        val f = v.find("empty.iso")!!
        assertEquals(0L, f.firstCluster)
        assertEquals(free0, v.freeClusters())
        v.verify(f, h, { _, _ -> }, { false })
        v.delete(f)
        assertEquals(free0, v.freeClusters())
    }

    @Test
    fun cleansNames() {
        assertEquals("a_b_c.iso", ExFatVolume.cleanName("a:b?c.iso"))
        assertEquals("x.iso", ExFatVolume.cleanName("x.iso. "))
        assertEquals(255, ExFatVolume.cleanName("n".repeat(300) + ".iso").length)
    }
}

class VentoyImportTest {
    private fun src(name: String, bytes: ByteArray) = VentoyImport.Source(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    @Test
    fun namesWhatVentoyCanSee() {
        val k = ImageKind.ISO_HYBRID
        assertEquals("bazzite.iso", VentoyImport.targetName("bazzite.iso", k, k))
        assertEquals("download.iso", VentoyImport.targetName("download", k, k))
        assertEquals("os.img", VentoyImport.targetName("os.img.xz", ImageKind.XZ, ImageKind.DISK))
        assertEquals("os.img", VentoyImport.targetName("os.raw.gz", ImageKind.GZIP, ImageKind.DISK))
        assertEquals("steamdeck-repair.img", VentoyImport.targetName("steamdeck-repair.img.bz2", ImageKind.BZIP2, ImageKind.DISK))
        assertEquals("distro.iso", VentoyImport.targetName("distro.iso.zip", ImageKind.ZIP, ImageKind.ISO_HYBRID))
    }

    @Test
    fun importsPlainAndCompressedFilesAndVerifies() {
        val (_, v) = volume(256)
        val a = iso(2 * 1024 * 1024 + 7)
        val disk = Random.nextBytes(3 * 1024 * 1024).also { it[510] = 0x55; it[511] = 0xAA.toByte() }
        val xz = ByteArrayOutputStream().also { o -> org.tukaani.xz.XZOutputStream(o, org.tukaani.xz.LZMA2Options(0)).use { it.write(disk) } }.toByteArray()
        val gz = ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(a) } }.toByteArray()
        val plans = listOf(src("first.iso", a), src("os.img.xz", xz), src("second.iso.gz", gz)).map {
            VentoyImport.plan(it, { _, _ -> }, { false })
        }
        assertEquals(listOf("first.iso", "os.img", "second.iso"), plans.map { it.targetName })
        assertEquals(disk.size.toLong(), plans[1].targetSize)
        assertTrue(plans.all { it.bootable })
        val stages = ArrayList<Stage>()
        VentoyImport.run(v, plans, { s, _, _ -> stages.add(s) }, {}, { false })
        assertEquals(setOf("first.iso", "os.img", "second.iso"), v.files().map { it.name }.toSet())
        // Checks come after all writes.
        assertEquals(Stage.CHECK, stages.last())
        assertFalse(stages.dropWhile { it == Stage.WRITE }.contains(Stage.WRITE))
        // Contents really are the unpacked bytes.
        val f = v.find("os.img")!!
        v.verify(f, java.security.MessageDigest.getInstance("SHA-256").digest(disk), { _, _ -> }, { false })
    }

    @Test
    fun replacesAFileOfTheSameName() {
        val (_, v) = volume(128)
        val one = iso(100_000)
        val two = iso(200_000)
        VentoyImport.run(v, listOf(VentoyImport.plan(src("x.iso", one), { _, _ -> }, { false })), { _, _, _ -> }, {}, { false })
        VentoyImport.run(v, listOf(VentoyImport.plan(src("X.ISO", two), { _, _ -> }, { false })), { _, _, _ -> }, {}, { false })
        assertEquals(1, v.files().size)
        assertEquals(200_000L, v.files()[0].size)
    }

    @Test
    fun duplicateNamesInOneBatchGetNumbered() {
        val (_, v) = volume(128)
        val p = VentoyImport.plan(src("same.iso", iso(50_000)), { _, _ -> }, { false })
        VentoyImport.run(v, listOf(p, p), { _, _, _ -> }, {}, { false })
        assertEquals(setOf("same.iso", "same (2).iso"), v.files().map { it.name }.toSet())
    }

    @Test
    fun damagedArchiveIsReportedBeforeAnythingIsWritten() {
        val junk = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0) + ByteArray(4000) { 9 }
        try {
            VentoyImport.plan(src("broken.img.xz", junk), { _, _ -> }, { false })
            fail("should reject")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("damaged"))
        }
    }

    @Test
    fun notEnoughSpaceIsCaughtUpFront() {
        val (_, v) = volume(64)
        val big = VentoyImport.Source("big.iso", 100L * 1024 * 1024) { ByteArrayInputStream(iso(40_000)) }
        val plan = VentoyImport.Plan(big, ImageKind.ISO_HYBRID, ImageKind.ISO_HYBRID, "big.iso", 100L * 1024 * 1024)
        try {
            VentoyImport.run(v, listOf(plan), { _, _, _ -> }, {}, { false })
            fail("should refuse")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Not enough space"))
        }
        assertNull(v.find("big.iso"))
    }

    @Test
    fun findsVentoyDrivesAndReadsTheirEfiPartition() {
        val files = VentoyTestPackage.files(secureBootFiles = true)
        FileDisk(tmpFile(), 3L * 1024 * 1024 * 1024 / 512).use { d ->
            assertNull(VentoyDrive.detect(d))
            Ventoy.install(d, files, { _, _, _ -> }, {}, { false })
            val vd = VentoyDrive.detect(d)!!
            assertEquals(2048L, vd.data.start)
            val efi = vd.readEfi(d)
            assertTrue(Ventoy.secureBootEnabled(efi))
            assertEquals("9.9.9", Ventoy.versionOf(efi))
            val vol = vd.openData(d)
            assertEquals("Ventoy", vol.label)
        }
    }

    @Test
    fun secureBootOffMatchesVtoycli() {
        val files = VentoyTestPackage.files(secureBootFiles = true)
        FileDisk(tmpFile(), 3L * 1024 * 1024 * 1024 / 512).use { d ->
            Ventoy.install(d, files, { _, _, _ -> }, {}, { false }, secureBoot = false)
            val efi = VentoyDrive.detect(d)!!.readEfi(d)
            val fs = Fat16(efi)
            assertFalse(Ventoy.secureBootEnabled(efi))
            assertArrayEquals(VentoyTestPackage.GRUB_REAL, fs.read("/EFI/BOOT/BOOTX64.EFI"))
            assertArrayEquals(VentoyTestPackage.GRUB_IA32_REAL, fs.read("/EFI/BOOT/BOOTIA32.EFI"))
            for (gone in listOf("grubx64.efi", "grubx64_real.efi", "MokManager.efi", "mmx64.efi", "grubia32.efi", "grubia32_real.efi", "mmia32.efi")) {
                assertNull(gone, fs.find("/EFI/BOOT/$gone"))
            }
            assertNull(fs.find("/ENROLL_THIS_KEY_IN_MOKMANAGER.cer"))
            assertNotNull(fs.find("/grub/grub.cfg")) // everything else stays
            assertEquals("9.9.9", Ventoy.versionOf(efi))
        }
    }
}

/** A Ventoy package with a real FAT16 EFI partition, built the slow, obvious way for tests. */
object VentoyTestPackage {
    val SHIM = ByteArray(70_000) { 1 }
    val GRUB_REAL = ByteArray(150_000) { (it % 7).toByte() }
    val GRUB_IA32_REAL = ByteArray(90_000) { (it % 5).toByte() }

    fun files(secureBootFiles: Boolean): Ventoy.Files {
        val tree = linkedMapOf(
            "EFI/BOOT/BOOTX64.EFI" to SHIM,
            "EFI/BOOT/grubx64.efi" to ByteArray(20_000) { 2 },
            "EFI/BOOT/BOOTIA32.EFI" to ByteArray(30_000) { 3 },
            "EFI/BOOT/grubia32.efi" to ByteArray(10_000) { 4 },
            "grub/grub.cfg" to "set VENTOY_VERSION=\"9.9.9\"\nmenuentry x {}\n".toByteArray(),
        )
        if (secureBootFiles) {
            tree["EFI/BOOT/grubx64_real.efi"] = GRUB_REAL
            tree["EFI/BOOT/grubia32_real.efi"] = GRUB_IA32_REAL
            tree["EFI/BOOT/MokManager.efi"] = ByteArray(5000) { 5 }
            tree["EFI/BOOT/mmx64.efi"] = ByteArray(6000) { 6 }
            tree["EFI/BOOT/mmia32.efi"] = ByteArray(4000) { 7 }
            tree["ENROLL_THIS_KEY_IN_MOKMANAGER.cer"] = ByteArray(900) { 8 }
        }
        val part2 = Fat16Builder.build(tree)
        val xz = { b: ByteArray -> ByteArrayOutputStream().also { o -> org.tukaani.xz.XZOutputStream(o, org.tukaani.xz.LZMA2Options(0)).use { it.write(b) } }.toByteArray() }
        return Ventoy.Files(ByteArray(512) { (it % 251).toByte() }, xz(ByteArray(1000 * 512) { 9 }), xz(part2))
    }
}

/** Minimal FAT16 writer (test fixture): 32 MB, 2 FATs, 512 root entries, long names for every file. */
object Fat16Builder {
    fun build(files: Map<String, ByteArray>): ByteArray {
        val img = ByteArray(32 * 1024 * 1024)
        val b = ByteBuffer.wrap(img).order(ByteOrder.LITTLE_ENDIAN)
        val spc = 4; val reserved = 4; val fatSectors = 64; val rootEntries = 512
        img[0] = 0xEB.toByte(); img[1] = 0x3C; img[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray().copyInto(img, 3)
        b.putShort(11, 512); img[13] = spc.toByte(); b.putShort(14, reserved.toShort()); img[16] = 2
        b.putShort(17, rootEntries.toShort()); b.putShort(19, 0); img[21] = 0xF8.toByte(); b.putShort(22, fatSectors.toShort())
        b.putInt(32, 65536); img[38] = 0x29; "VTOYEFI    ".toByteArray().copyInto(img, 43); "FAT16   ".toByteArray().copyInto(img, 54)
        img[510] = 0x55; img[511] = 0xAA.toByte()
        val rootStart = (reserved + 2 * fatSectors) * 512
        val dataStart = rootStart + rootEntries * 32
        var nextCluster = 2
        fun setFat(c: Int, v: Int) { for (k in 0..1) b.putShort((reserved + k * fatSectors) * 512 + c * 2, v.toShort()) }
        setFat(0, 0xFFF8); setFat(1, 0xFFFF)
        fun clusterOff(c: Int) = dataStart + (c - 2) * spc * 512
        fun alloc(data: ByteArray): Int {
            val n = maxOf(1, (data.size + spc * 512 - 1) / (spc * 512))
            val first = nextCluster
            for (i in 0 until n) setFat(first + i, if (i == n - 1) 0xFFFF else first + i + 1)
            data.copyInto(img, clusterOff(first))
            nextCluster += n
            return first
        }
        val dirs = HashMap<String, Int>() // path -> first cluster (0 = root)
        val dirFill = HashMap<Int, Int>() // dir cluster -> bytes used
        var serial = 0
        fun addEntry(dirCluster: Int, name: String, attr: Int, cluster: Int, size: Int) {
            val base = if (dirCluster == 0) rootStart else clusterOff(dirCluster)
            var at = base + (dirFill[dirCluster] ?: 0)
            val short = "F%06d".format(serial++).padEnd(8) + "BIN"
            val sum = short.toByteArray().fold(0) { s, ch -> (((s and 1) shl 7) + (s ushr 1) + (ch.toInt() and 0xFF)) and 0xFF }
            val parts = name.chunked(13)
            for ((i, part) in parts.withIndex().reversed()) {
                img[at] = ((i + 1) or if (i == parts.lastIndex) 0x40 else 0).toByte()
                img[at + 11] = 0x0F; img[at + 13] = sum.toByte()
                val chars = part.map { it.code } + (if (part.length < 13) listOf(0) else emptyList())
                val padded = (chars + List(13) { 0xFFFF }).take(13)
                intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30).forEachIndexed { k, pos -> b.putShort(at + pos, padded[k].toShort()) }
                at += 32
            }
            short.toByteArray().copyInto(img, at); img[at + 11] = attr.toByte()
            b.putShort(at + 26, cluster.toShort()); b.putInt(at + 28, size)
            at += 32
            dirFill[dirCluster] = at - base
        }
        fun dir(path: String): Int {
            if (path.isEmpty()) return 0
            dirs[path]?.let { return it }
            val parent = dir(path.substringBeforeLast('/', ""))
            val c = alloc(ByteArray(spc * 512))
            dirs[path] = c
            addEntry(parent, path.substringAfterLast('/'), 0x10, c, 0)
            return c
        }
        for ((path, data) in files) {
            val d = dir(path.substringBeforeLast('/', ""))
            addEntry(d, path.substringAfterLast('/'), 0x20, alloc(data), data.size)
        }
        return img
    }
}
