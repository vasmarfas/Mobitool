package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.media.ByteSink

internal fun sshFailure() = NetException(Res.string.ssh_error_failed)

// RFC 4251 section 5 data types
internal class SshWriter {
    private val sink = ByteSink()

    fun byte(value: Int) = apply { sink.byte(value) }

    fun boolean(value: Boolean) = byte(if (value) 1 else 0)

    fun uint32(value: Int) = apply { sink.intBE(value) }

    fun raw(bytes: ByteArray) = apply { sink.bytes(bytes) }

    fun string(bytes: ByteArray) = apply {
        sink.intBE(bytes.size)
        sink.bytes(bytes)
    }

    fun string(text: String) = string(text.encodeToByteArray())

    fun names(list: List<String>) = string(list.joinToString(","))

    // an unsigned magnitude, with a zero byte in front when the top bit is set
    fun mpint(magnitude: ByteArray) = string(stripZeros(magnitude).let { if (it.isNotEmpty() && it[0] < 0) byteArrayOf(0) + it else it })

    fun bytes(): ByteArray = sink.toByteArray()
}

internal class SshReader(private val data: ByteArray) {
    private var position = 0

    val remaining get() = data.size - position

    private fun take(count: Int): ByteArray {
        if (count < 0 || count > remaining) throw sshFailure()
        return data.copyOfRange(position, position + count).also { position += count }
    }

    fun raw(count: Int): ByteArray = take(count)

    fun byte(): Int = take(1)[0].toInt() and 0xFF

    fun boolean(): Boolean = byte() != 0

    fun uint32(): Int = take(4).let { (it[0].toInt() and 0xFF shl 24) or (it[1].toInt() and 0xFF shl 16) or (it[2].toInt() and 0xFF shl 8) or (it[3].toInt() and 0xFF) }

    fun string(): ByteArray = take(uint32())

    fun text(): String = string().decodeToString()

    fun names(): List<String> = text().split(',').filter { it.isNotEmpty() }

    // the magnitude of a non-negative mpint
    fun mpint(): ByteArray = stripZeros(string())
}

internal fun stripZeros(bytes: ByteArray): ByteArray {
    val first = bytes.indexOfFirst { it.toInt() != 0 }
    return if (first < 0) ByteArray(0) else if (first == 0) bytes else bytes.copyOfRange(first, bytes.size)
}

internal fun compareMagnitude(a: ByteArray, b: ByteArray): Int {
    val x = stripZeros(a)
    val y = stripZeros(b)
    if (x.size != y.size) return x.size.compareTo(y.size)
    for (i in x.indices) {
        val difference = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
        if (difference != 0) return difference
    }
    return 0
}

internal fun uint32Bytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

// just enough DER for PKCS#1, SEC1 and PKCS#8 keys
internal class DerReader(private val data: ByteArray, private var position: Int = 0, private val end: Int = data.size) {
    val hasMore get() = position < end

    val nextTag get() = if (position < end) data[position].toInt() and 0xFF else -1

    private fun element(tag: Int): Pair<Int, Int> {
        if (nextTag != tag) throw IllegalArgumentException("tag")
        position++
        var length = data[position++].toInt() and 0xFF
        if (length >= 0x80) {
            val count = length and 0x7F
            require(count in 1..3)
            length = 0
            repeat(count) { length = length shl 8 or (data[position++].toInt() and 0xFF) }
        }
        require(position + length <= end)
        return (position to position + length).also { position += length }
    }

    private fun value(tag: Int): ByteArray = element(tag).let { (from, to) -> data.copyOfRange(from, to) }

    fun sequence(): DerReader = element(0x30).let { (from, to) -> DerReader(data, from, to) }

    fun context(number: Int): DerReader? =
        if (nextTag == 0xA0 + number) element(0xA0 + number).let { (from, to) -> DerReader(data, from, to) } else null

    fun integer(): ByteArray = stripZeros(value(0x02))

    fun octets(): ByteArray = value(0x04)

    fun bits(): ByteArray = value(0x03).let { it.copyOfRange(1, it.size) }

    fun oid(): String {
        val bytes = value(0x06)
        val parts = mutableListOf((bytes[0].toInt() and 0xFF) / 40, (bytes[0].toInt() and 0xFF) % 40)
        var current = 0
        for (i in 1 until bytes.size) {
            current = current shl 7 or (bytes[i].toInt() and 0x7F)
            if (bytes[i].toInt() and 0x80 == 0) {
                parts += current
                current = 0
            }
        }
        return parts.joinToString(".")
    }

    fun skip() {
        position++
        var length = data[position++].toInt() and 0xFF
        if (length >= 0x80) {
            val count = length and 0x7F
            length = 0
            repeat(count) { length = length shl 8 or (data[position++].toInt() and 0xFF) }
        }
        position += length
    }
}
