package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.IncomingFile
import com.vasmarfas.card.core.listenTcp
import io.github.vinceglb.filekit.PlatformFile
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json

class DirectTest {
    private fun reach() = listenTcp(0).let { Reach(it, listOf("127.0.0.1:${it.port}"), null, null) }

    @Test
    fun linksAndCarriesFiles() = runBlocking {
        val key = Random(3).nextBytes(32)
        val receiving = reach()
        val sending = reach()
        val content = Random(5).nextBytes(300_000)
        val file = File.createTempFile("mobitool-direct", ".bin").apply { writeBytes(content) }
        try {
            val received = Buffer()
            val receiver = async {
                val stream = Direct.link(key, sender = false, receiving, Offer(endpoints = sending.endpoints), 10_000)!!
                readFrames(
                    key,
                    { stream.readFully(it) },
                    { manifest -> TransferJob(outgoing = false, peer = manifest.sender, items = manifest.files.map { TransferItem(it.name, it.size) }) },
                    { IncomingFile(received, { null }, {}) },
                ).also { stream.close() }
            }
            val stream = Direct.link(key, sender = true, sending, Offer(endpoints = receiving.endpoints), 8_000)!!
            val outgoing = TransferJob(outgoing = true, peer = "test", items = listOf(TransferItem(file.name, file.length())))
            val manifest = RelayManifest("test", listOf(RelayFile(file.name, file.length())))
            writeFrames(key, Json.encodeToString(RelayManifest.serializer(), manifest).encodeToByteArray(), listOf(PlatformFile(file)), outgoing) { bytes, count ->
                stream.write(bytes, 0, count)
            }
            val job = receiver.await()
            stream.close()
            assertEquals(JobState.DONE, job.state)
            assertEquals(content.size.toLong(), job.done)
            assertContentEquals(content, received.readByteArray())
        } finally {
            file.delete()
            receiving.close()
            sending.close()
        }
    }

    @Test
    fun anotherKeyIsTurnedAway() = runBlocking {
        val receiving = reach()
        val sending = reach()
        try {
            val receiver = async { Direct.link(Random(1).nextBytes(32), sender = false, receiving, Offer(endpoints = sending.endpoints), 2_000) }
            assertNull(Direct.link(Random(2).nextBytes(32), sender = true, sending, Offer(endpoints = receiving.endpoints), 1_500))
            assertNull(receiver.await())
        } finally {
            receiving.close()
            sending.close()
        }
    }

    @Test
    fun sealedOffers() {
        val key = Random(9).nextBytes(32)
        val sealed = Direct.seal(key, "offer".encodeToByteArray(), "os".encodeToByteArray())
        assertEquals("offer", Direct.open(key, sealed, "os".encodeToByteArray())?.decodeToString())
        assertNull(Direct.open(key, sealed, "or".encodeToByteArray()))
    }
}
