package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.UdpPacket
import com.vasmarfas.card.core.UdpSocket
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.openUdp
import com.vasmarfas.card.core.resolveHost
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.tools.network.Stun
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

private const val DATA = 0xD0.toByte()
private const val ACK = 0xA0.toByte()
private const val CLOSE = 0xC0.toByte()
private const val PROBE = 0xB0.toByte()
private const val PROBE_ACK = 0xB1.toByte()

// payload per datagram, under the 1280-byte IPv6 minimum with headers to spare
internal const val MSS = 1200

private const val WINDOW = 4096
private const val RANGES = 16
private const val SILENCE_MS = 30_000L

private fun ByteArray.u32(at: Int) =
    ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

private fun ByteArray.put32(at: Int, value: Int) {
    this[at] = (value ushr 24).toByte()
    this[at + 1] = (value ushr 16).toByte()
    this[at + 2] = (value ushr 8).toByte()
    this[at + 3] = value.toByte()
}

internal class UdpStream(private val socket: UdpSocket, override val remoteHost: String, val port: Int, scope: CoroutineScope) : TcpStream {
    private class Sent(val packet: ByteArray, var sentAt: Long, var retransmitted: Boolean)

    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val outgoing = Channel<ByteArray>(64)

    // shortcut: delivered data is not counted against the advertised window; a reader slower than the network would
    // let it grow, the transfer reader writes to disk faster than any link it gets
    private val delivered = Channel<ByteArray>(Channel.UNLIMITED)
    private var buffered: ByteArray? = null
    private var bufferedAt = 0

    private var nextSeq = 0
    private val inFlight = LinkedHashMap<Int, Sent>()
    private var cwnd = 32.0
    private var ssthresh = Double.MAX_VALUE
    private var recoverUntil = 0
    private var highestAcked = -1
    private var latestAckedSentAt = 0L
    private var peerWindow = WINDOW
    private var srtt = 0.0
    private var rttvar = 0.0
    private var latestRtt = 0.0
    private var probes = 0

    private var expected = 0
    private val held = HashMap<Int, ByteArray>()
    private val ranges = ArrayList<IntArray>()
    private var finalSeq: Int? = null
    private var ackDue = false
    private var lastHeard = currentEpochMillis()

