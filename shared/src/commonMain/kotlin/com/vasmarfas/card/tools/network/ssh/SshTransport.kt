package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.core.SshHostKey
import com.vasmarfas.card.core.TcpStream
import com.vasmarfas.card.core.secureRandomBytes
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.Sha1
import com.vasmarfas.card.tools.developer.Sha256
import com.vasmarfas.card.tools.developer.Sha512
import com.vasmarfas.card.tools.network.Rsa
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SshDisconnect : Exception()

private const val CLIENT_VERSION = "SSH-2.0-Mobitool"
private const val STRICT_CLIENT = "kex-strict-c-v00@openssh.com"
private const val STRICT_SERVER = "kex-strict-s-v00@openssh.com"

// the order says what the client prefers. Ed25519 and ECDSA host keys come before RSA as they did with sshj,
// so fingerprints saved by earlier versions still match
private val HOST_KEYS = listOf(
    "ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "rsa-sha2-512", "rsa-sha2-256", "ssh-rsa",
)

internal class KexResult(val secret: ByteArray, val hash: ByteArray, val hostKey: ByteArray, val signature: ByteArray)

internal abstract class Kex(val name: String, val digest: (ByteArray) -> ByteArray) {
    abstract suspend fun run(transport: SshTransport, prefix: ByteArray): KexResult

    protected fun hashOf(prefix: ByteArray, writer: SshWriter) = digest(prefix + writer.bytes())

    companion object {
        private fun group(bits: Int, c: Int) = lazy { Pi.modpPrime(bits, c) }

        val all: List<Kex> by lazy {
            val group14 = group(2048, 124476)
            val group16 = group(4096, 240904)
            val group18 = group(8192, 4743158)
            listOf(
                Curve25519Kex("curve25519-sha256"),
                Curve25519Kex("curve25519-sha256@libssh.org"),
                EcdhKex(NistCurve.P256),
                EcdhKex(NistCurve.P384),
                EcdhKex(NistCurve.P521),
                GroupExchangeKex("diffie-hellman-group-exchange-sha256", Sha256::digest),
                DhKex("diffie-hellman-group16-sha512", Sha512::digest, group16),
                DhKex("diffie-hellman-group18-sha512", Sha512::digest, group18),
                DhKex("diffie-hellman-group14-sha256", Sha256::digest, group14),
                DhKex("diffie-hellman-group14-sha1", Sha1::digest, group14),
                GroupExchangeKex("diffie-hellman-group-exchange-sha1", Sha1::digest),
            )
        }
    }
}

private class Curve25519Kex(name: String) : Kex(name, Sha256::digest) {
    override suspend fun run(transport: SshTransport, prefix: ByteArray): KexResult {
        val secret = secureRandomBytes(32)
        val ours = X25519.publicKey(secret)
        transport.sendKex(SshWriter().byte(30).string(ours).bytes())
        val reply = transport.expect(31)
        val hostKey = reply.string()
        val theirs = reply.string()
        val signature = reply.string()
        if (theirs.size != 32) throw sshFailure()
        val shared = X25519.shared(secret, theirs)
        if (shared.all { it.toInt() == 0 }) throw sshFailure()
        val hash = hashOf(prefix, SshWriter().string(hostKey).string(ours).string(theirs).mpint(shared))
        return KexResult(shared, hash, hostKey, signature)
    }
}

private class EcdhKex(private val curve: NistCurve) : Kex("ecdh-sha2-${curve.name}", curve.hash) {
    override suspend fun run(transport: SshTransport, prefix: ByteArray): KexResult {
        val (secret, ours) = curve.keyPair()
        transport.sendKex(SshWriter().byte(30).string(ours).bytes())
        val reply = transport.expect(31)
        val hostKey = reply.string()
        val theirs = reply.string()
        val signature = reply.string()
        val shared = curve.shared(secret, theirs) ?: throw sshFailure()
        val hash = hashOf(prefix, SshWriter().string(hostKey).string(ours).string(theirs).mpint(shared))
        return KexResult(shared, hash, hostKey, signature)
    }
}

// 512 bits is more than twice the security level of every group here, the usual length of a DH exponent
private fun dhSecret(): ByteArray = secureRandomBytes(64).also { it[0] = (it[0].toInt() or 0x40).toByte() }

private fun dhPublic(prime: ByteArray, value: ByteArray): Boolean {
    val pMinusOne = prime.copyOf().also { it[it.size - 1] = (it[it.size - 1] - 1).toByte() }
    return compareMagnitude(value, byteArrayOf(1)) > 0 && compareMagnitude(value, pMinusOne) < 0
}

