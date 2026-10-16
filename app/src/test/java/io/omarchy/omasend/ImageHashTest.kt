package io.omarchy.omasend

import io.omarchy.omasend.engine.ImageHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHashTest {

    @Test
    fun blake3MatchesReferenceVectors() {
        // Official BLAKE3 test values (same as the desktop's blake3::hash(..).to_hex()).
        assertEquals("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262", ImageHash.blake3Hex(ByteArray(0)))
        assertEquals("6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85", ImageHash.blake3Hex("abc".toByteArray()))
    }

    @Test
    fun traversalAndMalformedHashesAreRejected() {
        assertNull(ImageHash.normalize(null))
        assertNull(ImageHash.normalize(""))
        assertNull(ImageHash.normalize("../../files/device_ed25519.key"))
        assertNull(ImageHash.normalize("../" + "a".repeat(61)))
        assertNull(ImageHash.normalize("a".repeat(63)))
        assertNull(ImageHash.normalize("a".repeat(65)))
        assertNull(ImageHash.normalize("g".repeat(64)))
        assertNull(ImageHash.normalize("a".repeat(32) + "/" + "a".repeat(31)))
        assertFalse(ImageHash.isValid("a".repeat(60) + "%2e%2e"))
    }

    @Test
    fun validHashesAreNormalizedToLowercase() {
        val h = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"
        assertEquals(h.lowercase(), ImageHash.normalize(h))
        assertTrue(ImageHash.isValid(h.lowercase()))
    }

    @Test
    fun bytesAreVerifiedAgainstBlake3OrSha256() {
        val bytes = "png-bytes".toByteArray()
        assertTrue(ImageHash.matches(bytes, ImageHash.blake3Hex(bytes)))
        assertTrue(ImageHash.matches(bytes, ImageHash.blake3Hex(bytes).uppercase()))
        assertTrue(ImageHash.matches(bytes, ImageHash.sha256Hex(bytes)))
        assertFalse(ImageHash.matches("other".toByteArray(), ImageHash.blake3Hex(bytes)))
        assertFalse(ImageHash.matches(bytes, "../etc/passwd"))
    }
}
