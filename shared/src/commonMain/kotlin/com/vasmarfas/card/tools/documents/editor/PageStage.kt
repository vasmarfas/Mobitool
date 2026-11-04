package com.vasmarfas.card.tools.documents.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.tools.documents.pdf.PdfRect
import com.vasmarfas.card.ui.components.LocalChrome
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val MAX_ZOOM = 8f

@Stable
internal class StageView {
    var zoom by mutableStateOf(1f)
    var origin by mutableStateOf(Offset.Zero)
    var width by mutableStateOf(1f)
    var height by mutableStateOf(1f)
    var fit by mutableStateOf(1.0)

    val center: Offset get() = Offset(width / 2, height / 2)

    fun reset() {
        zoom = 1f
        origin = Offset.Zero
    }

    fun drawnOrigin(page: EditPage): Offset {
        val frame = page.frame
        val w = (frame.width * fit * zoom).toFloat()
        val h = (frame.height * fit * zoom).toFloat()
        val x = if (w <= width) (width - w) / 2 else origin.x.coerceIn(width - w, 0f)
        val y = if (h <= height) (height - h) / 2 else origin.y.coerceIn(height - h, 0f)
        return Offset(x, y)
    }

    fun zoomTo(next: Float, at: Offset, page: EditPage) {
        val value = next.coerceIn(1f, MAX_ZOOM)
        val o = drawnOrigin(page)
        val factor = value / zoom
        origin = Offset(at.x - (at.x - o.x) * factor, at.y - (at.y - o.y) * factor)
        zoom = value
    }
}

private class Tile(val page: Long, val objects: Map<Int, ObjectEdit>, val bitmap: ImageBitmap, val scale: Double, val x: Int, val y: Int)

private class Base(val bitmap: ImageBitmap, val k: Double, val objects: Map<Int, ObjectEdit>)