private class DhKex(name: String, digest: (ByteArray) -> ByteArray, group: Lazy<ByteArray>) : Kex(name, digest) {
    private val prime by group

    override suspend fun run(transport: SshTransport, prefix: ByteArray): KexResult {
        val x = dhSecret()
        val e = Rsa.modPow(byteArrayOf(2), x, prime)
        transport.sendKex(SshWriter().byte(30).mpint(e).bytes())
        val reply = transport.expect(31)
        val hostKey = reply.string()
        val f = reply.mpint()
        val signature = reply.string()
        if (!dhPublic(prime, f)) throw sshFailure()
        val shared = Rsa.modPow(f, x, prime)
        val hash = hashOf(prefix, SshWriter().string(hostKey).mpint(e).mpint(f).mpint(shared))
        return KexResult(shared, hash, hostKey, signature)
    }
}

private class GroupExchangeKex(name: String, digest: (ByteArray) -> ByteArray) : Kex(name, digest) {
    override suspend fun run(transport: SshTransport, prefix: ByteArray): KexResult {
        transport.sendKex(SshWriter().byte(34).uint32(MIN_BITS).uint32(PREFERRED_BITS).uint32(MAX_BITS).bytes())
        val group = transport.expect(31)
        val prime = group.mpint()
        val generator = group.mpint()
        val bits = prime.size * 8 - (prime[0].toInt() and 0xFF).countLeadingZeroBits() + 24
        if (bits !in MIN_BITS..MAX_BITS || prime.last().toInt() and 1 == 0 || !dhPublic(prime, generator)) throw sshFailure()
        val x = dhSecret()
        val e = Rsa.modPow(generator, x, prime)
        transport.sendKex(SshWriter().byte(32).mpint(e).bytes())
        val reply = transport.expect(33)
        val hostKey = reply.string()
        val f = reply.mpint()
        val signature = reply.string()
        if (!dhPublic(prime, f)) throw sshFailure()
        val shared = Rsa.modPow(f, x, prime)
        val hash = hashOf(
            prefix,
            SshWriter().string(hostKey).uint32(MIN_BITS).uint32(PREFERRED_BITS).uint32(MAX_BITS)
                .mpint(prime).mpint(generator).mpint(e).mpint(f).mpint(shared),
        )
        return KexResult(shared, hash, hostKey, signature)
    }

    private companion object {
        const val MIN_BITS = 2048
        const val PREFERRED_BITS = 3072
        const val MAX_BITS = 8192
    }
}

// RFC 4253: version exchange, algorithm negotiation, key exchange and the encrypted packet stream. Only the
// reading coroutine calls receive, so a rekey the server starts runs right there, while send holds other
// packets back until the new keys are in place
internal class SshTransport(private val tcp: TcpStream, private val trust: (SshHostKey) -> Boolean) {
    private val input = SshInput(tcp)
    private val sendLock = Mutex()

    @Volatile
    private var kexGate: CompletableDeferred<Unit>? = null
    private var sendSequence = 0
    private var receiveSequence = 0
    private var encoder: PacketCodec = PlainCodec
    private var decoder: PacketCodec = PlainCodec
    private var serverVersion = ""
    private var strict = false
    private var hostKey: ByteArray? = null
    var sessionId = ByteArray(0)
        private set

    suspend fun start() {
        tcp.write("$CLIENT_VERSION\r\n".encodeToByteArray())
        var line = input.line()
        while (!line.startsWith("SSH-")) line = input.line()
        if (!line.startsWith("SSH-2.0-") && !line.startsWith("SSH-1.99-")) throw sshFailure()
        serverVersion = line
        val ours = kexInit(first = true)
        sendKex(ours)
        val theirs = receive()
        if (theirs[0].toInt() != 20) throw sshFailure()
        exchange(ours, theirs)
    }

    private fun kexInit(first: Boolean): ByteArray {
        val kex = Kex.all.map { it.name } + if (first) listOf("ext-info-c", STRICT_CLIENT) else emptyList()
        return SshWriter().byte(20).raw(secureRandomBytes(16))
            .names(kex)
            .names(HOST_KEYS)
            .names(CipherSpec.all.map { it.name })
            .names(CipherSpec.all.map { it.name })
            .names(MacSpec.all.map { it.name })
            .names(MacSpec.all.map { it.name })
            .names(listOf("none"))
            .names(listOf("none"))
            .names(emptyList())
            .names(emptyList())
            .boolean(false)
            .uint32(0)
            .bytes()
    }

    private fun <T> choose(ours: List<T>, theirs: List<String>, name: (T) -> String): T =
        ours.firstOrNull { name(it) in theirs } ?: throw NetException(Res.string.ssh_error_algorithms)

