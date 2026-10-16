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
            VentoyImport.plan(it)
        }
        assertEquals(listOf("first.iso", "os.img", "second.iso"), plans.map { it.targetName })
        assertEquals(-1L, plans[1].targetSize) // learned while unpacking onto the drive, not before
        assertEquals(ImageKind.DISK, plans[1].content)
        assertEquals(ImageKind.ISO_HYBRID, plans[2].content)
        assertTrue(plans.all { it.bootable })
        val stages = ArrayList<Stage>()
        val writes = ArrayList<Pair<Long, Long>>()
        VentoyImport.run(v, plans, { s, d, t -> stages.add(s); if (s == Stage.WRITE) writes.add(d to t) }, {}, { false })
        // Write progress never goes backwards and ends at 100%.
        assertTrue(writes.zipWithNext().all { (a, b) -> b.first >= a.first })
        assertEquals(writes.last().second, writes.last().first)
        assertEquals(disk.size.toLong(), v.find("os.img")!!.size)
        assertEquals(a.size.toLong(), v.find("second.iso")!!.size)
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
        VentoyImport.run(v, listOf(VentoyImport.plan(src("x.iso", one))), { _, _, _ -> }, {}, { false })
        VentoyImport.run(v, listOf(VentoyImport.plan(src("X.ISO", two))), { _, _, _ -> }, {}, { false })
        assertEquals(1, v.files().size)
        assertEquals(200_000L, v.files()[0].size)
    }

    @Test
    fun duplicateNamesInOneBatchGetNumbered() {
        val (_, v) = volume(128)
        val p = VentoyImport.plan(src("same.iso", iso(50_000)))
        VentoyImport.run(v, listOf(p, p), { _, _, _ -> }, {}, { false })
        assertEquals(setOf("same.iso", "same (2).iso"), v.files().map { it.name }.toSet())
    }

    @Test
    fun damagedArchiveIsReportedBeforeAnythingIsWritten() {
        val junk = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0) + ByteArray(4000) { 9 }
        try {
            VentoyImport.plan(src("broken.img.xz", junk))
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

class VentoyConfigTest {
    @Test
    fun setsAndClearsBootNextOnAFreshDrive() {
        val (d, v) = volume()
        v.add("forge-test.iso", 4096, ByteArrayInputStream(ByteArray(4096)), { _, _ -> }, { false })
        assertNull(VentoyConfig.bootNext(v))
        VentoyConfig.setBootNext(v, "forge-test.iso")
        // Read back from a fresh open: it's really on the disk, in /ventoy/ventoy.json.
        val v2 = ExFatVolume.open(d, 2048)
        assertEquals("forge-test.iso", VentoyConfig.bootNext(v2))
        assertTrue(v2.lookup("ventoy")!!.isDir)
        val json = v2.readFile(VentoyConfig.PATH)!!.toString(Charsets.UTF_8)
        assertTrue(json, json.contains("\"VTOY_DEFAULT_IMAGE\": \"/forge-test.iso\""))
        assertTrue(json.contains("\"VTOY_MENU_TIMEOUT\": \"5\""))
        assertTrue(json.contains("\"VTOY_SECONDARY_BOOT_MENU\": \"0\""))
        // The folder doesn't show up as an image.
        assertEquals(listOf("forge-test.iso"), v2.files().map { it.name })
        // Change it, then clear it: the file goes away when nothing else is in it.
        VentoyConfig.setBootNext(v2, "other.iso")
        assertEquals("other.iso", VentoyConfig.bootNext(v2))
        val free = v2.freeClusters()
        VentoyConfig.setBootNext(v2, null)
        assertNull(VentoyConfig.bootNext(v2))
        assertNull(v2.lookup(VentoyConfig.PATH))
        assertEquals(free + 1, v2.freeClusters())
    }

    @Test
    fun keepsEverythingElseInAnExistingVentoyJson() {
        val existing = """
            {
                "theme": { "file": "/ventoy/theme/blur/theme.txt", "gfxmode": "1920x1080" },
                "control": [
                    { "VTOY_DEFAULT_MENU_MODE": "1" },
                    { "VTOY_MENU_TIMEOUT": "30" },
                    { "VTOY_FILT_DOT_UNDERSCORE_FILE": "1" }
                ],
                "menu_alias": [ { "image": "/a \"b\".iso", "alias": "Ünïcode ✓", "n": 1.50e3 } ]
            }
        """.trimIndent()
        val set = VentoyConfig.withBootNext(existing, "Bazzite Deck.iso")!!
        assertEquals("Bazzite Deck.iso", VentoyConfig.bootNext(set))
        val parsed = JsonText.parse(set) as Map<*, *>
        assertEquals(JsonText.parse(existing).let { (it as Map<*, *>)["theme"] }, parsed["theme"])
        assertEquals((JsonText.parse(existing) as Map<*, *>)["menu_alias"], parsed["menu_alias"])
        assertTrue(set.contains("1.50e3")) // numbers kept as written
        val control = parsed["control"] as List<*>
        assertTrue(control.contains(mapOf("VTOY_DEFAULT_MENU_MODE" to "1")))
        assertTrue(control.contains(mapOf("VTOY_FILT_DOT_UNDERSCORE_FILE" to "1")))
        assertTrue(control.contains(mapOf("VTOY_MENU_TIMEOUT" to "5")))
        assertFalse(control.contains(mapOf("VTOY_MENU_TIMEOUT" to "30")))
        // Clearing keeps the person's other settings and the file.
        val cleared = VentoyConfig.withBootNext(set, null)!!
        assertNull(VentoyConfig.bootNext(cleared))
        val c2 = (JsonText.parse(cleared) as Map<*, *>)["control"] as List<*>
        assertEquals(listOf(mapOf("VTOY_DEFAULT_MENU_MODE" to "1"), mapOf("VTOY_FILT_DOT_UNDERSCORE_FILE" to "1")), c2)
        // Our own output parses again identically (stable round trip).
        assertEquals(set, JsonText.write(JsonText.parse(set)) + "\n")
    }

    @Test
    fun refusesToOverwriteABrokenVentoyJson() {
        try {
            VentoyConfig.withBootNext("{ \"control\": [ { \"VTOY_MENU_TIMEOUT\": \"5\" } ", "a.iso")
            fail("should refuse")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!, e.message!!.contains("Fix or remove it"))
        }
        assertEquals("a.iso", VentoyConfig.bootNext(VentoyConfig.withBootNext("﻿{}", "a.iso")))
    }

    @Test
    fun writeFileReplacesInAFolderAndRefusesAFileAsFolder() {
        val (_, v) = volume()
        val folder = v.createFolder("ventoy")
        v.writeFile("ventoy/notes.txt", "hello".toByteArray())
        v.writeFile("VENTOY/NOTES.TXT", "hello again".toByteArray()) // replaces, ignoring case
        assertEquals("hello again", v.readFile("ventoy/notes.txt")!!.toString(Charsets.UTF_8))
        assertEquals(1, v.entries(folder).size)
        v.add("forge.iso", 1, ByteArrayInputStream(ByteArray(1)), { _, _ -> }, { false })
        try {
            v.writeFile("forge.iso/x", ByteArray(1))
            fail("a file isn't a folder")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!, e.message!!.contains("is a file"))
        }
    }
}

