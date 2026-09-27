package com.vasmarfas.card.tools.media

import kotlin.math.max
import kotlin.math.min

// encoding lags one frame behind addFrame: a frame that erases pixels decides the disposal method
// of the one before it
class GifWriter(val width: Int, val height: Int, val loopCount: Int = 0) {
    private val out = ByteSink(1 shl 16)
    private var shown: IntArray? = null
    private var pending: GifFrame? = null
    private var elapsedMs = 0L
    private var writtenCs = 0L
    private var finished = false

    init {
        require(width in 1..65535 && height in 1..65535) { "GIF dimensions must be within 1..65535" }
        require(loopCount in -1..65535) { "loopCount must be -1 (play once), 0 (forever) or a repeat count up to 65535" }
        out.ascii("GIF89a")
        out.shortLE(width)
        out.shortLE(height)
        out.byte(0x70)
        out.byte(0)
        out.byte(0)
        if (loopCount >= 0) {
            out.byte(0x21)
            out.byte(0xFF)
            out.byte(11)
            out.ascii("NETSCAPE2.0")
            out.byte(3)
            out.byte(1)
            out.shortLE(loopCount)
            out.byte(0)
        }
    }

    fun addFrame(pixels: IntArray, delayMs: Int, dither: Boolean = true) {
        check(!finished) { "finish() has already been called" }
        require(pixels.size == width * height) { "frame must have width × height pixels" }
        require(delayMs >= 0) { "delay must not be negative" }
        val target = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            target[i] = if (p ushr 24 < 128) 0 else p or OPAQUE
        }
        val previous = shown
        val last = pending
        var base = previous
        if (previous != null && last != null) {
            val erased = bounds { target[it] == 0 && previous[it] != 0 }
            if (erased != null) {
                last.cover(erased)
                last.disposal = RESTORE_BACKGROUND
                base = previous.copyOf()
                for (y in last.top until last.top + last.height) base.fill(0, y * width + last.left, y * width + last.left + last.width)
            }
        }
        val changed = if (base == null) bounds { target[it] != 0 } else bounds { target[it] != base[it] }
        shown = target
        if (changed == null && last != null && last.disposal == KEEP) {
            last.delayMs += delayMs
            return
        }
        if (last != null) write(last)
        pending = if (changed == null) GifFrame.blank(delayMs) else encode(target, base, changed, delayMs, dither)
    }

    fun finish(): ByteArray {
        check(!finished) { "finish() has already been called" }
        val last = checkNotNull(pending) { "a GIF needs at least one frame" }
        write(last)
        pending = null
        out.byte(0x3B)
        finished = true
        return out.toByteArray()
    }

    private fun encode(target: IntArray, base: IntArray?, r: Rect, delayMs: Int, dither: Boolean): GifFrame {
        val w = r.width
        val h = r.height
        val region = IntArray(w * h)
        var unchanged = 0
        var erasing = false
        for (y in 0 until h) {
            val row = (r.top + y) * width + r.left
            for (x in 0 until w) {
                val p = target[row + x]
                region[y * w + x] = p
                if (p == 0) erasing = true else if (base != null && p == base[row + x]) unchanged++
            }
        }
        val needsSlot = unchanged > 0 || w != width || h != height
        val palette = if (needsSlot && !erasing) Quantizer.palette(region, 255).withTransparent() else Quantizer.palette(region, 256)
        var indices = Quantizer.remap(region, w, h, palette, dither)
        val codeSize = codeSize(palette.size)
        var data = GifLzw.encode(indices, codeSize)
        if (base != null && unchanged > 0) {
            val t = palette.transparentIndex.toByte()
            val masked = indices.copyOf()
            for (y in 0 until h) {
                val row = (r.top + y) * width + r.left
                for (x in 0 until w) {
                    val p = target[row + x]
                    if (p != 0 && p == base[row + x]) masked[y * w + x] = t
                }
            }
            val maskedData = GifLzw.encode(masked, codeSize)
            if (maskedData.size <= data.size) {
                indices = masked
                data = maskedData
            }
        }
        return GifFrame(r.left, r.top, w, h, palette, indices, data, delayMs)
    }

    private fun write(f: GifFrame) {
        val transparent = f.palette.transparentIndex
        out.byte(0x21)
        out.byte(0xF9)
        out.byte(4)
        out.byte((f.disposal shl 2) or (if (transparent >= 0) 1 else 0))
        out.shortLE(centiseconds(f.delayMs))
        out.byte(max(transparent, 0))
        out.byte(0)
        out.byte(0x2C)
        out.shortLE(f.left)
        out.shortLE(f.top)
        out.shortLE(f.width)
        out.shortLE(f.height)
        val bits = tableBits(f.palette.size)
        out.byte(0x80 or (bits - 1))
        for (i in 0 until (1 shl bits)) {
            val c = if (i < f.palette.size) f.palette.colors[i] else 0
            out.byte(c shr 16)
            out.byte(c shr 8)
            out.byte(c)
        }
        val codeSize = codeSize(f.palette.size)
        val data = f.data ?: GifLzw.encode(f.indices, codeSize)
        out.byte(codeSize)
        var pos = 0
        while (pos < data.size) {
            val n = min(255, data.size - pos)
            out.byte(n)
            out.bytes(data, pos, pos + n)
            pos += n
        }
        out.byte(0)
    }

    // rounds the running total, not each frame, so 30 fps keeps its speed at 3-4-3 cs
    private fun centiseconds(delayMs: Int): Int {
        elapsedMs += delayMs
        val cs = max(2L, (elapsedMs + 5) / 10 - writtenCs).coerceAtMost(65535L)
        writtenCs += cs
        return cs.toInt()
    }

    private inline fun bounds(test: (Int) -> Boolean): Rect? {
        var left = width
        var right = -1
        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            var first = -1
            var lastX = -1
            for (x in 0 until width) {
                if (test(row + x)) {
                    if (first < 0) first = x
                    lastX = x
                }
            }
            if (first >= 0) {
                if (top < 0) top = y
                bottom = y
                left = min(left, first)
                right = max(right, lastX)
            }
        }
        return if (top < 0) null else Rect(left, top, right - left + 1, bottom - top + 1)
    }

    private class Rect(val left: Int, val top: Int, val width: Int, val height: Int)

    private class GifFrame(
        var left: Int,
        var top: Int,
        var width: Int,
        var height: Int,
        val palette: Palette,
        var indices: ByteArray,
        var data: ByteArray?,
        var delayMs: Int,
    ) {
        var disposal = KEEP

        fun cover(r: Rect) {
            val l = min(left, r.left)
            val t = min(top, r.top)
            val w = max(left + width, r.left + r.width) - l
            val h = max(top + height, r.top + r.height) - t
            if (l == left && t == top && w == width && h == height) return
            check(palette.transparentIndex >= 0)
            val grown = ByteArray(w * h)
            grown.fill(palette.transparentIndex.toByte())
            for (y in 0 until height) indices.copyInto(grown, (top - t + y) * w + (left - l), y * width, (y + 1) * width)
            left = l
            top = t
            width = w
            height = h
            indices = grown
            data = null
        }

        companion object {
            fun blank(delayMs: Int) = GifFrame(0, 0, 1, 1, Palette(IntArray(1), 0), ByteArray(1), null, delayMs)
        }
    }

    private companion object {
        const val OPAQUE = -0x1000000
        const val KEEP = 1
        const val RESTORE_BACKGROUND = 2

        fun tableBits(size: Int): Int {
            var bits = 1
            while (1 shl bits < size) bits++
            return bits
        }

        fun codeSize(size: Int): Int = max(2, tableBits(size))

        fun Palette.withTransparent(): Palette = Palette(colors + 0, size)
    }
}

