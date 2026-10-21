package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.InterfaceInfo
import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionCheckTest {
    private fun nif(name: String, vararg addresses: String, label: String = name) =
        InterfaceInfo(name, label, null, addresses.toList(), isUp = true, isLoopback = name == "lo", mtu = 1500)

    private val healthy = ConnectionFacts(
        local = true,
        interfaces = listOf(nif("lo", "127.0.0.1/8"), nif("wlan0", "192.168.1.23/24", "fe80::1/64")),
        link = mapOf("Gateway" to "192.168.1.1", "DNS" to "192.168.1.1", "RSSI" to "-58", "Transport" to "Wi-Fi"),
        gateway = PingStats("192.168.1.1", 10, List(10) { 3.0 }),
        internet = PingStats("1.1.1.1", 10, List(10) { 21.0 }),
        systemDns = listOf("1.1.1.1", "1.0.0.1"),
        doh = listOf("1.1.1.1", "1.0.0.1"),
        portal = Portal.NONE,
        https = Trace("203.0.113.7", "Russia"),
        clockSkew = 0,
        ipv6 = "",
    )

    @Test
    fun healthyConnectionPasses() {
        val summary = healthy.summary()
        assertEquals(Verdict.OK, summary?.verdict)
        assertEquals(Res.string.conn_network_ok, healthy.finding(CheckStep.NETWORK)?.text)
        assertEquals("192.168.1.23", healthy.finding(CheckStep.NETWORK)?.args?.single())
        assertEquals(Res.string.conn_dns_ok_servers, healthy.finding(CheckStep.DNS)?.text)
        assertEquals(Res.string.conn_ipv6_none, healthy.finding(CheckStep.IPV6)?.text)
        assertEquals(Res.string.conn_vpn_off, healthy.finding(CheckStep.VPN)?.text)
    }

    @Test
    fun summaryWaitsForEveryStep() {
        assertNull(healthy.copy(internet = null).summary())
        assertNull(ConnectionFacts(local = true).summary())
        assertFalse(healthy.copy(ipv6 = null).done)
    }

    @Test
    fun linkLocalAddressMeansNoDhcp() {
        val facts = healthy.copy(interfaces = listOf(nif("wlan0", "169.254.10.4/16")), link = mapOf("Gateway" to ""))
        assertEquals(Res.string.conn_no_dhcp, facts.summary()?.text)
    }

    @Test
    fun deadDnsIsTheCause() {
        val facts = healthy.copy(systemDns = emptyList())
        assertEquals(Res.string.conn_dns_broken, facts.summary()?.text)
        assertEquals(Res.string.conn_dns_none, facts.copy(doh = emptyList()).finding(CheckStep.DNS)?.text)
        assertEquals(Verdict.WARN, healthy.copy(systemDns = listOf("0.0.0.0")).finding(CheckStep.DNS)?.verdict)
    }

    @Test
    fun captivePortalComesBeforeTheMissingInternet() {
        val facts = healthy.copy(portal = Portal.FOUND, internet = PingStats("1.1.1.1", 3, emptyList()))
        assertEquals(Res.string.conn_portal_found, facts.summary()?.text)
        val flagged = healthy.copy(link = healthy.link!! + ("Captive portal" to "true"), portal = Portal.UNKNOWN)
        assertEquals(Res.string.conn_portal_found, flagged.summary()?.text)
    }

    @Test
    fun silentRouterIsFineWhileTheInternetAnswers() {
        val silent = healthy.copy(gateway = PingStats("192.168.1.1", 3, emptyList()))
        assertEquals(Verdict.INFO, silent.finding(CheckStep.GATEWAY)?.verdict)
        assertEquals(Verdict.OK, silent.summary()?.verdict)
        val down = silent.copy(internet = PingStats("1.1.1.1", 3, emptyList()))
        assertEquals(Res.string.conn_gateway_down, down.summary()?.text)
        assertNull(silent.copy(internet = null).finding(CheckStep.GATEWAY))
    }

    @Test
    fun lossesAndSlowRepliesWarn() {
        val lossy = healthy.copy(gateway = PingStats("192.168.1.1", 10, List(8) { 4.0 }))
        assertEquals(Res.string.conn_gateway_unstable, lossy.summary()?.text)
        assertEquals(listOf("192.168.1.1", "4", "20"), lossy.finding(CheckStep.GATEWAY)?.args?.toList())
        val weak = healthy.copy(link = healthy.link!! + ("RSSI" to "-81"))
        assertEquals(Res.string.conn_wifi_weak, weak.summary()?.text)
    }

    @Test
    fun clockSkew() {
        assertEquals(Res.string.conn_clock_ok, healthy.copy(clockSkew = -2).finding(CheckStep.CLOCK)?.text)
        assertEquals(Res.string.conn_clock_minutes, healthy.copy(clockSkew = -600).finding(CheckStep.CLOCK)?.text)
        val broken = healthy.copy(clockSkew = 3 * 86_400L, https = Trace(error = "certificate"))
        assertEquals(Res.string.conn_clock_days, broken.summary()?.text)
        assertFalse(CheckStep.CLOCK in healthy.copy(clockSkew = null).visibleSteps())
    }

    @Test
    fun vpnAndBrokenIpv6() {
        val vpn = healthy.copy(interfaces = healthy.interfaces!! + nif("tun0", "10.8.0.2/24"))
        assertEquals("tun0", vpn.vpn)
        val windows = healthy.copy(interfaces = listOf(nif("net7", "10.2.0.2/32", label = "WireGuard Tunnel #1"), nif("teredo", "2001:0:1::1/64", label = "Teredo Tunneling")))
        assertEquals("net7", windows.vpn)
        val v6 = healthy.copy(interfaces = listOf(nif("wlan0", "192.168.1.23/24", "2a02:6b8::5/64")))
        assertEquals(Res.string.conn_ipv6_broken, v6.finding(CheckStep.IPV6)?.text)
        assertEquals(Res.string.conn_ipv6_ok, v6.copy(ipv6 = "2a02:6b8::5").finding(CheckStep.IPV6)?.text)
    }

    @Test
    fun browserShowsOnlyWhatItCanCheck() {
        val web = ConnectionFacts(local = false, internet = healthy.internet, doh = healthy.doh, https = Trace("203.0.113.7", warp = true), clockSkew = 1, ipv6 = "")
        val steps = web.visibleSteps()
        assertFalse(CheckStep.NETWORK in steps || CheckStep.GATEWAY in steps || CheckStep.PORTAL in steps)
        assertEquals("Cloudflare WARP", web.vpn)
        assertEquals(Res.string.conn_dns_doh_ok, web.finding(CheckStep.DNS)?.text)
        assertEquals(Verdict.OK, web.summary()?.verdict)
    }

    @Test
    fun probes() {
        val (host, port, request) = ConnectionProbe.plainRequest("http://192.168.1.1:1900/rootDesc.xml")
        assertEquals("192.168.1.1", host)
        assertEquals(1900, port)
        assertTrue(request.decodeToString().startsWith("GET /rootDesc.xml HTTP/1.0\r\nHost: 192.168.1.1:1900\r\n"))
        val response = "HTTP/1.1 204 No Content\r\nDate: Wed, 21 Oct 2026 10:47:39 GMT\r\n\r\n"
        assertEquals(204, ConnectionProbe.status(response))
        assertEquals(1792579659, ConnectionProbe.header(response, "date")?.let(EmailHeaders::parseDate))
        val trace = ConnectionProbe.trace("fl=1\nip=198.51.100.36\nts=1792579294.000\nloc=RU\nwarp=off\n")
        assertEquals("RU", trace["loc"])
        assertEquals(1792579294.0, trace["ts"]?.toDouble())
    }
}
