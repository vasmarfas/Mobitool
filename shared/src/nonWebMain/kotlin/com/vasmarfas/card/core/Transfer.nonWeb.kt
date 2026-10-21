package com.vasmarfas.card.core

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileWithDefaultApplication
import io.github.vinceglb.filekit.source
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

actual suspend fun PlatformFile.openSource(): RawSource = source()

actual fun openReceived(file: PlatformFile) {
    runCatching { FileKit.openFileWithDefaultApplication(file) }
}

internal fun receivedFile(dir: Path, name: String): IncomingFile {
    SystemFileSystem.createDirectories(dir)
    val safe = safeFileName(name)
    val base = safe.substringBeforeLast('.', safe)
    val extension = safe.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var path = Path(dir, safe)
    var copy = 1
    while (SystemFileSystem.exists(path)) path = Path(dir, "$base (${copy++})$extension")
    return IncomingFile(SystemFileSystem.sink(path), { PlatformFile(path) }, { runCatching { SystemFileSystem.delete(path) } })
}
