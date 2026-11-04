package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.core.TcpServer
import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.network.transfer.readFully
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

// the bytes below passed between iperf 3.21 client and server on loopback, written down by a logging proxy
private const val COOKIE = "kuy4qmwnas3fc36kacetbxp7e4val4pctg4k"
private const val CLIENT_PARAMETERS = """{"tcp":true,"omit":0,"time":2,"num":0,"blockcount":0,"parallel":1,"len":131072,"pacing_timer":1000,"client_version":"3.21"}"""
private const val CLIENT_REPORT = """{"cpu_util_total":3.0740549760025386,"cpu_util_user":0,"cpu_util_system":3.0740549760025386,"sender_has_retransmits":0,"streams":[{"id":1,"bytes":191496192,"retransmits":-1,"jitter":0,"errors":0,"omitted_errors":0,"packets":0,"omitted_packets":0,"start_time":0,"end_time":2.00878}]}"""
private const val SERVER_REPORT = """{"cpu_util_total":3.0861232468206707,"cpu_util_user":1.5430616234103354,"cpu_util_system":1.5430616234103354,"sender_has_retransmits":-1,"streams":[{"id":1,"bytes":191496192,"retransmits":-1,"jitter":0,"errors":0,"omitted_errors":0,"packets":0,"omitted_packets":0,"start_time":0,"end_time":2.008968}]}"""
private const val REVERSE_SERVER_REPORT = """{"cpu_util_total":7.7362719853619755,"cpu_util_user":0.74867148245438464,"cpu_util_system":6.98760050290759,"sender_has_retransmits":0,"streams":[{"id":1,"bytes":257818624,"retransmits":-1,"jitter":0,"errors":0,"omitted_errors":0,"packets":0,"omitted_packets":0,"start_time":0,"end_time":2.003523},{"id":3,"bytes":285605888,"retransmits":-1,"jitter":0,"errors":0,"omitted_errors":0,"packets":0,"omitted_packets":0,"start_time":0,"end_time":2.003524}]}"""

private fun ints(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private suspend fun TcpStream.bytes(count: Int) = ByteArray(count).also { readFully(it) }

private suspend fun TcpStream.json() = bytes(4).let { bytes(((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF)).decodeToString() }

private class PipeEnd(private val incoming: Channel<ByteArray>, private val outgoing: Channel<ByteArray>) : TcpStream {
    private var rest = ByteArray(0)
    override val remoteHost = "192.0.2.10"

    override suspend fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (rest.isEmpty()) rest = incoming.receiveCatching().getOrNull() ?: return -1
        val count = minOf(length, rest.size)
        rest.copyInto(buffer, offset, 0, count)
        rest = rest.copyOfRange(count, rest.size)
        return count
    }

    // virtual time only moves while everything waits, and 90 ms puts exactly 11 blocks into each of the first seconds
    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        delay(90)
        outgoing.send(bytes.copyOfRange(offset, offset + length))
    }

    override fun close() {
        incoming.close()
        outgoing.close()
    }
}

private fun pipe(): Pair<TcpStream, TcpStream> {
    val forth = Channel<ByteArray>(Channel.UNLIMITED)
    val back = Channel<ByteArray>(Channel.UNLIMITED)
    return PipeEnd(back, forth) to PipeEnd(forth, back)
}

private class Listener : TcpServer {
    private val pending = Channel<TcpStream>(Channel.UNLIMITED)
    override val port = Iperf3.PORT

    override suspend fun accept(): TcpStream = pending.receive()

    override fun close() = Unit

    fun dial(): TcpStream {
        val (near, far) = pipe()
        pending.trySend(far)
        return near
    }
}

class Iperf3Test {
    @Test
    fun cookieIsThirtySixBase32CharsAndNul() {
        val cookie = Iperf3.cookie()
        assertEquals(37, cookie.size)
        assertEquals(0, cookie.last().toInt())
        assertTrue(cookie.dropLast(1).all { it.toInt().toChar() in "abcdefghijklmnopqrstuvwxyz234567" })
    }