private object GifLzw {
    private const val HASH_BITS = 13
    private const val MAX_CODE = 4096

    fun encode(indices: ByteArray, minCodeSize: Int): ByteArray {
        val clear = 1 shl minCodeSize
        val keys = IntArray(1 shl HASH_BITS)
        val codes = IntArray(1 shl HASH_BITS)
        keys.fill(-1)
        val bits = BitPacker(indices.size / 2 + 64)
        var next = clear + 2
        var width = minCodeSize + 1
        bits.write(clear, width)
        var prefix = indices[0].toInt() and 0xFF
        val mask = (1 shl HASH_BITS) - 1
        for (i in 1 until indices.size) {
            val k = indices[i].toInt() and 0xFF
            val key = (prefix shl 8) or k
            var slot = (key * -0x61c88647) ushr (32 - HASH_BITS)
            var found = -1
            while (true) {
                val existing = keys[slot]
                if (existing == key) {
                    found = codes[slot]
                    break
                }
                if (existing < 0) break
                slot = (slot + 1) and mask
            }
            if (found >= 0) {
                prefix = found
                continue
            }
            bits.write(prefix, width)
            if (next < MAX_CODE) {
                keys[slot] = key
                codes[slot] = next++
                if (next > 1 shl width && width < 12) width++
            } else {
                bits.write(clear, width)
                keys.fill(-1)
                next = clear + 2
                width = minCodeSize + 1
            }
            prefix = k
        }
        bits.write(prefix, width)
        if (next < MAX_CODE) next++
        if (next > 1 shl width && width < 12) width++
        bits.write(clear + 1, width)
        return bits.finish()
    }

    private class BitPacker(capacity: Int) {
        private val sink = ByteSink(capacity)
        private var acc = 0
        private var count = 0

        fun write(code: Int, width: Int) {
            acc = acc or (code shl count)
            count += width
            while (count >= 8) {
                sink.byte(acc)
                acc = acc ushr 8
                count -= 8
            }
        }

        fun finish(): ByteArray {
            if (count > 0) sink.byte(acc)
            return sink.toByteArray()
        }
    }
}
