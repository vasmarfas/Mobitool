package com.vasmarfas.card.tools.network

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.resources.*
import kotlin.random.Random

enum class SnmpVersion(val wire: Int, val label: String) {
    V1(0, "v1"),
    V2C(1, "v2c"),
}

enum class SnmpWalkEnd { DONE, LIMIT, LOOP, SILENCE }

class SnmpVarBind(val oid: String, val tag: Int, val raw: ByteArray) {
    val number: ULong get() = raw.fold(0uL) { acc, byte -> (acc shl 8) or byte.toUByte().toULong() }

    val text: String get() = when (tag) {
        Snmp.INTEGER -> signed(raw).toString()
        Snmp.OCTET_STRING -> octets(raw)
        Snmp.NULL -> "NULL"
        Snmp.OID -> decodeOid(raw)
        Snmp.IP_ADDRESS -> raw.joinToString(".") { (it.toInt() and 0xFF).toString() }
        Snmp.COUNTER32, Snmp.GAUGE32, Snmp.TIMETICKS, Snmp.COUNTER64 -> number.toString()
        Snmp.NO_SUCH_OBJECT -> "noSuchObject"
        Snmp.NO_SUCH_INSTANCE -> "noSuchInstance"
        Snmp.END_OF_MIB -> "endOfMibView"
        else -> hexColon(raw)
    }
}

class SnmpMessage(
    val version: Int,
    val community: String,
    val pdu: Int,
    val requestId: Int,
    val errorStatus: Int,
    val errorIndex: Int,
    val bindings: List<SnmpVarBind>,
)

class SnmpInterface(
    val index: String,
    val name: String,
    val type: String,
    val speedBits: ULong,
    val mac: String,
    val admin: String,
    val oper: String,
    val inOctets: ULong,
    val outOctets: ULong,
    val inErrors: ULong,
    val outErrors: ULong,
)

private class BerNode(val tag: Int, val value: ByteArray)

private fun tlvs(bytes: ByteArray): List<BerNode> {
    val out = ArrayList<BerNode>()
    var at = 0
    while (at < bytes.size) {
        val tag = bytes[at++].toInt() and 0xFF
        var length = bytes[at++].toInt() and 0xFF
        if (length >= 0x80) {
            val count = length and 0x7F
            require(count in 1..4)
            length = 0
            repeat(count) { length = (length shl 8) or (bytes[at++].toInt() and 0xFF) }
        }
        require(length >= 0 && length <= bytes.size - at)
        out += BerNode(tag, bytes.copyOfRange(at, at + length))
        at += length
    }
    return out
}

private fun signed(raw: ByteArray): Long =
    raw.fold(if (raw.isNotEmpty() && raw[0] < 0) -1L else 0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }

private fun hexColon(raw: ByteArray): String = raw.joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun octets(raw: ByteArray): String {
    val text = raw.decodeToString().trimEnd { it.code == 0 }
    return if (text.any { it.code == 0xFFFD || (it.isISOControl() && it !in "\r\n\t") }) hexColon(raw) else text
}

private fun decodeOid(raw: ByteArray): String {
    val arcs = ArrayList<Long>()
    var arc = 0L
    raw.forEach { byte ->
        arc = (arc shl 7) or (byte.toLong() and 0x7F)
        if (byte >= 0) {
            arcs += arc
            arc = 0
        }
    }
    val first = arcs.firstOrNull() ?: return ""
    val root = minOf(first / 40, 2L)
    return (listOf(root, first - root * 40) + arcs.drop(1)).joinToString(".")
}

private fun encodeArc(value: Long): List<Byte> {
    val groups = generateSequence(value) { (it shr 7).takeIf { rest -> rest > 0 } }.map { (it and 0x7F).toInt() }.toList().reversed()
    return groups.mapIndexed { index, group -> (if (index < groups.lastIndex) group or 0x80 else group).toByte() }
}

