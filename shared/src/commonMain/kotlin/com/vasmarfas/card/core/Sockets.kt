package com.vasmarfas.card.core

// cancelling a coroutine blocked in read or write closes the stream
interface TcpStream {
    val remoteHost: String
    suspend fun read(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int
    suspend fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
    fun close()
}

interface TcpServer {
    val port: Int
    suspend fun accept(): TcpStream
    fun close()
}

expect fun listenTcp(port: Int): TcpServer

expect suspend fun connectTcp(host: String, port: Int, timeoutMs: Int): TcpStream

class Datagram(val host: String, val data: ByteArray)

interface MulticastGroup {
    suspend fun receive(): Datagram
    suspend fun send(data: ByteArray)
    fun close()
}

// null in the browser and where no interface could join the group
expect fun joinMulticast(group: String, port: Int): MulticastGroup?

class UdpPacket(val host: String, val port: Int, val data: ByteArray)

interface UdpSocket {
    val port: Int
    suspend fun send(host: String, port: Int, data: ByteArray)
    suspend fun receive(): UdpPacket
    fun close()
}

expect fun openUdp(port: Int): UdpSocket
