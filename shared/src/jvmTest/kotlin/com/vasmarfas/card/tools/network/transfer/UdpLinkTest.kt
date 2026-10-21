package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.IncomingFile
import com.vasmarfas.card.core.UdpSocket
import com.vasmarfas.card.core.listenTcp
import com.vasmarfas.card.core.openUdp
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

private class Lossy(private val inner: UdpSocket, seed: Int) : UdpSocket by inner {
    private val random = Random(seed)
    private var held: Triple<String, Int, ByteArray>? = null

    override suspend fun send(host: String, port: Int, data: ByteArray) {
        if (random.nextInt(10) == 0) return
        if (random.nextInt(20) == 0) {
            held = Triple(host, port, data)
            return
        }
        inner.send(host, port, data)
        held?.let { (h, p, d) -> inner.send(h, p, d) }
        held = null
    }
}

class UdpLinkTest {
    private suspend fun drain(stream: UdpStream, size: Int): ByteArray {
        val out = ByteArray(size)
        var filled = 0
        while (filled < size) {
            val read = stream.read(out, filled, size - filled)
            check(read > 0) { "stream ended at $filled" }
            filled += read
        }
        return out
    }

    @Test
    fun survivesLossBothWays() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = openUdp(0)
        val b = openUdp(0)
        val left = UdpStream(Lossy(a, 1), "127.0.0.1", b.port, scope)
        val right = UdpStream(Lossy(b, 2), "127.0.0.1", a.port, scope)
        scope.launch { while (true) left.deliver(a.receive().data) }
        scope.launch { while (true) right.deliver(b.receive().data) }
        try {
            val one = Random(11).nextBytes(2_000_000)
            val two = Random(12).nextBytes(700_000)
            withTimeout(60_000.milliseconds) {
                val back = async { drain(left, two.size) }
                launch { left.write(one) }
                launch { right.write(two) }
                assertContentEquals(one, drain(right, one.size))
                assertContentEquals(two, back.await())
                left.close()
                assertEquals(-1, right.read(ByteArray(1)))
            }
        } finally {
            scope.cancel()
            a.close()
            b.close()
        }
    }

    @Test
    fun punchCarriesFiles() = runBlocking {
        val key = Random(21).nextBytes(32)
        val receivingPunch = Punch.open()!!
        val sendingPunch = Punch.open()!!
        val receiving = Reach(listenTcp(0), emptyList(), receivingPunch, null)
        val sending = Reach(listenTcp(0), emptyList(), sendingPunch, null)
        val content = Random(22).nextBytes(400_000)
        val file = File.createTempFile("mobitool-punch", ".bin").apply { writeBytes(content) }
        try {
            val received = Buffer()
            val receiver = async {
                val stream = Direct.link(key, sender = false, receiving, Offer(udp = listOf("127.0.0.1:${sendingPunch.port}")), 10_000)!!
                readFrames(
                    key,
                    { stream.readFully(it) },
                    { manifest -> TransferJob(outgoing = false, peer = manifest.sender, items = manifest.files.map { TransferItem(it.name, it.size) }) },
                    { IncomingFile(received, { null }, {}) },
                ).also { stream.write(byteArrayOf(1)) }
            }
            val stream = Direct.link(key, sender = true, sending, Offer(udp = listOf("127.0.0.1:${receivingPunch.port}")), 8_000)!!
            val manifest = RelayManifest("test", listOf(RelayFile(file.name, file.length())))
            val outgoing = TransferJob(outgoing = true, peer = "test", items = listOf(TransferItem(file.name, file.length())))
            writeFrames(key, Json.encodeToString(RelayManifest.serializer(), manifest).encodeToByteArray(), listOf(PlatformFile(file)), outgoing) { bytes, count ->
                stream.write(bytes, 0, count)
            }
            assertEquals(1, ByteArray(1).also { stream.readFully(it) }[0].toInt())
            assertEquals(JobState.DONE, receiver.await().state)
            assertContentEquals(content, received.readByteArray())
        } finally {
            file.delete()
            receiving.close()
            sending.close()
        }
    }
}

