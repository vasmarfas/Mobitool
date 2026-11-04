package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.SSDP_SEARCH
import com.vasmarfas.card.core.ssdpHeaders
import com.vasmarfas.card.tools.time.Ntp

object PortProbes {
    private const val DNS_ID = 0x1234
    private const val BANNER_LIMIT = 120

    private val httpPorts = setOf(80, 81, 591, 3000, 5000, 8000, 8008, 8080, 8081, 8083, 8088, 8123, 8888, 9000, 9090, 9091, 9200, 10000, 32400)
    private val greeting = Regex("^(\\d{3}[ -]|\\+OK |\\* OK )")

    val udpPorts: List<Int> = listOf(53, 123, 161, 1900)

    // a printer on 9100 prints whatever arrives, so a port missing from this list is only read
    fun tcpRequest(port: Int): ByteArray = when (port) {
        in httpPorts -> "HEAD / HTTP/1.0\r\n\r\n"
        6379 -> "INFO server\r\n"
        11211 -> "version\r\n"
        else -> ""
    }.encodeToByteArray()

    fun banner(reply: ByteArray): String? {
        // MySQL Protocol::HandshakeV10: 3 bytes of length, sequence 0, protocol 10, version up to a zero byte
        if (reply.size > 5 && reply[3].toInt() == 0 && reply[4].toInt() == 10) {
            return reply.drop(5).takeWhile { it.toInt() != 0 }.toByteArray().decodeToString()
        }
        val lines = reply.decodeToString().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val first = lines.firstOrNull() ?: return null
        lines.firstOrNull { it.startsWith("SSH-") }?.let { return it.take(BANNER_LIMIT) }
        lines.firstOrNull { it.startsWith("redis_version:") }?.let { return "Redis ${it.substringAfter(':')}" }
        if (first.startsWith("HTTP/")) {
            return (lines.firstOrNull { it.startsWith("server:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: first).take(BANNER_LIMIT)
        }
        if (first.any { it.code == 0xFFFD || it.isISOControl() }) return null
        return first.replace(greeting, "").take(BANNER_LIMIT)
    }

    fun udpRequest(port: Int, nowMs: Long): ByteArray = when (port) {
        53 -> DnsMessage.buildQuery(".", DnsTypes.byName.getValue("NS"), DNS_ID)
        123 -> Ntp.request(nowMs)
        161 -> Snmp.request(SnmpVersion.V2C, "public", Snmp.GET, 1, "1.3.6.1.2.1.1.1.0")
        1900 -> SSDP_SEARCH.encodeToByteArray()
        else -> "\r\n".encodeToByteArray()
    }

    fun udpAnswer(port: Int, reply: ByteArray): String? = when (port) {
        53 -> runCatching { DnsMessage.parse(reply) }.getOrNull()?.takeIf { it.id == DNS_ID && reply[2] < 0 }?.rcodeName
        123 -> Ntp.parse("", reply, 0, 0)?.let { "stratum ${it.stratum}" }
        161 -> Snmp.parse(reply)?.bindings?.firstOrNull()?.text?.lineSequence()?.first()?.take(BANNER_LIMIT)
        1900 -> ssdpHeaders(reply.decodeToString())["SERVER"]
        else -> null
    } ?: banner(reply)
}
