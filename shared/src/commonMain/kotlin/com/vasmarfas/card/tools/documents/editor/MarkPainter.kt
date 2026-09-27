package com.vasmarfas.card.tools.documents.editor

import com.vasmarfas.card.tools.documents.Align
import com.vasmarfas.card.tools.documents.DocFonts
import com.vasmarfas.card.tools.documents.Faces
import com.vasmarfas.card.tools.documents.FontFamily
import com.vasmarfas.card.tools.documents.Line
import com.vasmarfas.card.tools.documents.LineBreaker
import com.vasmarfas.card.tools.documents.PieceBuilder
import com.vasmarfas.card.tools.documents.RunStyle
import com.vasmarfas.card.tools.documents.hex4
import com.vasmarfas.card.tools.documents.pdf.PdfArray
import com.vasmarfas.card.tools.documents.pdf.PdfDict
import com.vasmarfas.card.tools.documents.pdf.PdfInt
import com.vasmarfas.card.tools.documents.pdf.PdfName
import com.vasmarfas.card.tools.documents.pdf.PdfObject
import com.vasmarfas.card.tools.documents.pdf.PdfReal
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.tools.documents.pdf.PdfRef
import com.vasmarfas.card.tools.documents.pdf.PdfWriter
import com.vasmarfas.card.tools.documents.pdf.formatReal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin

internal const val LINE_SPACING = 1.2f

class MarkFonts(val fonts: DocFonts) {
    fun family(font: MarkFont): FontFamily = when (font) {
        MarkFont.SANS -> fonts.headings
        MarkFont.SERIF -> fonts.body
        MarkFont.MONO -> fonts.code
    }

    val fallbacks: List<FontFamily> get() = listOf(fonts.headings, fonts.body, fonts.code)
}

internal class TextLayout(val lines: List<Line>, val inset: Float, val width: Float, val height: Float)

internal fun layoutText(
    faces: Faces,
    fonts: MarkFonts,
    text: String,
    size: Float,
    font: MarkFont,
    bold: Boolean,
    italic: Boolean,
    align: MarkAlign,
    width: Float,
    spacing: Float = LINE_SPACING,
    spans: List<StyleSpan> = emptyList(),
): TextLayout {
    val inset = size * 0.2f
    val family = fonts.family(font)
    val style = RunStyle(family, size, bold, italic)
    val builder = PieceBuilder(faces)
    var at = 0
    for (span in spans) {
        val start = span.start.coerceIn(at, text.length)
        val end = span.end.coerceIn(start, text.length)
        builder.text(text.substring(at, start), style)
        builder.text(text.substring(start, end), RunStyle(family, size, span.bold, span.italic, color = span.color))
        at = end
    }
    builder.text(text.substring(at), style)
    val pieces = builder.finish()
    val alignment = when (align) {
        MarkAlign.START -> Align.START
        MarkAlign.CENTER -> Align.CENTER
        MarkAlign.END -> Align.END
    }
    val lines = LineBreaker.lines(pieces, max(width - inset * 2, size), alignment, spacing, size)
    return TextLayout(lines, inset, width, lines.sumOf { it.height.toDouble() }.toFloat() + inset * 2)
}

internal fun layoutText(faces: Faces, fonts: MarkFonts, mark: TextMark): TextLayout {
    val (width, _) = Affine.frameSize(mark.box, mark.angle)
    return layoutText(faces, fonts, mark.text, mark.size, mark.font, mark.bold, mark.italic, mark.align, width.toFloat(), mark.spacing, mark.spans)
}

internal fun singleLine(faces: Faces, fonts: MarkFonts, text: String, size: Float): Line? =
    layoutText(faces, fonts, text, size, MarkFont.SANS, bold = false, italic = false, MarkAlign.START, Float.MAX_VALUE / 4).lines.firstOrNull()

internal fun lineWidth(line: Line): Double = if (line.boxes.isEmpty()) 0.0 else (line.xs.last() + line.boxes.last().width).toDouble()

