package com.vasmarfas.card.tools.documents.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    operator fun times(o: Matrix) = Matrix(
        a * o.a + b * o.c,
        a * o.b + b * o.d,
        c * o.a + d * o.c,
        c * o.b + d * o.d,
        e * o.a + f * o.c + o.e,
        e * o.b + f * o.d + o.f,
    )

    fun translate(tx: Double, ty: Double) = Matrix(a, b, c, d, tx * a + ty * c + e, tx * b + ty * d + f)

    fun x(px: Double, py: Double) = a * px + c * py + e

    fun y(px: Double, py: Double) = b * px + d * py + f

    fun inverse(): Matrix? {
        val det = a * d - b * c
        if (abs(det) < 1e-12) return null
        return Matrix(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
    }

    fun operands(): String = listOf(a, b, c, d, e, f).joinToString(" ") { formatReal(it) }

    companion object {
        val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun of(v: DoubleArray) = Matrix(v[0], v[1], v[2], v[3], v[4], v[5])
    }
}

internal class GraphicsState(
    var ctm: Matrix = Matrix.IDENTITY,
    var font: PdfFont? = null,
    var fontName: String? = null,
    var fontSize: Double = 0.0,
    var charSpacing: Double = 0.0,
    var wordSpacing: Double = 0.0,
    var scale: Double = 1.0,
    var leading: Double = 0.0,
    var rise: Double = 0.0,
    var render: Int = 0,
    var color: Int = BLACK,
    var lineWidth: Double = 1.0,
    var fill: String = "",
    var stroke: String = "",
    var states: List<String> = emptyList(),
) {
    fun copy() = GraphicsState(ctm, font, fontName, fontSize, charSpacing, wordSpacing, scale, leading, rise, render, color, lineWidth, fill, stroke, states)
}

private const val BLACK = 0xFF000000.toInt()

private const val MAX_FORM_OPERATORS = 5_000_000

internal val contentOperators = setOf(
    "b", "B", "b*", "B*", "BDC", "BI", "BMC", "BT", "BX", "c", "cm", "CS", "cs", "d", "d0", "d1", "Do", "DP", "EI", "EMC", "ET", "EX",
    "f", "F", "f*", "G", "g", "gs", "h", "i", "ID", "j", "J", "K", "k", "l", "m", "M", "MP", "n", "q", "Q", "re", "RG", "rg", "ri", "s",
    "S", "SC", "sc", "SCN", "scn", "sh", "T*", "Tc", "Td", "TD", "Tf", "Tj", "TJ", "TL", "Tm", "Tr", "Ts", "Tw", "Tz", "v", "w", "W", "W*",
    "y", "'", "\"",
)

private val paintOperators = setOf("S", "s", "f", "F", "f*", "B", "B*", "b", "b*")

private val strokingPaint = setOf("S", "s", "B", "B*", "b", "b*")

internal fun pageContentData(doc: PdfDocument, page: PdfPage): ByteArray {
    val contents = when (val value = doc.resolve(page.dict["Contents"])) {
        is PdfStream -> listOf(value)
        is PdfArray -> value.items.mapNotNull { doc.resolve(it) as? PdfStream }
        else -> emptyList()
    }
    if (contents.size == 1) return decodeOrEmpty(doc, contents[0])
    val sink = ByteSink()
    for (stream in contents) {
        sink.write(decodeOrEmpty(doc, stream))
        sink.write('\n'.code)
    }
    return sink.toByteArray()
}

private fun decodeOrEmpty(doc: PdfDocument, stream: PdfStream): ByteArray = try {
    PdfFilters.decode(stream.data, stream.dict, doc, lenient = true)
} catch (_: PdfException) {
    ByteArray(0)
}

internal class ContentOp(val name: String, val operands: List<PdfObject>, val start: Int, val end: Int)

enum class GraphicKind { IMAGE, FORM, PATH }

class PageGraphic internal constructor(val first: Int, internal val last: Int, val kind: GraphicKind, val bounds: PdfRect, internal val ctm: Matrix)

class TextRun internal constructor(val op: Int, val glyphs: List<PageGlyph>, internal val placeholder: String, internal val alone: Lazy<String>?)

