package com.vasmarfas.card.core

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PingParseTest {
    private fun cp866(text: String) = text.toByteArray(Charset.forName("IBM866")).toString(Charsets.ISO_8859_1)

    @Test
    fun windowsReplyInEnglishAndRussian() {
        val english = "\r\nPinging 8.8.8.8 with 32 bytes of data:\r\nReply from 8.8.8.8: bytes=32 time=12ms TTL=117\r\n\r\n" +
            "Ping statistics for 8.8.8.8:\r\n    Packets: Sent = 1, Received = 1, Lost = 0 (0% loss),\r\n" +
            "Approximate round trip times in milli-seconds:\r\n    Minimum = 12ms, Maximum = 12ms, Average = 12ms\r\n"
        assertEquals(PingReply(1, 12.0, 117, "8.8.8.8"), parsePing(english, 1, null, ipv6 = false, elapsedMs = 40.0))
        val russian = cp866("\r\nОбмен пакетами с 8.8.8.8 по с 32 байтами данных:\r\nОтвет от 8.8.8.8: число байт=32 время<1мс TTL=64\r\n")
        assertEquals(PingReply(1, 1.0, 64, "8.8.8.8"), parsePing(russian, 1, null, ipv6 = false, elapsedMs = 40.0))
    }

    @Test
    fun localizedTimeoutIsNotAReply() {
        val output = cp866("\r\nОбмен пакетами с 192.168.1.77 по с 32 байтами данных:\r\nПревышен интервал ожидания для запроса.\r\n\r\nСтатистика Ping для 192.168.1.77:\r\n")
        val reply = parsePing(output, 1, null, ipv6 = false, elapsedMs = 1500.0)
        assertNull(reply.timeMs)
        assertNull(reply.from)
    }

    @Test
    fun expiredTtlNamesTheHop() {
        val windows = cp866("\r\nОбмен пакетами с 8.8.8.8 по с 32 байтами данных:\r\nОтвет от 10.0.0.1: Превышен срок жизни (TTL) при передаче пакета.\r\n\r\nСтатистика Ping для 8.8.8.8:\r\n")
        assertEquals("10.0.0.1", parsePing(windows, 1, 1, ipv6 = false, elapsedMs = 30.0).from)
        val mac = "PING 8.8.8.8 (8.8.8.8): 56 data bytes\n36 bytes from 192.168.1.1: Time to live exceeded\n" +
            "Vr HL TOS  Len   ID Flg  off TTL Pro  cks      Src      Dst\n 4  5  00 5400 0f21   0 0000  01  01 7a0a 192.168.1.5  8.8.8.8\n\n" +
            "--- 8.8.8.8 ping statistics ---\n1 packets transmitted, 0 packets received, 100.0% packet loss\n"
        val hop = parsePing(mac, 1, 1, ipv6 = false, elapsedMs = 30.0)
        assertEquals("192.168.1.1", hop.from)
        assertEquals("ttl-expired", hop.error)
    }

    @Test
    fun unixReplyAndLoss() {
        val linux = "PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.\n64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=12.3 ms\n\n" +
            "--- 8.8.8.8 ping statistics ---\n1 packets transmitted, 1 received, 0% packet loss, time 0ms\nrtt min/avg/max/mdev = 12.3/12.3/12.3/0.000 ms\n"
        assertEquals(PingReply(1, 12.3, 117, "8.8.8.8"), parsePing(linux, 1, null, ipv6 = false, elapsedMs = 40.0))
        val dead = "PING 10.0.0.99 (10.0.0.99): 56 data bytes\n\n--- 10.0.0.99 ping statistics ---\n1 packets transmitted, 0 packets received, 100.0% packet loss\n"
        assertNull(parsePing(dead, 1, null, ipv6 = false, elapsedMs = 1000.0).timeMs)
    }

    @Test
    fun windowsIpv6ReplyHasNoTtl() {
        val output = "\r\nPinging 2001:4860:4860::8888 with 32 bytes of data:\r\nReply from 2001:4860:4860::8888: time=15ms\r\n\r\n" +
            "Ping statistics for 2001:4860:4860::8888:\r\n    Packets: Sent = 1, Received = 1, Lost = 0 (0% loss),\r\n"
        assertEquals(15.0, parsePing(output, 1, null, ipv6 = true, elapsedMs = 40.0).timeMs)
    }
}
