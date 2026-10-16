package com.vasmarfas.card.core

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vasmarfas.card.tools.documents.OcrText
import com.vasmarfas.card.tools.media.VideoText
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.GradientPaint
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

// runs the real Tesseract natives of the host, the same build the desktop app ships
@OptIn(InternalComposeUiApi::class)
class OcrEngineTest {
    init {
        registerSkikoComposeImplementation()
    }

    private val font = Font.createFont(Font.TRUETYPE_FONT, File("src/commonMain/composeResources/files/fonts/MobitoolSans-Regular.ttf"))

    private fun render(lines: List<String>, size: Float, width: Int = 1100): BufferedImage {
        val lineHeight = (size * 1.6f).toInt()
        val image = BufferedImage(width, lineHeight * lines.size + 80, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = font.deriveFont(size)
        lines.forEachIndexed { index, line -> g.drawString(line, 40, 40 + lineHeight * (index + 1)) }
        g.dispose()
        return image
    }

    @Test
    fun readsRussianAndEnglishLines() = runBlocking {
        val image = render(listOf("Распознавание текста работает", "Hello from the OCR engine 2026"), 36f).toComposeImageBitmap()
        val page = Ocr.recognize(image, OcrLanguage.entries.toSet())
        val text = VideoText.normalize(OcrText.text(page, joinLines = false))
        assertTrue("распознавание текста работает" in text, text)
        assertTrue("hello from the ocr engine 2026" in text, text)
        assertTrue(page.confidence > 70, "confidence ${page.confidence}")
        val first = page.lines.first()
        assertTrue(first.left in 20..80 && first.top in 20..100, "box ${first.left} ${first.top}")
    }

    // a caption like the ones burned into films: white letters in a black outline over a coloured picture
    @Test
    fun outlinedCaptionsReadBetterAsBrightText() = runBlocking {
        val frame = BufferedImage(1280, 240, BufferedImage.TYPE_INT_RGB)
        val g = frame.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(60, 90, 150), 0f, 240f, Color(170, 80, 110))
        g.fillRect(0, 0, frame.width, frame.height)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = font.deriveFont(Font.BOLD, 44f)
        val caption = "Hello, this is the second caption"
        val x = (frame.width - g.fontMetrics.stringWidth(caption)) / 2
        g.color = Color.BLACK
        for (dx in -2..2) for (dy in -2..2) g.drawString(caption, x + dx, 140 + dy)
        g.color = Color.WHITE
        g.drawString(caption, x, 140)
        g.dispose()
        val image = frame.toComposeImageBitmap()
        val page = Ocr.recognize(imageBitmapOf(VideoText.brightText(image.pixels()), image.width, image.height), OcrLanguage.entries.toSet())
        val text = VideoText.normalize(OcrText.text(page, joinLines = true))
        assertTrue("hello this is the second caption" in text, text)
        assertTrue(page.confidence > 85, "confidence ${page.confidence}")
    }

    @Test
    fun smallScreenshotsAreScaledUpBeforeReading() = runBlocking {
        val image = render(listOf("Мелкий текст на скриншоте"), 14f, width = 420).toComposeImageBitmap()
        val page = Ocr.recognize(image, setOf(OcrLanguage.RUSSIAN))
        val text = VideoText.normalize(OcrText.text(page, joinLines = true))
        assertTrue("мелкий текст на скриншоте" in text, text)
        assertTrue(page.lines.all { it.right <= image.width + 2 }, "boxes are in the original image")
    }
}
