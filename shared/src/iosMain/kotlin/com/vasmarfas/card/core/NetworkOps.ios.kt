@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import com.vasmarfas.card.resources.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.Network.NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT
import platform.Network.NW_CONNECTION_FINAL_MESSAGE_CONTEXT
import platform.Network._nw_parameters_configure_protocol_default_configuration
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_create
import platform.Network.nw_connection_receive
import platform.Network.nw_connection_send
import platform.Network.nw_connection_set_queue
import platform.Network.nw_connection_set_state_changed_handler
import platform.Network.nw_connection_start
import platform.Network.nw_connection_state_failed
import platform.Network.nw_connection_state_ready
import platform.Network.nw_connection_state_waiting
import platform.Network.nw_content_context_t
import platform.Network.nw_endpoint_create_host
import platform.Network.nw_error_domain_dns
import platform.Network.nw_error_domain_tls
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
import platform.Network.nw_parameters_create_quic
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_parameters_t
import platform.Network.nw_quic_add_tls_application_protocol
import platform.NetworkExtension.NEHotspotNetwork
import platform.darwin.CTL_NET
import platform.darwin.dispatch_data_apply
import platform.darwin.dispatch_data_create
import platform.darwin.dispatch_data_get_size
import platform.darwin.dispatch_data_t
import platform.darwin.dispatch_queue_create
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.sysctl
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.ECONNREFUSED
import platform.posix.EHOSTUNREACH
import platform.posix.EINPROGRESS
import platform.posix.ENETUNREACH
import platform.posix.ETIMEDOUT
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_ICMP
import platform.posix.IPPROTO_IP
import platform.posix.IP_TTL
import platform.posix.NET_RT_FLAGS
import platform.posix.NI_MAXHOST
import platform.posix.NI_NAMEREQD
import platform.posix.NI_NUMERICHOST
import platform.posix.O_NONBLOCK
import platform.posix.PF_ROUTE
import platform.posix.POLLOUT
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_ERROR
import platform.posix.SO_RCVTIMEO
import platform.posix.addrinfo
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.posix.getpid
import platform.posix.getsockopt
import platform.posix.memcpy
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.size_tVar
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_t
import platform.posix.socklen_tVar
import platform.posix.timeval
import platform.posix.uint32_tVar
import kotlin.time.Duration.Companion.milliseconds

actual fun platformNetCapabilities() = PlatformNetCapabilities(
    icmpPing = true,
    tcp = true,
    udp = true,
    interfaces = true,
    dnsOverTls = true,
    dnsOverQuic = true,
)

