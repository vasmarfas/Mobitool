package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.core.SshAuth
import com.vasmarfas.card.core.SshHostKey
import com.vasmarfas.card.core.SshShell
import com.vasmarfas.card.core.connectTcp
import com.vasmarfas.card.resources.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val CONNECT_TIMEOUT_MS = 10_000
private val HANDSHAKE_TIMEOUT = 30.seconds
private const val LOCAL_WINDOW = 2 * 1024 * 1024
private const val MAX_DATA = 32 * 1024

// trust runs during key exchange, false fails the connection
suspend fun openSshShell(
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    columns: Int,
    rows: Int,
    trust: (SshHostKey) -> Boolean,
): SshShell = withContext(Dispatchers.Default) {
    val key = (auth as? SshAuth.PrivateKey)?.let { parsePrivateKey(it.pem, it.passphrase) }
    val session = SshSession(SshTransport(connectTcp(host, port, CONNECT_TIMEOUT_MS), trust))
    try {
        withTimeout(HANDSHAKE_TIMEOUT) { session.start(user, auth, key, columns, rows) }
        session
    } catch (e: Exception) {
        session.close()
        throw when (e) {
            is TimeoutCancellationException -> NetException(Res.string.net_error_timeout)
            is CancellationException, is NetException -> e
            else -> sshFailure()
        }
    }
}

// RFC 4252 authentication and one RFC 4254 session channel with a terminal
private class SshSession(private val transport: SshTransport) : SshShell {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val writes = Mutex()
    private val windowLock = Mutex()
    private val windowOpened = Channel<Unit>(Channel.CONFLATED)
    private val banner = StringBuilder()
    private var serverSignatures: List<String>? = null
    private var remote = 0
    private var remoteWindow = 0L
    private var remoteMaxPacket = MAX_DATA
    private var consumed = 0
    private var closed = false
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    suspend fun start(user: String, auth: SshAuth, key: SshPrivateKey?, columns: Int, rows: Int) {
        transport.start()
        val accepted = try {
            authenticate(user, auth, key)
        } catch (e: SshDisconnect) {
            false
        }
        if (!accepted) throw NetException(Res.string.ssh_error_auth)
        if (banner.isNotEmpty()) incoming.trySend(banner.toString().replace("\r\n", "\n").replace("\n", "\r\n").encodeToByteArray())
        openChannel(columns, rows)
        scope.launch { pump() }
    }

    private fun request(user: String, method: String) =
        SshWriter().byte(50).string(user).string("ssh-connection").string(method)

    private suspend fun authenticate(user: String, auth: SshAuth, key: SshPrivateKey?): Boolean {
        transport.send(SshWriter().byte(5).string("ssh-userauth").bytes())
        await(6)
        transport.send(request(user, "none").bytes())
        val reply = await(51, 52)
        if (reply.byte() == 52) return true
        val methods = reply.names()
        return when {
            key != null -> "publickey" in methods && publicKey(user, key)
            auth is SshAuth.Password && "password" in methods -> password(user, auth.password)
            auth is SshAuth.Password && "keyboard-interactive" in methods -> keyboardInteractive(user, auth.password)
            else -> false
        }
    }

    private suspend fun password(user: String, password: String): Boolean {
        transport.send(request(user, "password").boolean(false).string(password).bytes())
        return await(51, 52, 60).byte() == 52
    }

    // PAM servers ask for the password this way, a second round of prompts means it was wrong
    private suspend fun keyboardInteractive(user: String, password: String): Boolean {
        transport.send(request(user, "keyboard-interactive").string("").string("").bytes())
        var answered = false
        while (true) {
            val reply = await(51, 52, 60)
            when (reply.byte()) {
                52 -> return true
                51 -> return false
            }
            reply.text()
            reply.text()
            reply.text()
            val count = reply.uint32()
            if (count > 0 && answered) return false
            val response = SshWriter().byte(61).uint32(count)
            repeat(count) {
                reply.text()
                reply.boolean()
                response.string(password)
            }
            transport.send(response.bytes())
            if (count > 0) answered = true
        }
    }

    private suspend fun publicKey(user: String, key: SshPrivateKey): Boolean {
        for (algorithm in key.algorithms(serverSignatures)) {
            val body = request(user, "publickey").boolean(true).string(algorithm).string(key.publicBlob)
            val signed = SshWriter().string(transport.sessionId).raw(body.bytes()).bytes()
            transport.send(body.string(key.sign(algorithm, signed)).bytes())
            val reply = await(51, 52)
            if (reply.byte() == 52) return true
            if ("publickey" !in reply.names()) return false
        }
        return false
    }

