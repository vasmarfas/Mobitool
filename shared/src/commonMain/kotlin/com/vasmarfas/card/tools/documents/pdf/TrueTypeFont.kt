package com.vasmarfas.card.tools.documents.pdf

import kotlin.math.abs

class FontFormatException(message: String) : Exception(message)

class GlyphContour(val x: FloatArray, val y: FloatArray, val onCurve: BooleanArray)

class TrueTypeFont(val data: ByteArray) {
    private val directory: Map<String, Entry> = readDirectory()
    private val head = required("head", 54)
    private val hhea = required("hhea", 36)
    private val maxp = required("maxp", 6)
    private val hmtx = required("hmtx")
    private val glyf = required("glyf")
    private val os2 = optional("OS/2")?.padded(OS2_LENGTH)
    private val post = optional("post")?.padded(POST_LENGTH)
    private val names = optional("name")

    val unitsPerEm: Int = head.u16(18)
    val numGlyphs: Int = maxp.u16(4)

    init {
        if (unitsPerEm !in 16..16384) throw FontFormatException("Invalid unitsPerEm $unitsPerEm")
        if (numGlyphs == 0) throw FontFormatException("Font has no glyphs")
    }

    private val loca: IntArray = readLoca()
    private val advances: IntArray = readAdvances()
    private val charMap: CharMap = readCmap()
    private val kerningPairs: Map<Int, Int> by lazy { readKern() }

    val ascent: Int
    val descent: Int
    val lineGap: Int

    init {
        val typo = os2?.takeIf { it.u16(62) and USE_TYPO_METRICS != 0 }
        ascent = typo?.s16(68) ?: hhea.s16(4)
        descent = -abs(typo?.s16(70) ?: hhea.s16(6))
        lineGap = typo?.s16(72) ?: hhea.s16(8)
    }

    val capHeight: Int = os2?.takeIf { it.u16(0) >= 2 }?.s16(88)?.takeIf { it > 0 } ?: glyphTop('H') ?: ascent
    val xHeight: Int = os2?.takeIf { it.u16(0) >= 2 }?.s16(86)?.takeIf { it > 0 } ?: glyphTop('x') ?: 0
    val bboxXMin: Int = head.s16(36)
    val bboxYMin: Int = head.s16(38)
    val bboxXMax: Int = head.s16(40)
    val bboxYMax: Int = head.s16(42)
    val italicAngle: Double = (post?.s32(4) ?: 0) / 65536.0
    val isFixedPitch: Boolean = post != null && post.u32(12) != 0L
    val isBold: Boolean = if (os2 != null) os2.u16(62) and 0x20 != 0 else head.u16(44) and 1 != 0
    val isItalic: Boolean = if (os2 != null) os2.u16(62) and 0x201 != 0 else head.u16(44) and 2 != 0
    val weightClass: Int = os2?.u16(4)?.takeIf { it in 1..1000 } ?: if (isBold) 700 else 400
    val isSerif: Boolean = os2 != null && os2.u8(32) == 2 && os2.u8(33) in 2..10
    val familyName: String = name(16) ?: name(1) ?: ""
    val postScriptName: String = name(6) ?: familyName.filterNot { it == ' ' }

    fun glyphId(codePoint: Int): Int {
        if (codePoint !in 0..0x10FFFF) return 0
        val glyph = charMap.glyph(codePoint)
        return if (glyph in 1 until numGlyphs) glyph else 0
    }

    fun advanceWidth(glyphId: Int): Int = if (glyphId in 0 until numGlyphs) advances[glyphId] else 0

    fun kerning(left: Int, right: Int): Int {
        if (left !in 0..0xFFFF || right !in 0..0xFFFF) return 0
        return kerningPairs[(left shl 16) or right] ?: 0
    }

