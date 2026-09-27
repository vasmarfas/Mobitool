package com.vasmarfas.card.tools.documents.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import com.vasmarfas.card.tools.documents.pdf.GraphicKind
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

internal interface StageHandler {
    val captures: Boolean

    fun move(position: Offset, delta: Offset): Boolean = false

    fun end(position: Offset, tap: Boolean) = Unit

    fun cancel() = Unit
}

internal interface StageActions {
    fun editNote(note: NoteMark)

    fun editField(field: FormField)

    fun needSignature()

    fun needImage()
}

internal data class ObjectDrag(val keys: Set<Int>, val from: Rect, val to: Rect, val text: Boolean, val done: Boolean = false)

private class Conversion(val markId: Long, val text: String, val before: DocumentEdit)

@Stable
internal class StageInput(
    private val session: EditorSession,
    private val renderer: MarkRenderer,
    private val view: StageView,
    private val scope: CoroutineScope,
    private val actions: StageActions,
    private val signatures: SignatureStore,
) {
    var toScreen: Affine = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    var erased by mutableStateOf<Set<Long>>(emptySet())
        private set
    var selection by mutableStateOf<TextMatch?>(null)
    var cropDraft by mutableStateOf<PdfRect?>(null)
    var hovered by mutableStateOf<Rect?>(null)
        private set
    var drag by mutableStateOf<ObjectDrag?>(null)

    var textBounds: Rect? = null
    private var stroke by mutableStateOf<FloatArray?>(null)
    private var dragBox by mutableStateOf<Pair<Offset, Offset>?>(null)
    private var lastTap: TimeSource.Monotonic.ValueTimeMark? = null
    private var lastTapAt = Offset.Zero
    private var conversion: Conversion? = null

    private val fromScreen: Affine get() = toScreen.inverse()

    private fun user(p: Offset): Offset = fromScreen.point(p.x.toDouble(), p.y.toDouble())

    private fun userDelta(d: Offset): Offset {
        val inv = fromScreen
        return Offset((inv.a * d.x + inv.c * d.y).toFloat(), (inv.b * d.x + inv.d * d.y).toFloat())
    }

    private val scale: Float get() = toScreen.scale

    fun begin(position: Offset, page: EditPage, typing: Boolean, mouse: Boolean): StageHandler {
        hovered = null
        val at = user(position)
        return when (session.tool) {
            EditTool.VIEW -> pan()
            EditTool.SELECT -> select(position, page, mouse)
            EditTool.TEXT -> tapOrPan { if (!typing) text(position, page) }
            EditTool.PEN -> ink(at, session.style.penColor, session.style.penWidth, highlighter = false)
            EditTool.MARKER -> ink(at, session.style.markerColor, session.style.markerWidth, highlighter = true)
            EditTool.SHAPE -> box(position) { a, b -> shape(a, b, session.id()) }
            EditTool.WHITEOUT -> box(position) { a, b -> cover(a, b, session.style.coverColor, redact = false) }
            EditTool.REDACT -> box(position) { a, b -> cover(a, b, 0xFF000000.toInt(), redact = true) }
            EditTool.ERASER -> eraser(at, page)
            EditTool.TEXT_SELECT -> textSelect(at, page)
            EditTool.CROP -> crop(position, page)
            EditTool.SIGN -> tapOrPan { sign(position, page) }
            EditTool.IMAGE -> tapOrPan { image(position, page) }
            EditTool.STAMP -> tapOrPan { stamp(position, page) }
            EditTool.NOTE -> tapOrPan {
                val u = user(position)
                val note = NoteMark(session.id(), u.x.toDouble(), u.y.toDouble(), "", 0xFFFFE082.toInt())
                session.add(note, select = true)
                actions.editNote(note)
            }
            EditTool.FORM -> tapOrPan { widgetAt(page, at)?.let { (field, widget) -> fill(field, widget) } }
        }
    }

    private fun pan(): StageHandler = object : StageHandler {
        override val captures = view.zoom > 1f
        private var total = Offset.Zero

        override fun move(position: Offset, delta: Offset): Boolean {
            total += delta
            if (view.zoom <= 1f) return false
            view.origin = view.drawnOrigin(session.page) + delta
            return true
        }

        override fun end(position: Offset, tap: Boolean) {
            if (tap) {
                doubleTap(position)
                return
            }
            if (view.zoom <= 1f && abs(total.x) > 3 * abs(total.y) && abs(total.x) > view.width / 5) {
                val next = session.current + if (total.x < 0) 1 else -1
                if (next in session.edit.pages.indices) session.current = next
            }
        }
    }

    private fun doubleTap(position: Offset) {
        val previous = lastTap
        if (previous != null && previous.elapsedNow().inWholeMilliseconds < 320 && (position - lastTapAt).getDistance() < 48f) {
            view.zoomTo(if (view.zoom > 1.01f) 1f else 2.5f, position, session.page)
            lastTap = null
            return
        }
        lastTap = TimeSource.Monotonic.markNow()
        lastTapAt = position
    }

    private inline fun tapOrPan(crossinline onTap: () -> Unit): StageHandler = object : StageHandler {
        private val inner = pan()
        override val captures = inner.captures

        override fun move(position: Offset, delta: Offset) = inner.move(position, delta)

        override fun end(position: Offset, tap: Boolean) {
            if (tap) onTap() else inner.end(position, false)
        }
    }

    private fun ink(at: Offset, color: Int, width: Float, highlighter: Boolean): StageHandler {
        stroke = floatArrayOf(at.x, at.y)
        return object : StageHandler {
            override val captures = true
            private val points = ArrayList<Float>().apply {
                add(at.x)
                add(at.y)
            }

            override fun move(position: Offset, delta: Offset): Boolean {
                val u = user(position)
                points += u.x
                points += u.y
                stroke = points.toFloatArray()
                return true
            }

            override fun end(position: Offset, tap: Boolean) {
                val path = InkPath(points.toFloatArray()).simplified(0.25f / max(scale, 0.01f) * 2)
                stroke = null
                session.add(InkMark(session.id(), path, color, width, highlighter))
            }

            override fun cancel() {
                stroke = null
            }
        }
    }

    private inline fun box(start: Offset, crossinline create: (Offset, Offset) -> Mark?): StageHandler {
        dragBox = start to start
        return object : StageHandler {
            override val captures = true

            override fun move(position: Offset, delta: Offset): Boolean {
                dragBox = start to position
                return true
            }

            override fun end(position: Offset, tap: Boolean) {
                dragBox = null
                if (tap) return
                create(user(start), user(position))?.let { session.add(it) }
            }

            override fun cancel() {
                dragBox = null
            }
        }
    }

    private fun shape(a: Offset, b: Offset, id: Long): Mark? {
        val style = session.style
        if (hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) < 2) return null
        return ShapeMark(
            id, style.shape, a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble(),
            style.shapeColor, style.shapeWidth, fill = if (style.shapeFill) (style.shapeColor and 0x00FFFFFF) or 0x40000000 else null,
        )
    }

    private fun cover(a: Offset, b: Offset, color: Int, redact: Boolean): Mark? {
        val box = PdfRect(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble()).normalized()
        if (box.width < 1 || box.height < 1) return null
        return CoverMark(session.id(), box, color, redact)
    }

    private fun eraser(at: Offset, page: EditPage): StageHandler {
        val hit = HashSet<Long>()
        fun rub(point: Offset) {
            val tolerance = 10f / max(scale, 0.01f)
            for (mark in page.marks) if (mark.id !in hit && touches(mark, point.x.toDouble(), point.y.toDouble(), tolerance.toDouble())) hit += mark.id
            erased = hit.toSet()
        }
        rub(at)
        return object : StageHandler {
            override val captures = true

            override fun move(position: Offset, delta: Offset): Boolean {
                rub(user(position))
                return true
            }

            override fun end(position: Offset, tap: Boolean) {
                if (hit.isNotEmpty()) session.updatePage(page.id) { p -> p.copy(marks = p.marks.filterNot { it.id in hit }) }
                erased = emptySet()
            }

            override fun cancel() {
                erased = emptySet()
            }
        }
    }

    private fun textSelect(at: Offset, page: EditPage): StageHandler {
        val source = page.source as? SourcePage ?: return pan()
        val text = session.texts[PageKey(source.page.document, source.page.index) to page.objects]
        if (text == null) {
            scope.launch { session.text(source, page.objects) }
            return pan()
        }
        val start = text.offsetAt(at.x.toDouble(), at.y.toDouble()) ?: return pan()
        return object : StageHandler {
            override val captures = true

            override fun move(position: Offset, delta: Offset): Boolean {
                val u = user(position)
                val end = text.offsetAt(u.x.toDouble(), u.y.toDouble()) ?: return true
                selection = text.range(min(start, end), max(start, end) + 1)
                return true
            }

            override fun end(position: Offset, tap: Boolean) {
                if (tap) selection = null
            }
        }
    }

    private fun select(position: Offset, page: EditPage, mouse: Boolean): StageHandler {
        session.mark(session.selected)?.let { selected ->
            val corner = cornerAt(screenBox(selected), resizable(selected), position)
            if (corner >= 0) return resize(selected, corner)
        }
        (session.picked as? PickedGraphic)?.takeIf { it.pageId == page.id }?.let { picked ->
            val corner = cornerAt(pickedRect(page, picked), true, position)
            if (corner >= 0) return scaleObject(page, picked, corner)
        }
        val at = user(position)
        val x = at.x.toDouble()
        val y = at.y.toDouble()
        val tolerance = (8f / max(scale, 0.01f)).toDouble()
        page.marks.lastOrNull { touches(it, x, y, tolerance) }?.let { return pickMark(it, mouse) }
        widgetAt(page, at)?.let { (field, widget) -> return tapOrPan { fill(field, widget) } }
        val picked = objectsOf(page)?.let { pickAt(page, it, x, y, tolerance) } ?: return tapOrPan {
            session.selected = null
            session.picked = null
        }
        return pickObject(page, picked, mouse)
    }

    private fun objectsOf(page: EditPage): PageObjects? {
        val source = page.source as? SourcePage ?: return null
        val found = session.objects[PageKey(source.page.document, source.page.index)]
        if (found == null) scope.launch { session.objects(source) }
        return found
    }

    private fun pickMark(hit: Mark, mouse: Boolean): StageHandler {
        val wasSelected = session.selected == hit.id
        if (!mouse && !wasSelected) {
            return tapOrPan {
                session.picked = null
                session.selected = hit.id
                if (hit is NoteMark) actions.editNote(hit)
            }
        }
        session.picked = null
        session.selected = hit.id
        return object : StageHandler {
            override val captures = true
            private var total = Offset.Zero

            override fun move(position: Offset, delta: Offset): Boolean {
                total += userDelta(delta)
                session.preview = hit.translated(total.x.toDouble(), total.y.toDouble())
                return true
            }

            override fun end(position: Offset, tap: Boolean) {
                if (tap) {
                    session.preview = null
                    when {
                        hit is TextMark && wasSelected -> session.editing = hit.id
                        hit is NoteMark -> actions.editNote(hit)
                    }
                    return
                }
                session.preview?.let { session.replace(it) }
            }

            override fun cancel() {
                session.preview = null
            }
        }
    }

    private fun pickObject(page: EditPage, hit: Picked, mouse: Boolean): StageHandler {
        val wasPicked = session.picked?.let { it.pageId == hit.pageId && it.keys == hit.keys } == true
        if (!mouse && !wasPicked) return tapOrPan { choose(hit) }
        choose(hit)
        val from = pickedRect(page, hit)
        return object : StageHandler {
            override val captures = true
            private var total = Offset.Zero

            override fun move(position: Offset, delta: Offset): Boolean {
                total += delta
                drag = ObjectDrag(hit.keys, from, from.translate(total), hit is PickedText)
                return true
            }

            override fun end(position: Offset, tap: Boolean) {
                if (!tap) {
                    commit(page, hit.keys, from, from.translate(total))
                    return
                }
                drag = null
                if (wasPicked && hit is PickedText) editBlock(page, hit.block)
            }

            override fun cancel() {
                drag = null
            }
        }
    }

    private fun choose(hit: Picked) {
        session.selected = null
        session.picked = hit
    }

    private fun scaleObject(page: EditPage, hit: PickedGraphic, corner: Int): StageHandler {
        val from = pickedRect(page, hit)
        val fixed = listOf(from.bottomRight, from.bottomLeft, from.topRight, from.topLeft)[corner]
        val keepAspect = hit.graphic.kind != GraphicKind.PATH
        val aspect = if (from.height > 0f) from.width / from.height else 1f
        return object : StageHandler {
            override val captures = true

            override fun move(position: Offset, delta: Offset): Boolean {
                var w = abs(position.x - fixed.x).coerceAtLeast(8f)
                var h = abs(position.y - fixed.y).coerceAtLeast(8f)
                if (keepAspect) {
                    if (w / h > aspect) w = h * aspect else h = w / aspect
                }
                val left = if (position.x < fixed.x) fixed.x - w else fixed.x
                val top = if (position.y < fixed.y) fixed.y - h else fixed.y
                drag = ObjectDrag(hit.keys, from, Rect(left, top, left + w, top + h), text = false)
                return true
            }

            override fun end(position: Offset, tap: Boolean) {
                val to = drag?.to
                if (tap || to == null) drag = null else commit(page, hit.keys, from, to)
            }

            override fun cancel() {
                drag = null
            }
        }
    }

    private fun commit(page: EditPage, keys: Set<Int>, from: Rect, to: Rect) {
        val sx = if (from.width > 0f) (to.width / from.width).toDouble() else 1.0
        val sy = if (from.height > 0f) (to.height / from.height).toDouble() else 1.0
        val screen = Affine(sx, 0.0, 0.0, sy, to.left - from.left * sx, to.top - from.top * sy)
        session.transformObjects(page.id, keys, toScreen.then(screen).then(fromScreen))
        drag = drag?.copy(to = to, done = true)
    }

    private fun pickedRect(page: EditPage, picked: Picked): Rect = toScreen.rect(currentBounds(page, picked))

    private fun currentBounds(page: EditPage, picked: Picked): PdfRect =
        page.objects[picked.keys.first()]?.transform?.bounds(picked.bounds) ?: picked.bounds

    private fun pickAt(page: EditPage, objects: PageObjects, x: Double, y: Double, tolerance: Double): Picked? {
        val gone = page.objects.filterValues { it.removed }.keys
        for (block in objects.blocks) {
            if (block.ops.any { it in gone }) continue
            val (px, py) = original(page, block.ops.first(), x, y)
            if (block.contains(px, py, tolerance / 2)) return PickedText(page.id, block)
        }
        val limit = page.box.area() * 0.8
        return objects.graphics
            .filter { graphic ->
                if (graphic.first in gone || graphic.bounds.area() > limit) return@filter false
                val (px, py) = original(page, graphic.first, x, y)
                graphic.bounds.inflated(tolerance).contains(px, py)
            }
            .minByOrNull { it.bounds.area() }
            ?.let { PickedGraphic(page.id, it) }
    }

    private fun original(page: EditPage, key: Int, x: Double, y: Double): Pair<Double, Double> {
        val back = page.objects[key]?.transform?.inverse() ?: return x to y
        return back.x(x, y) to back.y(x, y)
    }

    fun hover(position: Offset?, page: EditPage) {
        if (position == null || session.tool != EditTool.SELECT || drag != null) {
            hovered = null
            return
        }
        val at = user(position)
        val x = at.x.toDouble()
        val y = at.y.toDouble()
        val tolerance = (8f / max(scale, 0.01f)).toDouble()
        val mark = page.marks.lastOrNull { touches(it, x, y, tolerance) }
        hovered = when {
            mark != null -> screenBox(mark)
            else -> objectsOf(page)?.let { pickAt(page, it, x, y, tolerance) }?.let { pickedRect(page, it) }
        }
    }

    fun editBlock(page: EditPage, block: TextBlock) {
        val angle = block.angle ?: return
        val style = block.style
        val font = when {
            style.mono -> MarkFont.MONO
            style.serif -> MarkFont.SERIF
            else -> MarkFont.SANS
        }
        val size = block.size.toFloat()
        val width = block.width + size * 0.9
        val mark = TextMark(session.id(), PdfRect(0.0, 0.0, width, 1e4), block.text, size, block.color, font, style.bold, style.italic, block.align, spacing = block.spacing, spans = block.spans)
        val layout = renderer.layout(mark)
        val baseline = layout.lines.firstOrNull()?.baseline?.toDouble() ?: (size * 0.8)
        val moved = page.objects[block.ops.first()]?.transform
        val x = moved?.x(block.anchorX, block.anchorY) ?: block.anchorX
        val y = moved?.y(block.anchorX, block.anchorY) ?: block.anchorY
        val placed = mark.copy(box = lineBox(x, y, angle, layout.inset.toDouble(), baseline, width, layout.height.toDouble()), angle = angle)
        val before = session.edit
        session.updatePage(page.id) { it.copy(objects = it.objects + block.ops.associateWith { ObjectEdit(removed = true) }, marks = it.marks + placed) }
        conversion = Conversion(placed.id, placed.text, before)
        session.picked = null
        session.selected = placed.id
        session.editing = placed.id
    }

    private fun resizable(mark: Mark): Boolean = when (mark) {
        is TextMark, is ImageMark, is SignatureMark, is CoverMark -> true
        is ShapeMark -> true
        else -> false
    }

    private fun screenBox(mark: Mark): Rect = toScreen.rect(
        when (mark) {
            is ShapeMark -> mark.box
            else -> mark.bounds
        },
    )

    private fun cornerAt(r: Rect, resizable: Boolean, position: Offset): Int {
        if (!resizable) return -1
        val corners = listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight)
        return corners.indexOfFirst { (it - position).getDistance() < 28f }
    }

    private fun resize(mark: Mark, corner: Int): StageHandler {
        val r = screenBox(mark)
        val fixed = listOf(r.bottomRight, r.bottomLeft, r.topRight, r.topLeft)[corner]
        val keepAspect = mark is ImageMark || mark is SignatureMark
        val aspect = if (r.height > 0f) r.width / r.height else 1f
        return object : StageHandler {
            override val captures = true

            override fun move(position: Offset, delta: Offset): Boolean {
                var w = abs(position.x - fixed.x).coerceAtLeast(12f)
                var h = abs(position.y - fixed.y).coerceAtLeast(12f)
                if (keepAspect) {
                    if (w / h > aspect) w = h * aspect else h = w / aspect
                }
                val left = if (position.x < fixed.x) fixed.x - w else fixed.x
                val top = if (position.y < fixed.y) fixed.y - h else fixed.y
                val a = user(Offset(left, top))
                val b = user(Offset(left + w, top + h))
                val box = PdfRect(a.x.toDouble(), a.y.toDouble(), b.x.toDouble(), b.y.toDouble()).normalized()
                session.preview = when (mark) {
                    is TextMark -> renderer.fitted(mark.copy(box = box))
                    is ImageMark -> mark.copy(box = box)
                    is SignatureMark -> mark.copy(box = box)
                    is CoverMark -> mark.copy(box = box)
                    is ShapeMark -> resizedShape(mark, box)
                    else -> mark
                }
                return true
            }

            override fun end(position: Offset, tap: Boolean) {
                session.preview?.let { session.replace(it) }
            }

            override fun cancel() {
                session.preview = null
            }
        }
    }

    private fun resizedShape(mark: ShapeMark, box: PdfRect): ShapeMark {
        val flipX = mark.x1 < mark.x0
        val flipY = mark.y1 < mark.y0
        return mark.copy(
            x0 = if (flipX) box.right else box.left,
            x1 = if (flipX) box.left else box.right,
            y0 = if (flipY) box.top else box.bottom,
            y1 = if (flipY) box.bottom else box.top,
        )
    }

    private fun text(position: Offset, page: EditPage) {
        val at = user(position)
        val existing = page.marks.lastOrNull { it is TextMark && it.box.contains(at.x.toDouble(), at.y.toDouble()) }
        if (existing != null) {
            session.selected = existing.id
            session.editing = existing.id
            return
        }
        val style = session.style
        val frame = page.frame
        val u = frame.toDisplayU(at.x.toDouble(), at.y.toDouble())
        val v = frame.toDisplayV(at.x.toDouble(), at.y.toDouble())
        val width = min(240.0, frame.width - u - 4).coerceAtLeast(80.0)
        val height = style.textSize * LINE_SPACING + style.textSize * 0.4
        val left = u.coerceAtMost(frame.width - width)
        val top = (v - height / 2).coerceIn(0.0, max(frame.height - height, 0.0))
        val mark = TextMark(
            session.id(), frame.toUser(left, top, left + width, top + height), "", style.textSize, style.textColor,
            style.textFont, style.bold, style.italic, style.align, angle = page.rotation,
        )
        session.add(mark, select = true)
        session.editing = mark.id
    }

    fun finishText(id: Long, text: String, spans: List<StyleSpan>) {
        val converted = conversion?.takeIf { it.markId == id }
        if (converted != null) {
            conversion = null
            if (text == converted.text) {
                if (session.editing == id) session.editing = null
                session.revert(converted.before)
                return
            }
        }
        if (session.editing == id) session.editing = null
        val page = session.edit.pages.firstOrNull { p -> p.marks.any { it.id == id } } ?: return
        val mark = page.marks.first { it.id == id } as? TextMark ?: return
        if (text.isBlank()) {
            session.remove(id)
            return
        }
        session.replace(renderer.fitted(mark.copy(text = text, spans = spans)))
    }

    private fun sign(position: Offset, page: EditPage) {
        val signature = signatures.all.getOrNull(session.style.signature) ?: run {
            actions.needSignature()
            return
        }
        val frame = page.frame
        val at = user(position)
        val width = min(170.0, frame.width * 0.4)
        val box = page.placeAround(frame.toDisplayU(at.x.toDouble(), at.y.toDouble()), frame.toDisplayV(at.x.toDouble(), at.y.toDouble()), width, width / signature.aspect)
        val mark = SignatureMark(session.id(), box, signature.paths, session.style.signatureColor, 1.6f, angle = page.rotation)
        session.add(mark, select = true)
        session.tool = EditTool.SELECT
    }

    private fun image(position: Offset, page: EditPage) {
        val pending = session.pendingImage ?: run {
            actions.needImage()
            return
        }
        val frame = page.frame
        val at = user(position)
        val width = min(frame.width * 0.45, pending.image.width * 0.75)
        val height = width * pending.image.height / pending.image.width
        val box = page.placeAround(frame.toDisplayU(at.x.toDouble(), at.y.toDouble()), frame.toDisplayV(at.x.toDouble(), at.y.toDouble()), width, height)
        session.images[pending.image] = pending.bitmap
        val mark = ImageMark(session.id(), box, pending.image, angle = page.rotation)
        session.add(mark, select = true)
        session.pendingImage = null
        session.tool = EditTool.SELECT
    }

    private fun stamp(position: Offset, page: EditPage) {
        val style = session.style
        val frame = page.frame
        val at = user(position)
        val u = frame.toDisplayU(at.x.toDouble(), at.y.toDouble())
        val v = frame.toDisplayV(at.x.toDouble(), at.y.toDouble())
        val symbol = when (style.stamp) {
            Stamp.CHECK -> ShapeKind.CHECK
            Stamp.CROSS -> ShapeKind.CROSS
            Stamp.DOT -> ShapeKind.DOT
            else -> null
        }
        if (symbol != null) {
            val side = if (symbol == ShapeKind.DOT) 8.0 else 14.0
            val box = page.placeAround(u, v, side, side)
            session.add(ShapeMark(session.id(), symbol, box.left, box.bottom, box.right, box.top, style.stampColor, 1.8f, angle = page.rotation))
            return
        }
        val text = stampText(style.stamp)
        val size = 16f
        val probe = TextMark(0, PdfRect(0.0, 0.0, 1000.0, 100.0), text, size, style.stampColor, MarkFont.SANS, bold = true)
        val line = renderer.layout(probe).lines.firstOrNull()
        val width = (line?.let(::lineWidth) ?: 100.0) + size * 0.4 + 4
        val height = size * LINE_SPACING + size * 0.4
        val box = page.placeAround(u, v, width, height)
        session.add(TextMark(session.id(), box, text, size, style.stampColor, MarkFont.SANS, bold = true, align = MarkAlign.CENTER, angle = page.rotation, border = true), select = true)
    }

    var stampText: (Stamp) -> String = { it.name }

    private fun widgetAt(page: EditPage, at: Offset): Pair<FormField, FormWidget>? {
        val source = page.source as? SourcePage ?: return null
        if (source.page.document !== session.main) return null
        for (field in session.fields) {
            if (field.readOnly || field.kind == FieldKind.BUTTON || field.kind == FieldKind.SIGNATURE) continue
            val widget = field.widgets.firstOrNull { it.pageIndex == source.page.index && it.rect.contains(at.x.toDouble(), at.y.toDouble()) } ?: continue
            return field to widget
        }
        return null
    }

    private fun fill(field: FormField, widget: FormWidget) {
        when (field.kind) {
            FieldKind.CHECKBOX -> {
                val on = (session.value(field) as? FieldValue.Check)?.state != null
                session.setField(field, FieldValue.Check(if (on) null else widget.onState ?: "Yes"))
            }
            FieldKind.RADIO -> session.setField(field, FieldValue.Check(widget.onState))
            FieldKind.TEXT, FieldKind.COMBO, FieldKind.LIST -> actions.editField(field)
            FieldKind.BUTTON, FieldKind.SIGNATURE -> Unit
        }
    }

    private fun crop(position: Offset, page: EditPage): StageHandler {
        val frame = page.frame
        val current = cropDraft ?: page.box
        val display = frame.displayAffine().rect(current)
        val screen = toScreen.rect(current)
        val edges = listOf(
            abs(position.x - screen.left) < 28f,
            abs(position.y - screen.top) < 28f,
            abs(position.x - screen.right) < 28f,
            abs(position.y - screen.bottom) < 28f,
        )
        val inside = screen.contains(position)
        if (edges.none { it } && !inside) return pan()
        val source = page.source
        val limit = frame.displayAffine().rect(if (source is SourcePage) source.page.mediaBox else source.mediaBox)
        return object : StageHandler {
            override val captures = true
            private var total = Offset.Zero

            override fun move(position: Offset, delta: Offset): Boolean {
                total += Offset(delta.x / (view.fit * view.zoom).toFloat(), delta.y / (view.fit * view.zoom).toFloat())
                var left = display.left
                var top = display.top
                var right = display.right
                var bottom = display.bottom
                if (edges.none { it }) {
                    val dx = total.x.coerceIn(limit.left - display.left, limit.right - display.right)
                    val dy = total.y.coerceIn(limit.top - display.top, limit.bottom - display.bottom)
                    left += dx
                    right += dx
                    top += dy
                    bottom += dy
                } else {
                    if (edges[0]) left = (display.left + total.x).coerceIn(limit.left, right - 20)
                    if (edges[1]) top = (display.top + total.y).coerceIn(limit.top, bottom - 20)
                    if (edges[2]) right = (display.right + total.x).coerceIn(left + 20, limit.right)
                    if (edges[3]) bottom = (display.bottom + total.y).coerceIn(top + 20, limit.bottom)
                }
                cropDraft = frame.toUser(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())
                return true
            }
        }
    }

    fun touches(mark: Mark, x: Double, y: Double, tolerance: Double): Boolean = when (mark) {
        is InkMark -> near(mark.path.xy, x, y, tolerance + mark.width / 2)
        is SignatureMark -> mark.bounds.inflated(tolerance).contains(x, y)
        is ShapeMark -> when (mark.kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> near(floatArrayOf(mark.x0.toFloat(), mark.y0.toFloat(), mark.x1.toFloat(), mark.y1.toFloat()), x, y, tolerance + mark.width)
            else -> mark.box.inflated(tolerance).contains(x, y)
        }
        is MarkupMark -> mark.quads.any { it.bounds().inflated(tolerance).contains(x, y) }
        else -> mark.bounds.inflated(tolerance).contains(x, y)
    }

    private fun near(xy: FloatArray, x: Double, y: Double, tolerance: Double): Boolean {
        val count = xy.size / 2
        if (count == 1) return hypot(xy[0] - x, xy[1] - y) <= tolerance
        for (i in 0 until count - 1) {
            val ax = xy[2 * i].toDouble()
            val ay = xy[2 * i + 1].toDouble()
            val bx = xy[2 * i + 2].toDouble()
            val by = xy[2 * i + 3].toDouble()
            val lx = bx - ax
            val ly = by - ay
            val length = lx * lx + ly * ly
            val t = if (length == 0.0) 0.0 else (((x - ax) * lx + (y - ay) * ly) / length).coerceIn(0.0, 1.0)
            if (hypot(ax + lx * t - x, ay + ly * t - y) <= tolerance) return true
        }
        return false
    }

    fun drawUnder(scope: DrawScope, page: EditPage) = with(scope) {
        val source = page.source as? SourcePage ?: return@with
        if (source.page.document === session.main && session.fields.isNotEmpty()) {
            for (field in session.fields) {
                val value = session.edit.fields[field.name]
                for (widget in field.widgets) {
                    if (widget.pageIndex != source.page.index) continue
                    if (value != null) with(renderer) { fieldValue(field, widget, value, toScreen) }
                    val r = toScreen.rect(widget.rect)
                    if (session.tool == EditTool.FORM && !field.readOnly) {
                        drawRect(Color(0x332196F3), r.topLeft, r.size)
                        drawRect(Color(0xFF2196F3), r.topLeft, r.size, style = Stroke(1.5f))
                    }
                }
            }
        }
        val hits = session.results.filter { it.pageId == page.id }
        for ((i, hit) in hits.withIndex()) {
            val active = session.results.indexOf(hit) == session.hit
            for (quad in hit.match.quads) {
                val p = quad.points
                drawPath(
                    polygon(listOf(toScreen.point(p[0], p[1]), toScreen.point(p[2], p[3]), toScreen.point(p[6], p[7]), toScreen.point(p[4], p[5]))),
                    if (active) Color(0xAAFF9800.toInt()) else Color(0x66FFEB3B),
                    blendMode = BlendMode.Multiply,
                )
            }
            if (i > 500) break
        }
    }

    fun drawOver(scope: DrawScope, page: EditPage) = with(scope) {
        val style = session.style
        stroke?.let { xy ->
            val tool = session.tool
            val color = Color(if (tool == EditTool.MARKER) style.markerColor else style.penColor)
            val width = (if (tool == EditTool.MARKER) style.markerWidth else style.penWidth) * scale
            drawPath(
                inkPath(xy) { x, y -> toScreen.point(x, y) },
                color,
                style = Stroke(max(width, 1f), cap = if (tool == EditTool.MARKER) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = if (tool == EditTool.MARKER) BlendMode.Multiply else BlendMode.SrcOver,
            )
        }
        dragBox?.let { (a, b) ->
            val r = Rect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
            when (session.tool) {
                EditTool.WHITEOUT -> drawRect(Color(style.coverColor), r.topLeft, r.size)
                EditTool.REDACT -> drawRect(Color.Black, r.topLeft, r.size)
                EditTool.SHAPE -> {
                    val preview = shape(user(a), user(b), 0)
                    if (preview != null) with(renderer) { draw(preview, toScreen, session.images) }
                }
                else -> Unit
            }
            outline(r, Color(0xFF1E88E5))
        }
        selection?.let { match ->
            for (quad in match.quads) {
                val p = quad.points
                drawPath(
                    polygon(listOf(toScreen.point(p[0], p[1]), toScreen.point(p[2], p[3]), toScreen.point(p[6], p[7]), toScreen.point(p[4], p[5]))),
                    Color(0x552196F3),
                )
            }
        }
        val picked = session.picked?.takeIf { it.pageId == page.id }
        hovered?.let { r ->
            if (picked == null || r != pickedRect(page, picked)) drawRect(Color(0x991E88E5), r.topLeft, r.size, style = Stroke(1f))
        }
        session.mark(session.selected)?.takeIf { session.editing != it.id }?.let { mark ->
            val live = session.preview?.takeIf { it.id == mark.id } ?: mark
            val r = screenBox(live)
            outline(r.inflate(3f), Color(0xFF1E88E5))
            if (resizable(live)) handles(r, Color(0xFF1E88E5))
        }
        if (picked != null) {
            val r = drag?.takeIf { it.keys == picked.keys }?.to ?: pickedRect(page, picked)
            outline(r.inflate(2f), Color(0xFF1E88E5))
            if (picked is PickedGraphic) handles(r, Color(0xFF1E88E5))
        }
        if (session.tool == EditTool.CROP) {
            val box = toScreen.rect(cropDraft ?: page.box)
            val shade = Color.Black.copy(alpha = 0.45f)
            drawRect(shade, Offset.Zero, Size(size.width, box.top))
            drawRect(shade, Offset(0f, box.bottom), Size(size.width, size.height - box.bottom))
            drawRect(shade, Offset(0f, box.top), Size(box.left, box.height))
            drawRect(shade, Offset(box.right, box.top), Size(size.width - box.right, box.height))
            drawRect(Color.White, box.topLeft, box.size, style = Stroke(2f))
            handles(box, Color.White)
        }
    }

    fun selectionRect(page: EditPage): Rect? {
        session.picked?.takeIf { it.pageId == page.id }?.let { return drag?.takeIf { d -> d.keys == it.keys }?.to ?: pickedRect(page, it) }
        session.mark(session.selected)?.takeIf { session.editing != it.id }?.let { return screenBox(session.preview?.takeIf { p -> p.id == it.id } ?: it) }
        selection?.let { match -> return toScreen.rect(match.bounds) }
        return null
    }

    fun key(key: Key, ctrl: Boolean, shift: Boolean): Boolean {
        if (session.editing != null) return false
        when {
            ctrl && key == Key.Z && shift -> session.redo()
            ctrl && key == Key.Z -> session.undo()
            ctrl && key == Key.Y -> session.redo()
            key == Key.Delete || key == Key.Backspace -> when {
                session.selected != null -> session.selected?.let { session.remove(it) }
                session.picked != null -> session.picked?.let { session.removeObjects(it.pageId, it.keys) }
                else -> return false
            }
            key == Key.Escape -> {
                session.selected = null
                session.picked = null
                selection = null
            }
            key == Key.PageDown || (key == Key.DirectionRight && session.selected == null && session.picked == null) -> session.current = min(session.current + 1, session.edit.pages.lastIndex)
            key == Key.PageUp || (key == Key.DirectionLeft && session.selected == null && session.picked == null) -> session.current = max(session.current - 1, 0)
            key == Key.Equals || key == Key.Plus || key == Key.NumPadAdd -> view.zoomTo(view.zoom * 1.25f, view.center, session.page)
            key == Key.Minus || key == Key.NumPadSubtract -> view.zoomTo(view.zoom / 1.25f, view.center, session.page)
            else -> return nudge(key)
        }
        return true
    }

    private fun nudge(key: Key): Boolean {
        val (du, dv) = when (key) {
            Key.DirectionLeft -> -1.0 to 0.0
            Key.DirectionRight -> 1.0 to 0.0
            Key.DirectionUp -> 0.0 to -1.0
            Key.DirectionDown -> 0.0 to 1.0
            else -> return false
        }
        val frame = session.page.frame
        val dx = frame.toUserX(du, dv) - frame.toUserX(0.0, 0.0)
        val dy = frame.toUserY(du, dv) - frame.toUserY(0.0, 0.0)
        session.mark(session.selected)?.let {
            session.replace(it.translated(dx, dy))
            return true
        }
        val picked = session.picked ?: return false
        session.transformObjects(picked.pageId, picked.keys, Affine(1.0, 0.0, 0.0, 1.0, dx, dy))
        return true
    }
}
