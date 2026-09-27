package com.vasmarfas.card.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource

data class ChartSeries(
    val label: String,
    val color: Color,
    val points: List<Float>,
    val colorAt: ((Int) -> Color)? = null,
    val tooltip: ((Int) -> String)? = null,
)

enum class ChartKind { LINE, BAR, AREA }

private val ChartHeight = 180.dp

@Composable
private fun chartHeight(): Dp = expandedHeight(normal = ChartHeight, reserved = 340.dp)

private const val TICKS = 5

private val windowOptions = listOf(0, 60, 120, 300)

@Composable
fun InteractiveChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    symmetric: Boolean = false,
    xLabel: (Int) -> String = { it.toString() },
    yFormat: (Float) -> String = { it.toDouble().fmt(1) },
    kind: ChartKind = ChartKind.LINE,
    // a spectrum has no time axis, so the window chips would offer a choice that means nothing
    windows: Boolean = true,
) {
    val count = series.maxOfOrNull { it.points.size } ?: 0
    if (count == 0) {
        Box(
            modifier = modifier.fillMaxWidth().height(chartHeight()),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(icon = Icons.AutoMirrored.Filled.ShowChart, title = Res.string.no_data_yet.str())
        }
        return
    }

    var windowSize by rememberSaveable { mutableStateOf(0) }
    var windowStart by remember { mutableStateOf<Int?>(null) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var hovered by remember { mutableStateOf<Int?>(null) }

    val visible = if (windowSize <= 0) count else windowSize.coerceAtMost(count)
    val start = (windowStart ?: (count - visible)).coerceIn(0, count - visible)
    val end = start + visible
    val bars = kind == ChartKind.BAR
    val range = remember(series, start, end, symmetric, bars) { chartRange(series, start, end, symmetric, bars) }
    val ticks = remember(range) { List(TICKS) { range.axisMin + (range.axisMax - range.axisMin) * it / (TICKS - 1) } }

    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = monoFamily(),
    )
    val tickLabels = remember(ticks, labelStyle, yFormat) { ticks.map { measurer.measure(yFormat(it), labelStyle) } }
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val crosshairColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val strokeWidth = with(density) { 2.dp.toPx() }
    val gutter = with(density) { 6.dp.toPx() }
    val inset = with(density) { 6.dp.toPx() }
    val dotRadius = with(density) { 3.5.dp.toPx() }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (series.size > 1) {
            ChartLegend(series)
        }
        BoxWithConstraints(modifier.fillMaxWidth().height(chartHeight())) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val left = tickLabels.maxOf { it.size.width }.toFloat() + gutter
            val plotWidth = (widthPx - left - inset).coerceAtLeast(1f)
            val axisIndices = remember(start, end) { listOf(start, (start + end - 1) / 2, end - 1).distinct() }
            val axisLabels = remember(axisIndices, labelStyle, xLabel) { axisIndices.map { measurer.measure(xLabel(it), labelStyle) } }
            val axisHeight = axisLabels.maxOf { it.size.height }.toFloat() + gutter
            val plotHeight = (heightPx - inset * 2 - axisHeight).coerceAtLeast(1f)
            val metrics = PlotMetrics(left, plotWidth, start, visible, count, bars)
            val shapes = remember(series, metrics, range, plotHeight, inset, kind) {
                series.map { buildShape(it, metrics, range, inset, plotHeight, kind) }
            }
            val yOf: (Float) -> Float = { inset + plotHeight - (it - range.axisMin) / (range.axisMax - range.axisMin) * plotHeight }
            val current by rememberUpdatedState(metrics)
            val active = (selected ?: hovered)?.takeIf { it in start until end }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val hit = current.indexAt(offset.x)
                            selected = if (offset.x < current.left || hit == selected) null else hit
                        }
                    }
                    .pointerInput(Unit) {
                        var base = 0
                        var origin = 0f
                        detectHorizontalDragGestures(
                            onDragStart = {
                                base = current.start
                                origin = it.x
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val plot = current
                                if (plot.visible < plot.total) {
                                    val shift = ((change.position.x - origin) / plot.slot).roundToInt()
                                    windowStart = (base - shift).coerceIn(0, plot.total - plot.visible)
                                } else {
                                    selected = plot.indexAt(change.position.x)
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Move -> event.changes.firstOrNull()
                                        ?.takeIf { !it.pressed }
                                        ?.let { hovered = current.indexAt(it.position.x) }
                                    PointerEventType.Exit -> hovered = null
                                    else -> Unit
                                }
                            }
                        }
                    },
            ) {
                ticks.forEachIndexed { index, tick ->
                    val y = yOf(tick)
                    drawLine(gridColor, Offset(left, y), Offset(size.width, y), 1f)
                    drawText(
                        textLayoutResult = tickLabels[index],
                        topLeft = Offset(left - gutter - tickLabels[index].size.width, y - tickLabels[index].size.height / 2f),
                    )
                }
                axisIndices.forEachIndexed { index, point ->
                    val label = axisLabels[index]
                    val anchor = metrics.xAt(point) - label.size.width / 2f
                    drawText(
                        textLayoutResult = label,
                        topLeft = Offset(
                            anchor.coerceIn(left, (left + plotWidth - label.size.width).coerceAtLeast(left)),
                            inset + plotHeight + gutter,
                        ),
                    )
                }
                shapes.forEachIndexed { index, shape ->
                    shape.fills.forEach { (color, path) -> drawPath(path, color) }
                    shape.stroke?.let {
                        drawPath(it, series[index].color, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                if (active != null) {
                    val x = metrics.xAt(active)
                    drawLine(crosshairColor, Offset(x, inset), Offset(x, inset + plotHeight), 1f)
                    series.forEach { line ->
                        val value = line.points.getOrNull(active) ?: return@forEach
                        drawCircle(line.colorAt?.invoke(active) ?: line.color, dotRadius, Offset(x, yOf(value)))
                    }
                }
            }

            if (active != null) {
                // the card goes to the other half from its point, so a peak is never covered by its own reading
                val point = series.firstNotNullOfOrNull { it.points.getOrNull(active) }
                val high = point != null && yOf(point) < inset + plotHeight / 2f
                ChartTooltip(
                    header = xLabel(active),
                    entries = series.mapNotNull { line ->
                        val value = line.points.getOrNull(active) ?: return@mapNotNull null
                        ChartEntry(line.colorAt?.invoke(active) ?: line.color, line.label, line.tooltip?.invoke(active) ?: yFormat(value))
                    },
                    modifier = Modifier
                        .align(
                            when {
                                metrics.xAt(active) > widthPx / 2 -> if (high) Alignment.BottomStart else Alignment.TopStart
                                else -> if (high) Alignment.BottomEnd else Alignment.TopEnd
                            },
                        )
                        .padding(4.dp),
                )
            }
        }
        Text(
            text = listOf(
                "${Res.string.unit_min.str()} ${yFormat(range.low)}",
                "${Res.string.chart_avg.str()} ${yFormat(range.average)}",
                "${Res.string.chart_max.str()} ${yFormat(range.high)}",
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = monoFamily()),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val choices = windowOptions.filter { it == 0 || it < count }
        if (windows && choices.size > 1) {
            Text(Res.string.chart_show.str(), style = MaterialTheme.typography.labelLarge)
            ChoiceChips(
                options = choices,
                selected = if (windowSize <= 0) 0 else windowSize,
                onSelect = {
                    windowSize = it
                    windowStart = null
                },
                label = { if (it == 0) Res.string.chart_all_readings.str() else pluralStringResource(Res.plurals.chart_last_readings, it, it) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(series: List<ChartSeries>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        series.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(line.color))
                Text(line.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private class ChartEntry(val color: Color, val label: String, val value: String)

@Composable
private fun ChartTooltip(header: String, entries: List<ChartEntry>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 200.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(header, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entries.forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(entry.color))
                    if (entry.label.isNotEmpty()) {
                        Text(entry.label, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(entry.value, style = MaterialTheme.typography.labelLarge, fontFamily = monoFamily())
                }
            }
        }
    }
}

private data class PlotMetrics(
    val left: Float,
    val width: Float,
    val start: Int,
    val visible: Int,
    val total: Int,
    val bars: Boolean,
) {
    val slot: Float get() = width / visible.coerceAtLeast(1)

    fun xAt(index: Int): Float = when {
        bars -> left + (index - start + 0.5f) * slot
        visible <= 1 -> left + width / 2f
        else -> left + (index - start).toFloat() / (visible - 1) * width
    }

    fun indexAt(x: Float): Int {
        val local = (x - left).coerceIn(0f, width)
        val offset = if (bars) (local / slot).toInt() else (local / width * (visible - 1)).roundToInt()
        return (start + offset).coerceIn(start, start + visible - 1)
    }
}

private class ChartRange(val axisMin: Float, val axisMax: Float, val low: Float, val high: Float, val average: Float)

private class SeriesShape(val stroke: Path?, val fills: List<Pair<Color, Path>>)

private fun chartRange(series: List<ChartSeries>, start: Int, end: Int, symmetric: Boolean, bars: Boolean): ChartRange {
    var low = Float.MAX_VALUE
    var high = -Float.MAX_VALUE
    var sum = 0.0
    var n = 0
    series.forEach { line ->
        for (i in start until minOf(end, line.points.size)) {
            val value = line.points[i]
            if (value < low) low = value
            if (value > high) high = value
            sum += value
            n++
        }
    }
    if (n == 0) return ChartRange(0f, 1f, 0f, 0f, 0f)
    var axisMin = low
    var axisMax = high
    if (symmetric) {
        axisMax = maxOf(abs(low), abs(high)).coerceAtLeast(0.001f)
        axisMin = -axisMax
    } else if (bars) {
        axisMin = minOf(low, 0f)
        axisMax = maxOf(high, 0f)
    }
    if (axisMax - axisMin < 1e-6f) axisMax = axisMin + 1f
    return ChartRange(axisMin, axisMax, low, high, (sum / n).toFloat())
}

private fun buildShape(
    series: ChartSeries,
    metrics: PlotMetrics,
    range: ChartRange,
    top: Float,
    height: Float,
    kind: ChartKind,
): SeriesShape {
    val from = metrics.start.coerceAtMost(series.points.size)
    val to = (metrics.start + metrics.visible).coerceAtMost(series.points.size)
    if (to <= from) return SeriesShape(null, emptyList())
    val span = range.axisMax - range.axisMin
    val y: (Float) -> Float = { top + height - (it - range.axisMin) / span * height }

    if (kind == ChartKind.BAR) {
        val width = (metrics.slot * 0.72f).coerceIn(1f, 28f)
        val base = y(0f.coerceIn(range.axisMin, range.axisMax))
        val groups = LinkedHashMap<Color, Path>()
        for (i in from until to) {
            val value = y(series.points[i])
            val x = metrics.xAt(i)
            val upper = minOf(value, base)
            groups.getOrPut(series.colorAt?.invoke(i) ?: series.color) { Path() }
                .addRect(Rect(x - width / 2, upper, x + width / 2, maxOf(value, base, upper + 1f)))
        }
        return SeriesShape(null, groups.map { (color, path) -> color to path })
    }

    val points = samplePoints(series.points, from, to, metrics, y)
    val stroke = Path()
    points.forEachIndexed { index, point ->
        if (index == 0) stroke.moveTo(point.x, point.y) else stroke.lineTo(point.x, point.y)
    }
    if (kind != ChartKind.AREA || points.size < 2) return SeriesShape(stroke, emptyList())
    val area = Path()
    area.moveTo(points.first().x, top + height)
    points.forEach { area.lineTo(it.x, it.y) }
    area.lineTo(points.last().x, top + height)
    area.close()
    return SeriesShape(stroke, listOf(series.color.copy(alpha = 0.16f) to area))
}

private fun samplePoints(values: List<Float>, from: Int, to: Int, metrics: PlotMetrics, y: (Float) -> Float): List<Offset> {
    val step = ((to - from) / (metrics.width * 2f)).toInt().coerceAtLeast(1)
    val result = ArrayList<Offset>((to - from) / step + 2)
    var i = from
    while (i < to) {
        val chunkEnd = minOf(i + step, to)
        if (step == 1) {
            result.add(Offset(metrics.xAt(i), y(values[i])))
        } else {
            var lowest = i
            var highest = i
            for (j in i until chunkEnd) {
                if (values[j] < values[lowest]) lowest = j
                if (values[j] > values[highest]) highest = j
            }
            val first = minOf(lowest, highest)
            val second = maxOf(lowest, highest)
            result.add(Offset(metrics.xAt(first), y(values[first])))
            if (second != first) result.add(Offset(metrics.xAt(second), y(values[second])))
        }
        i = chunkEnd
    }
    return result
}