internal class MarkPainter(private val writer: PdfWriter, val fonts: MarkFonts) {
    val faces = Faces(writer, fonts.fallbacks)
    private val images = HashMap<MarkImage, PdfRef>()

    fun canvas() = PaintCanvas(this)

    fun finish() {
        for (face in faces.all) face.write(writer)
    }

    fun form(canvas: PaintCanvas, bbox: PdfRect): PdfRef = writer.stream(
        PdfDict(
            "Type" to PdfName("XObject"),
            "Subtype" to PdfName("Form"),
            "BBox" to PdfArray(PdfReal(bbox.left), PdfReal(bbox.bottom), PdfReal(bbox.right), PdfReal(bbox.top)),
            "Resources" to canvas.resources(),
        ),
        canvas.ops.toString().encodeToByteArray(),
    )

    fun image(image: MarkImage): PdfRef = images.getOrPut(image) {
        val dict = PdfDict(
            "Type" to PdfName("XObject"),
            "Subtype" to PdfName("Image"),
            "Width" to PdfInt.of(image.width),
            "Height" to PdfInt.of(image.height),
            "ColorSpace" to PdfName(if (image.gray) "DeviceGray" else "DeviceRGB"),
            "BitsPerComponent" to PdfInt.of(8),
        )
        val jpeg = image.jpeg
        if (jpeg != null) {
            dict["Filter"] = PdfName("DCTDecode")
            writer.stream(dict, jpeg, compress = false)
        } else {
            image.alpha?.let { alpha ->
                dict["SMask"] = writer.stream(
                    PdfDict(
                        "Type" to PdfName("XObject"),
                        "Subtype" to PdfName("Image"),
                        "Width" to PdfInt.of(image.width),
                        "Height" to PdfInt.of(image.height),
                        "ColorSpace" to PdfName("DeviceGray"),
                        "BitsPerComponent" to PdfInt.of(8),
                    ),
                    alpha,
                )
            }
            writer.stream(dict, image.rgb ?: ByteArray(0))
        }
    }
}

internal class PaintCanvas(private val painter: MarkPainter) {
    val ops = StringBuilder()
    private val fonts = LinkedHashMap<String, PdfRef>()
    private val xobjects = LinkedHashMap<String, PdfRef>()
    private val states = LinkedHashMap<String, PdfDict>()

    val isEmpty: Boolean get() = ops.isEmpty()

    fun resources(): PdfDict {
        val out = PdfDict()
        if (fonts.isNotEmpty()) out["Font"] = PdfDict(LinkedHashMap<String, PdfObject>(fonts))
        if (xobjects.isNotEmpty()) out["XObject"] = PdfDict(LinkedHashMap<String, PdfObject>(xobjects))
        if (states.isNotEmpty()) out["ExtGState"] = PdfDict(LinkedHashMap<String, PdfObject>(states))
        return out
    }

    fun draw(mark: Mark) {
        when (mark) {
            is InkMark -> ink(mark)
            is ShapeMark -> shape(mark)
            is TextMark -> text(mark)
            is ImageMark -> image(mark)
            is SignatureMark -> signature(mark)
            is NoteMark -> note(mark)
            is CoverMark -> {
                fillColor(mark.color)
                ops.append(rect(mark.box)).append(" re f\n")
            }
            is MarkupMark -> markup(mark)
        }
    }

    fun paintForm(form: PdfRef, matrix: DoubleArray) {
        val name = "Fm${xobjects.size + 1}"
        xobjects[name] = form
        ops.append("q ").append(matrix.joinToString(" ") { n(it) }).append(" cm /").append(name).append(" Do Q\n")
    }

