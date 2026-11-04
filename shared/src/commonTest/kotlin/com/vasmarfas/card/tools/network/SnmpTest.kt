package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// RFC 1157 and RFC 3416 define the messages in ASN.1 and carry no hex dumps, so these bytes are encoded by hand from
// those definitions, with application tags from RFC 2578 and the OID rule from X.690 section 8.19
class SnmpTest {
    private val sysDescr = "2B 06 01 02 01 01 01 00"
    private val sysUpTime = "2B 06 01 02 01 01 03 00"
    private val sysName = "2B 06 01 02 01 01 05 00"
    private val ifNumber = "2B 06 01 02 01 02 01 00"

    private fun hex(text: String): ByteArray = assertNotNull(SocketPayload.hex(text))

    private fun tlv(tag: Int, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { all, part -> all + part }
        return byteArrayOf(tag.toByte(), body.size.toByte()) + body
    }

    private fun binding(oid: String, value: String): ByteArray = tlv(0x30, tlv(0x06, hex(oid)), hex(value))

    private fun response(id: Int, status: Int, index: Int, vararg bindings: ByteArray, version: Int = 1): ByteArray {
        val requestId = ByteArray(4) { (id shr (24 - 8 * it)).toByte() }
        return tlv(
            0x30,
            hex("02 01 0$version 04 06 70 75 62 6C 69 63"),
            tlv(0xA2, tlv(0x02, requestId), byteArrayOf(2, 1, status.toByte()), byteArrayOf(2, 1, index.toByte()), tlv(0x30, *bindings)),
        )
    }

    private fun value(text: String): SnmpVarBind = assertNotNull(Snmp.parse(response(1, 0, 0, binding(sysDescr, text)))).bindings.single()

    private fun agent(vararg replies: (Int) -> ByteArray?): suspend (ByteArray) -> ByteArray? {
        val queue = ArrayDeque(replies.toList())
        return { request -> queue.removeFirst()(assertNotNull(Snmp.parse(request)).requestId) }
    }

    @Test
    fun getRequestMatchesRfc1157Layout() {
        val expected = hex("30 26 02 01 00 04 06 70 75 62 6C 69 63 A0 19 02 01 01 02 01 00 02 01 00 30 0E 30 0C 06 08 2B 06 01 02 01 01 01 00 05 00")
        assertContentEquals(expected, Snmp.request(SnmpVersion.V1, "public", Snmp.GET, 1, "1.3.6.1.2.1.1.1.0"))
    }

    @Test
    fun getNextCarriesTagA1AndVersionOneForV2c() {
        val expected = hex("30 26 02 01 01 04 06 70 75 62 6C 69 63 A1 19 02 01 07 02 01 00 02 01 00 30 0E 30 0C 06 08 2B 06 01 02 01 01 03 00 05 00")
        assertContentEquals(expected, Snmp.request(SnmpVersion.V2C, "public", Snmp.GET_NEXT, 7, "1.3.6.1.2.1.1.3.0"))
    }

    @Test
    fun requestIdKeepsItsPositiveSign() {
        val request = Snmp.request(SnmpVersion.V1, "c", Snmp.GET, 200, "1.3")
        assertContentEquals(hex("02 02 00 C8"), request.copyOfRange(10, 14))
        assertEquals(Int.MAX_VALUE, Snmp.parse(Snmp.request(SnmpVersion.V1, "c", Snmp.GET, Int.MAX_VALUE, "1.3"))?.requestId)
    }

    @Test
    fun longCommunityUsesLongFormLength() {
        val community = "c".repeat(300)
        val request = Snmp.request(SnmpVersion.V2C, community, Snmp.GET, 1, "1.3.6.1.2.1.1.1.0")
        assertEquals(0x82, request[1].toInt() and 0xFF)
        assertEquals(community, Snmp.parse(request)?.community)
    }

    @Test
    fun oidEncodingFollowsX690() {
        val request = Snmp.request(SnmpVersion.V1, "c", Snmp.GET, 1, "2.999.3")
        assertContentEquals(hex("06 03 88 37 03 05 00"), request.copyOfRange(request.size - 7, request.size))
        assertEquals("2.999.3", value("06 03 88 37 03").text)
        assertEquals("1.3.6.1.4.1.2021", value("06 07 2B 06 01 04 01 8F 65").text)
    }

