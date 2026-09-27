package com.vasmarfas.card.tools.documents.pdf

import com.vasmarfas.card.core.DeflateException
import com.vasmarfas.card.core.Inflate
import com.vasmarfas.card.core.Zlib
import kotlin.math.abs

// the chain stops at the first image codec (DCT, JPX, CCITTFax, JBIG2) or unknown filter, the data
// comes back decoded up to that point
internal object PdfFilters {
    fun filterNames(dict: PdfDict, doc: PdfDocument?): List<String> = when (val filter = dict.resolved("Filter", doc) ?: dict.resolved("F", doc)) {
        is PdfName -> listOf(filter.name)
        is PdfArray -> filter.items.mapNotNull { (doc?.resolve(it) ?: it) as? PdfName }.map { it.name }
        else -> emptyList()
    }

    private fun parameters(dict: PdfDict, doc: PdfDocument?, count: Int): List<PdfDict?> {
        val parms = dict.resolved("DecodeParms", doc) ?: dict.resolved("DP", doc)
        return when (parms) {
            is PdfDict -> List(count) { if (it == 0) parms else null }
            is PdfArray -> List(count) { parms.resolved(it, doc) as? PdfDict }
            else -> List(count) { null }
        }
    }

    fun decode(data: ByteArray, dict: PdfDict, doc: PdfDocument?, lenient: Boolean = false): ByteArray {
        val filters = filterNames(dict, doc)
        if (filters.isEmpty()) return data
        val parms = parameters(dict, doc, filters.size)
        var bytes = data
        for (i in filters.indices) {
            val p = parms[i]
            bytes = when (filters[i]) {
                "FlateDecode", "Fl" -> predict(flate(bytes, lenient), p, doc)
                "LZWDecode", "LZW" -> predict(lzw(bytes, p?.int("EarlyChange", doc) ?: 1), p, doc)
                "ASCIIHexDecode", "AHx" -> asciiHex(bytes)
                "ASCII85Decode", "A85" -> ascii85(bytes)
                "RunLengthDecode", "RL" -> runLength(bytes)
                "Crypt" -> bytes
                else -> return bytes
            }
        }
        return bytes
    }

    fun flate(data: ByteArray, lenient: Boolean = false): ByteArray {
        if (data.size < 2) return ByteArray(0)
        val cmf = data[0].toInt() and 0xFF
        val flg = data[1].toInt() and 0xFF
        val zlibHeader = cmf and 0x0F == 8 && (cmf * 256 + flg) % 31 == 0
        if (zlibHeader) {
            try {
                return Zlib.decompress(data)
            } catch (_: DeflateException) {
            }
        }
        try {
            return Inflate.inflate(data, 0, data.size, data.size * 4)
        } catch (e: DeflateException) {
            if (lenient) runCatching { return Inflate.inflatePartial(data, if (zlibHeader) 2 else 0) }
            throw PdfException("Corrupt Flate data: ${e.message}")
        }
    }

    fun predict(data: ByteArray, parms: PdfDict?, doc: PdfDocument?): ByteArray {
        val predictor = parms?.int("Predictor", doc) ?: 1
        if (predictor < 2) return data
        val colors = (parms?.int("Colors", doc) ?: 1).coerceIn(1, 32)
        val bits = (parms?.int("BitsPerComponent", doc) ?: 8).let { if (it in intArrayOf(1, 2, 4, 8, 16)) it else 8 }
        val columns = (parms?.int("Columns", doc) ?: 1).coerceIn(1, 1 shl 24)
        val bitsPerPixel = colors * bits
        val rowLength = ((columns.toLong() * bitsPerPixel + 7) / 8).coerceAtMost(data.size.toLong()).toInt()
        if (rowLength == 0) return data
        if (predictor == 2) return tiff(data, colors, bits, columns, rowLength)
        return png(data, (bitsPerPixel + 7) / 8, rowLength)
    }

    private fun png(data: ByteArray, bpp: Int, rowLength: Int): ByteArray {
        val out = ByteSink(data.size)
        var prior = ByteArray(rowLength)
        var current = ByteArray(rowLength)
        var p = 0
        while (p < data.size) {
            val type = data[p++].toInt() and 0xFF
            val n = minOf(rowLength, data.size - p)
            data.copyInto(current, 0, p, p + n)
            if (n < rowLength) current.fill(0, n, rowLength)
            p += n
            when (type) {
                1 -> for (i in bpp until n) current[i] = (current[i] + current[i - bpp]).toByte()
                2 -> for (i in 0 until n) current[i] = (current[i] + prior[i]).toByte()
                3 -> for (i in 0 until n) {
                    val left = if (i >= bpp) current[i - bpp].toInt() and 0xFF else 0
                    current[i] = (current[i] + ((left + (prior[i].toInt() and 0xFF)) ushr 1)).toByte()
                }
                4 -> for (i in 0 until n) {
                    val a = if (i >= bpp) current[i - bpp].toInt() and 0xFF else 0
                    val b = prior[i].toInt() and 0xFF
                    val c = if (i >= bpp) prior[i - bpp].toInt() and 0xFF else 0
                    val estimate = a + b - c
                    val pa = abs(estimate - a)
                    val pb = abs(estimate - b)
                    val pc = abs(estimate - c)
                    val predicted = if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    current[i] = (current[i] + predicted).toByte()
                }
            }
            out.write(current, 0, n)
            val swap = prior
            prior = current
            current = swap
        }
        return out.toByteArray()
    }

