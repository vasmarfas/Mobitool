package com.vasmarfas.card.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.vasmarfas.card.core.platformDynamicColorScheme
import com.vasmarfas.card.core.setSystemBarsDark
import com.vasmarfas.card.data.AppSettings
import com.vasmarfas.card.data.ThemeMode
import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MobitoolTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val followSystem = settings.themeMode == ThemeMode.SYSTEM
    LaunchedEffect(dark, followSystem) { setSystemBarsDark(dark, followSystem) }
    val dynamic = if (settings.dynamicColor) platformDynamicColorScheme(dark) else null
    val generated = remember(settings.seedColor, dark) { appColorScheme(settings.seedColor, dark) }
    CompositionLocalProvider(LocalStatusColors provides statusColors(dark)) {
        MaterialExpressiveTheme(
            colorScheme = dynamic ?: generated,
            motionScheme = MotionScheme.expressive(),
            typography = appTypography,
            content = content,
        )
    }
}

private val BrandCream = Color(0xFFF0EAE1)

fun appColorScheme(seed: Long, dark: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = Color(seed),
    isDark = dark,
    neutral = BrandCream.takeIf { seed == AppSettings.DEFAULT_SEED },
    style = PaletteStyle.Vibrant,
)

val seedPresets: List<Pair<Long, StringResource>> = listOf(
    AppSettings.DEFAULT_SEED to Res.string.teal,
    0xFF3F51B5 to Res.string.indigo,
    0xFF6750A4 to Res.string.violet,
    0xFF006E1C to Res.string.green,
    0xFFB3261E to Res.string.red,
    0xFF9C4400 to Res.string.orange,
    0xFF00629E to Res.string.blue,
    0xFF7B4E7F to Res.string.plum,
)

private fun TextStyle.emphasised(tracking: Float) = copy(
    fontWeight = FontWeight.Medium,
    letterSpacing = tracking.em,
    fontFeatureSettings = "tnum",
)

internal val appTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.emphasised(-0.02f),
        displayMedium = base.displayMedium.emphasised(-0.02f),
        displaySmall = base.displaySmall.emphasised(-0.02f),
        headlineLarge = base.headlineLarge.emphasised(-0.01f),
        headlineMedium = base.headlineMedium.emphasised(-0.01f),
        headlineSmall = base.headlineSmall.emphasised(-0.01f),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    )
}
