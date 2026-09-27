package com.vasmarfas.card.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.security.SecureRandom
import java.util.Locale
import androidx.core.net.toUri

@SuppressLint("StaticFieldLeak")
object AppContextHolder {
    lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }
}

actual fun platformInfo(): PlatformInfo = PlatformInfo(
    kind = PlatformKind.ANDROID,
    osName = "Android",
    osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    locale = Locale.getDefault().toLanguageTag(),
    extra = listOf(
        "Brand" to Build.BRAND,
        "Device" to Build.DEVICE,
        "Board" to Build.BOARD,
        "Hardware" to Build.HARDWARE,
        "ABIs" to Build.SUPPORTED_ABIS.joinToString(),
        "Security patch" to Build.VERSION.SECURITY_PATCH,
        "Build" to Build.DISPLAY,
    ),
)

actual fun openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (url.startsWith(AppConfig.SITE_COM) || url.startsWith(AppConfig.SITE_RU)) {
        intent.selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
    }
    runCatching { AppContextHolder.context.startActivity(intent) }
}

actual fun setWindowTitle(title: String) = Unit

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val context = LocalContext.current
    return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

actual fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

actual fun currentEpochMillis(): Long = System.currentTimeMillis()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }

actual fun siteHost(): String? = null