    // ids stay stable for CIDToGIDMap /Identity, name and cmap stay since Java and GDI reject a font without them
    fun subset(glyphIds: Set<Int>): ByteArray {
        val kept = BooleanArray(numGlyphs)
        val pending = ArrayDeque<Int>()
        fun keep(glyph: Int) {
            if (glyph in 0 until numGlyphs && !kept[glyph]) {
                kept[glyph] = true
                pending.addLast(glyph)
            }
        }
        keep(0)
        glyphIds.forEach(::keep)
        while (pending.isNotEmpty()) forEachComponent(pending.removeFirst(), ::keep)

        val glyfOut = ByteArray((0 until numGlyphs).sumOf { if (kept[it]) padded4(glyphEnd(it) - loca[it]) else 0 })
        val locaOut = ByteArray(4 * (numGlyphs + 1))
        var pos = 0
        for (glyph in 0 until numGlyphs) {
            locaOut.putInt(4 * glyph, pos)
            if (kept[glyph]) {
                glyf.copyInto(glyfOut, pos, loca[glyph], glyphEnd(glyph))
                pos += padded4(glyphEnd(glyph) - loca[glyph])
            }
        }
        locaOut.putInt(4 * numGlyphs, pos)

        val tables = mutableListOf(
            "head" to head.bytes().also {
                it.putInt(8, 0)
                it.putShort(50, 1)
            },
            "hhea" to hhea.bytes(),
            "maxp" to maxp.bytes(),
            "hmtx" to hmtx.bytes(),
            "loca" to locaOut,
            "glyf" to glyfOut,
            "post" to ByteArray(POST_LENGTH).also {
                post?.copyInto(it, 0, 0, POST_LENGTH)
                it.putInt(0, 0x00030000)
            },
            "cmap" to cmapSubset(kept),
        )
        for (tag in listOf("cvt ", "fpgm", "prep", "OS/2")) optional(tag)?.let { tables += tag to it.bytes() }
        nameSubset()?.let { tables += "name" to it }
        return writeFont(tables)
    }

    private fun readDirectory(): Map<String, Entry> {
        val file = Table(data, 0, data.size, "Font header")
        when (file.tag(0)) {
            "OTTO" -> throw FontFormatException("CFF-flavoured OpenType (OTTO) is not supported, only TrueType outlines")
            "ttcf" -> throw FontFormatException("TrueType collections (ttcf) are not supported")
            "true", "\u0000\u0001\u0000\u0000" -> Unit
            else -> throw FontFormatException("Not a TrueType font")
        }
        return (0 until file.u16(4)).associate { i ->
            val record = 12 + 16 * i
            file.tag(record) to Entry(file.u32(record + 8), file.u32(record + 12))
        }
    }

    private fun required(tag: String, minLength: Int = 0): Table {
        val entry = directory[tag] ?: throw FontFormatException("Missing '$tag' table")
        if (entry.offset + entry.length > data.size + 3L) throw FontFormatException("Font data ends inside the '$tag' table")
        return table(tag, entry).also { if (it.length < minLength) throw FontFormatException("'$tag' table is truncated") }
    }

    private fun optional(tag: String): Table? = directory[tag]?.takeIf { it.offset < data.size }?.let { table(tag, it) }

    private fun table(tag: String, entry: Entry): Table {
        val start = minOf(entry.offset, data.size.toLong())
        val end = minOf(entry.offset + entry.length, data.size.toLong())
        return Table(data, start.toInt(), (end - start).toInt(), "'$tag' table")
    }

    private fun readLoca(): IntArray {
        val table = required("loca")
        val long = when (head.s16(50)) {
            0 -> false
            1 -> true
            else -> throw FontFormatException("Invalid indexToLocFormat")
        }
        var last = 0
        return IntArray(numGlyphs + 1) { i ->
            val offset = when {
                long && table.has(4 * i, 4) -> table.u32(4 * i)
                !long && table.has(2 * i, 2) -> 2L * table.u16(2 * i)
                else -> last.toLong()
            }
            last = minOf(offset, glyf.length.toLong()).toInt()
            last
        }
    }

    private fun readAdvances(): IntArray {
        val metrics = minOf(hhea.u16(34), numGlyphs)
        if (metrics == 0 || !hmtx.has(0, 2)) throw FontFormatException("Font has no horizontal metrics")
        var last = 0
        return IntArray(numGlyphs) { i ->
            if (i < metrics && hmtx.has(4 * i, 2)) last = hmtx.u16(4 * i)
            last
        }
    }

