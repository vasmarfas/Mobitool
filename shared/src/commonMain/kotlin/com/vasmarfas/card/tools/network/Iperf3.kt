package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.core.TcpServer
import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.network.transfer.readFully
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class IperfRequest(val seconds: Int, val omit: Int, val parallel: Int, val reverse: Boolean)

class IperfReport(val bytes: Long, val seconds: Double?, val retransmits: Long?)

class LanSpeedResult(val download: Boolean, val sent: Long, val received: Long, val seconds: Double, val retransmits: Long?) {
    val mbps: Double get() = speedMbps(received, (seconds * 1000).toLong())
}

typealias OnSecond = (download: Boolean, second: Int, mbps: Double) -> Unit

object Iperf3 {
    const val PORT = 5201
    const val MAX_STREAMS = 128
    const val COOKIE_SIZE = 37
    const val BLOCK = 128 * 1024

    const val TEST_START: Byte = 1
    const val TEST_RUNNING: Byte = 2
    const val TEST_END: Byte = 4
    const val PARAM_EXCHANGE: Byte = 9
    const val CREATE_STREAMS: Byte = 10
    const val SERVER_TERMINATE: Byte = 11
    const val EXCHANGE_RESULTS: Byte = 13
    const val DISPLAY_RESULTS: Byte = 14
    const val IPERF_DONE: Byte = 16
    const val ACCESS_DENIED: Byte = -1
    const val SERVER_ERROR: Byte = -2

    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private const val MAX_TIME = 86_400
    private const val MAX_OMIT = 600

    fun cookie(): ByteArray = (secureRandomBytes(COOKIE_SIZE - 1).map { ALPHABET[it.toInt() and 31].code.toByte() } + 0.toByte()).toByteArray()

    // iperf_add_stream numbers the first stream 1 and the next ones from 3
    fun streamId(index: Int): Int = if (index == 0) 1 else index + 2

    fun frame(json: String): ByteArray {
        val body = json.encodeToByteArray()
        val size = body.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + body
    }

    fun parameters(seconds: Int, parallel: Int, reverse: Boolean): String = buildJsonObject {
        put("tcp", true)
        put("omit", 0)
        put("time", seconds)
        put("num", 0)
        put("blockcount", 0)
        put("parallel", parallel)
        if (reverse) put("reverse", true)
        put("len", BLOCK)
        put("pacing_timer", 1000)
    }.toString()

    // byte and block limited tests send time 0, the waiting side refuses them along with UDP
    fun request(json: String): IperfRequest? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        fun flag(name: String) = root[name]?.jsonPrimitive?.booleanOrNull == true
        val seconds = root.getValue("time").jsonPrimitive.int
        val omit = root["omit"]?.jsonPrimitive?.int ?: 0
        val parallel = root.getValue("parallel").jsonPrimitive.int
        val fits = flag("tcp") && !flag("bidirectional") && seconds in 1..MAX_TIME && omit in 0..MAX_OMIT && parallel in 1..MAX_STREAMS
        if (fits) IperfRequest(seconds, omit, parallel, flag("reverse")) else null
    }.getOrNull()

    fun results(streams: List<Pair<Int, Long>>, sender: Boolean, seconds: Double): String = buildJsonObject {
        put("cpu_util_total", 0)
        put("cpu_util_user", 0)
        put("cpu_util_system", 0)
        put("sender_has_retransmits", if (sender) 0 else -1)
        putJsonArray("streams") {
            streams.forEach { (id, bytes) ->
                addJsonObject {
                    put("id", id)
                    put("bytes", bytes)
                    put("retransmits", -1)
                    put("jitter", 0)
                    put("errors", 0)
                    put("omitted_errors", 0)
                    put("packets", 0)
                    put("omitted_packets", 0)
                    put("start_time", 0)
                    put("end_time", seconds)
                }
            }
        }
    }.toString()

    fun report(json: String): IperfReport? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        val streams = root.getValue("streams").jsonArray.map { it.jsonObject }
        IperfReport(
            bytes = streams.sumOf { it.getValue("bytes").jsonPrimitive.double.toLong() },
            seconds = streams.mapNotNull { it["end_time"]?.jsonPrimitive?.doubleOrNull }.maxOrNull()?.takeIf { it > 0 },
            retransmits = if (root["sender_has_retransmits"]?.jsonPrimitive?.intOrNull == 1) streams.sumOf { it.getValue("retransmits").jsonPrimitive.double.toLong() } else null,
        )
    }.getOrNull()
}

