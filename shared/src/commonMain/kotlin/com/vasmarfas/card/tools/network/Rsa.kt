package com.vasmarfas.card.tools.network

private const val LIMB = 0xFFFFFFFFL

object Rsa {
    // big-endian numbers, odd modulus. Montgomery form with 32-bit limbs needs no long division
    fun modPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray {
        val size = (modulus.size + 3) / 4
        val n = limbs(modulus, size)
        require(n[0] and 1L == 1L && base.size <= modulus.size)
        val x = limbs(base, size)
        require(less(x, n))
        var inverse = 1L
        repeat(5) { inverse = inverse * (2 - n[0] * inverse) and LIMB }
        val factor = -inverse and LIMB
        val one = LongArray(size).also { it[0] = 1 }
        val square = montgomerySquare(n)
        val montgomeryBase = multiply(x, square, n, factor)
        var result = multiply(one, square, n, factor)
        for (byte in exponent) {
            for (bit in 7 downTo 0) {
                result = multiply(result, result, n, factor)
                if (byte.toInt() shr bit and 1 == 1) result = multiply(result, montgomeryBase, n, factor)
            }
        }
        result = multiply(result, one, n, factor)
        return ByteArray(modulus.size) { i ->
            val position = modulus.size - 1 - i
            (result[position / 4] ushr (position % 4 * 8)).toByte()
        }
    }

    private fun limbs(bytes: ByteArray, size: Int): LongArray {
        val out = LongArray(size)
        bytes.forEachIndexed { i, byte ->
            val position = bytes.size - 1 - i
            out[position / 4] = out[position / 4] or ((byte.toLong() and 0xFF) shl (position % 4 * 8))
        }
        return out
    }

    // 2^(64 * size) mod n by doubling, the one value that converts into Montgomery form
    private fun montgomerySquare(n: LongArray): LongArray {
        val out = LongArray(n.size).also { it[0] = 1 }
        repeat(64 * n.size) {
            val overflow = out.last() ushr 31 != 0L
            for (i in n.indices.reversed()) {
                out[i] = out[i] shl 1 and LIMB or (if (i > 0) out[i - 1] ushr 31 else 0)
            }
            if (overflow || !less(out, n)) subtract(out, n)
        }
        return out
    }

    private fun multiply(a: LongArray, b: LongArray, n: LongArray, factor: Long): LongArray {
        val size = n.size
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

    private fun less(a: LongArray, b: LongArray): Boolean {
        for (i in a.indices.reversed()) if (a[i] != b[i]) return a[i] < b[i]
        return false
    }

    private fun subtract(a: LongArray, b: LongArray) {
        var borrow = 0L
        for (i in a.indices) {
            val difference = a[i] - b[i] - borrow
            a[i] = difference and LIMB
            borrow = difference ushr 63
        }
    }
}