class PageContent internal constructor(
    private val data: ByteArray,
    private val ops: List<ContentOp>,
    val runs: List<TextRun>,
    val graphics: List<PageGraphic>,
) {
    fun rewrite(removed: Set<Int>, moved: Map<Int, DoubleArray>): ByteArray {
        val runs = runs.associateBy { it.op }
        val graphics = graphics.associateBy { it.first }
        val out = ByteSink(data.size + 256)
        val tail = StringBuilder()
        var copied = 0
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            val graphic = graphics[i]
            val run = runs[i]
            if (graphic != null && (i in removed || i in moved)) {
                val last = ops[graphic.last]
                out.write(data, copied, op.start - copied)
                val move = moved[i]
                val local = graphic.ctm.inverse()
                if (i !in removed && move != null && local != null) {
                    out.writeAscii("q ${(graphic.ctm * Matrix.of(move) * local).operands()} cm\n")
                    out.write(data, op.start, last.end - op.start)
                    out.writeAscii("\nQ\n")
                }
                copied = last.end
                i = graphic.last + 1
                continue
            }
            if (run != null && (i in removed || i in moved)) {
                out.write(data, copied, op.start - copied)
                out.writeAscii(run.placeholder)
                copied = op.end
                val move = moved[i]
                if (i !in removed && move != null && run.alone != null) tail.append("q ").append(Matrix.of(move).operands()).append(" cm ").append(run.alone.value).append(" Q\n")
            }
            i++
        }
        out.write(data, copied, data.size - copied)
        if (tail.isEmpty()) return out.toByteArray()
        val framed = ByteSink(out.size + tail.length + 8)
        framed.writeAscii("q\n")
        out.copyInto(framed, 0, out.size)
        framed.writeAscii("\nQ\n")
        framed.writeAscii(tail.toString())
        return framed.toByteArray()
    }

    companion object {
        fun of(page: PdfPage): PageContent {
            val analysis = ContentAnalysis()
            ContentInterpreter(page.document, analysis, analysis).run(page)
            return PageContent(analysis.data, analysis.ops, analysis.runs, analysis.graphics)
        }
    }
}

internal class ContentAnalysis : GlyphSink {
    var data = ByteArray(0)
    val ops = ArrayList<ContentOp>()
    val runs = ArrayList<TextRun>()
    val graphics = ArrayList<PageGraphic>()
    var collected: ArrayList<PageGlyph>? = null

    override fun glyph(text: String?, startX: Double, startY: Double, stopX: Double, stopY: Double, fontSize: Double, spaceWidth: Double, dx: Double, dy: Double, color: Int, style: FontStyle) {
        collected?.add(PageGlyph(if (text.isNullOrEmpty()) "�" else text, startX, startY, stopX, stopY, fontSize, dx, dy, color, style))
    }

    fun graphic(first: Int, last: Int, kind: GraphicKind, points: DoubleArray, pad: Double, ctm: Matrix) {
        var left = Double.MAX_VALUE
        var bottom = Double.MAX_VALUE
        var right = -Double.MAX_VALUE
        var top = -Double.MAX_VALUE
        for (k in 0 until points.size step 2) {
            left = min(left, points[k])
            right = max(right, points[k])
            bottom = min(bottom, points[k + 1])
            top = max(top, points[k + 1])
        }
        if (left > right) return
        graphics += PageGraphic(first, last, kind, PdfRect(left - pad, bottom - pad, right + pad, top + pad), ctm)
    }
}

internal class ContentInterpreter(private val doc: PdfDocument, private val sink: GlyphSink, private val analysis: ContentAnalysis? = null) {
    private var state = GraphicsState()
    private val saved = ArrayList<GraphicsState>()
    private var tm = Matrix.IDENTITY
    private var tlm = Matrix.IDENTITY
    private val forms = ArrayList<PdfStream>()
    private var source = ""
    private var index = -1
    private var pathFirst = -1
    private var clip = false
    private val path = ArrayList<Double>()
    private var budget = MAX_FORM_OPERATORS

    fun run(page: PdfPage) {
        val data = pageContentData(doc, page)
        analysis?.data = data
        execute(data, page.resources)
    }