    fun textLine(text: String, at: LinePlacement, size: Float, color: Int, opacity: Float = 1f) {
        val line = singleLine(painter.faces, painter.fonts, text, size) ?: return
        val cos = cos(at.angle * PI / 180)
        val sin = sin(at.angle * PI / 180)
        ops.append("q ")
        if (opacity < 1f) ops.append('/').append(state(opacity, opacity, multiply = false)).append(" gs ")
        ops.append(n(cos)).append(' ').append(n(sin)).append(' ').append(n(-sin)).append(' ').append(n(cos)).append(' ').append(n(at.x)).append(' ').append(n(at.y)).append(" cm\n")
        fillColor(color)
        drawLine(line, 0.0, 0.0, color)
        ops.append("Q\n")
    }

    fun textWidth(text: String, size: Float): Double = singleLine(painter.faces, painter.fonts, text, size)?.let { lineWidth(it) } ?: 0.0

    private fun ink(mark: InkMark) {
        ops.append("q ")
        val alpha = alpha(mark.color)
        if (mark.highlighter || alpha < 1f) ops.append('/').append(state(alpha, alpha, mark.highlighter)).append(" gs ")
        strokeColor(mark.color)
        ops.append(n(mark.width.toDouble())).append(" w ").append(if (mark.highlighter) "0 J" else "1 J").append(" 1 j\n")
        path(mark.path.xy) { x, y -> x to y }
        ops.append("S Q\n")
    }

    private fun shape(mark: ShapeMark) {
        val box = mark.box
        val fill = mark.fill?.takeIf { mark.kind == ShapeKind.RECTANGLE || mark.kind == ShapeKind.ELLIPSE }
        ops.append("q ")
        val strokeAlpha = alpha(mark.color)
        val fillAlpha = if (fill != null) alpha(fill) else strokeAlpha
        if (strokeAlpha < 1f || fillAlpha < 1f) ops.append('/').append(state(fillAlpha, strokeAlpha, multiply = false)).append(" gs ")
        strokeColor(mark.color)
        ops.append(n(mark.width.toDouble())).append(" w 1 J 1 j\n")
        if (fill != null) fillColor(fill)
        when (mark.kind) {
            ShapeKind.RECTANGLE -> ops.append(rect(box)).append(if (fill != null) " re B\n" else " re S\n")
            ShapeKind.ELLIPSE -> {
                ellipse(box)
                ops.append(if (fill != null) "B\n" else "S\n")
            }
            ShapeKind.LINE -> ops.append(n(mark.x0)).append(' ').append(n(mark.y0)).append(" m ").append(n(mark.x1)).append(' ').append(n(mark.y1)).append(" l S\n")
            ShapeKind.ARROW -> arrow(mark)
            ShapeKind.CHECK -> framed(box, mark.angle) { w, h ->
                ops.append(n(w * 0.1)).append(' ').append(n(h * 0.52)).append(" m ")
                    .append(n(w * 0.38)).append(' ').append(n(h * 0.18)).append(" l ")
                    .append(n(w * 0.92)).append(' ').append(n(h * 0.86)).append(" l S\n")
            }
            ShapeKind.CROSS -> framed(box, mark.angle) { w, h ->
                ops.append(n(w * 0.15)).append(' ').append(n(h * 0.15)).append(" m ").append(n(w * 0.85)).append(' ').append(n(h * 0.85)).append(" l ")
                    .append(n(w * 0.15)).append(' ').append(n(h * 0.85)).append(" m ").append(n(w * 0.85)).append(' ').append(n(h * 0.15)).append(" l S\n")
            }
            ShapeKind.DOT -> {
                fillColor(mark.color)
                ellipse(box)
                ops.append("f\n")
            }
        }
        ops.append("Q\n")
    }

