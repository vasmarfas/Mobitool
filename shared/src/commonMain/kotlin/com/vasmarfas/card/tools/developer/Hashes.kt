package com.vasmarfas.card.tools.developer

fun ByteArray.toHex(upper: Boolean = false): String {
    val digits = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v shr 4]).append(digits[v and 0xF])
    }
    return sb.toString()
}

fun hexToBytes(text: String): ByteArray? {
    val clean = text.filter { !it.isWhitespace() && it != ':' && it != '-' }.removePrefix("0x").removePrefix("0X")
    if (clean.length % 2 != 0) return null
    val out = ByteArray(clean.length / 2)
    for (i in out.indices) {
        val hi = clean[i * 2].digitToIntOrNull(16) ?: return null
        val lo = clean[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private val NO_PREFIX = ByteArray(0)

enum class HashAlgorithm(val title: String, val blockSize: Int, private val hash: (ByteArray, ByteArray) -> ByteArray) {
    MD5("MD5", 64, Md5::digest),
    SHA1("SHA-1", 64, Sha1::digest),
    SHA256("SHA-256", 64, Sha256::digest),
    SHA512("SHA-512", 128, Sha512::digest);

    fun digest(message: ByteArray): ByteArray = hash(NO_PREFIX, message)

    fun digest(prefix: ByteArray, message: ByteArray): ByteArray = hash(prefix, message)
}

fun hmac(algorithm: HashAlgorithm, key: ByteArray, message: ByteArray): ByteArray {
    val blockSize = algorithm.blockSize
    val shortKey = if (key.size > blockSize) algorithm.digest(key) else key
    val padded = ByteArray(blockSize)
    shortKey.copyInto(padded)
    val ipad = ByteArray(blockSize) { (padded[it].toInt() xor 0x36).toByte() }
    val opad = ByteArray(blockSize) { (padded[it].toInt() xor 0x5c).toByte() }
    return algorithm.digest(opad, algorithm.digest(ipad, message))
}

// the message is hashed in place and only the padded tail is copied, the prefix is one whole block or empty
private inline fun forEachBlock(prefix: ByteArray, message: ByteArray, blockSize: Int, lengthBytes: Int, littleEndian: Boolean, block: (ByteArray, Int) -> Unit) {
    if (prefix.isNotEmpty()) block(prefix, 0)
    val whole = message.size / blockSize * blockSize
    for (offset in 0 until whole step blockSize) block(message, offset)
    val rest = message.size - whole
    val tail = ByteArray(if (rest + 1 + lengthBytes <= blockSize) blockSize else blockSize * 2)
    message.copyInto(tail, 0, whole, message.size)
    tail[rest] = 0x80.toByte()
    val bitLength = (prefix.size.toLong() + message.size) * 8
    for (i in 0 until 8) {
        val shift = if (littleEndian) i * 8 else (7 - i) * 8
        val index = tail.size - lengthBytes + (if (littleEndian) i else lengthBytes - 8 + i)
        tail[index] = (bitLength ushr shift).toByte()
    }
    for (offset in 0 until tail.size step blockSize) block(tail, offset)
}

private fun readIntBE(b: ByteArray, off: Int): Int =
    ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

private fun readIntLE(b: ByteArray, off: Int): Int =
    ((b[off + 3].toInt() and 0xFF) shl 24) or ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off].toInt() and 0xFF)

private fun readLongBE(b: ByteArray, off: Int): Long =
    (readIntBE(b, off).toLong() shl 32) or (readIntBE(b, off + 4).toLong() and 0xFFFFFFFFL)

private fun writeIntBE(out: ByteArray, off: Int, v: Int) {
    out[off] = (v ushr 24).toByte()
    out[off + 1] = (v ushr 16).toByte()
    out[off + 2] = (v ushr 8).toByte()
    out[off + 3] = v.toByte()
}

private fun writeIntLE(out: ByteArray, off: Int, v: Int) {
    out[off] = v.toByte()
    out[off + 1] = (v ushr 8).toByte()
    out[off + 2] = (v ushr 16).toByte()
    out[off + 3] = (v ushr 24).toByte()
}

private fun writeLongBE(out: ByteArray, off: Int, v: Long) {
    writeIntBE(out, off, (v ushr 32).toInt())
    writeIntBE(out, off + 4, v.toInt())
}

private fun hexWords(text: String): IntArray = text.split(' ').map { it.toLong(16).toInt() }.toIntArray()

object Md5 {
    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val K = hexWords(
        "d76aa478 e8c7b756 242070db c1bdceee f57c0faf 4787c62a a8304613 fd469501 " +
            "698098d8 8b44f7af ffff5bb1 895cd7be 6b901122 fd987193 a679438e 49b40821 " +
            "f61e2562 c040b340 265e5a51 e9b6c7aa d62f105d 02441453 d8a1e681 e7d3fbc8 " +
            "21e1cde6 c33707d6 f4d50d87 455a14ed a9e3e905 fcefa3f8 676f02d9 8d2a4c8a " +
            "fffa3942 8771f681 6d9d6122 fde5380c a4beea44 4bdecfa9 f6bb4b60 bebfbc70 " +
            "289b7ec6 eaa127fa d4ef3085 04881d05 d9d4d039 e6db99e5 1fa27cf8 c4ac5665 " +
            "f4292244 432aff97 ab9423a7 fc93a039 655b59c3 8f0ccc92 ffeff47d 85845dd1 " +
            "6fa87e4f fe2ce6e0 a3014314 4e0811a1 f7537e82 bd3af235 2ad7d2bb eb86d391",
    )

    fun digest(message: ByteArray): ByteArray = digest(NO_PREFIX, message)

    fun digest(prefix: ByteArray, message: ByteArray): ByteArray {
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476
        val m = IntArray(16)
        forEachBlock(prefix, message, 64, 8, littleEndian = true) { data, chunk ->
            for (i in 0 until 16) m[i] = readIntLE(data, chunk + i * 4)
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }
                    1 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) % 16
                    }
                    2 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }
                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                val rotated = (f + a + K[i] + m[g]).rotateLeft(S[i])
                a = d
                d = c
                c = b
                b += rotated
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }
        val out = ByteArray(16)
        writeIntLE(out, 0, a0)
        writeIntLE(out, 4, b0)
        writeIntLE(out, 8, c0)
        writeIntLE(out, 12, d0)
        return out
    }
}