    @Test
    fun parametersMatchTheRecordedOnes() {
        assertEquals(CLIENT_PARAMETERS.replace(""","client_version":"3.21"""", ""), Iperf3.parameters(2, 1, false))
        assertEquals(
            """{"tcp":true,"omit":0,"time":2,"num":0,"blockcount":0,"parallel":2,"reverse":true,"len":131072,"pacing_timer":1000}""",
            Iperf3.parameters(2, 2, true),
        )
    }

    @Test
    fun frameIsBigEndianLengthAndJson() {
        assertContentEquals(ints(0, 0, 0, 2, '{'.code, '}'.code), Iperf3.frame("{}"))
        assertContentEquals(ints(0, 0, 1, 0x1A), Iperf3.frame(CLIENT_REPORT).copyOf(4))
        assertContentEquals(ints(0, 0, 1, 0x2D), Iperf3.frame(SERVER_REPORT).copyOf(4))
        assertContentEquals(ints(0, 0, 1, 0xC3), Iperf3.frame(REVERSE_SERVER_REPORT).copyOf(4))
    }

    @Test
    fun streamsAreNumberedLikeIperfAddStream() {
        assertEquals(listOf(1, 3, 4, 5), List(4, Iperf3::streamId))
    }

    @Test
    fun reportsAreSummedOverStreams() {
        val single = assertNotNull(Iperf3.report(SERVER_REPORT))
        assertEquals(191496192L, single.bytes)
        assertEquals(2.008968, single.seconds)
        assertNull(single.retransmits)
        assertEquals(257818624L + 285605888L, Iperf3.report(REVERSE_SERVER_REPORT)?.bytes)
        val linux = Iperf3.report("""{"sender_has_retransmits":1,"streams":[{"id":1,"bytes":12345678901,"retransmits":3},{"id":3,"bytes":1.5e+9,"retransmits":4}]}""")
        assertEquals(13845678901L, linux?.bytes)
        assertEquals(7L, linux?.retransmits)
        assertNull(linux?.seconds)
        assertNull(Iperf3.report("""{"streams":[{"id":1}]}"""))
        assertNull(Iperf3.report("HTTP/1.1 400"))
    }

    @Test
    fun ownReportHasEverythingGetResultsRequires() {
        assertEquals(
            """{"cpu_util_total":0,"cpu_util_user":0,"cpu_util_system":0,"sender_has_retransmits":-1,"streams":[""" +
                """{"id":1,"bytes":10,"retransmits":-1,"jitter":0,"errors":0,"omitted_errors":0,"packets":0,"omitted_packets":0,"start_time":0,"end_time":2.5},""" +
                """{"id":3,"bytes":20,"retransmits":-1,"jitter":0,"errors":0,"omitted_errors":0,"packets":0,"omitted_packets":0,"start_time":0,"end_time":2.5}]}""",
            Iperf3.results(listOf(1 to 10L, 3 to 20L), false, 2.5),
        )
        assertTrue(""""sender_has_retransmits":0""" in Iperf3.results(emptyList(), true, 1.0))
    }

    @Test
    fun waitingSideTakesOnlyTimedTcpTests() {
        val request = assertNotNull(Iperf3.request(CLIENT_PARAMETERS))
        assertEquals(2, request.seconds)
        assertEquals(1, request.parallel)
        assertFalse(request.reverse)
        assertTrue(Iperf3.request(Iperf3.parameters(2, 2, true))?.reverse == true)
        assertNull(Iperf3.request("""{"udp":true,"omit":0,"time":2,"parallel":1}"""))
        assertNull(Iperf3.request("""{"tcp":true,"omit":0,"time":2,"parallel":1,"bidirectional":true}"""))
        assertNull(Iperf3.request("""{"tcp":true,"omit":0,"time":0,"num":1048576,"parallel":1}"""))
        assertNull(Iperf3.request("""{"tcp":true,"omit":0,"time":2,"parallel":129}"""))
        assertNull(Iperf3.request("""{"tcp":true,"omit":0,"time":86401,"parallel":1}"""))
        assertNull(Iperf3.request("not json"))
    }

    @Test
    fun speedMatchesWhatIperfPrinted() {
        val recorded = LanSpeedResult(false, 191496192, 191496192, 2.008968, null)
        assertEquals(763.0, recorded.mbps, 0.5)
    }

    @Test
    fun uploadAgainstRecordedServer() = runTest {
        val (control, serverControl) = pipe()
        val (data, serverData) = pipe()
        val links = ArrayDeque(listOf(control, data))
        val samples = ArrayList<Double>()
        val script = async {
            val cookie = serverControl.bytes(Iperf3.COOKIE_SIZE)
            serverControl.write(ints(9))
            assertContentEquals(ints(0, 0, 0, 0x63), serverControl.bytes(4))
            assertEquals(Iperf3.parameters(2, 1, false), serverControl.bytes(0x63).decodeToString())
            serverControl.write(ints(10))
            assertContentEquals(cookie, serverData.bytes(Iperf3.COOKIE_SIZE))
            serverControl.write(ints(1))
            serverControl.write(ints(2))
            val drain = launch {
                val buffer = ByteArray(Iperf3.BLOCK)
                while (serverData.read(buffer) >= 0) Unit
            }
            assertContentEquals(ints(4), serverControl.bytes(1))
            serverControl.write(ints(13))
            val report = assertNotNull(Iperf3.report(serverControl.json()))
            serverControl.write(ints(0, 0, 1, 0x2D) + SERVER_REPORT.encodeToByteArray() + ints(14))
            assertContentEquals(ints(16), serverControl.bytes(1))
            drain.cancel()
            report
        }
        val result = iperfClient(2, 1, false, { download, _, mbps ->
            assertFalse(download)
            samples += mbps
        }) { links.removeFirst() }
        assertEquals(List(2) { speedMbps(11L * Iperf3.BLOCK, 1_000) }, samples)
        assertEquals(23L * Iperf3.BLOCK, result.sent)
        assertEquals(23L * Iperf3.BLOCK, script.await().bytes)
        assertEquals(191496192L, result.received)
        assertEquals(2.008968, result.seconds)
        assertFalse(result.download)
    }

    @Test
    fun reverseAgainstRecordedServer() = runTest {
        val (control, serverControl) = pipe()
        val data = List(2) { pipe() }
        val links = ArrayDeque(listOf(control) + data.map { it.first })
        val script = async {
            val cookie = serverControl.bytes(Iperf3.COOKIE_SIZE)
            serverControl.write(ints(9))
            assertContentEquals(ints(0, 0, 0, 0x72), serverControl.bytes(4))
            assertEquals(Iperf3.parameters(2, 2, true), serverControl.bytes(0x72).decodeToString())
            serverControl.write(ints(10))
            data.forEach { (_, far) -> assertContentEquals(cookie, far.bytes(Iperf3.COOKIE_SIZE)) }
            serverControl.write(ints(1, 2))
            data.forEach { (_, far) -> far.write(ByteArray(1_000)) }
            assertContentEquals(ints(4), serverControl.bytes(1))
            data.forEach { (_, far) -> far.close() }
            serverControl.write(ints(13))
            val written = serverControl.json()
            serverControl.write(ints(0, 0, 1, 0xC3) + REVERSE_SERVER_REPORT.encodeToByteArray() + ints(14))
            assertContentEquals(ints(16), serverControl.bytes(1))
            written
        }
        val result = iperfClient(2, 2, true, { download, _, _ -> assertTrue(download) }) { links.removeFirst() }
        val written = script.await()
        assertTrue(""""sender_has_retransmits":-1""" in written)
        assertTrue(""""id":1,"bytes":1000""" in written)
        assertTrue(""""id":3,"bytes":1000""" in written)
        assertTrue(result.download)
        assertEquals(2_000L, result.received)
        assertEquals(543424512L, result.sent)
        assertNull(result.retransmits)
    }

    private suspend fun refusal(vararg bytes: Int): NetException {
        val (control, server) = pipe()
        server.write(ints(*bytes))
        return assertFailsWith<NetException> { iperfClient(2, 1, false, { _, _, _ -> }) { control } }
    }

    @Test
    fun clientExplainsRefusals() = runTest {
        assertEquals(Res.string.lan_speed_busy, refusal(0xFF).reason)
        assertEquals(Res.string.lan_speed_terminated, refusal(11).reason)
        assertEquals(Res.string.lan_speed_bad_reply, refusal('H'.code).reason)
        // the error frame iperf 3.21 sent on the recording: i_errno 206, errno 2
        val error = refusal(0xFE, 0, 0, 0, 0xCE, 0, 0, 0, 2)
        assertEquals(Res.string.lan_speed_server_error, error.reason)
        assertEquals("206", error.detail)
    }

    @Test
    fun waitingSideFollowsRecordedClient() = runTest {
        val listener = Listener()
        val results = ArrayList<LanSpeedResult>()
        val server = launch { iperfServer(listener, { _, _, _ -> }, { results += it }) }
        val cookie = COOKIE.encodeToByteArray() + ints(0)
        val control = listener.dial()
        control.write(cookie)
        assertContentEquals(ints(9), control.bytes(1))
        control.write(ints(0, 0, 0, 0x7B) + CLIENT_PARAMETERS.encodeToByteArray())
        assertContentEquals(ints(10), control.bytes(1))
        val stranger = listener.dial()
        stranger.write(ByteArray(Iperf3.COOKIE_SIZE) { 'x'.code.toByte() })
        assertContentEquals(ints(0xFF), stranger.bytes(1))
        val data = listener.dial()
        data.write(cookie)
        assertContentEquals(ints(1, 2), control.bytes(2))
        repeat(3) { data.write(ByteArray(1_000)) }
        control.write(ints(4))
        assertContentEquals(ints(13), control.bytes(1))
        control.write(ints(0, 0, 1, 0x1A) + CLIENT_REPORT.encodeToByteArray())
        val report = assertNotNull(Iperf3.report(control.json()))
        assertEquals(3_000L, report.bytes)
        assertContentEquals(ints(14), control.bytes(1))
        control.write(ints(16))
        testScheduler.advanceUntilIdle()
        server.cancelAndJoin()
        val result = results.single()
        assertTrue(result.download)
        assertEquals(3_000L, result.received)
        assertEquals(191496192L, result.sent)
    }

    @Test
    fun waitingSideRefusesUdp() = runTest {
        val listener = Listener()
        val server = launch { iperfServer(listener, { _, _, _ -> }, { }) }
        val control = listener.dial()
        control.write(COOKIE.encodeToByteArray() + ints(0))
        assertContentEquals(ints(9), control.bytes(1))
        control.write(Iperf3.frame("""{"udp":true,"omit":0,"time":2,"parallel":1}"""))
        assertContentEquals(ints(0xFF), control.bytes(1))
        server.cancelAndJoin()
    }

    @Test
    fun twoCopiesMeasureBothDirections() = runTest {
        val listener = Listener()
        val seen = ArrayList<LanSpeedResult>()
        val server = launch { iperfServer(listener, { _, _, _ -> }, { seen += it }) }
        val upload = iperfClient(2, 2, false, { _, _, _ -> }) { listener.dial() }
        val download = iperfClient(2, 2, true, { _, _, _ -> }) { listener.dial() }
        testScheduler.advanceUntilIdle()
        server.cancelAndJoin()
        assertFalse(upload.download)
        assertTrue(upload.received > 0)
        assertEquals(upload.sent, upload.received)
        assertTrue(download.download)
        assertTrue(download.received > 0)
        assertTrue(download.sent >= download.received)
        assertEquals(listOf(true, false), seen.map { it.download })
        assertEquals(upload.received, seen[0].received)
    }
}
