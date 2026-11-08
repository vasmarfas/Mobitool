package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.tools.developer.Sha512

// X25519 (RFC 7748) and Ed25519 (RFC 8032) after TweetNaCl: field elements are 16 limbs of 16 bits mod 2^255 - 19,
// the ladder and the selects run in constant time
private typealias Field = LongArray

private fun field(vararg limbs: Int): Field = LongArray(16) { if (it < limbs.size) limbs[it].toLong() else 0L }

private val ZERO = field()
private val ONE = field(1)
private val A24 = field(0xDB41, 1)
private val D = field(
    0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070, 0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203,
)
private val D2 = field(
    0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0, 0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406,
)
private val BASE_X = field(
    0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c, 0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169,
)
private val BASE_Y = field(
    0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
)
private val SQRT_MINUS_ONE = field(
    0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43, 0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83,
)
private val ORDER = longArrayOf(
    0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
)

private fun carry(o: Field) {
    for (i in 0 until 16) {
        o[i] += 1L shl 16
        val c = o[i] shr 16
        if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
        o[i] -= c shl 16
    }
}

private fun select(p: Field, q: Field, bit: Int) {
    val mask = (bit - 1L).inv()
    for (i in 0 until 16) {
        val t = mask and (p[i] xor q[i])
        p[i] = p[i] xor t
        q[i] = q[i] xor t
    }
}

private fun pack(n: Field): ByteArray {
    val t = n.copyOf()
    val m = LongArray(16)
    carry(t)
    carry(t)
    carry(t)
    repeat(2) {
        m[0] = t[0] - 0xffed
        for (i in 1 until 15) {
            m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
            m[i - 1] = m[i - 1] and 0xffff
        }
        m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
        val b = ((m[15] shr 16) and 1).toInt()
        m[14] = m[14] and 0xffff
        select(t, m, 1 - b)
    }
    return ByteArray(32) { i -> (if (i % 2 == 0) t[i / 2] else t[i / 2] shr 8).toByte() }
}

private fun unpack(bytes: ByteArray): Field =
    LongArray(16) { i -> (bytes[2 * i].toLong() and 0xff) + ((bytes[2 * i + 1].toLong() and 0xff) shl 8) }.also { it[15] = it[15] and 0x7fff }

private fun parity(a: Field): Int = pack(a)[0].toInt() and 1

private fun differ(a: Field, b: Field): Boolean = !pack(a).contentEquals(pack(b))

private fun add(a: Field, b: Field): Field = LongArray(16) { a[it] + b[it] }

private fun sub(a: Field, b: Field): Field = LongArray(16) { a[it] - b[it] }

private fun mul(a: Field, b: Field): Field {
    val t = LongArray(31)
    for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
    for (i in 0 until 15) t[i] += 38 * t[i + 16]
    val o = t.copyOf(16)
    carry(o)
    carry(o)
    return o
}

private fun square(a: Field): Field = mul(a, a)

private fun invert(i: Field): Field {
    var c = i.copyOf()
    for (a in 253 downTo 0) {
        c = square(c)
        if (a != 2 && a != 4) c = mul(c, i)
    }
    return c
}

private fun pow2523(i: Field): Field {
    var c = i.copyOf()
    for (a in 250 downTo 0) {
        c = square(c)
        if (a != 1) c = mul(c, i)
    }
    return c
}

object X25519 {
    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun publicKey(secret: ByteArray): ByteArray = shared(secret, BASE)

    fun shared(secret: ByteArray, peer: ByteArray): ByteArray {
        val z = secret.copyOf(32)
        z[31] = (z[31].toInt() and 127 or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = unpack(peer)
        var a = ONE.copyOf()
        var b = x.copyOf()
        var c = ZERO.copyOf()
        var d = ONE.copyOf()
        for (i in 254 downTo 0) {
            val r = (z[i shr 3].toInt() shr (i and 7)) and 1
            select(a, b, r)
            select(c, d, r)
            var e = add(a, c)
            a = sub(a, c)
            c = add(b, d)
            b = sub(b, d)
            d = square(e)
            val f = square(a)
            a = mul(c, a)
            c = mul(b, e)
            e = add(a, c)
            a = sub(a, c)
            b = square(a)
            c = sub(d, f)
            a = mul(c, A24)
            a = add(a, d)
            c = mul(c, a)
            a = mul(d, f)
            d = mul(b, x)
            b = square(e)
            select(a, b, r)
            select(c, d, r)
        }
        return pack(mul(a, invert(c)))
    }
}

object Ed25519 {
    private class Point(val x: Field, val y: Field, val z: Field, val t: Field) {
        fun copy() = Point(x.copyOf(), y.copyOf(), z.copyOf(), t.copyOf())
    }

