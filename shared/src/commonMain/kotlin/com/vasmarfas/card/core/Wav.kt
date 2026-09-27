package com.vasmarfas.card.core

object Wav {
    fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val out = ByteArray(44)
        fun text(at: Int, value: String) = value.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun int(at: Int, value: Long) {
            for (i in 0 until 4) out[at + i] = (value ushr (8 * i)).toByte()
        }
        fun short(at: Int, value: Int) {
            out[at] = value.toByte()
            out[at + 1] = (value ushr 8).toByte()
        }
        text(0, "RIFF")
        int(4, (36 + dataBytes).coerceAtMost(0xFFFFFFFFL))
        text(8, "WAVE")
        text(12, "fmt ")
        int(16, 16)
        short(20, 1)
        short(22, channels)
        int(24, sampleRate.toLong())
        int(28, sampleRate.toLong() * channels * 2)
        short(32, channels * 2)
        short(34, 16)
        text(36, "data")
        int(40, dataBytes.coerceAtMost(0xFFFFFFFFL))
        return out
    }
}