    private fun readCmap(): CharMap {
        val cmap = required("cmap", 4)
        val candidates = (0 until cmap.u16(2)).mapNotNull { i ->
            val record = 4 + 8 * i
            if (!cmap.has(record, 8)) return@mapNotNull null
            val platform = cmap.u16(record)
            val encoding = cmap.u16(record + 2)
            val rank = when {
                platform == 3 && encoding == 10 -> 0
                platform == 3 && encoding == 1 -> 1
                platform == 0 && encoding <= 6 && encoding != 5 -> 8 - encoding
                platform == 3 && encoding == 0 -> RANK_SYMBOL
                platform == 1 && encoding == 0 -> RANK_MAC_ROMAN
                else -> return@mapNotNull null
            }
            rank to cmap.u32(record + 4)
        }.sortedBy { it.first }
        for ((rank, offset) in candidates) {
            if (offset >= cmap.length) continue
            val map = try {
                subtable(cmap.slice(offset.toInt()))
            } catch (e: FontFormatException) {
                null
            } ?: continue
            return when (rank) {
                RANK_SYMBOL -> CharMap { code ->
                    val glyph = map.glyph(code)
                    if (glyph == 0 && code in 0x20..0xFF) map.glyph(0xF000 + code) else glyph
                }
                RANK_MAC_ROMAN -> CharMap { code ->
                    val mac = macRomanCode(code)
                    if (mac < 0) 0 else map.glyph(mac)
                }
                else -> map
            }
        }
        throw FontFormatException("No supported cmap subtable")
    }

    private fun subtable(t: Table): CharMap? = when (t.u16(0)) {
        0 -> CharMap { code -> if (code in 0..255 && t.has(6 + code, 1)) t.u8(6 + code) else 0 }
        4 -> format4(t)
        6 -> {
            val first = t.u16(6)
            val count = t.u16(8)
            CharMap { code ->
                val index = code - first
                if (index in 0 until count && t.has(10 + 2 * index, 2)) t.u16(10 + 2 * index) else 0
            }
        }
        12 -> format12(t, false)
        13 -> format12(t, true)
        else -> null
    }

    private fun format4(t: Table): CharMap {
        val segments = t.u16(6) / 2
        val ends = IntArray(segments) { t.u16(14 + 2 * it) }
        val starts = IntArray(segments) { t.u16(16 + 2 * segments + 2 * it) }
        val deltas = IntArray(segments) { t.u16(16 + 4 * segments + 2 * it) }
        val rangeOffsetsAt = 16 + 6 * segments
        val rangeOffsets = IntArray(segments) { t.u16(rangeOffsetsAt + 2 * it) }
        return CharMap { code ->
            val i = ends.firstAtLeast(code)
            when {
                code > 0xFFFF || i == segments || starts[i] > code -> 0
                rangeOffsets[i] == 0 -> (code + deltas[i]) and 0xFFFF
                else -> {
                    val at = rangeOffsetsAt + 2 * i + rangeOffsets[i] + 2 * (code - starts[i])
                    val glyph = if (t.has(at, 2)) t.u16(at) else 0
                    if (glyph == 0) 0 else (glyph + deltas[i]) and 0xFFFF
                }
            }
        }
    }

    private fun format12(t: Table, constant: Boolean): CharMap {
        val count = t.u32(12)
        if (count > (t.length - 16) / 12) throw FontFormatException("cmap format ${t.u16(0)} subtable is truncated")
        val groups = count.toInt()
        val starts = IntArray(groups) { minOf(t.u32(16 + 12 * it), 0x110000L).toInt() }
        val ends = IntArray(groups) { minOf(t.u32(20 + 12 * it), 0x110000L).toInt() }
        val glyphs = IntArray(groups) { minOf(t.u32(24 + 12 * it), 0x10000L).toInt() }
        return CharMap { code ->
            val i = ends.firstAtLeast(code)
            when {
                i == groups || starts[i] > code -> 0
                constant -> glyphs[i]
                else -> glyphs[i] + code - starts[i]
            }
        }
    }

