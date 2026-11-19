package io.github.ravenor907.handheldforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImageStoreTest {
    private val day = ImageStore.DAY_MS
    private var clock = 100 * day
    private val dir: File = Files.createTempDirectory("store").toFile()
    private val catalog = Catalog.parse(repoFile("handheld_forge/distros.json").readText()) +
        Catalog.parse(repoFile("handheld_forge/labs.json").readText())
    private val store = ImageStore(dir, ImageStore.ownerFrom(catalog)) { clock }

    private fun file(name: String, bytes: Int = 1000, age: Long = 0): File =
        File(dir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(clock - age)
        }

    @Test
    fun ownersComeFromTheCatalogWhateverTheVersion() {
        val owner = ImageStore.ownerFrom(catalog)
        assertEquals("bazzite-deck", owner("bazzite-deck-stable-live-amd64.iso"))
        assertEquals("bazzite-deck", owner("bazzite-deck-stable-amd64.iso"))
        assertEquals("steamos", owner("steamdeck-repair-latest.img.bz2"))
        assertEquals("cachyos-handheld", owner("cachyos-handheld-linux-251130.iso"))
        assertEquals("batocera", owner("batocera-zen3-x86-64-v3-42-20250101.img.gz"))
        assertEquals(null, owner("something-else.iso"))
    }

    @Test
    fun writtenImageIsDeletedUnlessKept() {
        val iso = file("steamdeck-repair-latest.img.bz2", 5000)
        val stamp = file("steamdeck-repair-latest.img.bz2.verified", 10)
        store.used("steamos", iso)
        assertEquals(5000L, store.afterWrite("steamos", iso))
        assertFalse(iso.exists())
        assertFalse(stamp.exists())

        val iso2 = file("steamdeck-repair-latest.img.bz2", 5000)
        store.setKeep("steamos", true)
        assertEquals(0L, store.afterWrite("steamos", iso2))
        assertTrue(iso2.exists())
        assertTrue(store.entries().single().keep)
    }

    @Test
    fun newerVersionReplacesTheOld() {
        val old = file("cachyos-handheld-linux-260101.iso", 3000)
        file("cachyos-handheld-linux-260101.iso.verified", 10)
        val other = file("steamdeck-repair-latest.img.bz2", 2000)
        val new = file("cachyos-handheld-linux-260628.iso", 4000)
        store.used("cachyos-handheld", new)
        assertEquals(3000L, store.replaceOlder("cachyos-handheld", new))
        assertFalse(old.exists())
        assertFalse(File(dir, "cachyos-handheld-linux-260101.iso.verified").exists())
        assertTrue(new.exists())
        assertTrue(other.exists())
        assertEquals(1, store.entries().count { it.owner == "cachyos-handheld" })
    }

    @Test
    fun imagesUnusedForTwoWeeksGoUnlessKept() {
        val stale = file("steamdeck-repair-latest.img.bz2", 2000, age = 20 * day)
        val fresh = file("bazzite-deck-stable-live-amd64.iso", 3000, age = 2 * day)
        val keptOld = file("batocera-zen3-x86-64-v3-43.1-20260529.img.gz", 1000, age = 40 * day)
        store.setKeep("batocera", true)
        assertEquals(2000L, store.removeUnused())
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(keptOld.exists())

        // Using an image resets its clock.
        store.used("bazzite-deck", fresh)
        clock += 13 * day
        assertEquals(0L, store.removeUnused())
        clock += 2 * day
        assertEquals(3000L, store.removeUnused())
    }

    @Test
    fun leftoversGoOnStartButARecentPartialDownloadStays() {
        val oldPart = file("bazzite-deck-stable-live-amd64.iso.part", 700, age = 3 * day)
        val oldExpected = file("bazzite-deck-stable-live-amd64.iso.part.expected", 10, age = 3 * day)
        val newPart = file("cachyos-handheld-linux-260628.iso.part", 500, age = 60_000)
        val newExpected = file("cachyos-handheld-linux-260628.iso.part.expected", 10)
        val orphanStamp = file("steamdeck-repair-latest.img.bz2.verified", 10)
        val orphanExpected = file("gone.iso.part.expected", 10)
        val tmp = file("images.txt.tmp", 10, age = 2 * day)
        val image = file("bazzite-deck-stable-amd64.iso", 900)
        val goodStamp = file("bazzite-deck-stable-amd64.iso.verified", 10)
        File(dir, "ventoy").mkdirs()
        val vPart = File(dir, "ventoy/ventoy-1.0-linux.tar.gz.part").apply { writeBytes(ByteArray(50)); setLastModified(clock - 5 * day) }

        store.cleanLeftovers()
        for (f in listOf(oldPart, oldExpected, orphanStamp, orphanExpected, tmp, vPart)) assertFalse(f.name, f.exists())
        assertFalse(File(dir, "ventoy").exists())
        for (f in listOf(newPart, newExpected, image, goodStamp)) assertTrue(f.name, f.exists())
        // Partial downloads and stamps aren't images.
        assertEquals(listOf(image.name), store.entries().map { it.file.name })
    }

    @Test
    fun removeAllEmptiesTheFolderButKeepsChoices() {
        file("bazzite-deck-stable-live-amd64.iso", 3000)
        file("bazzite-deck-stable-live-amd64.iso.verified", 10)
        file("cachyos-handheld-linux-260628.iso.part", 500)
        File(dir, "ventoy").mkdirs()
        File(dir, "ventoy/ventoy-1.0-linux.tar.gz").writeBytes(ByteArray(200))
        store.setKeep("bazzite-deck", true)
        assertEquals(3000L + 200, store.entries().sumOf { it.bytes })
        assertTrue(store.removeAll() >= 3200L)
        assertEquals(emptyList<ImageStore.Entry>(), store.entries())
        assertEquals(listOf(ImageStore.INDEX), dir.list()!!.toList())
        assertTrue(store.isKept("bazzite-deck"))
    }

    @Test
    fun ventoyFolderCountsAsOneImage() {
        val v = File(dir, "ventoy").apply { mkdirs() }
        File(v, "ventoy-1.1-linux.tar.gz").writeBytes(ByteArray(300))
        val e = store.entries().single()
        assertEquals(ImageStore.VENTOY, e.owner)
        assertEquals(300L, e.bytes)
        assertEquals(300L, store.afterWrite(ImageStore.VENTOY, v))
        assertFalse(v.exists())
    }

    @Test
    fun noteSaysWhatWasFreedAndThatItComesBack() {
        assertEquals(
            "Freed 6.2 GB by deleting the downloaded image. It downloads again when needed.",
            ImageStore.freedNote(6_200_000_000, "the downloaded image"),
        )
    }
}
