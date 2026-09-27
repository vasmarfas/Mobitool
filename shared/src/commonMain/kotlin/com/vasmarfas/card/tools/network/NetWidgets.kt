package com.vasmarfas.card.tools.network

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.data.LocalSettings
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.monoFamily

private const val WIDE_COLUMN_UNIT = 110

@Composable
fun TableRow(cells: List<String>, header: Boolean = false, weights: List<Float>? = null, mono: Boolean = true, highlight: Boolean = false) {
    val wide = LocalSettings.current.wideTables
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
fun TableBlock(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val wide = LocalSettings.current.wideTables
    val scroll = rememberScrollState()
    Column(
        modifier = if (wide) modifier.fillMaxWidth().horizontalScroll(scroll) else modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
fun SimpleTable(header: List<String>, rows: List<List<String>>, weights: List<Float>? = null, mono: Boolean = true, highlight: Int? = null) {
    val wide = LocalSettings.current.wideTables
    val headerStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    val cellStyle = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily()) else MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    BoxWithConstraints {
        val available = constraints.maxWidth - with(LocalDensity.current) { 8.dp.roundToPx() } * (header.size - 1)
        val longestWords = header.indices.map { column -> rows.maxOfOrNull { row -> row.getOrElse(column) { "" }.split(' ').maxOf { it.length } } ?: 0 }
        val fits = wide || remember(header, longestWords, weights, available) {
            val total = weights?.sum() ?: header.size.toFloat()
            header.indices.all { column ->
                val width = available * (weights?.getOrNull(column) ?: 1f) / total
                val longest = rows.flatMap { it.getOrElse(column) { "" }.split(' ') }.sortedByDescending { it.length }.take(3)
                header[column].split(' ').all { measurer.measure(it, headerStyle).size.width <= width } &&
                    longest.all { measurer.measure(it, cellStyle).size.width <= width }
            }
        }
        if (fits) {
            TableBlock {
                TableRow(header, header = true, weights = weights)
                HorizontalDivider()
                rows.forEachIndexed { index, row -> TableRow(row, weights = weights, mono = mono, highlight = index == highlight) }
            }
        } else {
            StackedRows(header, rows, cellStyle, highlight)
        }
    }
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
