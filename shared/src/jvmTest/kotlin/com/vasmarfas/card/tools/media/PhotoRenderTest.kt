package com.vasmarfas.card.tools.media

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.registerSkikoComposeImplementation
import com.vasmarfas.card.core.decodeRawImage
import com.vasmarfas.card.core.imageBitmapOf
import com.vasmarfas.card.core.pixels
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class PhotoRenderTest {
    init {
        registerSkikoComposeImplementation()
    }

    private fun flat(w: Int, h: Int, grey: Int) = imageBitmapOf(IntArray(w * h) { (255 shl 24) or (grey shl 16) or (grey shl 8) or grey }, w, h)

    private fun marked(w: Int, h: Int) = imageBitmapOf(
        IntArray(w * h) { i -> if (i % w < w / 4 && i / w < h / 4) 0xFFFF0000.toInt() else 0xFF808080.toInt() },
        w,
        h,
    )

    private fun ImageBitmap.at(x: Int, y: Int): Int = pixels()[y * width + x]

    private fun red(p: Int) = (p shr 16) and 0xFF

    private fun green(p: Int) = (p shr 8) and 0xFF

    @Test
    fun brightnessAndExposureMoveTheLevelsTheSameOnEveryBackend() {
        val bright = PhotoEditor.render(flat(8, 8, 100), PhotoEdit(brightness = 0.5f))
        assertTrue(abs(green(bright.at(4, 4)) - 140) <= 1, "brightness: ${green(bright.at(4, 4))}")
        val exposed = PhotoEditor.render(flat(8, 8, 60), PhotoEdit(exposure = 1f))
        assertTrue(abs(green(exposed.at(4, 4)) - 120) <= 1, "exposure: ${green(exposed.at(4, 4))}")
    }

    @Test
    fun cropAndTurnGiveTheExpectedGeometry() {
        val source = marked(400, 300)
        val cut = PhotoEditor.render(source, PhotoEdit(crop = Rect(0.25f, 0f, 0.75f, 1f)))
        assertEquals(200 to 300, cut.width to cut.height)
        val turned = PhotoEditor.render(source, PhotoEdit().turned(clockwise = true))
        assertEquals(300 to 400, turned.width to turned.height)
        assertEquals(255, red(turned.at(290, 10)), "the red corner goes to the top right")
        assertEquals(128, red(turned.at(10, 10)))
    }

    @Test
    fun flipIsLeftRightOnScreenEvenOnATurnedPhoto() {
        val source = marked(400, 300)
        val sideways = PhotoEdit().turned(clockwise = true)
        val shown = PhotoEditor.render(source, sideways)
        val flipped = PhotoEditor.render(source, sideways.flipped())
        assertEquals(shown.width to shown.height, flipped.width to flipped.height)
        for ((x, y) in listOf(10 to 10, 290 to 10, 10 to 390, 290 to 390, 150 to 200)) {
            assertEquals(red(shown.at(shown.width - 1 - x, y)), red(flipped.at(x, y)), "at $x, $y")
        }
    }

    @Test
    fun straighteningLeavesNoEmptyCorners() {
        val out = PhotoEditor.render(flat(400, 300, 90), PhotoEdit(straighten = 12f))
        assertEquals(400 to 300, out.width to out.height)
        for ((x, y) in listOf(0 to 0, 399 to 0, 0 to 299, 399 to 299)) {
            assertEquals(255, out.at(x, y) ushr 24, "corner $x, $y")
        }
    }

    @Test
    fun vignetteDarkensTheCornersOnly() {
        val out = PhotoEditor.render(flat(300, 200, 200), PhotoEdit(vignette = 1f))
        assertEquals(200, green(out.at(150, 100)))
        assertTrue(green(out.at(0, 0)) < 60, "corner ${green(out.at(0, 0))}")
    }

    @Test
    fun everyTargetDecodesBack() = runBlocking {
        val source = marked(64, 48)
        for (target in ImageTarget.entries) {
            val bytes = encodeImage(source, target, icons = listOf(16, 48))
            val decoded = assertNotNull(decodeRawImage(bytes), "$target does not decode")
            if (target == ImageTarget.ICO) {
                assertEquals(48, decoded.bitmap.width)
            } else {
                assertEquals(64 to 48, decoded.bitmap.width to decoded.bitmap.height, "$target size")
            }
        }
    }

    @Test
    fun formatsBuiltInArraysAreCappedOnTheLongSide() {
        val wide = flat(4000, 30, 90)
        assertEquals(3072, wide.limitedFor(ImageTarget.GIF, 0).width)
        assertEquals(3072, wide.limitedFor(ImageTarget.BMP, 0).width)
        assertEquals(3072, wide.limitedFor(ImageTarget.PNG, 256).width)
        assertEquals(4000, wide.limitedFor(ImageTarget.PNG, 0).width)
        assertEquals(4000, wide.limitedFor(ImageTarget.JPEG, 0).width)
    }

    @Test
    fun compressionFitsTheLimitAndShrinksWhenQualityIsNotEnough() {
        val noisy = imageBitmapOf(IntArray(1200 * 900) { (0xFF shl 24) or ((it * 2654435761L).toInt() and 0xFFFFFF) }, 1200, 900)
        for (target in listOf(ImageTarget.JPEG, ImageTarget.WEBP)) {
            val loose = compressImage(noisy, target, 2_000_000)
            assertTrue(loose.bytes.size <= 2_000_000 && loose.width == 1200, "$target at 2 MB: ${loose.bytes.size} B, ${loose.width} px")
            val tight = compressImage(noisy, target, 60_000)
            assertTrue(tight.bytes.size <= 60_000, "$target at 60 kB: ${tight.bytes.size} B")
            assertTrue(tight.width < 1200, "$target should have shrunk")
        }
    }

    @Test
    fun previewDecodeFitsTheAskedSide() = runBlocking {
        val preview = assertNotNull(decodeImage(encodeImage(marked(800, 600), ImageTarget.PNG), 200))
        assertEquals(200 to 150, preview.width to preview.height)
        assertEquals(255, red(preview.at(10, 10)))
    }

    @Test
    fun tiffOpensThroughTheCommonDecoder() = runBlocking {
        val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0xFF0000) }
        val tiff = ByteArrayOutputStream().also { ImageIO.write(image, "tiff", it) }.toByteArray()
        val decoded = assertNotNull(decodeImage(tiff))
        assertEquals(40 to 30, decoded.width to decoded.height)
        assertEquals(255, red(decoded.at(0, 0)))
    }
}