    private fun readKern(): Map<Int, Int> {
        val kern = optional("kern") ?: return emptyMap()
        val apple = kern.has(0, 8) && kern.u32(0) == 0x00010000L
        val header = if (apple) 8 else 6
        var remaining = when {
            apple -> kern.u32(4)
            kern.has(0, 4) && kern.u16(0) == 0 -> kern.u16(2).toLong()
            else -> 0L
        }
        val pairs = HashMap<Int, Int>()
        var pos = if (apple) 8 else 4
        while (remaining-- > 0 && kern.has(pos, header + 8)) {
            val declared = if (apple) kern.u32(pos) else kern.u16(pos + 2).toLong()
            val coverage = kern.u16(pos + 4)
            val format = if (apple) coverage and 0xFF else coverage shr 8
            val horizontal = if (apple) coverage and 0xE000 == 0 else coverage and 0x7 == 1
            val override = !apple && coverage and 0x8 != 0
            val count = kern.u16(pos + header)
            if (format == 0 && horizontal) {
                for (i in 0 until count) {
                    val pair = pos + header + 8 + 6 * i
                    if (!kern.has(pair, 6)) break
                    val key = (kern.u16(pair) shl 16) or kern.u16(pair + 2)
                    val previous = if (override) 0 else pairs[key] ?: 0
                    pairs[key] = previous + kern.s16(pair + 4)
                }
            }
            val length = if (format == 0) maxOf(declared, header + 8 + 6L * count) else declared
            if (length <= 0 || pos + length >= kern.length) break
            pos += length.toInt()
        }
        return pairs
    }