private fun encodeOid(oid: String): ByteArray {
    val arcs = oid.split('.').map { it.toLong() }
    return (listOf(arcs[0] * 40 + arcs[1]) + arcs.drop(2)).flatMap { encodeArc(it) }.toByteArray()
}

private fun encodeTlv(tag: Int, value: ByteArray): ByteArray {
    val size = value.size
    val length = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
        else -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
    }
    return byteArrayOf(tag.toByte()) + length + value
}

private fun encodeInteger(value: Int): ByteArray {
    val bytes = ByteArray(4) { (value shr (24 - 8 * it)).toByte() }
    var from = 0
    while (from < 3 && bytes[from].toInt() == 0 && bytes[from + 1] >= 0) from++
    return encodeTlv(Snmp.INTEGER, bytes.copyOfRange(from, 4))
}

object Snmp {
    const val INTEGER = 0x02
    const val OCTET_STRING = 0x04
    const val NULL = 0x05
    const val OID = 0x06
    const val SEQUENCE = 0x30
    const val IP_ADDRESS = 0x40
    const val COUNTER32 = 0x41
    const val GAUGE32 = 0x42
    const val TIMETICKS = 0x43
    const val COUNTER64 = 0x46
    const val NO_SUCH_OBJECT = 0x80
    const val NO_SUCH_INSTANCE = 0x81
    const val END_OF_MIB = 0x82
    const val GET = 0xA0
    const val GET_NEXT = 0xA1
    const val RESPONSE = 0xA2
    const val NO_SUCH_NAME = 2

    private const val MIB2 = "1.3.6.1.2.1"
    private const val IF_ENTRY = "$MIB2.2.2.1"
    private const val IF_X_ENTRY = "$MIB2.31.1.1.1"

    private val errors = listOf(
        "noError", "tooBig", "noSuchName", "badValue", "readOnly", "genErr", "noAccess", "wrongType", "wrongLength", "wrongEncoding",
        "wrongValue", "noCreation", "inconsistentValue", "resourceUnavailable", "commitFailed", "undoFailed", "authorizationError",
        "notWritable", "inconsistentName",
    )

    private val ifStates = listOf("up", "down", "testing", "unknown", "dormant", "notPresent", "lowerLayerDown")

    private val ifTypes = mapOf(
        1 to "other", 6 to "ethernetCsmacd", 23 to "ppp", 24 to "softwareLoopback", 53 to "propVirtual", 71 to "ieee80211",
        131 to "tunnel", 135 to "l2vlan", 136 to "l3ipvlan", 161 to "ieee8023adLag", 209 to "bridge",
    )

    private val oids: Map<String, String> = buildMap {
        fun column(base: String, names: List<String>) = names.forEachIndexed { index, name -> put(name, "$base.${index + 1}") }
        put("mib-2", MIB2)
        put("system", "$MIB2.1")
        put("interfaces", "$MIB2.2")
        put("ip", "$MIB2.4")
        put("icmp", "$MIB2.5")
        put("tcp", "$MIB2.6")
        put("udp", "$MIB2.7")
        put("snmp", "$MIB2.11")
        put("ifNumber", "$MIB2.2.1")
        put("ifTable", "$MIB2.2.2")
        put("ifEntry", IF_ENTRY)
        put("ifXTable", "$MIB2.31.1.1")
        put("ifXEntry", IF_X_ENTRY)
        column("$MIB2.1", listOf("sysDescr", "sysObjectID", "sysUpTime", "sysContact", "sysName", "sysLocation", "sysServices"))
        column(
            IF_ENTRY,
            listOf(
                "ifIndex", "ifDescr", "ifType", "ifMtu", "ifSpeed", "ifPhysAddress", "ifAdminStatus", "ifOperStatus", "ifLastChange", "ifInOctets",
                "ifInUcastPkts", "ifInNUcastPkts", "ifInDiscards", "ifInErrors", "ifInUnknownProtos", "ifOutOctets", "ifOutUcastPkts",
                "ifOutNUcastPkts", "ifOutDiscards", "ifOutErrors", "ifOutQLen", "ifSpecific",
            ),
        )
        column(
            IF_X_ENTRY,
            listOf(
                "ifName", "ifInMulticastPkts", "ifInBroadcastPkts", "ifOutMulticastPkts", "ifOutBroadcastPkts", "ifHCInOctets", "ifHCInUcastPkts",
                "ifHCInMulticastPkts", "ifHCInBroadcastPkts", "ifHCOutOctets", "ifHCOutUcastPkts", "ifHCOutMulticastPkts", "ifHCOutBroadcastPkts",
                "ifLinkUpDownTrapEnable", "ifHighSpeed", "ifPromiscuousMode", "ifConnectorPresent", "ifAlias", "ifCounterDiscontinuityTime",
            ),
        )
    }