    private fun tiff(data: ByteArray, colors: Int, bits: Int, columns: Int, rowLength: Int): ByteArray {
        val out = data.copyOf()
        val rows = out.size / rowLength
        for (row in 0 until rows) {
            val base = row * rowLength
            when (bits) {
                8 -> for (i in colors until rowLength) out[base + i] = (out[base + i] + out[base + i - colors]).toByte()
                16 -> {
                    val stride = colors * 2
                    var i = stride
                    while (i + 1 < rowLength) {
                        val value = ((out[base + i].toInt() and 0xFF) shl 8 or (out[base + i + 1].toInt() and 0xFF)) +
                            ((out[base + i - stride].toInt() and 0xFF) shl 8 or (out[base + i - stride + 1].toInt() and 0xFF))
                        out[base + i] = (value ushr 8).toByte()
                        out[base + i + 1] = value.toByte()
                        i += 2
                    }
                }
                else -> {
                    val mask = (1 shl bits) - 1
                    val previous = IntArray(colors)
                    for (sample in 0 until minOf(columns * colors, rowLength * 8 / bits)) {
                        val bit = sample * bits
                        val index = base + bit / 8
                        val shift = 8 - bits - bit % 8
                        val raw = (out[index].toInt() ushr shift) and mask
                        val value = (raw + previous[sample % colors]) and mask
                        previous[sample % colors] = value
                        out[index] = ((out[index].toInt() and (mask shl shift).inv()) or (value shl shift)).toByte()
                    }
                }
            }
        }
        return out
    }

    fun lzw(data: ByteArray, earlyChange: Int): ByteArray {
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val first = ByteArray(4096)
        val length = IntArray(4096)
        for (i in 0 until 256) {
            prefix[i] = -1
            suffix[i] = i.toByte()
            first[i] = i.toByte()
            length[i] = 1
        }
        val stack = ByteArray(4096)
        val out = ByteSink(data.size * 3)
        var next = 258
        var codeLength = 9
        var previous = -1
        var buffer = 0
        var bufferBits = 0
        var p = 0
        while (true) {
            while (bufferBits < codeLength && p < data.size) {
                buffer = (buffer shl 8) or (data[p++].toInt() and 0xFF)
                bufferBits += 8
            }
            if (bufferBits < codeLength) break
            val code = (buffer ushr (bufferBits - codeLength)) and ((1 shl codeLength) - 1)
            bufferBits -= codeLength
            buffer = buffer and ((1 shl bufferBits) - 1)
            if (code == 256) {
                next = 258
                codeLength = 9
                previous = -1
                continue
            }
            if (code == 257) break
            if (previous < 0) {
                if (code > 255) break
                out.write(code)
                previous = code
                continue
            }
            if (code > next) break
            if (next < 4096) {
                prefix[next] = previous
                suffix[next] = first[if (code < next) code else previous]
                first[next] = first[previous]
                length[next] = length[previous] + 1
                next++
            }
            var c = code
            var k = length[code]
            while (c >= 0 && k > 0) {
                stack[--k] = suffix[c]
                c = prefix[c]
            }
            out.write(stack, 0, length[code])
            previous = code
            val threshold = next + earlyChange
            codeLength = when {
                threshold >= 2048 -> 12
                threshold >= 1024 -> 11
                threshold >= 512 -> 10
                else -> 9
            }
        }
        return out.toByteArray()
    }

    fun asciiHex(data: ByteArray): ByteArray {
        val out = ByteSink(data.size / 2 + 1)
        var high = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val v = hexValue(c)
            if (v < 0) continue
            if (high < 0) {
                high = v
            } else {
                out.write((high shl 4) or v)
                high = -1
            }
        }
        if (high >= 0) out.write(high shl 4)
        return out.toByteArray()
    }

    fun ascii85(data: ByteArray): ByteArray {
        val out = ByteSink(data.size)
        var i = 0
        while (i < data.size && isWhitespace(data[i].toInt())) i++
        if (i + 1 < data.size && data[i].toInt() == '<'.code && data[i + 1].toInt() == '~'.code) i += 2
        var tuple = 0L
        var count = 0
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            if (c == '~'.code) break
            if (c == 'z'.code && count == 0) {
                repeat(4) { out.write(0) }
                continue
            }
            if (c < '!'.code || c > 'u'.code) continue
            tuple = tuple * 85 + (c - 33)
            count++
            if (count == 5) {
                for (shift in intArrayOf(24, 16, 8, 0)) out.write((tuple ushr shift).toInt() and 0xFF)
                tuple = 0
                count = 0
            }
        }
        if (count > 1) {
            for (k in count until 5) tuple = tuple * 85 + 84
            for (k in 0 until count - 1) out.write((tuple ushr (24 - 8 * k)).toInt() and 0xFF)
        }
        return out.toByteArray()
    }

    fun runLength(data: ByteArray): ByteArray {
        val out = ByteSink(data.size * 2)
        var i = 0
        while (i < data.size) {
            val n = data[i++].toInt() and 0xFF
            when {
                n < 128 -> {
                    val count = minOf(n + 1, data.size - i)
                    out.write(data, i, count)
                    i += count
                }
                n > 128 -> {
                    if (i >= data.size) break
                    val b = data[i++].toInt()
                    repeat(257 - n) { out.write(b) }
                }
                else -> break
            }
        }
        return out.toByteArray()
    }
}
