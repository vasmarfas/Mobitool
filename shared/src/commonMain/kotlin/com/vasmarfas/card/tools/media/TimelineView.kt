package com.vasmarfas.card.tools.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.card.core.ClipKind
import com.vasmarfas.card.core.MediaEngine
import com.vasmarfas.card.core.MediaInfo
import com.vasmarfas.card.core.PreviewState
import com.vasmarfas.card.core.TrackKind
import com.vasmarfas.card.core.decodeRawImage
import com.vasmarfas.card.core.scaled
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private val RulerHeight = 28.dp
private val VideoLane = 56.dp
private val AudioLane = 40.dp
private val HeaderWidth = 44.dp
private val HandleWidth = 14.dp
private const val MIN_ZOOM = 2f
private const val MAX_ZOOM = 800f
private const val PEAKS_PER_SECOND = 20

@Stable
internal class MediaCache(private val scope: CoroutineScope) {
    val infos = mutableStateMapOf<PlatformFile, MediaInfo>()
    val frames = mutableStateMapOf<PlatformFile, List<Pair<Long, ImageBitmap>>>()
    val peaks = mutableStateMapOf<PlatformFile, FloatArray>()
    private val requested = HashSet<PlatformFile>()

    suspend fun probe(file: PlatformFile): MediaInfo = infos[file] ?: MediaEngine.probe(file).also { infos[file] = it }

    fun prepare(file: PlatformFile, kind: ClipKind) {
        if (!requested.add(file)) return
        scope.launch {
            val info = runCatching { probe(file) }.getOrNull() ?: return@launch
            when {
                kind == ClipKind.IMAGE -> runCatching {
                    decodeRawImage(file.readBytes(), 160)?.bitmap?.let { frames[file] = listOf(0L to it.scaled(160, (160L * it.height / it.width.coerceAtLeast(1)).toInt())) }
                }
                kind == ClipKind.VIDEO && info.hasVideo && info.durationMs > 0 -> runCatching {
                    val count = (info.durationMs / 1000).coerceIn(1, 30)
                    val step = info.durationMs.toDouble() / count
                    val list = ArrayList<Pair<Long, ImageBitmap>>()
                    MediaEngine.frames(file, 0, info.durationMs, 1000.0 / step, 160) { image ->
                        list += (list.size * step).toLong() to image
                        frames[file] = list.toList()
                    }
                }
            }
            if (kind != ClipKind.IMAGE && info.hasAudio) {
                runCatching { MediaEngine.peaks(file, PEAKS_PER_SECOND, 3 * 60 * 60 * 1000L) }.onSuccess { peaks[file] = it }
            }
        }
    }
}

@Stable
internal class TimelineZoom {
    var dpPerSecond by mutableFloatStateOf(40f)

