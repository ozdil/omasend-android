package io.omarchy.omasend

import io.omarchy.omasend.crypto.DeviceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL_V2.md auth table: unknown keys fall back to legacy rules, known keys get 401. */
class DeviceKeyAuthTest {

    private fun headers(id: DeviceKey.Identity, ts: Long, nonce: String, bodyTag: String): Map<String, String> =
        mapOf(
            "x-omasend-pk" to id.publicB64,
            "x-omasend-ts" to ts.toString(),
            "x-omasend-nonce" to nonce,
            "x-omasend-sig" to id.signB64(DeviceKey.canonical("POST", "/api/p2p/request", ts, nonce, bodyTag).toByteArray())
        )

    private fun trusted(id: DeviceKey.Identity) = DeviceKey.TrustedKey(
        pk = id.publicB64,
        device_id = id.deviceId,
        name = "Peer",
        added_at = DeviceKey.nowSecs()
    )

    @Test
    fun unknownKeyWithSkewedClockGetsLegacyRules() {
        val stranger = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val stale = DeviceKey.nowSecs() - 10 * DeviceKey.MAX_CLOCK_SKEW_SECS
        val h = headers(stranger, stale, DeviceKey.hex(DeviceKey.randomBytes(16)), "tag")
        assertEquals(DeviceKey.AuthResult.Absent, DeviceKey.verifyRequest({ null }, h, "POST", "/api/p2p/request", "tag"))
    }

    @Test
    fun unknownKeyWithBadNonceOrPartialHeadersGetsLegacyRules() {
        val stranger = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val badNonce = headers(stranger, DeviceKey.nowSecs(), "not-hex", "tag")
        assertEquals(DeviceKey.AuthResult.Absent, DeviceKey.verifyRequest({ null }, badNonce, "POST", "/api/p2p/request", "tag"))
        val partial = mapOf("x-omasend-pk" to stranger.publicB64)
        assertEquals(DeviceKey.AuthResult.Absent, DeviceKey.verifyRequest({ null }, partial, "POST", "/api/p2p/request", "tag"))
    }

    @Test
    fun knownKeyWithSkewedClockIsRejected() {
        val peer = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val store = mapOf(peer.publicB64 to trusted(peer))
        val stale = DeviceKey.nowSecs() - 10 * DeviceKey.MAX_CLOCK_SKEW_SECS
        val h = headers(peer, stale, DeviceKey.hex(DeviceKey.randomBytes(16)), "tag")
        assertEquals(
            DeviceKey.AuthResult.Rejected("Timestamp outside allowed window"),
            DeviceKey.verifyRequest({ store[it] }, h, "POST", "/api/p2p/request", "tag")
        )
    }

    @Test
    fun knownKeyWithMissingHeadersOrBadNonceIsRejected() {
        val peer = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val store = mapOf(peer.publicB64 to trusted(peer))
        val partial = mapOf("x-omasend-pk" to peer.publicB64)
        assertEquals(
            DeviceKey.AuthResult.Rejected("Incomplete signature headers"),
            DeviceKey.verifyRequest({ store[it] }, partial, "POST", "/api/p2p/request", "tag")
        )
        val badNonce = headers(peer, DeviceKey.nowSecs(), "zz", "tag")
        assertEquals(
            DeviceKey.AuthResult.Rejected("Bad nonce"),
            DeviceKey.verifyRequest({ store[it] }, badNonce, "POST", "/api/p2p/request", "tag")
        )
    }

    @Test
    fun signatureWithoutPkIsRejected() {
        val h = mapOf("x-omasend-sig" to "AA")
        assertTrue(DeviceKey.verifyRequest({ null }, h, "POST", "/api/p2p/request", "tag") is DeviceKey.AuthResult.Rejected)
    }

    @Test
    fun knownKeyWithFreshSignatureIsTrusted() {
        val peer = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val store = mapOf(peer.publicB64 to trusted(peer))
        val h = headers(peer, DeviceKey.nowSecs(), DeviceKey.hex(DeviceKey.randomBytes(16)), "tag")
        assertTrue(DeviceKey.verifyRequest({ store[it] }, h, "POST", "/api/p2p/request", "tag") is DeviceKey.AuthResult.Trusted)
    }
}