    private fun plus(p: Point, q: Point): Point {
        val a = mul(sub(p.y, p.x), sub(q.y, q.x))
        val b = mul(add(p.x, p.y), add(q.x, q.y))
        val c = mul(mul(p.t, q.t), D2)
        val d = mul(p.z, q.z).let { add(it, it) }
        val e = sub(b, a)
        val f = sub(d, c)
        val g = add(d, c)
        val h = add(b, a)
        return Point(mul(e, f), mul(h, g), mul(g, f), mul(e, h))
    }

    private fun swap(p: Point, q: Point, bit: Int) {
        select(p.x, q.x, bit)
        select(p.y, q.y, bit)
        select(p.z, q.z, bit)
        select(p.t, q.t, bit)
    }

    private fun encode(p: Point): ByteArray {
        val zi = invert(p.z)
        val out = pack(mul(p.y, zi))
        out[31] = (out[31].toInt() xor (parity(mul(p.x, zi)) shl 7)).toByte()
        return out
    }

    private fun multiply(base: Point, scalar: ByteArray): Point {
        var p = Point(ZERO.copyOf(), ONE.copyOf(), ONE.copyOf(), ZERO.copyOf())
        var q = base.copy()
        for (i in 255 downTo 0) {
            val bit = (scalar[i / 8].toInt() shr (i and 7)) and 1
            swap(p, q, bit)
            q = plus(q, p)
            p = plus(p, p)
            swap(p, q, bit)
        }
        return p
    }

    private fun multiplyBase(scalar: ByteArray): Point = multiply(Point(BASE_X, BASE_Y, ONE, mul(BASE_X, BASE_Y)), scalar)

    // decodes the negated point, which is what verification needs
    private fun decodeNegated(bytes: ByteArray): Point? {
        val y = unpack(bytes)
        var num = square(y)
        var den = mul(num, D)
        num = sub(num, ONE)
        den = add(ONE, den)
        val den2 = square(den)
        val den4 = square(den2)
        val den6 = mul(den4, den2)
        var t = mul(mul(den6, num), den)
        t = pow2523(t)
        t = mul(mul(mul(t, num), den), den)
        var x = mul(t, den)
        if (differ(mul(square(x), den), num)) x = mul(x, SQRT_MINUS_ONE)
        if (differ(mul(square(x), den), num)) return null
        if (parity(x) == (bytes[31].toInt() shr 7 and 1)) x = sub(ZERO, x)
        return Point(x, y, ONE.copyOf(), mul(x, y))
    }

    private fun modOrder(x: LongArray): ByteArray {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * ORDER[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * ORDER[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * ORDER[j]
        val r = ByteArray(32)
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[i] = (x[i] and 255).toByte()
        }
        return r
    }

    private fun reduce(bytes: ByteArray): ByteArray = modOrder(LongArray(64) { bytes[it].toLong() and 0xff })

    private fun expand(seed: ByteArray): ByteArray = Sha512.digest(seed).also {
        it[0] = (it[0].toInt() and 248).toByte()
        it[31] = (it[31].toInt() and 127 or 64).toByte()
    }

    fun publicKey(seed: ByteArray): ByteArray = encode(multiplyBase(expand(seed)))

    fun sign(seed: ByteArray, publicKey: ByteArray, message: ByteArray): ByteArray {
        val d = expand(seed)
        val r = reduce(Sha512.digest(d.copyOfRange(32, 64) + message))
        val big = encode(multiplyBase(r))
        val h = reduce(Sha512.digest(big + publicKey + message))
        val x = LongArray(64)
        for (i in 0 until 32) x[i] = r[i].toLong() and 0xff
        for (i in 0 until 32) for (j in 0 until 32) x[i + j] += (h[i].toLong() and 0xff) * (d[j].toLong() and 0xff)
        return big + modOrder(x)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val negated = decodeNegated(publicKey) ?: return false
        val h = reduce(Sha512.digest(signature.copyOf(32) + publicKey + message))
        val sum = plus(multiply(negated, h), multiplyBase(signature.copyOfRange(32, 64)))
        return encode(sum).contentEquals(signature.copyOf(32))
    }
}
