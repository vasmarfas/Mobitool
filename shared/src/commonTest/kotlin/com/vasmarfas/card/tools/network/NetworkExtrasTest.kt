package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.Net
import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject

class NetworkExtrasTest {
    @Test
    fun bufferbloat() {
        assertEquals("A+", bufferbloatGrade(3.0))
        assertEquals("B", bufferbloatGrade(45.0))
        assertEquals("C", bufferbloatGrade(120.0))
        assertEquals("F", bufferbloatGrade(900.0))
    }

    @Test
    fun mtu() {
        assertEquals(Res.string.ping_mtu_ethernet, mtuNote(1500))
        assertEquals(Res.string.ping_mtu_pppoe, mtuNote(1492))
        assertEquals(Res.string.ping_mtu_tunnel, mtuNote(1420))
        assertEquals(Res.string.ping_mtu_low, mtuNote(1280))
    }

    @Test
    fun socketPayload() {
        assertContentEquals(byteArrayOf(0x48, 0x49, 0x0A), SocketPayload.hex("48 49:0a"))
        assertNull(SocketPayload.hex("4G"))
        assertNull(SocketPayload.hex("484"))
        assertEquals("PING\r\nQUIT\r\n", SocketPayload.text("PING\nQUIT", crlf = true).decodeToString())
        assertEquals("PING", SocketPayload.text("PING", crlf = false).decodeToString())
        assertEquals("0000  48 69 00" + " ".repeat(41) + "Hi.", SocketPayload.dump(byteArrayOf(0x48, 0x69, 0)))
    }

    @Test
    fun ripeStat() {
        val ip = Net.json.parseToJsonElement(
            """{"data":{"announced":true,"asns":[{"asn":13335,"holder":"CLOUDFLARENET - Cloudflare, Inc."}],"resource":"1.1.1.0/24"}}""",
        ).jsonObject
        val routing = RipeStat.prefixOverview(ip)
        assertEquals("1.1.1.0/24", routing.prefix)
        assertEquals(listOf(13335L to "CLOUDFLARENET - Cloudflare, Inc."), routing.origins)
        val quiet = Net.json.parseToJsonElement("""{"data":{"announced":false,"asns":[],"resource":"10.0.0.0/8"}}""").jsonObject
        assertNull(RipeStat.prefixOverview(quiet).prefix)
        val prefixes = Net.json.parseToJsonElement("""{"data":{"prefixes":[{"prefix":"104.16.0.0/13"},{"prefix":"2606:4700::/32"}]}}""").jsonObject
        assertEquals(listOf("104.16.0.0/13", "2606:4700::/32"), RipeStat.announced(prefixes))
        assertEquals("CLOUDFLARENET", RipeStat.holder(Net.json.parseToJsonElement("""{"data":{"holder":"CLOUDFLARENET"}}""").jsonObject))
    }
}
