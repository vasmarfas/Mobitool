package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.ZipArchive
import com.vasmarfas.card.core.safeFileName
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

private class Replay(text: String, private val step: Int = 7) : TcpStream {
    private val bytes = text.encodeToByteArray()
    private var at = 0
    val written = ArrayList<Byte>()
    override val remoteHost = "192.168.1.20"

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (at >= bytes.size) return -1
        val count = minOf(length, step, bytes.size - at)
        bytes.copyInto(buffer, offset, at, at + count)
        at += count
        return count
    }

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        for (i in offset until offset + length) written.add(bytes[i])
    }

    override fun close() = Unit
}

class TransferWireTest {
    @Test
    fun requestsOnOneConnection() = runTest {
        val stream = Replay(
            "POST /api/localsend/v2/upload?sessionId=a%20b&fileId=f1&token=x+y HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello" +
                "POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2;ext\r\nde\r\n0\r\n\r\n" +
                "GET /end HTTP/1.1\r\n\r\n",
        )
        val reader = WireReader(stream)
        val first = reader.request()!!
        assertEquals("/api/localsend/v2/upload", first.path)
        assertEquals("a b", first.query["sessionId"])
        assertEquals("x y", first.query["token"])
        assertEquals("hello", first.body.bytes(100).decodeToString())
        val second = reader.request()!!
        assertNull(second.body.length)
        assertEquals("abcde", second.body.bytes(100).decodeToString())
        val third = reader.request()!!
        assertEquals("GET", third.method)
        third.body.drain()
        assertNull(reader.request())
    }

    @Test
    fun responseUntilClose() = runTest {
        val reply = WireReader(Replay("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")).response()
        assertEquals(204, reply.status)
        assertEquals("", reply.body.bytes(10).decodeToString())
        val streamed = WireReader(Replay("HTTP/1.0 200 OK\r\n\r\n{\"a\":1}")).response()
        assertEquals("{\"a\":1}", streamed.body.bytes(100).decodeToString())
    }

    @Test
    fun percentRoundTrip() {
        val name = "отчёт 2026 & (копия).pdf"
        assertEquals(name, percentDecode(percentEncode(name)))
        assertEquals("100%", percentDecode("100%"))
    }

    @Test
    fun peerNamesStayInTheFolder() {
        assertEquals("passwd", safeFileName("../../etc/passwd"))
        assertEquals("photo.jpg", safeFileName("DCIM\\Camera\\photo.jpg"))
        assertEquals("file", safeFileName(".."))
        assertEquals("ab.txt", safeFileName("a\u0000b.txt"))
    }

    @Test
    fun streamedZipOpens() = runTest {
        val out = ArrayList<Byte>()
        val zip = ZipStream { bytes, count -> for (i in 0 until count) out.add(bytes[i]) }
        val first = "first file".encodeToByteArray()
        val second = ByteArray(70_000) { (it % 251).toByte() }
        zip.add("a.txt") { write -> write(first, first.size) }
        zip.add("папка.bin") { write ->
            write(second.copyOf(65_536), 65_536)
            write(second.copyOfRange(65_536, second.size), second.size - 65_536)
        }
        zip.finish()
        val archive = ZipArchive(out.toByteArray())
        assertEquals(listOf("a.txt", "папка.bin"), archive.entries.map { it.name })
        assertContentEquals(first, archive.read("a.txt"))
        assertContentEquals(second, archive.read("папка.bin"))
    }

    @Test
    fun localSendJson() {
        val sent = LocalSend.json.encodeToString(DeviceInfo.serializer(), DeviceInfo(alias = "Desk", fingerprint = "f", port = 53317))
        assertEquals("""{"alias":"Desk","version":"2.1","fingerprint":"f","port":53317,"protocol":"http","download":false}""", sent)
        // the example from the protocol description
        val prepare = LocalSend.json.decodeFromString(
            PrepareUpload.serializer(),
            """
            {"info":{"alias":"Nice Orange","version":"2.0","deviceModel":"Samsung","deviceType":"mobile","fingerprint":"random string",
            "port":53317,"protocol":"https","download":true},
            "files":{"some file id":{"id":"some file id","fileName":"my image.png","size":324242,"fileType":"image/jpeg",
            "sha256":"*sha256 hash*","preview":"*preview data*","metadata":{"modified":"2021-01-01T12:34:56Z"}}}}
            """.trimIndent(),
        )
        assertEquals("https", prepare.info.protocol)
        assertEquals(324242L, prepare.files.getValue("some file id").size)
    }

    // the get_peers example from BEP 5, both ways
    @Test
    fun bencode() {
        val query = mapOf("t" to "aa", "y" to "q", "q" to "get_peers", "a" to mapOf("id" to "abcdefghij0123456789", "info_hash" to "mnopqrstuvwxyz123456"))
        val wire = "d1:ad2:id20:abcdefghij01234567899:info_hash20:mnopqrstuvwxyz123456e1:q9:get_peers1:t2:aa1:y1:qe"
        assertEquals(wire, Bencode.encode(query).decodeToString())
        val reply = Bencode.decode("d1:rd2:id20:abcdefghij01234567895:token8:aoeusnth6:valuesl6:axje.u6:idhtnmee1:t2:aa1:y1:re".encodeToByteArray()) as Map<*, *>
        val values = ((reply["r"] as Map<*, *>)["values"] as List<*>).map { (it as ByteArray).decodeToString() }
        assertEquals(listOf("axje.u", "idhtnm"), values)
        assertEquals(42L, Bencode.decode("i42e".encodeToByteArray()))
        assertNull(Bencode.decode("d1:a".encodeToByteArray()))
    }
}

