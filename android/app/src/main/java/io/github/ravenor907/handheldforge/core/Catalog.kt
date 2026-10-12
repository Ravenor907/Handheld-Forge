package io.github.ravenor907.handheldforge.core

/** The shared catalog (handheld_forge/distros.json), the same file the desktop app reads. */
data class ImageSource(
    val label: String,
    val isoUrl: String,
    /** Null when the publisher offers no checksum (SteamOS); bzip2 CRCs and read-back still apply. */
    val checksumUrl: String?,
    val signatureUrl: String?,
    val requireSignature: Boolean,
) {
    val filename: String get() = isoUrl.trimEnd('/').substringAfterLast('/')
}

data class Distro(
    val id: String,
    val name: String,
    val edition: String,
    val enabled: Boolean,
    val minDriveGb: Int,
    /** Path of the public key inside the catalog, e.g. "keys/bazzite-cosign.pub". */
    val publicKey: String?,
    val images: List<ImageSource>,
    /** "iso" (written as-is) or "raw-bz2" (bzip2-compressed disk image, decompressed while writing). */
    val format: String = "iso",
) {
    val minDriveBytes: Long get() = (minDriveGb * 1_000_000_000L * 0.93).toLong()
}

class CatalogException(msg: String) : Exception(msg)

object Catalog {
    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): List<Distro> {
        val root = Json.parse(text) as? Map<String, Any?> ?: throw CatalogException("catalog is not an object")
        if ((root["schema"] as? Long) != 1L) throw CatalogException("unsupported catalog schema")
        val seen = HashSet<String>()
        return (root["distros"] as List<Map<String, Any?>>).map { raw ->
            val id = raw["id"] as String
            if (!seen.add(id)) throw CatalogException("duplicate distro id $id")
            val images = (raw["images"] as? List<Map<String, Any?>> ?: emptyList()).map { img ->
                ImageSource(
                    label = img["label"] as String,
                    isoUrl = img["iso_url"] as String,
                    checksumUrl = img["checksum_url"] as String?,
                    signatureUrl = img["signature_url"] as String?,
                    requireSignature = img["require_signature"] == true,
                )
            }
            for (img in images) {
                for (url in listOfNotNull(img.isoUrl, img.checksumUrl, img.signatureUrl)) {
                    if (!url.startsWith("https://")) throw CatalogException("$id: only https URLs allowed ($url)")
                }
                if (img.requireSignature && img.signatureUrl == null) {
                    throw CatalogException("$id: signature required but no signature_url")
                }
            }
            val enabled = raw["enabled"] == true
            val format = raw["format"] as? String ?: "iso"
            if (format !in setOf("iso", "raw-bz2")) throw CatalogException("$id: unknown format $format")
            if (enabled && images.isEmpty()) throw CatalogException("$id: enabled but has no images")
            Distro(
                id = id,
                name = raw["name"] as String,
                edition = raw["edition"] as? String ?: "",
                enabled = enabled,
                minDriveGb = ((raw["min_drive_gb"] as? Long) ?: 16L).toInt(),
                publicKey = raw["public_key"] as String?,
                images = images,
                format = format,
            )
        }
    }
}
