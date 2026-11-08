package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.HashAlgorithm
import com.vasmarfas.card.tools.developer.hmac
import com.vasmarfas.card.tools.documents.pdf.Aes
import com.vasmarfas.card.tools.network.transfer.ChaCha20Poly1305

private const val MAX_PACKET = 256 * 1024

internal class SshInput(private val tcp: TcpStream) {
    private val buffer = ByteArray(32 * 1024)
    private var start = 0
    private var end = 0

    private suspend fun fill() {
        val count = tcp.read(buffer, 0, buffer.size)
        if (count < 0) throw NetException(Res.string.net_error_closed)
        start = 0
        end = count
    }

    suspend fun exactly(count: Int): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            if (start == end) fill()
            val take = minOf(count - filled, end - start)
            buffer.copyInto(out, filled, start, start + take)
            start += take
            filled += take
        }
        return out
    }

    // the identification string, after any lines the server prints before it
    suspend fun line(): String {
        val line = StringBuilder()
        while (true) {
            if (start == end) fill()
            val c = buffer[start++].toInt() and 0xFF
            if (c == '\n'.code) return line.toString().removeSuffix("\r")
            if (line.length > 1024) throw sshFailure()
            line.append(c.toChar())
        }
    }
}

private fun lengthOf(bytes: ByteArray): Int =
    (bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)

private fun checkLength(length: Int, alignment: Int) {
    if (length < 5 || length > MAX_PACKET || length % alignment != 0) throw sshFailure()
}

private fun sameBytes(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var difference = 0
    for (i in a.indices) difference = difference or (a[i].toInt() xor b[i].toInt())
    return difference == 0
}

// one direction of the binary packet protocol, RFC 4253 section 6. A packet here starts with its length field,
// open returns it without that field
internal abstract class PacketCodec {
    abstract val blockSize: Int

    // ETM and AEAD modes leave the length field out of the encrypted, aligned part
    open val clearLength = false

    abstract fun seal(sequence: Int, packet: ByteArray): ByteArray

    abstract suspend fun open(sequence: Int, input: SshInput): ByteArray

    fun frame(payload: ByteArray): ByteArray {
        val aligned = (if (clearLength) 0 else 4) + 1 + payload.size
        var padding = blockSize - aligned % blockSize
        if (padding < 4) padding += blockSize
        return SshWriter().uint32(1 + payload.size + padding).byte(padding).raw(payload).raw(secureRandomBytes(padding)).bytes()
    }
}

internal object PlainCodec : PacketCodec() {
    override val blockSize = 8

    override fun seal(sequence: Int, packet: ByteArray) = packet

    override suspend fun open(sequence: Int, input: SshInput): ByteArray {
        val length = lengthOf(input.exactly(4))
        checkLength(length, 1)
        return input.exactly(length)
    }
}

internal class AesCtr(key: ByteArray, iv: ByteArray) {
    private val aes = Aes(key)
    private val counter = iv.copyOf(16)
    private val stream = ByteArray(16)
    private var used = 16

    fun process(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (i in data.indices) {
            if (used == 16) {
                aes.encryptBlock(counter, 0, stream, 0)
                for (j in 15 downTo 0) if (++counter[j] != 0.toByte()) break
                used = 0
            }
            out[i] = (data[i].toInt() xor stream[used++].toInt()).toByte()
        }
        return out
    }
}

internal class AesCbc(key: ByteArray, iv: ByteArray, private val encrypt: Boolean) {
    private val aes = Aes(key)
    private val chain = iv.copyOf(16)

    fun process(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (offset in data.indices step 16) {
            if (encrypt) {
                for (i in 0 until 16) chain[i] = (chain[i].toInt() xor data[offset + i].toInt()).toByte()
                aes.encryptBlock(chain, 0, out, offset)
                out.copyInto(chain, 0, offset, offset + 16)
            } else {
                aes.decryptBlock(data, offset, out, offset)
                for (i in 0 until 16) out[offset + i] = (out[offset + i].toInt() xor chain[i].toInt()).toByte()
                data.copyInto(chain, 0, offset, offset + 16)
            }
        }
        return out
    }
}

internal class SshMac(private val algorithm: HashAlgorithm, private val key: ByteArray, val etm: Boolean) {
    val size = algorithm.digest(ByteArray(0)).size

    fun compute(sequence: Int, data: ByteArray): ByteArray = hmac(algorithm, key, uint32Bytes(sequence) + data)
}

