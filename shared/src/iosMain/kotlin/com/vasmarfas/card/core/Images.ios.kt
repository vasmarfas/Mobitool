package com.vasmarfas.card.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSData
import platform.Foundation.dataWithBytes
import platform.UIKit.UIGraphicsPopContext
import platform.UIKit.UIGraphicsPushContext
import platform.UIKit.UIImage

@OptIn(ExperimentalForeignApi::class)
actual suspend fun decodeRawImage(bytes: ByteArray, maxSide: Int): RawImage? = withContext(Dispatchers.Default) {
    skiaDecode(bytes)?.let { return@withContext RawImage(it, oriented = true) }
    val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.convert()) }
    val image = UIImage.imageWithData(data) ?: return@withContext null
    val width = image.size.useContents { width * image.scale }.toInt()
    val height = image.size.useContents { height * image.scale }.toInt()
    if (width <= 0 || height <= 0) return@withContext null
    val rgba = ByteArray(width * height * 4)
    val colorSpace = CGColorSpaceCreateDeviceRGB()
    rgba.usePinned { pinned ->
        val context = CGBitmapContextCreate(
            pinned.addressOf(0), width.convert(), height.convert(), 8u, (width * 4).convert(), colorSpace,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        )
        CGContextTranslateCTM(context, 0.0, height.toDouble())
        CGContextScaleCTM(context, 1.0, -1.0)
        UIGraphicsPushContext(context)
        image.drawInRect(CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
        UIGraphicsPopContext()
        CGContextRelease(context)
    }
    CGColorSpaceRelease(colorSpace)
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val a = rgba[i * 4 + 3].toInt() and 0xFF
        var r = rgba[i * 4].toInt() and 0xFF
        var g = rgba[i * 4 + 1].toInt() and 0xFF
        var b = rgba[i * 4 + 2].toInt() and 0xFF
        if (a in 1..254) {
            r = (r * 255 / a).coerceAtMost(255)
            g = (g * 255 / a).coerceAtMost(255)
            b = (b * 255 / a).coerceAtMost(255)
        }
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    RawImage(imageBitmapOf(pixels, width, height), oriented = true)
}
