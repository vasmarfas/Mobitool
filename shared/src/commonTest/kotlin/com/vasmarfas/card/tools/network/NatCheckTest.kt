package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.parseXml
import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NatCheckTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun stun() {
        val transaction = ByteArray(12) { it.toByte() }
        val request = Stun.request(transaction)
        assertEquals(20, request.size)
        assertEquals(listOf(0, 1, 0, 0, 0x21, 0x12, 0xA4, 0x42), request.take(8).map { it.toInt() and 0xFF })
        // 203.0.113.7:54321 xored with the cookie: port 0xD431 ^ 0x2112, address bytes ^ 21 12 A4 42
        val header = bytes(1, 1, 0, 12, 0x21, 0x12, 0xA4, 0x42) + transaction
        val xored = bytes(0, 0x20, 0, 8, 0, 1, 0xD4 xor 0x21, 0x31 xor 0x12, 203 xor 0x21, 0 xor 0x12, 113 xor 0xA4, 7 xor 0x42)
        assertEquals("203.0.113.7:54321", Stun.mappedAddress(header + xored))
        val plain = bytes(1, 1, 0, 12, 0x21, 0x12, 0xA4, 0x42) + transaction + bytes(0, 1, 0, 8, 0, 1, 0xD4, 0x31, 203, 0, 113, 7)
        assertEquals("203.0.113.7:54321", Stun.mappedAddress(plain))
        assertNull(Stun.mappedAddress(bytes(1, 0, 0, 0)))
    }

    @Test
    fun natPmp() {
        assertEquals("100.70.1.2", NatPmp.externalAddress(bytes(0, 128, 0, 0, 0, 0, 1, 0, 100, 70, 1, 2)))
        assertNull(NatPmp.externalAddress(bytes(0, 128, 0, 3, 0, 0, 1, 0, 0, 0, 0, 0)))
        assertNull(NatPmp.externalAddress(null))
        assertEquals(listOf(0, 2, 0, 0, 0x9C, 0x40, 0x9C, 0x40, 0, 0, 2, 0x58), NatPmp.mapRequest(40000, 40000, 600).map { it.toInt() and 0xFF })
        assertEquals(40001, NatPmp.mappedPort(bytes(0, 130, 0, 0, 0, 0, 1, 0, 0x9C, 0x40, 0x9C, 0x41, 0, 0, 2, 0x58)))
        assertNull(NatPmp.mappedPort(bytes(0, 130, 0, 2, 0, 0, 1, 0, 0x9C, 0x40, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun upnp() {
        val description = parseXml(
            """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device><deviceList><device><deviceList><device>
              <serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                <controlURL>/upnp/control/WANIPConn1</controlURL>
              </service></serviceList>
            </device></deviceList></device></deviceList></device></root>
            """.trimIndent(),
        )
        val (type, control) = Upnp.wanService(description)!!
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", type)
        val url = Upnp.controlUrl("http://192.168.1.1:5000/rootDesc.xml", control)
        assertEquals("http://192.168.1.1:5000/upnp/control/WANIPConn1", url)
        val request = Upnp.request(url, type, "GetExternalIPAddress").decodeToString()
        assertEquals("POST /upnp/control/WANIPConn1 HTTP/1.0", request.lines().first())
        val mapping = Upnp.request(url, type, "AddPortMapping", listOf("NewExternalPort" to "40000", "NewProtocol" to "TCP")).decodeToString()
        assertTrue("SOAPAction: \"$type#AddPortMapping\"" in mapping)
        assertTrue("<u:AddPortMapping xmlns:u=\"$type\"><NewExternalPort>40000</NewExternalPort><NewProtocol>TCP</NewProtocol></u:AddPortMapping>" in mapping)
        assertEquals("100.64.12.9", Upnp.externalIp("<s:Body><u:GetExternalIPAddressResponse><NewExternalIPAddress>100.64.12.9</NewExternalIPAddress>"))
    }

    private fun http(ip: String?) = listOf(SeenIp(IpSource.YANDEX, ip), SeenIp(IpSource.CLOUDFLARE, ip))

    private fun stun(vararg mapped: String) = mapped.map { SeenIp(IpSource.STUN_GOOGLE, it) }

    @Test
    fun classes() {
        val base = NatFacts(
            local = true,
            http = http("203.0.113.7"),
            stun = stun("203.0.113.7:40000"),
            addresses = listOf("192.168.1.5"),
            routerDone = true,
            hops = listOf("192.168.1.1", "203.0.113.1"),
        )
        assertEquals(IpClass.WHITE_LIKELY, base.ipClass)
        assertEquals(IpClass.WHITE, base.copy(router = "203.0.113.7").ipClass)
        assertEquals(Res.string.nat_router_same, base.copy(router = "203.0.113.7").routerFinding()?.text)
        assertEquals(IpClass.GRAY, base.copy(router = "100.64.3.4").ipClass)
        assertEquals(Res.string.nat_router_cgnat, base.copy(router = "100.64.3.4").routerFinding()?.text)
        assertEquals(IpClass.GRAY_LIKELY, base.copy(hops = listOf("192.168.1.1", "10.20.0.1", "203.0.113.1")).ipClass)
        assertEquals(IpClass.GRAY, base.copy(addresses = listOf("100.72.1.9")).ipClass)
        assertEquals(IpClass.WHITE, base.copy(addresses = listOf("203.0.113.7")).ipClass)
        assertEquals(NatKind.CONE, base.natKind)
        assertEquals(NatKind.SYMMETRIC, base.copy(stun = stun("203.0.113.7:40000", "203.0.113.7:40003")).natKind)
        assertEquals(NatKind.NONE, base.copy(addresses = listOf("203.0.113.7")).natKind)
        assertEquals(Res.string.nat_type_unknown, base.copy(stun = listOf(SeenIp(IpSource.STUN_GOOGLE, null))).natFinding()?.text)
        assertEquals(Res.string.nat_paths_same, base.pathsFinding()?.text)
        assertEquals(IpClass.WHITE, base.copy(hops = listOf("203.0.113.7")).ipClass)
        assertEquals(Res.string.nat_path_own, base.copy(hops = listOf("203.0.113.7")).pathFinding()?.text)
        val beyond = base.copy(hops = listOf("192.168.1.1", "10.0.0.1", "203.0.113.7"))
        assertEquals(IpClass.GRAY, beyond.ipClass)
        assertEquals(3, beyond.ownDistance)
    }

    // a VPN that wraps only foreign sites: Yandex sees the provider, Cloudflare the VPN exit
    @Test
    fun splitRouting() {
        val split = NatFacts(
            local = true,
            http = listOf(SeenIp(IpSource.YANDEX, "203.0.113.95"), SeenIp(IpSource.CLOUDFLARE, "198.51.100.36"), SeenIp(IpSource.IPIFY, null)),
            stun = stun("203.0.113.95:50000"),
            addresses = listOf("192.168.1.10"),
            routerDone = true,
            router = "203.0.113.95",
            hops = emptyList(),
        )
        assertEquals(listOf("203.0.113.95", "198.51.100.36"), split.observed)
        assertEquals("203.0.113.95", split.directIp)
        val paths = split.pathsFinding()!!
        assertEquals(Res.string.nat_paths_split, paths.text)
        assertEquals("203.0.113.95", paths.args[1])
        assertEquals(IpClass.WHITE, split.ipClass)
        val silentRouter = split.copy(
            http = listOf(SeenIp(IpSource.YANDEX, null), SeenIp(IpSource.BEGET, "203.0.113.40"), SeenIp(IpSource.CLOUDFLARE, "198.51.100.162")),
            stun = stun("198.51.100.162:34536"),
            router = null,
            hops = listOf("203.0.113.40"),
        )
        assertEquals("203.0.113.40", silentRouter.directIp)
        // one socket: Google sees the VPN exit, Sipnet the line, each with its own port, and that is still a cone NAT
        val bothPaths = listOf(SeenIp(IpSource.STUN_GOOGLE, "198.51.100.162:34536"), SeenIp(IpSource.STUN_SIPNET, "203.0.113.40:51000"))
        assertEquals(NatKind.CONE, silentRouter.copy(stun = bothPaths).natKind)
        assertEquals(IpClass.WHITE, silentRouter.ipClass)
        val wrapped = split.copy(http = http("198.51.100.36"), stun = stun("198.51.100.36:41000"))
        assertEquals(Res.string.nat_router_other, wrapped.routerFinding()?.text)
        assertEquals(IpClass.WHITE, wrapped.ipClass)
    }
}