    private suspend fun exchange(ours: ByteArray, theirs: ByteArray) {
        val server = SshReader(theirs)
        server.raw(17)
        val kexNames = server.names()
        val hostKeyNames = server.names()
        val cipherOutNames = server.names()
        val cipherInNames = server.names()
        val macOutNames = server.names()
        val macInNames = server.names()
        if ("none" !in server.names() || "none" !in server.names()) throw NetException(Res.string.ssh_error_algorithms)
        server.names()
        server.names()
        val guessed = server.boolean()

        val kex = choose(Kex.all, kexNames) { it.name }
        val hostKeyAlgorithm = choose(HOST_KEYS, hostKeyNames) { it }
        val cipherOut = choose(CipherSpec.all, cipherOutNames) { it.name }
        val cipherIn = choose(CipherSpec.all, cipherInNames) { it.name }
        val macOut = if (cipherOut.aead) null else choose(MacSpec.all, macOutNames) { it.name }
        val macIn = if (cipherIn.aead) null else choose(MacSpec.all, macInNames) { it.name }
        if (sessionId.isEmpty()) strict = STRICT_SERVER in kexNames
        if (guessed && (kexNames.firstOrNull() != kex.name || hostKeyNames.firstOrNull() != hostKeyAlgorithm)) receive()

        val prefix = SshWriter().string(CLIENT_VERSION).string(serverVersion).string(ours).string(theirs).bytes()
        val result = kex.run(this, prefix)
        if (!verifyHostSignature(hostKeyAlgorithm, result.hostKey, result.hash, result.signature)) throw sshFailure()
        val known = hostKey
        if (known == null) {
            if (!trust(SshHostKey(SshReader(result.hostKey).text(), result.hostKey))) throw sshFailure()
            hostKey = result.hostKey
        } else if (!known.contentEquals(result.hostKey)) {
            throw sshFailure()
        }
        if (sessionId.isEmpty()) sessionId = result.hash

        val secret = SshWriter().mpint(result.secret).bytes()
        fun derive(letter: Char, size: Int): ByteArray {
            var out = kex.digest(secret + result.hash + byteArrayOf(letter.code.toByte()) + sessionId)
            while (out.size < size) out += kex.digest(secret + result.hash + out)
            return out.copyOf(size)
        }
        val outgoing = cipherOut.codec(
            derive('C', cipherOut.keySize), derive('A', cipherOut.ivSize), macOut?.mac(derive('E', macOut.keySize)), encrypt = true,
        )
        val incoming = cipherIn.codec(
            derive('D', cipherIn.keySize), derive('B', cipherIn.ivSize), macIn?.mac(derive('F', macIn.keySize)), encrypt = false,
        )
        sendLock.withLock {
            write(byteArrayOf(21))
            encoder = outgoing
            if (strict) sendSequence = 0
        }
        if (receive()[0].toInt() != 21) throw sshFailure()
        decoder = incoming
        if (strict) receiveSequence = 0
    }

    // the server asked for new keys, its KEXINIT is already read
    suspend fun rekey(theirs: ByteArray) {
        val ours = kexInit(first = false)
        sendLock.withLock {
            kexGate = CompletableDeferred()
            write(ours)
        }
        try {
            exchange(ours, theirs)
        } finally {
            val gate = kexGate
            kexGate = null
            gate?.complete(Unit)
        }
    }

    private suspend fun write(payload: ByteArray) {
        tcp.write(encoder.seal(sendSequence, encoder.frame(payload)))
        sendSequence++
    }

    private suspend fun read(): ByteArray {
        val plain = decoder.open(receiveSequence, input)
        receiveSequence++
        val padding = plain[0].toInt() and 0xFF
        if (padding < 4 || padding + 2 > plain.size) throw sshFailure()
        return plain.copyOfRange(1, plain.size - padding)
    }

    suspend fun sendKex(payload: ByteArray) = sendLock.withLock { write(payload) }

    suspend fun send(payload: ByteArray) {
        while (true) {
            val gate = kexGate
            if (gate != null) {
                gate.await()
                continue
            }
            sendLock.withLock {
                if (kexGate == null) {
                    write(payload)
                    return
                }
            }
        }
    }

    // the next message other than ignore, debug and unimplemented
    suspend fun receive(): ByteArray {
        while (true) {
            val payload = read()
            when (payload[0].toInt()) {
                1 -> throw SshDisconnect()
                2, 3, 4 -> continue
                else -> return payload
            }
        }
    }

    suspend fun expect(type: Int): SshReader {
        val payload = receive()
        if (payload[0].toInt() != type) throw sshFailure()
        return SshReader(payload).also { it.byte() }
    }

    fun close() = tcp.close()
}
