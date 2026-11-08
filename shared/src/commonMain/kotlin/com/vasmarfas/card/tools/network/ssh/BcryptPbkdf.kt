package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.tools.developer.Sha512

// bcrypt_pbkdf from OpenBSD, the key derivation of passphrase-protected OpenSSH keys
internal object BcryptPbkdf {
    private val MAGIC = "OxychromaticBlowfishSwatDynamite".encodeToByteArray()

    fun derive(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        val key = ByteArray(length)
        val stride = (length + 31) / 32
        val amount = (length + stride - 1) / stride
        val sha2pass = Sha512.digest(password)
        var remaining = length
        var count = 1
        while (remaining > 0) {
            val countSalt = byteArrayOf((count ushr 24).toByte(), (count ushr 16).toByte(), (count ushr 8).toByte(), count.toByte())
            var tmp = hash(sha2pass, Sha512.digest(salt + countSalt))
            val out = tmp.copyOf()
            repeat(rounds - 1) {
                tmp = hash(sha2pass, Sha512.digest(tmp))
                for (j in out.indices) out[j] = (out[j].toInt() xor tmp[j].toInt()).toByte()
            }
            var i = 0
            while (i < minOf(amount, remaining)) {
                val dest = i * stride + (count - 1)
                if (dest >= length) break
                key[dest] = out[i]
                i++
            }
            remaining -= i
            count++
        }
        return key
    }

    private fun hash(sha2pass: ByteArray, sha2salt: ByteArray): ByteArray {
        val state = Blowfish()
        state.expand(sha2salt, sha2pass)
        repeat(64) {
            state.expand0(sha2salt)
            state.expand0(sha2pass)
        }
        val data = IntArray(8)
        var position = 0
        for (i in data.indices) {
            var word = 0
            repeat(4) {
                word = word shl 8 or (MAGIC[position++].toInt() and 0xFF)
            }
            data[i] = word
        }
        repeat(64) {
            for (i in data.indices step 2) state.encipher(data, i)
        }
        val out = ByteArray(32)
        for (i in data.indices) {
            out[4 * i] = data[i].toByte()
            out[4 * i + 1] = (data[i] ushr 8).toByte()
            out[4 * i + 2] = (data[i] ushr 16).toByte()
            out[4 * i + 3] = (data[i] ushr 24).toByte()
        }
        return out
    }

    private class Blowfish {
        val p = Pi.fraction.copyOf(18)
        val s = Pi.fraction.copyOfRange(18, 18 + 1024)
        private var position = 0

        private fun f(x: Int): Int =
            ((s[x ushr 24] + s[0x100 + (x ushr 16 and 0xFF)]) xor s[0x200 + (x ushr 8 and 0xFF)]) + s[0x300 + (x and 0xFF)]

        fun encipher(data: IntArray, at: Int) {
            var l = data[at] xor p[0]
            var r = data[at + 1]
            for (i in 1..16 step 2) {
                r = r xor f(l) xor p[i]
                l = l xor f(r) xor p[i + 1]
            }
            data[at] = r xor p[17]
            data[at + 1] = l
        }

        // the key bytes repeat cyclically, as Blowfish_stream2word reads them
        private fun word(bytes: ByteArray): Int {
            var word = 0
            repeat(4) {
                if (position >= bytes.size) position = 0
                word = word shl 8 or (bytes[position++].toInt() and 0xFF)
            }
            return word
        }

        private fun mixKey(key: ByteArray) {
            position = 0
            for (i in p.indices) p[i] = p[i] xor word(key)
        }

        fun expand(data: ByteArray, key: ByteArray) {
            mixKey(key)
            position = 0
            val block = IntArray(2)
            for (i in 0 until 18 step 2) {
                block[0] = block[0] xor word(data)
                block[1] = block[1] xor word(data)
                encipher(block, 0)
                p[i] = block[0]
                p[i + 1] = block[1]
            }
            for (i in 0 until 1024 step 2) {
                block[0] = block[0] xor word(data)
                block[1] = block[1] xor word(data)
                encipher(block, 0)
                s[i] = block[0]
                s[i + 1] = block[1]
            }
        }

        fun expand0(key: ByteArray) {
            mixKey(key)
            val block = IntArray(2)
            for (i in 0 until 18 step 2) {
                encipher(block, 0)
                p[i] = block[0]
                p[i + 1] = block[1]
            }
            for (i in 0 until 1024 step 2) {
                encipher(block, 0)
                s[i] = block[0]
                s[i + 1] = block[1]
            }
        }
    }
}
