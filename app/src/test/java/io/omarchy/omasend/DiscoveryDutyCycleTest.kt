package io.omarchy.omasend

import io.omarchy.omasend.network.DiscoveryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryDutyCycleTest {

    @Test
    fun testBeaconIntervalIsTenSeconds() {
        // Battery: one LAN beacon every 10 s while the screen is on (was 1-6 s).
        assertEquals(10_000L, DiscoveryManager.BEACON_INTERVAL_MS)
    }

    @Test
    fun testPeerExpiryTimeoutAlignedWithBeaconInterval() {
        // A peer survives at least 3 missed beacons.
        assertEquals(35_000L, DiscoveryManager.PEER_EXPIRY_TIMEOUT_MS)
        assertTrue(DiscoveryManager.PEER_EXPIRY_TIMEOUT_MS > 3 * DiscoveryManager.BEACON_INTERVAL_MS)
    }
}
