package com.vasmarfas.card.core

import com.vasmarfas.card.resources.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.TreeMap
import java.util.TreeSet
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private const val INITIAL = 0
private const val HANDSHAKE = 1
private const val APPLICATION = 2
private const val RESEND_MS = 1000L

private val EMPTY = ByteArray(0)
private val INITIAL_SALT = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
private val RETRY_KEY = hex("be0c690b9f66575a1d766b54e368c84e")
private val RETRY_NONCE = hex("461599d35d632bf2239825bb")
private val HELLO_RETRY_RANDOM = hex("cf21ad74e59a6111be1d8c021e65b891c2a211167abb8c5e079e09e2c8a8339c")
private val SIGNATURE_SCHEMES = intArrayOf(0x0403, 0x0804, 0x0503, 0x0805, 0x0806, 0x0603, 0x0401, 0x0501, 0x0601)
private val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
private val NOT_ACK_ELICITING = setOf(0x00, 0x02, 0x03, 0x1c, 0x1d)

internal class QuicDnsExchange(private val host: String, private val port: Int, private val timeoutMs: Int) {
    private val random = SecureRandom()
    private val sourceId = randomBytes(8)
    private val originalId = randomBytes(8)
    private var destinationId = originalId
    private var serverIdKnown = false
    private var token = EMPTY
    private val spaces = Array(3) { QuicSpace() }
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val hello = clientHello()
    private val transcript = ByteArrayOutputStream().apply { write(hello) }
    private var clientHandshakeSecret = EMPTY
    private var serverHandshakeSecret = EMPTY
    private var masterSecret = EMPTY
    private var certificates = emptyList<X509Certificate>()
    private var alpnAccepted = false
    private var serverVerified = false
    private var clientFinished: ByteArray? = null
    private var nextSend = 0L
    private val answer = QuicReassembly()

    init {
        setInitialKeys(originalId)
    }

    fun run(query: ByteArray): ByteArray {
        val address = InetAddress.getByName(host)
        val deadline = System.currentTimeMillis() + timeoutMs
        DatagramSocket().use { socket ->
            socket.connect(address, port)
            val buffer = ByteArray(65536)
            while (true) {
                val now = System.currentTimeMillis()
                if (now >= deadline) throw SocketTimeoutException(host)
                if (now >= nextSend) {
                    socket.sendBytes(flight(query))
                    nextSend = now + RESEND_MS
                }
                socket.soTimeout = (minOf(deadline, nextSend) - now).toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val finishedBefore = clientFinished != null
                try {
                    onDatagram(buffer.copyOf(packet.length))
                } catch (_: IndexOutOfBoundsException) {
                    continue
                }
                answer.dnsMessage()?.let { message ->
                    runCatching { socket.sendBytes(shortPacket(closeFrame())) }
                    return message
                }
                when {
                    clientFinished != null && !finishedBefore -> nextSend = 0
                    clientFinished == null -> ackOnly()?.let { socket.sendBytes(it) }
                }
            }
        }
    }

    private fun flight(query: ByteArray): ByteArray {
        val finished = clientFinished
        return when {
            finished != null -> longPacket(HANDSHAKE, ackFrame(HANDSHAKE) + cryptoFrame(finished)) + shortPacket(streamFrame(query))
            spaces[HANDSHAKE].send != null -> longPacket(HANDSHAKE, ackFrame(HANDSHAKE) + byteArrayOf(0x01))
            else -> longPacket(INITIAL, ackFrame(INITIAL) + cryptoFrame(hello), 1200)
        }
    }

    private fun ackOnly(): ByteArray? = when {
        spaces[HANDSHAKE].send != null -> if (spaces[HANDSHAKE].ackPending) longPacket(HANDSHAKE, ackFrame(HANDSHAKE)) else null
        spaces[INITIAL].ackPending -> longPacket(INITIAL, ackFrame(INITIAL), 1200)
        else -> null
    }

