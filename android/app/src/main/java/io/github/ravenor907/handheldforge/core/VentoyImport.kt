package io.github.ravenor907.handheldforge.core

import java.io.IOException
import java.io.InputStream

/**
 * Copies OS images from the phone onto a Ventoy drive's image partition, so they show up in
 * Ventoy's boot menu. Compressed images (.xz, .gz, .bz2, .zip) are unpacked on the way in,
 * because Ventoy boots files straight off the disk and can't unpack them itself.
 */
object VentoyImport {
    /** What Ventoy lists in its menu (by extension). */
    val BOOTABLE = setOf("iso", "img", "wim", "vhd", "vhdx", "efi", "vtoy")
    private val PACKED = setOf("xz", "gz", "bz2", "zip")

    /** A file picked on the phone. [open] gives a fresh stream from the start each time. */
    class Source(val name: String, val size: Long, val open: () -> InputStream)

    /** What will land on the drive. */
    data class Plan(
        val source: Source,
        val kind: ImageKind,
        /** For compressed files, what's inside once unpacked; otherwise the same as [kind]. */
        val content: ImageKind,
        val targetName: String,
        val targetSize: Long,
    ) {
        val unpacks get() = kind.compressed
        val bootable get() = targetName.substringAfterLast('.', "").lowercase() in BOOTABLE
    }

