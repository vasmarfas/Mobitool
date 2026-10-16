package com.vasmarfas.card.core

import androidx.compose.ui.graphics.ImageBitmap
import com.vasmarfas.card.resources.*
import org.jetbrains.compose.resources.StringResource
import kotlin.math.roundToInt

enum class OcrLanguage(val tesseract: String, val apple: String, val title: StringResource) {
    RUSSIAN("rus", "ru-RU", Res.string.ocr_language_russian),
    ENGLISH("eng", "en-US", Res.string.ocr_language_english),
}

class OcrLine(val text: String, val confidence: Float, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

class OcrPage(val lines: List<OcrLine>, val width: Int, val height: Int) {
    val confidence: Float get() = if (lines.isEmpty()) 0f else lines.sumOf { it.confidence.toDouble() * it.text.length }.toFloat() / lines.sumOf { it.text.length }.coerceAtLeast(1)
}

class OcrException(message: String, val reason: StringResource = Res.string.ocr_failed) : Exception(message)

expect object OcrEngine {
    suspend fun recognize(image: ImageBitmap, languages: Set<OcrLanguage>): OcrPage
}

object Ocr {
    private const val MIN_SIDE = 1400
    private const val MAX_SIDE = 3600

    fun scaleFor(width: Int, height: Int): Double {
        val side = maxOf(width, height)
        return when {
            side < MIN_SIDE -> (MIN_SIDE.toDouble() / side).coerceAtMost(3.0)
            side > MAX_SIDE -> MAX_SIDE.toDouble() / side
            else -> 1.0
        }
    }

    suspend fun recognize(image: ImageBitmap, languages: Set<OcrLanguage>): OcrPage {
        val scale = scaleFor(image.width, image.height)
        val input = if (scale == 1.0) image else image.scaled((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
        val page = OcrEngine.recognize(input, languages.ifEmpty { OcrLanguage.entries.toSet() })
        if (scale == 1.0) return page
        return OcrPage(
            page.lines.map { line ->
                OcrLine(
                    line.text, line.confidence,
                    (line.left / scale).roundToInt(), (line.top / scale).roundToInt(),
                    (line.right / scale).roundToInt(), (line.bottom / scale).roundToInt(),
                )
            },
            image.width, image.height,
        )
    }

    fun gray(pixels: IntArray): ByteArray = ByteArray(pixels.size) { i ->
        val p = pixels[i]
        val y = (299 * (p shr 16 and 0xFF) + 587 * (p shr 8 and 0xFF) + 114 * (p and 0xFF)) / 1000
        val alpha = p ushr 24
        (if (alpha == 255) y else 255 - (255 - y) * alpha / 255).toByte()
    }
}
