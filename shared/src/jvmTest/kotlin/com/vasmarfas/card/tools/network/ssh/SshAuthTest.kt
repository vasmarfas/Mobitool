package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.core.SshAuth
import com.vasmarfas.card.core.SshShell
import com.vasmarfas.card.resources.*
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.keyboard.UserAuthKeyboardInteractiveFactory
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider

// password and keyboard-interactive logins against Apache MINA SSHD, which also differs from OpenSSH in
// the algorithms it picks and in how it frames channel data
class SshAuthTest {
    private var server: SshServer? = null

    @AfterTest
    fun stop() {
        server?.stop(true)
    }

    // greets, answers one line and exits
    private class EchoShell : Command {
        private lateinit var input: InputStream
        private lateinit var output: OutputStream
        private lateinit var exit: ExitCallback

        override fun setInputStream(stream: InputStream) {
            input = stream
        }

        override fun setOutputStream(stream: OutputStream) {
            output = stream
        }

        override fun setErrorStream(stream: OutputStream) {}

        override fun setExitCallback(callback: ExitCallback) {
            exit = callback
        }

        override fun start(channel: ChannelSession, env: Environment) {
            thread {
                output.write("hello\r\n".toByteArray())
                output.flush()
                val line = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0 || c == '\r'.code) break
                    line.append(c.toChar())
                }
                output.write("got $line\r\n".toByteArray())
                output.flush()
                exit.onExit(0)
            }
        }

        override fun destroy(channel: ChannelSession) {}
    }

    private fun start(keyboardInteractive: Boolean): Int {
        val ssh = SshServer.setUpDefaultServer()
        ssh.port = 0
        ssh.keyPairProvider = SimpleGeneratorHostKeyProvider()
        ssh.setPasswordAuthenticator { user, password, _ -> user == "tester" && password == "test-password" }
        ssh.userAuthFactories = listOf(if (keyboardInteractive) UserAuthKeyboardInteractiveFactory.INSTANCE else UserAuthPasswordFactory.INSTANCE)
        ssh.setShellFactory { EchoShell() }
        ssh.start()
        server = ssh
        return ssh.port
    }

    private suspend fun SshShell.transcript(): String {
        write("ping\r".encodeToByteArray())
        val out = StringBuilder()
        val buffer = ByteArray(1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            out.append(buffer.decodeToString(0, count))
        }
        close()
        return out.toString()
    }

    @Test
    fun password() = runBlocking {
        val port = start(keyboardInteractive = false)
        val shell = openSshShell("127.0.0.1", port, "tester", SshAuth.Password("test-password"), 80, 24) { true }
        assertEquals("hello\r\ngot ping\r\n", shell.transcript())
    }

    @Test
    fun keyboardInteractive() = runBlocking {
        val port = start(keyboardInteractive = true)
        val shell = openSshShell("127.0.0.1", port, "tester", SshAuth.Password("test-password"), 80, 24) { true }
        assertEquals("hello\r\ngot ping\r\n", shell.transcript())
    }

    @Test
    fun wrongPasswordIsAnAuthError() = runBlocking {
        for (keyboardInteractive in listOf(false, true)) {
            val port = start(keyboardInteractive)
            val error = assertFailsWith<NetException> {
                openSshShell("127.0.0.1", port, "tester", SshAuth.Password("wrong"), 80, 24) { true }
            }
            assertEquals(Res.string.ssh_error_auth, error.reason)
            server?.stop(true)
        }
    }

    @Test
    fun rejectedHostKeyStopsBeforeLogin() = runBlocking {
        val port = start(keyboardInteractive = false)
        var asked = 0
        assertFailsWith<NetException> {
            openSshShell("127.0.0.1", port, "tester", SshAuth.Password("test-password"), 80, 24) { key ->
                asked++
                assertTrue(key.type.startsWith("ecdsa-sha2-") || key.type == "ssh-rsa", key.type)
                false
            }
        }
        assertEquals(1, asked)
    }
}
