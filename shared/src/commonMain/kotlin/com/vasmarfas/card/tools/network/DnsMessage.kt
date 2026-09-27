package com.vasmarfas.card.tools.network

data class DnsRecord(
    val name: String,
    val type: Int,
    val ttl: Long,
    val data: String,
) {
    val typeName: String get() = DnsTypes.name(type)
}

data class DnsResponse(
    val id: Int,
    val rcode: Int,
    val authoritative: Boolean,
    val authenticData: Boolean,
    val truncated: Boolean,
    val answers: List<DnsRecord>,
    val authority: List<DnsRecord>,
    val additional: List<DnsRecord>,
) {
    val rcodeName: String get() = DnsTypes.rcodeName(rcode)
}

object DnsTypes {
    val byName: Map<String, Int> = mapOf(
        "A" to 1, "NS" to 2, "CNAME" to 5, "SOA" to 6, "PTR" to 12, "HINFO" to 13, "MX" to 15, "TXT" to 16,
        "AAAA" to 28, "SRV" to 33, "NAPTR" to 35, "DS" to 43, "RRSIG" to 46, "NSEC" to 47, "DNSKEY" to 48,
        "TLSA" to 52, "HTTPS" to 65, "SVCB" to 64, "SPF" to 99, "CAA" to 257, "ANY" to 255,
    )
    private val byType: Map<Int, String> = byName.entries.associate { it.value to it.key }

    val queryable: List<String> = listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA", "SRV", "PTR", "CAA", "HTTPS", "DNSKEY", "DS", "ANY")

    fun name(type: Int): String = byType[type] ?: "TYPE$type"

    fun rcodeName(rcode: Int): String = when (rcode) {
        0 -> "NOERROR"
        1 -> "FORMERR"
        2 -> "SERVFAIL"
        3 -> "NXDOMAIN"
        4 -> "NOTIMP"
        5 -> "REFUSED"
        else -> "RCODE$rcode"
    }
}

object DnsMessage {
    private const val OPT = 41

    fun buildQuery(name: String, type: Int, id: Int = 0x1234, recursion: Boolean = true): ByteArray {
        val out = ArrayList<Byte>(64)
        fun u16(v: Int) {
            out.add((v shr 8).toByte()); out.add(v.toByte())
        }
        u16(id)
        u16(if (recursion) 0x0120 else 0x0020)
        u16(1); u16(0); u16(0); u16(1)
        name.trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.encodeToByteArray()
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0)
        u16(type)
        u16(1)
        out.add(0)
        u16(OPT); u16(1232); u16(0); u16(0); u16(0)
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): DnsResponse {
        var pos = 0
        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

        fun readName(start: Int = -1): String {
            val labels = mutableListOf<String>()
            var p = if (start >= 0) start else pos
            var jumped = false
            var guard = 0
            while (guard++ < 128) {
                val len = bytes[p].toInt() and 0xFF
                if (len == 0) {
                    p++
                    break
                }
                if (len and 0xC0 == 0xC0) {
                    val pointer = ((len and 0x3F) shl 8) or (bytes[p + 1].toInt() and 0xFF)
                    if (!jumped && start < 0) pos = p + 2
                    jumped = true
                    p = pointer
                    continue
                }
                labels.add(bytes.decodeToString(p + 1, p + 1 + len))
                p += 1 + len
            }
            if (!jumped && start < 0) pos = p
            return if (labels.isEmpty()) "." else labels.joinToString(".")
        }

        fun readRecord(): DnsRecord {
            val name = readName()
            val type = u16()
            u16()
            val ttl = u32()
            val length = u16()
            val dataStart = pos
            val data = when (type) {
                1 -> if (length == 4) (0 until 4).joinToString(".") { (bytes[dataStart + it].toInt() and 0xFF).toString() } else hex(bytes, dataStart, length)
                28 -> if (length == 16) Ipv6Address(IntArray(8) { ((bytes[dataStart + it * 2].toInt() and 0xFF) shl 8) or (bytes[dataStart + it * 2 + 1].toInt() and 0xFF) }).compressed() else hex(bytes, dataStart, length)
                2, 5, 12 -> readName(dataStart)
                15 -> {
                    val pref = ((bytes[dataStart].toInt() and 0xFF) shl 8) or (bytes[dataStart + 1].toInt() and 0xFF)
                    "$pref ${readName(dataStart + 2)}"
                }
                16, 99 -> {
                    var p = dataStart
                    val parts = mutableListOf<String>()
                    while (p < dataStart + length) {
                        val l = bytes[p].toInt() and 0xFF
                        parts.add(bytes.decodeToString(p + 1, p + 1 + l))
                        p += 1 + l
                    }
                    parts.joinToString("")
                }
                6 -> {
                    val mname = readName(dataStart)
                    val mnameLen = nameLength(bytes, dataStart)
                    val rname = readName(dataStart + mnameLen)
                    val rnameLen = nameLength(bytes, dataStart + mnameLen)
                    var p = dataStart + mnameLen + rnameLen
                    fun n32(): Long {
                        val v = ((bytes[p].toLong() and 0xFF) shl 24) or ((bytes[p + 1].toLong() and 0xFF) shl 16) or ((bytes[p + 2].toLong() and 0xFF) shl 8) or (bytes[p + 3].toLong() and 0xFF)
                        p += 4
                        return v
                    }
                    "$mname $rname ${n32()} ${n32()} ${n32()} ${n32()} ${n32()}"
                }
                33 -> {
                    fun n16(o: Int) = ((bytes[dataStart + o].toInt() and 0xFF) shl 8) or (bytes[dataStart + o + 1].toInt() and 0xFF)
                    "${n16(0)} ${n16(2)} ${n16(4)} ${readName(dataStart + 6)}"
                }
                257 -> {
                    val flags = bytes[dataStart].toInt() and 0xFF
                    val tagLen = bytes[dataStart + 1].toInt() and 0xFF
                    val tag = bytes.decodeToString(dataStart + 2, dataStart + 2 + tagLen)
                    val value = bytes.decodeToString(dataStart + 2 + tagLen, dataStart + length)
                    "$flags $tag $value"
                }
                else -> hex(bytes, dataStart, length)
            }
            pos = dataStart + length
            return DnsRecord(name, type, ttl, data)
        }

        val id = u16()
        val flags = u16()
        val qd = u16(); val an = u16(); val ns = u16(); val ar = u16()
        repeat(qd) {
            readName(); u16(); u16()
        }
        val answers = List(an) { readRecord() }
        val authority = List(ns) { readRecord() }
        val additional = runCatching { List(ar) { readRecord() } }.getOrDefault(emptyList()).filter { it.type != OPT }
        return DnsResponse(
            id = id,
            rcode = flags and 0xF,
            authoritative = flags and 0x0400 != 0,
            authenticData = flags and 0x0020 != 0,
            truncated = flags and 0x0200 != 0,
            answers = answers,
            authority = authority,
            additional = additional,
        )
    }

    private fun nameLength(bytes: ByteArray, start: Int): Int {
        var p = start
        while (true) {
            val len = bytes[p].toInt() and 0xFF
            if (len == 0) return p - start + 1
            if (len and 0xC0 == 0xC0) return p - start + 2
            p += 1 + len
        }
    }

    private fun hex(bytes: ByteArray, start: Int, length: Int): String =
        (start until start + length).joinToString("") { (bytes[it].toInt() and 0xFF).toString(16).padStart(2, '0') }
}
