package com.vasmarfas.card.core

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

enum class PlatformKind(val title: StringResource) {
    ANDROID(Res.string.android),
    IOS(Res.string.ios),
    DESKTOP(Res.string.desktop),
    WEB(Res.string.web);

    companion object {
        val all: Set<PlatformKind> = entries.toSet()
        val native: Set<PlatformKind> = setOf(ANDROID, IOS, DESKTOP)
        val jvm: Set<PlatformKind> = setOf(ANDROID, DESKTOP)
        val mobile: Set<PlatformKind> = setOf(ANDROID, IOS)
        val mobileAndWeb: Set<PlatformKind> = setOf(ANDROID, IOS, WEB)
    }
}

data class PlatformInfo(
    val kind: PlatformKind,
    val osName: String,
    val osVersion: String,
    val deviceModel: String,
    val locale: String,
    val extra: List<Pair<String, String>> = emptyList(),
)

expect fun platformInfo(): PlatformInfo

// the browser reports one even when more exist, see parallelWorkers
expect fun cpuCoreCount(): Int

// Kotlin/Wasm has no worker pool behind Dispatchers.Default, extra coroutines only take turns on one thread
val parallelWorkers: Int get() = if (currentPlatform == PlatformKind.WEB) 1 else cpuCoreCount()

expect fun siteHost(): String?

expect fun openUrl(url: String)

expect fun setWindowTitle(title: String)

expect fun listenForFindShortcut(open: () -> Boolean)

@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

expect fun supportsDynamicColor(): Boolean

expect fun currentEpochMillis(): Long

expect fun secureRandomBytes(count: Int): ByteArray

val currentPlatform: PlatformKind by lazy { platformInfo().kind }