    private val names: Map<String, String> = oids.entries.associate { it.value to it.key }

    val interfaceColumns: List<String> = listOf(
        "ifDescr", "ifType", "ifSpeed", "ifPhysAddress", "ifAdminStatus", "ifOperStatus", "ifInOctets", "ifInErrors", "ifOutOctets", "ifOutErrors",
        "ifHighSpeed", "ifHCInOctets", "ifHCOutOctets",
    ).map { oids.getValue(it) }

    fun errorName(status: Int): String = errors.getOrElse(status) { status.toString() }

    fun resolve(input: String): String? {
        val text = input.trim().removePrefix(".")
        val name = text.substringBefore('.')
        val oid = oids[name]?.plus(text.removePrefix(name)) ?: text
        val arcs = oid.split('.').map { it.toLongOrNull() ?: return null }
        val valid = arcs.size >= 2 && arcs[0] in 0..2 && (arcs[0] == 2L || arcs[1] < 40) && arcs.all { it in 0..0xFFFFFFFFL }
        return oid.takeIf { valid }
    }

    fun name(oid: String): String =
        generateSequence(oid) { it.substringBeforeLast('.', "").ifEmpty { null } }.firstNotNullOfOrNull { base -> names[base]?.plus(oid.removePrefix(base)) } ?: oid

    fun after(next: String, previous: String): Boolean {
        val a = next.split('.').map { it.toLong() }
        val b = previous.split('.').map { it.toLong() }
        a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> return x > y }
        return a.size > b.size
    }

    fun request(version: SnmpVersion, community: String, pdu: Int, id: Int, oid: String): ByteArray {
        val binding = encodeTlv(SEQUENCE, encodeTlv(OID, encodeOid(oid)) + byteArrayOf(NULL.toByte(), 0))
        val body = encodeInteger(id) + encodeInteger(0) + encodeInteger(0) + encodeTlv(SEQUENCE, binding)
        return encodeTlv(SEQUENCE, encodeInteger(version.wire) + encodeTlv(OCTET_STRING, community.encodeToByteArray()) + encodeTlv(pdu, body))
    }

    fun parse(bytes: ByteArray): SnmpMessage? = runCatching {
        val message = tlvs(bytes).single()
        require(message.tag == SEQUENCE)
        val (version, community, pdu) = tlvs(message.value)
        val (id, status, index, list) = tlvs(pdu.value)
        require(list.tag == SEQUENCE)
        val bindings = tlvs(list.value).map { entry ->
            val (name, value) = tlvs(entry.value)
            require(name.tag == OID)
            SnmpVarBind(decodeOid(name.value), value.tag, value.value)
        }
        SnmpMessage(signed(version.value).toInt(), community.value.decodeToString(), pdu.tag, signed(id.value).toInt(), signed(status.value).toInt(), signed(index.value).toInt(), bindings)
    }.getOrNull()

    fun interfaces(bindings: List<SnmpVarBind>): List<SnmpInterface> =
        bindings.groupBy { it.oid.substringAfterLast('.') }.entries.sortedBy { it.key.toLong() }.map { (index, row) ->
            val cells = row.associateBy { it.oid.substringBeforeLast('.') }
            fun cell(name: String) = cells[oids.getValue(name)]
            fun count(name: String) = cell(name)?.number ?: 0uL
            fun state(name: String) = cell(name)?.number?.toInt()?.let { ifStates.getOrElse(it - 1) { _ -> it.toString() } }.orEmpty()
            SnmpInterface(
                index = index,
                name = cell("ifDescr")?.text.orEmpty(),
                type = cell("ifType")?.number?.toInt()?.let { ifTypes[it] ?: it.toString() }.orEmpty(),
                speedBits = count("ifHighSpeed").takeIf { it > 0uL }?.let { it * 1_000_000uL } ?: count("ifSpeed"),
                mac = cell("ifPhysAddress")?.raw?.let(::hexColon).orEmpty(),
                admin = state("ifAdminStatus"),
                oper = state("ifOperStatus"),
                inOctets = (cell("ifHCInOctets") ?: cell("ifInOctets"))?.number ?: 0uL,
                outOctets = (cell("ifHCOutOctets") ?: cell("ifOutOctets"))?.number ?: 0uL,
                inErrors = count("ifInErrors"),
                outErrors = count("ifOutErrors"),
            )
        }
}

