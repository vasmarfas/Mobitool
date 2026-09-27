package com.vasmarfas.card.tools.network

import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ipv4Test {
    @Test
    fun parsesAndFormats() {
        val ip = Ipv4.parse("192.168.1.10")
        assertNotNull(ip)
        assertEquals("192.168.1.10", Ipv4.format(ip))
        assertNull(Ipv4.parse("256.1.1.1"))
        assertNull(Ipv4.parse("1.2.3"))
    }

    @Test
    fun subnetMath() {
        val s = Ipv4.parseSubnet("192.168.1.10/24")!!
        assertEquals("192.168.1.0", Ipv4.format(s.network))
        assertEquals("192.168.1.255", Ipv4.format(s.broadcast))
        assertEquals("255.255.255.0", Ipv4.format(s.mask))
        assertEquals("0.0.0.255", Ipv4.format(s.wildcard))
        assertEquals(254, s.usableHosts)
        assertEquals("192.168.1.1", Ipv4.format(s.firstHost))
        assertEquals("192.168.1.254", Ipv4.format(s.lastHost))
        assertTrue(s.isPrivate)
    }

    @Test
    fun maskAndPrefixForms() {
        assertEquals(20, Ipv4.parseSubnet("172.16.5.4 255.255.240.0")!!.prefix)
        assertEquals(20, Ipv4.parseSubnet("172.16.5.4/255.255.240.0")!!.prefix)
        assertEquals(8, Ipv4.parseSubnet("10.1.2.3")!!.prefix)
        assertNull(Ipv4.maskToPrefix(Ipv4.parse("255.0.255.0")!!))
        assertEquals(2, Ipv4.parseSubnet("10.0.0.0/31")!!.usableHosts)
        assertEquals(1, Ipv4.parseSubnet("10.0.0.7/32")!!.usableHosts)
    }

    @Test
    fun ptrName() {
        assertEquals("4.3.2.1.in-addr.arpa", Ipv4.ptrName(Ipv4.parse("1.2.3.4")!!))
    }
}

class Ipv6Test {
    @Test
    fun compressAndExpand() {
        val a = Ipv6Address.parse("2001:0db8:0000:0000:0000:0000:0000:0001")!!
        assertEquals("2001:db8::1", a.compressed())
        assertEquals("2001:0db8:0000:0000:0000:0000:0000:0001", a.expanded())
        assertEquals("::", Ipv6Address.parse("::")!!.compressed())
        assertEquals("::1", Ipv6Address.parse("0:0:0:0:0:0:0:1")!!.compressed())
        assertEquals("fe80::1:0:0:1", Ipv6Address.parse("fe80:0:0:0:1:0:0:1")!!.compressed())
    }

    @Test
    fun rejectsInvalid() {
        assertNull(Ipv6Address.parse("2001::db8::1"))
        assertNull(Ipv6Address.parse("12345::1"))
        assertNull(Ipv6Address.parse("1:2:3:4:5:6:7:8:9"))
    }

    @Test
    fun ipv4Mapped() {
        val a = Ipv6Address.parse("::ffff:192.0.2.128")!!
        assertEquals("::ffff:c000:280", a.compressed())
        assertEquals("IPv4-mapped (::ffff:0:0/96)", a.type)
    }

    @Test
    fun prefixBounds() {
        val (a, p) = Ipv6Address.parseWithPrefix("2001:db8:abcd:1234::5/64")!!
        assertEquals(64, p)
        assertEquals("2001:db8:abcd:1234::", a.withPrefix(p).compressed())
        assertEquals("2001:db8:abcd:1234:ffff:ffff:ffff:ffff", a.lastInPrefix(p).compressed())
    }
}

class SubnetMathTest {
    @Test
    fun equalSplit() {
        val parts = SubnetMath.split(Ipv4.parseSubnet("192.168.0.0/24")!!, 4).toList()
        assertEquals(4, parts.size)
        assertEquals("192.168.0.64/26", "${Ipv4.format(parts[1].network)}/${parts[1].prefix}")
    }

