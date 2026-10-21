@file:OptIn(ExperimentalForeignApi::class)

package com.vasmarfas.card.core

import com.vasmarfas.card.tools.developer.Sha256
import com.vasmarfas.card.tools.developer.toHex
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.shareFile
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.io.files.Path
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.serverTrust
import platform.Security.SecCertificateCopyData
import platform.Security.SecTrustCopyCertificateChain
import platform.Security.SecTrustRef
import platform.UIKit.UIDevice

// Documents shows up in Files under the app's name once UIFileSharingEnabled is set
actual fun incomingFile(name: String): IncomingFile = receivedFile(Path(FileKit.filesDir.path), name)

actual fun incomingFolder(): String? = AppConfig.APP_NAME

actual fun deviceName(): String = UIDevice.currentDevice.name

actual suspend fun shareFiles(files: List<PlatformFile>): Boolean = runCatching { FileKit.shareFile(files) }.isSuccess

private fun leafSha256(trust: SecTrustRef): String? {
    val chain = SecTrustCopyCertificateChain(trust) ?: return null
    try {
        if (CFArrayGetCount(chain) < 1) return null
        val data = SecCertificateCopyData(CFArrayGetValueAtIndex(chain, 0)?.reinterpret()) ?: return null
        try {
            val bytes = CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).convert()) ?: return null
            return Sha256.digest(bytes).toHex()
        } finally {
            CFRelease(data)
        }
    } finally {
        CFRelease(chain)
    }
}

actual fun lanTlsClient(fingerprint: String, onCertificate: (String) -> Unit): HttpClient = HttpClient(Darwin) {
    expectSuccess = false
    engine {
        configureSession { timeoutIntervalForRequest = 3600.0 }
        handleChallenge { _, _, challenge, completionHandler ->
            val trust = challenge.protectionSpace.serverTrust
            when {
                challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust || trust == null ->
                    completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                leafSha256(trust)?.also(onCertificate).equals(fingerprint, ignoreCase = true) ->
                    completionHandler(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
                else -> completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }
}

actual suspend fun shareOverBluetooth(files: List<PlatformFile>): Boolean = false

actual suspend fun startHotspot(): Hotspot? = null

actual fun shareOverNfc(url: String?): Boolean = false
