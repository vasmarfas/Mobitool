package com.vasmarfas.card.tools.media

import kotlin.math.min

// Truevision TGA 2.0. The format has no signature, so a file counts as TGA only when its header holds together
object Tga {
    private const val HEADER = 18
    private const val MAX_RUN = 128
    private const val MAX_PIXELS = 1L shl 28
    private const val MAPPED = 1
    private const val TRUE_COLOR = 2
    private const val OPAQUE = 0xFF shl 24

    fun decode(bytes: ByteArray): DecodedImage? {
        if (bytes.size < HEADER || bytes.u8(1) > 1) return null
        val hasMap = bytes.u8(1) == 1
        val type = bytes.u8(2)
        if (type !in 1..3 && type !in 9..11) return null
        val kind = type and 3
        val rle = type > 3
        val width = bytes.u16le(12)
        val height = bytes.u16le(14)
        val depth = bytes.u8(16)
        val descriptor = bytes.u8(17)
        val attributes = descriptor and 15
        val valid = when (kind) {
            MAPPED -> hasMap && (depth == 8 || depth == 16)
            TRUE_COLOR -> depth == 15 || depth == 16 || depth == 24 || depth == 32
            else -> depth == 8 || depth == 16
        }
        if (!valid || width == 0 || height == 0) return null

        val mapFirst = bytes.u16le(3)
        val mapLength = if (hasMap) bytes.u16le(5) else 0
        val mapDepth = bytes.u8(7)
        val mapEntry = (mapDepth + 7) / 8
        var at = HEADER + bytes.u8(0)
        if (at + mapLength * mapEntry > bytes.size) return null
        val palette = if (kind == MAPPED) {
            if (mapDepth != 15 && mapDepth != 16 && mapDepth != 24 && mapDepth != 32) return null
            IntArray(mapLength) { color(bytes, at + it * mapEntry, mapDepth, attributes) }
        } else {
            IntArray(0)
        }
        at += mapLength * mapEntry

        val size = (depth + 7) / 8
        val total = width.toLong() * height
        val available = (bytes.size - at).toLong()
        val limit = if (rle) available * MAX_RUN else available / size
        if (total > MAX_PIXELS || total > limit) return null

        fun read(p: Int): Int = when {
            kind == MAPPED -> palette.getOrElse((if (size == 1) bytes.u8(p) else bytes.u16le(p)) - mapFirst) { 0 }
            kind == TRUE_COLOR -> color(bytes, p, depth, attributes)
            size == 1 -> OPAQUE or gray(bytes.u8(p))
            else -> (bytes.u8(p + 1) shl 24) or gray(bytes.u8(p))
        }
        val topDown = descriptor and 0x20 != 0
        val rightToLeft = descriptor and 0x10 != 0
        val pixels = IntArray(total.toInt())
        var n = 0
        fun put(color: Int) {
            val row = n / width
            val column = n % width
            val x = if (rightToLeft) width - 1 - column else column
            val y = if (topDown) row else height - 1 - row
            pixels[y * width + x] = color
            n++
        }
        if (rle) {
            while (n < pixels.size) {
                if (at >= bytes.size) return null
                val header = bytes.u8(at++)
                val count = min((header and 0x7F) + 1, pixels.size - n)
                val repeated = header >= 0x80
                val needed = if (repeated) size else ((header and 0x7F) + 1) * size
                if (at + needed > bytes.size) return null
                if (repeated) {
                    val color = read(at)
                    repeat(count) { put(color) }
                } else {
                    for (i in 0 until count) put(read(at + i * size))
                }
                at += needed
            }
        } else {
            for (i in pixels.indices) put(read(at + i * size))
        }
        // many writers leave the alpha byte of a 32-bit file at zero when the picture has no transparency
        if (pixels.all { it ushr 24 == 0 }) for (i in pixels.indices) pixels[i] = pixels[i] or OPAQUE
        return DecodedImage(width, height, pixels)
    }

    private fun gray(v: Int): Int = (v shl 16) or (v shl 8) or v

    private fun color(bytes: ByteArray, at: Int, depth: Int, attributes: Int): Int = when (depth) {
        15, 16 -> {
            val v = bytes.u16le(at)
            val alpha = if (depth == 16 && attributes > 0 && v and 0x8000 == 0) 0 else OPAQUE
            alpha or (five(v shr 10) shl 16) or (five(v shr 5) shl 8) or five(v)
        }
        24 -> OPAQUE or (bytes.u8(at + 2) shl 16) or (bytes.u8(at + 1) shl 8) or bytes.u8(at)
        else -> (bytes.u8(at + 3) shl 24) or (bytes.u8(at + 2) shl 16) or (bytes.u8(at + 1) shl 8) or bytes.u8(at)
    }

    private fun five(v: Int): Int {
        val c = v and 31
        return (c shl 3) or (c shr 2)
    }
}
