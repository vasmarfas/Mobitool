package com.vasmarfas.card.tools.documents.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.vasmarfas.card.tools.documents.Faces
import com.vasmarfas.card.tools.documents.Line
import com.vasmarfas.card.tools.documents.pdf.GlyphContour
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.tools.documents.pdf.PdfWriter
import com.vasmarfas.card.tools.documents.pdf.TrueTypeFont
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun Affine.toMatrix(): Matrix = Matrix().also { m ->
    m.values[Matrix.ScaleX] = a.toFloat()
    m.values[Matrix.SkewY] = b.toFloat()
    m.values[Matrix.SkewX] = c.toFloat()
    m.values[Matrix.ScaleY] = d.toFloat()
    m.values[Matrix.TranslateX] = e.toFloat()
    m.values[Matrix.TranslateY] = f.toFloat()
}

internal fun Affine.point(x: Double, y: Double) = Offset(x(x, y).toFloat(), y(x, y).toFloat())

internal fun Affine.rect(rect: PdfRect): Rect {
    val a = point(rect.left, rect.bottom)
    val b = point(rect.right, rect.top)
    return Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
}

internal val Affine.scale: Float get() = hypot(a, b).toFloat()

internal class MarkRenderer(private val fonts: MarkFonts) {
    val faces = Faces(PdfWriter(), fonts.fallbacks)
    private val glyphs = HashMap<TrueTypeFont, HashMap<Int, Path>>()
    private val texts = LinkedHashMap<Any, Map<Int?, Path>>()

    fun layout(mark: TextMark): TextLayout = layoutText(faces, fonts, mark)

    fun fitted(mark: TextMark): TextMark {
        val (width, height) = Affine.frameSize(mark.box, mark.angle)
        return mark.copy(box = Affine.frameOf(mark.box, mark.angle).bounds(PdfRect(0.0, height - layout(mark).height, width, height)))
    }

    private fun glyph(font: TrueTypeFont, id: Int): Path = glyphs.getOrPut(font) { HashMap() }.getOrPut(id) { outlinePath(font.outline(id)) }

    private fun textPaths(mark: TextMark): Map<Int?, Path> = cached(mark) { paths ->
        val layout = layout(mark)
        var top = Affine.frameSize(mark.box, mark.angle).second - layout.inset
        for (line in layout.lines) {
            addLine(paths, line, layout.inset.toDouble(), top - line.baseline)
            top -= line.height
        }
    }

    private fun linePaths(text: String, size: Float): Map<Int?, Path> = cached(text to size) { paths ->
        singleLine(faces, fonts, text, size)?.let { addLine(paths, it, 0.0, 0.0) }
    }

    private fun cached(key: Any, build: (MutableMap<Int?, Path>) -> Unit): Map<Int?, Path> {
        texts[key]?.let { return it }
        val value = LinkedHashMap<Int?, Path>().also(build)
        texts[key] = value
        if (texts.size > 400) texts.remove(texts.keys.first())
        return value
    }

    private fun addLine(paths: MutableMap<Int?, Path>, line: Line, x: Double, baseline: Double) {
        for (k in line.boxes.indices) {
            val box = line.boxes[k]
            val path = paths.getOrPut(box.style.color) { Path() }
            val font = box.face.font
            val scale = box.style.size.toDouble() / font.unitsPerEm
            var gx = x + line.xs[k]
            val by = baseline + box.style.rise
            for (g in box.glyphs.indices) {
                val outline = Path().apply {
                    addPath(glyph(font, box.glyphs[g]))
                    transform(Affine(scale, 0.0, 0.0, scale, gx, by).toMatrix())
                }
                path.addPath(outline)
                gx += font.advanceWidth(box.glyphs[g]) * scale - box.kerns[g] * box.style.size / 1000.0
            }
            if (box.face.ruble) {
                val stem = box.style.size * if (font.weightClass >= 600) 0.12 else 0.075
                val left = x + line.xs[k] - box.style.size * 0.06
                val bottom = by + font.capHeight * scale * 0.3 - stem / 2
                path.addRect(Rect(left.toFloat(), bottom.toFloat(), (left + box.width * 0.62).toFloat(), (bottom + stem).toFloat()))
            }
        }
    }