    fun zoom(factor: Float) {
        dpPerSecond = (dpPerSecond * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}

private sealed interface Drag {
    val clipId: Int

    data class Move(override val clipId: Int, val atMs: Long, val trackIndex: Int) : Drag

    data class Trim(override val clipId: Int, val start: Boolean, val edgeMs: Long) : Drag
}

private class Hit(val trackIndex: Int, val clip: EditClip?, val startEdge: Boolean?)

private const val GESTURE_UP = 0
private const val GESTURE_DRAG = 1
private const val GESTURE_PINCH = 2
private const val MODE_SCRUB = 1
private const val MODE_MOVE = 2
private const val MODE_TRIM = 3
private const val MODE_PINCH = 4

private fun laneHeight(kind: TrackKind): Dp = if (kind == TrackKind.VIDEO) VideoLane else AudioLane

private fun Timeline.displayed(): List<EditTrack> = tracks.filter { it.kind == TrackKind.VIDEO }.asReversed() + tracks.filter { it.kind == TrackKind.AUDIO }

@Composable
internal fun TimelineView(timeline: Timeline, cache: MediaCache, state: PreviewState, zoom: TimelineZoom, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    var drag by remember { mutableStateOf<Drag?>(null) }
    val haptics = LocalHapticFeedback.current
    val tracks = timeline.displayed()
    val lanesHeight = tracks.fold(RulerHeight) { h, t -> h + laneHeight(t.kind) }

    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHighest)) {
        Column(Modifier.width(HeaderWidth)) {
            Spacer(Modifier.height(RulerHeight))
            tracks.forEach { track ->
                TrackHeader(track, timeline.tracks.filter { it.kind == track.kind }.indexOf(track) + 1, timeline)
            }
        }
        Box(Modifier.weight(1f)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(lanesHeight)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        fun pxPerMs() = zoom.dpPerSecond * density.density / 1000f
                        fun timeAt(x: Float) = state.positionMs + ((x - size.width / 2f) / pxPerMs()).toLong()
                        fun trackAt(y: Float): Int {
                            var top = RulerHeight.toPx()
                            val shown = timeline.displayed()
                            shown.forEachIndexed { i, t ->
                                val bottom = top + laneHeight(t.kind).toPx()
                                if (y < bottom) return if (y < top) -1 else i
                                top = bottom
                            }
                            return shown.lastIndex
                        }
                        fun hitAt(position: Offset): Hit {
                            val index = trackAt(position.y)
                            val track = timeline.displayed().getOrNull(index) ?: return Hit(-1, null, null)
                            val t = timeAt(position.x)
                            val slop = (HandleWidth.toPx() / pxPerMs()).toLong()
                            val selected = track.clips.firstOrNull { it.id == timeline.selectedId }
                            if (selected != null) {
                                if (abs(t - selected.clip.atMs) <= slop) return Hit(index, selected, true)
                                if (abs(t - selected.clip.endAtMs) <= slop) return Hit(index, selected, false)
                            }
                            return Hit(index, track.clips.firstOrNull { t >= it.clip.atMs && t < it.clip.endAtMs }, null)
                        }
                        fun snap(ms: Long, ignoreId: Int): Long {
                            val reach = (10.dp.toPx() / pxPerMs()).toLong()
                            val candidates = timeline.tracks.flatMap { t -> t.clips.filter { it.id != ignoreId }.flatMap { listOf(it.clip.atMs, it.clip.endAtMs) } } + state.positionMs + 0L
                            return candidates.minByOrNull { abs(it - ms) }?.takeIf { abs(it - ms) <= reach } ?: ms
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val hit = hitAt(down.position)
                            val startPosition = state.positionMs
                            val onHandle = hit.clip != null && hit.clip.id == timeline.selectedId && hit.startEdge != null
                            var dx = 0f
                            var dy = 0f
                            var wheel = false
                            val first = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var outcome = -1
                                while (outcome < 0) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (event.type == PointerEventType.Scroll) {
                                        val scroll = event.changes.first().scrollDelta
                                        if (event.keyboardModifiers.isCtrlPressed) zoom.zoom(if (scroll.y < 0) 1.15f else 1 / 1.15f)
                                        else state.seek((state.positionMs + (scroll.x + scroll.y) * 40 / pxPerMs() / 10).toLong().coerceIn(0, timeline.durationMs))
                                        event.changes.forEach { it.consume() }
                                        wheel = true
                                        outcome = GESTURE_UP
                                    } else if (pressed.isEmpty()) {
                                        outcome = GESTURE_UP
                                    } else if (pressed.size >= 2) {
                                        outcome = GESTURE_PINCH
                                    } else {
                                        val delta = pressed.first().positionChange()
                                        dx += delta.x
                                        dy += delta.y
                                        if (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop) outcome = GESTURE_DRAG
                                    }
                                }
                                outcome
                            }
                            if (wheel) return@awaitEachGesture
                            val clip = hit.clip
                            val mode = when (first) {
                                null -> if (clip != null) MODE_MOVE else return@awaitEachGesture
                                GESTURE_PINCH -> MODE_PINCH
                                GESTURE_DRAG -> when {
                                    onHandle -> MODE_TRIM
                                    abs(dx) > abs(dy) -> MODE_SCRUB
                                    else -> return@awaitEachGesture
                                }
                                else -> {
                                    when {
                                        hit.trackIndex < 0 -> {
                                            state.playing = false
                                            state.seek(timeAt(down.position.x).coerceIn(0, timeline.durationMs))
                                        }
                                        clip != null -> timeline.select(clip.id)
                                        else -> {
                                            timeline.select(null)
                                            timeline.activeTrackId = timeline.displayed()[hit.trackIndex].id
                                        }
                                    }
                                    return@awaitEachGesture
                                }
                            }
                            if (mode == MODE_MOVE && clip != null) {
                                timeline.select(clip.id)
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (mode == MODE_SCRUB) state.playing = false
                            var pinchFrom = 0f
                            val zoomFrom = zoom.dpPerSecond
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (mode == MODE_PINCH) {
                                    if (pressed.size >= 2) {
                                        val distance = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                                        if (pinchFrom <= 0f) pinchFrom = distance else zoom.dpPerSecond = (zoomFrom * distance / pinchFrom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    }
                                    pressed.forEach { it.consume() }
                                    continue
                                }
                                val change = pressed.first()
                                val delta = change.positionChange()
                                dx += delta.x
                                dy += delta.y
                                val shiftMs = (dx / pxPerMs()).toLong()
                                when (mode) {
                                    MODE_SCRUB -> state.seek((startPosition - shiftMs).coerceIn(0, timeline.durationMs))
                                    MODE_MOVE -> if (clip != null) {
                                        val wanted = (clip.clip.atMs + shiftMs).coerceAtLeast(0)
                                        val snappedStart = snap(wanted, clip.id)
                                        val snappedEnd = snap(wanted + clip.clip.durationMs, clip.id) - clip.clip.durationMs
                                        val at = when {
                                            snappedStart != wanted && abs(snappedStart - wanted) <= abs(snappedEnd - wanted) -> snappedStart
                                            snappedEnd != wanted -> snappedEnd
                                            else -> wanted
                                        }
                                        val shown = timeline.displayed()
                                        val target = trackAt(change.position.y).takeIf { it >= 0 && shown[it].kind == shown[hit.trackIndex].kind } ?: hit.trackIndex
                                        drag = Drag.Move(clip.id, at.coerceAtLeast(0), target)
                                    }
                                    MODE_TRIM -> if (clip != null) {
                                        val start = hit.startEdge == true
                                        val edge = (if (start) clip.clip.atMs else clip.clip.endAtMs) + shiftMs
                                        drag = Drag.Trim(clip.id, start, snap(edge, clip.id))
                                    }
                                }
                                change.consume()
                            }
                            when (val done = drag) {
                                is Drag.Move -> timeline.move(done.clipId, timeline.displayed()[done.trackIndex].id, done.atMs)
                                is Drag.Trim -> if (done.start) timeline.trimStart(done.clipId, done.edgeMs) else timeline.trimEnd(done.clipId, done.edgeMs)
                                null -> Unit
                            }
                            drag = null
                        }
                    },
            ) {
                val pxPerMs = zoom.dpPerSecond * density.density / 1000f
                val center = size.width / 2f
                fun x(ms: Long) = center + (ms - state.positionMs) * pxPerMs
                drawRuler(state.positionMs, pxPerMs, colors.onSurfaceVariant, measurer, labelStyle)
                var top = RulerHeight.toPx()
                tracks.forEachIndexed { index, track ->
                    val height = laneHeight(track.kind).toPx()
                    drawRect(if (index % 2 == 0) colors.surfaceContainerHigh else colors.surfaceContainer, Offset(0f, top), Size(size.width, height))
                    val lane = top
                    track.clips.forEach { edit ->
                        val moving = drag?.clipId == edit.id
                        if (moving && drag is Drag.Move) return@forEach
                        var clip = edit.clip
                        val trim = drag as? Drag.Trim
                        if (moving && trim != null) {
                            clip = if (trim.start) {
                                val shift = trim.edgeMs.coerceIn(0, clip.endAtMs - Timeline.MIN_CLIP_MS) - clip.atMs
                                if (edit.trimmable) clip.copy(atMs = clip.atMs + shift, startMs = (clip.startMs + shift).coerceAtLeast(0)) else clip.copy(atMs = clip.atMs + shift, endMs = clip.endMs - shift)
                            } else {
                                clip.copy(endMs = clip.startMs + (trim.edgeMs - clip.atMs).coerceAtLeast(Timeline.MIN_CLIP_MS))
                            }
                        }
                        drawClip(edit.copy(clip = clip), x(clip.atMs), x(clip.endAtMs), lane, height, edit.id == timeline.selectedId, cache, pxPerMs, colors.primary, if (track.kind == TrackKind.VIDEO) colors.secondaryContainer else colors.tertiaryContainer, colors.onSecondaryContainer, measurer, labelStyle, track.hidden || track.muted)
                    }
                    top += height
                }
                (drag as? Drag.Move)?.let { move ->
                    val edit = tracks.flatMap { it.clips }.firstOrNull { it.id == move.clipId } ?: return@let
                    var laneTop = RulerHeight.toPx()
                    for (i in 0 until move.trackIndex) laneTop += laneHeight(tracks[i].kind).toPx()
                    val height = laneHeight(tracks[move.trackIndex].kind).toPx()
                    val clip = edit.clip.copy(atMs = move.atMs)
                    drawClip(edit.copy(clip = clip), x(clip.atMs), x(clip.endAtMs), laneTop, height, true, cache, pxPerMs, colors.primary, colors.primaryContainer, colors.onPrimaryContainer, measurer, labelStyle, false)
                }
                drawLine(colors.primary, Offset(center, 0f), Offset(center, size.height), 2.dp.toPx())
                drawCircle(colors.primary, 4.dp.toPx(), Offset(center, 4.dp.toPx()))
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .background(Brush.horizontalGradient(0f to Color.Transparent, 0.3f to colors.surfaceContainerHighest))
                    .padding(start = 16.dp),
            ) {
                ZoomButton(Icons.Filled.ZoomOut, Res.string.zoom_out.str()) { zoom.zoom(1 / 1.5f) }
                ZoomButton(Icons.Filled.ZoomIn, Res.string.zoom_in.str()) { zoom.zoom(1.5f) }
            }
        }
    }
}

