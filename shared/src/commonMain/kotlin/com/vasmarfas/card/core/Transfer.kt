package com.vasmarfas.card.core

import io.github.vinceglb.filekit.PlatformFile
import io.ktor.client.HttpClient
import kotlinx.io.RawSink
import kotlinx.io.RawSource

class IncomingFile(val sink: RawSink, val keep: suspend () -> PlatformFile?, val drop: () -> Unit)

expect fun incomingFile(name: String): IncomingFile

expect fun deviceName(): String

// onCertificate hears every certificate offered, so a scan learns a fingerprint from a refused handshake
expect fun lanTlsClient(fingerprint: String, onCertificate: (String) -> Unit = {}): HttpClient

expect suspend fun PlatformFile.openSource(): RawSource

expect suspend fun shareFiles(files: List<PlatformFile>): Boolean

expect fun incomingFolder(): String?

expect fun openReceived(file: PlatformFile)

fun safeFileName(name: String): String {
    val base = name.replace('\\', '/').substringAfterLast('/').filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimEnd('.')
    return base.takeIf { it.isNotEmpty() && it != "." && it != ".." }?.take(200) ?: "file"
}

expect suspend fun shareOverBluetooth(files: List<PlatformFile>): Boolean

class Hotspot(val ssid: String, val password: String, val stop: () -> Unit)

expect suspend fun startHotspot(): Hotspot?

expect fun shareOverNfc(url: String?): Boolean