    /** Name for the drive: compression suffix dropped, and an extension Ventoy recognizes for what it is. */
    fun targetName(name: String, kind: ImageKind, content: ImageKind): String {
        var n = name
        if (kind.compressed) {
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext in PACKED) n = n.substringBeforeLast('.')
        }
        val ext = n.substringAfterLast('.', "").lowercase()
        n = when (content) {
            ImageKind.ISO_HYBRID, ImageKind.ISO_DVD_ONLY -> if (ext == "iso") n else "$n.iso"
            ImageKind.DISK -> if (ext in setOf("img", "vhd", "vhdx", "vtoy")) n else (if (ext == "raw") n.substringBeforeLast('.') else n) + ".img"
            else -> n
        }
        return ExFatVolume.cleanName(n)
    }

    /**
     * Works out what each file is and how big it is once on the drive. A compressed file is read
     * through once to learn its unpacked size (needed to give it one unbroken run on the drive).
     */
    fun plan(src: Source, measured: (Long, Long) -> Unit, cancel: () -> Boolean): Plan {
        val head = src.open().use { readHead(it) }
        val kind = ImageKind.sniff(head)
        if (!kind.writable) throw IOException("${src.name} is a ${kind.label} file, which can't be unpacked here. Unpack it first.")
        if (!kind.compressed) return Plan(src, kind, kind, targetName(src.name, kind, kind), src.size)
        var total = 0L
        var inner = ByteArray(0)
        val buf = ByteArray(1 shl 20)
        try {
            src.open().use { raw ->
                val counting = Counting(raw)
                ImageKind.decoder(kind)(counting.buffered(1 shl 16)).use { d ->
                    while (true) {
                        if (cancel()) throw CancelledException()
                        val n = d.read(buf)
                        if (n < 0) break
                        if (inner.size < ImageKind.SNIFF_BYTES) inner += buf.copyOf(minOf(n, ImageKind.SNIFF_BYTES - inner.size))
                        total += n
                        measured(counting.count, src.size)
                    }
                }
            }
        } catch (e: CancelledException) {
            throw e
        } catch (e: IOException) {
            throw IOException("${src.name} is damaged or not really a ${kind.label} (${e.message}). Download it again.")
        } catch (e: RuntimeException) {
            throw IOException("${src.name} is damaged or not really a ${kind.label}. Download it again.")
        }
        val content = ImageKind.sniff(inner)
        return Plan(src, kind, content, targetName(src.name, kind, content), total)
    }

    /** Opens the stream that produces exactly the bytes that go on the drive. */
    fun content(p: Plan): InputStream {
        val raw = p.source.open()
        return if (p.unpacks) ImageKind.decoder(p.kind)(raw.buffered(1 shl 16)) else raw
    }

    /**
     * Copies every planned file (replacing files of the same name), then reads each back from the
     * drive and checks it. Progress: Stage.WRITE over all bytes written, then Stage.CHECK over all
     * bytes read back. Returns the names added.
     */
    fun run(vol: ExFatVolume, picked: List<Plan>, stage: StageFn, log: LogFn, cancel: () -> Boolean): List<String> {
        val plans = unique(picked)
        val total = plans.sumOf { it.targetSize }
        val needClusters = plans.sumOf { maxOf(1L, (it.targetSize + vol.clusterBytes - 1) / vol.clusterBytes) }
        val replacing = plans.mapNotNull { vol.find(it.targetName) }
        val freeAfterDeletes = vol.freeClusters() + replacing.sumOf { (it.size + vol.clusterBytes - 1) / vol.clusterBytes }
        if (needClusters > freeAfterDeletes) {
            throw IOException(
                "Not enough space: %.1f GB to add, %.1f GB free on the drive.".format(total / 1e9, freeAfterDeletes * vol.clusterBytes / 1e9),
            )
        }
        vol.firstMisfit(plans.map { it.targetSize }, replacing)?.let { size ->
            val p = plans.first { it.targetSize == size }
            throw IOException(
                "There's enough free space in total, but not in one piece for ${p.targetName} (%.1f GB). ".format(size / 1e9) +
                    "Ventoy needs each image in one piece. Remove an image from the drive, or copy everything off, reinstall Ventoy and add them again.",
            )
        }
        for (old in replacing) {
            log("Replacing ${old.name} already on the drive")
            vol.delete(old)
        }
        val hashes = ArrayList<ByteArray>()
        var done = 0L
        stage(Stage.WRITE, 0, total)
        for (p in plans) {
            log(if (p.unpacks) "Unpacking ${p.source.name} onto the drive as ${p.targetName}" else "Copying ${p.targetName}")
            val before = done
            val h = try {
                content(p).use { input ->
                    vol.add(p.targetName, p.targetSize, input, { d, _ -> stage(Stage.WRITE, before + d, total) }, cancel)
                }
            } catch (e: CancelledException) {
                throw e
            } catch (e: WriteException) {
                throw e
            } catch (e: IOException) {
                if (e is ScsiException || e.message?.contains("USB") == true) throw e
                throw IOException("Couldn't copy ${p.targetName}: ${e.message}", e)
            }
            hashes.add(h)
            done += p.targetSize
        }
        var checked = 0L
        stage(Stage.CHECK, 0, total)
        for ((i, p) in plans.withIndex()) {
            log("Reading ${p.targetName} back from the drive to confirm")
            val f = vol.find(p.targetName) ?: throw WriteException("${p.targetName} is missing from the drive after copying.")
            val before = checked
            vol.verify(f, hashes[i], { d, _ -> stage(Stage.CHECK, before + d, total) }, cancel)
            checked += p.targetSize
        }
        stage(Stage.CHECK, total, total)
        return plans.map { it.targetName }
    }

    /** Two picked files that would get the same name: the later ones get " (2)", " (3)"... */
    fun unique(plans: List<Plan>): List<Plan> {
        val seen = HashSet<String>()
        return plans.map { p ->
            var name = p.targetName
            var k = 2
            while (!seen.add(name.lowercase())) {
                val ext = p.targetName.substringAfterLast('.', "")
                val base = if (ext.isEmpty()) p.targetName else p.targetName.substringBeforeLast('.')
                name = if (ext.isEmpty()) "$base ($k)" else "$base ($k).$ext"
                k++
            }
            if (name == p.targetName) p else p.copy(targetName = name)
        }
    }

    private fun readHead(input: InputStream): ByteArray {
        val head = ByteArray(ImageKind.SNIFF_BYTES)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        return head.copyOf(n)
    }

    private class Counting(inner: InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