@Composable
private fun ZoomButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(RulerHeight)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackHeader(track: EditTrack, number: Int, timeline: Timeline) {
    var menu by remember { mutableStateOf(false) }
    val off = if (track.kind == TrackKind.VIDEO) track.hidden else track.muted
    val active = timeline.trackFor(track.kind).id == track.id
    Box(
        Modifier
            .height(laneHeight(track.kind))
            .fillMaxWidth()
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable {
                timeline.activeTrackId = track.id
                menu = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Icon(
                when {
                    off && track.kind == TrackKind.VIDEO -> Icons.Filled.VisibilityOff
                    off -> Icons.AutoMirrored.Filled.VolumeOff
                    track.kind == TrackKind.VIDEO -> Icons.Filled.Movie
                    else -> Icons.Filled.MusicNote
                },
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (off) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${if (track.kind == TrackKind.VIDEO) "V" else "A"}$number", style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (track.kind == TrackKind.VIDEO) {
                DropdownMenuItem(
                    text = { Text(if (track.hidden) Res.string.show_track.str() else Res.string.hide_track.str()) },
                    onClick = {
                        timeline.toggleHidden(track.id)
                        menu = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(if (track.muted) Res.string.unmute_track.str() else Res.string.mute_track.str()) },
                onClick = {
                    timeline.toggleMuted(track.id)
                    menu = false
                },
            )
            if (timeline.tracks.count { it.kind == track.kind } > 1) {
                DropdownMenuItem(
                    text = { Text(Res.string.remove_track.str()) },
                    onClick = {
                        timeline.removeTrack(track.id)
                        menu = false
                    },
                )
            }
        }
    }
}

private val rulerSteps = longArrayOf(100, 200, 500, 1000, 2000, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)

private fun DrawScope.drawRuler(positionMs: Long, pxPerMs: Float, color: Color, measurer: TextMeasurer, style: TextStyle) {
    val height = RulerHeight.toPx()
    val step = rulerSteps.firstOrNull { it * pxPerMs >= 64.dp.toPx() } ?: rulerSteps.last()
    val halfSpan = (size.width / 2f / pxPerMs).toLong()
    var t = ((positionMs - halfSpan) / step - 1).coerceAtLeast(0) * step
    while (t <= positionMs + halfSpan + step) {
        val x = size.width / 2f + (t - positionMs) * pxPerMs
        drawLine(color, Offset(x, height * 0.55f), Offset(x, height), 1.dp.toPx())
        val minor = x + step * pxPerMs / 2
        drawLine(color.copy(alpha = 0.5f), Offset(minor, height * 0.78f), Offset(minor, height), 1.dp.toPx())
        val label = measurer.measure(rulerLabel(t, step), style.copy(color = color))
        drawText(label, topLeft = Offset(x + 3.dp.toPx(), 1.dp.toPx()))
        t += step
    }
}

private fun rulerLabel(ms: Long, step: Long): String {
    val minutes = ms / 60_000
    val seconds = ms / 1000 % 60
    val base = "$minutes:${seconds.toString().padStart(2, '0')}"
    return if (step < 1000) base + "." + (ms % 1000 / 100) else base
}

private fun DrawScope.drawClip(
    edit: EditClip,
    left: Float,
    right: Float,
    top: Float,
    height: Float,
    selected: Boolean,
    cache: MediaCache,
    pxPerMs: Float,
    accent: Color,
    container: Color,
    onContainer: Color,
    measurer: TextMeasurer,
    style: TextStyle,
    dimmed: Boolean,
) {
    if (right < 0 || left > size.width) return
    val clip = edit.clip
    val inset = 2.dp.toPx()
    val box = Rect(left + 1, top + inset, right - 1, top + height - inset)
    if (box.width <= 0f) return
    val corner = CornerRadius(6.dp.toPx())
    val outline = Path().apply { addRoundRect(RoundRect(box, corner)) }
    clipPath(outline) {
        drawRect(container, box.topLeft, box.size)
        val frames = cache.frames[clip.file]
        if (clip.kind != ClipKind.AUDIO && !frames.isNullOrEmpty()) {
            val first = frames.first().second
            val tileWidth = (box.height * first.width / first.height.coerceAtLeast(1)).coerceAtLeast(8f)
            var x = box.left
            while (x < box.right && x < size.width) {
                if (x + tileWidth >= 0) {
                    val sourceMs = clip.startMs + ((x + tileWidth / 2 - left) / pxPerMs).toLong()
                    val image = frames.minByOrNull { abs(it.first - sourceMs) }!!.second
                    drawImage(
                        image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(image.width, image.height),
                        dstOffset = IntOffset(x.roundToInt(), box.top.roundToInt()),
                        dstSize = IntSize(tileWidth.roundToInt(), box.height.roundToInt()),
                        alpha = if (dimmed) 0.35f else 1f,
                    )
                }
                x += tileWidth
            }
        }
        val peaks = cache.peaks[clip.file]
        if (peaks != null && clip.kind != ClipKind.IMAGE && clip.volume > 0f) {
            val band = if (clip.kind == ClipKind.AUDIO) box.height else box.height / 3
            val baseline = box.bottom - band / 2
            val step = 2.dp.toPx()
            var x = maxOf(box.left, 0f)
            while (x < minOf(box.right, size.width)) {
                val sourceMs = clip.startMs + ((x - left) / pxPerMs).toLong()
                val peak = peaks.getOrNull((sourceMs * PEAKS_PER_SECOND / 1000).toInt()) ?: 0f
                val h = (peak * band * 0.9f).coerceAtLeast(1f)
                drawLine(onContainer.copy(alpha = if (dimmed) 0.3f else 0.75f), Offset(x, baseline - h / 2), Offset(x, baseline + h / 2), 1.dp.toPx())
                x += step
            }
        }
        if (clip.fadeInMs > 0) drawFade(box.left, box.left + clip.fadeInMs * pxPerMs, box, true)
        if (clip.fadeOutMs > 0) drawFade(box.right - clip.fadeOutMs * pxPerMs, box.right, box, false)
        val name = measurer.measure(edit.clip.file.name, style.copy(color = Color.White), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (box.width - 8.dp.toPx()).coerceAtLeast(1f).toInt()))
        if (box.width > 24.dp.toPx()) {
            drawRoundRect(Color.Black.copy(alpha = 0.45f), Offset(box.left + 2.dp.toPx(), box.top + 2.dp.toPx()), Size(name.size.width + 6.dp.toPx(), name.size.height.toFloat()), CornerRadius(3.dp.toPx()))
            drawText(name, topLeft = Offset(box.left + 5.dp.toPx(), box.top + 2.dp.toPx()))
        }
    }
    if (selected) {
        drawRoundRect(accent, box.topLeft, box.size, corner, style = Stroke(2.dp.toPx()))
        val handle = HandleWidth.toPx() / 2
        listOf(box.left, box.right - handle).forEach { hx ->
            drawRoundRect(accent, Offset(hx, box.top), Size(handle, box.height), CornerRadius(3.dp.toPx()))
            drawLine(Color.White, Offset(hx + handle / 2, box.top + box.height * 0.3f), Offset(hx + handle / 2, box.bottom - box.height * 0.3f), 1.5.dp.toPx())
        }
    }
}

private fun DrawScope.drawFade(from: Float, to: Float, box: Rect, rising: Boolean) {
    if (to <= from) return
    val path = Path().apply {
        if (rising) {
            moveTo(from, box.top)
            lineTo(from, box.bottom)
            lineTo(to, box.top)
        } else {
            moveTo(to, box.top)
            lineTo(to, box.bottom)
            lineTo(from, box.top)
        }
        close()
    }
    drawPath(path, Color.Black.copy(alpha = 0.35f))
}
