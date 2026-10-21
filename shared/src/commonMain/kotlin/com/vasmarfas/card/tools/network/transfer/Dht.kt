package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.UdpPacket
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.resolveHost
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.tools.developer.toHex
import com.vasmarfas.card.tools.network.Ipv4
import com.vasmarfas.card.tools.network.Ipv4Subnet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal object Bencode {
    fun encode(value: Any): ByteArray {
        val out = ArrayList<Byte>()
        fun put(text: String) = text.encodeToByteArray().forEach { out += it }
        fun write(item: Any) {
            when (item) {
                is ByteArray -> {
                    put("${item.size}:")
                    item.forEach { out += it }
                }
                is String -> write(item.encodeToByteArray())
                is Int, is Long -> put("i${item}e")
                is List<*> -> {
                    put("l")
                    item.forEach { write(it!!) }
                    put("e")
                }
                is Map<*, *> -> {
                    put("d")
                    item.entries.sortedBy { it.key as String }.forEach { (key, entry) ->
                        write(key as String)
                        write(entry!!)
                    }
                    put("e")
                }
                else -> error("cannot bencode ${item::class}")
            }
        }
        write(value)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Any? = runCatching {
        var at = 0
        fun read(): Any {
            return when (val c = bytes[at].toInt().toChar()) {
                'i' -> {
                    val end = (at until bytes.size).first { bytes[it] == 'e'.code.toByte() }
                    bytes.decodeToString(at + 1, end).toLong().also { at = end + 1 }
                }
                'l' -> {
                    at++
                    val list = ArrayList<Any>()
                    while (bytes[at] != 'e'.code.toByte()) list += read()
                    at++
                    list
                }
                'd' -> {
                    at++
                    val map = LinkedHashMap<String, Any>()
                    while (bytes[at] != 'e'.code.toByte()) {
                        val key = (read() as ByteArray).decodeToString()
                        map[key] = read()
                    }
                    at++
                    map
                }
                in '0'..'9' -> {
                    val colon = (at until bytes.size).first { bytes[it] == ':'.code.toByte() }
                    val length = bytes.decodeToString(at, colon).toInt()
                    require(length >= 0 && colon + 1 + length <= bytes.size)
                    bytes.copyOfRange(colon + 1, colon + 1 + length).also { at = colon + 1 + length }
                }
                else -> error("bad bencode at $at: $c")
            }
        }
        read()
    }.getOrNull()
}

// "ro" (BEP 43) keeps this node out of routing tables, implied_port stores the port the NAT gave the socket
internal class Dht(private val send: suspend (String, Int, ByteArray) -> Unit, private val replies: ReceiveChannel<UdpPacket>) {
    private class Node(val host: String, val port: Int) {
        var id: ByteArray? = null
        var token: ByteArray? = null
        val asked = HashSet<String>()
        var announced = 0L
    }

    private val id = secureRandomBytes(20)
    private val nodes = LinkedHashMap<String, Node>()
    // true for lookups of the other side's hash, the only replies whose values count
    private val pending = HashMap<String, Pair<Node, Boolean>>()
    private var transaction = 0

    private fun distance(node: Node, target: ByteArray): ByteArray? = node.id?.let { nodeId -> ByteArray(20) { (nodeId[it].toInt() xor target[it].toInt()).toByte() } }

    private fun closest(target: ByteArray, count: Int): List<Node> = nodes.values.filter { it.id != null }.sortedWith { a, b ->
        val x = distance(a, target)!!
        val y = distance(b, target)!!
        (0 until 20).firstNotNullOfOrNull { i -> ((x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)).takeIf { it != 0 } } ?: 0
    }.take(count)

    private suspend fun query(node: Node, method: String, arguments: Map<String, Any>, lookup: Boolean = false) {
        val tid = byteArrayOf((transaction shr 8).toByte(), transaction.toByte())
        transaction = (transaction + 1) and 0xFFFF
        pending[tid.decodeToString(throwOnInvalidSequence = false) + node.host] = node to lookup
        val message = mapOf("t" to tid, "y" to "q", "q" to method, "ro" to 1, "a" to mapOf("id" to id) + arguments)
        runCatching { send(node.host, node.port, Bencode.encode(message)) }
    }

    private suspend fun getPeers(node: Node, target: ByteArray, lookup: Boolean) {
        node.asked += target.toHex()
        query(node, "get_peers", mapOf("info_hash" to target), lookup)
    }

    private fun add(host: String, port: Int, nodeId: ByteArray? = null) {
        val address = Ipv4.parse(host)?.let { Ipv4Subnet(it, 32) } ?: return
        if (port == 0 || !address.isPublic) return
        val node = nodes.getOrPut("$host:$port") { Node(host, port) }
        if (nodeId != null) node.id = nodeId
    }

    private fun handle(packet: UdpPacket): List<String> {
        val message = Bencode.decode(packet.data) as? Map<*, *> ?: return emptyList()
        val tid = (message["t"] as? ByteArray)?.decodeToString(throwOnInvalidSequence = false) ?: return emptyList()
        val (node, lookup) = pending.remove(tid + packet.host) ?: return emptyList()
        val reply = message["r"] as? Map<*, *> ?: return emptyList()
        (reply["id"] as? ByteArray)?.takeIf { it.size == 20 }?.let { node.id = it }
        nodes.getOrPut("${node.host}:${node.port}") { node }
        node.token = reply["token"] as? ByteArray
        (reply["nodes"] as? ByteArray)?.let { compact ->
            for (at in 0..compact.size - 26 step 26) {
                val host = (20 until 24).joinToString(".") { (compact[at + it].toInt() and 0xFF).toString() }
                add(host, ((compact[at + 24].toInt() and 0xFF) shl 8) or (compact[at + 25].toInt() and 0xFF), compact.copyOfRange(at, at + 20))
            }
        }
        if (!lookup) return emptyList()
        return (reply["values"] as? List<*>).orEmpty().mapNotNull { value ->
            (value as? ByteArray)?.takeIf { it.size == 6 }?.let { peer ->
                (0 until 4).joinToString(".") { (peer[it].toInt() and 0xFF).toString() } + ":" + (((peer[4].toInt() and 0xFF) shl 8) or (peer[5].toInt() and 0xFF))
            }
        }
    }

    private suspend fun collect(timeoutMs: Long): List<String> {
        val found = ArrayList<String>()
        withTimeoutOrNull(timeoutMs.milliseconds) {
            while (true) found += handle(replies.receive())
        }
        return found
    }

    // some nodes answer with whoever asked about a hash, so every address goes to the punch, never only the first
    suspend fun meet(own: ByteArray, other: ByteArray, port: Int, found: suspend (String) -> Unit) {
        coroutineScope {
            bootstrap.map { (host, port) -> async { runCatching { resolveHost(host).firstOrNull { '.' in it } }.getOrNull()?.let { it to port } } }
                .awaitAll().filterNotNull().map { (host, port) -> Node(host, port) }.forEach {
                    getPeers(it, own, lookup = false)
                    getPeers(it, other, lookup = true)
                }
        }
        val seen = HashSet<String>()
        var rounds = 0
        while (true) {
            collect(800).filter(seen::add).forEach { found(it) }
            val now = currentEpochMillis()
            closest(own, 8).filter { it.token != null && now - it.announced > 120_000 }.forEach {
                it.announced = now
                query(it, "announce_peer", mapOf("info_hash" to own, "port" to port, "token" to it.token!!, "implied_port" to 1))
            }
            val fresh = listOf(own, other).flatMap { target -> closest(target, 8).filter { target.toHex() !in it.asked }.map { it to target } }
            if (fresh.isNotEmpty() && rounds++ < 30) {
                fresh.forEach { (node, target) -> getPeers(node, target, lookup = target === other) }
                continue
            }
            delay(2_000.milliseconds)
            closest(other, 8).forEach { getPeers(it, other, lookup = true) }
        }
    }

    companion object {
        private val bootstrap = listOf(
            "router.bittorrent.com" to 6881, "dht.transmissionbt.com" to 6881, "router.utorrent.com" to 6881, "dht.libtorrent.org" to 25401,
        )
    }
}