@Composable
internal fun PageStage(session: EditorSession, renderer: MarkRenderer, input: StageInput, view: StageView, overlay: @Composable BoxScope.() -> Unit) {
    val page = session.page
    val frame = page.frame
    val density = LocalDensity.current
    val immersive = LocalChrome.current.immersive
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val focus = remember { FocusRequester() }
    val slop = LocalViewConfiguration.current.touchSlop
    val currentPage by rememberUpdatedState(page)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxStage = (windowHeight - if (immersive) 150.dp else 300.dp).coerceAtLeast(320.dp)
        val height = min(maxWidth.value * (frame.height / frame.width).toFloat(), maxStage.value).coerceAtLeast(240f).dp
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { height.toPx() }
        val fit = min(widthPx / frame.width, heightPx / frame.height)
        view.width = widthPx
        view.height = heightPx
        view.fit = fit
        val zoom = view.zoom
        val (originX, originY) = view.drawnOrigin(page)
        val toScreen = frame.displayAffine().scaled(fit * zoom, originX.toDouble(), originY.toDouble())
        input.toScreen = toScreen

        var base by remember(page.source) { mutableStateOf<Base?>(null) }
        var ghost by remember { mutableStateOf<Base?>(null) }
        var tile by remember { mutableStateOf<Tile?>(null) }
        val source = page.source
        val sourceFrame = PageFrame(source.cropBox, source.rotation)
        val baseWidth = ((sourceFrame.width * fit * 1.25).roundToInt() / 256 + 1).coerceAtMost(12) * 256
        val drag = input.drag
        // while a picked object is dragged the page is drawn without it, and its ghost is cut from the page drawn before
        val shown = if (drag != null && !drag.done) page.objects + drag.keys.associateWith { ObjectEdit(removed = true) } else page.objects
        LaunchedEffect(page.source, shown, baseWidth) {
            if (source is SourcePage) session.render(source, baseWidth, shown)?.let { base = Base(it, it.width / sourceFrame.width, shown) }
        }
        LaunchedEffect(drag == null) { ghost = if (drag == null) null else base }
        LaunchedEffect(base, drag) {
            if (drag?.done != true) return@LaunchedEffect
            if (base?.objects != page.objects) delay(4000)
            input.drag = null
        }
        LaunchedEffect(page.id, shown, zoom, originX, originY, fit) {
            tile = tile?.takeIf { it.page == page.id && it.objects == shown && abs(it.scale - fit * zoom) < 1e-6 }
            if (source !is SourcePage || zoom < 1.3f) {
                tile = null
                return@LaunchedEffect
            }
            delay(220)
            val scale = fit * zoom
            val toSource = toScreen.inverse().then(sourceFrame.displayAffine()).scaled(scale)
            val corners = listOf(toSource.point(0.0, 0.0), toSource.point(widthPx.toDouble(), 0.0), toSource.point(0.0, heightPx.toDouble()), toSource.point(widthPx.toDouble(), heightPx.toDouble()))
            val fullW = sourceFrame.width * scale
            val fullH = sourceFrame.height * scale
            val x = corners.minOf { it.x }.toDouble().coerceIn(0.0, fullW).toInt()
            val y = corners.minOf { it.y }.toDouble().coerceIn(0.0, fullH).toInt()
            val right = corners.maxOf { it.x }.toDouble().coerceIn(0.0, fullW).roundToInt()
            val bottom = corners.maxOf { it.y }.toDouble().coerceIn(0.0, fullH).roundToInt()
            if (right - x < 8 || bottom - y < 8 || (right - x).toLong() * (bottom - y) > 16_000_000L) return@LaunchedEffect
            session.render(source, shown, fullW.roundToInt(), x, y, right - x, bottom - y)?.let { tile = Tile(page.id, shown, it, scale, x, y) }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .focusRequester(focus)
                .focusTarget()
                .onKeyEvent { event -> event.type == KeyEventType.KeyDown && input.key(event.key, event.isCtrlPressed || event.isMetaPressed, event.isShiftPressed) }
                .pointerHoverIcon(if (input.hovered != null) PointerIcon.Hand else PointerIcon.Default)
                .pointerInput(page.id, fit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (input.textBounds?.contains(down.position) == true) return@awaitEachGesture
                        val typing = session.editing != null
                        runCatching { focus.requestFocus() }
                        val handler = input.begin(down.position, currentPage, typing, mouse = down.type == PointerType.Mouse)
                        var moved = false
                        var multi = false
                        if (handler.captures) down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Scroll) break
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                if (!multi) handler.cancel()
                                multi = true
                                val centroid = event.calculateCentroid()
                                val change = event.calculateZoom()
                                val pan = event.calculatePan()
                                view.zoomTo(view.zoom * change, centroid, currentPage)
                                view.origin += pan
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (!multi) handler.end(change.position, tap = !moved)
                                change.consume()
                                break
                            }
                            if (multi) continue
                            if (!moved && (change.position - down.position).getDistance() > slop) moved = true
                            if (moved) {
                                val consumed = handler.move(change.position, change.position - change.previousPosition)
                                if (consumed) change.consume()
                            }
                        }
                    }
                }
                .pointerInput(page.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    val delta = change.scrollDelta
                                    val ctrl = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                                    if (ctrl) {
                                        view.zoomTo(view.zoom * if (delta.y < 0) 1.15f else 1 / 1.15f, change.position, currentPage)
                                        event.changes.forEach { it.consume() }
                                    } else if (view.zoom > 1f) {
                                        view.origin = view.drawnOrigin(currentPage) - delta * 40f
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                PointerEventType.Move -> if (change.type == PointerType.Mouse && !change.pressed) input.hover(change.position, currentPage)
                                PointerEventType.Exit -> input.hover(null, currentPage)
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val pageRect = toScreen.rect(page.box)
                drawRect(Color.White, pageRect.topLeft, pageRect.size)
                clipRect(pageRect.left, pageRect.top, pageRect.right, pageRect.bottom) {
                    val fromBitmap = sourceFrame.displayAffine().inverse().then(toScreen)
                    when (source) {
                        is SourcePage -> {
                            base?.let { b -> bitmap(b.bitmap, Affine(1 / b.k, 0.0, 0.0, 1 / b.k, 0.0, 0.0).then(fromBitmap)) }
                            tile?.takeIf { it.page == page.id && it.objects == shown && abs(it.scale - fit * zoom) < 1e-6 }?.let { t ->
                                bitmap(t.bitmap, Affine(1 / t.scale, 0.0, 0.0, 1 / t.scale, t.x / t.scale, t.y / t.scale).then(fromBitmap))
                            }
                            val g = ghost
                            if (drag != null && g != null) {
                                val sx = (drag.to.width / drag.from.width.coerceAtLeast(1f)).toDouble()
                                val sy = (drag.to.height / drag.from.height.coerceAtLeast(1f)).toDouble()
                                val move = Affine(sx, 0.0, 0.0, sy, drag.to.left - drag.from.left * sx, drag.to.top - drag.from.top * sy)
                                // text is multiplied in, so the paper around its letters does not cover what is under it
                                clipRect(drag.to.left, drag.to.top, drag.to.right, drag.to.bottom) {
                                    bitmap(g.bitmap, Affine(1 / g.k, 0.0, 0.0, 1 / g.k, 0.0, 0.0).then(fromBitmap).then(move), if (drag.text) BlendMode.Multiply else BlendMode.SrcOver)
                                }
                            }
                        }
                        is ImagePage -> session.images[source.image]?.let { bitmap ->
                            bitmap(bitmap, Affine(source.width / bitmap.width, 0.0, 0.0, -source.height / bitmap.height, 0.0, source.height).then(toScreen))
                        }
                        is BlankPage -> Unit
                    }
                    input.drawUnder(this, currentPage)
                    with(renderer) {
                        for (mark in session.marks(currentPage)) {
                            if (mark.id == session.editing || mark.id in input.erased) continue
                            draw(mark, toScreen, session.images)
                        }
                        decorations(currentPage, session.current, session.edit.pages.size, session.edit, toScreen)
                    }
                    input.drawOver(this, currentPage)
                }
            }
            session.mark(session.editing)?.let { mark -> if (mark is TextMark) InlineText(session, input, mark, toScreen, fit * zoom) }
            overlay()
        }
    }
}

