package com.vasmarfas.card.core

import com.vasmarfas.card.resources.*
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import java.security.Security
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import org.bouncycastle.jce.provider.BouncyCastleProvider

private const val SSH_CONNECT_TIMEOUT_MS = 10_000

// Android registers a cut-down provider named BC and sshj looks its provider up by that name
private fun installBouncyCastle() {
    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) is BouncyCastleProvider) return
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.addProvider(BouncyCastleProvider())
}

// disconnecting writes to the socket, which Android forbids on the main thread
private fun SSHClient.closeInBackground() {
    thread { runCatching { close() } }
}

private class SshjShell(private val client: SSHClient, private val shell: Session.Shell) : SshShell {
    private val input = shell.inputStream
    private val output = shell.outputStream
    private val writes = Mutex()

    override suspend fun read(buffer: ByteArray): Int = withContext(Dispatchers.IO) { input.read(buffer) }

    // keys pressed in a row have to reach the server in that order
    override suspend fun write(bytes: ByteArray) = writes.withLock {
        withContext(Dispatchers.IO) {
            output.write(bytes)
            output.flush()
        }
    }

    override suspend fun resize(columns: Int, rows: Int) = withContext(Dispatchers.IO) {
        shell.changeWindowDimensions(columns, rows, 0, 0)
    }

    override fun close() = client.closeInBackground()
}

private fun loadKey(client: SSHClient, key: SshAuth.PrivateKey): KeyProvider {
    val passphrase = object : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = key.passphrase.toCharArray()

        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }
    return try {
        // the file is parsed on first use, reading the type surfaces a bad file or passphrase here
        client.loadKeys(key.pem, null, passphrase).also { it.type }
    } catch (e: Exception) {
        throw NetException(Res.string.ssh_error_key)
    }
}

private fun sshFailure(e: Exception): Exception = when (e) {
    is UserAuthException -> NetException(Res.string.ssh_error_auth)
    is NetException, is CancellationException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException, is UnknownHostException -> e
    else -> NetException(Res.string.ssh_error_failed)
}

actual suspend fun openSshShell(
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    columns: Int,
    rows: Int,
    trust: (SshHostKey) -> Boolean,
): SshShell {
    val client = withContext(Dispatchers.IO) {
        installBouncyCastle()
        SSHClient(DefaultConfig())
    }
    return try {
        runInterruptible(Dispatchers.IO) {
            client.connectTimeout = SSH_CONNECT_TIMEOUT_MS
            client.addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean =
                    trust(SshHostKey(KeyType.fromKey(key).toString(), Buffer.PlainBuffer().putPublicKey(key).compactData))

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
            client.connect(host, port)
            when (auth) {
                is SshAuth.Password -> client.authPassword(user, auth.password)
                is SshAuth.PrivateKey -> client.authPublickey(user, loadKey(client, auth))
            }
            val session = client.startSession()
            session.allocatePTY("xterm-256color", columns, rows, 0, 0, emptyMap())
            SshjShell(client, session.startShell())
        }
    } catch (e: Exception) {
        client.closeInBackground()
        throw sshFailure(e)
    }
}
