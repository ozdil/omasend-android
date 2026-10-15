package io.omarchy.omasend

import io.omarchy.omasend.crypto.DeviceKey
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-language compatibility with the desktop engine (src/devicekey.rs::test_vectors_match_android).
 * If any value here changes, desktop and Android can no longer talk to each other.
 */
class ProtocolV2VectorTest {
    private val seed = ByteArray(32) { 7 }

    @Test
    fun identityAndSignatureMatchRustVector() {
        val id = DeviceKey.Identity(seed)
        assertEquals("6kpsY-KcUgq-9VB7Ey7F-ZVHdq6-vnuSQh7qaRRG0iw", id.publicB64)
        assertEquals("FE81-2C12-F3AB-4CE6", id.deviceId)
        val tag = DeviceKey.bodyTagJson("{}".toByteArray())
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", tag)
        val canon = DeviceKey.canonical("POST", "/api/p2p/request", 1_760_000_000, "00112233445566778899aabbccddeeff", tag)
        assertEquals(
            "eFkLAbIm3RpexYvL_-DJ6IlojDwGoLd42QJ72_kyhpn7QA_GBOcYOOLoQPw7kI2GeNO0Bl0PZG60gJZvzI9oAA",
            id.signB64(canon.toByteArray())
        )
        assertEquals(
            "58c09b0af9427c54894465e53a59c22480cb28449242df537932d4c659cd1fb7",
            DeviceKey.pairMac(ByteArray(32) { 9 }, id.publicB64, id.publicB64)
        )
    }

    @Test
    fun pairingRoundTripAndSignedRequest() {
        val phone = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val tablet = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val uri = DeviceKey.createPairOffer(tablet, "Tablet & Co", "192.168.1.30", 53317, ownDevice = true, quicPort = 53318)
        val offer = DeviceKey.parsePairUri(uri).getOrThrow()
        assertEquals("Tablet & Co", offer.name)
        assertTrue(offer.ownDevice)

        val req = DeviceKey.buildPairRequest(phone, offer, "Phone", 40000)
        val (resp, key) = DeviceKey.acceptPairRequest(tablet, "Tablet", req, "192.168.1.20").getOrThrow()
        DeviceKey.verifyPairResponse(phone, offer, resp).getOrThrow()
        assertEquals(phone.publicB64, key.pk)
        assertTrue(key.own_device)
        assertEquals(53318, offer.quicPort)
        assertEquals(40000, key.quic_port)
        // single use
        assertTrue(DeviceKey.acceptPairRequest(tablet, "Tablet", req, "192.168.1.20").isFailure)

        val store = mapOf(key.pk to key)
        val body = """{"files":[]}""".toByteArray()
        val headers = DeviceKey.authHeaders(phone, "POST", "/api/p2p/request", DeviceKey.bodyTagJson(body))
            .mapKeys { it.key.lowercase() }
        val ok = DeviceKey.verifyRequest({ store[it] }, headers, "POST", "/api/p2p/request", DeviceKey.bodyTagJson(body))
        assertTrue(ok is DeviceKey.AuthResult.Trusted)
        val replay = DeviceKey.verifyRequest({ store[it] }, headers, "POST", "/api/p2p/request", DeviceKey.bodyTagJson(body))
        assertEquals(DeviceKey.AuthResult.Rejected("Replayed request"), replay)

        val h2 = DeviceKey.authHeaders(phone, "POST", "/api/p2p/request", DeviceKey.bodyTagJson(body)).mapKeys { it.key.lowercase() }
        val tampered = DeviceKey.verifyRequest({ store[it] }, h2, "POST", "/api/p2p/request", DeviceKey.bodyTagJson("evil".toByteArray()))
        assertEquals(DeviceKey.AuthResult.Rejected("Invalid signature"), tampered)
    }

    @Test
    fun wrongSecretAndUnknownKeysAreNotTrusted() {
        val responder = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val attacker = DeviceKey.Identity(DeviceKey.randomBytes(32))
        val offer = DeviceKey.parsePairUri(DeviceKey.createPairOffer(responder, "R", "10.0.0.2", 53317, false)).getOrThrow()
        val forged = DeviceKey.buildPairRequest(attacker, offer.copy(secret = ByteArray(32)), "Evil")
        assertTrue(DeviceKey.acceptPairRequest(responder, "R", forged, "10.0.0.66").isFailure)

        val headers = DeviceKey.authHeaders(attacker, "POST", "/api/p2p/clipboard", "x").mapKeys { it.key.lowercase() }
        assertEquals(DeviceKey.AuthResult.Absent, DeviceKey.verifyRequest({ null }, headers, "POST", "/api/p2p/clipboard", "x"))
        assertFalse(DeviceKey.isPairUri("https://example.com"))
        assertEquals("omasend-v2", DeviceKey.SIG_DOMAIN)
        assertEquals(responder.publicB64, offer.pk)
        assertTrue(offer.pk == DeviceKey.parsePairUri(DeviceKey.createPairOffer(responder, "R", "10.0.0.2", 53317, false)).getOrThrow().pk)
        // response signature check rejects a different responder key
        val bogusResp = kotlinx.serialization.json.buildJsonObject {
            put("pk", kotlinx.serialization.json.JsonPrimitive(attacker.publicB64))
            put("sig", kotlinx.serialization.json.JsonPrimitive("AA"))
        }
        assertTrue(DeviceKey.verifyPairResponse(attacker, offer, bogusResp).isFailure)
        assertEquals(attacker.publicB64, bogusResp["pk"]!!.jsonPrimitive.content)
    }
}