    private fun arrow(mark: ShapeMark) {
        val dx = mark.x1 - mark.x0
        val dy = mark.y1 - mark.y0
        val length = hypot(dx, dy)
        if (length < 1e-3) return
        val ux = dx / length
        val uy = dy / length
        val head = minOf(max(6.0, mark.width * 4.0), length)
        val half = head * 0.45
        val bx = mark.x1 - ux * head
        val by = mark.y1 - uy * head
        ops.append(n(mark.x0)).append(' ').append(n(mark.y0)).append(" m ").append(n(bx)).append(' ').append(n(by)).append(" l S\n")
        fillColor(mark.color)
        ops.append(n(mark.x1)).append(' ').append(n(mark.y1)).append(" m ")
            .append(n(bx - uy * half)).append(' ').append(n(by + ux * half)).append(" l ")
            .append(n(bx + uy * half)).append(' ').append(n(by - ux * half)).append(" l h f\n")
    }

    private fun text(mark: TextMark) {
        val (width, height) = Affine.frameSize(mark.box, mark.angle)
        val layout = layoutText(painter.faces, painter.fonts, mark)
        ops.append("q ").append(Affine.frameOf(mark.box, mark.angle).operands()).append(" cm\n")
        mark.fill?.let {
            fillColor(it)
            ops.append("0 0 ").append(n(width)).append(' ').append(n(height)).append(" re f\n")
        }
        if (mark.border) {
            strokeColor(mark.color)
            val w = max(1.0, mark.size / 12.0)
            ops.append(n(w)).append(" w ").append(n(w / 2)).append(' ').append(n(w / 2)).append(' ').append(n(width - w)).append(' ').append(n(height - w)).append(" re S\n")
        }
        ops.append("0 0 ").append(n(width)).append(' ').append(n(height)).append(" re W n\n")
        val alpha = alpha(mark.color)
        if (alpha < 1f) ops.append('/').append(state(alpha, alpha, multiply = false)).append(" gs ")
        fillColor(mark.color)
        var top = height - layout.inset
        for (line in layout.lines) {
            drawLine(line, layout.inset.toDouble(), top - line.baseline, mark.color)
            top -= line.height
        }
        ops.append("Q\n")
    }

    private fun drawLine(line: Line, x: Double, baseline: Double, color: Int) {
        var fill = color
        for (k in line.boxes.indices) {
            val box = line.boxes[k]
            val wanted = box.style.color ?: color
            if (wanted != fill) {
                fillColor(wanted)
                fill = wanted
            }
            val bx = x + line.xs[k]
            val by = baseline + box.style.rise
            fonts[box.face.resource] = box.face.ref
            ops.append("BT /").append(box.face.resource).append(' ').append(n(box.style.size.toDouble())).append(" Tf 1 0 0 1 ")
                .append(n(bx)).append(' ').append(n(by)).append(" Tm [<")
            for (g in box.glyphs.indices) {
                ops.append(hex4(box.glyphs[g]))
                val kern = box.kerns[g]
                if (kern != 0 && g + 1 < box.glyphs.size) ops.append('>').append(kern).append('<')
            }
            ops.append(">] TJ ET\n")
            if (box.face.ruble) {
                val scale = box.face.scale(box.style.size)
                val stem = box.style.size * if (box.face.font.weightClass >= 600) 0.12 else 0.075
                ops.append(n(bx - box.style.size * 0.06)).append(' ').append(n(by + box.face.font.capHeight * scale * 0.3 - stem / 2)).append(' ')
                    .append(n(box.width * 0.62)).append(' ').append(n(stem)).append(" re f\n")
            }
        }
        if (fill != color) fillColor(color)
    }

    private fun image(mark: ImageMark) {
        val (width, height) = Affine.frameSize(mark.box, mark.angle)
        val name = "Im${xobjects.size + 1}"
        xobjects[name] = painter.image(mark.image)
        ops.append("q ")
        if (mark.opacity < 1f) ops.append('/').append(state(mark.opacity, mark.opacity, multiply = false)).append(" gs ")
        ops.append(Affine.frameOf(mark.box, mark.angle).operands()).append(" cm ")
            .append(n(width)).append(" 0 0 ").append(n(height)).append(" 0 0 cm /").append(name).append(" Do Q\n")
    }

