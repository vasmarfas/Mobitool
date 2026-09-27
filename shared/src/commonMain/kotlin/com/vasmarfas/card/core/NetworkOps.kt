package com.vasmarfas.card.core

data class PingReply(
    val sequence: Int,
    val timeMs: Double?,
    val ttl: Int?,
    val from: String?,
    val error: String? = null,
)

data class TracerouteHop(
    val hop: Int,
    val address: String?,
    val hostname: String?,
    val timeMs: Double?,
)

data class PortProbe(
    val port: Int,
    val open: Boolean,
    val timeMs: Long,
)

data class LanHost(
    val address: String,
    val hostname: String?,
    val timeMs: Long,
    val openPorts: List<Int>,
)

data class InterfaceInfo(
    val name: String,
    val displayName: String,
    val mac: String?,
    val addresses: List<String>,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val mtu: Int,
)

data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val serial: String,
    val signatureAlgorithm: String,
    val publicKey: String,
    val subjectAltNames: List<String>,
    val sha256: String,
    val sha1: String,
    val version: Int,
    val expired: Boolean,
    val daysLeft: Long,
)

enum class CertTrust { TRUSTED, EXPIRED, NOT_YET_VALID, UNTRUSTED }

data class TlsInfo(
    val protocol: String,
    val cipherSuite: String,
    val chain: List<CertificateInfo>,
    val trust: CertTrust,
)

data class DiscoveredDevice(
    val address: String,
    val headers: Map<String, String>,
)

object NetCapabilities {
    val icmpPing: Boolean get() = platformNetCapabilities().icmpPing
    val tcp: Boolean get() = platformNetCapabilities().tcp
    val udp: Boolean get() = platformNetCapabilities().udp
    val interfaces: Boolean get() = platformNetCapabilities().interfaces
}

data class PlatformNetCapabilities(
    val icmpPing: Boolean,
    val tcp: Boolean,
    val udp: Boolean,
    val interfaces: Boolean,
)

expect fun platformNetCapabilities(): PlatformNetCapabilities

expect suspend fun icmpPing(host: String, sequence: Int, timeoutMs: Int, ttl: Int? = null): PingReply

expect suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long?

expect suspend fun httpPing(url: String, timeoutMs: Int): Long

expect suspend fun resolveHost(host: String): List<String>

expect suspend fun reverseLookup(address: String): String?

expect suspend fun wakeOnLan(mac: String, broadcast: String, port: Int): Boolean

expect suspend fun networkInterfaces(): List<InterfaceInfo>

expect suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int): TlsInfo

expect suspend fun whoisQuery(server: String, query: String, timeoutMs: Int): String

expect suspend fun udpQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray?

expect suspend fun tcpDnsQuery(host: String, payload: ByteArray, timeoutMs: Int): ByteArray?

expect suspend fun ssdpDiscover(timeoutMs: Int): List<DiscoveredDevice>

expect suspend fun mdnsQuery(serviceName: String, timeoutMs: Int): List<ByteArray>

expect suspend fun wifiDetails(): Map<String, String>
