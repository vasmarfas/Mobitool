package com.vasmarfas.card.tools.network

private const val LIMB = 0xFFFFFFFFL

// arithmetic modulo an odd big-endian number, values in Montgomery form with 32-bit limbs, the lowest first.
// Montgomery multiplication needs no long division, so RSA, Diffie-Hellman and the NIST curves share it
class Montgomery(private val modulus: ByteArray) {
    private val size = (modulus.size + 3) / 4
    private val n = limbs(modulus, size)
    private val factor: Long
    private val square: LongArray
    val one: LongArray
    private val primeInverseExponent by lazy { minus(modulus, 2) }

    init {
        require(n[0] and 1L == 1L)
        var inverse = 1L
        repeat(5) { inverse = inverse * (2 - n[0] * inverse) and LIMB }
        factor = -inverse and LIMB
        square = montgomerySquare()
        one = mul(LongArray(size).also { it[0] = 1 }, square)
    }

    // the value has to be below the modulus
    fun enter(value: ByteArray): LongArray {
        val x = limbs(value, size)
        require(less(x, n))
        return mul(x, square)
    }

    // for a value below twice the modulus, such as a hash or an x coordinate of the same length
    fun reduce(value: ByteArray): LongArray {
        val x = limbs(value, size)
        if (!less(x, n)) subtract(x, n)
        return mul(x, square)
    }

    fun leave(x: LongArray): ByteArray {
        val plain = mul(x, LongArray(size).also { it[0] = 1 })
        return ByteArray(modulus.size) { i ->
            val position = modulus.size - 1 - i
            (plain[position / 4] ushr (position % 4 * 8)).toByte()
        }
    }

    fun mul(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(size + 2)
        for (i in 0 until size) {
            var carry = 0L
            for (j in 0 until size) {
                val sum = t[j] + a[j] * b[i] + carry
                t[j] = sum and LIMB
                carry = sum ushr 32
            }
            var sum = t[size] + carry
            t[size] = sum and LIMB
            t[size + 1] = sum ushr 32
            val m = t[0] * factor and LIMB
            carry = (t[0] + m * n[0]) ushr 32
            for (j in 1 until size) {
                sum = t[j] + m * n[j] + carry
                t[j - 1] = sum and LIMB
                carry = sum ushr 32
            }
            sum = t[size] + carry
            t[size - 1] = sum and LIMB
            t[size] = t[size + 1] + (sum ushr 32)
        }
        val out = t.copyOf(size)
        if (t[size] != 0L || !less(out, n)) subtract(out, n)
        return out
    }

    fun add(a: LongArray, b: LongArray): LongArray {
        val out = LongArray(size)
        var carry = 0L
        for (i in 0 until size) {
            val sum = a[i] + b[i] + carry
            out[i] = sum and LIMB
            carry = sum ushr 32
        }
        if (carry != 0L || !less(out, n)) subtract(out, n)
        return out
    }

    fun sub(a: LongArray, b: LongArray): LongArray {
        val out = a.copyOf()
        if (subtract(out, b)) {
            var carry = 0L
            for (i in 0 until size) {
                val sum = out[i] + n[i] + carry
                out[i] = sum and LIMB
                carry = sum ushr 32
            }
        }
        return out
    }

    fun pow(base: LongArray, exponent: ByteArray): LongArray {
        var result = one
        for (byte in exponent) {
            for (bit in 7 downTo 0) {
                result = mul(result, result)
                if (byte.toInt() shr bit and 1 == 1) result = mul(result, base)
            }
        }
        return result
    }

    // Fermat, so only for a prime modulus
    fun inverse(a: LongArray): LongArray = pow(a, primeInverseExponent)

    fun isZero(a: LongArray): Boolean = a.all { it == 0L }

    // 2^(64 * size) mod n by doubling, the one value that converts into Montgomery form
    private fun montgomerySquare(): LongArray {
        val out = LongArray(size).also { it[0] = 1 }
        repeat(64 * size) {
            val overflow = out.last() ushr 31 != 0L
            for (i in out.indices.reversed()) {
                out[i] = out[i] shl 1 and LIMB or (if (i > 0) out[i - 1] ushr 31 else 0)
            }
            if (overflow || !less(out, n)) subtract(out, n)
        }
        return out
    }

    private fun less(a: LongArray, b: LongArray): Boolean {
        for (i in a.indices.reversed()) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }

    // returns the borrow out of the top limb
    private fun subtract(a: LongArray, b: LongArray): Boolean {
        var borrow = 0L
        for (i in a.indices) {
            val difference = a[i] - b[i] - borrow
            a[i] = difference and LIMB
            borrow = difference ushr 63
        }
        return borrow != 0L
    }

    private companion object {
        fun limbs(bytes: ByteArray, size: Int): LongArray {
            val out = LongArray(size)
            bytes.forEachIndexed { i, byte ->
                val position = bytes.size - 1 - i
                if (position < size * 4) {
                    out[position / 4] = out[position / 4] or ((byte.toLong() and 0xFF) shl (position % 4 * 8))
                } else {
                    require(byte.toInt() == 0)
                }
            }
            return out
        }

        fun minus(value: ByteArray, amount: Int): ByteArray {
            val out = value.copyOf()
            var borrow = amount
            for (i in out.indices.reversed()) {
                if (borrow == 0) break
                val difference = (out[i].toInt() and 0xFF) - borrow
                out[i] = difference.toByte()
                borrow = if (difference < 0) 1 else 0
            }
            return out
        }
    }
}