    fun DrawScope.draw(mark: Mark, toScreen: Affine, bitmaps: Map<MarkImage, ImageBitmap>) {
        val scale = toScreen.scale
        when (mark) {
            is InkMark -> {
                val path = inkPath(mark.path.xy) { x, y -> toScreen.point(x, y) }
                drawPath(
                    path,
                    Color(mark.color),
                    style = Stroke(max(mark.width * scale, 1f), cap = if (mark.highlighter) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round),
                    blendMode = if (mark.highlighter) BlendMode.Multiply else BlendMode.SrcOver,
                )
            }
            is ShapeMark -> shape(mark, toScreen, scale)
            is TextMark -> {
                val frame = Affine.frameOf(mark.box, mark.angle).then(toScreen)
                val (w, h) = Affine.frameSize(mark.box, mark.angle)
                val corners = listOf(frame.point(0.0, 0.0), frame.point(w, 0.0), frame.point(w, h), frame.point(0.0, h))
                mark.fill?.let { drawPath(polygon(corners), Color(it)) }
                if (mark.border) drawPath(polygon(corners), Color(mark.color), style = Stroke(max(1f, mark.size / 12f * scale)))
                withTransform({ transform(frame.toMatrix()) }) {
                    for ((color, path) in textPaths(mark)) drawPath(path, Color(color ?: mark.color))
                }
            }
            is ImageMark -> {
                val bitmap = bitmaps[mark.image] ?: return
                val (w, h) = Affine.frameSize(mark.box, mark.angle)
                val m = Affine(w / bitmap.width, 0.0, 0.0, -h / bitmap.height, 0.0, h).then(Affine.frameOf(mark.box, mark.angle)).then(toScreen)
                withTransform({ transform(m.toMatrix()) }) {
                    drawImage(bitmap, IntOffset.Zero, IntSize(bitmap.width, bitmap.height), alpha = mark.opacity, filterQuality = FilterQuality.Medium)
                }
            }
            is SignatureMark -> {
                val (w, h) = Affine.frameSize(mark.box, mark.angle)
                val m = Affine(w, 0.0, 0.0, -h, 0.0, h).then(Affine.frameOf(mark.box, mark.angle)).then(toScreen)
                for (stroke in mark.paths) {
                    drawPath(inkPath(stroke.xy) { x, y -> m.point(x, y) }, Color(mark.color), style = Stroke(max(mark.width * scale, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            is NoteMark -> {
                val r = toScreen.rect(mark.bounds)
                val side = max(24f, min(r.width, r.height))
                val topLeft = Offset(r.left, r.top)
                drawRect(Color(mark.color), topLeft, Size(side, side))
                drawRect(Color(0xFF5D4037.toInt()), topLeft, Size(side, side), style = Stroke(1.5f))
                for (k in 1..3) {
                    val y = r.top + side * 0.25f * k
                    drawLine(Color(0xFF5D4037.toInt()), Offset(r.left + side * 0.2f, y), Offset(r.left + side * 0.8f, y), 1.5f)
                }
            }
            is CoverMark -> {
                val r = toScreen.rect(mark.box)
                drawRect(Color(mark.color), r.topLeft, r.size)
                if (mark.redact) drawRect(Color(0xFFD32F2F.toInt()), r.topLeft, r.size, style = Stroke(1.5f))
            }
            is MarkupMark -> markup(mark, toScreen)
        }
    }

    private fun DrawScope.shape(mark: ShapeMark, toScreen: Affine, scale: Float) {
        val color = Color(mark.color)
        val stroke = Stroke(max(mark.width * scale, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (mark.kind) {
            ShapeKind.RECTANGLE -> {
                val r = toScreen.rect(mark.box)
                mark.fill?.let { drawRect(Color(it), r.topLeft, r.size) }
                drawRect(color, r.topLeft, r.size, style = stroke)
            }
            ShapeKind.ELLIPSE -> {
                val r = toScreen.rect(mark.box)
                mark.fill?.let { drawOval(Color(it), r.topLeft, r.size) }
                drawOval(color, r.topLeft, r.size, style = stroke)
            }
            ShapeKind.DOT -> {
                val r = toScreen.rect(mark.box)
                drawOval(color, r.topLeft, r.size)
            }
            ShapeKind.LINE -> drawLine(color, toScreen.point(mark.x0, mark.y0), toScreen.point(mark.x1, mark.y1), stroke.width, cap = StrokeCap.Round)
            ShapeKind.ARROW -> {
                val from = toScreen.point(mark.x0, mark.y0)
                val to = toScreen.point(mark.x1, mark.y1)
                val length = hypot((to.x - from.x).toDouble(), (to.y - from.y).toDouble()).toFloat()
                if (length < 1f) return
                val ux = (to.x - from.x) / length
                val uy = (to.y - from.y) / length
                val head = min(max(6f, mark.width * 4f) * scale, length)
                val half = head * 0.45f
                val base = Offset(to.x - ux * head, to.y - uy * head)
                drawLine(color, from, base, stroke.width, cap = StrokeCap.Round)
                drawPath(polygon(listOf(to, Offset(base.x - uy * half, base.y + ux * half), Offset(base.x + uy * half, base.y - ux * half))), color)
            }
            ShapeKind.CHECK, ShapeKind.CROSS -> {
                val (w, h) = Affine.frameSize(mark.box, mark.angle)
                val frame = Affine.frameOf(mark.box, mark.angle).then(toScreen)
                val path = Path()
                if (mark.kind == ShapeKind.CHECK) {
                    path.moveTo(frame.point(w * 0.1, h * 0.52))
                    path.lineTo(frame.point(w * 0.38, h * 0.18))
                    path.lineTo(frame.point(w * 0.92, h * 0.86))
                } else {
                    path.moveTo(frame.point(w * 0.15, h * 0.15))
                    path.lineTo(frame.point(w * 0.85, h * 0.85))
                    path.moveTo(frame.point(w * 0.15, h * 0.85))
                    path.lineTo(frame.point(w * 0.85, h * 0.15))
                }
                drawPath(path, color, style = stroke)
            }
        }
    }

    private fun DrawScope.markup(mark: MarkupMark, toScreen: Affine) {
        val color = Color(mark.color)
        for (quad in mark.quads) {
            val p = quad.points
            val tl = toScreen.point(p[0], p[1])
            val tr = toScreen.point(p[2], p[3])
            val bl = toScreen.point(p[4], p[5])
            val br = toScreen.point(p[6], p[7])
            val height = hypot((tl.x - bl.x).toDouble(), (tl.y - bl.y).toDouble()).toFloat()
            val width = max(1f, height * 0.07f)
            fun along(t: Float, a: Offset, b: Offset) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            when (mark.kind) {
                MarkupKind.HIGHLIGHT -> drawPath(polygon(listOf(tl, tr, br, bl)), color.copy(alpha = max(color.alpha, 0.35f)), blendMode = BlendMode.Multiply)
                MarkupKind.UNDERLINE -> drawLine(color, along(0.08f, bl, tl), along(0.08f, br, tr), width)
                MarkupKind.STRIKEOUT -> drawLine(color, along(0.42f, bl, tl), along(0.42f, br, tr), width)
                MarkupKind.SQUIGGLY -> {
                    val path = Path()
                    val steps = max(2, ((br - bl).getDistance() / max(height / 6f, 2f)).toInt())
                    for (s in 0..steps) {
                        val base = along(s.toFloat() / steps, bl, br)
                        val lift = if (s % 2 == 0) 0.02f else 0.14f
                        val point = Offset(base.x + (tl.x - bl.x) * lift, base.y + (tl.y - bl.y) * lift)
                        if (s == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    }
                    drawPath(path, color, style = Stroke(width))
                }
            }
        }
    }

    fun DrawScope.fieldValue(field: FormField, widget: FormWidget, value: FieldValue, toScreen: Affine) {
        val r = toScreen.rect(widget.rect)
        drawRect(Color.White, r.topLeft, r.size)
        when (field.kind) {
            FieldKind.CHECKBOX, FieldKind.RADIO -> {
                val on = (value as? FieldValue.Check)?.state?.let { it == (widget.onState ?: "Yes") } == true
                if (!on) return
                val side = min(r.width, r.height)
                val path = Path().apply {
                    moveTo(r.left + side * 0.2f, r.top + side * 0.5f)
                    lineTo(r.left + side * 0.42f, r.top + side * 0.74f)
                    lineTo(r.left + side * 0.82f, r.top + side * 0.24f)
                }
                drawPath(path, Color(field.textColor), style = Stroke(max(1.5f, side * 0.1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            else -> {
                val text = if (field.password) "•".repeat(field.display(value).length) else field.display(value)
                val size = if (field.fontSize > 0f) field.fontSize else min(12f, ((widget.rect.height - 2) / 1.2).toFloat()).coerceAtLeast(4f)
                val at = LinePlacement(widget.rect.left + 2, widget.rect.bottom + (widget.rect.height - size * 0.72) / 2, 0.0)
                line(text, size, at, toScreen, Color(field.textColor))
            }
        }
    }

    fun DrawScope.decorations(page: EditPage, index: Int, total: Int, edit: DocumentEdit, toScreen: Affine) {
        edit.watermark?.let { watermark ->
            val width = singleLine(faces, fonts, watermark.text, watermark.size)?.let(::lineWidth) ?: 0.0
            line(watermark.text, watermark.size, watermarkPlacement(page, watermark, width), toScreen, Color(watermark.color).copy(alpha = watermark.opacity))
        }
        edit.numbering?.let { numbering ->
            if (numbering.skipFirst && index == 0) return@let
            val label = numbering.label(index, total)
            val width = singleLine(faces, fonts, label, numbering.size)?.let(::lineWidth) ?: 0.0
            line(label, numbering.size, numberPlacement(page, numbering, width), toScreen, Color(numbering.color))
        }
    }

    fun DrawScope.line(text: String, size: Float, at: LinePlacement, toScreen: Affine, color: Color) {
        val rad = at.angle * PI / 180
        val m = Affine(cos(rad), sin(rad), -sin(rad), cos(rad), at.x, at.y).then(toScreen)
        withTransform({ transform(m.toMatrix()) }) { for (path in linePaths(text, size).values) drawPath(path, color) }
    }
}

private fun Path.moveTo(point: Offset) = moveTo(point.x, point.y)

private fun Path.lineTo(point: Offset) = lineTo(point.x, point.y)

internal fun polygon(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}

internal inline fun inkPath(xy: FloatArray, map: (Double, Double) -> Offset): Path {
    val path = Path()
    val count = xy.size / 2
    if (count == 0) return path
    val points = Array(count) { map(xy[2 * it].toDouble(), xy[2 * it + 1].toDouble()) }
    path.moveTo(points[0].x, points[0].y)
    if (count == 1) {
        path.lineTo(points[0].x + 0.01f, points[0].y)
        return path
    }
    if (count == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 0 until count - 1) {
        val p0 = points[max(i - 1, 0)]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points[min(i + 2, count - 1)]
        path.cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
    }
    return path
}

private fun outlinePath(contours: List<GlyphContour>): Path {
    val path = Path()
    for (c in contours) {
        val n = c.x.size
        if (n == 0) continue
        val s = c.onCurve.indexOfFirst { it }
        val startX = if (s >= 0) c.x[s] else (c.x[n - 1] + c.x[0]) / 2
        val startY = if (s >= 0) c.y[s] else (c.y[n - 1] + c.y[0]) / 2
        val from = if (s >= 0) s + 1 else 0
        val steps = if (s >= 0) n - 1 else n
        path.moveTo(startX, startY)
        var cx = 0f
        var cy = 0f
        var control = false
        for (k in 0 until steps) {
            val i = (from + k) % n
            val px = c.x[i]
            val py = c.y[i]
            if (c.onCurve[i]) {
                if (control) path.quadraticTo(cx, cy, px, py) else path.lineTo(px, py)
                control = false
            } else {
                if (control) path.quadraticTo(cx, cy, (cx + px) / 2, (cy + py) / 2)
                cx = px
                cy = py
                control = true
            }
        }
        if (control) path.quadraticTo(cx, cy, startX, startY) else path.lineTo(startX, startY)
        path.close()
    }
    return path
}