    @Test
    fun splitBeyondTheAddressSpace() {
        assertTrue(SubnetMath.split(Ipv4.parseSubnet("10.0.0.0/8")!!, 1_073_741_825).none())
        val last = SubnetMath.split(Ipv4.parseSubnet("10.0.0.0/8")!!, 16_777_216).take(3).toList().last()
        assertEquals("10.0.0.2/32", "${Ipv4.format(last.network)}/${last.prefix}")
    }

    @Test
    fun bareAddressIsOneHost() {
        assertEquals(32, Ipv4.parseBlock("8.8.8.8")!!.prefix)
        assertEquals(24, Ipv4.parseBlock("10.0.0.0/24")!!.prefix)
        val result = SubnetMath.summarize(listOf("8.8.8.8", "8.8.4.4").map { Ipv4.parseBlock(it)!! })
        assertEquals(listOf("8.8.4.4/32", "8.8.8.8/32"), result.map { "${Ipv4.format(it.network)}/${it.prefix}" })
    }

    @Test
    fun reservedRangesAreNotPublic() {
        assertTrue(Ipv4.parseSubnet("8.8.8.8/32")!!.isPublic)
        assertTrue(Ipv4.parseSubnet("240.1.2.3/4")!!.isReserved)
        assertTrue(Ipv4.parseSubnet("0.0.0.0/8")!!.isThisNetwork)
        assertTrue(Ipv4.parseSubnet("255.255.255.255/32")!!.isBroadcast)
        listOf("240.1.2.3/4", "0.0.0.0/8", "255.255.255.255/32").forEach { assertFalse(Ipv4.parseSubnet(it)!!.isPublic, it) }
    }

    @Test
    fun vlsm() {
        val plan = SubnetMath.vlsm(Ipv4.parseSubnet("192.168.0.0/24")!!, listOf(100, 50, 20, 2))!!
        assertEquals(listOf(25, 26, 27, 30), plan.map { it.second.prefix })
        assertEquals("192.168.0.0", Ipv4.format(plan[0].second.network))
        assertEquals("192.168.0.128", Ipv4.format(plan[1].second.network))
        assertNull(SubnetMath.vlsm(Ipv4.parseSubnet("192.168.0.0/28")!!, listOf(100)))
    }

    @Test
    fun rangeToCidrs() {
        val cidrs = SubnetMath.rangeToCidrs(Ipv4.parse("192.168.1.10")!!, Ipv4.parse("192.168.1.250")!!)
        assertEquals("192.168.1.10/31", "${Ipv4.format(cidrs.first().network)}/${cidrs.first().prefix}")
        assertEquals(241L, cidrs.sumOf { it.totalAddresses })
    }

    @Test
    fun summarize() {
        val result = SubnetMath.summarize(listOf("10.0.0.0/24", "10.0.1.0/24", "10.0.2.0/23").map { Ipv4.parseSubnet(it)!! })
        assertEquals(1, result.size)
        assertEquals("10.0.0.0/22", "${Ipv4.format(result[0].network)}/${result[0].prefix}")
    }
}