@OptIn(ExperimentalForeignApi::class)
internal fun resolveFirst(host: String, port: Int, socketType: Int, family: Int = AF_UNSPEC, block: (CPointer<addrinfo>) -> Unit) {
    memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = family
        hints.ai_socktype = socketType
        val result = allocPointerTo<addrinfo>()
        if (getaddrinfo(host, port.toString(), hints.ptr, result.ptr) != 0) return
        val first = result.value ?: return
        try {
            block(first)
        } finally {
            freeaddrinfo(first)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun setTimeout(descriptor: Int, timeoutMs: Int) {
    memScoped {
        val tv = alloc<timeval>()
        tv.tv_sec = (timeoutMs / 1000).convert()
        tv.tv_usec = ((timeoutMs % 1000) * 1000).convert()
        setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
    }
}

// SO_RCVTIMEO only governs recv, so a blocking connect() ignores timeoutMs and runs to the
// kernel's own TCP timeout; O_NONBLOCK plus poll() makes the deadline real, and the original
// flags go back so the caller's later recv() still honours SO_RCVTIMEO
@OptIn(ExperimentalForeignApi::class)
internal fun connectWithin(descriptor: Int, address: CPointer<sockaddr>?, length: socklen_t, timeoutMs: Int): Boolean {
    val flags = fcntl(descriptor, F_GETFL, 0)
    if (flags < 0 || fcntl(descriptor, F_SETFL, flags or O_NONBLOCK) < 0) return false
    val connected = when {
        connect(descriptor, address, length) == 0 -> true
        errno != EINPROGRESS -> false
        else -> memScoped {
            val fds = alloc<pollfd>()
            fds.fd = descriptor
            fds.events = POLLOUT.convert()
            fds.revents = 0
            if (poll(fds.ptr, 1.convert(), timeoutMs) <= 0) return@memScoped false
            val error = alloc<IntVar>()
            val size = alloc<socklen_tVar>()
            size.value = sizeOf<IntVar>().convert()
            getsockopt(descriptor, SOL_SOCKET, SO_ERROR, error.ptr, size.ptr) == 0 && error.value == 0
        }
    }
    fcntl(descriptor, F_SETFL, flags)
    return connected
}

private const val ICMP_ECHO_REPLY = 0
private const val ICMP_UNREACHABLE = 3
private const val ICMP_ECHO_REQUEST = 8
private const val ICMP_TIME_EXCEEDED = 11
private const val ICMP_PAYLOAD = 56

private fun ByteArray.putShort(index: Int, value: Int) {
    this[index] = (value shr 8).toByte()
    this[index + 1] = value.toByte()
}

private fun ByteArray.shortAt(index: Int): Int = ((this[index].toInt() and 0xFF) shl 8) or (this[index + 1].toInt() and 0xFF)

private fun icmpChecksum(packet: ByteArray): Int {
    var sum = 0
    var i = 0
    while (i + 1 < packet.size) {
        sum += packet.shortAt(i)
        i += 2
    }
    if (i < packet.size) sum += (packet[i].toInt() and 0xFF) shl 8
    while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
    return sum.inv() and 0xFFFF
}

private fun icmpEchoRequest(identifier: Int, sequence: Int): ByteArray {
    val packet = ByteArray(8 + ICMP_PAYLOAD)
    packet[0] = ICMP_ECHO_REQUEST.toByte()
    packet.putShort(4, identifier)
    packet.putShort(6, sequence)
    for (i in 0 until ICMP_PAYLOAD) packet[8 + i] = i.toByte()
    packet.putShort(2, icmpChecksum(packet))
    return packet
}

private fun icmpOffset(packet: ByteArray): Int {
    if (packet.size < 8) return -1
    val first = packet[0].toInt() and 0xFF
    if (first == ICMP_ECHO_REPLY || first == ICMP_TIME_EXCEEDED || first == ICMP_UNREACHABLE) return 0
    if (first shr 4 != 4) return -1
    val headerLength = (first and 0x0F) * 4
    return if (headerLength >= 20 && packet.size >= headerLength + 8) headerLength else -1
}

private fun echoSequence(packet: ByteArray, offset: Int, type: Int): Int? {
    if (type == ICMP_ECHO_REPLY) return packet.shortAt(offset + 6)
    val quoted = offset + 8
    if (packet.size < quoted + 20) return null
    val inner = quoted + (packet[quoted].toInt() and 0x0F) * 4
    if (packet.size < inner + 8 || (packet[inner].toInt() and 0xFF) != ICMP_ECHO_REQUEST) return null
    return packet.shortAt(inner + 6)
}

private fun quotedDestination(packet: ByteArray, offset: Int): String? {
    val quoted = offset + 8
    if (packet.size < quoted + 20) return null
    return (0 until 4).joinToString(".") { (packet[quoted + 16 + it].toInt() and 0xFF).toString() }
}

@OptIn(ExperimentalForeignApi::class)
private fun numericHost(address: CPointer<sockaddr>, length: UInt): String? = memScoped {
    val buffer = allocArray<ByteVar>(NI_MAXHOST)
    if (getnameinfo(address, length.convert(), buffer, NI_MAXHOST.convert(), null, 0u, NI_NUMERICHOST) != 0) return@memScoped null
    buffer.toKString().takeIf { it.isNotBlank() }
}

@OptIn(ExperimentalForeignApi::class)
private fun awaitIcmpReply(descriptor: Int, sequence: Int, timeoutMs: Int, target: String?, started: TimeMark): PingReply {
    val buffer = ByteArray(1500)
    memScoped {
        val from = alloc<sockaddr_storage>()
        val fromLength = alloc<socklen_tVar>()
        while (true) {
            val remaining = timeoutMs - started.elapsedNow().inWholeMilliseconds
            if (remaining <= 0) break
            setTimeout(descriptor, remaining.toInt())
            fromLength.value = sizeOf<sockaddr_storage>().convert()
            val read = recvfrom(descriptor, buffer.refTo(0), buffer.size.convert(), 0, from.ptr.reinterpret<sockaddr>(), fromLength.ptr)
            if (read <= 0) break
            val elapsed = started.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
            val packet = buffer.copyOf(read.toInt())
            val offset = icmpOffset(packet)
            if (offset < 0) continue
            val type = packet[offset].toInt() and 0xFF
            if (echoSequence(packet, offset, type) != (sequence and 0xFFFF)) continue
            val sender = numericHost(from.ptr.reinterpret<sockaddr>(), fromLength.value.convert())
            val pinged = if (type == ICMP_ECHO_REPLY) sender else quotedDestination(packet, offset)
            if (target != null && pinged != target) continue
            val replyTtl = if (offset > 0) packet[8].toInt() and 0xFF else null
            return when (type) {
                ICMP_ECHO_REPLY -> PingReply(sequence, elapsed, replyTtl, sender, null)
                ICMP_TIME_EXCEEDED -> PingReply(sequence, elapsed, replyTtl, sender, "ttl-expired")
                else -> PingReply(sequence, null, null, sender, "unreachable")
            }
        }
    }
    return PingReply(sequence, null, null, null, null)
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun icmpPing(host: String, sequence: Int, timeoutMs: Int, ttl: Int?, size: Int?): PingReply = withContext(Dispatchers.IO) {
    var reply = PingReply(sequence, null, null, null, null)
    resolveFirst(host, 0, SOCK_DGRAM, AF_INET) { info ->
        val target = info.pointed.ai_addr?.let { numericHost(it, info.pointed.ai_addrlen.convert()) }
        val descriptor = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)
        if (descriptor < 0) {
            reply = PingReply(sequence, null, null, null, "socket failed")
            return@resolveFirst
        }
        if (ttl != null) {
            memScoped {
                val hops = alloc<IntVar>()
                hops.value = ttl
                setsockopt(descriptor, IPPROTO_IP, IP_TTL, hops.ptr, sizeOf<IntVar>().convert())
            }
        }
        val request = icmpEchoRequest(getpid() and 0xFFFF, sequence and 0xFFFF)
        val started = TimeSource.Monotonic.markNow()
        val written = sendto(descriptor, request.refTo(0), request.size.convert(), 0, info.pointed.ai_addr, info.pointed.ai_addrlen)
        reply = if (written <= 0) {
            PingReply(sequence, null, null, null, "send failed")
        } else {
            awaitIcmpReply(descriptor, sequence, timeoutMs, target, started)
        }
        close(descriptor)
    }
    reply
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun tcpConnect(host: String, port: Int, timeoutMs: Int): Long? = withContext(Dispatchers.IO) {
    var result: Long? = null
    val started = currentEpochMillis()
    resolveFirst(host, port, SOCK_STREAM) { info ->
        val descriptor = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol)
        if (descriptor >= 0) {
            setTimeout(descriptor, timeoutMs)
            if (connectWithin(descriptor, info.pointed.ai_addr, info.pointed.ai_addrlen, timeoutMs)) {
                result = currentEpochMillis() - started
            }
            close(descriptor)
        }
    }
    result
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun resolveHost(host: String): List<String> = withContext(Dispatchers.IO) {
    val addresses = mutableListOf<String>()
    memScoped {
        val hints = alloc<addrinfo>()
        hints.ai_family = AF_UNSPEC
        hints.ai_socktype = SOCK_STREAM
        val result = allocPointerTo<addrinfo>()
        if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return@withContext emptyList()
        var current = result.value
        val head = current
        while (current != null) {
            val buffer = allocArray<ByteVar>(NI_MAXHOST)
            val addr = current.pointed.ai_addr
            if (addr != null) {
                val ok = getnameinfo(addr, current.pointed.ai_addrlen, buffer, NI_MAXHOST.convert(), null, 0u, NI_NUMERICHOST)
                if (ok == 0) buffer.toKString().takeIf { it.isNotBlank() }?.let { addresses.add(it) }
            }
            current = current.pointed.ai_next
        }
        if (head != null) freeaddrinfo(head)
    }
    addresses.distinct()
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun reverseLookup(address: String): String? = withContext(Dispatchers.IO) {
    var name: String? = null
    resolveFirst(address, 0, SOCK_STREAM) { info ->
        memScoped {
            val buffer = allocArray<ByteVar>(NI_MAXHOST)
            val addr = info.pointed.ai_addr
            if (addr != null && getnameinfo(addr, info.pointed.ai_addrlen, buffer, NI_MAXHOST.convert(), null, 0u, NI_NAMEREQD) == 0) {
                name = buffer.toKString().takeIf { it.isNotBlank() && it != address }
            }
        }
    }
    name
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun wakeOnLan(mac: String, broadcast: String, port: Int): Boolean = withContext(Dispatchers.IO) {
    val bytes = mac.replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }
    if (bytes.size != 6) return@withContext false
    val packet = ByteArray(6 + 16 * 6) { i -> if (i < 6) 0xFF.toByte() else bytes[(i - 6) % 6] }
    var sent = false
    resolveFirst(broadcast, port, SOCK_DGRAM) { info ->
        val descriptor = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol)
        if (descriptor >= 0) {
            memScoped {
                val enable = alloc<uint32_tVar>()
                enable.value = 1u
                setsockopt(descriptor, SOL_SOCKET, SO_BROADCAST, enable.ptr, sizeOf<uint32_tVar>().convert())
            }
            if (connect(descriptor, info.pointed.ai_addr, info.pointed.ai_addrlen) == 0) {
                val written = send(descriptor, packet.refTo(0), packet.size.convert(), 0)
                sent = written > 0
            }
            close(descriptor)
        }
    }
    sent
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun networkInterfaces(): List<InterfaceInfo> {
    val byName = LinkedHashMap<String, MutableList<String>>()
    memScoped {
        val list = allocPointerTo<ifaddrs>()
        if (getifaddrs(list.ptr) != 0) return emptyList()
        var current = list.value
        val head = current
        while (current != null) {
            val entry = current.pointed
            val name = entry.ifa_name?.toKString()
            val addr = entry.ifa_addr
            if (name != null && addr != null) {
                val family = addr.pointed.sa_family.toInt()
                if (family == AF_INET || family == AF_INET6) {
                    val buffer = allocArray<ByteVar>(NI_MAXHOST)
                    val length = if (family == AF_INET) sizeOf<sockaddr_in>() else sizeOf<sockaddr_in6>()
                    if (getnameinfo(addr, length.convert(), buffer, NI_MAXHOST.convert(), null, 0u, NI_NUMERICHOST) == 0) {
                        val text = buffer.toKString().substringBefore('%')
                        if (text.isNotBlank()) byName.getOrPut(name) { mutableListOf() }.add(text)
                    }
                }
            }
            current = entry.ifa_next
        }
        if (head != null) freeifaddrs(head)
    }
    return byName.map { (name, addresses) ->
        InterfaceInfo(
            name = name,
            displayName = name,
            mac = null,
            addresses = addresses,
            isUp = true,
            isLoopback = name.startsWith("lo"),
            mtu = 0,
        )
    }
}

actual suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int, serverName: String): TlsInfo =
    throw UnsupportedOperationException("unsupported")

@OptIn(ExperimentalForeignApi::class)
actual suspend fun tcpExchange(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
    var response: ByteArray? = null
    resolveFirst(host, port, SOCK_STREAM) { info ->
        val descriptor = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol)
        if (descriptor >= 0) {
            setTimeout(descriptor, timeoutMs)
            if (connectWithin(descriptor, info.pointed.ai_addr, info.pointed.ai_addrlen, timeoutMs)) {
                if (payload.isNotEmpty()) send(descriptor, payload.refTo(0), payload.size.convert(), 0)
                var received = ByteArray(0)
                val buffer = ByteArray(8192)
                while (received.size < MAX_EXCHANGE_BYTES) {
                    val read = recv(descriptor, buffer.refTo(0), buffer.size.convert(), 0)
                    if (read <= 0) break
                    received += buffer.copyOf(read.toInt())
                }
                response = received
            }
            close(descriptor)
        }
    }
    response ?: throw NetException(Res.string.net_error_refused)
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun udpQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray? = withContext(Dispatchers.IO) {
    var result: ByteArray? = null
    resolveFirst(host, port, SOCK_DGRAM) { info ->
        val descriptor = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol)
        if (descriptor >= 0) {
            setTimeout(descriptor, timeoutMs)
            if (connect(descriptor, info.pointed.ai_addr, info.pointed.ai_addrlen) == 0) {
                send(descriptor, payload.refTo(0), payload.size.convert(), 0)
                val buffer = ByteArray(4096)
                val read = recv(descriptor, buffer.refTo(0), buffer.size.convert(), 0)
                if (read > 0) result = buffer.copyOf(read.toInt())
            }
            close(descriptor)
        }
    }
    result
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun udpExchanges(targets: List<Pair<String, Int>>, payload: ByteArray, timeoutMs: Int): List<ByteArray?> = withContext(Dispatchers.IO) {
    val descriptor = socket(AF_INET, SOCK_DGRAM, 0)
    if (descriptor < 0) return@withContext targets.map { null }
    setTimeout(descriptor, timeoutMs)
    val replies = targets.map { (host, port) ->
        var reply: ByteArray? = null
        resolveFirst(host, port, SOCK_DGRAM, AF_INET) { info ->
            if (sendto(descriptor, payload.refTo(0), payload.size.convert(), 0, info.pointed.ai_addr, info.pointed.ai_addrlen) > 0) {
                val buffer = ByteArray(2048)
                val read = recv(descriptor, buffer.refTo(0), buffer.size.convert(), 0)
                if (read > 0) reply = buffer.copyOf(read.toInt())
            }
        }
        reply
    }
    close(descriptor)
    replies
}

actual suspend fun browserIceCandidates(stunServers: List<String>, timeoutMs: Int): List<String> = emptyList()

actual suspend fun browserJsonp(url: String, timeoutMs: Int): String? = null

@OptIn(ExperimentalForeignApi::class)
private fun receiveExactly(descriptor: Int, count: Int): ByteArray? {
    val buffer = ByteArray(count)
    var filled = 0
    while (filled < count) {
        val read = recv(descriptor, buffer.refTo(filled), (count - filled).convert(), 0)
        if (read <= 0) return null
        filled += read.toInt()
    }
    return buffer
}

@OptIn(ExperimentalForeignApi::class)
actual suspend fun tcpDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray? = withContext(Dispatchers.IO) {
    var result: ByteArray? = null
    resolveFirst(host, port, SOCK_STREAM) { info ->
        val descriptor = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol)
        if (descriptor >= 0) {
            setTimeout(descriptor, timeoutMs)
            if (connectWithin(descriptor, info.pointed.ai_addr, info.pointed.ai_addrlen, timeoutMs)) {
                val request = byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()) + payload
                send(descriptor, request.refTo(0), request.size.convert(), 0)
                val length = receiveExactly(descriptor, 2)
                if (length != null) result = receiveExactly(descriptor, ((length[0].toInt() and 0xFF) shl 8) or (length[1].toInt() and 0xFF))
            }
            close(descriptor)
        }
    }
    result
}

actual suspend fun tlsDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray {
    val parameters = nw_parameters_create_secure_tcp(
        _nw_parameters_configure_protocol_default_configuration,
        _nw_parameters_configure_protocol_default_configuration,
    )
    return framedDnsQuery(host, port, parameters, NW_CONNECTION_DEFAULT_MESSAGE_CONTEXT, payload, timeoutMs)
}

// a QUIC nw_connection is one bidirectional stream, and the final-send context ends it with the FIN
// that RFC 9250 asks for after the query
actual suspend fun quicDnsQuery(host: String, port: Int, payload: ByteArray, timeoutMs: Int): ByteArray {
    val parameters = nw_parameters_create_quic { options -> nw_quic_add_tls_application_protocol(options, "doq") }
    return framedDnsQuery(host, port, parameters, NW_CONNECTION_FINAL_MESSAGE_CONTEXT, payload, timeoutMs)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun framedDnsQuery(
    host: String,
    port: Int,
    parameters: nw_parameters_t,
    sendContext: nw_content_context_t,
    payload: ByteArray,
    timeoutMs: Int,
): ByteArray {
    val connection = nw_connection_create(nw_endpoint_create_host(host, port.toString()), parameters)
    val queue = dispatch_queue_create("com.vasmarfas.dns", null)
    nw_connection_set_queue(connection, queue)
    try {
        return withTimeoutOrNull(timeoutMs.toLong().milliseconds) {
            suspendCancellableCoroutine<ByteArray> { continuation ->
                var received = ByteArray(0)
                fun fail(error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                fun receive() {
                    nw_connection_receive(connection, 1u, 65537u) { content, _, complete, error ->
                        if (content != null) received += content.toByteArray()
                        val length = if (received.size >= 2) ((received[0].toInt() and 0xFF) shl 8) or (received[1].toInt() and 0xFF) else -1
                        when {
                            length >= 0 && received.size >= 2 + length -> if (continuation.isActive) continuation.resume(received.copyOfRange(2, 2 + length))
                            error != null -> fail(error.toNetException())
                            complete -> fail(NetException(Res.string.request_failed))
                            else -> receive()
                        }
                    }
                }
                nw_connection_set_state_changed_handler(connection) { state, error ->
                    when (state) {
                        nw_connection_state_ready -> {
                            val framed = byteArrayOf((payload.size shr 8).toByte(), payload.size.toByte()) + payload
                            val content = framed.usePinned { dispatch_data_create(it.addressOf(0), framed.size.convert(), queue, null) }
                            nw_connection_send(connection, content, sendContext, true) { sendError -> sendError?.let { fail(it.toNetException()) } }
                            receive()
                        }
                        nw_connection_state_failed, nw_connection_state_waiting -> error?.let { fail(it.toNetException()) }
                        else -> Unit
                    }
                }
                nw_connection_start(connection)
            }
        } ?: throw NetException(Res.string.net_error_timeout)
    } finally {
        nw_connection_cancel(connection)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun dispatch_data_t.toByteArray(): ByteArray {
    val bytes = ByteArray(dispatch_data_get_size(this).toInt())
    bytes.usePinned { pinned ->
        dispatch_data_apply(this) { _, offset, buffer, size ->
            memcpy(pinned.addressOf(offset.toInt()), buffer, size)
            true
        }
    }
    return bytes
}

private val certificateErrors = setOf(-9807, -9808, -9812, -9813, -9814, -9815, -9843)

private fun nw_error_t.toNetException(): NetException {
    val code = nw_error_get_error_code(this)
    val reason = when (nw_error_get_error_domain(this)) {
        nw_error_domain_dns -> Res.string.net_error_unknown_host
        nw_error_domain_tls -> if (code in certificateErrors) Res.string.net_error_certificate else Res.string.net_error_tls
        else -> when (code) {
            ETIMEDOUT -> Res.string.net_error_timeout
            ECONNREFUSED, EHOSTUNREACH, ENETUNREACH -> Res.string.net_error_refused
            else -> Res.string.request_failed
        }
    }
    return NetException(reason)
}

actual suspend fun ssdpDiscover(timeoutMs: Int): List<DiscoveredDevice> = emptyList()

actual suspend fun mdnsQuery(serviceName: String, timeoutMs: Int): List<ByteArray> = emptyList()

actual suspend fun wifiDetails(): Map<String, String> = buildMap {
    currentHotspot()?.let {
        put("SSID", it.SSID)
        put("BSSID", it.BSSID.uppercase())
    }
    defaultGateway()?.let { put("Gateway", it) }
}

// answers only with the wifi-info entitlement and location access, otherwise nil
private suspend fun currentHotspot(): NEHotspotNetwork? = withTimeoutOrNull(3_000.milliseconds) {
    suspendCancellableCoroutine { cont -> NEHotspotNetwork.fetchCurrentWithCompletionHandler { cont.resume(it) } }
}

private const val RTF_GATEWAY = 0x2
private const val RT_MSGHDR_SIZE = 92

// the routing table from sysctl is a run of rt_msghdr records, each followed by the sockaddrs its
// rtm_addrs bits name, padded to 4 bytes; net/route.h is not in the iOS SDK, so the offsets are spelled out
@OptIn(ExperimentalForeignApi::class)
private fun defaultGateway(): String? = memScoped {
    val mib = allocArray<IntVar>(6)
    intArrayOf(CTL_NET, PF_ROUTE, 0, AF_INET, NET_RT_FLAGS, RTF_GATEWAY).forEachIndexed { i, value -> mib[i] = value }
    val size = alloc<size_tVar>()
    if (sysctl(mib, 6u, null, size.ptr, null, 0u) != 0 || size.value == 0uL) return null
    val table = ByteArray(size.value.toInt())
    if (table.usePinned { sysctl(mib, 6u, it.addressOf(0), size.ptr, null, 0u) } != 0) return null
    val length = size.value.toInt().coerceAtMost(table.size)
    fun u8(at: Int) = table[at].toInt() and 0xFF
    fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
    fun i32(at: Int) = u16(at) or (u16(at + 2) shl 16)
    var record = 0
    while (record + RT_MSGHDR_SIZE <= length) {
        val recordLength = u16(record)
        if (recordLength == 0) break
        val present = i32(record + 12)
        var at = record + RT_MSGHDR_SIZE
        var defaultRoute = false
        for (slot in 0 until 8) {
            if (present and (1 shl slot) == 0) continue
            if (at + 8 > length) break
            val saLength = u8(at)
            when (slot) {
                0 -> defaultRoute = saLength < 8 || (4 until 8).all { table[at + it] == 0.toByte() }
                1 -> if (defaultRoute && u8(at + 1) == AF_INET) return (4 until 8).joinToString(".") { u8(at + it).toString() }
            }
            at += if (saLength == 0) 4 else (saLength + 3) and 3.inv()
        }
        record += recordLength
    }
    null
}

actual suspend fun scanWifi(): WifiScan = WifiScan(emptyList(), false, WifiProblem.UNSUPPORTED)

actual fun wifiScanLimit(): ScanLimit? = null

actual fun openScanLimitSettings() = Unit
