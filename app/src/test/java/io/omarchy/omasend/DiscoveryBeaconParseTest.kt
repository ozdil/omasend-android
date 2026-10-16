package io.omarchy.omasend

import io.omarchy.omasend.model.P2pBeaconPacket
import io.omarchy.omasend.network.DiscoveryManager
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Feeds real serialized beacons through the same parse path the UDP listener uses
 * (regression for A1: a byte-0 magic prefix filter dropped every JSON beacon).
 */
class DiscoveryBeaconParseTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private fun datagram(payload: String, padTo: Int = 2048): Pair<ByteArray, Int> {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val buffer = ByteArray(maxOf(padTo, bytes.size))
        System.arraycopy(bytes, 0, buffer, 0, bytes.size)
        return buffer to bytes.size
    }

    @Test
    fun androidSerializedBeaconIsAccepted() {
        val beacon = P2pBeaconPacket(
            magic = "OMASEND_P2P",
            v = 1,
            id = "android-pixel-01",
            name = "Pixel",
            ip = "192.168.1.42",
            port = 53317,
            mode = "ALL",
            oma_id = "4829-1048-5729-1104",
            fp = "4829-1048-5729-1104",
            pk = ""
        )
        val wire = json.encodeToString(P2pBeaconPacket.serializer(), beacon)
        val (buf, len) = datagram(wire)

        val parsed = DiscoveryManager.parseBeacon(buf, len)
        assertNotNull(parsed)
        assertEquals(beacon, parsed)
    }

    @Test
    fun desktopSerdeBeaconIsAccepted() {
        // Byte-for-byte shape of the desktop (serde_json) beacon.
        val wire = """{"magic":"OMASEND_P2P","v":1,"id":"arch-desktop","oma_id":"4829-1048-5729-1104","name":"ThinkPad","ip":"192.168.1.100","port":53317,"mode":"KNOWN","fp":"4829-1048-5729-1104","pk":"","extra_future_field":true}"""
        val (buf, len) = datagram(wire)

        val parsed = DiscoveryManager.parseBeacon(buf, len)
        assertNotNull(parsed)
        assertEquals("arch-desktop", parsed!!.id)
        assertEquals("ThinkPad", parsed.name)
        assertEquals("KNOWN", parsed.mode)
        assertEquals(53317, parsed.port)
    }

    @Test
    fun prettyPrintedBeaconIsAccepted() {
        val wire = "{\n  \"magic\": \"OMASEND_P2P\",\n  \"v\": 1,\n  \"id\": \"x\",\n  \"name\": \"n\",\n  \"ip\": \"10.0.0.2\"\n}"
        val (buf, len) = datagram(wire)
        assertEquals("x", DiscoveryManager.parseBeacon(buf, len)?.id)
    }

    @Test
    fun strayTrafficIsRejected() {
        val (noise, noiseLen) = datagram("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n\r\n")
        assertNull(DiscoveryManager.parseBeacon(noise, noiseLen))

        val (wrongMagic, wrongLen) = datagram("""{"magic":"OTHER","v":1,"id":"a","name":"b","ip":"c","note":"OMASEND_P2P"}""")
        assertNull(DiscoveryManager.parseBeacon(wrongMagic, wrongLen))

        val (broken, brokenLen) = datagram("""{"magic":"OMASEND_P2P","v":1,"id":""")
        assertNull(DiscoveryManager.parseBeacon(broken, brokenLen))

        assertNull(DiscoveryManager.parseBeacon(ByteArray(0), 0))
    }

    @Test
    fun onlyTheReceivedLengthIsParsed() {
        val first = """{"magic":"OMASEND_P2P","v":1,"id":"a-much-longer-device-id","name":"n","ip":"1.2.3.4"}"""
        val second = """{"magic":"OMASEND_P2P","v":1,"id":"b","name":"n","ip":"1.2.3.4"}"""
        val buffer = ByteArray(2048)
        val firstBytes = first.toByteArray(Charsets.UTF_8)
        System.arraycopy(firstBytes, 0, buffer, 0, firstBytes.size)
        val secondBytes = second.toByteArray(Charsets.UTF_8)
        System.arraycopy(secondBytes, 0, buffer, 0, secondBytes.size)

        // Stale bytes from the longer first datagram remain after `second`; they must be ignored.
        assertEquals("b", DiscoveryManager.parseBeacon(buffer, secondBytes.size)?.id)
    }
}