    private fun setInitialKeys(connectionId: ByteArray) {
        val secret = extract(INITIAL_SALT, connectionId)
        spaces[INITIAL].send = QuicKeys(expandLabel(secret, "client in", EMPTY, 32))
        spaces[INITIAL].receive = QuicKeys(expandLabel(secret, "server in", EMPTY, 32))
    }

    private fun longPacket(index: Int, frames: ByteArray, datagramSize: Int = 0): ByteArray {
        val space = spaces[index]
        val number = space.nextNumber++
        val header = bytes {
            u8(0xC3 or (if (index == INITIAL) 0x00 else 0x20))
            u32(1)
            u8(destinationId.size)
            write(destinationId)
            u8(sourceId.size)
            write(sourceId)
            if (index == INITIAL) {
                varint(token.size.toLong())
                write(token)
            }
        }
        val payload = frames + ByteArray((datagramSize - header.size - 2 - 4 - frames.size - 16).coerceAtLeast(0))
        val full = header + bytes {
            u16(0x4000 or (4 + payload.size + 16))
            u32(number)
        }
        return protect(full, full.size - 4, number, payload, space.send!!, 0x0F)
    }

    private fun shortPacket(frames: ByteArray): ByteArray {
        val space = spaces[APPLICATION]
        val number = space.nextNumber++
        val header = bytes {
            u8(0x43)
            write(destinationId)
            u32(number)
        }
        return protect(header, header.size - 4, number, frames, space.send!!, 0x1F)
    }

    private fun protect(header: ByteArray, numberOffset: Int, number: Long, payload: ByteArray, keys: QuicKeys, firstByteMask: Int): ByteArray {
        val packet = header + keys.seal(number, header, payload)
        val mask = keys.mask(packet, numberOffset + 4)
        packet[0] = (packet[0].toInt() xor (mask[0].toInt() and firstByteMask)).toByte()
        for (i in 0 until 4) packet[numberOffset + i] = (packet[numberOffset + i].toInt() xor mask[i + 1].toInt()).toByte()
        return packet
    }

    private fun cryptoFrame(data: ByteArray) = bytes {
        varint(0x06)
        varint(0)
        varint(data.size.toLong())
        write(data)
    }

    private fun streamFrame(query: ByteArray) = bytes {
        varint(0x0b)
        varint(0)
        varint(query.size + 2L)
        u16(query.size)
        write(query)
    }

    private fun closeFrame() = bytes {
        varint(0x1d)
        varint(0)
        varint(0)
    }

    private fun ackFrame(index: Int): ByteArray {
        val space = spaces[index]
        space.ackPending = false
        if (space.received.isEmpty()) return EMPTY
        val ranges = mutableListOf<LongArray>()
        for (number in space.received.descendingSet()) {
            val last = ranges.lastOrNull()
            if (last != null && last[1] == number + 1) last[1] = number else ranges.add(longArrayOf(number, number))
        }
        return bytes {
            varint(0x02)
            varint(ranges[0][0])
            varint(0)
            varint(ranges.size - 1L)
            varint(ranges[0][0] - ranges[0][1])
            for (i in 1 until ranges.size) {
                varint(ranges[i - 1][1] - ranges[i][0] - 2)
                varint(ranges[i][0] - ranges[i][1])
            }
        }
    }

    private fun onDatagram(data: ByteArray) {
        var start = 0
        while (start < data.size) {
            if (data[start].toInt() and 0x80 == 0) {
                unprotect(data, start, start + 1 + sourceId.size, data.size, APPLICATION, 0x1F)?.let { onFrames(APPLICATION, it) }
                return
            }
            start = onLongPacket(data, start) ?: return
        }
    }