    private fun name(id: Int): String? {
        val table = names?.takeIf { it.has(0, 6) } ?: return null
        val storage = table.u16(4)
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until table.u16(2)) {
            val record = 6 + 12 * i
            if (!table.has(record, 12) || table.u16(record + 6) != id) continue
            val rank = nameRank(table.u16(record), table.u16(record + 2), table.u16(record + 4))
            val length = table.u16(record + 8)
            val offset = storage + table.u16(record + 10)
            if (rank >= bestRank || !table.has(offset, length)) continue
            best = if (table.u16(record) == 1) {
                CharArray(length) { macRoman(table.u8(offset + it)) }.concatToString()
            } else {
                CharArray(length / 2) { Char(table.u16(offset + 2 * it)) }.concatToString()
            }
            bestRank = rank
        }
        return best
    }

    private fun glyphTop(char: Char): Int? {
        val glyph = glyphId(char.code)
        return if (glyph != 0 && glyphEnd(glyph) - loca[glyph] >= 10) glyf.s16(loca[glyph] + 8) else null
    }

    private fun glyphEnd(glyph: Int): Int = maxOf(loca[glyph], loca[glyph + 1])

    fun outline(glyphId: Int): List<GlyphContour> = try {
        outline(glyphId, 0)
    } catch (_: FontFormatException) {
        emptyList()
    }

    private fun outline(glyph: Int, depth: Int): List<GlyphContour> {
        if (glyph !in 0 until numGlyphs || depth > 8) return emptyList()
        val start = loca[glyph]
        val end = glyphEnd(glyph)
        if (end - start < 10) return emptyList()
        val count = glyf.s16(start)
        return if (count >= 0) simpleOutline(start, count) else compositeOutline(start, end, depth)
    }

    private fun simpleOutline(start: Int, count: Int): List<GlyphContour> {
        if (count == 0) return emptyList()
        val ends = IntArray(count) { glyf.u16(start + 10 + 2 * it) }
        val points = ends.last() + 1
        var pos = start + 10 + 2 * count
        pos += 2 + glyf.u16(pos)
        val flags = IntArray(points)
        var i = 0
        while (i < points) {
            val flag = glyf.u8(pos++)
            flags[i++] = flag
            if (flag and REPEAT_FLAG != 0) {
                repeat(glyf.u8(pos++)) { if (i < points) flags[i++] = flag }
            }
        }
        val xs = FloatArray(points)
        var x = 0
        for (k in 0 until points) {
            val flag = flags[k]
            x += when {
                flag and X_SHORT != 0 -> glyf.u8(pos++).let { if (flag and X_SAME_OR_POSITIVE != 0) it else -it }
                flag and X_SAME_OR_POSITIVE != 0 -> 0
                else -> glyf.s16(pos).also { pos += 2 }
            }
            xs[k] = x.toFloat()
        }
        val ys = FloatArray(points)
        var y = 0
        for (k in 0 until points) {
            val flag = flags[k]
            y += when {
                flag and Y_SHORT != 0 -> glyf.u8(pos++).let { if (flag and Y_SAME_OR_POSITIVE != 0) it else -it }
                flag and Y_SAME_OR_POSITIVE != 0 -> 0
                else -> glyf.s16(pos).also { pos += 2 }
            }
            ys[k] = y.toFloat()
        }
        var from = 0
        return ends.map { last ->
            val range = from..minOf(last, points - 1)
            from = last + 1
            GlyphContour(xs.sliceArray(range), ys.sliceArray(range), BooleanArray(range.count()) { flags[range.first + it] and ON_CURVE != 0 })
        }
    }

    private fun compositeOutline(start: Int, end: Int, depth: Int): List<GlyphContour> {
        val out = ArrayList<GlyphContour>()
        var pos = start + 10
        while (pos + 4 <= end) {
            val flags = glyf.u16(pos)
            val component = glyf.u16(pos + 2)
            pos += 4
            val words = flags and ARG_1_AND_2_ARE_WORDS != 0
            val dx: Int
            val dy: Int
            if (words) {
                dx = glyf.s16(pos)
                dy = glyf.s16(pos + 2)
                pos += 4
            } else {
                dx = glyf.u8(pos).toByte().toInt()
                dy = glyf.u8(pos + 1).toByte().toInt()
                pos += 2
            }
            var a = 1f
            var b = 0f
            var c = 0f
            var d = 1f
            when {
                flags and WE_HAVE_A_SCALE != 0 -> {
                    a = f2dot14(glyf.s16(pos))
                    d = a
                    pos += 2
                }
                flags and WE_HAVE_AN_X_AND_Y_SCALE != 0 -> {
                    a = f2dot14(glyf.s16(pos))
                    d = f2dot14(glyf.s16(pos + 2))
                    pos += 4
                }
                flags and WE_HAVE_A_TWO_BY_TWO != 0 -> {
                    a = f2dot14(glyf.s16(pos))
                    b = f2dot14(glyf.s16(pos + 2))
                    c = f2dot14(glyf.s16(pos + 4))
                    d = f2dot14(glyf.s16(pos + 6))
                    pos += 8
                }
            }
            val offsetX = if (flags and ARGS_ARE_XY_VALUES != 0) dx.toFloat() else 0f
            val offsetY = if (flags and ARGS_ARE_XY_VALUES != 0) dy.toFloat() else 0f
            for (contour in outline(component, depth + 1)) {
                out += GlyphContour(
                    FloatArray(contour.x.size) { a * contour.x[it] + c * contour.y[it] + offsetX },
                    FloatArray(contour.y.size) { b * contour.x[it] + d * contour.y[it] + offsetY },
                    contour.onCurve,
                )
            }
            if (flags and MORE_COMPONENTS == 0) break
        }
        return out
    }

    private fun f2dot14(value: Int): Float = value / 16384f

    private inline fun forEachComponent(glyph: Int, action: (Int) -> Unit) {
        val end = glyphEnd(glyph)
        var pos = loca[glyph]
        if (end - pos < 10 || glyf.s16(pos) >= 0) return
        pos += 10
        while (pos + 4 <= end) {
            val flags = glyf.u16(pos)
            action(glyf.u16(pos + 2))
            if (flags and MORE_COMPONENTS == 0) return
            pos += 4 + (if (flags and ARG_1_AND_2_ARE_WORDS != 0) 4 else 2) + when {
                flags and WE_HAVE_A_SCALE != 0 -> 2
                flags and WE_HAVE_AN_X_AND_Y_SCALE != 0 -> 4
                flags and WE_HAVE_A_TWO_BY_TWO != 0 -> 8
                else -> 0
            }
        }
    }

    private fun cmapSubset(kept: BooleanArray): ByteArray {
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()
        val deltas = ArrayList<Int>()
        for (code in 0 until 0xFFFF) {
            val glyph = glyphId(code)
            if (glyph == 0 || !kept[glyph]) continue
            val delta = (glyph - code) and 0xFFFF
            if (ends.isNotEmpty() && ends.last() == code - 1 && deltas.last() == delta) {
                ends[ends.lastIndex] = code
            } else {
                if (starts.size == MAX_CMAP_SEGMENTS - 1) break
                starts += code
                ends += code
                deltas += delta
            }
        }
        starts += 0xFFFF
        ends += 0xFFFF
        deltas += 1

        val segments = starts.size
        val searchRange = 2 * segments.takeHighestOneBit()
        val out = ByteArray(12 + 16 + 8 * segments)
        out.putShort(2, 1)
        out.putShort(4, 3)
        out.putShort(6, 1)
        out.putInt(8, 12)
        out.putShort(12, 4)
        out.putShort(14, 16 + 8 * segments)
        out.putShort(18, 2 * segments)
        out.putShort(20, searchRange)
        out.putShort(22, searchRange.countTrailingZeroBits() - 1)
        out.putShort(24, 2 * segments - searchRange)
        for (i in 0 until segments) {
            out.putShort(26 + 2 * i, ends[i])
            out.putShort(28 + 2 * segments + 2 * i, starts[i])
            out.putShort(28 + 4 * segments + 2 * i, deltas[i])
        }
        return out
    }

    private fun nameSubset(): ByteArray? {
        val table = names?.takeIf { it.has(0, 6) } ?: return null
        val storage = table.u16(4)
        val records = (0 until table.u16(2)).map { 6 + 12 * it }.filter { record ->
            table.has(record, 12) &&
                table.u16(record + 6) in SUBSET_NAME_IDS &&
                nameRank(table.u16(record), table.u16(record + 2), table.u16(record + 4)) in ENGLISH_NAME_RANKS &&
                table.has(storage + table.u16(record + 10), table.u16(record + 8))
        }
        if (records.isEmpty()) return null
        val stringsAt = 6 + 12 * records.size
        val out = ByteArray(stringsAt + records.sumOf { table.u16(it + 8) })
        out.putShort(2, records.size)
        out.putShort(4, stringsAt)
        var pos = 0
        records.forEachIndexed { i, record ->
            val length = table.u16(record + 8)
            val offset = storage + table.u16(record + 10)
            table.copyInto(out, 6 + 12 * i, record, record + 8)
            out.putShort(6 + 12 * i + 8, length)
            out.putShort(6 + 12 * i + 10, pos)
            table.copyInto(out, stringsAt + pos, offset, offset + length)
            pos += length
        }
        return out
    }
}