class FragmentationTest {
    @Test
    fun refusesUpFrontWhenFreeSpaceIsInPieces() {
        val d = FileDisk(Files.createTempFile("frag", ".img").toFile().apply { deleteOnExit() }, 64L * 2048)
        ExFat.format(d, 2048, d.blockCount - 2048, "Ventoy", 64) // 32 KB clusters
        val v = ExFatVolume.open(d, 2048)
        val chunk = 4L * 1024 * 1024
        // Fill with 4 MB files, then delete every other one: lots of free space, all in 4 MB pieces.
        var i = 0
        while (v.largestFreeRun() >= chunk) {
            v.add("f$i.iso", chunk, ByteArrayInputStream(ByteArray(chunk.toInt())), { _, _ -> }, { false }); i++
        }
        v.files().filter { it.name.removePrefix("f").removeSuffix(".iso").toInt() % 2 == 0 }.forEach { v.delete(it) }
        assertTrue(v.freeBytes > 3 * chunk)
        assertEquals(null, v.firstMisfit(listOf(chunk, chunk)))
        assertEquals(chunk * 2, v.firstMisfit(listOf(chunk, chunk * 2)))
        val big = ByteArray((chunk * 2).toInt())
        val plan = VentoyImport.Plan(VentoyImport.Source("big.iso", big.size.toLong()) { ByteArrayInputStream(big) }, ImageKind.DISK, ImageKind.DISK, "big.img", big.size.toLong())
        val before = v.files().map { it.name }
        try {
            VentoyImport.run(v, listOf(plan), { _, _, _ -> }, {}, { false })
            fail("should refuse")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!, e.message!!.contains("not in one piece"))
        }
        assertEquals(before, v.files().map { it.name }) // nothing written
    }
}

