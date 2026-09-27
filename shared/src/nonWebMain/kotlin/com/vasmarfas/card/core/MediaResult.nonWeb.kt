package com.vasmarfas.card.core

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.write
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

actual class MediaResult(val file: PlatformFile) {
    actual val size: Long get() = file.size()
}

actual suspend fun MediaResult.save(fileName: String): Boolean {
    val target = FileKit.openFileSaver(
        suggestedName = fileName.substringBeforeLast('.', fileName),
        defaultExtension = fileName.substringAfterLast('.', "").ifEmpty { null },
    ) ?: return false
    target.write(file)
    return true
}

actual suspend fun MediaResult.readBytes(): ByteArray = file.readBytes()

actual fun MediaResult.asFile(name: String): PlatformFile = file

actual fun MediaResult.discard() {
    runCatching { SystemFileSystem.delete(Path(file.path), mustExist = false) }
}

private var sweptStale = false

internal fun tempPath(extension: String): String {
    val now = currentEpochMillis()
    if (!sweptStale) {
        sweptStale = true
        runCatching { SystemFileSystem.list(SystemTemporaryDirectory) }.getOrDefault(emptyList())
            .filter { it.name.startsWith("media-") && (it.name.substringAfter('-').substringBefore('-').toLongOrNull() ?: now) < now - 86_400_000 }
            .forEach { runCatching { SystemFileSystem.delete(it, mustExist = false) } }
    }
    val name = "media-$now-${Random.nextInt(100_000, 999_999)}.$extension"
    return Path(SystemTemporaryDirectory, name).toString()
}
