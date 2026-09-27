package com.vasmarfas.card.core

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import java.awt.Desktop
import java.net.URI
import java.security.SecureRandom
import java.util.Locale
import java.util.prefs.Preferences

actual fun platformInfo(): PlatformInfo = PlatformInfo(
    kind = PlatformKind.DESKTOP,
    osName = System.getProperty("os.name") ?: "Desktop",
    osVersion = System.getProperty("os.version") ?: "",
    deviceModel = System.getProperty("os.arch") ?: "",
    locale = Locale.getDefault().toLanguageTag(),
    extra = listOf(
        "Java" to (System.getProperty("java.version") ?: ""),
        "JVM" to (System.getProperty("java.vm.name") ?: ""),
        "User" to (System.getProperty("user.name") ?: ""),
        "CPU cores" to Runtime.getRuntime().availableProcessors().toString(),
        "Max heap" to formatBytes(Runtime.getRuntime().maxMemory()),
    ),
)

actual fun openUrl(url: String) {
    val opened = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else false
    }.getOrDefault(false)
    if (opened) return
    val os = (System.getProperty("os.name") ?: "").lowercase()
    val command = when {
        os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        os.contains("mac") -> listOf("open", url)
        else -> listOf("xdg-open", url)
    }
    runCatching { ProcessBuilder(command).start() }
}

val windowTitle = mutableStateOf(AppConfig.APP_NAME)

actual fun setWindowTitle(title: String) {
    windowTitle.value = title
}

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

actual fun supportsDynamicColor(): Boolean = false

actual fun currentEpochMillis(): Long = System.currentTimeMillis()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

private class JvmPrefsStore : KeyValueStore {
    private val prefs = Preferences.userRoot().node("com/vasmarfas/card")

    override fun get(key: String): String? = prefs.get(key, null)

    override fun put(key: String, value: String) {
        if (value.length > Preferences.MAX_VALUE_LENGTH) {
            val chunks = value.chunked(Preferences.MAX_VALUE_LENGTH)
            prefs.putInt("$key.chunks", chunks.size)
            chunks.forEachIndexed { i, chunk -> prefs.put("$key.$i", chunk) }
            prefs.remove(key)
        } else {
            prefs.remove("$key.chunks")
            prefs.put(key, value)
        }
        prefs.flush()
    }

    override fun remove(key: String) {
        prefs.remove(key)
        val chunks = prefs.getInt("$key.chunks", 0)
        repeat(chunks) { prefs.remove("$key.$it") }
        prefs.remove("$key.chunks")
        prefs.flush()
    }

    private fun readChunked(key: String): String? {
        val chunks = prefs.getInt("$key.chunks", 0)
        if (chunks == 0) return null
        return buildString { repeat(chunks) { append(prefs.get("$key.$it", "")) } }
    }

    fun getAny(key: String): String? = prefs.get(key, null) ?: readChunked(key)
}

actual fun createKeyValueStore(): KeyValueStore {
    val store = JvmPrefsStore()
    return object : KeyValueStore {
        override fun get(key: String): String? = store.getAny(key)
        override fun put(key: String, value: String) = store.put(key, value)
        override fun remove(key: String) = store.remove(key)
    }
}

actual fun siteHost(): String? = null
