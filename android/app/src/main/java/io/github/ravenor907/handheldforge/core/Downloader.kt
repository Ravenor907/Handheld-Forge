package io.github.ravenor907.handheldforge.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection

enum class Stage { LOOKUP, DOWNLOAD, VERIFY, WRITE, CHECK }

typealias StageFn = (Stage, Long, Long) -> Unit
typealias LogFn = (String) -> Unit

class DownloadException(msg: String) : Exception(msg)

data class PreparedImage(
    val file: File,
    val sha256: String,
    val source: ImageSource,
    val signatureVerified: Boolean,
    val fromCache: Boolean,
)

/**
 * Finds the newest image for a distro, downloads it only if the cache is stale
 * (resuming partial downloads), and verifies checksum and signature.
 */
class Downloader(
    private val cacheDir: File,
    /** Returns the PEM text of a catalog key path such as "keys/bazzite-cosign.pub". */
    private val readKey: (String) -> String,
    private val userAgent: String = "HandheldForge-Android",
    /** Lets tests point the catalog's https URLs at a local http server. */
    private val rewriteUrl: (String) -> String = { it },
) {
    private fun open(url: String, method: String = "GET", range: Long = 0): HttpURLConnection {
        val c = java.net.URI(rewriteUrl(url)).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = 60_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", userAgent)
        if (range > 0) c.setRequestProperty("Range", "bytes=$range-")
        return c
    }

    fun fetchText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw HttpStatus(c.responseCode)
            return c.inputStream.use { readCapped(it, 64 * 1024) }.toString(Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }

    /** Size from HEAD, or null if the file isn't published. */
    fun remoteSize(url: String): Long? = remoteMeta(url)?.first

    /** (size, version tag from ETag/Last-Modified) from HEAD, or null if not published. */
    fun remoteMeta(url: String): Pair<Long, String>? {
        val c = open(url, "HEAD")
        try {
            return when (c.responseCode) {
                in 200..299 -> c.contentLengthLong.coerceAtLeast(0) to
                    (c.getHeaderField("ETag") ?: c.getHeaderField("Last-Modified") ?: "").trim('"', ' ')
                403, 404, 410 -> null
                else -> throw HttpStatus(c.responseCode)
            }
        } finally {
            c.disconnect()
        }
    }

    fun resolve(distro: Distro, log: LogFn): Pair<ImageSource, Long> = resolveMeta(distro, log).let { it.first to it.second.first }

    private fun resolveMeta(distro: Distro, log: LogFn): Pair<ImageSource, Pair<Long, String>> {
        if (!distro.enabled) throw DownloadException("${distro.name} is not available yet")
        for (img in distro.images) {
            try {
                val meta = remoteMeta(img.isoUrl)
                if (meta != null) {
                    log("Using ${img.label}: ${img.filename}")
                    return img to meta
                }
                log("${img.label} not found upstream, trying next")
            } catch (e: IOException) {
                log("Could not reach ${img.isoUrl}: ${e.message}")
            }
        }
        throw DownloadException("Couldn't reach the ${distro.name} download server. Check your connection.")
    }

    fun prepare(distro: Distro, stage: StageFn, log: LogFn, cancel: () -> Boolean): PreparedImage {
        stage(Stage.LOOKUP, 0, 0)
        val (img, meta) = resolveMeta(distro, log)
        val remote = meta.first
        // Without a published checksum, the cache is keyed on the server's size and version tag.
        val checksummed = img.checksumUrl != null
        val expected = if (checksummed) {
            Verify.parseChecksum(fetchText(img.checksumUrl!!), img.filename).also {
                log("Latest ${img.filename} sha256 ${it.take(16)}...")
            }
        } else {
            log("${distro.name} publishes no checksum; the image's own CRCs and a full read-back will be checked")
            "unchecked:$remote:${meta.second}"
        }

        val signature: String? = img.signatureUrl?.let { url ->
            try {
                fetchText(url)
            } catch (e: HttpStatus) {
                if (img.requireSignature) throw VerificationException("Signature required but missing (${e.code})")
                log("No signature published for this image")
                null
            }
        }
        val key = if (signature != null) {
            Verify.loadPublicKey(readKey(distro.publicKey ?: throw VerificationException("No key for ${distro.name}")))
        } else {
            null
        }

        cacheDir.mkdirs()
        val iso = File(cacheDir, img.filename)
        val stamp = File(cacheDir, "${img.filename}.verified")
        val part = File(cacheDir, "${img.filename}.part")
        val partStamp = File(cacheDir, "${img.filename}.part.expected")

        // The stamp records the hash (and whether the signature checked out) at download
        // time. Same hash means same bytes, so that verification still holds.
        val stampLines = if (stamp.exists()) stamp.readText().lines() else emptyList()
        if (iso.exists() && stampLines.firstOrNull()?.trim() == expected) {
            val wasSigned = stampLines.getOrNull(1)?.trim() == "signed"
            if (signature == null || wasSigned) {
                log("Already have the latest release, skipping download")
                return PreparedImage(iso, expected, img, wasSigned, fromCache = true)
            }
        }

        if (part.exists() && (!partStamp.exists() || partStamp.readText().trim() != expected)) part.delete()
        partStamp.writeText(expected)

        val need = remote - (if (part.exists()) part.length() else 0L)
        val free = cacheDir.usableSpace
        if (need > 0 && free < need + 200L * 1024 * 1024) {
            throw DownloadException(
                "Not enough space. Free up %.1f GB on this phone and try again."
                    .format((need + 200L * 1024 * 1024 - free) / 1e9),
            )
        }

        log("Downloading ${img.isoUrl}")
        download(img.isoUrl, part, stage, cancel)

        stage(Stage.VERIFY, 0, 0)
        val (actual, signed) = if (checksummed) {
            Verify.hashAndVerify(part, signature, key, cancel) { d, t -> stage(Stage.VERIFY, d, t) }
        } else {
            (if (part.length() == remote) expected else "size-mismatch") to false
        }
        if (actual != expected) {
            part.delete()
            partStamp.delete()
            throw VerificationException("The download was corrupted, so it was deleted. Try again.")
        }
        log(if (signed) "Checksum and signature verified" else "Checksum verified")
        stamp.delete()
        iso.delete()
        if (!part.renameTo(iso)) throw IOException("Could not finalize the download")
        partStamp.delete()
        stamp.writeText(expected + if (signed) "\nsigned" else "")
        return PreparedImage(iso, expected, img, signed, fromCache = false)
    }

    private fun download(url: String, part: File, stage: StageFn, cancel: () -> Boolean) {
        var attempt = 0
        while (true) {
            try {
                downloadOnce(url, part, stage, cancel)
                return
            } catch (e: IOException) {
                // Phones drop Wi-Fi; resume a few times before giving up.
                if (++attempt >= 5 || cancel()) throw e
                Thread.sleep(2_000L * attempt)
            }
        }
    }

    private fun downloadOnce(url: String, part: File, stage: StageFn, cancel: () -> Boolean) {
        var have = if (part.exists()) part.length() else 0L
        val c = open(url, range = have)
        try {
            val code = c.responseCode
            if (code == 416 && have > 0) return // already complete
            if (code !in 200..299) throw HttpStatus(code)
            if (have > 0 && code != 206) have = 0 // server ignored the range: start over
            val length = c.contentLengthLong
            val total = if (length > 0) have + length else 0L
            FileOutputStream(part, have > 0).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(1 shl 20)
                    var done = have
                    var lastReport = 0L
                    while (true) {
                        if (cancel()) throw CancelledException()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > (4 shl 20) || done == total) {
                            stage(Stage.DOWNLOAD, done, total)
                            lastReport = done
                        }
                    }
                    if (total > 0 && done < total) throw IOException("Connection closed early")
                }
            }
        } finally {
            c.disconnect()
        }
    }
}

class HttpStatus(val code: Int) : IOException("HTTP $code")

private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < cap) {
        val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
