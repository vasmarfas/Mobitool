package com.vasmarfas.card.tools.documents.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PdfFiltersTest {
    private val random = Random(1234)

    private fun decode(data: ByteArray, dict: String) = PdfFilters.decode(data, PdfParser(latin1(dict), NameCache()).parseObject() as PdfDict, null)

    private fun sample(size: Int) = ByteArray(size) { (random.nextInt(8) * 17 + it / 50).toByte() }

    @Test
    fun flateWithAndWithoutZlibHeader() {
        val data = sample(10_000)
        assertContentEquals(data, decode(deflate(data), "<</Filter/FlateDecode>>"))
        val raw = Deflater(6, true).run {
            setInput(data)
            finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!finished()) out.write(buffer, 0, deflate(buffer))
            out.toByteArray()
        }
        assertContentEquals(data, decode(raw, "<</Filter/Fl>>"))
        assertContentEquals(ByteArray(0), decode(ByteArray(0), "<</Filter/FlateDecode>>"))
        assertFailsWith<PdfException> { decode(byteArrayOf(0x78, 0x9C.toByte(), -1, -1, -1, -1), "<</Filter/FlateDecode>>") }
    }

    @Test
    fun aTruncatedStreamGivesItsBeginningWhenLenient() {
        val data = latin1((0 until 400).joinToString("") { "BT /F1 12 Tf 72 ${700 - it} Td (Line $it) Tj ET\n" })
        val cut = deflate(data).let { it.copyOf(it.size / 2) }
        assertFailsWith<PdfException> { PdfFilters.flate(cut) }
        val partial = PdfFilters.flate(cut, lenient = true)
        assertTrue(partial.isNotEmpty() && partial.size < data.size)
        assertContentEquals(data.copyOf(partial.size), partial)
    }

    private fun pngEncode(rows: List<ByteArray>, bpp: Int, types: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        var prior = ByteArray(rows[0].size)
        for ((index, row) in rows.withIndex()) {
            val type = types[index % types.size]
            out.write(type)
            for (i in row.indices) {
                val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = prior[i].toInt() and 0xFF
                val c = if (i >= bpp) prior[i - bpp].toInt() and 0xFF else 0
                val predicted = when (type) {
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> {
                        val p = a + b - c
                        val pa = Math.abs(p - a)
                        val pb = Math.abs(p - b)
                        val pc = Math.abs(p - c)
                        if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> 0
                }
                out.write((row[i] - predicted) and 0xFF)
            }
            prior = row
        }
        return out.toByteArray()
    }

    @Test
    fun pngPredictors() {
        val rows = List(9) { sample(3 * 17) }
        val encoded = pngEncode(rows, 3, intArrayOf(0, 1, 2, 3, 4))
        val expected = rows.reduce { acc, bytes -> acc + bytes }
        val parms = "/DecodeParms<</Predictor 15/Colors 3/BitsPerComponent 8/Columns 17>>"
        assertContentEquals(expected, decode(deflate(encoded), "<</Filter/FlateDecode$parms>>"))
        val truncated = encoded.copyOf(encoded.size - 10)
        val partial = decode(deflate(truncated), "<</Filter/FlateDecode$parms>>")
        assertContentEquals(expected.copyOf(partial.size), partial)
    }

    @Test
    fun tiffPredictor() {
        val eightBit = byteArrayOf(10, 20, 5, 5, 1, 1, 7, 8, 1, 2, 3, 4)
        val decoded = decode(deflate(eightBit), "<</Filter/FlateDecode/DecodeParms<</Predictor 2/Colors 2/Columns 3>>>>")
        assertContentEquals(byteArrayOf(10, 20, 15, 25, 16, 26, 7, 8, 8, 10, 11, 14), decoded)
        val sixteen = byteArrayOf(0x01, 0x00, 0x00, 0xFF.toByte(), 0x00, 0x02)
        val wide = decode(deflate(sixteen), "<</Filter/FlateDecode/DecodeParms<</Predictor 2/BitsPerComponent 16/Columns 3>>>>")
        assertContentEquals(byteArrayOf(0x01, 0x00, 0x01, 0xFF.toByte(), 0x02, 0x01), wide)
        val nibbles = byteArrayOf(0x12, 0x31)
        val small = decode(deflate(nibbles), "<</Filter/FlateDecode/DecodeParms<</Predictor 2/BitsPerComponent 4/Columns 4>>>>")
        assertContentEquals(byteArrayOf(0x13, 0x67), small)
    }

    private fun lzwEncode(data: ByteArray, earlyChange: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0L
        var bits = 0
        fun emit(code: Int, width: Int) {
            buffer = (buffer shl width) or code.toLong()
            bits += width
            while (bits >= 8) {
                out.write((buffer ushr (bits - 8)).toInt() and 0xFF)
                bits -= 8
            }
        }
        val table = HashMap<String, Int>()
        fun reset() {
            table.clear()
            for (i in 0 until 256) table[i.toChar().toString()] = i
        }
        reset()
        var next = 258
        var width = 9
        emit(256, width)
        var current = ""
        for (b in data) {
            val c = (b.toInt() and 0xFF).toChar()
            val candidate = current + c
            if (table.containsKey(candidate)) {
                current = candidate
                continue
            }
            emit(table.getValue(current), width)
            table[candidate] = next++
            if (next + earlyChange > (1 shl width) && width < 12) width++
            if (next >= 4094) {
                emit(256, width)
                reset()
                next = 258
                width = 9
            }
            current = c.toString()
        }
        if (current.isNotEmpty()) emit(table.getValue(current), width)
        next++
        if (next + earlyChange > (1 shl width) && width < 12) width++
        emit(257, width)
        if (bits > 0) out.write((buffer shl (8 - bits)).toInt() and 0xFF)
        return out.toByteArray()
    }

    @Test
    fun lzw() {
        val specimen = byteArrayOf(0x80.toByte(), 0x0B, 0x60, 0x50, 0x22, 0x0C, 0x0C, 0x85.toByte(), 0x01)
        assertContentEquals(latin1("-----A---B"), decode(specimen, "<</Filter/LZWDecode>>"))
        val text = latin1("TOBEORNOTTOBEORTOBEORNOT".repeat(400)) + sample(20_000)
        assertContentEquals(text, decode(lzwEncode(text, 1), "<</Filter/LZWDecode>>"))
        assertContentEquals(text, decode(lzwEncode(text, 0), "<</Filter/LZW/DecodeParms<</EarlyChange 0>>>>"))
    }

    @Test
    fun asciiFilters() {
        assertContentEquals(latin1("ab`"), decode(latin1("61 62\n6>tail"), "<</Filter/ASCIIHexDecode>>"))
        assertContentEquals(latin1("Man sure."), decode(latin1("<~9jqo^F*2M7/c~>"), "<</Filter/ASCII85Decode>>"))
        assertContentEquals(ByteArray(8) + latin1("Man "), decode(latin1("zz9jq\no^~>"), "<</Filter/A85>>"))
        val data = sample(1001)
        val encoded = StringBuilder()
        for (i in data.indices step 4) {
            var value = 0L
            val count = minOf(4, data.size - i)
            for (k in 0 until 4) value = (value shl 8) or (if (k < count) data[i + k].toLong() and 0xFF else 0L)
            val digits = CharArray(5)
            for (k in 4 downTo 0) {
                digits[k] = (value % 85 + 33).toInt().toChar()
                value /= 85
            }
            encoded.append(digits, 0, count + 1)
        }
        assertContentEquals(data, decode(latin1("$encoded~>"), "<</Filter/ASCII85Decode>>"))
    }

    @Test
    fun runLength() {
        val encoded = byteArrayOf(2, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 253.toByte(), 'x'.code.toByte(), 128.toByte(), 1, 2)
        assertContentEquals(latin1("abcxxxx"), decode(encoded, "<</Filter/RunLengthDecode>>"))
    }

    @Test
    fun chainsStopAtImageCodecs() {
        val data = sample(500)
        val hex = latin1(deflate(data).joinToString("") { "%02x".format(it) } + ">")
        assertContentEquals(data, decode(hex, "<</Filter[/ASCIIHexDecode/FlateDecode]>>"))
        assertContentEquals(data, decode(deflate(data), "<</Filter[/FlateDecode/DCTDecode]>>"))
        val jpeg = byteArrayOf(-1, -40, -1, -32)
        assertContentEquals(jpeg, decode(jpeg, "<</Filter/DCTDecode>>"))
        val rows = List(4) { sample(8) }
        val png = deflate(pngEncode(rows, 1, intArrayOf(2)))
        val both = latin1(png.joinToString("") { "%02X".format(it) })
        assertContentEquals(rows.reduce { a, b -> a + b }, decode(both, "<</Filter[/AHx/Fl]/DecodeParms[null<</Predictor 12/Columns 8>>]>>"))
    }
}
