package com.geoffrey.cancitroen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkParsingTest {
    private val addr = """
        1: lo    inet 127.0.0.1/8 scope host lo
        30: wlan0    inet 192.168.43.7/24 brd 192.168.43.255 scope global wlan0
    """.trimIndent()

    @Test fun localAddressesAreRead() {
        assertEquals(setOf("127.0.0.1", "192.168.43.7"), LinkParsing.localAddresses(addr))
    }

    @Test fun gatewayFromAnyWifiTable() {
        val routes = """
            default via 192.168.43.1 dev wlan0 table wlan0 proto static
            default via 10.20.30.40 dev rmnet_data1 table rmnet_data1
            192.168.43.0/24 dev wlan0 proto kernel scope link src 192.168.43.7
        """.trimIndent()
        assertEquals(
            listOf(Candidate("192.168.43.1", "wlan0", "route")),
            LinkParsing.fromRoutes(routes, setOf("192.168.43.7")),
        )
    }

    @Test fun routingTableByNumberOrName() {
        val numbered = """
            192.168.43.0/24 dev wlan0 table 1000000017 proto static scope link
            default via 192.168.43.1 dev wlan0 table 1017 proto static mtu 1500
        """.trimIndent()
        assertEquals("1017", LinkParsing.routingTable(numbered, "wlan0"))
        assertEquals("wlan0", LinkParsing.routingTable("default via 192.168.43.1 dev wlan0 table wlan0 proto static", "wlan0"))
        assertEquals("1000000017", LinkParsing.routingTable("192.168.43.0/24 dev wlan0 table 1000000017 proto static", "wlan0"))
        assertEquals(null, LinkParsing.routingTable(numbered, "p2p0"))
    }

    @Test fun deadNeighboursAndOwnAddressIgnored() {
        val neigh = """
            192.168.43.1 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE
            192.168.43.9 dev wlan0  FAILED
            192.168.43.7 dev wlan0 lladdr 11:22:33:44:55:66 STALE
            10.0.0.1 dev rmnet0 lladdr 11:22:33:44:55:66 STALE
        """.trimIndent()
        assertEquals(
            listOf(Candidate("192.168.43.1", "wlan0", "voisin")),
            LinkParsing.fromNeighbours(neigh, setOf("192.168.43.7")),
        )
    }

    @Test fun onlyPlainValuesReachTheRootCommand() {
        assertTrue(LinkParsing.isIpv4("192.168.43.1"))
        assertFalse(LinkParsing.isIpv4("192.168.43.300"))
        assertFalse(LinkParsing.isIpv4("1.2.3.4; reboot"))
        assertFalse(LinkParsing.isIpv4("atoto.local"))
        assertTrue(LinkParsing.isIfaceName("wlan0"))
        assertFalse(LinkParsing.isIfaceName("wlan0;reboot"))
    }

    @Test fun reportKeepsWifiAndHidesHardwareAddresses() {
        val neigh = "192.168.43.1 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE\n10.0.0.1 dev rmnet0 lladdr 11:22:33:44:55:66 STALE"
        assertEquals("192.168.43.1 dev wlan0 REACHABLE", LinkParsing.wifiLinesOnly(neigh))
    }
}