    private val engine = scope.launch {
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            delivered.close(e)
            outgoing.close(e)
        } finally {
            delivered.close()
        }
    }

    fun deliver(packet: ByteArray) {
        incoming.trySend(packet)
    }

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        var at = offset
        while (at < offset + length) {
            val count = min(MSS, offset + length - at)
            outgoing.send(bytes.copyOfRange(at, at + count))
            at += count
        }
    }

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var chunk = buffered
        if (chunk == null || bufferedAt >= chunk.size) {
            val result = delivered.receiveCatching()
            result.exceptionOrNull()?.let { throw it }
            chunk = result.getOrNull() ?: return -1
            buffered = chunk
            bufferedAt = 0
        }
        val count = min(length, chunk.size - bufferedAt)
        chunk.copyInto(buffer, offset, bufferedAt, bufferedAt + count)
        bufferedAt += count
        return count
    }

    override fun close() {
        outgoing.close()
    }

    private suspend fun transmit(packet: ByteArray) = socket.send(remoteHost, port, packet)

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        var writing = true
        while (true) {
            val now = currentEpochMillis()
            if (!writing && inFlight.isEmpty()) {
                val close = ByteArray(5).also {
                    it[0] = CLOSE
                    it.put32(1, nextSeq)
                }
                repeat(2) { transmit(close) }
                return
            }
            if (now - lastHeard > SILENCE_MS) throw RelayException(corrupt = false)
            val wait = max(1L, (deadline() ?: (now + 1_000)) - now)
            select<Unit> {
                incoming.onReceive { handle(it) }
                if (writing && inFlight.size < min(cwnd.toInt(), peerWindow)) {
                    outgoing.onReceiveCatching { result ->
                        val chunk = result.getOrNull()
                        if (chunk == null) writing = false else send(chunk)
                    }
                }
                onTimeout(wait) { expire() }
            }
            if (ackDue) acknowledge()
        }
    }

    private suspend fun send(chunk: ByteArray) {
        val packet = ByteArray(5 + chunk.size)
        packet[0] = DATA
        packet.put32(1, nextSeq)
        chunk.copyInto(packet, 5)
        inFlight[nextSeq++] = Sent(packet, currentEpochMillis(), false)
        transmit(packet)
    }

    private suspend fun handle(packet: ByteArray) {
        lastHeard = currentEpochMillis()
        when (packet.firstOrNull()) {
            DATA -> if (packet.size >= 5) {
                receive(packet.u32(1), packet.copyOfRange(5, packet.size))
                ackDue = true
            }
            ACK -> if (packet.size >= 8) acknowledged(packet)
            CLOSE -> if (packet.size >= 5) {
                finalSeq = packet.u32(1)
                if (expected >= packet.u32(1)) delivered.close()
            }
        }
    }

    private fun receive(seq: Int, payload: ByteArray) {
        when {
            seq == expected -> {
                delivered.trySend(payload)
                expected++
                if (ranges.isNotEmpty() && ranges[0][0] == expected) {
                    val run = ranges.removeAt(0)
                    for (next in run[0] until run[1]) delivered.trySend(held.remove(next)!!)
                    expected = run[1]
                }
                finalSeq?.let { if (expected >= it) delivered.close() }
            }
            seq > expected && seq - expected < WINDOW && seq !in held -> {
                held[seq] = payload
                mark(seq)
            }
        }
    }

    private fun mark(seq: Int) {
        val index = ranges.indexOfFirst { it[0] > seq }.let { if (it < 0) ranges.size else it }
        val before = ranges.getOrNull(index - 1)
        val after = ranges.getOrNull(index)
        when {
            before != null && before[1] == seq -> {
                before[1] = seq + 1
                if (after != null && after[0] == seq + 1) {
                    before[1] = after[1]
                    ranges.removeAt(index)
                }
            }
            after != null && after[0] == seq + 1 -> after[0] = seq
            else -> ranges.add(index, intArrayOf(seq, seq + 1))
        }
    }

    private suspend fun acknowledge() {
        ackDue = false
        val count = min(RANGES, ranges.size)
        val packet = ByteArray(8 + count * 8)
        packet[0] = ACK
        packet.put32(1, expected)
        val window = (WINDOW - held.size).coerceIn(0, 0xFFFF)
        packet[5] = (window shr 8).toByte()
        packet[6] = window.toByte()
        packet[7] = count.toByte()
        for (i in 0 until count) {
            packet.put32(8 + i * 8, ranges[i][0])
            packet.put32(12 + i * 8, ranges[i][1])
        }
        transmit(packet)
    }

    private suspend fun acknowledged(packet: ByteArray) {
        val now = currentEpochMillis()
        val cumulative = packet.u32(1)
        peerWindow = ((packet[5].toInt() and 0xFF) shl 8) or (packet[6].toInt() and 0xFF)
        val count = min(packet[7].toInt() and 0xFF, (packet.size - 8) / 8)
        var acked = 0
        var sample: Long? = null
        fun done(sent: Sent) {
            acked++
            latestAckedSentAt = max(latestAckedSentAt, sent.sentAt)
            if (!sent.retransmitted) sample = now - sent.sentAt
        }
        val oldest = inFlight.entries.iterator()
        while (oldest.hasNext()) {
            val entry = oldest.next()
            if (entry.key >= cumulative) break
            done(entry.value)
            oldest.remove()
        }
        highestAcked = max(highestAcked, cumulative - 1)
        for (i in 0 until count) {
            val start = packet.u32(8 + i * 8)
            val end = packet.u32(12 + i * 8)
            if (end <= start || end - start > WINDOW) continue
            for (seq in start until end) inFlight.remove(seq)?.let(::done)
            highestAcked = max(highestAcked, end - 1)
        }
        sample?.let { rtt ->
            if (srtt == 0.0) {
                srtt = rtt.toDouble()
                rttvar = rtt / 2.0
            } else {
                rttvar = 0.75 * rttvar + 0.25 * abs(srtt - rtt)
                srtt = 0.875 * srtt + 0.125 * rtt
            }
            latestRtt = rtt.toDouble()
        }
        if (acked > 0) {
            probes = 0
            cwnd = min(WINDOW.toDouble(), if (cwnd < ssthresh) cwnd + acked else cwnd + acked / cwnd)
        }
        detectLoss(now)
    }

    private fun lossDelay() = max(9.0 / 8 * max(srtt, latestRtt), 2.0).toLong()

    private fun probeTimeout() = (if (srtt == 0.0) 300L else (srtt + max(4 * rttvar, 5.0)).toLong()) shl min(probes, 6)

    private fun deadline(): Long? {
        val oldest = inFlight.values.firstOrNull() ?: return null
        val loss = inFlight.values.firstOrNull { it.sentAt < latestAckedSentAt }?.let { it.sentAt + lossDelay() }
        val probe = oldest.sentAt + probeTimeout()
        return if (loss != null) min(loss, probe) else probe
    }

    private suspend fun retransmit(sent: Sent, now: Long) {
        sent.sentAt = now
        sent.retransmitted = true
        transmit(sent.packet)
    }

    // loss detection as in QUIC (RFC 9002): a later packet acked and three numbers or 9/8 of a round trip passed
    private suspend fun detectLoss(now: Long) {
        for ((seq, sent) in inFlight) {
            // fresh packets go out in number order, so past the first one sent after the acked mark none can be lost
            if (sent.sentAt >= latestAckedSentAt) {
                if (sent.retransmitted) continue else break
            }
            if (now - sent.sentAt < lossDelay() && (sent.retransmitted || seq + 3 > highestAcked)) continue
            if (seq >= recoverUntil) {
                ssthresh = max(cwnd / 2, 4.0)
                cwnd = ssthresh
                recoverUntil = nextSeq
            }
            retransmit(sent, now)
        }
    }

    private suspend fun expire() {
        val now = currentEpochMillis()
        detectLoss(now)
        val oldest = inFlight.values.firstOrNull() ?: return
        if (now - oldest.sentAt < probeTimeout()) return
        probes++
        if (probes >= 3) {
            ssthresh = max(cwnd / 2, 4.0)
            cwnd = 4.0
            recoverUntil = nextSeq
        }
        retransmit(oldest, now)
    }
}

