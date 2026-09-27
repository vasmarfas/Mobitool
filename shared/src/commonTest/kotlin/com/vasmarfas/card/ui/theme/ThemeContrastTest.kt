package com.vasmarfas.card.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

private fun Color.luminance(): Double {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

private fun contrast(a: Color, b: Color): Double {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private class Pair(val name: String, val foreground: ColorScheme.() -> Color, val background: ColorScheme.() -> Color)

private val bodyPairs = listOf(
    Pair("onSurface/surface", { onSurface }, { surface }),
    Pair("onSurfaceVariant/surface", { onSurfaceVariant }, { surface }),
    Pair("onSurfaceVariant/surfaceContainer", { onSurfaceVariant }, { surfaceContainer }),
    Pair("onSurfaceVariant/surfaceContainerHigh", { onSurfaceVariant }, { surfaceContainerHigh }),
    Pair("onPrimaryContainer/primaryContainer", { onPrimaryContainer }, { primaryContainer }),
    Pair("onSecondaryContainer/secondaryContainer", { onSecondaryContainer }, { secondaryContainer }),
    Pair("onTertiaryContainer/tertiaryContainer", { onTertiaryContainer }, { tertiaryContainer }),
    Pair("onErrorContainer/errorContainer", { onErrorContainer }, { errorContainer }),
    Pair("onPrimary/primary", { onPrimary }, { primary }),
    Pair("primary/surface", { primary }, { surface }),
    Pair("error/surface", { error }, { surface }),
    Pair("onBackground/background", { onBackground }, { background }),
)

class ThemeContrastTest {
    private fun schemes(): List<Triple<String, Boolean, ColorScheme>> =
        seedPresets.flatMap { (seed, name) ->
            listOf(false, true).map { dark ->
                Triple(name.key, dark, appColorScheme(seed, dark))
            }
        }

    @Test
    fun statusColoursStayReadableOnEveryPreset() {
        val failures = mutableListOf<String>()
        schemes().forEach { (name, dark, scheme) ->
            val status = statusColors(dark)
            listOf("good" to status.good, "warn" to status.warn, "bad" to status.bad, "info" to status.info).forEach { (role, color) ->
                val ratio = contrast(color, scheme.surface)
                if (ratio < 4.5) {
                    failures += "$name ${if (dark) "dark" else "light"} $role = ${(ratio * 100).toInt() / 100.0}"
                }
            }
            status.series.forEachIndexed { index, color ->
                val ratio = contrast(color, scheme.surface)
                if (ratio < 3.0) {
                    failures += "$name ${if (dark) "dark" else "light"} series[$index] = ${(ratio * 100).toInt() / 100.0}"
                }
            }
        }
        assertTrue(failures.isEmpty(), "status colours below the threshold:\n" + failures.joinToString("\n"))
    }

    @Test
    fun everyPresetKeepsBodyTextReadable() {
        val failures = mutableListOf<String>()
        schemes().forEach { (name, dark, scheme) ->
            bodyPairs.forEach { pair ->
                val ratio = contrast(pair.foreground(scheme), pair.background(scheme))
                if (ratio < 4.5) {
                    failures += "$name ${if (dark) "dark" else "light"} ${pair.name} = ${(ratio * 100).toInt() / 100.0}"
                }
            }
        }
        assertTrue(failures.isEmpty(), "WCAG AA (4.5:1) not met:\n" + failures.joinToString("\n"))
    }
}

