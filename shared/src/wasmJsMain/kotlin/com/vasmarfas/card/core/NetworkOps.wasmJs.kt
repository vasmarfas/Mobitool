package com.vasmarfas.card.core

import kotlinx.coroutines.await
import kotlin.js.Promise

actual fun platformNetCapabilities() = PlatformNetCapabilities(
    icmpPing = false,
    tcp = false,
    udp = false,
    interfaces = false,
)

actual suspend fun icmpPing(host: String, sequence: Int, timeoutMs: Int, ttl: Int?): PingReply =
    PingReply(sequence, null, null, null, "unsupported")

actual suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? = null

private fun jsOpaqueHead(url: String, timeoutMs: Int): Promise<JsAny?> =
    js("fetch(url, { method: 'HEAD', mode: 'no-cors', cache: 'no-store', signal: AbortSignal.timeout(timeoutMs) })")

actual suspend fun httpPing(url: String, timeoutMs: Int): Long {
    val started = currentEpochMillis()
    jsOpaqueHead(url, timeoutMs).await<JsAny?>()
    return currentEpochMillis() - started
}

actual suspend fun resolveHost(host: String): List<String> = emptyList()

actual suspend fun reverseLookup(address: String): String? = null

actual suspend fun wakeOnLan(mac: String, broadcast: String, port: Int): Boolean = false

actual suspend fun networkInterfaces(): List<InterfaceInfo> = emptyList()

actual suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int): TlsInfo = throw UnsupportedOperationException("unsupported")

actual suspend fun whoisQuery(server: String, query: String, timeoutMs: Int): String = throw UnsupportedOperationException("unsupported")

actual suspend fun udpQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray? = null

actual suspend fun tcpDnsQuery(host: String, payload: ByteArray, timeoutMs: Int): ByteArray? = null

actual suspend fun ssdpDiscover(timeoutMs: Int): List<DiscoveredDevice> = emptyList()

actual suspend fun mdnsQuery(serviceName: String, timeoutMs: Int): List<ByteArray> = emptyList()

actual suspend fun wifiDetails(): Map<String, String> = emptyMap()
