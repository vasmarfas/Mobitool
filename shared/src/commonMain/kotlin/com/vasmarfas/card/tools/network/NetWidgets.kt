package com.vasmarfas.card.tools.network

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.monoFamily

private const val WIDE_COLUMN_UNIT = 110

private const val GRID_CELL_MAX = 260

private const val GRID_HEADER_WRAP = 72

@Composable
fun TableRow(
    cells: List<String>,
    header: Boolean = false,
    weights: List<Float>? = null,
    mono: Boolean = true,
    highlight: Boolean = false,
    wide: Boolean = LocalSettings.current.wideTables,
) {
    val style = when {
        header -> MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
        mono -> MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily())
        else -> MaterialTheme.typography.bodySmall
    }
    val color = when {
        highlight -> MaterialTheme.colorScheme.onPrimaryContainer
        header -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val tint = if (highlight) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)) else Modifier
    Row(
        modifier = (if (wide) Modifier else Modifier.fillMaxWidth()).then(tint).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cells.forEachIndexed { index, cell ->
            val weight = weights?.getOrNull(index) ?: 1f
            val cellModifier = if (wide) Modifier.width((WIDE_COLUMN_UNIT * weight).dp) else Modifier.weight(weight)
            SelectionContainer(cellModifier) {
                Text(cell, style = style, color = color, softWrap = !wide, maxLines = if (wide) 1 else Int.MAX_VALUE)
            }
        }
    }
}

