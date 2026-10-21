package com.vasmarfas.card.core

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.downloadsDir
import io.github.vinceglb.filekit.path
import java.net.InetAddress
import kotlinx.io.files.Path

private val receivedDir get() = Path(FileKit.downloadsDir.path, "Mobitool")

actual fun incomingFile(name: String): IncomingFile = receivedFile(receivedDir, name)

actual fun incomingFolder(): String? = receivedDir.toString()

actual fun deviceName(): String =
    System.getenv("COMPUTERNAME") ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: System.getProperty("os.name") ?: "Computer"

actual suspend fun shareFiles(files: List<PlatformFile>): Boolean = false

actual fun joinMulticast(group: String, port: Int): MulticastGroup? = runCatching { JavaMulticast(group, port) {} }.getOrNull()

actual suspend fun shareOverBluetooth(files: List<PlatformFile>): Boolean = false

actual suspend fun startHotspot(): Hotspot? = null

actual fun shareOverNfc(url: String?): Boolean = false