    private fun onLongPacket(data: ByteArray, start: Int): Int? {
        val reader = QuicReader(data, start)
        val type = (reader.u8() shr 4) and 3
        if (reader.u32() == 0L) throw NetException(Res.string.dns_doq_unsupported)
        reader.skip(reader.u8())
        val serverId = reader.bytes(reader.u8())
        if (type == 3) {
            onRetry(data, start, reader.pos, serverId)
            return null
        }
        if (type == 0) reader.skip(reader.varint().toInt())
        val length = reader.varint().toInt()
        val end = reader.pos + length
        if (end > data.size) return null
        val index = when (type) {
            0 -> INITIAL
            2 -> HANDSHAKE
            else -> return end
        }
        val payload = unprotect(data, start, reader.pos, end, index, 0x0F) ?: return end
        if (index == INITIAL && !serverIdKnown) {
            destinationId = serverId
            serverIdKnown = true
        }
        onFrames(index, payload)
        return end
    }

    private fun onRetry(data: ByteArray, start: Int, tokenStart: Int, serverId: ByteArray) {
        if (serverIdKnown || data.size - tokenStart < 16) return
        val tokenEnd = data.size - 16
        val pseudoPacket = bytes {
            u8(originalId.size)
            write(originalId)
            write(data, start, tokenEnd - start)
        }
        val tag = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(RETRY_KEY, "AES"), GCMParameterSpec(128, RETRY_NONCE))
            updateAAD(pseudoPacket)
            doFinal()
        }
        if (!MessageDigest.isEqual(tag, data.copyOfRange(tokenEnd, data.size))) return
        token = data.copyOfRange(tokenStart, tokenEnd)
        destinationId = serverId
        serverIdKnown = true
        setInitialKeys(serverId)
        nextSend = 0
    }

    private fun unprotect(data: ByteArray, start: Int, numberOffset: Int, end: Int, index: Int, firstByteMask: Int): ByteArray? {
        val space = spaces[index]
        val keys = space.receive ?: return null
        if (numberOffset + 20 > end) return null
        val mask = keys.mask(data, numberOffset + 4)
        val header = data.copyOfRange(start, numberOffset + 4)
        header[0] = (header[0].toInt() xor (mask[0].toInt() and firstByteMask)).toByte()
        val numberLength = (header[0].toInt() and 3) + 1
        var truncated = 0L
        for (i in 0 until numberLength) {
            val at = numberOffset - start + i
            header[at] = (header[at].toInt() xor mask[i + 1].toInt()).toByte()
            truncated = (truncated shl 8) or (header[at].toLong() and 0xFF)
        }
        val number = decodePacketNumber(space.largest, truncated, numberLength * 8)
        val aad = header.copyOf(numberOffset - start + numberLength)
        val payload = runCatching { keys.open(number, aad, data.copyOfRange(numberOffset + numberLength, end)) }.getOrNull() ?: return null
        space.received.add(number)
        space.largest = maxOf(space.largest, number)
        return payload
    }

    private fun onFrames(index: Int, payload: ByteArray) {
        val reader = QuicReader(payload)
        while (reader.remaining > 0) {
            val type = reader.varint().toInt()
            if (type !in NOT_ACK_ELICITING) spaces[index].ackPending = true
            when (type) {
                0x00, 0x01, 0x1e -> Unit
                0x02, 0x03 -> {
                    reader.varint()
                    reader.varint()
                    val count = reader.varint()
                    reader.varint()
                    repeat(count.toInt() * 2) { reader.varint() }
                    if (type == 0x03) repeat(3) { reader.varint() }
                }
                0x04 -> {
                    val stream = reader.varint()
                    val code = reader.varint()
                    reader.varint()
                    if (stream == 0L) throw NetException(Res.string.dns_connection_closed, "0x" + code.toString(16))
                }
                0x05, 0x11, 0x15 -> repeat(2) { reader.varint() }
                0x06 -> {
                    val offset = reader.varint()
                    val data = reader.bytes(reader.varint().toInt())
                    if (index != APPLICATION) spaces[index].crypto.add(offset, data)
                }
                0x07 -> reader.skip(reader.varint().toInt())
                in 0x08..0x0f -> {
                    val stream = reader.varint()
                    val offset = if (type and 0x04 != 0) reader.varint() else 0
                    val data = reader.bytes(if (type and 0x02 != 0) reader.varint().toInt() else reader.remaining)
                    if (stream == 0L) answer.add(offset, data)
                }
                0x10, 0x12, 0x13, 0x14, 0x16, 0x17, 0x19 -> reader.varint()
                0x18 -> {
                    reader.varint()
                    reader.varint()
                    reader.skip(reader.u8() + 16)
                }
                0x1a, 0x1b -> reader.skip(8)
                0x1c, 0x1d -> {
                    val code = reader.varint()
                    if (type == 0x1c) reader.varint()
                    reader.skip(reader.varint().toInt())
                    throw when {
                        type == 0x1c && code == 0x178L -> NetException(Res.string.dns_doq_unsupported)
                        type == 0x1c && code in 0x100L..0x1FFL -> SSLHandshakeException("TLS alert ${code - 0x100}")
                        else -> NetException(Res.string.dns_connection_closed, "0x" + code.toString(16))
                    }
                }
                else -> throw IndexOutOfBoundsException()
            }
        }
        if (index == APPLICATION) return
        while (true) onHandshakeMessage(spaces[index].crypto.handshakeMessage() ?: return)
    }

    private fun onHandshakeMessage(message: ByteArray) {
        val reader = QuicReader(message, 4)
        when (message[0].toInt()) {
            2 -> onServerHello(message, reader)
            8 -> {
                reader.forEachExtension { type, data ->
                    if (type == 16) {
                        data.u16()
                        alpnAccepted = data.bytes(data.u8()).decodeToString() == "doq"
                    }
                }
                if (!alpnAccepted) throw NetException(Res.string.dns_doq_unsupported)
            }
            11 -> {
                reader.skip(reader.u8())
                val end = reader.u24() + reader.pos
                val factory = CertificateFactory.getInstance("X.509")
                certificates = buildList {
                    while (reader.pos < end) {
                        add(factory.generateCertificate(reader.bytes(reader.u24()).inputStream()) as X509Certificate)
                        reader.skip(reader.u16())
                    }
                }
                if (certificates.isEmpty()) throw SSLPeerUnverifiedException(host)
                trustManager().checkServerTrusted(certificates.toTypedArray(), "UNKNOWN")
                if (!matchesHost(certificates.first())) throw SSLPeerUnverifiedException(host)
            }
            15 -> {
                val verifier = signatureFor(reader.u16())
                val signature = reader.bytes(reader.u16())
                verifier.initVerify(certificates.firstOrNull()?.publicKey ?: throw SSLPeerUnverifiedException(host))
                verifier.update(ByteArray(64) { 0x20 })
                verifier.update("TLS 1.3, server CertificateVerify".encodeToByteArray())
                verifier.update(0.toByte())
                verifier.update(sha256(transcript.toByteArray()))
                if (!verifier.verify(signature)) throw SSLHandshakeException("CertificateVerify")
                serverVerified = true
            }
            20 -> {
                if (!serverVerified || !alpnAccepted) throw SSLHandshakeException("Finished before CertificateVerify")
                val expected = hmac(expandLabel(serverHandshakeSecret, "finished", EMPTY, 32), sha256(transcript.toByteArray()))
                if (!MessageDigest.isEqual(expected, reader.bytes(32))) throw SSLHandshakeException("Finished")
                transcript.write(message)
                val hash = sha256(transcript.toByteArray())
                spaces[APPLICATION].send = QuicKeys(expandLabel(masterSecret, "c ap traffic", hash, 32))
                spaces[APPLICATION].receive = QuicKeys(expandLabel(masterSecret, "s ap traffic", hash, 32))
                clientFinished = handshakeMessage(20, hmac(expandLabel(clientHandshakeSecret, "finished", EMPTY, 32), hash))
                return
            }
            else -> throw SSLHandshakeException("handshake message ${message[0]}")
        }
        if (message[0].toInt() != 2) transcript.write(message)
    }

    private fun onServerHello(message: ByteArray, reader: QuicReader) {
        reader.skip(2)
        if (reader.bytes(32).contentEquals(HELLO_RETRY_RANDOM)) throw SSLHandshakeException("HelloRetryRequest")
        reader.skip(reader.u8())
        if (reader.u16() != 0x1301) throw SSLHandshakeException("cipher suite")
        reader.skip(1)
        var serverShare = EMPTY
        reader.forEachExtension { type, data ->
            if (type == 51 && data.u16() == 0x0017) serverShare = data.bytes(data.u16())
        }
        if (serverShare.size != 65 || serverShare[0].toInt() != 4) throw SSLHandshakeException("key share")
        val params = (keyPair.public as ECPublicKey).params
        val point = ECPoint(BigInteger(1, serverShare.copyOfRange(1, 33)), BigInteger(1, serverShare.copyOfRange(33, 65)))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)), true)
            generateSecret()
        }
        transcript.write(message)
        val helloHash = sha256(transcript.toByteArray())
        val early = extract(ByteArray(32), ByteArray(32))
        val handshakeSecret = extract(expandLabel(early, "derived", sha256(EMPTY), 32), shared)
        clientHandshakeSecret = expandLabel(handshakeSecret, "c hs traffic", helloHash, 32)
        serverHandshakeSecret = expandLabel(handshakeSecret, "s hs traffic", helloHash, 32)
        masterSecret = extract(expandLabel(handshakeSecret, "derived", sha256(EMPTY), 32), ByteArray(32))
        spaces[HANDSHAKE].send = QuicKeys(clientHandshakeSecret)
        spaces[HANDSHAKE].receive = QuicKeys(serverHandshakeSecret)
    }

    private fun clientHello(): ByteArray {
        val publicKey = (keyPair.public as ECPublicKey).w
        val body = bytes {
            u16(0x0303)
            write(randomBytes(32))
            u8(0)
            prefixed(2) { u16(0x1301) }
            u8(1)
            u8(0)
            prefixed(2) {
                if (!isIpLiteral(host)) {
                    extension(0) {
                        prefixed(2) {
                            u8(0)
                            prefixed(2) { write(host.encodeToByteArray()) }
                        }
                    }
                }
                extension(10) { prefixed(2) { u16(0x0017) } }
                extension(13) { prefixed(2) { SIGNATURE_SCHEMES.forEach { u16(it) } } }
                extension(16) { prefixed(2) { prefixed(1) { write("doq".encodeToByteArray()) } } }
                extension(43) { prefixed(1) { u16(0x0304) } }
                extension(51) {
                    prefixed(2) {
                        u16(0x0017)
                        prefixed(2) {
                            u8(4)
                            write(publicKey.affineX.toFixedBytes(32))
                            write(publicKey.affineY.toFixedBytes(32))
                        }
                    }
                }
                extension(0x39) {
                    parameter(0x01, 10_000)
                    parameter(0x04, 1L shl 20)
                    parameter(0x05, 1L shl 18)
                    varint(0x0f)
                    varint(sourceId.size.toLong())
                    write(sourceId)
                }
            }
        }
        return handshakeMessage(1, body)
    }

    private fun matchesHost(certificate: X509Certificate): Boolean {
        val literal = isIpLiteral(host)
        val address = if (literal) InetAddress.getByName(host).address else null
        val name = host.lowercase().trimEnd('.')
        return certificate.subjectAlternativeNames.orEmpty().any { entry ->
            val value = (entry[1] as? String)?.lowercase() ?: return@any false
            when (entry[0]) {
                2 -> !literal && (value == name || value.startsWith("*.") && name.indexOf('.') > 0 && name.substringAfter('.') == value.substring(2))
                7 -> literal && InetAddress.getByName(value).address.contentEquals(address)
                else -> false
            }
        }
    }

    private fun randomBytes(count: Int) = ByteArray(count).also(random::nextBytes)
}

