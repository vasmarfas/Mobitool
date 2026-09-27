@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.EINPROGRESS
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_ICMP
import platform.posix.IPPROTO_IP
import platform.posix.IP_TTL
import platform.posix.NI_MAXHOST
import platform.posix.NI_NAMEREQD
import platform.posix.NI_NUMERICHOST
import platform.posix.O_NONBLOCK
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
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_t
import platform.posix.socklen_tVar
import platform.posix.timeval
import platform.posix.uint32_tVar

actual fun platformNetCapabilities() = PlatformNetCapabilities(
    icmpPing = true,
    tcp = true,
    udp = true,
    interfaces = true,
)

@OptIn(ExperimentalForeignApi::class)
private fun resolveFirst(host: String, port: Int, socketType: Int, family: Int = AF_UNSPEC, block: (CPointer<addrinfo>) -> Unit) {
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
private fun connectWithin(descriptor: Int, address: CPointer<sockaddr>?, length: socklen_t, timeoutMs: Int): Boolean {
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
actual suspend fun icmpPing(host: String, sequence: Int, timeoutMs: Int, ttl: Int?): PingReply = withContext(Dispatchers.IO) {
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

actual suspend fun tlsHandshake(host: String, port: Int, timeoutMs: Int): TlsInfo =
    throw UnsupportedOperationException("unsupported")

@OptIn(ExperimentalForeignApi::class)
actual suspend fun whoisQuery(server: String, query: String, timeoutMs: Int): String = withContext(Dispatchers.IO) {
    var response = ""
    resolveFirst(server, 43, SOCK_STREAM) { info ->
        val descriptor = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol)
        if (descriptor >= 0) {
            setTimeout(descriptor, timeoutMs)
            if (connectWithin(descriptor, info.pointed.ai_addr, info.pointed.ai_addrlen, timeoutMs)) {
                val request = (query + "\r\n").encodeToByteArray()
                send(descriptor, request.refTo(0), request.size.convert(), 0)
                val builder = StringBuilder()
                val buffer = ByteArray(4096)
                while (true) {
                    val read = recv(descriptor, buffer.refTo(0), buffer.size.convert(), 0)
                    if (read <= 0) break
                    builder.append(buffer.decodeToString(0, read.toInt()))
                }
                response = builder.toString()
            }
            close(descriptor)
        }
    }
    response
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
actual suspend fun tcpDnsQuery(host: String, payload: ByteArray, timeoutMs: Int): ByteArray? = withContext(Dispatchers.IO) {
    var result: ByteArray? = null
    resolveFirst(host, 53, SOCK_STREAM) { info ->
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

actual suspend fun ssdpDiscover(timeoutMs: Int): List<DiscoveredDevice> = emptyList()

actual suspend fun mdnsQuery(serviceName: String, timeoutMs: Int): List<ByteArray> = emptyList()

actual suspend fun wifiDetails(): Map<String, String> = emptyMap()
