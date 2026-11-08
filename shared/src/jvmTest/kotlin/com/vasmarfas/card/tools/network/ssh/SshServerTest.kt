package com.vasmarfas.card.tools.network.ssh

import com.vasmarfas.card.core.SshAuth
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

// logs in to real servers with every key and checks a command's output, see CODEMAP.md for the OpenSSH matrix in WSL
class SshServerTest {
    @Test
    fun everyServerRunsACommand() = runBlocking {
        val spec = System.getProperty("ssh.test") ?: return@runBlocking
        val (target, keyFiles) = spec.split(';')
        val user = target.substringBefore('@')
        val host = target.substringAfter('@').substringBeforeLast(':')
        val (first, last) = target.substringAfterLast(':').split('-').map { it.toInt() }
        val failures = mutableListOf<String>()
        for (port in first..last) {
            for (keyFile in keyFiles.split(',')) {
                val name = "$port ${File(keyFile).name}"
                val output = runCatching { session(host, port, user, File(keyFile).readText()) }.getOrElse { "${it::class.simpleName} ${it.message}" }
                if (DONE !in output || "19999" !in output) failures += "$name: ${output.takeLast(200)}"
                println("$name: ${if (DONE in output) "ok" else "failed"}")
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // twenty thousand lines of output are enough for several rekeys on a server with a small RekeyLimit
    private suspend fun session(host: String, port: Int, user: String, key: String): String = withTimeout(60.seconds) {
        val shell = openSshShell(host, port, user, SshAuth.PrivateKey(key, ""), 80, 24) { true }
        shell.write("seq 1 20000; echo mobitool-\$((40 + 2)); exit\r".encodeToByteArray())
        val output = StringBuilder()
        val buffer = ByteArray(8192)
        while (true) {
            val count = shell.read(buffer)
            if (count < 0) break
            output.append(buffer.decodeToString(0, count))
        }
        shell.close()
        output.toString()
    }

    // the terminal echoes the command, so the marker is something only the shell computes
    private companion object {
        const val DONE = "mobitool-42"
    }
}
