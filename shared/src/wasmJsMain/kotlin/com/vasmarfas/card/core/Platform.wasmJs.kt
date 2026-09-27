package com.vasmarfas.card.core

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window

private fun jsRandomBytes(count: Int): JsAny? = js("crypto.getRandomValues(new Uint8Array(count))")

private fun jsByteAt(array: JsAny?, index: Int): Int = js("array[index]")

private fun jsHideSplash(): Unit = js("{ var s = document.getElementById('splash'); if (s) { s.classList.add('hidden'); setTimeout(function(){ s.style.display = 'none'; }, 400); } }")

private fun jsDateNow(): Double = js("Date.now()")

private fun jsHardwareConcurrency(): Int = js("(navigator.hardwareConcurrency || 0)")

private fun jsDeviceMemory(): Double = js("(navigator.deviceMemory || 0)")

private fun jsScreenSize(): String = js("(screen.width + 'x' + screen.height + ' @' + Math.round((window.devicePixelRatio || 1) * 100) / 100 + 'x')")

private fun jsLanguage(): String = js("(navigator.language || 'en')")

private fun jsPlatform(): String = js("(navigator.platform || '')")

fun hideWebSplash() = jsHideSplash()

private fun detectOs(ua: String): Pair<String, String> {
    val lower = ua.lowercase()
    return when {
        lower.contains("android") -> "Android" to (Regex("Android ([0-9.]+)").find(ua)?.groupValues?.get(1) ?: "")
        lower.contains("iphone") || lower.contains("ipad") -> "iOS" to (Regex("OS ([0-9_]+)").find(ua)?.groupValues?.get(1)?.replace('_', '.') ?: "")
        lower.contains("windows") -> "Windows" to (Regex("Windows NT ([0-9.]+)").find(ua)?.groupValues?.get(1) ?: "")
        lower.contains("mac os") -> "macOS" to (Regex("Mac OS X ([0-9_]+)").find(ua)?.groupValues?.get(1)?.replace('_', '.') ?: "")
        lower.contains("linux") -> "Linux" to ""
        else -> "Web" to ""
    }
}

private fun detectBrowser(ua: String): String {
    val patterns = listOf(
        "Edg/" to "Edge", "OPR/" to "Opera", "YaBrowser/" to "Yandex Browser", "Firefox/" to "Firefox",
        "Chrome/" to "Chrome", "Safari/" to "Safari",
    )
    for ((token, name) in patterns) {
        val idx = ua.indexOf(token)
        if (idx >= 0) {
            val version = ua.substring(idx + token.length).takeWhile { it.isDigit() || it == '.' }
            return "$name $version".trim()
        }
    }
    return "Unknown"
}

actual fun platformInfo(): PlatformInfo {
    val ua = window.navigator.userAgent
    val (os, version) = detectOs(ua)
    return PlatformInfo(
        kind = PlatformKind.WEB,
        osName = os,
        osVersion = version,
        deviceModel = detectBrowser(ua),
        locale = jsLanguage(),
        extra = listOf(
            "User agent" to ua,
            "Platform" to jsPlatform(),
            "Screen" to jsScreenSize(),
            "CPU threads" to jsHardwareConcurrency().toString(),
            "Device memory" to jsDeviceMemory().let { if (it > 0) "$it GB" else "n/a" },
        ),
    )
}

actual fun openUrl(url: String) {
    window.open(url, "_blank", "noopener")
}

actual fun setWindowTitle(title: String) {
    document.title = title
}

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

actual fun supportsDynamicColor(): Boolean = false

actual fun currentEpochMillis(): Long = jsDateNow().toLong()

actual fun secureRandomBytes(count: Int): ByteArray {
    val array = jsRandomBytes(count)
    return ByteArray(count) { jsByteAt(array, it).toByte() }
}

private class LocalStorageStore : KeyValueStore {
    private val written = mutableMapOf<String, String>()

    override fun get(key: String): String? = written[key] ?: runCatching { localStorage.getItem(key) }.getOrNull()

    override fun put(key: String, value: String) {
        written[key] = value
        runCatching { localStorage.setItem(key, value) }
    }

    override fun remove(key: String) {
        written.remove(key)
        runCatching { localStorage.removeItem(key) }
    }
}

actual fun createKeyValueStore(): KeyValueStore = LocalStorageStore()

actual fun siteHost(): String? = window.location.hostname.ifBlank { null }

private fun jsListenFind(open: () -> Boolean): Unit = js("""{
    window.addEventListener('keydown', function (e) {
        if ((e.ctrlKey || e.metaKey) && !e.altKey && e.code === 'KeyF' && open()) e.preventDefault();
    });
}""")

private var findShortcut: () -> Boolean = { false }
private var findListening = false

actual fun listenForFindShortcut(open: () -> Boolean) {
    findShortcut = open
    if (findListening) return
    findListening = true
    jsListenFind { findShortcut() }
}