private class Entry(val offset: Long, val length: Long)

private fun interface CharMap {
    fun glyph(code: Int): Int
}

private class Table(private val data: ByteArray, private val offset: Int, val length: Int, private val label: String) {
    fun has(pos: Int, size: Int): Boolean = pos >= 0 && size >= 0 && pos <= length - size

    fun u8(pos: Int): Int = data[at(pos, 1)].toInt() and 0xFF

    fun u16(pos: Int): Int {
        val p = at(pos, 2)
        return ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
    }

    fun s16(pos: Int): Int = u16(pos).toShort().toInt()

    fun s32(pos: Int): Int {
        val p = at(pos, 4)
        return ((data[p].toInt() and 0xFF) shl 24) or ((data[p + 1].toInt() and 0xFF) shl 16) or
            ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
    }

    fun u32(pos: Int): Long = s32(pos).toLong() and 0xFFFFFFFFL

    fun tag(pos: Int): String = CharArray(4) { Char(u8(pos + it)) }.concatToString()

    fun slice(pos: Int): Table = Table(data, offset + pos, length - pos, label)

    fun padded(size: Int): Table = Table(bytes().copyOf(maxOf(size, length)), 0, maxOf(size, length), label)

    fun bytes(): ByteArray = data.copyOfRange(offset, offset + length)

    fun copyInto(target: ByteArray, targetPos: Int, from: Int, to: Int) {
        data.copyInto(target, targetPos, offset + from, offset + minOf(to, length))
    }

    private fun at(pos: Int, size: Int): Int {
        if (!has(pos, size)) throw FontFormatException("$label is truncated")
        return offset + pos
    }
}