private const val MAX_FRAME = 1 shl 20
private const val HANDSHAKE_SECONDS = 10

// create_server_timers gives 4 seconds to each of 10 state changes
private const val GRACE_SECONDS = 40
private const val SENDER_STOP_MS = 3_000

private class Flowing(val id: Int, val stream: TcpStream) {
    val bytes = MutableStateFlow(0L)
}

private fun List<Flowing>.total() = sumOf { it.bytes.value }

private fun List<Flowing>.counts() = map { it.id to it.bytes.value }

private suspend fun TcpStream.take(count: Int) = ByteArray(count).also { readFully(it) }

private suspend fun TcpStream.state() = take(1)[0]

private suspend fun TcpStream.send(state: Byte) = write(byteArrayOf(state))

private fun ByteArray.number() = fold(0) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF) }

private suspend fun TcpStream.frame(): String {
    val size = take(4).number()
    if (size !in 1..MAX_FRAME) throw NetException(Res.string.lan_speed_bad_reply)
    return take(size).decodeToString()
}

private fun CoroutineScope.move(flow: Flowing, send: Boolean, stop: Job) = launch {
    val block = Random.nextBytes(Iperf3.BLOCK)
    try {
        while (!stop.isCompleted) {
            val count = if (send) Iperf3.BLOCK.also { flow.stream.write(block) } else flow.stream.read(block)
            if (count < 0) break
            flow.bytes.value += count
        }
    } catch (e: Exception) {
        if (!stop.isCompleted) throw e
    }
}

private suspend fun ticks(flows: List<Flowing>, download: Boolean, onSecond: OnSecond, count: Int = Int.MAX_VALUE) {
    var seen = 0L
    repeat(count) { second ->
        delay(1_000.milliseconds)
        val total = flows.total()
        onSecond(download, second, speedMbps(total - seen, 1_000))
        seen = total
    }
}

private fun lanResult(download: Boolean, own: Long, seconds: Double, peer: IperfReport) =
    if (download) LanSpeedResult(true, peer.bytes, own, seconds, peer.retransmits) else LanSpeedResult(false, own, peer.bytes, peer.seconds ?: seconds, null)

suspend fun iperfClient(seconds: Int, parallel: Int, reverse: Boolean, onSecond: OnSecond, connect: suspend () -> TcpStream): LanSpeedResult =
    withTimeoutOrNull((seconds + GRACE_SECONDS).seconds) {
        val control = connect()
        val cookie = Iperf3.cookie()
        val flows = ArrayList<Flowing>()
        try {
            control.write(cookie)
            coroutineScope {
                var timer: Deferred<Long>? = null
                var result: LanSpeedResult? = null
                do {
                    val state = control.state()
                    when (state) {
                        Iperf3.PARAM_EXCHANGE -> control.write(Iperf3.frame(Iperf3.parameters(seconds, parallel, reverse)))
                        Iperf3.CREATE_STREAMS -> repeat(parallel) { index ->
                            flows += Flowing(Iperf3.streamId(index), connect().also { it.write(cookie) })
                        }
                        Iperf3.TEST_START, Iperf3.DISPLAY_RESULTS -> Unit
                        Iperf3.TEST_RUNNING -> {
                            val started = currentEpochMillis()
                            val stop = Job()
                            val workers = flows.map { move(it, !reverse, stop) }
                            timer = async {
                                ticks(flows, reverse, onSecond, seconds)
                                val elapsed = currentEpochMillis() - started
                                stop.complete()
                                if (!reverse && withTimeoutOrNull(SENDER_STOP_MS.milliseconds) { workers.joinAll() } == null) workers.forEach { it.cancel() }
                                control.send(Iperf3.TEST_END)
                                elapsed
                            }
                        }
                        Iperf3.EXCHANGE_RESULTS -> {
                            val elapsed = (timer?.await() ?: throw NetException(Res.string.lan_speed_bad_reply)) / 1000.0
                            control.write(Iperf3.frame(Iperf3.results(flows.counts(), !reverse, elapsed)))
                            val peer = Iperf3.report(control.frame()) ?: throw NetException(Res.string.lan_speed_bad_reply)
                            result = lanResult(reverse, flows.total(), elapsed, peer)
                        }
                        Iperf3.ACCESS_DENIED -> throw NetException(Res.string.lan_speed_busy)
                        Iperf3.SERVER_TERMINATE -> throw NetException(Res.string.lan_speed_terminated)
                        Iperf3.SERVER_ERROR -> throw NetException(Res.string.lan_speed_server_error, control.take(4).number().toString())
                        else -> throw NetException(Res.string.lan_speed_bad_reply)
                    }
                } while (state != Iperf3.DISPLAY_RESULTS)
                coroutineContext.cancelChildren()
                control.send(Iperf3.IPERF_DONE)
                result ?: throw NetException(Res.string.lan_speed_bad_reply)
            }
        } finally {
            flows.forEach { it.stream.close() }
            control.close()
        }
    } ?: throw NetException(Res.string.net_error_timeout)

