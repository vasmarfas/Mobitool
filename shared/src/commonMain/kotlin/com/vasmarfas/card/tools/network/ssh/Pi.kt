package com.vasmarfas.card.tools.network.ssh

// Blowfish starts from the hex digits of pi and the MODP groups of RFC 3526 are built from its bits, so pi is
// computed once by Machin's formula, pi = 16 atan(1/5) - 4 atan(1/239), instead of carrying 4 KB of digits
internal object Pi {
    private const val LIMB = 0xFFFFFFFFL
    private const val WORDS = 18 + 4 * 256
    private const val GUARD = 2

    // the words of the fraction, 0x243F6A88 first
    val fraction: IntArray by lazy {
        val size = 1 + WORDS + GUARD
        val pi = arctanInverse(5, size).also { times(it, 16) }
        val tail = arctanInverse(239, size).also { times(it, 4) }
        minus(pi, tail)
        IntArray(WORDS) { pi[it + 1].toInt() }
    }

    // RFC 3526: p = 2^n - 2^(n - 64) - 1 + 2^64 * (floor(2^(n - 130) * pi) + c)
    fun modpPrime(bits: Int, c: Int): ByteArray {
        val length = bits / 8
        val f = ByteArray(length - 16)
        for (i in f.indices) {
            var byte = 0
            for (bit in 0 until 8) byte = byte shl 1 or piBit(i * 8 + bit)
            f[i] = byte.toByte()
        }
        var carry = c
        for (i in f.indices.reversed()) {
            if (carry == 0) break
            val sum = (f[i].toInt() and 0xFF) + (carry and 0xFF)
            f[i] = sum.toByte()
            carry = (carry ushr 8) + (sum ushr 8)
        }
        val top = ByteArray(length) { 0xFF.toByte() }.also { it[7] = 0xFE.toByte() }
        val out = ByteArray(length)
        var sum = 0
        for (i in length - 1 downTo 0) {
            val shifted = if (i in 8 until length - 8) f[i - 8].toInt() and 0xFF else 0
            sum += (top[i].toInt() and 0xFF) + shifted
            out[i] = sum.toByte()
            sum = sum ushr 8
        }
        return out
    }

    // bits of pi from the top, the two integer bits 11 first
    private fun piBit(index: Int): Int {
        if (index < 2) return 1
        val position = index - 2
        return fraction[position / 32] ushr (31 - position % 32) and 1
    }

    // fixed point in base 2^32, the integer part in the first limb
    private fun arctanInverse(x: Int, size: Int): LongArray {
        val power = LongArray(size).also { it[0] = 1 }
        divide(power, x.toLong())
        val sum = power.copyOf()
        val square = x.toLong() * x
        var k = 1
        while (power.any { it != 0L }) {
            divide(power, square)
            val term = power.copyOf()
            divide(term, 2L * k + 1)
            if (k % 2 == 1) minus(sum, term) else plus(sum, term)
            k++
        }
        return sum
    }

    private fun divide(a: LongArray, d: Long) {
        var remainder = 0L
        for (i in a.indices) {
            val current = remainder shl 32 or a[i]
            a[i] = current / d
            remainder = current % d
        }
    }

    private fun times(a: LongArray, m: Long) {
        var carry = 0L
        for (i in a.indices.reversed()) {
            val product = a[i] * m + carry
            a[i] = product and LIMB
            carry = product ushr 32
        }
    }

    private fun plus(a: LongArray, b: LongArray) {
        var carry = 0L
        for (i in a.indices.reversed()) {
            val sum = a[i] + b[i] + carry
            a[i] = sum and LIMB
            carry = sum ushr 32
        }
    }

    private fun minus(a: LongArray, b: LongArray) {
        var borrow = 0L
        for (i in a.indices.reversed()) {
            val difference = a[i] - b[i] - borrow
            a[i] = difference and LIMB
            borrow = difference ushr 63
        }
    }
}
