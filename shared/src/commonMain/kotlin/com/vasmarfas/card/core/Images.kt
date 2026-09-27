package com.vasmarfas.card.core

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

enum class EncodedFormat { JPEG, PNG, WEBP }

class RawImage(val bitmap: ImageBitmap, val oriented: Boolean)

expect suspend fun decodeRawImage(bytes: ByteArray, maxSide: Int = 0): RawImage?

expect fun ImageBitmap.encode(format: EncodedFormat, quality: Int): ByteArray

// non-premultiplied ARGB, the way readPixels fills it
expect fun imageBitmapOf(pixels: IntArray, width: Int, height: Int): ImageBitmap

fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }

private fun smooth() = Paint().apply { filterQuality = FilterQuality.High }

// mirror first, then clockwise quarter turns: the order EXIF orientations use
fun ImageBitmap.transformed(quarterTurns: Int, mirror: Boolean): ImageBitmap {
    val turns = ((quarterTurns % 4) + 4) % 4
    if (turns == 0 && !mirror) return this
    val w = width.toFloat()
    val h = height.toFloat()
    val out = if (turns % 2 == 0) ImageBitmap(width, height) else ImageBitmap(height, width)
    Canvas(out).apply {
        when (turns) {
            1 -> {
                translate(h, 0f)
                rotate(90f)
            }
            2 -> {
                translate(w, h)
                rotate(180f)
            }
            3 -> {
                translate(0f, w)
                rotate(270f)
            }
        }
        if (mirror) {
            translate(w, 0f)
            scale(-1f, 1f)
        }
        drawImage(this@transformed, Offset.Zero, smooth())
    }
    return out
}

fun ImageBitmap.oriented(exifOrientation: Int): ImageBitmap = when (exifOrientation) {
    2 -> transformed(0, mirror = true)
    3 -> transformed(2, mirror = false)
    4 -> transformed(2, mirror = true)
    5 -> transformed(3, mirror = true)
    6 -> transformed(1, mirror = false)
    7 -> transformed(1, mirror = true)
    8 -> transformed(3, mirror = false)
    else -> this
}

// halve step by step first, a single bilinear pass over a 4x reduction skips most of the pixels
fun ImageBitmap.scaled(targetWidth: Int, targetHeight: Int): ImageBitmap {
    val tw = targetWidth.coerceAtLeast(1)
    val th = targetHeight.coerceAtLeast(1)
    var current = this
    while (current.width / 2 >= tw && current.height / 2 >= th) {
        current = current.redrawn(current.width / 2, current.height / 2)
    }
    return if (current.width == tw && current.height == th) current else current.redrawn(tw, th)
}

private fun ImageBitmap.redrawn(w: Int, h: Int): ImageBitmap {
    val out = ImageBitmap(w, h)
    Canvas(out).drawImageRect(this, IntOffset.Zero, IntSize(width, height), IntOffset.Zero, IntSize(w, h), smooth())
    return out
}

fun ImageBitmap.limitedTo(longSide: Int): ImageBitmap {
    val side = maxOf(width, height)
    if (longSide <= 0 || longSide >= side) return this
    val scale = longSide.toDouble() / side
    return scaled((width * scale).roundToInt(), (height * scale).roundToInt())
}

fun ImageBitmap.cropped(left: Int, top: Int, cropWidth: Int, cropHeight: Int): ImageBitmap {
    val x = left.coerceIn(0, width - 1)
    val y = top.coerceIn(0, height - 1)
    val w = cropWidth.coerceIn(1, width - x)
    val h = cropHeight.coerceIn(1, height - y)
    if (x == 0 && y == 0 && w == width && h == height) return this
    val out = ImageBitmap(w, h)
    Canvas(out).drawImageRect(this, IntOffset(x, y), IntSize(w, h), IntOffset.Zero, IntSize(w, h), smooth())
    return out
}

fun ImageBitmap.flattened(background: Color): ImageBitmap {
    val out = ImageBitmap(width, height)
    Canvas(out).apply {
        drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = background })
        drawImage(this@flattened, Offset.Zero, smooth())
    }
    return out
}

fun ImageBitmap.filtered(matrix: ColorMatrix): ImageBitmap {
    val out = ImageBitmap(width, height)
    val paint = Paint().apply {
        filterQuality = FilterQuality.High
        colorFilter = ColorFilter.colorMatrix(matrix)
    }
    Canvas(out).drawImage(this, Offset.Zero, paint)
    return out
}