// a cancelled accept closes the listener, so one loop owns it and sessions take connections from the channel
// shortcut: a second client waits for the running test and gets no ACCESS_DENIED, enough for two devices on a LAN
suspend fun iperfServer(listener: TcpServer, onSecond: OnSecond, onResult: (LanSpeedResult) -> Unit) {
    try {
        coroutineScope {
            val incoming = Channel<TcpStream>(Channel.UNLIMITED)
            launch { while (true) incoming.send(listener.accept()) }
            while (true) {
                val control = incoming.receive()
                runCatching { serve(control, incoming, onSecond)?.let(onResult) }
                control.close()
            }
        }
    } finally {
        listener.close()
    }
}

private suspend fun serve(control: TcpStream, incoming: ReceiveChannel<TcpStream>, onSecond: OnSecond): LanSpeedResult? {
    val flows = ArrayList<Flowing>()
    try {
        val request = withTimeout(HANDSHAKE_SECONDS.seconds) {
            val cookie = control.take(Iperf3.COOKIE_SIZE)
            control.send(Iperf3.PARAM_EXCHANGE)
            Iperf3.request(control.frame())?.also { request ->
                control.send(Iperf3.CREATE_STREAMS)
                while (flows.size < request.parallel) {
                    val stream = incoming.receive()
                    if (stream.take(Iperf3.COOKIE_SIZE).contentEquals(cookie)) {
                        flows += Flowing(Iperf3.streamId(flows.size), stream)
                    } else {
                        stream.send(Iperf3.ACCESS_DENIED)
                        stream.close()
                    }
                }
            }
        }
        if (request == null) {
            control.send(Iperf3.ACCESS_DENIED)
            return null
        }
        val sender = request.reverse
        control.write(byteArrayOf(Iperf3.TEST_START, Iperf3.TEST_RUNNING))
        val started = currentEpochMillis()
        val stop = Job()
        coroutineScope {
            val workers = flows.map { move(it, sender, stop) }
            val ticker = launch { ticks(flows, !sender, onSecond) }
            val state = withTimeout((request.seconds + request.omit + GRACE_SECONDS).seconds) { control.state() }
            stop.complete()
            flows.forEach { it.stream.close() }
            ticker.cancel()
            workers.forEach { it.cancel() }
            if (state != Iperf3.TEST_END) throw NetException(Res.string.lan_speed_terminated)
        }
        val elapsed = (currentEpochMillis() - started) / 1000.0
        return withTimeout(HANDSHAKE_SECONDS.seconds) {
            control.send(Iperf3.EXCHANGE_RESULTS)
            val peer = Iperf3.report(control.frame()) ?: throw NetException(Res.string.lan_speed_bad_reply)
            control.write(Iperf3.frame(Iperf3.results(flows.counts(), sender, elapsed)) + Iperf3.DISPLAY_RESULTS)
            control.state()
            lanResult(!sender, flows.total(), elapsed, peer)
        }
    } finally {
        flows.forEach { it.stream.close() }
    }
}