class Punch private constructor(private val socket: UdpSocket) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inbox = Channel<UdpPacket>(Channel.UNLIMITED)
    private val dht = Channel<UdpPacket>(Channel.UNLIMITED)
    private val found = Channel<String>(Channel.UNLIMITED)
    private var meeting: Job? = null
    private var stream: UdpStream? = null

    var endpoints: List<String> = emptyList()
        private set

    internal val port: Int get() = socket.port

    init {
        scope.launch {
            while (true) {
                val packet = socket.receive()
                val bound = stream
                when {
                    bound != null && packet.host == bound.remoteHost && packet.port == bound.port -> bound.deliver(packet.data)
                    // a bencoded dictionary is a DHT answer
                    packet.data.firstOrNull() == 'd'.code.toByte() -> dht.trySend(packet)
                    else -> inbox.trySend(packet)
                }
            }
        }
    }

    suspend fun gather(lan: List<String>) {
        coroutineScope {
            stunServers.map { (host, port) ->
                async { runCatching { resolveHost(host).firstOrNull { '.' in it }?.let { socket.send(it, port, Stun.request(Random.nextBytes(12))) } } }
            }.awaitAll()
        }
        val mapped = LinkedHashSet<String>()
        withTimeoutOrNull(1_500.milliseconds) {
            while (true) Stun.mappedAddress(inbox.receive().data)?.let { mapped += it }
        }
        endpoints = lan.map { "$it:${socket.port}" } + mapped
    }

    // the role byte keeps a probe looped back by the router from passing for the peer's
    private fun probe(key: ByteArray, type: Byte, sender: Boolean) =
        byteArrayOf(type) + Direct.seal(key, byteArrayOf(if (sender) 1 else 0) + secureRandomBytes(8), byteArrayOf(type))

    private fun fromPeer(key: ByteArray, packet: ByteArray, sender: Boolean): Boolean {
        if (packet.size <= 1) return false
        val side = Direct.open(key, packet.copyOfRange(1, packet.size), byteArrayOf(packet[0]))?.firstOrNull() ?: return false
        return side != (if (sender) 1.toByte() else 0.toByte())
    }

    fun meet(own: ByteArray, other: ByteArray) {
        meeting = scope.launch { Dht(socket::send, dht).meet(own, other, socket.port, found::send) }
    }

    internal suspend fun connect(key: ByteArray, sender: Boolean, peer: List<String>): UdpStream = coroutineScope {
        // a stream bound by an attempt that was called off would otherwise keep taking the peer's packets
        stream = null
        var targets: Set<Pair<String, Int>> = peer.mapNotNull(Direct::endpoint).filter { '.' in it.first }.toSet()
        val prober = launch {
            while (true) {
                targets.forEach { (host, port) -> runCatching { socket.send(host, port, probe(key, PROBE, sender)) } }
                delay(150.milliseconds)
            }
        }
        try {
            var bound: UdpStream? = null
            while (bound == null) {
                select<Unit> {
                    found.onReceive { address -> Direct.endpoint(address)?.let { targets = targets + it } }
                    inbox.onReceive { packet ->
                        when (packet.data.firstOrNull()) {
                            PROBE -> if (fromPeer(key, packet.data, sender)) {
                                targets = targets + (packet.host to packet.port)
                                socket.send(packet.host, packet.port, probe(key, PROBE_ACK, sender))
                            }
                            PROBE_ACK -> if (sender && fromPeer(key, packet.data, sender)) bound = bind(packet.host, packet.port, null)
                            DATA -> if (!sender) bound = bind(packet.host, packet.port, packet.data)
                        }
                    }
                }
            }
            bound
        } finally {
            prober.cancel()
        }
    }

    private fun bind(host: String, port: Int, first: ByteArray?): UdpStream {
        meeting?.cancel()
        return UdpStream(socket, host, port, scope).also { created ->
            stream = created
            first?.let(created::deliver)
        }
    }

    fun close() {
        stream?.close()
        scope.cancel()
        socket.close()
    }

    companion object {
        private val stunServers = listOf("stun.l.google.com" to 19302, "stun.cloudflare.com" to 3478, "stun.sipnet.ru" to 3478, "stun.zadarma.com" to 3478)

        fun open(): Punch? = runCatching { openUdp(0) }.getOrNull()?.let(::Punch)
    }
}
