@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import com.vasmarfas.card.resources.*
import kotlin.concurrent.Volatile
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.AF_INET
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_REUSEADDR
import platform.posix.bind
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.accept as acceptSocket
import platform.posix.close as closeSocket

private suspend fun <T> blocking(close: () -> Unit, block: () -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { close() }
    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { cont.resumeWith(runCatching(block)) })
}

private fun swapPort(port: Int): Int = ((port and 0xFF) shl 8) or ((port shr 8) and 0xFF)

private fun option(fd: Int, name: Int) = memScoped {
    val one = alloc<IntVar>().apply { value = 1 }
    setsockopt(fd, SOL_SOCKET, name, one.ptr, sizeOf<IntVar>().convert())
}

// closing a descriptor does not wake a thread blocked on it in Darwin, so waits poll in short steps and look at the flag
private class Descriptor(val fd: Int) {
    @Volatile
    private var closed = false

    fun await(events: Int) = memScoped {
        val request = alloc<pollfd>()
        request.fd = fd
        request.events = events.convert()
        while (true) {
            if (closed) error("socket closed")
            request.revents = 0
            val ready = poll(request.ptr, 1u, 250)
            if (ready > 0) return@memScoped
            if (ready < 0 && errno != EINTR) error("poll failed: $errno")
        }
    }

    fun close() {
        if (closed) return
        closed = true
        shutdown(fd, SHUT_RDWR)
        closeSocket(fd)
    }
}

private class PosixStream(private val socket: Descriptor, override val remoteHost: String) : TcpStream {
    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = blocking(::close) {
        if (length == 0) return@blocking 0
        socket.await(POLLIN)
        val read = buffer.usePinned { recv(socket.fd, it.addressOf(offset), length.convert(), 0) }.toInt()
        when {
            read > 0 -> read
            read == 0 -> -1
            else -> error("recv failed: $errno")
        }
    }

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = blocking(::close) {
        var sent = 0
        while (sent < length) {
            socket.await(POLLOUT)
            val written = bytes.usePinned { send(socket.fd, it.addressOf(offset + sent), (length - sent).convert(), 0) }.toInt()
            if (written < 0 && errno != EINTR) error("send failed: $errno")
            if (written > 0) sent += written
        }
    }

    override fun close() = socket.close()
}

private fun bindAny(fd: Int, port: Int, stream: Boolean): Int = memScoped {
    val address = alloc<sockaddr_in>()
    address.sin_len = sizeOf<sockaddr_in>().convert()
    address.sin_family = AF_INET.convert()
    address.sin_port = swapPort(port).convert()
    address.sin_addr.s_addr = 0u
    val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
    when {
        bind(fd, address.ptr.reinterpret(), length.value) != 0 || stream && listen(fd, 32) != 0 -> -1
        getsockname(fd, address.ptr.reinterpret(), length.ptr) != 0 -> -1
        else -> swapPort(address.sin_port.toInt())
    }
}

private fun dotted(address: sockaddr_in): String {
    val ip = address.sin_addr.s_addr.toInt()
    return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip ushr 24}"
}

actual fun listenTcp(port: Int): TcpServer {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    check(fd >= 0) { "no socket" }
    option(fd, SO_REUSEADDR)
    val bound = bindAny(fd, port, stream = true)
    if (bound < 0) {
        closeSocket(fd)
        error("port $port is busy")
    }
    val listener = Descriptor(fd)
    return object : TcpServer {
        override val port = bound

        override suspend fun accept(): TcpStream = blocking(listener::close) {
            listener.await(POLLIN)
            memScoped {
                val address = alloc<sockaddr_in>()
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                val client = acceptSocket(fd, address.ptr.reinterpret(), length.ptr)
                if (client < 0) error("accept failed: $errno")
                option(client, SO_NOSIGPIPE)
                PosixStream(Descriptor(client), dotted(address))
            }
        }

        override fun close() = listener.close()
    }
}

actual suspend fun connectTcp(host: String, port: Int, timeoutMs: Int): TcpStream = blocking({}) {
    var stream: TcpStream? = null
    resolveFirst(host, port, SOCK_STREAM, AF_INET) { info ->
        val fd = socket(info.pointed.ai_family, info.pointed.ai_socktype, info.pointed.ai_protocol)
        if (fd >= 0) {
            if (connectWithin(fd, info.pointed.ai_addr, info.pointed.ai_addrlen, timeoutMs)) {
                option(fd, SO_NOSIGPIPE)
                stream = PosixStream(Descriptor(fd), host)
            } else {
                closeSocket(fd)
            }
        }
    }
    stream ?: throw NetException(Res.string.net_error_refused)
}

actual fun joinMulticast(group: String, port: Int): MulticastGroup? = null

actual fun openUdp(port: Int): UdpSocket {
    val fd = socket(AF_INET, SOCK_DGRAM, 0)
    check(fd >= 0) { "no socket" }
    val bound = bindAny(fd, port, stream = false)
    if (bound < 0) {
        closeSocket(fd)
        error("port $port is busy")
    }
    val descriptor = Descriptor(fd)
    return object : UdpSocket {
        override val port = bound

        override suspend fun send(host: String, port: Int, data: ByteArray) {
            val parts = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { part -> part in 0..255 } }
            if (parts.size != 4 || data.isEmpty()) return
            memScoped {
                val address = alloc<sockaddr_in>()
                address.sin_len = sizeOf<sockaddr_in>().convert()
                address.sin_family = AF_INET.convert()
                address.sin_port = swapPort(port).convert()
                address.sin_addr.s_addr = (parts[0] or (parts[1] shl 8) or (parts[2] shl 16) or (parts[3] shl 24)).toUInt()
                data.usePinned { sendto(fd, it.addressOf(0), data.size.convert(), 0, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) }
            }
        }

        override suspend fun receive(): UdpPacket = blocking(descriptor::close) {
            descriptor.await(POLLIN)
            memScoped {
                val buffer = ByteArray(2048)
                val address = alloc<sockaddr_in>()
                val length = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                val read = buffer.usePinned { recvfrom(fd, it.addressOf(0), buffer.size.convert(), 0, address.ptr.reinterpret(), length.ptr) }.toInt()
                if (read < 0) error("recvfrom failed: $errno")
                UdpPacket(dotted(address), swapPort(address.sin_port.toInt()), buffer.copyOf(read))
            }
        }

        override fun close() = descriptor.close()
    }
}
