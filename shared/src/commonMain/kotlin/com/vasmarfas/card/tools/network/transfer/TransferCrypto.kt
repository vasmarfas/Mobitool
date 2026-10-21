package com.vasmarfas.card.tools.network.transfer

import com.vasmarfas.card.tools.developer.HashAlgorithm
import com.vasmarfas.card.tools.developer.hmac

// RFC 8439; the browser build has no crypto API reachable from Kotlin/Wasm, so it is spelled out here
object ChaCha20Poly1305 {
    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun block(state: IntArray, out: ByteArray) {
        val x = state.copyOf()
        repeat(10) {
            quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val word = x[i] + state[i]
            out[i * 4] = word.toByte()
            out[i * 4 + 1] = (word ushr 8).toByte()
            out[i * 4 + 2] = (word ushr 16).toByte()
            out[i * 4 + 3] = (word ushr 24).toByte()
        }
    }

    private fun state(key: ByteArray, nonce: ByteArray, counter: Int) = IntArray(16).also { s ->
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(nonce, i * 4)
    }

    fun xor(key: ByteArray, nonce: ByteArray, counter: Int, data: ByteArray): ByteArray {
        val s = state(key, nonce, counter)
        val stream = ByteArray(64)
        val out = ByteArray(data.size)
        for (start in data.indices step 64) {
            block(s, stream)
            s[12]++
            for (i in start until minOf(start + 64, data.size)) out[i] = (data[i].toInt() xor stream[i - start].toInt()).toByte()
        }
        return out
    }

    fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        val mask = 0x3ffffffL
        val r0 = le32(key, 0).toLong() and 0x3ffffff
        val r1 = (le32(key, 3).toLong() and 0xffffffffL ushr 2) and 0x3ffff03
        val r2 = (le32(key, 6).toLong() and 0xffffffffL ushr 4) and 0x3ffc0ff
        val r3 = (le32(key, 9).toLong() and 0xffffffffL ushr 6) and 0x3f03fff
        val r4 = (le32(key, 12).toLong() and 0xffffffffL ushr 8) and 0x00fffff
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        val chunk = ByteArray(17)
        for (start in message.indices step 16) {
            val length = minOf(16, message.size - start)
            chunk.fill(0)
            message.copyInto(chunk, 0, start, start + length)
            val hibit = if (length == 16) 1L shl 24 else 0L
            if (length < 16) chunk[length] = 1
            h0 += le32(chunk, 0).toLong() and 0x3ffffff
            h1 += (le32(chunk, 3).toLong() and 0xffffffffL ushr 2) and 0x3ffffff
            h2 += (le32(chunk, 6).toLong() and 0xffffffffL ushr 4) and 0x3ffffff
            h3 += (le32(chunk, 9).toLong() and 0xffffffffL ushr 6) and 0x3ffffff
            h4 += (le32(chunk, 12).toLong() and 0xffffffffL ushr 8) or hibit
            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            h0 = d0 and mask; d1 += d0 ushr 26
            h1 = d1 and mask; d2 += d1 ushr 26
            h2 = d2 and mask; d3 += d2 ushr 26
            h3 = d3 and mask; d4 += d3 ushr 26
            h4 = d4 and mask
            h0 += (d4 ushr 26) * 5
            h1 += h0 ushr 26; h0 = h0 and mask
        }
        var c = h1 ushr 26; h1 = h1 and mask
        h2 += c; c = h2 ushr 26; h2 = h2 and mask
        h3 += c; c = h3 ushr 26; h3 = h3 and mask
        h4 += c; c = h4 ushr 26; h4 = h4 and mask
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and mask
        h1 += c
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and mask
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and mask
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and mask
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and mask
        val g4 = h4 + c - (1L shl 26)
        // g4 below zero means h was already under 2^130 - 5
        val useG = (g4 ushr 63) - 1
        h0 = (h0 and useG.inv()) or (g0 and useG)
        h1 = (h1 and useG.inv()) or (g1 and useG)
        h2 = (h2 and useG.inv()) or (g2 and useG)
        h3 = (h3 and useG.inv()) or (g3 and useG)
        h4 = (h4 and useG.inv()) or (g4 and useG)
        val words = longArrayOf(
            (h0 or (h1 shl 26)) and 0xffffffffL,
            ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL,
            ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL,
            ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL,
        )
        val tag = ByteArray(16)
        var carry = 0L
        for (i in 0 until 4) {
            val f = words[i] + (le32(key, 16 + i * 4).toLong() and 0xffffffffL) + carry
            carry = f ushr 32
            for (b in 0 until 4) tag[i * 4 + b] = (f ushr (b * 8)).toByte()
        }
        return tag
    }

    private fun macInput(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        fun padded(size: Int) = (size + 15) / 16 * 16
        val out = ByteArray(padded(aad.size) + padded(ciphertext.size) + 16)
        aad.copyInto(out)
        ciphertext.copyInto(out, padded(aad.size))
        val lengths = out.size - 16
        for (b in 0 until 8) {
            out[lengths + b] = (aad.size.toLong() ushr (b * 8)).toByte()
            out[lengths + 8 + b] = (ciphertext.size.toLong() ushr (b * 8)).toByte()
        }
        return out
    }

    private fun oneTimeKey(key: ByteArray, nonce: ByteArray) = ByteArray(64).also { block(state(key, nonce, 0), it) }.copyOf(32)

    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val ciphertext = xor(key, nonce, 1, plaintext)
        return ciphertext + poly1305(oneTimeKey(key, nonce), macInput(aad, ciphertext))
    }

    fun open(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < 16) return null
        val ciphertext = sealed.copyOf(sealed.size - 16)
        val expected = poly1305(oneTimeKey(key, nonce), macInput(aad, ciphertext))
        var diff = 0
        for (i in 0 until 16) diff = diff or (expected[i].toInt() xor sealed[sealed.size - 16 + i].toInt())
        return if (diff == 0) xor(key, nonce, 1, ciphertext) else null
    }
}

fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
    val out = ByteArray(length)
    var index = 1
    var filled = 0
    while (filled < length) {
        var u = hmac(HashAlgorithm.SHA256, password, salt + byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte()))
        val t = u.copyOf()
        repeat(iterations - 1) {
            u = hmac(HashAlgorithm.SHA256, password, u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        t.copyInto(out, filled, 0, minOf(32, length - filled))
        filled += 32
        index++
    }
    return out
}
