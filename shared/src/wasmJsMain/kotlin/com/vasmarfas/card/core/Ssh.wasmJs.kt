package com.vasmarfas.card.core

actual suspend fun openSshShell(
    host: String,
    port: Int,
    user: String,
    auth: SshAuth,
    columns: Int,
    rows: Int,
    trust: (SshHostKey) -> Boolean,
): SshShell = throw UnsupportedOperationException("unsupported")
