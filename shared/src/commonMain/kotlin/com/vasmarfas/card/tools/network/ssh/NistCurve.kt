package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.tools.developer.Sha256
import com.vasmarfas.card.tools.developer.Sha384
import com.vasmarfas.card.tools.developer.Sha512
import com.vasmarfas.card.tools.developer.hexToBytes
import com.vasmarfas.card.tools.network.Montgomery

// short Weierstrass curves with a = -3 in Jacobian coordinates, for ECDH and ECDSA in SSH. The scalar
// multiplication is plain double-and-add: the keys are ephemeral or sign once per login
class NistCurve private constructor(
    val name: String,
    val hash: (ByteArray) -> ByteArray,
    prime: String,
    b: String,
    order: String,
    gx: String,
    gy: String,
) {
    private val p = hexToBytes(prime)!!
    val size = p.size
    private val field = Montgomery(p)
    private val orderBytes = hexToBytes(order)!!
    private val n = Montgomery(orderBytes)
    private val b = field.enter(hexToBytes(b)!!)
    private val generator = Point(field.enter(hexToBytes(gx)!!), field.enter(hexToBytes(gy)!!), field.one)
    private val orderBits = orderBytes.size * 8 - orderBytes[0].countLeadingZeroBits()

    private class Point(val x: LongArray, val y: LongArray, val z: LongArray)

    private fun isInfinity(p: Point) = field.isZero(p.z)

    private fun double(p: Point): Point {
        if (isInfinity(p) || field.isZero(p.y)) return Point(field.one, field.one, LongArray(field.one.size))
        with(field) {
            val delta = mul(p.z, p.z)
            val gamma = mul(p.y, p.y)
            val beta = mul(p.x, gamma)
            val t = mul(sub(p.x, delta), add(p.x, delta))
            val alpha = add(add(t, t), t)
            val beta4 = add(add(beta, beta), add(beta, beta))
            val x3 = sub(mul(alpha, alpha), add(beta4, beta4))
            val yz = add(p.y, p.z)
            val z3 = sub(sub(mul(yz, yz), gamma), delta)
            val gamma2 = mul(gamma, gamma)
            val gamma8 = add(add(add(gamma2, gamma2), add(gamma2, gamma2)), add(add(gamma2, gamma2), add(gamma2, gamma2)))
            val y3 = sub(mul(alpha, sub(beta4, x3)), gamma8)
            return Point(x3, y3, z3)
        }
    }

    private fun add(p: Point, q: Point): Point {
        if (isInfinity(p)) return q
        if (isInfinity(q)) return p
        with(field) {
            val z1z1 = mul(p.z, p.z)
            val z2z2 = mul(q.z, q.z)
            val u1 = mul(p.x, z2z2)
            val u2 = mul(q.x, z1z1)
            val s1 = mul(mul(p.y, q.z), z2z2)
            val s2 = mul(mul(q.y, p.z), z1z1)
            val h = sub(u2, u1)
            val r = sub(s2, s1).let { add(it, it) }
            if (isZero(h)) return if (isZero(r)) double(p) else Point(one, one, LongArray(one.size))
            val h2 = add(h, h)
            val i = mul(h2, h2)
            val j = mul(h, i)
            val v = mul(u1, i)
            val x3 = sub(sub(mul(r, r), j), add(v, v))
            val s1j = mul(s1, j)
            val y3 = sub(mul(r, sub(v, x3)), add(s1j, s1j))
            val zz = add(p.z, q.z)
            val z3 = mul(sub(sub(mul(zz, zz), z1z1), z2z2), h)
            return Point(x3, y3, z3)
        }
    }

    private fun multiply(point: Point, scalar: ByteArray): Point {
        var result = Point(field.one, field.one, LongArray(field.one.size))
        for (byte in scalar) {
            for (bit in 7 downTo 0) {
                result = double(result)
                if (byte.toInt() shr bit and 1 == 1) result = add(result, point)
            }
        }
        return result
    }

    private fun affineX(point: Point): ByteArray {
        val zi = field.inverse(point.z)
        return field.leave(field.mul(point.x, field.mul(zi, zi)))
    }

    private fun encode(point: Point): ByteArray {
        val zi = field.inverse(point.z)
        val zi2 = field.mul(zi, zi)
        return byteArrayOf(4) + field.leave(field.mul(point.x, zi2)) + field.leave(field.mul(point.y, field.mul(zi2, zi)))
    }

    // uncompressed SEC1 point that lies on the curve
    private fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 1 + 2 * size || bytes[0].toInt() != 4) return null
        val x = runCatching { field.enter(bytes.copyOfRange(1, 1 + size)) }.getOrNull() ?: return null
        val y = runCatching { field.enter(bytes.copyOfRange(1 + size, bytes.size)) }.getOrNull() ?: return null
        with(field) {
            val x3 = mul(mul(x, x), x)
            val right = add(sub(x3, add(add(x, x), x)), b)
            if (!mul(y, y).contentEquals(right)) return null
        }
        return Point(x, y, field.one)
    }

    private fun randomScalar(): ByteArray {
        while (true) {
            val k = secureRandomBytes(orderBytes.size)
            val extra = orderBytes.size * 8 - orderBits
            k[0] = (k[0].toInt() and (0xFF ushr extra)).toByte()
            if (k.all { it.toInt() == 0 }) continue
            if (runCatching { n.enter(k) }.isSuccess) return k
        }
    }

    // SSH pairs every curve with a hash no longer than its order, so the whole digest is the scalar
    private fun hashScalar(digest: ByteArray): LongArray = n.reduce(digest)

    fun keyPair(): Pair<ByteArray, ByteArray> {
        val d = randomScalar()
        return d to encode(multiply(generator, d))
    }

    fun publicKey(secret: ByteArray): ByteArray = encode(multiply(generator, secret))

    // the x coordinate of d * peer, null for a point off the curve
    fun shared(secret: ByteArray, peer: ByteArray): ByteArray? {
        val point = decode(peer) ?: return null
        val product = multiply(point, secret)
        return if (isInfinity(product)) null else affineX(product)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, r: ByteArray, s: ByteArray): Boolean {
        val q = decode(publicKey) ?: return false
        val rm = runCatching { n.enter(r) }.getOrNull() ?: return false
        val sm = runCatching { n.enter(s) }.getOrNull() ?: return false
        if (n.isZero(rm) || n.isZero(sm)) return false
        val w = n.inverse(sm)
        val u1 = n.leave(n.mul(hashScalar(hash(message)), w))
        val u2 = n.leave(n.mul(rm, w))
        val sum = add(multiply(generator, u1), multiply(q, u2))
        if (isInfinity(sum)) return false
        return n.reduce(affineX(sum)).contentEquals(rm)
    }

    fun sign(secret: ByteArray, message: ByteArray): Pair<ByteArray, ByteArray> {
        val d = n.enter(secret)
        val e = hashScalar(hash(message))
        while (true) {
            val k = randomScalar()
            val r = n.reduce(affineX(multiply(generator, k)))
            if (n.isZero(r)) continue
            val s = n.mul(n.inverse(n.enter(k)), n.add(e, n.mul(r, d)))
            if (n.isZero(s)) continue
            return n.leave(r) to n.leave(s)
        }
    }

    companion object {
        val P256 = NistCurve(
            "nistp256", Sha256::digest,
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff",
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b",
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551",
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296",
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
        )
        val P384 = NistCurve(
            "nistp384", Sha384::digest,
            "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffeffffffff0000000000000000ffffffff",
            "b3312fa7e23ee7e4988e056be3f82d19181d9c6efe8141120314088f5013875ac656398d8a2ed19d2a85c8edd3ec2aef",
            "ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52973",
            "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7",
            "3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f",
        )
        val P521 = NistCurve(
            "nistp521", Sha512::digest,
            "01ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            "0051953eb9618e1c9a1f929a21a0b68540eea2da725b99b315f3b8b489918ef109e156193951ec7e937b1652c0bd3bb1bf073573df883d2c34f1ef451fd46b503f00",
            "01fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffa51868783bf2f966b7fcc0148f709a5d03bb5c9b8899c47aebb6fb71e91386409",
            "00c6858e06b70404e9cd9e3ecb662395b4429c648139053fb521f828af606b4d3dbaa14b5e77efe75928fe1dc127a2ffa8de3348b3c1856a429bf97e7e31c2e5bd66",
            "011839296a789a3bc0045c8a5fb42c7d1bd998f54449579b446817afbd17273e662c97ee72995ef42640c550b9013fad0761353c7086a272c24088be94769fd16650",
        )

        fun byName(name: String): NistCurve? = listOf(P256, P384, P521).firstOrNull { it.name == name }
    }
}