object Sha1 {
    fun digest(message: ByteArray): ByteArray = digest(NO_PREFIX, message)

    fun digest(prefix: ByteArray, message: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val w = IntArray(80)
        forEachBlock(prefix, message, 64, 8, littleEndian = false) { data, chunk ->
            for (i in 0 until 16) w[i] = readIntBE(data, chunk + i * 4)
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when (i / 20) {
                    0 -> {
                        f = (b and c) or (b.inv() and d)
                        k = 0x5A827999
                    }
                    1 -> {
                        f = b xor c xor d
                        k = 0x6ED9EBA1
                    }
                    2 -> {
                        f = (b and c) or (b and d) or (c and d)
                        k = 0x8F1BBCDC.toInt()
                    }
                    else -> {
                        f = b xor c xor d
                        k = 0xCA62C1D6.toInt()
                    }
                }
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d
                d = c
                c = b.rotateLeft(30)
                b = a
                a = temp
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
        }
        val out = ByteArray(20)
        writeIntBE(out, 0, h0)
        writeIntBE(out, 4, h1)
        writeIntBE(out, 8, h2)
        writeIntBE(out, 12, h3)
        writeIntBE(out, 16, h4)
        return out
    }
}

object Sha256 {
    private val K = hexWords(
        "428a2f98 71374491 b5c0fbcf e9b5dba5 3956c25b 59f111f1 923f82a4 ab1c5ed5 " +
            "d807aa98 12835b01 243185be 550c7dc3 72be5d74 80deb1fe 9bdc06a7 c19bf174 " +
            "e49b69c1 efbe4786 0fc19dc6 240ca1cc 2de92c6f 4a7484aa 5cb0a9dc 76f988da " +
            "983e5152 a831c66d b00327c8 bf597fc7 c6e00bf3 d5a79147 06ca6351 14292967 " +
            "27b70a85 2e1b2138 4d2c6dfc 53380d13 650a7354 766a0abb 81c2c92e 92722c85 " +
            "a2bfe8a1 a81a664b c24b8b70 c76c51a3 d192e819 d6990624 f40e3585 106aa070 " +
            "19a4c116 1e376c08 2748774c 34b0bcb5 391c0cb3 4ed8aa4a 5b9cca4f 682e6ff3 " +
            "748f82ee 78a5636f 84c87814 8cc70208 90befffa a4506ceb bef9a3f7 c67178f2",
    )

    private val H0 = hexWords("6a09e667 bb67ae85 3c6ef372 a54ff53a 510e527f 9b05688c 1f83d9ab 5be0cd19")

    fun digest(message: ByteArray): ByteArray = digest(NO_PREFIX, message)

    fun digest(prefix: ByteArray, message: ByteArray): ByteArray {
        val h = H0.copyOf()
        val w = IntArray(64)
        forEachBlock(prefix, message, 64, 8, littleEndian = false) { data, chunk ->
            for (i in 0 until 16) w[i] = readIntBE(data, chunk + i * 4)
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) writeIntBE(out, i * 4, h[i])
        return out
    }
}