private fun DrawScope.bitmap(bitmap: ImageBitmap, m: Affine, blend: BlendMode = BlendMode.SrcOver) {
    withTransform({ transform(m.toMatrix()) }) {
        drawImage(bitmap, IntOffset.Zero, IntSize(bitmap.width, bitmap.height), blendMode = blend, filterQuality = FilterQuality.Medium)
    }
}

@Composable
private fun InlineText(session: EditorSession, input: StageInput, mark: TextMark, toScreen: Affine, scale: Double) {
    val density = LocalDensity.current
    val rect = toScreen.rect(mark.box)
    val bounds = Rect(rect.topLeft, Size(max(rect.width, 48f), max(rect.height, (mark.size * scale * 1.4).toFloat())))
    val focus = remember(mark.id) { FocusRequester() }
    var value by remember(mark.id) { mutableStateOf(TextFieldValue(mark.text)) }
    var spans by remember(mark.id, mark.spans) { mutableStateOf(mark.withText(value.text).spans) }
    var focused by remember(mark.id) { mutableStateOf(false) }
    LaunchedEffect(mark.id) { runCatching { focus.requestFocus() } }
    SideEffect { input.textBounds = bounds }
    DisposableEffect(mark.id) {
        onDispose {
            input.textBounds = null
            session.textRange = null
            input.finishText(mark.id, value.text, spans)
        }
    }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            if (next.text != value.text) {
                val changed = mark.copy(text = value.text, spans = spans).withText(next.text)
                spans = changed.spans
                session.preview = changed
            }
            value = next
            session.textRange = next.selection.takeUnless { it.collapsed }
        },
        textStyle = TextStyle(
            color = Color(mark.color),
            fontSize = with(density) { (mark.size * scale).toFloat().toSp() },
            fontWeight = if (mark.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (mark.italic) FontStyle.Italic else FontStyle.Normal,
        ),
        visualTransformation = { TransformedText(styled(it.text, spans), OffsetMapping.Identity) },
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier
            .offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
            .size(with(density) { bounds.width.toDp() }, with(density) { bounds.height.toDp() })
            .background(Color.White.copy(alpha = 0.85f))
            .border(1.dp, MaterialTheme.colorScheme.primary)
            .padding(with(density) { (mark.size * scale * 0.2).toFloat().toDp() })
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) input.finishText(mark.id, value.text, spans)
                focused = state.isFocused
            },
    )
}

private fun styled(text: String, spans: List<StyleSpan>): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        val style = SpanStyle(
            color = Color(span.color),
            fontWeight = if (span.bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
        )
        addStyle(style, span.start, span.end)
    }
}

internal fun DrawScope.outline(rect: Rect, color: Color) {
    drawRect(color, rect.topLeft, rect.size, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
}

internal fun DrawScope.handles(rect: Rect, color: Color) {
    val r = 5.dp.toPx()
    for (corner in listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight)) {
        drawCircle(Color.White, r + 1.5f, corner)
        drawCircle(color, r, corner)
    }
}

internal fun PdfRect.normalized(): PdfRect = PdfRect(min(left, right), min(bottom, top), max(left, right), max(bottom, top))
