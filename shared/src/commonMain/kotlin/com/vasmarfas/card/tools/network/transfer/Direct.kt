package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.TcpServer
import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.connectTcp
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.listenTcp
import com.vasmarfas.card.core.networkInterfaces
import com.vasmarfas.card.core.parseXml
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.core.ssdpDiscover
import com.vasmarfas.card.core.tcpExchange
import com.vasmarfas.card.core.udpQuery
import com.vasmarfas.card.core.wifiDetails
import com.vasmarfas.card.tools.network.ConnectionProbe
import com.vasmarfas.card.tools.network.Ipv4
import com.vasmarfas.card.tools.network.Ipv4Subnet
import com.vasmarfas.card.tools.network.NatPmp
import com.vasmarfas.card.tools.network.Upnp
import com.vasmarfas.card.tools.network.plainGet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

@Serializable
class Offer(val alias: String = "", val direct: Boolean = false, val endpoints: List<String> = emptyList(), val udp: List<String> = emptyList())

class Reach(val listener: TcpServer, val endpoints: List<String>, val punch: Punch?, private val unmap: (suspend () -> Unit)?) {
    suspend fun close() {
        listener.close()
        punch?.close()
        unmap?.let { withContext(NonCancellable) { runCatching { withTimeoutOrNull(3_000.milliseconds) { it() } } } }
    }
}

internal suspend fun TcpStream.readFully(into: ByteArray) {
    var filled = 0
    while (filled < into.size) {
        val read = read(into, filled, into.size - filled)
        if (read < 0) throw RelayException(corrupt = true)
        filled += read
    }
}

object Direct {
    private const val LEASE = 600
    private const val SEALED = 12 + 16 + 16
    private val hello = "h".encodeToByteArray()
    private val ack = "a".encodeToByteArray()

