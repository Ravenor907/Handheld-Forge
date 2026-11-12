package io.github.ravenor907.handheldforge.core

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class VerificationException(msg: String) : Exception(msg)

/**
 * Checksum (catches a corrupted download) and cosign signature (catches a
 * tampered one, checked against the publisher key pinned in the repo).
 */
object Verify {
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    /** Read size for hashing a file: small enough to still be in the CPU cache when it's hashed. */
    internal const val HASH_BUFFER = 256 shl 10
    private const val PROGRESS_EVERY = 4L shl 20

    fun parseChecksum(text: String, filename: String): String {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (ln in lines) {
            val parts = ln.split(Regex("\\s+"))
            if (parts.size >= 2 && HEX64.matches(parts[0])) {
                val name = parts[1].removePrefix("*").substringAfterLast('/')
                if (name == filename) return parts[0].lowercase()
            }
        }
        if (lines.size == 1 && HEX64.matches(lines[0])) return lines[0].lowercase()
        throw VerificationException("No checksum for $filename in the checksum file")
    }

    fun loadPublicKey(pem: String): PublicKey {
        val b64 = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
        val der = try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw VerificationException("Pinned public key is not valid")
        }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    /** Hash and signature check fed piece by piece, for data that's never all in one file at once. */
    class Running internal constructor(private val signatureB64: String?, key: PublicKey?, raw: Boolean) {
        constructor(signatureB64: String?, key: PublicKey?) : this(signatureB64, key, true)

        private val md = MessageDigest.getInstance("SHA-256")

        // The signature is ECDSA over the same SHA-256, so the data is hashed once and the digest
        // handed to a raw ECDSA check. Where that isn't offered, the signature hashes for itself.
        private val rawSig: Signature?
        private val hashingSig: Signature?

        init {
            if (signatureB64 != null && key != null) {
                val direct = if (raw) {
                    try {
                        Signature.getInstance("NONEwithECDSA").apply { initVerify(key) }
                    } catch (e: java.security.GeneralSecurityException) {
                        null
                    }
                } else {
                    null
                }
                rawSig = direct
                hashingSig = if (direct == null) Signature.getInstance("SHA256withECDSA").apply { initVerify(key) } else null
            } else {
                rawSig = null
                hashingSig = null
            }
        }

        fun update(b: ByteArray, off: Int, len: Int) {
            md.update(b, off, len)
            hashingSig?.update(b, off, len)
        }

        /**
         * The SHA-256 hex, and whether a signature was checked. Throws if the signature is wrong.
         * A raw check that doesn't pass isn't taken as final when [recheck] is given: it runs the
         * signature's own hashing over the same data (see [recheckFile]) and that result decides.
         */
        fun finish(recheck: (() -> Pair<String, Boolean>)? = null): Pair<String, Boolean> {
            val digest = md.digest()
            val hex = digest.toHex()
            val sig = rawSig ?: hashingSig ?: return hex to false
            val raw = try {
                Base64.getDecoder().decode(signatureB64!!.trim())
            } catch (e: IllegalArgumentException) {
                throw VerificationException("Signature file is not valid base64")
            }
            var ok = if (rawSig != null) {
                try {
                    rawSig.update(digest)
                    rawSig.verify(raw)
                } catch (e: Exception) {
                    false
                }
            } else {
                try {
                    sig.verify(raw)
                } catch (e: java.security.SignatureException) {
                    false
                }
            }
            if (!ok && rawSig != null && recheck != null) {
                val again = recheck()
                ok = again.second && again.first == hex
            }
            if (!ok) throw VerificationException("Signature does NOT match the pinned key. Refusing this image.")
            return hex to true
        }
    }

    /**
     * Hash the file and, when a signature is given, check it in the same pass
     * (cosign blob signatures are DER ECDSA over SHA-256 of the file).
     * Returns the SHA-256 hex.
     */
    fun hashAndVerify(
        file: File,
        signatureB64: String?,
        key: PublicKey?,
        cancel: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Pair<String, Boolean> {
        val v = Running(signatureB64, key)
        feed(file, v, cancel, progress)
        return v.finish { recheckFile(file, signatureB64, key, cancel, progress) }
    }

    /** Hashes [file] with the signature doing its own hashing (SHA256withECDSA over every byte) and checks it. */
    internal fun recheckFile(
        file: File,
        signatureB64: String?,
        key: PublicKey?,
        cancel: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Pair<String, Boolean> {
        val v = Running(signatureB64, key, raw = false)
        feed(file, v, cancel, progress)
        return v.finish()
    }

    private fun feed(file: File, v: Running, cancel: () -> Boolean, progress: (Long, Long) -> Unit) {
        val total = file.length()
        var done = 0L
        var reported = 0L
        val buf = ByteArray(HASH_BUFFER)
        file.inputStream().use { input ->
            while (true) {
                if (cancel()) throw CancelledException()
                val n = input.read(buf)
                if (n < 0) break
                v.update(buf, 0, n)
                done += n
                if (done - reported >= PROGRESS_EVERY) {
                    progress(done, total)
                    reported = done
                }
            }
        }
        if (done > reported) progress(done, total)
    }
}

class CancelledException : Exception("Cancelled")

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