class OnePassTest {
    private fun src(name: String, bytes: ByteArray) = VentoyImport.Source(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    private fun bz2(b: ByteArray) = ByteArrayOutputStream().also { o ->
        org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(o, 1).use { it.write(b) }
    }.toByteArray()

    @Test
    fun compressedImageIsReadOnceAndLandsIntact() {
        val (_, v) = volume(128)
        val disk = Random.nextBytes(5 * 1024 * 1024).also { it[510] = 0x55; it[511] = 0xAA.toByte() }
        val packed = bz2(disk)
        var opens = 0
        var bytesRead = 0L
        val s = VentoyImport.Source("steamdeck-repair.img.bz2", packed.size.toLong()) {
            opens++
            object : java.io.FilterInputStream(ByteArrayInputStream(packed)) {
                override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also { if (it > 0) bytesRead += it }
            }
        }
        val plan = VentoyImport.plan(s)
        val afterPlan = bytesRead
        assertTrue("plan should only look at the start, read $afterPlan of ${packed.size}", afterPlan < packed.size)
        VentoyImport.run(v, listOf(plan), { _, _, _ -> }, {}, { false })
        // The whole archive is read exactly once for the copy (plus the small look at its start).
        assertEquals(packed.size.toLong(), bytesRead - afterPlan)
        val f = v.find("steamdeck-repair.img")!!
        assertEquals(disk.size.toLong(), f.size)
        assertTrue(f.contiguous)
        v.verify(f, java.security.MessageDigest.getInstance("SHA-256").digest(disk), { _, _ -> }, { false })
        // Only the clusters it needs are claimed.
        val (_, fresh) = volume(128)
        assertEquals(fresh.freeClusters() - (disk.size + v.clusterBytes - 1) / v.clusterBytes, v.freeClusters())
    }

    @Test
    fun damageHalfwayLeavesNothingOnTheDrive() {
        val (_, v) = volume(128)
        val disk = Random.nextBytes(3 * 1024 * 1024).also { it[510] = 0x55; it[511] = 0xAA.toByte() }
        val packed = bz2(disk)
        packed[packed.size / 2] = (packed[packed.size / 2].toInt() xor 0x55).toByte()
        val free = v.freeClusters()
        val plan = VentoyImport.plan(src("os.img.bz2", packed))
        try {
            VentoyImport.run(v, listOf(plan), { _, _, _ -> }, {}, { false })
            fail("should report damage")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!, e.message!!.contains("damaged"))
        }
        assertTrue(v.files().isEmpty())
        assertEquals(free, v.freeClusters())
    }

    @Test
    fun tooBigOnceUnpackedIsRefusedCleanly() {
        val (_, v) = volume(16) // about 15 MB of space
        val disk = ByteArray(40 * 1024 * 1024).also { it[510] = 0x55; it[511] = 0xAA.toByte() } // compresses to almost nothing
        val packed = bz2(disk)
        val free = v.freeClusters()
        try {
            VentoyImport.run(v, listOf(VentoyImport.plan(src("huge.img.bz2", packed))), { _, _, _ -> }, {}, { false })
            fail("should refuse")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!, e.message!!.contains("in one piece"))
        }
        assertTrue(v.files().isEmpty())
        assertEquals(free, v.freeClusters())
    }

    @Test
    fun cancelHalfwayLeavesNothingOnTheDrive() {
        val (_, v) = volume(64)
        val disk = Random.nextBytes(8 * 1024 * 1024).also { it[510] = 0x55; it[511] = 0xAA.toByte() }
        val free = v.freeClusters()
        var calls = 0
        try {
            VentoyImport.run(v, listOf(VentoyImport.plan(src("os.img.bz2", bz2(disk)))), { _, _, _ -> }, {}, { ++calls > 3 })
            fail("should stop")
        } catch (_: CancelledException) {
        }
        assertTrue(v.files().isEmpty())
        assertEquals(free, v.freeClusters())
    }

    @Test
    fun prefetchGivesTheSameBytesAndPassesErrorsOn() {
        val data = Random.nextBytes(10 * 1024 * 1024 + 17)
        val out = PrefetchInputStream(ByteArrayInputStream(data), chunk = 1 shl 20, depth = 2).use { it.readBytes() }
        assertArrayEquals(data, out)
        val failing = object : java.io.InputStream() {
            var n = 0
            override fun read(): Int = if (n++ < 3_000_000) 1 else throw java.io.IOException("boom")
        }
        try {
            PrefetchInputStream(failing, chunk = 1 shl 20).use { it.readBytes() }
            fail("should pass the error on")
        } catch (e: java.io.IOException) {
            assertEquals("boom", e.message)
        }
    }
}