class DnsMessageTest {
    @Test
    fun buildsQuery() {
        val bytes = DnsMessage.buildQuery("example.com", 1, 0x1234)
        assertEquals(0x12, bytes[0].toInt() and 0xFF)
        assertEquals(0x34, bytes[1].toInt() and 0xFF)
        assertEquals(0x0120, ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))
        assertEquals(1, bytes[11].toInt())
        assertEquals(7, bytes[12].toInt())
        assertEquals('e'.code, bytes[13].toInt())
        assertEquals(1, bytes[28].toInt())
        assertEquals(41, bytes[31].toInt())
        assertEquals(1232, ((bytes[32].toInt() and 0xFF) shl 8) or (bytes[33].toInt() and 0xFF))
        assertEquals(40, bytes.size)
    }

    @Test
    fun wireAndJsonReadTheSame() {
        val soa = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 0, 0, 1, 0, 0, 0, 0) +
            byteArrayOf(0, 0, 6, 0, 1, 0, 0, 0x0E, 0x10, 0, 30) +
            byteArrayOf(2) + "ns".encodeToByteArray() + byteArrayOf(0) + byteArrayOf(4) + "host".encodeToByteArray() + byteArrayOf(0) +
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0, 5)
        assertEquals("ns host 1 2 3 4 5", DnsMessage.parse(soa).answers[0].data)
        assertEquals("ns host 1 2 3 4 5", DnsClient.jsonData(6, "ns. host. 1 2 3 4 5"))
        assertEquals("v=spf1 -allpart two", DnsClient.jsonData(16, "\"v=spf1 -all\" \"part two\""))
        assertEquals("v=spf1 -all", DnsClient.jsonData(16, "v=spf1 -all"))
        assertEquals("0 issue letsencrypt.org", DnsClient.jsonData(257, "0 issue \"letsencrypt.org\""))
        assertEquals("10 mail.example.com", DnsClient.jsonData(15, "10 mail.example.com."))
    }

    @Test
    fun parsesAnswerWithCompression() {
        val header = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        val question = byteArrayOf(7) + "example".encodeToByteArray() + byteArrayOf(3) + "com".encodeToByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        val answer = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0x0E, 0x10, 0, 4, 93, 184.toByte(), 216.toByte(), 34)
        val response = DnsMessage.parse(header + question + answer)
        assertEquals("NOERROR", response.rcodeName)
        assertEquals(1, response.answers.size)
        assertEquals("example.com", response.answers[0].name)
        assertEquals("93.184.216.34", response.answers[0].data)
        assertEquals(3600L, response.answers[0].ttl)
    }
}

class MacAndPortsTest {
    @Test
    fun macNormalization() {
        assertEquals("001A2B3C4D5E", MacAddress.normalize("00:1a:2b:3c:4d:5e"))
        assertEquals("001A2B3C4D5E", MacAddress.normalize("001a.2b3c.4d5e"))
        assertNull(MacAddress.normalize("00:1a:2b"))
        assertEquals("00:1A:2B:3C:4D:5E", MacAddress.colon("001A2B3C4D5E"))
        assertTrue(MacAddress.isMulticast("01005E000001"))
        assertTrue(MacAddress.isLocal(MacAddress.random()))
    }

    @Test
    fun portSpec() {
        assertEquals(listOf(22, 80, 443, 8000, 8001, 8002), parsePorts("22, 80,443 8000-8002"))
        assertEquals(emptyList(), parsePorts("0, 70000"))
        assertEquals(65535, parsePorts("1-65535").size)
        assertEquals(65535, parsePorts("0-2000000000").size)
        assertEquals(listOf(65534, 65535), parsePorts("65534-70000"))
        assertTrue(WellKnownPorts.search("ssh").any { it.port == 22 })
        assertEquals("HTTPS", WellKnownPorts.service(443))
    }

    @Test
    fun portDescriptionsInBothLanguages() {
        assertTrue(WellKnownPorts.search("mail transfer").any { it.port == 25 })
        assertTrue(WellKnownPorts.search("почт").any { it.port == 25 })
        WellKnownPorts.all.forEach { assertNotNull(it.description.russian(), it.port.toString()) }
    }

    @Test
    fun rdapAsksForAsciiNames() {
        assertEquals("https://rdap.org/domain/xn--e1afmkfd.xn--p1ai", Rdap.url("пример.рф"))
        assertEquals("https://rdap.org/ip/8.8.8.8", Rdap.url("8.8.8.8"))
        assertEquals("https://rdap.org/autnum/15169", Rdap.url("AS15169"))
    }

    @Test
    fun hostExtraction() {
        assertEquals("example.com", hostFrom("https://example.com/path?q=1"))
        assertEquals("example.com", hostFrom("example.com:8080"))
        assertEquals("2001:db8::1", hostFrom("[2001:db8::1]:443"))
    }
}