private class QuicSpace {
    var send: QuicKeys? = null
    var receive: QuicKeys? = null
    var nextNumber = 0L
    var largest = -1L
    val received = TreeSet<Long>()
    var ackPending = false
    val crypto = QuicReassembly()
}

private class QuicKeys(secret: ByteArray) {
    private val key = SecretKeySpec(expandLabel(secret, "quic key", EMPTY, 16), "AES")
    private val iv = expandLabel(secret, "quic iv", EMPTY, 12)
    private val headerCipher = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(expandLabel(secret, "quic hp", EMPTY, 16), "AES"))
    }

    fun seal(number: Long, header: ByteArray, payload: ByteArray) = aead(Cipher.ENCRYPT_MODE, number, header, payload)

    fun open(number: Long, header: ByteArray, sealed: ByteArray) = aead(Cipher.DECRYPT_MODE, number, header, sealed)

    fun mask(packet: ByteArray, sampleOffset: Int): ByteArray = headerCipher.doFinal(packet, sampleOffset, 16)

    private fun aead(mode: Int, number: Long, header: ByteArray, input: ByteArray): ByteArray {
        val nonce = iv.copyOf()
        for (i in 0 until 8) nonce[11 - i] = (nonce[11 - i].toInt() xor (number ushr (8 * i)).toInt()).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(mode, key, GCMParameterSpec(128, nonce))
            updateAAD(header)
            doFinal(input)
        }
    }
}