// CTR or CBC with a separate MAC, plain or encrypt-then-MAC
internal class BlockCodec(
    private val cipher: (ByteArray) -> ByteArray,
    private val mac: SshMac,
) : PacketCodec() {
    override val blockSize = 16
    override val clearLength = mac.etm

    override fun seal(sequence: Int, packet: ByteArray): ByteArray = if (mac.etm) {
        val sealed = packet.copyOf(4) + cipher(packet.copyOfRange(4, packet.size))
        sealed + mac.compute(sequence, sealed)
    } else {
        cipher(packet) + mac.compute(sequence, packet)
    }

    override suspend fun open(sequence: Int, input: SshInput): ByteArray {
        if (mac.etm) {
            val head = input.exactly(4)
            val length = lengthOf(head)
            checkLength(length, blockSize)
            val body = input.exactly(length)
            if (!sameBytes(input.exactly(mac.size), mac.compute(sequence, head + body))) throw sshFailure()
            return cipher(body)
        }
        val first = cipher(input.exactly(blockSize))
        val length = lengthOf(first)
        if (length < blockSize - 4) throw sshFailure()
        checkLength(length + 4, blockSize)
        val packet = first + cipher(input.exactly(length + 4 - blockSize))
        if (!sameBytes(input.exactly(mac.size), mac.compute(sequence, packet))) throw sshFailure()
        return packet.copyOfRange(4, packet.size)
    }
}

// AES-GCM as RFC 5647 frames it: the length is the associated data, the last 8 bytes of the nonce count packets
internal class AesGcm(key: ByteArray, iv: ByteArray) {
    private val aes = Aes(key)
    private val nonce = iv.copyOf(12)
    private val hHigh: Long
    private val hLow: Long

    init {
        val h = ByteArray(16)
        aes.encryptBlock(ByteArray(16), 0, h, 0)
        hHigh = long(h, 0)
        hLow = long(h, 8)
    }

    private fun long(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v shl 8 or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun counterBlock(counter: Int) = nonce + uint32Bytes(counter)

    private fun ghash(aad: ByteArray, data: ByteArray): ByteArray {
        var yHigh = 0L
        var yLow = 0L
        fun absorb(bytes: ByteArray) {
            for (offset in bytes.indices step 16) {
                val block = bytes.copyOfRange(offset, minOf(offset + 16, bytes.size)).copyOf(16)
                var xHigh = yHigh xor long(block, 0)
                var xLow = yLow xor long(block, 8)
                var zHigh = 0L
                var zLow = 0L
                var vHigh = hHigh
                var vLow = hLow
                repeat(128) {
                    if (xHigh < 0) {
                        zHigh = zHigh xor vHigh
                        zLow = zLow xor vLow
                    }
                    xHigh = xHigh shl 1 or (xLow ushr 63)
                    xLow = xLow shl 1
                    val lsb = vLow and 1L
                    vLow = vLow ushr 1 or (vHigh shl 63)
                    vHigh = vHigh ushr 1
                    if (lsb != 0L) vHigh = vHigh xor (0xE1L shl 56)
                }
                yHigh = zHigh
                yLow = zLow
            }
        }
        absorb(aad)
        absorb(data)
        val lengths = ByteArray(16)
        val aadBits = aad.size.toLong() * 8
        val dataBits = data.size.toLong() * 8
        for (i in 0 until 8) {
            lengths[i] = (aadBits ushr (56 - 8 * i)).toByte()
            lengths[8 + i] = (dataBits ushr (56 - 8 * i)).toByte()
        }
        absorb(lengths)
        return ByteArray(16) { i -> ((if (i < 8) yHigh else yLow) ushr (56 - 8 * (i % 8))).toByte() }
    }

    private fun ctr(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        val stream = ByteArray(16)
        for (offset in data.indices step 16) {
            aes.encryptBlock(counterBlock(2 + offset / 16), 0, stream, 0)
            for (i in offset until minOf(offset + 16, data.size)) out[i] = (data[i].toInt() xor stream[i - offset].toInt()).toByte()
        }
        return out
    }

    private fun tag(aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val mask = ByteArray(16)
        aes.encryptBlock(counterBlock(1), 0, mask, 0)
        return ghash(aad, ciphertext).also { for (i in 0 until 16) it[i] = (it[i].toInt() xor mask[i].toInt()).toByte() }
    }

    private fun next() {
        for (i in 11 downTo 4) if (++nonce[i] != 0.toByte()) break
    }

    fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
        val ciphertext = ctr(plaintext)
        return (ciphertext + tag(aad, ciphertext)).also { next() }
    }

