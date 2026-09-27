package com.vasmarfas.card.core

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.posix.arc4random_buf

actual fun platformInfo(): PlatformInfo {
    val device = UIDevice.currentDevice
    return PlatformInfo(
        kind = PlatformKind.IOS,
        osName = device.systemName,
        osVersion = device.systemVersion,
        deviceModel = device.model,
        locale = NSLocale.currentLocale.localeIdentifier.replace('_', '-'),
        extra = listOf(
            "Name" to device.name,
            "CPU cores" to NSProcessInfo.processInfo.processorCount.toString(),
            "Memory" to formatBytes(NSProcessInfo.processInfo.physicalMemory.toLong()),
        ),
    )
}

actual fun openUrl(url: String) {
    val nsUrl = NSURL.URLWithString(url) ?: return
    UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
}

actual fun setWindowTitle(title: String) = Unit

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

actual fun supportsDynamicColor(): Boolean = false

actual fun currentEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

// arc4random_buf is the platform CSPRNG and cannot fail
@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    if (count > 0) {
        bytes.usePinned { pinned -> arc4random_buf(pinned.addressOf(0), count.convert()) }
    }
    return bytes
}

private class UserDefaultsStore : KeyValueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun put(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

actual fun createKeyValueStore(): KeyValueStore = UserDefaultsStore()

actual fun siteHost(): String? = null