private class QuicReassembly {
    private val pending = TreeMap<Long, ByteArray>()
    private var consumed = 0L
    private var data = EMPTY

    fun add(offset: Long, chunk: ByteArray) {
        if ((pending[offset]?.size ?: -1) < chunk.size) pending[offset] = chunk
        while (true) {
            val entry = pending.firstEntry() ?: return
            val end = consumed + data.size
            if (entry.key > end) return
            pending.remove(entry.key)
            val skip = (end - entry.key).toInt()
            if (skip < entry.value.size) data += entry.value.copyOfRange(skip, entry.value.size)
        }
    }

    fun handshakeMessage(): ByteArray? {
        if (data.size < 4) return null
        val length = QuicReader(data, 1).u24()
        if (data.size < 4 + length) return null
        val message = data.copyOf(4 + length)
        data = data.copyOfRange(message.size, data.size)
        consumed += message.size
        return message
    }

    fun dnsMessage(): ByteArray? {
        if (data.size < 2) return null
        val length = QuicReader(data).u16()
        return if (data.size < 2 + length) null else data.copyOfRange(2, 2 + length)
    }
}

private class QuicReader(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
    val remaining: Int get() = end - pos

    fun u8(): Int {
        if (pos >= end) throw IndexOutOfBoundsException()
        return data[pos++].toInt() and 0xFF
    }

    fun u16() = (u8() shl 8) or u8()

    fun u24() = (u8() shl 16) or u16()

    fun u32() = (u16().toLong() shl 16) or u16().toLong()

    fun varint(): Long {
        val first = u8()
        var value = (first and 0x3F).toLong()
        repeat((1 shl (first shr 6)) - 1) { value = (value shl 8) or u8().toLong() }
        return value
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0 || count > remaining) throw IndexOutOfBoundsException()
        return data.copyOfRange(pos, pos + count).also { pos += count }
    }

    fun skip(count: Int) {
        if (count < 0 || count > remaining) throw IndexOutOfBoundsException()
        pos += count
    }

    fun forEachExtension(block: (type: Int, data: QuicReader) -> Unit) {
        val length = u16()
        val stop = pos + length
        while (pos < stop) {
            val type = u16()
            val size = u16()
            if (pos + size > stop) throw IndexOutOfBoundsException()
            block(type, QuicReader(data, pos, pos + size))
            pos += size
        }
    }
}