    @Test
    fun parsesResponseFromRfc3416Layout() {
        val reply = hex("30 2B 02 01 01 04 06 70 75 62 6C 69 63 A2 1E 02 01 01 02 01 00 02 01 00 30 13 30 11 06 08 2B 06 01 02 01 01 01 00 04 05 4C 69 6E 75 78")
        val message = assertNotNull(Snmp.parse(reply))
        assertEquals(1, message.version)
        assertEquals("public", message.community)
        assertEquals(Snmp.RESPONSE, message.pdu)
        assertEquals(1, message.requestId)
        assertEquals(0, message.errorStatus)
        assertEquals("1.3.6.1.2.1.1.1.0", message.bindings.single().oid)
        assertEquals("Linux", message.bindings.single().text)
    }

    @Test
    fun parsesLengthsInLongForm() {
        val reply = hex("30 81 2F 02 01 01 04 06 70 75 62 6C 69 63 A2 84 00 00 00 1E 02 01 01 02 01 00 02 01 00 30 13 30 11 06 08 2B 06 01 02 01 01 01 00 04 05 4C 69 6E 75 78")
        assertEquals("Linux", Snmp.parse(reply)?.bindings?.single()?.text)
    }

    @Test
    fun readsApplicationTypes() {
        assertEquals("-1", value("02 01 FF").text)
        assertEquals("-129", value("02 02 FF 7F").text)
        assertEquals("192.0.2.1", value("40 04 C0 00 02 01").text)
        assertEquals("4294967295", value("41 04 FF FF FF FF").text)
        assertEquals("4294967295", value("42 05 00 FF FF FF FF").text)
        assertEquals(123456uL, value("43 03 01 E2 40").number)
        assertEquals("18446744073709551615", value("46 09 00 FF FF FF FF FF FF FF FF").text)
        assertEquals("NULL", value("05 00").text)
    }

    @Test
    fun readsRfc3416Exceptions() {
        assertEquals("noSuchObject", value("80 00").text)
        assertEquals("noSuchInstance", value("81 00").text)
        assertEquals("endOfMibView", value("82 00").text)
    }

    @Test
    fun binaryOctetStringFallsBackToHex() {
        assertEquals("00:00:5e:00:53:01", value("04 06 00 00 5E 00 53 01").text)
        assertEquals("router", value("04 07 72 6F 75 74 65 72 00").text)
        assertEquals("0", value("02 00").text)
    }

    @Test
    fun rejectsDamagedMessages() {
        assertNull(Snmp.parse(ByteArray(0)))
        assertNull(Snmp.parse(hex("30")))
        assertNull(Snmp.parse(hex("30 7F 02 01 00")))
        assertNull(Snmp.parse(hex("04 03 61 62 63")))
        assertNull(Snmp.parse(hex("30 84 7F FF FF FF 02 01 00")))
        assertNull(Snmp.parse(hex("30 2B 02 01 01 04 06 70 75 62 6C 69 63 A2 1E 02 01 01 02 01 00 02 01 00 30 13 30 11 06 08 2B 06 01 02")))
    }

    @Test
    fun resolvesNamesAndNumbers() {
        assertEquals("1.3.6.1.2.1.1.1.0", Snmp.resolve("sysDescr.0"))
        assertEquals("1.3.6.1.2.1.2.2.1.10.3", Snmp.resolve("ifInOctets.3"))
        assertEquals("1.3.6.1.2.1.31.1.1.1.6", Snmp.resolve("ifHCInOctets"))
        assertEquals("1.3.6.1.2.1.1", Snmp.resolve(" .1.3.6.1.2.1.1 "))
        assertNull(Snmp.resolve("noSuchName.0"))
        assertNull(Snmp.resolve("3.1"))
        assertNull(Snmp.resolve("1.40"))
        assertNull(Snmp.resolve("1.3.4294967296"))
        assertNull(Snmp.resolve(""))
    }

