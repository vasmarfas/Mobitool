package com.vasmarfas.card.core

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.download
import io.github.vinceglb.filekit.readBytes
import io.ktor.client.HttpClient
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray

// shortcut: the page holds a received file in memory until it hands it to the browser, a stream to disk needs the File System Access API
actual fun incomingFile(name: String): IncomingFile {
    val buffer = Buffer()
    return IncomingFile(buffer, { FileKit.download(buffer.readByteArray(), safeFileName(name)); null }, { buffer.clear() })
}

actual fun incomingFolder(): String? = null

actual fun deviceName(): String = platformInfo().deviceModel

actual fun lanTlsClient(fingerprint: String, onCertificate: (String) -> Unit): HttpClient = Net.client

actual suspend fun PlatformFile.openSource(): RawSource = Buffer().apply { write(readBytes()) }

actual suspend fun shareFiles(files: List<PlatformFile>): Boolean = false

actual fun openReceived(file: PlatformFile) = Unit

actual fun listenTcp(port: Int): TcpServer = throw UnsupportedOperationException("a page cannot listen")

actual suspend fun connectTcp(host: String, port: Int, timeoutMs: Int): TcpStream = throw UnsupportedOperationException("a page has no raw sockets")

actual fun joinMulticast(group: String, port: Int): MulticastGroup? = null

actual suspend fun shareOverBluetooth(files: List<PlatformFile>): Boolean = false

actual suspend fun startHotspot(): Hotspot? = null

actual fun shareOverNfc(url: String?): Boolean = false

actual fun openUdp(port: Int): UdpSocket = throw UnsupportedOperationException("a page has no UDP")