private fun signatureFor(scheme: Int): Signature = when (scheme) {
    0x0403 -> Signature.getInstance("SHA256withECDSA")
    0x0503 -> Signature.getInstance("SHA384withECDSA")
    0x0603 -> Signature.getInstance("SHA512withECDSA")
    0x0804 -> rsaPss("SHA-256", MGF1ParameterSpec.SHA256, 32)
    0x0805 -> rsaPss("SHA-384", MGF1ParameterSpec.SHA384, 48)
    0x0806 -> rsaPss("SHA-512", MGF1ParameterSpec.SHA512, 64)
    else -> throw SSLHandshakeException("signature scheme $scheme")
}

// Android names PSS "SHA256withRSA/PSS", the JDK only knows "RSASSA-PSS" with explicit parameters
private fun rsaPss(digest: String, mgf: MGF1ParameterSpec, saltLength: Int): Signature =
    runCatching { Signature.getInstance("${digest.replace("-", "")}withRSA/PSS") }.getOrElse {
        Signature.getInstance("RSASSA-PSS").apply { setParameter(PSSParameterSpec(digest, "MGF1", mgf, saltLength, 1)) }
    }

private fun trustManager(): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

private fun decodePacketNumber(largest: Long, truncated: Long, bits: Int): Long {
    val expected = largest + 1
    val window = 1L shl bits
    val candidate = (expected and (window - 1).inv()) or truncated
    return when {
        candidate <= expected - window / 2 && candidate < (1L shl 62) - window -> candidate + window
        candidate > expected + window / 2 && candidate >= window -> candidate - window
        else -> candidate
    }
}