private fun writeFont(tables: List<Pair<String, ByteArray>>): ByteArray {
    val sorted = tables.sortedBy { it.first }
    val headerSize = 12 + 16 * sorted.size
    val out = ByteArray(headerSize + sorted.sumOf { padded4(it.second.size) })
    val power = sorted.size.takeHighestOneBit()
    out.putInt(0, 0x00010000)
    out.putShort(4, sorted.size)
    out.putShort(6, 16 * power)
    out.putShort(8, power.countTrailingZeroBits())
    out.putShort(10, 16 * (sorted.size - power))
    var pos = headerSize
    var headAt = 0
    sorted.forEachIndexed { i, (tag, bytes) ->
        val record = 12 + 16 * i
        tag.encodeToByteArray().copyInto(out, record)
        out.putInt(record + 4, checksum(bytes, bytes.size))
        out.putInt(record + 8, pos)
        out.putInt(record + 12, bytes.size)
        bytes.copyInto(out, pos)
        if (tag == "head") headAt = pos
        pos += padded4(bytes.size)
    }
    out.putInt(headAt + 8, CHECKSUM_MAGIC - checksum(out, out.size))
    return out
}

private fun checksum(bytes: ByteArray, length: Int): Int {
    var sum = 0
    for (word in 0 until (length + 3) / 4) {
        var value = 0
        for (i in 4 * word until 4 * word + 4) value = (value shl 8) or (if (i < length) bytes[i].toInt() and 0xFF else 0)
        sum += value
    }
    return sum
}

private fun padded4(length: Int): Int = (length + 3) and 3.inv()

private fun ByteArray.putShort(pos: Int, value: Int) {
    this[pos] = (value shr 8).toByte()
    this[pos + 1] = value.toByte()
}

private fun ByteArray.putInt(pos: Int, value: Int) {
    putShort(pos, value shr 16)
    putShort(pos + 2, value)
}

private fun IntArray.firstAtLeast(value: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] < value) low = mid + 1 else high = mid
    }
    return low
}

private fun nameRank(platform: Int, encoding: Int, language: Int): Int = when {
    platform == 3 && (encoding == 1 || encoding == 10) -> if (language == 0x409) 0 else 1
    platform == 3 && encoding == 0 -> if (language == 0x409) 2 else 3
    platform == 0 -> 4
    platform == 1 && encoding == 0 -> if (language == 0) 5 else 6
    else -> Int.MAX_VALUE
}

private fun macRoman(byte: Int): Char = if (byte < 0x80) Char(byte) else MAC_ROMAN[byte - 0x80]

private fun macRomanCode(codePoint: Int): Int = when {
    codePoint < 0x80 -> codePoint
    codePoint > 0xFFFF -> -1
    else -> MAC_ROMAN.indexOf(Char(codePoint)).let { if (it < 0) -1 else 0x80 + it }
}

private const val MAC_ROMAN =
    "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø" +
        "¿¡¬√ƒ≈∆«»… ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ"

private const val OS2_LENGTH = 100
private const val POST_LENGTH = 32
private const val USE_TYPO_METRICS = 0x80
private const val RANK_SYMBOL = 9
private const val RANK_MAC_ROMAN = 10
private const val MAX_CMAP_SEGMENTS = 8189
private const val CHECKSUM_MAGIC = 0xB1B0AFBA.toInt()

private const val ARG_1_AND_2_ARE_WORDS = 0x0001
private const val ARGS_ARE_XY_VALUES = 0x0002
private const val WE_HAVE_A_SCALE = 0x0008
private const val MORE_COMPONENTS = 0x0020
private const val WE_HAVE_AN_X_AND_Y_SCALE = 0x0040
private const val WE_HAVE_A_TWO_BY_TWO = 0x0080

private const val ON_CURVE = 0x01
private const val X_SHORT = 0x02
private const val Y_SHORT = 0x04
private const val REPEAT_FLAG = 0x08
private const val X_SAME_OR_POSITIVE = 0x10
private const val Y_SAME_OR_POSITIVE = 0x20

private val SUBSET_NAME_IDS = setOf(1, 2, 3, 4, 6, 16, 17)
private val ENGLISH_NAME_RANKS = setOf(0, 2, 4, 5)