    private fun signature(mark: SignatureMark) {
        val (width, height) = Affine.frameSize(mark.box, mark.angle)
        ops.append("q ").append(Affine.frameOf(mark.box, mark.angle).operands()).append(" cm ")
        strokeColor(mark.color)
        ops.append(n(mark.width.toDouble())).append(" w 1 J 1 j\n")
        for (stroke in mark.paths) path(stroke.xy) { x, y -> x * width to (1 - y) * height }
        ops.append("S Q\n")
    }

    private fun note(mark: NoteMark) {
        val s = NoteMark.NOTE_SIZE
        val x = mark.x
        val y = mark.y - s
        ops.append("q 0.6 w ")
        fillColor(mark.color)
        strokeColor(0xFF5D4037.toInt())
        ops.append(n(x + 0.5)).append(' ').append(n(y + 0.5)).append(' ').append(n(s - 1)).append(' ').append(n(s - 1)).append(" re B\n")
        for (k in 1..3) {
            val ly = y + s - s * 0.25 * k
            ops.append(n(x + s * 0.2)).append(' ').append(n(ly)).append(" m ").append(n(x + s * 0.8)).append(' ').append(n(ly)).append(" l S\n")
        }
        ops.append("Q\n")
    }

    private fun markup(mark: MarkupMark) {
        ops.append("q ")
        if (mark.kind == MarkupKind.HIGHLIGHT) {
            ops.append('/').append(state(alpha(mark.color), alpha(mark.color), multiply = true)).append(" gs ")
            fillColor(mark.color)
            for (quad in mark.quads) {
                val p = quad.points
                ops.append(n(p[0])).append(' ').append(n(p[1])).append(" m ").append(n(p[2])).append(' ').append(n(p[3])).append(" l ")
                    .append(n(p[6])).append(' ').append(n(p[7])).append(" l ").append(n(p[4])).append(' ').append(n(p[5])).append(" l h f\n")
            }
            ops.append("Q\n")
            return
        }
        strokeColor(mark.color)
        for (quad in mark.quads) {
            val p = quad.points
            val height = hypot(p[0] - p[4], p[1] - p[5])
            val width = max(0.5, height * 0.07)
            val ux = if (height > 0) (p[0] - p[4]) / height else 0.0
            val uy = if (height > 0) (p[1] - p[5]) / height else 1.0
            ops.append(n(width)).append(" w ")
            when (mark.kind) {
                MarkupKind.UNDERLINE -> {
                    val shift = height * 0.08
                    ops.append(n(p[4] + ux * shift)).append(' ').append(n(p[5] + uy * shift)).append(" m ")
                        .append(n(p[6] + ux * shift)).append(' ').append(n(p[7] + uy * shift)).append(" l S\n")
                }
                MarkupKind.STRIKEOUT -> {
                    val shift = height * 0.42
                    ops.append(n(p[4] + ux * shift)).append(' ').append(n(p[5] + uy * shift)).append(" m ")
                        .append(n(p[6] + ux * shift)).append(' ').append(n(p[7] + uy * shift)).append(" l S\n")
                }
                MarkupKind.SQUIGGLY -> {
                    val length = hypot(p[6] - p[4], p[7] - p[5])
                    val vx = if (length > 0) (p[6] - p[4]) / length else 1.0
                    val vy = if (length > 0) (p[7] - p[5]) / length else 0.0
                    val step = max(height / 6, 1.0)
                    val amplitude = height / 14
                    var t = 0.0
                    var up = false
                    ops.append(n(p[4] + ux * amplitude)).append(' ').append(n(p[5] + uy * amplitude)).append(" m ")
                    while (t < length) {
                        t = minOf(t + step, length)
                        up = !up
                        val offset = if (up) amplitude * 2 else amplitude * 0.2
                        ops.append(n(p[4] + vx * t + ux * offset)).append(' ').append(n(p[5] + vy * t + uy * offset)).append(" l ")
                    }
                    ops.append("S\n")
                }
                MarkupKind.HIGHLIGHT -> Unit
            }
        }
        ops.append("Q\n")
    }