    private suspend fun openChannel(columns: Int, rows: Int) {
        transport.send(SshWriter().byte(90).string("session").uint32(0).uint32(LOCAL_WINDOW).uint32(MAX_DATA).bytes())
        val confirmation = await(91, 92)
        if (confirmation.byte() != 91) throw sshFailure()
        confirmation.uint32()
        remote = confirmation.uint32()
        remoteWindow = confirmation.uint32().toLong() and 0xFFFFFFFFL
        remoteMaxPacket = minOf(confirmation.uint32().toLong() and 0xFFFFFFFFL, MAX_DATA.toLong()).toInt()
        // a server without terminals still runs the shell, so a refused pty is not an error
        transport.send(
            SshWriter().byte(98).uint32(remote).string("pty-req").boolean(true)
                .string("xterm-256color").uint32(columns).uint32(rows).uint32(0).uint32(0).string(byteArrayOf(0)).bytes(),
        )
        await(99, 100)
        transport.send(SshWriter().byte(98).uint32(remote).string("shell").boolean(true).bytes())
        if (await(99, 100).byte() != 99) throw sshFailure()
    }

    // the next message of one of the types with the reader at its type byte, anything that comes first is handled
    private suspend fun await(vararg types: Int): SshReader {
        while (true) {
            val payload = transport.receive()
            if ((payload[0].toInt() and 0xFF) in types) return SshReader(payload)
            if (!handle(payload)) throw sshFailure()
        }
    }

    private suspend fun handle(payload: ByteArray): Boolean {
        val reader = SshReader(payload)
        when (reader.byte()) {
            7 -> repeat(reader.uint32()) {
                val name = reader.text()
                val value = reader.text()
                if (name == "server-sig-algs") serverSignatures = value.split(',')
            }
            20 -> transport.rekey(payload)
            53 -> banner.append(reader.text())
            80 -> {
                reader.text()
                if (reader.boolean()) transport.send(byteArrayOf(82))
            }
            90 -> {
                reader.text()
                val sender = reader.uint32()
                transport.send(SshWriter().byte(92).uint32(sender).uint32(1).string("").string("").bytes())
            }
            93 -> {
                reader.uint32()
                val more = reader.uint32().toLong() and 0xFFFFFFFFL
                windowLock.withLock { remoteWindow = minOf(remoteWindow + more, 0xFFFFFFFFL) }
                windowOpened.trySend(Unit)
            }
            94 -> {
                reader.uint32()
                deliver(reader.string())
            }
            95 -> {
                reader.uint32()
                reader.uint32()
                deliver(reader.string())
            }
            96, 99, 100 -> {}
            97 -> {
                if (!closed) transport.send(SshWriter().byte(97).uint32(remote).bytes())
                closed = true
            }
            98 -> {
                reader.uint32()
                reader.text()
                if (reader.boolean()) transport.send(SshWriter().byte(100).uint32(remote).bytes())
            }
            else -> return false
        }
        return true
    }

    private suspend fun deliver(data: ByteArray) {
        incoming.trySend(data)
        consumed += data.size
        if (consumed >= LOCAL_WINDOW / 2) {
            transport.send(SshWriter().byte(93).uint32(remote).uint32(consumed).bytes())
            consumed = 0
        }
    }

    private suspend fun pump() {
        try {
            while (!closed) handle(transport.receive())
        } catch (_: Exception) {
        } finally {
            incoming.close()
            windowOpened.close()
            transport.close()
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        val chunk = pending ?: incoming.receiveCatching().getOrNull() ?: return -1
        val count = minOf(buffer.size, chunk.size - pendingOffset)
        chunk.copyInto(buffer, 0, pendingOffset, pendingOffset + count)
        pendingOffset += count
        if (pendingOffset == chunk.size) {
            pending = null
            pendingOffset = 0
        } else {
            pending = chunk
        }
        return count
    }

    // keys pressed in a row have to reach the server in that order
    override suspend fun write(bytes: ByteArray) = writes.withLock {
        var offset = 0
        while (offset < bytes.size) {
            val size = reserve(bytes.size - offset)
            transport.send(SshWriter().byte(94).uint32(remote).string(bytes.copyOfRange(offset, offset + size)).bytes())
            offset += size
        }
    }

    private suspend fun reserve(wanted: Int): Int {
        while (true) {
            windowLock.withLock {
                if (remoteWindow > 0) {
                    val size = minOf(wanted.toLong(), remoteWindow, remoteMaxPacket.toLong()).toInt()
                    remoteWindow -= size
                    return size
                }
            }
            windowOpened.receive()
        }
    }

    override suspend fun resize(columns: Int, rows: Int) {
        transport.send(
            SshWriter().byte(98).uint32(remote).string("window-change").boolean(false)
                .uint32(columns).uint32(rows).uint32(0).uint32(0).bytes(),
        )
    }

    override fun close() {
        scope.cancel()
        incoming.close()
        transport.close()
    }
}
