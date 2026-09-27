package com.vasmarfas.card.tools.documents

import com.vasmarfas.card.tools.documents.pdf.PdfArray
import com.vasmarfas.card.tools.documents.pdf.PdfDict
import com.vasmarfas.card.tools.documents.pdf.PdfInt
import com.vasmarfas.card.tools.documents.pdf.PdfName
import com.vasmarfas.card.tools.documents.pdf.PdfObject
import com.vasmarfas.card.tools.documents.pdf.PdfReal
import com.vasmarfas.card.tools.documents.pdf.PdfRef
import com.vasmarfas.card.tools.documents.pdf.PdfString
import com.vasmarfas.card.tools.documents.pdf.PdfWriter
import com.vasmarfas.card.tools.documents.pdf.TrueTypeFont

class FontFamily(val regular: TrueTypeFont, val bold: TrueTypeFont, val italic: TrueTypeFont = regular, val boldItalic: TrueTypeFont = bold) {
    fun pick(bold: Boolean, italic: Boolean): TrueTypeFont = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }
}

class DocFonts(val body: FontFamily, val headings: FontFamily, val code: FontFamily)

// the ruble face is a second copy whose ToUnicode maps its Р to U+20BD
internal class Face(val font: TrueTypeFont, val resource: String, val ref: PdfRef, val ruble: Boolean = false) {
    private val used = HashMap<Int, Int>()

    fun scale(size: Float): Float = size / font.unitsPerEm

    fun use(glyph: Int, codePoint: Int) {
        if (glyph !in used) used[glyph] = if (ruble || font.glyphId(codePoint) == glyph) codePoint else '?'.code
    }

    fun write(writer: PdfWriter) {
        val glyphs = used.keys.sorted()
        val subset = font.subset(glyphs.toSet() + 0)
        val name = PdfName(tag(glyphs) + "+" + font.postScriptName.filter { it.code in 33..126 && it !in "()<>[]{}/%#" })
        val em = 1000f / font.unitsPerEm
        val fontFile = writer.stream(PdfDict("Length1" to PdfInt.of(subset.size)), subset)
        var flags = 32
        if (font.isFixedPitch) flags = flags or 1
        if (font.isSerif) flags = flags or 2
        if (font.isItalic) flags = flags or 64
        val descriptor = writer.add(
            PdfDict(
                "Type" to PdfName("FontDescriptor"),
                "FontName" to name,
                "Flags" to PdfInt.of(flags),
                "FontBBox" to PdfArray(
                    PdfInt.of((font.bboxXMin * em).toInt()),
                    PdfInt.of((font.bboxYMin * em).toInt()),
                    PdfInt.of((font.bboxXMax * em).toInt()),
                    PdfInt.of((font.bboxYMax * em).toInt()),
                ),
                "ItalicAngle" to PdfReal(font.italicAngle),
                "Ascent" to PdfInt.of((font.ascent * em).toInt()),
                "Descent" to PdfInt.of((font.descent * em).toInt()),
                "CapHeight" to PdfInt.of((font.capHeight * em).toInt()),
                "StemV" to PdfInt.of(if (font.weightClass >= 600) 120 else 80),
                "FontFile2" to fontFile,
            ),
        )
        val widths = PdfArray()
        var i = 0
        while (i < glyphs.size) {
            var j = i
            while (j + 1 < glyphs.size && glyphs[j + 1] == glyphs[j] + 1) j++
            widths.add(PdfInt.of(glyphs[i]))
            widths.add(PdfArray((i..j).mapTo(ArrayList<PdfObject>()) { PdfInt.of((font.advanceWidth(glyphs[it]) * em).toInt()) }))
            i = j + 1
        }
        val cid = writer.add(
            PdfDict(
                "Type" to PdfName("Font"),
                "Subtype" to PdfName("CIDFontType2"),
                "BaseFont" to name,
                "CIDSystemInfo" to PdfDict("Registry" to PdfString.ofText("Adobe"), "Ordering" to PdfString.ofText("Identity"), "Supplement" to PdfInt.of(0)),
                "FontDescriptor" to descriptor,
                "W" to widths,
                "CIDToGIDMap" to PdfName("Identity"),
            ),
        )
        writer[ref] = PdfDict(
            "Type" to PdfName("Font"),
            "Subtype" to PdfName("Type0"),
            "BaseFont" to name,
            "Encoding" to PdfName("Identity-H"),
            "DescendantFonts" to PdfArray(cid),
            "ToUnicode" to writer.stream(PdfDict(), toUnicode(glyphs).encodeToByteArray()),
        )
    }

    private fun toUnicode(glyphs: List<Int>): String {
        val out = StringBuilder()
        out.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        out.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        out.append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
        out.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        for (chunk in glyphs.chunked(100)) {
            out.append(chunk.size).append(" beginbfchar\n")
            for (glyph in chunk) {
                out.append('<').append(hex4(glyph)).append("> <")
                val cp = used.getValue(glyph)
                if (cp < 0x10000) {
                    out.append(hex4(cp))
                } else {
                    val v = cp - 0x10000
                    out.append(hex4(0xD800 + (v shr 10))).append(hex4(0xDC00 + (v and 0x3FF)))
                }
                out.append(">\n")
            }
            out.append("endbfchar\n")
        }
        out.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        return out.toString()
    }

    private fun tag(glyphs: List<Int>): String {
        var hash = 1125899906842597L
        for (g in glyphs) hash = 31 * hash + g
        hash = 31 * hash + resource.hashCode()
        val letters = CharArray(6)
        var v = hash and Long.MAX_VALUE
        for (k in 0 until 6) {
            letters[k] = 'A' + (v % 26).toInt()
            v /= 26
        }
        return letters.concatToString()
    }
}

internal fun hex4(value: Int): String {
    val digits = "0123456789ABCDEF"
    return charArrayOf(digits[(value shr 12) and 15], digits[(value shr 8) and 15], digits[(value shr 4) and 15], digits[value and 15]).concatToString()
}
