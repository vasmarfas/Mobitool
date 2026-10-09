package com.vasmarfas.card.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail

// the HTML copy of the site in index.html sets its text in the app's type scale, written out as classes in styles.css
class StaticPageTypographyTest {
    private val css = File("../webApp/src/wasmJsMain/resources/styles.css").readText()

    private val styles: Map<String, Typography.() -> TextStyle> = mapOf(
        ".pre-name" to { displayLarge },
        ".t-hm" to { headlineMedium },
        ".t-hs" to { headlineSmall },
        ".t-tl" to { titleLarge },
        ".t-tm" to { titleMedium },
        ".t-ts" to { titleSmall },
        ".t-bl" to { bodyLarge },
        ".t-bm" to { bodyMedium },
        ".t-bs" to { bodySmall },
        ".t-ll" to { labelLarge },
        ".t-lm" to { labelMedium },
        ".t-ls" to { labelSmall },
    )

    private fun rule(selector: String): Map<String, String> {
        val body = css.substringAfter("\n$selector {", "").substringBefore("}")
        return Regex("""([a-z-]+):\s*([^;]+);""").findAll(body).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private fun same(declared: String?, unit: TextUnit): Boolean {
        val number = declared?.let { Regex("""^(-?[\d.]+)(px|em)?$""").find(it) } ?: return false
        val value = number.groupValues[1].toFloat()
        return value == 0f && unit.value == 0f ||
            number.groupValues[2] == (if (unit.isEm) "em" else "px") && abs(value - unit.value) < 0.001f
    }

    @Test
    fun cssClassesMatchTheTypeScale() {
        val problems = mutableListOf<String>()
        styles.forEach { (selector, style) ->
            val declared = rule(selector)
            val text = appTypography.style()
            listOf("font-size" to text.fontSize, "line-height" to text.lineHeight, "letter-spacing" to text.letterSpacing)
                .filterNot { (property, unit) -> same(declared[property], unit) }
                .forEach { (property, unit) -> problems += "$selector $property: ${declared[property]}, the app has $unit" }
            if (text.fontFeatureSettings == "tnum" && declared["font-feature-settings"] != "\"tnum\"") {
                problems += "$selector has no tabular figures, the app sets tnum"
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }
}