    @Test
    fun namesOidsByLongestPrefix() {
        assertEquals("sysUpTime.0", Snmp.name("1.3.6.1.2.1.1.3.0"))
        assertEquals("ifOutErrors.12", Snmp.name("1.3.6.1.2.1.2.2.1.20.12"))
        assertEquals("system.9.1", Snmp.name("1.3.6.1.2.1.1.9.1"))
        assertEquals("1.3.6.1.4.1.2021", Snmp.name("1.3.6.1.4.1.2021"))
    }

    @Test
    fun comparesOidsArcByArc() {
        assertTrue(Snmp.after("1.3.6.10", "1.3.6.9"))
        assertTrue(Snmp.after("1.3.6.1", "1.3.6"))
        assertTrue(!Snmp.after("1.3.6.1", "1.3.6.1"))
        assertTrue(!Snmp.after("1.3.5.100", "1.3.6"))
    }

    @Test
    fun errorNamesComeFromRfc3416() {
        assertEquals("noSuchName", Snmp.errorName(2))
        assertEquals("genErr", Snmp.errorName(5))
        assertEquals("inconsistentName", Snmp.errorName(18))
        assertEquals("19", Snmp.errorName(19))
    }

    @Test
    fun getReportsAgentErrorAndSilence() = runTest {
        val refused = SnmpSession(SnmpVersion.V1, "public", agent({ id -> response(id, 2, 1, binding(sysDescr, "05 00"), version = 0) }))
        val error = assertFailsWith<NetException> { refused.get("1.3.6.1.2.1.1.1.0") }
        assertEquals(Res.string.snmp_agent_error, error.reason)
        assertEquals("noSuchName", error.detail)

        val silent = SnmpSession(SnmpVersion.V2C, "public", agent({ null }, { null }))
        assertEquals(Res.string.snmp_no_response, assertFailsWith<NetException> { silent.get("1.3.6.1.2.1.1.1.0") }.reason)
    }

    @Test
    fun getRepeatsOnceAfterALostDatagram() = runTest {
        val session = SnmpSession(SnmpVersion.V2C, "public", agent({ null }, { id -> response(id, 0, 0, binding(sysDescr, "04 02 6F 6B")) }))
        assertEquals("ok", session.get("1.3.6.1.2.1.1.1.0").text)
    }

    @Test
    fun repliesToAnotherRequestAreIgnored() = runTest {
        val session = SnmpSession(SnmpVersion.V2C, "public", agent({ id -> response(id + 1, 0, 0, binding(sysDescr, "04 02 6F 6B")) }))
        assertFailsWith<NetException> { session.get("1.3.6.1.2.1.1.1.0") }
    }

    @Test
    fun v2cWalkEndsOnEndOfMibView() = runTest {
        val session = SnmpSession(
            SnmpVersion.V2C,
            "public",
            agent(
                { id -> response(id, 0, 0, binding(sysDescr, "04 02 6F 6B")) },
                { id -> response(id, 0, 0, binding(sysUpTime, "43 01 64")) },
                { id -> response(id, 0, 0, binding(sysName, "82 00")) },
            ),
        )
        val rows = ArrayList<SnmpVarBind>()
        assertEquals(SnmpWalkEnd.DONE, session.walk("1.3.6.1.2.1.1", 100) { rows += it })
        assertEquals(listOf("1.3.6.1.2.1.1.1.0", "1.3.6.1.2.1.1.3.0"), rows.map { it.oid })
    }

    @Test
    fun v1WalkEndsOnNoSuchName() = runTest {
        val session = SnmpSession(
            SnmpVersion.V1,
            "public",
            agent(
                { id -> response(id, 0, 0, binding(sysDescr, "04 02 6F 6B"), version = 0) },
                { id -> response(id, 2, 1, binding(sysDescr, "05 00"), version = 0) },
            ),
        )
        val rows = ArrayList<SnmpVarBind>()
        assertEquals(SnmpWalkEnd.DONE, session.walk("1.3.6.1.2.1.1", 100) { rows += it })
        assertEquals(1, rows.size)
    }