    // random nonces start with 0xFF, the frame counter nonces of the stream start with zeros
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val nonce = secureRandomBytes(12).also { it[0] = 0xFF.toByte() }
        return nonce + ChaCha20Poly1305.seal(key, nonce, plain, aad)
    }

    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? =
        if (sealed.size < 28) null else ChaCha20Poly1305.open(key, sealed.copyOf(12), sealed.copyOfRange(12, sealed.size), aad)

    private fun globalIpv6(address: String) = ':' in address && address.substringBefore(':').toIntOrNull(16)?.let { it in 0x2000..0x3FFF } == true

    suspend fun reach(): Reach? {
        val listener = runCatching { listenTcp(0) }.getOrNull() ?: return null
        val port = listener.port
        val addresses = runCatching { networkInterfaces() }.getOrDefault(emptyList()).filter { it.isUp && !it.isLoopback }
            .flatMap { it.addresses }.map { it.substringBefore('/').substringBefore('%') }.distinct()
        val lan = addresses.filter { a -> Ipv4.parse(a)?.let { Ipv4Subnet(it, 32) }?.let { it.isPrivate || it.isCgnat } == true }
        // the iOS listener is IPv4 only
        val v6 = if (currentPlatform == PlatformKind.IOS) emptyList() else addresses.filter(::globalIpv6)
        val punch = Punch.open()
        val mapping = coroutineScope {
            val mapped = async { runCatching { withTimeoutOrNull(5_000) { mapPort(port, lan) } }.getOrNull() }
            punch?.gather(lan)
            mapped.await()
        }
        val endpoints = lan.map { "$it:$port" } + v6.map { "[$it]:$port" } + listOfNotNull(mapping?.first)
        return Reach(listener, endpoints, punch, mapping?.second)
    }

    private suspend fun mapPort(port: Int, lan: List<String>): Pair<String, suspend () -> Unit>? {
        val gateway = wifiDetails()["Gateway"]?.takeIf { Ipv4.parse(it) != null } ?: return null
        val net = Ipv4Subnet(Ipv4.parse(gateway)!!, 24).network
        val own = lan.firstOrNull { Ipv4.parse(it)?.let { a -> Ipv4Subnet(a, 24).network == net } == true } ?: return null
        fun public(ip: String?) = ip?.let(Ipv4::parse)?.let { Ipv4Subnet(it, 32).isPublic } == true

        val external = NatPmp.mappedPort(udpQuery(gateway, 5351, NatPmp.mapRequest(port, port, LEASE), 1_500))
        if (external != null) {
            val ip = NatPmp.externalAddress(udpQuery(gateway, 5351, NatPmp.request, 1_500))
            val remove: suspend () -> Unit = { udpQuery(gateway, 5351, NatPmp.mapRequest(port, 0, 0), 1_000) }
            if (public(ip)) return "$ip:$external" to remove
            remove()
        }

        val location = ssdpDiscover(2_000).filter { it.address == gateway }.firstNotNullOfOrNull { it.headers["LOCATION"] } ?: return null
        val description = runCatching { parseXml(ConnectionProbe.body(plainGet(location, 3_000))) }.getOrNull() ?: return null
        val (type, control) = Upnp.wanService(description) ?: return null
        val url = Upnp.controlUrl(location, control)
        val authority = url.substringAfter("://").substringBefore('/')
        val host = authority.substringBefore(':')
        val soapPort = authority.substringAfter(':', "80").toIntOrNull() ?: 80
        suspend fun call(action: String, arguments: List<Pair<String, String>> = emptyList()): String? =
            runCatching { tcpExchange(host, soapPort, Upnp.request(url, type, action, arguments), 3_000).decodeToString() }.getOrNull()
        val mapped = call(
            "AddPortMapping",
            listOf(
                "NewRemoteHost" to "", "NewExternalPort" to "$port", "NewProtocol" to "TCP", "NewInternalPort" to "$port",
                "NewInternalClient" to own, "NewEnabled" to "1", "NewPortMappingDescription" to "Mobitool", "NewLeaseDuration" to "$LEASE",
            ),
        )
        if (mapped?.lineSequence()?.firstOrNull()?.contains(" 200 ") != true) return null
        val remove: suspend () -> Unit = { call("DeletePortMapping", listOf("NewRemoteHost" to "", "NewExternalPort" to "$port", "NewProtocol" to "TCP")) }
        val ip = call("GetExternalIPAddress")?.let(Upnp::externalIp)
        if (public(ip)) return "$ip:$port" to remove
        remove()
        return null
    }

    fun endpoint(text: String): Pair<String, Int>? {
        val host = if (text.startsWith('[')) text.substringAfter('[').substringBefore(']') else text.substringBeforeLast(':')
        val port = text.substringAfterLast(':').toIntOrNull() ?: return null
        return host.takeIf { it.isNotEmpty() }?.let { it to port }
    }

    private suspend fun handshake(key: ByteArray, sender: Boolean, stream: TcpStream): Boolean = runCatching {
        if (sender) {
            val challenge = secureRandomBytes(16)
            stream.write(seal(key, challenge, hello))
            val answer = ByteArray(SEALED).also { stream.readFully(it) }
            open(key, answer, ack)?.contentEquals(challenge) == true
        } else {
            val question = ByteArray(SEALED).also { stream.readFully(it) }
            val challenge = open(key, question, hello) ?: return@runCatching false
            stream.write(seal(key, challenge, ack))
            ByteArray(4).also { stream.readFully(it) }.contentEquals(STREAM_MAGIC)
        }
    }.getOrDefault(false)

    // no listener here: a cancelled accept would close the socket the later link needs
    suspend fun punched(key: ByteArray, sender: Boolean, punch: Punch): TcpStream? {
        val stream = punch.connect(key, sender, emptyList())
        if (handshake(key, sender, stream)) return stream
        stream.close()
        return null
    }

    suspend fun link(key: ByteArray, sender: Boolean, reach: Reach, peer: Offer, timeoutMs: Long): TcpStream? = withTimeoutOrNull(timeoutMs.milliseconds) {
        coroutineScope {
            val winner = CompletableDeferred<TcpStream>()
            suspend fun attempt(stream: TcpStream) {
                if (!handshake(key, sender, stream) || !winner.complete(stream)) stream.close()
            }
            launch {
                while (true) {
                    val stream = reach.listener.accept()
                    launch { attempt(stream) }
                }
            }
            peer.endpoints.mapNotNull(::endpoint).forEach { (host, port) ->
                launch { runCatching { connectTcp(host, port, 4_000) }.getOrNull()?.let { attempt(it) } }
            }
            reach.punch?.let { punch ->
                launch { runCatching { punch.connect(key, sender, peer.udp) }.getOrNull()?.let { attempt(it) } }
            }
            winner.await().also { coroutineContext.cancelChildren() }
        }
    }
}