    fun open(aad: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray? {
        val valid = sameBytes(tag(aad, ciphertext), tag)
        return if (valid) ctr(ciphertext).also { next() } else null
    }
}

internal class GcmCodec(key: ByteArray, iv: ByteArray) : PacketCodec() {
    private val gcm = AesGcm(key, iv)
    override val blockSize = 16
    override val clearLength = true

    override fun seal(sequence: Int, packet: ByteArray): ByteArray {
        val head = packet.copyOf(4)
        return head + gcm.seal(head, packet.copyOfRange(4, packet.size))
    }

    override suspend fun open(sequence: Int, input: SshInput): ByteArray {
        val head = input.exactly(4)
        val length = lengthOf(head)
        checkLength(length, blockSize)
        val body = input.exactly(length)
        return gcm.open(head, body, input.exactly(16)) ?: throw sshFailure()
    }
}

// chacha20-poly1305@openssh.com: the second half of the key hides the length, the first one the rest and the tag.
// The 64-bit nonce is the sequence number, the IETF layout with zero high counter words is the same stream
internal class ChaChaCodec(key: ByteArray) : PacketCodec() {
    private val main = key.copyOf(32)
    private val header = key.copyOfRange(32, 64)
    override val blockSize = 8
    override val clearLength = true

    private fun nonce(sequence: Int) = ByteArray(8) + uint32Bytes(sequence)

    override fun seal(sequence: Int, packet: ByteArray): ByteArray {
        val nonce = nonce(sequence)
        val sealed = ChaCha20Poly1305.xor(header, nonce, 0, packet.copyOf(4)) +
            ChaCha20Poly1305.xor(main, nonce, 1, packet.copyOfRange(4, packet.size))
        val polyKey = ChaCha20Poly1305.xor(main, nonce, 0, ByteArray(32))
        return sealed + ChaCha20Poly1305.poly1305(polyKey, sealed)
    }

    override suspend fun open(sequence: Int, input: SshInput): ByteArray {
        val nonce = nonce(sequence)
        val head = input.exactly(4)
        val length = lengthOf(ChaCha20Poly1305.xor(header, nonce, 0, head))
        checkLength(length, blockSize)
        val body = input.exactly(length)
        val polyKey = ChaCha20Poly1305.xor(main, nonce, 0, ByteArray(32))
        if (!sameBytes(input.exactly(16), ChaCha20Poly1305.poly1305(polyKey, head + body))) throw sshFailure()
        return ChaCha20Poly1305.xor(main, nonce, 1, body)
    }
}

internal class CipherSpec(val name: String, val keySize: Int, val ivSize: Int, val aead: Boolean) {
    fun codec(key: ByteArray, iv: ByteArray, mac: SshMac?, encrypt: Boolean): PacketCodec = when {
        name.startsWith("chacha20") -> ChaChaCodec(key)
        name.contains("gcm") -> GcmCodec(key, iv)
        name.endsWith("ctr") -> AesCtr(key, iv).let { ctr -> BlockCodec(ctr::process, mac!!) }
        else -> AesCbc(key, iv, encrypt).let { cbc -> BlockCodec(cbc::process, mac!!) }
    }

    companion object {
        val all = listOf(
            CipherSpec("chacha20-poly1305@openssh.com", 64, 0, aead = true),
            CipherSpec("aes128-gcm@openssh.com", 16, 12, aead = true),
            CipherSpec("aes256-gcm@openssh.com", 32, 12, aead = true),
            CipherSpec("aes128-ctr", 16, 16, aead = false),
            CipherSpec("aes192-ctr", 24, 16, aead = false),
            CipherSpec("aes256-ctr", 32, 16, aead = false),
            CipherSpec("aes128-cbc", 16, 16, aead = false),
            CipherSpec("aes192-cbc", 24, 16, aead = false),
            CipherSpec("aes256-cbc", 32, 16, aead = false),
        )
    }
}

internal class MacSpec(val name: String, val algorithm: HashAlgorithm) {
    val keySize = algorithm.digest(ByteArray(0)).size

    fun mac(key: ByteArray) = SshMac(algorithm, key, name.endsWith("-etm@openssh.com"))

    companion object {
        val all = listOf(
            MacSpec("hmac-sha2-256-etm@openssh.com", HashAlgorithm.SHA256),
            MacSpec("hmac-sha2-512-etm@openssh.com", HashAlgorithm.SHA512),
            MacSpec("hmac-sha1-etm@openssh.com", HashAlgorithm.SHA1),
            MacSpec("hmac-sha2-256", HashAlgorithm.SHA256),
            MacSpec("hmac-sha2-512", HashAlgorithm.SHA512),
            MacSpec("hmac-sha1", HashAlgorithm.SHA1),
        )
    }
}