    private inline fun framed(box: PdfRect, angle: Int, body: (Double, Double) -> Unit) {
        val (w, h) = Affine.frameSize(box, angle)
        ops.append("q ").append(Affine.frameOf(box, angle).operands()).append(" cm\n")
        body(w, h)
        ops.append("Q\n")
    }

    private inline fun path(xy: FloatArray, map: (Double, Double) -> Pair<Double, Double>) {
        val count = xy.size / 2
        if (count == 0) return
        val px = DoubleArray(count)
        val py = DoubleArray(count)
        for (i in 0 until count) {
            val (x, y) = map(xy[2 * i].toDouble(), xy[2 * i + 1].toDouble())
            px[i] = x
            py[i] = y
        }
        ops.append(n(px[0])).append(' ').append(n(py[0])).append(" m ")
        if (count == 1) {
            ops.append(n(px[0])).append(' ').append(n(py[0])).append(" l\n")
            return
        }
        if (count == 2) {
            ops.append(n(px[1])).append(' ').append(n(py[1])).append(" l\n")
            return
        }
        for (i in 0 until count - 1) {
            val x0 = px[maxOf(i - 1, 0)]
            val y0 = py[maxOf(i - 1, 0)]
            val x3 = px[minOf(i + 2, count - 1)]
            val y3 = py[minOf(i + 2, count - 1)]
            ops.append(n(px[i] + (px[i + 1] - x0) / 6)).append(' ').append(n(py[i] + (py[i + 1] - y0) / 6)).append(' ')
                .append(n(px[i + 1] - (x3 - px[i]) / 6)).append(' ').append(n(py[i + 1] - (y3 - py[i]) / 6)).append(' ')
                .append(n(px[i + 1])).append(' ').append(n(py[i + 1])).append(" c\n")
        }
    }

    private fun ellipse(box: PdfRect) {
        val k = 0.5522847498
        val cx = (box.left + box.right) / 2
        val cy = (box.bottom + box.top) / 2
        val rx = box.width / 2
        val ry = box.height / 2
        ops.append(n(cx + rx)).append(' ').append(n(cy)).append(" m ")
        curve(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry)
        curve(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy)
        curve(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry)
        curve(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy)
        ops.append("h ")
    }

    private fun curve(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        ops.append(n(x1)).append(' ').append(n(y1)).append(' ').append(n(x2)).append(' ').append(n(y2)).append(' ').append(n(x3)).append(' ').append(n(y3)).append(" c ")
    }

    private fun rect(box: PdfRect): String = "${n(box.left)} ${n(box.bottom)} ${n(box.width)} ${n(box.height)}"

    private fun state(fill: Float, stroke: Float, multiply: Boolean): String {
        val dict = PdfDict("Type" to PdfName("ExtGState"), "ca" to PdfReal(fill.toDouble()), "CA" to PdfReal(stroke.toDouble()))
        if (multiply) dict["BM"] = PdfName("Multiply")
        states.entries.firstOrNull { (_, d) -> d["ca"] == dict["ca"] && d["CA"] == dict["CA"] && d["BM"] == dict["BM"] }?.let { return it.key }
        val name = "Gs${states.size + 1}"
        states[name] = dict
        return name
    }

    private fun fillColor(argb: Int) {
        ops.append(component(argb shr 16)).append(' ').append(component(argb shr 8)).append(' ').append(component(argb)).append(" rg ")
    }

    private fun strokeColor(argb: Int) {
        ops.append(component(argb shr 16)).append(' ').append(component(argb shr 8)).append(' ').append(component(argb)).append(" RG ")
    }

    private fun component(value: Int): String = n((value and 0xFF) / 255.0)
}

internal fun alpha(argb: Int): Float = ((argb ushr 24) and 0xFF) / 255f

private fun n(value: Double): String = formatReal((value * 1000).roundToLong() / 1000.0)
