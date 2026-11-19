package io.github.ravenor907.handheldforge.core

import java.io.File

/**
 * Keeps downloaded images from sitting unused on the phone. An image is deleted once a drive has
 * been made from it (unless it's kept), when it hasn't been used for [UNUSED_DAYS] days, and when
 * a newer one of the same system has been downloaded and verified. Leftovers (old partial
 * downloads, `.verified` stamps without their image, temp files) go on start.
 *
 * Each image belongs to an owner: the system's id (several builds of one system share it), or
 * "file:<name>" for a file no system in the catalog claims. "Keep" is set per owner, so it holds
 * across new versions.
 */
class ImageStore(
    val dir: File,
    /** The owner of a file name, or null when no system claims it; see [ownerFrom]. */
    private val ownerOf: (String) -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Entry(val file: File, val owner: String, val bytes: Long, val lastUsed: Long, val keep: Boolean)

    private val index = File(dir, INDEX)

    private class Index(val used: MutableMap<String, Pair<String, Long>>, val keep: MutableSet<String>)

    private fun read(): Index {
        val used = HashMap<String, Pair<String, Long>>()
        val keep = HashSet<String>()
        if (index.exists()) {
            for (line in index.readLines()) {
                val f = line.split('\t')
                when {
                    f.size == 4 && f[0] == "use" -> f[3].toLongOrNull()?.let { used[f[1]] = f[2] to it }
                    f.size == 2 && f[0] == "keep" -> keep += f[1]
                }
            }
        }
        return Index(used, keep)
    }

    private fun write(ix: Index) {
        dir.mkdirs()
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(
            buildString {
                ix.used.toSortedMap().forEach { (name, v) -> append("use\t$name\t${v.first}\t${v.second}\n") }
                ix.keep.sorted().forEach { append("keep\t$it\n") }
            },
        )
        if (!tmp.renameTo(index)) {
            index.delete()
            tmp.renameTo(index)
        }
    }

    private fun isImage(f: File) = when {
        f.isDirectory -> f.name == VENTOY
        !f.isFile -> false
        f.name == INDEX -> false
        else -> LEFTOVER_SUFFIXES.none { f.name.endsWith(it) }
    }

    private fun ownerFor(name: String, ix: Index) = ix.used[name]?.first ?: if (name == VENTOY) VENTOY else ownerOf(name) ?: "file:$name"

    /** Every image on the phone, newest use first. A file seen for the first time counts as used when it was saved. */
    fun entries(): List<Entry> = synchronized(LOCK) {
        val ix = read()
        (dir.listFiles() ?: emptyArray()).filter(::isImage).map { f ->
            val owner = ownerFor(f.name, ix)
            Entry(f, owner, sizeOf(f), ix.used[f.name]?.second ?: newest(f), owner in ix.keep)
        }.sortedByDescending { it.lastUsed }
    }

    fun totalBytes(): Long = entries().sumOf { it.bytes }

    /** [file] (an image in [dir]) belongs to [owner] and was just used. */
    fun used(owner: String, file: File) = synchronized(LOCK) {
        val ix = read()
        ix.used[file.name] = owner to now()
        write(ix)
    }

    fun isKept(owner: String): Boolean = synchronized(LOCK) { owner in read().keep }

    /** Owners set to keep their images. */
    fun kept(): Set<String> = synchronized(LOCK) { read().keep.toSet() }

    fun setKeep(owner: String, on: Boolean) = synchronized(LOCK) {
        val ix = read()
        if (on) ix.keep += owner else ix.keep -= owner
        write(ix)
    }

    /** A newer [current] of [owner] is downloaded and verified: every other image of it goes. Returns bytes freed. */
    fun replaceOlder(owner: String, current: File): Long = synchronized(LOCK) {
        entries().filter { it.owner == owner && it.file.name != current.name }.sumOf { delete(it.file) }
    }

    /** A drive was made from [file]: delete it unless [owner] is kept. Returns bytes freed. */
    fun afterWrite(owner: String, file: File): Long = synchronized(LOCK) {
        if (isKept(owner) || !file.exists()) 0L else delete(file)
    }

    /** Deletes images not used for [UNUSED_DAYS] days, except kept ones. Returns bytes freed. */
    fun removeUnused(): Long = synchronized(LOCK) {
        val cutoff = now() - UNUSED_DAYS * DAY_MS
        entries().filter { !it.keep && it.lastUsed < cutoff }.sumOf { delete(it.file) }
    }

    /**
     * Deletes what nothing will use: partial downloads untouched for [PART_STALE_MS] (a recent one
     * is kept so a paused download picks up where it left off), `.verified` and `.part.expected`
     * stamps whose file is gone, temp files, and index lines for images that are gone.
     * Only call it while no job is running. Returns bytes freed.
     */
    fun cleanLeftovers(): Long = synchronized(LOCK) {
        var freed = 0L
        val stale = now() - PART_STALE_MS
        fun sweep(d: File) {
            for (f in d.listFiles() ?: emptyArray()) {
                if (!f.isFile) continue
                val n = f.name
                val gone = when {
                    n.endsWith(".part") -> f.lastModified() < stale
                    n.endsWith(".part.expected") -> !File(d, n.removeSuffix(".expected")).exists() ||
                        File(d, n.removeSuffix(".expected")).lastModified() < stale
                    n.endsWith(".verified") -> !File(d, n.removeSuffix(".verified")).exists()
                    n.endsWith(".tmp") -> f.lastModified() < now() - TMP_STALE_MS
                    else -> false
                }
                if (gone) {
                    freed += f.length()
                    f.delete()
                }
            }
        }
        sweep(dir)
        File(dir, VENTOY).takeIf { it.isDirectory }?.let { v ->
            sweep(v)
            if (v.list().isNullOrEmpty()) v.delete()
        }
        val ix = read()
        if (ix.used.keys.removeAll { !File(dir, it).exists() }) write(ix)
        freed
    }

    /** Deletes every downloaded image and partial download. "Keep" choices stay. Returns bytes freed. */
    fun removeAll(): Long = synchronized(LOCK) {
        var freed = entries().sumOf { delete(it.file) }
        for (f in dir.listFiles() ?: emptyArray()) {
            if (f.isFile && f.name != INDEX) {
                freed += f.length()
                f.delete()
            }
        }
        freed
    }

    /** Deletes an image with its stamps and partial download. Returns bytes freed. */
    private fun delete(f: File): Long {
        val bytes = sizeOf(f)
        if (f.isDirectory) f.deleteRecursively() else f.delete()
        for (s in LEFTOVER_SUFFIXES) File(dir, f.name + s).delete()
        val ix = read()
        if (ix.used.remove(f.name) != null) write(ix)
        return bytes
    }

    companion object {
        const val UNUSED_DAYS = 14L
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val PART_STALE_MS = DAY_MS
        const val TMP_STALE_MS = 60L * 60 * 1000
        const val INDEX = "images.txt"

        /** Ventoy's download folder inside [dir], handled as one image. */
        const val VENTOY = "ventoy"
        private val LEFTOVER_SUFFIXES = listOf(".verified", ".part", ".part.expected", ".tmp")
        private val LOCK = Any()

        private fun sizeOf(f: File): Long = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()
        private fun newest(f: File): Long = if (f.isDirectory) f.walkBottomUp().maxOf { it.lastModified() } else f.lastModified()

        /** A file name with its version numbers blanked, so two builds of one system look alike. */
        fun shape(name: String) = name.replace(Regex("\\d+(\\.\\d+)*"), "#")

        /** Which system a file belongs to: the one whose catalog file name has the same shape. */
        fun ownerFrom(distros: List<Distro>): (String) -> String? {
            val byShape = HashMap<String, String>()
            for (d in distros) for (img in d.images) byShape.putIfAbsent(shape(img.filename), d.id)
            return { name -> byShape[shape(name)] }
        }

        /** The one-line note shown after a clean-up, e.g. "Freed 6.2 GB: ...". */
        fun freedNote(bytes: Long, why: String): String =
            "Freed ${size(bytes)} by deleting $why. It downloads again when needed."

        fun size(bytes: Long): String = when {
            bytes >= 10e9 -> "%.0f GB".format(bytes / 1e9)
            bytes >= 1e9 -> "%.1f GB".format(bytes / 1e9)
            else -> "%.0f MB".format(maxOf(1.0, bytes / 1e6))
        }
    }
}