@Composable
fun TableBlock(modifier: Modifier = Modifier, wide: Boolean = LocalSettings.current.wideTables, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Column(
        modifier = if (wide) modifier.fillMaxWidth().horizontalScroll(scroll) else modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
fun SimpleTable(
    header: List<String>,
    rows: List<List<String>>,
    weights: List<Float>? = null,
    mono: Boolean = true,
    highlight: Int? = null,
    wide: Boolean = LocalSettings.current.wideTables,
) {
    val headerStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    val cellStyle = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily()) else MaterialTheme.typography.bodySmall
    if (wide) {
        GridTable(header, rows, headerStyle, cellStyle, highlight)
        return
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val available = constraints.maxWidth - with(LocalDensity.current) { 8.dp.roundToPx() } * (header.size - 1)
        val longestWords = header.indices.map { column -> rows.maxOfOrNull { row -> row.getOrElse(column) { "" }.split(' ').maxOf { it.length } } ?: 0 }
        val fits = remember(header, longestWords, weights, available) {
            val total = weights?.sum() ?: header.size.toFloat()
            header.indices.all { column ->
                val width = available * (weights?.getOrNull(column) ?: 1f) / total
                val longest = rows.flatMap { it.getOrElse(column) { "" }.split(' ') }.sortedByDescending { it.length }.take(3)
                header[column].split(' ').all { measurer.measure(it, headerStyle).size.width <= width } &&
                    longest.all { measurer.measure(it, cellStyle).size.width <= width }
            }
        }
        if (fits) {
            TableBlock(wide = false) {
                TableRow(header, header = true, weights = weights, wide = false)
                HorizontalDivider()
                rows.forEachIndexed { index, row -> TableRow(row, weights = weights, mono = mono, highlight = index == highlight, wide = false) }
            }
        } else {
            StackedRows(header, rows, cellStyle, highlight)
        }
    }
}

@Composable
private fun GridTable(header: List<String>, rows: List<List<String>>, headerStyle: TextStyle, cellStyle: TextStyle, highlight: Int?) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Layout(
                content = {
                    (listOf(header) + rows).forEachIndexed { r, row ->
                        val marked = r - 1 == highlight
                        val fill = when {
                            r == 0 -> colors.surfaceContainerHigh
                            marked -> colors.primaryContainer
                            r % 2 == 0 -> colors.surfaceContainerLow
                            else -> colors.surface
                        }
                        header.indices.forEach { c ->
                            val edge = if (c == 0) {
                                Modifier.drawBehind {
                                    val x = size.width - 0.5.dp.toPx()
                                    drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                }
                            } else {
                                Modifier
                            }
                            Text(
                                row.getOrElse(c) { "" },
                                modifier = Modifier.background(fill).then(edge).padding(horizontal = 8.dp, vertical = 6.dp).wrapContentHeight(),
                                style = if (r == 0) headerStyle else cellStyle,
                                color = when {
                                    r == 0 -> colors.onSurfaceVariant
                                    marked -> colors.onPrimaryContainer
                                    else -> colors.onSurface
                                },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().border(1.dp, colors.outlineVariant, shape).clip(shape).horizontalScroll(scroll),
            ) { measurables, constraints ->
                val grid = measurables.chunked(header.size)
                val headerWrap = GRID_HEADER_WRAP.dp.roundToPx()
                fun width(c: Int, cap: Int): Int {
                    val head = grid[0][c]
                    val data = grid.drop(1).maxOfOrNull { it[c].maxIntrinsicWidth(Constraints.Infinity) } ?: 0
                    val natural = maxOf(data, minOf(head.maxIntrinsicWidth(Constraints.Infinity), headerWrap), head.minIntrinsicWidth(Constraints.Infinity))
                    return if (natural <= cap) natural else maxOf(cap, grid.maxOf { it[c].minIntrinsicWidth(Constraints.Infinity) })
                }
                val view = constraints.minWidth
                val cellMax = GRID_CELL_MAX.dp.roundToPx()
                val pinned = width(0, minOf(cellMax, view * 2 / 5))
                val widths = IntArray(header.size) { c -> if (c == 0) pinned else width(c, minOf(cellMax, view - pinned)) }
                val spare = view - widths.sum()
                if (spare > 0) widths.indices.forEach { widths[it] += spare / widths.size + if (it < spare % widths.size) 1 else 0 }
                val heights = grid.map { row -> row.indices.maxOf { c -> row[c].minIntrinsicHeight(widths[c]) } }
                val cells = grid.mapIndexed { r, row -> row.mapIndexed { c, cell -> cell.measure(Constraints.fixed(widths[c], heights[r])) } }
                val xs = widths.runningFold(0) { x, width -> x + width }
                layout(xs.last(), heights.sum()) {
                    var y = 0
                    cells.forEachIndexed { r, row ->
                        row.forEachIndexed { c, cell -> if (c > 0) cell.place(xs[c], y) }
                        row[0].place(scroll.value, y, zIndex = 1f)
                        y += heights[r]
                    }
                }
            }
        }
        if (scroll.maxValue > 0) GridScrollbar(scroll)
    }
}

@Composable
private fun GridScrollbar(scroll: ScrollState) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val thumb = MaterialTheme.colorScheme.outline
    Spacer(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .draggable(
                rememberDraggableState { scroll.dispatchRawDelta(it * (scroll.maxValue + scroll.viewportSize) / scroll.viewportSize) },
                Orientation.Horizontal,
            )
            .drawBehind {
                val bar = 4.dp.toPx()
                val top = (size.height - bar) / 2
                val length = size.width * scroll.viewportSize / (scroll.maxValue + scroll.viewportSize)
                val start = (size.width - length) * scroll.value / scroll.maxValue
                drawRoundRect(track, Offset(0f, top), Size(size.width, bar), CornerRadius(bar / 2))
                drawRoundRect(thumb, Offset(start, top), Size(length, bar), CornerRadius(bar / 2))
            },
    )
}

@Composable
private fun StackedRows(header: List<String>, rows: List<List<String>>, style: TextStyle, highlight: Int?) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider()
            val tint = if (index == highlight) Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp)).padding(4.dp) else Modifier
            SelectionContainer(tint) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.first(), style = style.copy(fontWeight = FontWeight.Bold))
                    row.drop(1).forEachIndexed { i, cell ->
                        if (cell.isNotBlank()) {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = labelColor)) { append("${header[i + 1]}: ") }
                                    append(cell)
                                },
                                style = style,
                            )
                        }
                    }
                }
            }
        }
    }
}

fun looksLikeIp(text: String): Boolean = Ipv4.parse(text) != null || Ipv6Address.parse(text) != null

fun pluralQuantity(count: Long): Int = if (count < 100) count.toInt() else (count % 100 + 100).toInt()

fun hostFrom(input: String): String {
    var t = input.trim()
    if (t.contains("://")) t = t.substringAfter("://")
    t = t.substringBefore('/').substringBefore('?')
    if (t.startsWith("[")) return t.substringBefore(']').trimStart('[')
    if (t.count { it == ':' } == 1) t = t.substringBefore(':')
    return t
}
