package io.omarchy.omasend.engine

import org.bouncycastle.crypto.digests.Blake3Digest
import java.security.MessageDigest

/**
 * Clipboard image content addresses (`image_hash`), as produced by the desktop
 * (`compute_image_clipboard_hash`: lowercase hex BLAKE3-256; `verify_image_clipboard_hash` also
 * accepts SHA-256).
 *
 * The hash arrives from the network and is used as a file name and in URLs, so it must be
 * validated with [normalize] before any such use (A3: `../../files/x` path traversal).
 */
object ImageHash {
    private val HEX64 = Regex("^[a-f0-9]{64}$")

    /** Lowercase 64-char hex hash, or null when [hash] is not one. */
    fun normalize(hash: String?): String? {
        val h = hash?.trim()?.lowercase() ?: return null
        return h.takeIf { HEX64.matches(it) }
    }

    fun isValid(hash: String?): Boolean = normalize(hash) != null

    /** Like [normalize] but throws for an invalid hash; for code that builds paths/URLs. */
    fun require(hash: String?): String =
        normalize(hash) ?: throw IllegalArgumentException("Invalid image hash")

    fun blake3Hex(bytes: ByteArray): String {
        val d = Blake3Digest(256)
        d.update(bytes, 0, bytes.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return toHex(out)
    }

    fun sha256Hex(bytes: ByteArray): String = toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** True when [bytes] are the content [expected] names (BLAKE3, or SHA-256 like the desktop). */
    fun matches(bytes: ByteArray, expected: String?): Boolean {
        val h = normalize(expected) ?: return false
        return blake3Hex(bytes) == h || sha256Hex(bytes) == h
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
