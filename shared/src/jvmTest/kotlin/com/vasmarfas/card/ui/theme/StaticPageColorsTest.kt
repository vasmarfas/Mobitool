package com.vasmarfas.card.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.vasmarfas.card.data.AppSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

// the HTML copy of the site in index.html paints with the app's default scheme, written out in styles.css
class StaticPageColorsTest {
    private val css = File("../webApp/src/wasmJsMain/resources/styles.css").readText()

    private val roles: Map<String, ColorScheme.() -> Color> = mapOf(
        "background" to { background },
        "on-background" to { onBackground },
        "surface" to { surface },
        "on-surface" to { onSurface },
        "on-surface-variant" to { onSurfaceVariant },
        "surface-container" to { surfaceContainer },
        "surface-container-high" to { surfaceContainerHigh },
        "primary" to { primary },
        "on-primary" to { onPrimary },
        "primary-container" to { primaryContainer },
        "on-primary-container" to { onPrimaryContainer },
        "secondary" to { secondary },
        "secondary-container" to { secondaryContainer },
        "on-secondary-container" to { onSecondaryContainer },
        "outline" to { outline },
        "outline-variant" to { outlineVariant },
    )

    private fun block(selector: String): Map<String, String> {
        val body = css.substringAfter("$selector {", "").substringBefore("}")
        return Regex("""--md-([a-z-]+):\s*(#[0-9a-f]{6});""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun hex(color: Color) = "#%06x".format(color.toArgb() and 0xFFFFFF)

    @Test
    fun cssVariablesMatchTheDefaultScheme() {
        val problems = mutableListOf<String>()
        listOf(":root" to false, """:root[data-theme="dark"]""" to true).forEach { (selector, dark) ->
            val declared = block(selector)
            assertTrue(declared.isNotEmpty(), "no --md- variables under $selector in styles.css")
            val scheme = appColorScheme(AppSettings.DEFAULT_SEED, dark)
            declared.forEach { (name, value) ->
                val expected = roles[name]?.invoke(scheme)?.let(::hex)
                when {
                    expected == null -> problems += "$selector --md-$name is not listed in this test"
                    expected != value -> problems += "$selector --md-$name: $value, the app has $expected"
                }
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