object Sha512 {
    private val K = (
        "428a2f98d728ae22 7137449123ef65cd b5c0fbcfec4d3b2f e9b5dba58189dbbc 3956c25bf348b538 59f111f1b605d019 923f82a4af194f9b ab1c5ed5da6d8118 " +
            "d807aa98a3030242 12835b0145706fbe 243185be4ee4b28c 550c7dc3d5ffb4e2 72be5d74f27b896f 80deb1fe3b1696b1 9bdc06a725c71235 c19bf174cf692694 " +
            "e49b69c19ef14ad2 efbe4786384f25e3 0fc19dc68b8cd5b5 240ca1cc77ac9c65 2de92c6f592b0275 4a7484aa6ea6e483 5cb0a9dcbd41fbd4 76f988da831153b5 " +
            "983e5152ee66dfab a831c66d2db43210 b00327c898fb213f bf597fc7beef0ee4 c6e00bf33da88fc2 d5a79147930aa725 06ca6351e003826f 142929670a0e6e70 " +
            "27b70a8546d22ffc 2e1b21385c26c926 4d2c6dfc5ac42aed 53380d139d95b3df 650a73548baf63de 766a0abb3c77b2a8 81c2c92e47edaee6 92722c851482353b " +
            "a2bfe8a14cf10364 a81a664bbc423001 c24b8b70d0f89791 c76c51a30654be30 d192e819d6ef5218 d69906245565a910 f40e35855771202a 106aa07032bbd1b8 " +
            "19a4c116b8d2d0c8 1e376c085141ab53 2748774cdf8eeb99 34b0bcb5e19b48a8 391c0cb3c5c95a63 4ed8aa4ae3418acb 5b9cca4f7763e373 682e6ff3d6b2b8a3 " +
            "748f82ee5defb2fc 78a5636f43172f60 84c87814a1f0ab72 8cc702081a6439ec 90befffa23631e28 a4506cebde82bde9 bef9a3f7b2c67915 c67178f2e372532b " +
            "ca273eceea26619c d186b8c721c0c207 eada7dd6cde0eb1e f57d4f7fee6ed178 06f067aa72176fba 0a637dc5a2c898a6 113f9804bef90dae 1b710b35131c471b " +
            "28db77f523047d84 32caab7b40c72493 3c9ebe0a15c9bebc 431d67c49c100d4c 4cc5d4becb3e42b6 597f299cfc657e2a 5fcb6fab3ad6faec 6c44198c4a475817"
        ).split(' ').map { it.toULong(16).toLong() }.toLongArray()

    private val H0 = "6a09e667f3bcc908 bb67ae8584caa73b 3c6ef372fe94f82b a54ff53a5f1d36f1 510e527fade682d1 9b05688c2b3e6c1f 1f83d9abfb41bd6b 5be0cd19137e2179"
        .split(' ').map { it.toULong(16).toLong() }.toLongArray()

    fun digest(message: ByteArray): ByteArray = digest(NO_PREFIX, message, H0, 64)

    fun digest(prefix: ByteArray, message: ByteArray): ByteArray = digest(prefix, message, H0, 64)

    internal fun digest(prefix: ByteArray, message: ByteArray, initial: LongArray, length: Int): ByteArray {
        val h = initial.copyOf()
        val w = LongArray(80)
        forEachBlock(prefix, message, 128, 16, littleEndian = false) { data, chunk ->
            for (i in 0 until 16) w[i] = readLongBE(data, chunk + i * 8)
            for (i in 16 until 80) {
                val s0 = w[i - 15].rotateRight(1) xor w[i - 15].rotateRight(8) xor (w[i - 15] ushr 7)
                val s1 = w[i - 2].rotateRight(19) xor w[i - 2].rotateRight(61) xor (w[i - 2] ushr 6)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]
            for (i in 0 until 80) {
                val s1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                hh = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
        }
        val out = ByteArray(64)
        for (i in 0 until 8) writeLongBE(out, i * 8, h[i])
        return if (length == 64) out else out.copyOf(length)
    }
}

object Crc32 {
    private val table = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
        c
    }

    fun compute(data: ByteArray): Int {
        var crc = -1
        for (b in data) crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }

    fun digest(data: ByteArray): ByteArray {
        val out = ByteArray(4)
        writeIntBE(out, 0, compute(data))
        return out
    }
}

object Sha384 {
    private val H0 = "cbbb9d5dc1059ed8 629a292a367cd507 9159015a3070dd17 152fecd8f70e5939 67332667ffc00b31 8eb44a8768581511 db0c2e0d64f98fa7 47b5481dbefa4fa4"
        .split(' ').map { it.toULong(16).toLong() }.toLongArray()

    fun digest(message: ByteArray): ByteArray = Sha512.digest(NO_PREFIX, message, H0, 48)
}