class SnmpSession(private val version: SnmpVersion, private val community: String, private val exchange: suspend (ByteArray) -> ByteArray?) {
    // UDP loses datagrams, one repeat keeps a long walk from dying on a single drop
    private suspend fun ask(pdu: Int, oid: String): SnmpMessage? {
        val id = Random.nextInt(1, Int.MAX_VALUE)
        val request = Snmp.request(version, community, pdu, id, oid)
        val reply = exchange(request) ?: exchange(request) ?: return null
        return Snmp.parse(reply)?.takeIf { it.pdu == Snmp.RESPONSE && it.requestId == id }
    }

    suspend fun get(oid: String): SnmpVarBind {
        val reply = ask(Snmp.GET, oid) ?: throw NetException(Res.string.snmp_no_response)
        if (reply.errorStatus != 0) throw NetException(Res.string.snmp_agent_error, Snmp.errorName(reply.errorStatus))
        return reply.bindings.firstOrNull() ?: throw NetException(Res.string.snmp_no_response)
    }

    suspend fun walk(root: String, limit: Int, onBinding: (SnmpVarBind) -> Unit): SnmpWalkEnd {
        var oid = root
        var count = 0
        while (count < limit) {
            val reply = ask(Snmp.GET_NEXT, oid)
            if (reply == null) {
                if (count == 0) throw NetException(Res.string.snmp_no_response)
                return SnmpWalkEnd.SILENCE
            }
            if (reply.errorStatus == Snmp.NO_SUCH_NAME) return SnmpWalkEnd.DONE
            if (reply.errorStatus != 0) throw NetException(Res.string.snmp_agent_error, Snmp.errorName(reply.errorStatus))
            val next = reply.bindings.firstOrNull() ?: return SnmpWalkEnd.DONE
            if (next.tag == Snmp.END_OF_MIB || !next.oid.startsWith("$root.")) return SnmpWalkEnd.DONE
            if (!Snmp.after(next.oid, oid)) return SnmpWalkEnd.LOOP
            onBinding(next)
            oid = next.oid
            count++
        }
        return SnmpWalkEnd.LIMIT
    }

    suspend fun interfaces(limit: Int): List<SnmpInterface> {
        val rows = ArrayList<SnmpVarBind>()
        Snmp.interfaceColumns.forEach { column ->
            if (walk(column, limit) { rows += it } == SnmpWalkEnd.SILENCE) throw NetException(Res.string.snmp_no_response)
        }
        return Snmp.interfaces(rows)
    }
}
