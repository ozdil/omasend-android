package io.omarchy.omasend

import io.omarchy.omasend.network.NetworkUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkUtilsLocalRangesTest {

    @Test
    fun carrierGradeNatSpaceIsNotLocal() {
        // RFC 6598 (100.64.0.0/10) is shared with other carrier subscribers.
        assertFalse(NetworkUtils.isPrivateOrLocalIp("100.64.0.1"))
        assertFalse(NetworkUtils.isPrivateOrLocalIp("100.127.255.254"))
        assertFalse(NetworkUtils.isPrivateOrLocalIp("100.100.1.2:53317"))
        assertFalse(NetworkUtils.isPrivateOrLocalIp("100.63.255.255"))
        assertFalse(NetworkUtils.isPrivateOrLocalIp("100.128.0.1"))
    }

    @Test
    fun ipv6LinkLocalAndUniqueLocalAreLocal() {
        assertTrue(NetworkUtils.isPrivateOrLocalIp("fe80::1"))
        assertTrue(NetworkUtils.isPrivateOrLocalIp("fe80::1c2b:3cff:fe4d:5e6f%wlan0"))
        assertTrue(NetworkUtils.isPrivateOrLocalIp("FEBF::1"))
        assertTrue(NetworkUtils.isPrivateOrLocalIp("[fe80::1]:53317"))
        assertTrue(NetworkUtils.isPrivateOrLocalIp("fd12:3456:789a::1"))
        assertTrue(NetworkUtils.isPrivateOrLocalIp("fc00::1"))
        assertTrue(NetworkUtils.isPrivateOrLocalIp("::ffff:192.168.1.5"))
        assertTrue(NetworkUtils.isPrivateOrLocalIp("0:0:0:0:0:0:0:1"))
    }

    @Test
    fun publicIpv6IsNotLocal() {
        assertFalse(NetworkUtils.isPrivateOrLocalIp("2001:4860:4860::8888"))
        assertFalse(NetworkUtils.isPrivateOrLocalIp("fec0::1")) // deprecated site-local, not fe80::/10
        assertFalse(NetworkUtils.isPrivateOrLocalIp("fe00::1"))
        assertFalse(NetworkUtils.isPrivateOrLocalIp("::ffff:8.8.8.8"))
        assertFalse(NetworkUtils.isPrivateOrLocalIp("::"))
        assertFalse(NetworkUtils.isPrivateOrLocalIp("zz::1"))
    }

    @Test
    fun inetAddressGate() {
        fun local(s: String) = NetworkUtils.isPrivateOrLocalAddress(java.net.InetAddress.getByName(s))
        assertTrue(local("127.0.0.1"))
        assertTrue(local("::1"))
        assertTrue(local("192.168.1.5"))
        assertTrue(local("fe80::1"))
        assertTrue(local("fd00::1"))
        assertFalse(local("0.0.0.0"))
        assertFalse(local("::"))
        assertFalse(local("100.64.0.1"))
        assertFalse(local("fec0::1"))
        assertFalse(local("2001:db8::1"))
        assertFalse(local("8.8.8.8"))
        assertFalse(local("224.0.0.251"))
    }

    @Test
    fun lanInterfacesRankBeforeCellular() {
        assertEquals(0, NetworkUtils.interfaceRank("wlan0"))
        assertEquals(0, NetworkUtils.interfaceRank("eth0"))
        assertEquals(0, NetworkUtils.interfaceRank("ap0"))
        assertEquals(2, NetworkUtils.interfaceRank("rmnet_data0"))
        assertEquals(2, NetworkUtils.interfaceRank("ccmni1"))
        assertEquals(2, NetworkUtils.interfaceRank("v4-rmnet_data0"))
        assertEquals(1, NetworkUtils.interfaceRank("bt-pan"))
        val sorted = listOf("rmnet_data0", "bt-pan", "wlan0").sortedBy { NetworkUtils.interfaceRank(it) }
        assertEquals(listOf("wlan0", "bt-pan", "rmnet_data0"), sorted)
    }
}
