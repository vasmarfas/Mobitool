package com.vasmarfas.card.core

sealed interface SshAuth {
    class Password(val password: String) : SshAuth
    class PrivateKey(val pem: String, val passphrase: String) : SshAuth
}

class SshHostKey(val type: String, val blob: ByteArray)

// close() wakes a coroutine blocked in read
interface SshShell {
    suspend fun read(buffer: ByteArray): Int
    suspend fun write(bytes: ByteArray)
    suspend fun resize(columns: Int, rows: Int)
    fun close()
}

// trust runs on the transport thread during key exchange, false fails the connection
expect suspend fun openSshShell(
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    columns: Int,
    rows: Int,
    trust: (SshHostKey) -> Boolean,
): SshShell