private fun isIpLiteral(host: String) = host.contains(':') || IPV4_LITERAL.matches(host)

private fun DatagramSocket.sendBytes(bytes: ByteArray) = send(DatagramPacket(bytes, bytes.size))

private fun handshakeMessage(type: Int, body: ByteArray) = bytes {
    u8(type)
    u24(body.size)
    write(body)
}

private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256"))
    parts.forEach { update(it) }
    doFinal()
}

private fun extract(salt: ByteArray, material: ByteArray) = hmac(salt, material)

private fun expandLabel(secret: ByteArray, label: String, context: ByteArray, length: Int): ByteArray {
    val info = bytes {
        u16(length)
        prefixed(1) { write("tls13 $label".encodeToByteArray()) }
        prefixed(1) { write(context) }
    }
    val out = ByteArrayOutputStream()
    var block = EMPTY
    var counter = 1
    while (out.size() < length) {
        block = hmac(secret, block, info, byteArrayOf(counter++.toByte()))
        out.write(block)
    }
    return out.toByteArray().copyOf(length)
}

private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun BigInteger.toFixedBytes(size: Int): ByteArray {
    val raw = toByteArray()
    return if (raw.size >= size) raw.copyOfRange(raw.size - size, raw.size) else ByteArray(size - raw.size) + raw
}

private fun bytes(block: ByteArrayOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply(block).toByteArray()

private fun ByteArrayOutputStream.u8(value: Int) = write(value)

private fun ByteArrayOutputStream.u16(value: Int) {
    write(value ushr 8)
    write(value)
}

private fun ByteArrayOutputStream.u24(value: Int) {
    write(value ushr 16)
    u16(value)
}

private fun ByteArrayOutputStream.u32(value: Long) {
    u16((value ushr 16).toInt())
    u16(value.toInt())
}

private fun ByteArrayOutputStream.varint(value: Long) {
    when {
        value < 0x40 -> u8(value.toInt())
        value < 0x4000 -> u16(0x4000 or value.toInt())
        value < 0x40000000 -> u32(0x80000000L or value)
        else -> {
            u32(0xC0000000L or (value ushr 32))
            u32(value)
        }
    }
}

private fun ByteArrayOutputStream.prefixed(lengthBytes: Int, block: ByteArrayOutputStream.() -> Unit) {
    val body = bytes(block)
    when (lengthBytes) {
        1 -> u8(body.size)
        2 -> u16(body.size)
        else -> u24(body.size)
    }
    write(body)
}

private fun ByteArrayOutputStream.extension(type: Int, block: ByteArrayOutputStream.() -> Unit) {
    u16(type)
    prefixed(2, block)
}

private fun ByteArrayOutputStream.parameter(id: Int, value: Long) {
    val encoded = bytes { varint(value) }
    varint(id.toLong())
    varint(encoded.size.toLong())
    write(encoded)
}