    private fun execute(data: ByteArray, resources: PdfDict) {
        val nested = forms.isNotEmpty()
        val top = if (nested) null else analysis
        val parser = PdfParser(data, doc.names, allowRefs = false)
        val lexer = parser.lexer
        val operands = ArrayList<PdfObject>()
        var start = -1
        while (!nested || budget > 0) {
            lexer.skipWhitespace()
            val c = lexer.peek()
            if (c < 0) break
            if (start < 0) start = lexer.pos
            if (c == '{'.code || c == '}'.code) {
                lexer.pos++
                continue
            }
            if (!isRegular(c) || lexer.startsNumber(c)) {
                operands.add(parser.parseObject())
                if (operands.size > 10_000) operands.clear()
                continue
            }
            when (val op = lexer.readRegular()) {
                "true" -> operands.add(PdfBoolean.of(true))
                "false" -> operands.add(PdfBoolean.of(false))
                "null" -> operands.add(PdfNull)
                "BI" -> {
                    skipInlineImage(parser, doc)
                    if (top != null) {
                        top.ops += ContentOp("BI", emptyList(), start, lexer.pos)
                        top.graphic(top.ops.lastIndex, top.ops.lastIndex, GraphicKind.IMAGE, unitSquare(state.ctm), 0.0, state.ctm)
                    }
                    operands.clear()
                    start = -1
                }
                else -> {
                    if (nested) budget--
                    if (top != null) {
                        top.ops += ContentOp(op, operands.toList(), start, lexer.pos)
                        index = top.ops.lastIndex
                        source = latin1(data, start, lexer.pos)
                    }
                    operator(op, operands, resources, top)
                    operands.clear()
                    start = -1
                }
            }
        }
    }

    private fun number(args: List<PdfObject>, fromEnd: Int): Double = args.getOrNull(args.size - 1 - fromEnd).asDouble() ?: 0.0

    private fun matrix(args: List<PdfObject>): Matrix? {
        if (args.size < 6) return null
        val v = DoubleArray(6) { args[args.size - 6 + it].asDouble() ?: return null }
        return Matrix.of(v)
    }

    private fun operator(op: String, args: List<PdfObject>, resources: PdfDict, top: ContentAnalysis?) {
        when (op) {
            "q" -> saved.add(state.copy())
            "Q" -> if (saved.isNotEmpty()) state = saved.removeAt(saved.size - 1)
            "cm" -> matrix(args)?.let { state.ctm = it * state.ctm }
            "BT" -> {
                tm = Matrix.IDENTITY
                tlm = Matrix.IDENTITY
            }
            "Tf" -> {
                val name = args.getOrNull(args.size - 2) as? PdfName
                state.font = font(resources, name)
                state.fontName = name?.name
                state.fontSize = number(args, 0)
            }
            "Td" -> moveText(number(args, 1), number(args, 0))
            "TD" -> {
                state.leading = -number(args, 0)
                moveText(number(args, 1), number(args, 0))
            }
            "Tm" -> matrix(args)?.let {
                tm = it
                tlm = it
            }
            "T*" -> moveText(0.0, -state.leading)
            "TL" -> state.leading = number(args, 0)
            "Tc" -> state.charSpacing = number(args, 0)
            "Tw" -> state.wordSpacing = number(args, 0)
            "Tz" -> state.scale = number(args, 0) / 100
            "Ts" -> state.rise = number(args, 0)
            "Tr" -> state.render = number(args, 0).toInt()
            "Tj", "TJ", "'", "\"" -> text(op, args, top)
            "Do" -> (args.lastOrNull() as? PdfName)?.let { xobject(resources, it.name, top) }
            "g" -> state.color = gray(number(args, 0))
            "rg" -> state.color = rgb(number(args, 2), number(args, 1), number(args, 0))
            "k" -> state.color = cmyk(number(args, 3), number(args, 2), number(args, 1), number(args, 0))
            "sc", "scn" -> state.color = when (args.count { it is PdfInt || it is PdfReal }) {
                1 -> gray(number(args, 0))
                3 -> rgb(number(args, 2), number(args, 1), number(args, 0))
                4 -> cmyk(number(args, 3), number(args, 2), number(args, 1), number(args, 0))
                else -> state.color
            }
            "cs" -> state.color = BLACK
            "w" -> state.lineWidth = number(args, 0)
        }
        if (top != null) track(op, args, top)
    }

