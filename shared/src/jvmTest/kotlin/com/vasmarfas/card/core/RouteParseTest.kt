package com.vasmarfas.card.core

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteParseTest {
    @Test
    fun androidPrefersIpv4AndScopesLinkLocal() {
        val v4 = InetAddress.getByName("192.0.2.1")
        val linkLocal = InetAddress.getByName("fe80::1")
        assertEquals("192.0.2.1", androidGateway(listOf(linkLocal, v4), "wlan0"))
        assertEquals("${linkLocal.hostAddress}%wlan0", androidGateway(listOf(linkLocal), "wlan0"))
        assertEquals(InetAddress.getByName("2001:db8::1").hostAddress, androidGateway(listOf(InetAddress.getByName("2001:db8::1")), "wlan0"))
        assertEquals("", androidGateway(emptyList(), "wlan0"))
    }

    @Test
    fun windowsPicksTheLowestMetric() {
        val routes = """
            ===========================================================================
            IPv4 Route Table
            ===========================================================================
            Active Routes:
            Network Destination        Netmask          Gateway       Interface  Metric
                      0.0.0.0          0.0.0.0     192.168.1.1     192.168.1.10     25
                      0.0.0.0          0.0.0.0      10.0.0.1         10.0.0.5        50
            ===========================================================================
        """.trimIndent()
        assertEquals("192.168.1.1", windowsGateway(routes))
        assertNull(windowsGateway("Active Routes:\nNone\n"))
    }

    @Test
    fun linuxReadsLittleEndianHex() {
        val table = "Iface\tDestination\tGateway \tFlags\tRefCnt\tUse\tMetric\tMask\t\tMTU\tWindow\tIRTT\n" +
            "wlan0\t00000000\t0114A8C0\t0003\t0\t0\t600\t00000000\t0\t0\t0\n" +
            "eth0\t00000000\t0100000A\t0003\t0\t0\t100\t00000000\t0\t0\t0\n" +
            "eth0\t0000000A\t00000000\t0001\t0\t0\t100\t00FFFFFF\t0\t0\t0\n"
        assertEquals("10.0.0.1", linuxGateway(table))
        assertNull(linuxGateway(table.lines().take(1).joinToString("\n")))
    }
}
