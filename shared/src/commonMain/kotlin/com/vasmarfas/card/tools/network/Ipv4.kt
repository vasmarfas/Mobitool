package com.vasmarfas.card.tools.network

data class Ipv4Subnet(
    val address: Long,
    val prefix: Int,
) {
    val mask: Long get() = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val wildcard: Long get() = mask.inv() and 0xFFFFFFFFL
    val network: Long get() = address and mask
    val broadcast: Long get() = network or wildcard
    val totalAddresses: Long get() = 1L shl (32 - prefix)
    val usableHosts: Long get() = when (prefix) {
        32 -> 1
        31 -> 2
        else -> totalAddresses - 2
    }
    val firstHost: Long get() = if (prefix >= 31) network else network + 1
    val lastHost: Long get() = if (prefix >= 31) broadcast else broadcast - 1
    val ipClass: Char get() {
        val first = (address shr 24).toInt()
        return when {
            first < 128 -> 'A'
            first < 192 -> 'B'
            first < 224 -> 'C'
            first < 240 -> 'D'
            else -> 'E'
        }
    }
    val isPrivate: Boolean get() {
        val a = (address shr 24).toInt()
        val b = ((address shr 16) and 0xFF).toInt()
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
    val isLoopback: Boolean get() = (address shr 24).toInt() == 127
    val isLinkLocal: Boolean get() = (address shr 24).toInt() == 169 && ((address shr 16) and 0xFF).toInt() == 254
    val isCgnat: Boolean get() = (address shr 24).toInt() == 100 && ((address shr 16) and 0xFF).toInt() in 64..127
    val isMulticast: Boolean get() = (address shr 24).toInt() in 224..239
    val isThisNetwork: Boolean get() = (address shr 24).toInt() == 0
    val isBroadcast: Boolean get() = address == 0xFFFFFFFFL
    val isReserved: Boolean get() = (address shr 24).toInt() >= 240 && !isBroadcast
    val isPublic: Boolean get() = !isPrivate && !isLoopback && !isLinkLocal && !isCgnat && !isMulticast && !isThisNetwork && !isBroadcast && !isReserved
}

object Ipv4 {
    private val dotted = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun parse(text: String): Long? {
        val m = dotted.find(text.trim()) ?: return null
        var result = 0L
        for (i in 1..4) {
            val octet = m.groupValues[i].toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            result = (result shl 8) or octet.toLong()
        }
        return result
    }

    fun format(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

    fun binary(value: Long): String =
        (0..3).joinToString(".") { i -> ((value shr (24 - i * 8)) and 0xFF).toString(2).padStart(8, '0') }

    fun hex(value: Long): String =
        "0x" + (0..3).joinToString("") { i -> ((value shr (24 - i * 8)) and 0xFF).toString(16).padStart(2, '0').uppercase() }

    fun maskToPrefix(mask: Long): Int? {
        if (mask == 0L) return 0
        var prefix = 0
        var seenZero = false
        for (i in 31 downTo 0) {
            val bit = (mask shr i) and 1L
            if (bit == 1L) {
                if (seenZero) return null
                prefix++
            } else {
                seenZero = true
            }
        }
        return prefix
    }

    fun parseSubnet(text: String): Ipv4Subnet? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val slash = t.indexOf('/')
        if (slash >= 0) {
            val address = parse(t.substring(0, slash)) ?: return null
            val suffix = t.substring(slash + 1).trim()
            val prefix = suffix.toIntOrNull() ?: parse(suffix)?.let(::maskToPrefix) ?: return null
            if (prefix !in 0..32) return null
            return Ipv4Subnet(address, prefix)
        }
        val parts = t.split(Regex("\\s+"))
        if (parts.size == 2) {
            val address = parse(parts[0]) ?: return null
            val prefix = parse(parts[1])?.let(::maskToPrefix) ?: parts[1].toIntOrNull() ?: return null
            if (prefix !in 0..32) return null
            return Ipv4Subnet(address, prefix)
        }
        val address = parse(t) ?: return null
        val prefix = when ((address shr 24).toInt()) {
            in 0..127 -> 8
            in 128..191 -> 16
            in 192..223 -> 24
            else -> 32
        }
        return Ipv4Subnet(address, prefix)
    }

    // in a list of blocks a bare address is that one host, not its classful network
    fun parseBlock(text: String): Ipv4Subnet? = parse(text)?.let { Ipv4Subnet(it, 32) } ?: parseSubnet(text)

    fun ptrName(value: Long): String =
        (0..3).joinToString(".") { i -> ((value shr (i * 8)) and 0xFF).toString() } + ".in-addr.arpa"
}