    // what only the analysis needs: the operators behind colours and states, and the extent of paths
    private fun track(op: String, args: List<PdfObject>, top: ContentAnalysis) {
        when (op) {
            "g", "rg", "k" -> state.fill = ""
            "cs" -> state.fill = source
            "sc", "scn" -> if (state.fill.isNotEmpty()) state.fill = state.fill.substringBefore(" cs") + " cs " + source
            "G", "RG", "K", "CS" -> state.stroke = source
            "SC", "SCN" -> state.stroke = state.stroke.substringBefore(" CS", "").let { if (it.isEmpty()) source else "$it CS $source" }
            "gs" -> (args.lastOrNull() as? PdfName)?.let { state.states = state.states - it.name + it.name }
            "m", "l" -> point(number(args, 1), number(args, 0))
            "c" -> for (k in 0 until 3) point(number(args, 5 - 2 * k), number(args, 4 - 2 * k))
            "v", "y" -> for (k in 0 until 2) point(number(args, 3 - 2 * k), number(args, 2 - 2 * k))
            "re" -> {
                val x = number(args, 3)
                val y = number(args, 2)
                val w = number(args, 1)
                val h = number(args, 0)
                point(x, y)
                point(x + w, y)
                point(x, y + h)
                point(x + w, y + h)
            }
            "h" -> Unit
            "W", "W*" -> clip = true
            "n" -> endPath()
            in paintOperators -> {
                if (pathFirst >= 0 && !clip) {
                    val ctm = state.ctm
                    val pad = if (op in strokingPaint) state.lineWidth * sqrt(abs(ctm.a * ctm.d - ctm.b * ctm.c)) / 2 else 0.0
                    top.graphic(pathFirst, index, GraphicKind.PATH, path.toDoubleArray(), pad, ctm)
                }
                endPath()
            }
        }
    }

    private fun point(x: Double, y: Double) {
        if (pathFirst < 0) pathFirst = index
        val ctm = state.ctm
        path += ctm.x(x, y)
        path += ctm.y(x, y)
    }

    private fun endPath() {
        pathFirst = -1
        clip = false
        path.clear()
    }

    private fun unitSquare(m: Matrix) = doubleArrayOf(m.x(0.0, 0.0), m.y(0.0, 0.0), m.x(1.0, 0.0), m.y(1.0, 0.0), m.x(0.0, 1.0), m.y(0.0, 1.0), m.x(1.0, 1.0), m.y(1.0, 1.0))

    private fun text(op: String, args: List<PdfObject>, top: ContentAnalysis?) {
        if (op == "\"") {
            state.wordSpacing = number(args, 2)
            state.charSpacing = number(args, 1)
        }
        if (op == "'" || op == "\"") moveText(0.0, -state.leading)
        val before = tm
        val glyphs = if (top != null) ArrayList<PageGlyph>() else null
        top?.collected = glyphs
        val shown = args.lastOrNull()
        val advance = when {
            op == "TJ" && shown is PdfArray -> showArray(shown)
            shown is PdfString -> show(shown.bytes)
            else -> 0.0
        }
        top?.collected = null
        if (top == null || glyphs == null || shown == null) return
        val vertical = state.font?.vertical == true
        val unit = if (vertical) state.fontSize else state.fontSize * state.scale
        val empty = if (unit != 0.0 && advance != 0.0) "[${formatReal(-advance * 1000 / unit)}] TJ" else ""
        val placeholder = when (op) {
            "'" -> "T* $empty"
            "\"" -> "${formatReal(state.wordSpacing)} Tw ${formatReal(state.charSpacing)} Tc T* $empty"
            else -> empty
        }
        val alone = state.fontName?.let { font ->
            val at = state.copy()
            lazy(LazyThreadSafetyMode.NONE) {
                val show = latin1(PdfSyntax.serialize(shown)) + if (op == "TJ") " TJ" else " Tj"
                val fill = at.fill.ifEmpty { colorOperands(at.color) + " rg" }
                val stroke = if (at.render % 4 == 1 || at.render % 4 == 2) "${at.stroke} ${formatReal(at.lineWidth)} w " else ""
                val states = at.states.joinToString("") { "${name(it)} gs " }
                "q ${at.ctm.operands()} cm $states$fill $stroke" +
                    "BT ${name(font)} ${formatReal(at.fontSize)} Tf ${formatReal(at.charSpacing)} Tc ${formatReal(at.wordSpacing)} Tw " +
                    "${formatReal(at.scale * 100)} Tz ${formatReal(at.rise)} Ts ${at.render} Tr ${before.operands()} Tm $show ET Q"
            }
        }
        top.runs += TextRun(index, glyphs, placeholder, alone)
    }

    private fun name(value: String) = latin1(PdfSyntax.serialize(PdfName(value)))

