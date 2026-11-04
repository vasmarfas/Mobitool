package com.vasmarfas.card.tools.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortProbesTest {
    private fun hex(text: String): ByteArray = assertNotNull(SocketPayload.hex(text))

    private fun banner(text: String) = PortProbes.banner(text.encodeToByteArray())

    // RFC 4253 section 4.2
    @Test
    fun readsSshIdentification() {
        assertEquals("SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13", banner("SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13\r\n"))
        assertEquals("SSH-2.0-dropbear_2022.83", banner("Welcome\r\nSSH-2.0-dropbear_2022.83\r\n"))
    }

    // RFC 959 and RFC 5321 reply 220, RFC 1939 +OK, RFC 9051 untagged OK
    @Test
    fun stripsGreetingCodes() {
        assertEquals("ProFTPD Server (Debian) [203.0.113.5]", banner("220 ProFTPD Server (Debian) [203.0.113.5]\r\n"))
        assertEquals("mail.example.com ESMTP Postfix (Ubuntu)", banner("220 mail.example.com ESMTP Postfix (Ubuntu)\r\n"))
        assertEquals("vsFTPd ready", banner("220-vsFTPd ready\r\n220 go on\r\n"))
        assertEquals("Dovecot ready.", banner("+OK Dovecot ready.\r\n"))
        assertEquals("[CAPABILITY IMAP4rev1] Dovecot ready.", banner("* OK [CAPABILITY IMAP4rev1] Dovecot ready.\r\n"))
    }

    @Test
    fun readsHttpServerHeader() {
        assertEquals("nginx/1.24.0", banner("HTTP/1.1 301 Moved Permanently\r\nserver: nginx/1.24.0\r\nContent-Length: 0\r\n\r\n"))
        assertEquals("HTTP/1.0 200 OK", banner("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\n\r\n"))
    }

    @Test
    fun readsRedisVersionFromInfo() {
        assertEquals("Redis 7.2.4", banner("\$220\r\n# Server\r\nredis_version:7.2.4\r\nredis_mode:standalone\r\n"))
        assertEquals("-NOAUTH Authentication required.", banner("-NOAUTH Authentication required.\r\n"))
    }

    @Test
    fun readsPlainOneLiners() {
        assertEquals("VERSION 1.6.21", banner("VERSION 1.6.21\r\n"))
        assertEquals("RFB 003.008", banner("RFB 003.008\n"))
    }

    // MySQL Protocol::HandshakeV10
    @Test
    fun readsMysqlHandshake() {
        assertEquals("8.0.36", PortProbes.banner(hex("4A 00 00 00 0A 38 2E 30 2E 33 36 00 0C 00 00 00")))
    }

    @Test
    fun ignoresBinaryAndEmptyReplies() {
        assertNull(PortProbes.banner(ByteArray(0)))
        assertNull(PortProbes.banner(hex("FF FD 18 FF FD 20")))
        assertNull(PortProbes.banner(hex("01 02 03 04")))
        assertNull(banner("\r\n"))
    }

    @Test
    fun longBannerIsCut() {
        assertEquals(120, assertNotNull(banner("x".repeat(500))).length)
    }

    @Test
    fun onlyKnownPortsGetARequest() {
        assertEquals("HEAD / HTTP/1.0\r\n\r\n", PortProbes.tcpRequest(8080).decodeToString())
        assertEquals("INFO server\r\n", PortProbes.tcpRequest(6379).decodeToString())
        assertEquals("version\r\n", PortProbes.tcpRequest(11211).decodeToString())
        listOf(21, 22, 25, 443, 515, 9100, 9101, 54321).forEach { assertEquals(0, PortProbes.tcpRequest(it).size, "port $it") }
    }

    @Test
    fun udpProbesCarryTheirProtocol() {
        assertEquals(48, PortProbes.udpRequest(123, 0).size)
        assertTrue(PortProbes.udpRequest(1900, 0).decodeToString().startsWith("M-SEARCH * HTTP/1.1"))
        assertEquals("public", Snmp.parse(PortProbes.udpRequest(161, 0))?.community)
        assertTrue(PortProbes.udpRequest(40000, 0).isNotEmpty())
    }

    // RFC 1035 section 4.1: header, then the root question, type NS, class IN
    @Test
    fun recognisesDnsAnswer() {
        assertEquals("NOERROR", PortProbes.udpAnswer(53, hex("12 34 81 80 00 01 00 00 00 00 00 00 00 00 02 00 01")))
        assertEquals("REFUSED", PortProbes.udpAnswer(53, hex("12 34 81 85 00 01 00 00 00 00 00 00 00 00 02 00 01")))
    }

    @Test
    fun anEchoedDnsQueryIsNotAnAnswer() {
        assertNull(PortProbes.udpAnswer(53, hex("12 34 01 20 00 01 00 00 00 00 00 00 00 00 02 00 01")))
    }

    // RFC 5905 section 7.3: LI 0, version 4, mode 4 in the first byte, stratum in the second
    @Test
    fun recognisesNtpAnswer() {
        val reply = ByteArray(48).also {
            it[0] = 0x24
            it[1] = 2
        }
        assertEquals("stratum 2", PortProbes.udpAnswer(123, reply))
    }

    @Test
    fun recognisesSnmpAnswer() {
        val reply = hex("30 2B 02 01 01 04 06 70 75 62 6C 69 63 A2 1E 02 01 01 02 01 00 02 01 00 30 13 30 11 06 08 2B 06 01 02 01 01 01 00 04 05 4C 69 6E 75 78")
        assertEquals("Linux", PortProbes.udpAnswer(161, reply))
    }

    // UPnP Device Architecture 2.0 section 1.3.3
    @Test
    fun recognisesSsdpAnswer() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nSERVER: Linux/5.10 UPnP/1.0 MiniUPnPd/2.3\r\nST: upnp:rootdevice\r\n\r\n"
        assertEquals("Linux/5.10 UPnP/1.0 MiniUPnPd/2.3", PortProbes.udpAnswer(1900, reply.encodeToByteArray()))
    }

    @Test
    fun otherUdpPortsShowWhatTheySaid() {
        assertEquals("hello", PortProbes.udpAnswer(40000, "hello\n".encodeToByteArray()))
        assertNull(PortProbes.udpAnswer(40000, hex("00 01 02")))
    }
}
