package com.vasmarfas.card.tools.time

class NtpReply(val server: String, val offsetMs: Double, val delayMs: Double, val stratum: Int, val reference: String)

// RFC 5905
object Ntp {
    private const val UNIX_TO_NTP_SECONDS = 2_208_988_800L
    private const val FRACTION = 4_294_967_296.0

    // version 4, client mode, our clock in the transmit field comes back as the origin
    fun request(nowMs: Long): ByteArray = ByteArray(48).also {
        it[0] = 0x23
        write(it, 40, nowMs)
    }

    private fun write(bytes: ByteArray, at: Int, ms: Long) {
        val seconds = ms / 1000 + UNIX_TO_NTP_SECONDS
        val fraction = ((ms % 1000) * FRACTION / 1000).toLong()
        for (i in 0 until 4) {
            bytes[at + i] = (seconds shr (24 - 8 * i)).toByte()
            bytes[at + 4 + i] = (fraction shr (24 - 8 * i)).toByte()
        }
    }

    private fun read(bytes: ByteArray, at: Int): Double {
        fun word(offset: Int) = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (bytes[offset + i].toLong() and 0xFF) }
        return (word(at) - UNIX_TO_NTP_SECONDS) * 1000.0 + word(at + 4) / FRACTION * 1000
    }

    // a stratum 0 reply is a kiss-of-death code, and a reply in another mode is not an answer to us
    fun parse(server: String, response: ByteArray?, sentMs: Long, receivedMs: Long): NtpReply? {
        if (response == null || response.size < 48 || response[0].toInt() and 0x07 != 4) return null
        val stratum = response[1].toInt() and 0xFF
        if (stratum == 0) return null
        val t1 = read(response, 32)
        val t2 = read(response, 40)
        val offset = ((t1 - sentMs) + (t2 - receivedMs)) / 2
        val delay = (receivedMs - sentMs) - (t2 - t1)
        val id = response.copyOfRange(12, 16)
        val reference = if (stratum == 1) id.decodeToString().trimEnd('\u0000') else id.joinToString(".") { (it.toInt() and 0xFF).toString() }
        return NtpReply(server, offset, delay, stratum, reference)
    }
}
