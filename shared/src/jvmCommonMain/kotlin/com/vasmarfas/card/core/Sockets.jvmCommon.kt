package com.vasmarfas.card.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

// blocking socket calls ignore cancellation, closing the socket is what wakes them up
private suspend fun <T> blocking(close: () -> Unit, block: () -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { close() }
    Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { cont.resumeWith(runCatching(block)) })
}

private class SocketStream(private val socket: Socket) : TcpStream {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    override val remoteHost: String = socket.inetAddress?.hostAddress.orEmpty()

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int = blocking(::close) { input.read(buffer, offset, length) }

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) = blocking(::close) { output.write(bytes, offset, length) }

    override fun close() {
        runCatching { socket.close() }
    }
}

actual fun listenTcp(port: Int): TcpServer {
    val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }
    return object : TcpServer {
        override val port = server.localPort

        override suspend fun accept(): TcpStream = SocketStream(blocking(::close) { server.accept().apply { tcpNoDelay = true } })

        override fun close() {
            runCatching { server.close() }
        }
    }
}

actual fun openUdp(port: Int): UdpSocket {
    val socket = DatagramSocket(port)
    return object : UdpSocket {
        override val port = socket.localPort

        // Android counts a datagram send on the main thread as network access there and throws
        override suspend fun send(host: String, port: Int, data: ByteArray) = withContext(Dispatchers.IO) {
            socket.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
        }

        override suspend fun receive(): UdpPacket {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            blocking(::close) { socket.receive(packet) }
            return UdpPacket(packet.address.hostAddress.orEmpty(), packet.port, packet.data.copyOf(packet.length))
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }
}

actual suspend fun connectTcp(host: String, port: Int, timeoutMs: Int): TcpStream {
    val socket = Socket()
    blocking(socket::close) {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.tcpNoDelay = true
    }
    return SocketStream(socket)
}

// with a VPN or virtual adapters the route table picks one interface, so every IPv4 one joins and sends
internal class JavaMulticast(group: String, private val port: Int, private val release: () -> Unit) : MulticastGroup {
    private val address = InetAddress.getByName(group)
    private val interfaces = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && it.inetAddresses.toList().any { a -> a is Inet4Address } }
    private val socket = MulticastSocket(port).apply {
        timeToLive = 1
        interfaces.forEach { runCatching { joinGroup(InetSocketAddress(address, 0), it) } }
    }

    override suspend fun receive(): Datagram {
        val packet = DatagramPacket(ByteArray(65_536), 65_536)
        blocking(::close) { socket.receive(packet) }
        return Datagram(packet.address.hostAddress.orEmpty(), packet.data.copyOf(packet.length))
    }

    override suspend fun send(data: ByteArray) = blocking(::close) {
        interfaces.forEach { nif ->
            runCatching {
                socket.networkInterface = nif
                socket.send(DatagramPacket(data, data.size, address, port))
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
        release()
    }
}