    private fun colorOperands(argb: Int) = listOf(16, 8, 0).joinToString(" ") { formatReal(((argb shr it) and 0xFF) / 255.0) }

    private fun channel(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()

    private fun gray(v: Double) = rgb(v, v, v)

    private fun rgb(r: Double, g: Double, b: Double) = (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

    private fun cmyk(c: Double, m: Double, y: Double, k: Double) = rgb((1 - c) * (1 - k), (1 - m) * (1 - k), (1 - y) * (1 - k))

    private fun moveText(tx: Double, ty: Double) {
        tlm = tlm.translate(tx, ty)
        tm = tlm
    }

    private fun font(resources: PdfDict, name: PdfName?): PdfFont? {
        if (name == null) return null
        val entry = resources.dict("Font", doc)?.get(name.name) ?: return null
        return try {
            PdfFonts.load(doc, entry)
        } catch (_: PdfException) {
            null
        }
    }

    private fun show(bytes: ByteArray): Double {
        val font = state.font ?: return 0.0
        val size = state.fontSize
        val scale = state.scale
        val rise = state.rise
        val ctm = state.ctm
        var total = 0.0
        var pos = 0
        while (pos < bytes.size) {
            val length = font.read(bytes, pos)
            val code = font.code
            pos += length
            val w0 = font.width(code, length)
            val spacing = state.charSpacing + if (length == 1 && code == 32L) state.wordSpacing else 0.0
            val m = tm * ctm
            val unit = sqrt(m.c * m.c + m.d * m.d)
            val userSize = abs(size) * (if (unit > 0) unit else 1.0)
            val startX = rise * m.c + m.e
            val startY = rise * m.d + m.f
            val space = (if (font.spaceWidth > 0) font.spaceWidth else 0.25) * userSize * scale
            if (font.vertical) {
                val advance = font.advanceY(code) * size + spacing
                val len = if (unit > 0) unit else 1.0
                sink.glyph(font.text(code, length), startX, startY, startX + advance * m.c, startY + advance * m.d, userSize, space, -m.c / len, -m.d / len, state.color, font.style)
                tm = tm.translate(0.0, advance)
                total += advance
            } else {
                val width = (w0 * size + max(state.charSpacing, 0.0)) * scale
                val len = sqrt(m.a * m.a + m.b * m.b).let { if (it > 0) it else 1.0 }
                sink.glyph(font.text(code, length), startX, startY, startX + width * m.a, startY + width * m.b, userSize, space, m.a / len, m.b / len, state.color, font.style)
                val advance = (w0 * size + spacing) * scale
                tm = tm.translate(advance, 0.0)
                total += advance
            }
        }
        return total
    }

    private fun showArray(array: PdfArray): Double {
        var total = 0.0
        for (item in array.items) {
            when (item) {
                is PdfString -> total += show(item.bytes)
                is PdfInt, is PdfReal -> {
                    val adjust = (item.asDouble() ?: 0.0) / 1000 * state.fontSize
                    if (state.font?.vertical == true) {
                        tm = tm.translate(0.0, -adjust)
                        total -= adjust
                    } else {
                        tm = tm.translate(-adjust * state.scale, 0.0)
                        total -= adjust * state.scale
                    }
                }
                else -> Unit
            }
        }
        return total
    }

    private fun xobject(resources: PdfDict, name: String, top: ContentAnalysis?) {
        val stream = resources.dict("XObject", doc)?.resolved(name, doc) as? PdfStream ?: return
        val subtype = stream.dict.name("Subtype", doc)
        if (top != null && subtype == "Image") top.graphic(index, index, GraphicKind.IMAGE, unitSquare(state.ctm), 0.0, state.ctm)
        if (subtype != "Form" || forms.size >= 12 || forms.any { it === stream }) return
        val formMatrix = stream.dict.array("Matrix", doc)?.let { matrix(it.items) }
        if (top != null) {
            val m = (formMatrix ?: Matrix.IDENTITY) * state.ctm
            rectOf(stream.dict.array("BBox", doc), doc)?.let { box ->
                val corners = doubleArrayOf(
                    m.x(box.left, box.bottom), m.y(box.left, box.bottom), m.x(box.right, box.bottom), m.y(box.right, box.bottom),
                    m.x(box.left, box.top), m.y(box.left, box.top), m.x(box.right, box.top), m.y(box.right, box.top),
                )
                top.graphic(index, index, GraphicKind.FORM, corners, 0.0, state.ctm)
            }
        }
        val data = decodeOrEmpty(doc, stream)
        val outer = state.copy()
        val depth = saved.size
        val outerTm = tm
        val outerTlm = tlm
        forms.add(stream)
        formMatrix?.let { state.ctm = it * state.ctm }
        execute(data, stream.dict.dict("Resources", doc) ?: resources)
        forms.removeAt(forms.size - 1)
        state = outer
        while (saved.size > depth) saved.removeAt(saved.size - 1)
        tm = outerTm
        tlm = outerTlm
    }
}

internal fun skipInlineImage(parser: PdfParser, doc: PdfDocument) {
    val lexer = parser.lexer
    val dict = PdfDict()
    while (true) {
        lexer.skipWhitespace()
        val c = lexer.peek()
        if (c < 0) return
        if (c == '/'.code) {
            val key = lexer.readName()
            dict[key] = parser.parseObject()
            continue
        }
        if (lexer.readKeyword("ID")) break
        parser.parseObject()
    }
    if (lexer.pos < lexer.end && isWhitespace(lexer.peek())) lexer.pos++
    lexer.pos = inlineImageEnd(lexer, dict, doc)
}

private fun inlineImageEnd(lexer: PdfLexer, dict: PdfDict, doc: PdfDocument): Int {
    val data = lexer.data
    val start = lexer.pos
    val end = lexer.end
    val filters = PdfFilters.filterNames(dict, null)
    val known = when {
        filters.isEmpty() -> rawImageLength(dict).let { if (it >= 0 && start + it <= end) (start + it).toInt() else -1 }
        filters[0] == "ASCIIHexDecode" || filters[0] == "AHx" -> lexer.indexOf(">", start).let { if (it >= 0) it + 1 else -1 }
        filters[0] == "ASCII85Decode" || filters[0] == "A85" -> lexer.indexOf("~>", start).let { if (it >= 0) it + 2 else -1 }
        else -> -1
    }
    if (known >= 0) {
        var i = known
        while (i < end && isWhitespace(data[i].toInt())) i++
        if (lexer.matchesAt(i, "EI")) return i + 2
    }
    var from = start
    while (true) {
        val at = lexer.indexOf("EI", from)
        if (at < 0) return end
        val before = at == start || isWhitespace(data[at - 1].toInt())
        if (before && lexer.matchesAt(at, "EI") && plausibleContent(data, at + 2, end, doc)) return at + 2
        from = at + 1
    }
}

private fun rawImageLength(dict: PdfDict): Long {
    val width = (dict["W"] ?: dict["Width"]).asInt() ?: return -1
    val height = (dict["H"] ?: dict["Height"]).asInt() ?: return -1
    val mask = (dict["IM"] ?: dict["ImageMask"]) == PdfBoolean.of(true)
    val bits = if (mask) 1 else (dict["BPC"] ?: dict["BitsPerComponent"]).asInt() ?: 8
    val components = if (mask) {
        1
    } else {
        when (val space = dict["CS"] ?: dict["ColorSpace"]) {
            is PdfName -> when (space.name) {
                "G", "DeviceGray", "CalGray", "I", "Indexed" -> 1
                "RGB", "DeviceRGB", "CalRGB" -> 3
                "CMYK", "DeviceCMYK" -> 4
                else -> return -1
            }
            is PdfArray -> if ((space.items.firstOrNull() as? PdfName)?.name in setOf("I", "Indexed")) 1 else return -1
            else -> return -1
        }
    }
    return height.toLong() * ((width.toLong() * components * bits + 7) / 8)
}

private fun plausibleContent(data: ByteArray, from: Int, end: Int, doc: PdfDocument): Boolean {
    val lexer = PdfLexer(data, doc.names, from, end)
    repeat(8) {
        lexer.skipWhitespace()
        val c = lexer.peek()
        if (c < 0) return true
        if (c < 0x20 || c > 0x7E) return false
        when {
            lexer.startsNumber(c) -> if (!lexer.readNumber()) return false
            c == '/'.code -> lexer.readName()
            c == '('.code -> lexer.readLiteralString()
            c == '<'.code -> if (lexer.peekAt(1) == '<'.code) lexer.pos += 2 else lexer.readHexString()
            isRegular(c) -> return lexer.readRegular() in contentOperators
            else -> lexer.pos++
        }
    }
    return true
}