    @Test
    fun walkStopsAtTheSubtreeEdge() = runTest {
        val session = SnmpSession(SnmpVersion.V2C, "public", agent({ id -> response(id, 0, 0, binding(ifNumber, "02 01 04")) }))
        val rows = ArrayList<SnmpVarBind>()
        assertEquals(SnmpWalkEnd.DONE, session.walk("1.3.6.1.2.1.1", 100) { rows += it })
        assertTrue(rows.isEmpty())
    }

    @Test
    fun walkStopsAtTheLimit() = runTest {
        val session = SnmpSession(
            SnmpVersion.V2C,
            "public",
            agent(
                { id -> response(id, 0, 0, binding(sysDescr, "04 02 6F 6B")) },
                { id -> response(id, 0, 0, binding(sysUpTime, "43 01 64")) },
            ),
        )
        val rows = ArrayList<SnmpVarBind>()
        assertEquals(SnmpWalkEnd.LIMIT, session.walk("1.3.6.1.2.1.1", 2) { rows += it })
        assertEquals(2, rows.size)
    }

    @Test
    fun walkDetectsAnAgentThatGoesBackwards() = runTest {
        val session = SnmpSession(
            SnmpVersion.V2C,
            "public",
            agent(
                { id -> response(id, 0, 0, binding(sysUpTime, "43 01 64")) },
                { id -> response(id, 0, 0, binding(sysDescr, "04 02 6F 6B")) },
            ),
        )
        assertEquals(SnmpWalkEnd.LOOP, session.walk("1.3.6.1.2.1.1", 100) {})
    }

    @Test
    fun walkKeepsRowsWhenTheAgentFallsSilent() = runTest {
        val session = SnmpSession(SnmpVersion.V2C, "public", agent({ id -> response(id, 0, 0, binding(sysDescr, "04 02 6F 6B")) }, { null }, { null }))
        val rows = ArrayList<SnmpVarBind>()
        assertEquals(SnmpWalkEnd.SILENCE, session.walk("1.3.6.1.2.1.1", 100) { rows += it })
        assertEquals(1, rows.size)
    }

    @Test
    fun buildsInterfaceTable() {
        fun cell(column: String, index: Int, text: String): SnmpVarBind {
            val raw = hex(text)
            return SnmpVarBind("${assertNotNull(Snmp.resolve(column))}.$index", raw[0].toInt() and 0xFF, raw.copyOfRange(2, raw.size))
        }
        val rows = Snmp.interfaces(
            listOf(
                cell("ifDescr", 10, "04 04 65 74 68 31"),
                cell("ifDescr", 2, "04 04 65 74 68 30"),
                cell("ifType", 2, "02 01 06"),
                cell("ifType", 10, "02 02 03 E7"),
                cell("ifSpeed", 2, "42 04 3B 9A CA 00"),
                cell("ifSpeed", 10, "42 04 FF FF FF FF"),
                cell("ifHighSpeed", 10, "42 02 27 10"),
                cell("ifPhysAddress", 2, "04 06 00 00 5E 00 53 01"),
                cell("ifAdminStatus", 2, "02 01 01"),
                cell("ifOperStatus", 2, "02 01 02"),
                cell("ifOperStatus", 10, "02 01 07"),
                cell("ifInOctets", 2, "41 01 64"),
                cell("ifHCInOctets", 10, "46 05 01 00 00 00 00"),
                cell("ifInOctets", 10, "41 01 05"),
                cell("ifInErrors", 2, "41 01 03"),
            ),
        )
        assertEquals(listOf("2", "10"), rows.map { it.index })
        val first = rows[0]
        assertEquals("eth0", first.name)
        assertEquals("ethernetCsmacd", first.type)
        assertEquals(1_000_000_000uL, first.speedBits)
        assertEquals("00:00:5e:00:53:01", first.mac)
        assertEquals("up", first.admin)
        assertEquals("down", first.oper)
        assertEquals(100uL, first.inOctets)
        assertEquals(3uL, first.inErrors)
        val second = rows[1]
        assertEquals("999", second.type)
        assertEquals(10_000_000_000uL, second.speedBits)
        assertEquals("lowerLayerDown", second.oper)
        assertEquals(4_294_967_296uL, second.inOctets)
    }
}
