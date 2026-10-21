package com.vasmarfas.card.tools.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import com.vasmarfas.card.core.Wifi
import com.vasmarfas.card.core.WifiAccessPoint
import com.vasmarfas.card.core.WifiBand
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import org.jetbrains.compose.resources.stringResource

private const val FLOOR_DBM = -100f
private const val CEILING_DBM = -25f

@Composable
fun WifiChannelsView(points: List<WifiAccessPoint>) {
    if (points.isEmpty()) return
    val bands = points.map { it.band }.distinct().sorted()
    var picked by rememberSaveable { mutableStateOf(WifiBand.GHZ_2_4) }
    val band = if (picked in bands) picked else bands.first()
    if (bands.size > 1) SegmentedChoice(bands, band, { picked = it }, { bandLabel(it) })
    ChannelGraph(points.filter { it.band == band }, band)

    val own = points.firstOrNull { it.connected }
    val choices = remember(points, band) { WifiChannels.choices(points, band, own) }
    val best = choices.firstOrNull() ?: return
    ResultCard(Res.string.wifi_best_channel.str()) {
        Text(stringResource(if (best.channels.size > 1) Res.string.wifi_recommend_block else Res.string.wifi_recommend, best.label), style = MaterialTheme.typography.bodyLarge)
        if (own != null && own.band == band) Text(stringResource(Res.string.wifi_own_channel, own.title(), own.channel.toString()), style = MaterialTheme.typography.bodyMedium)
        SimpleTable(
            header = listOf(Res.string.wifi_channel.str(), Res.string.wifi_networks_on_channel.str(), Res.string.wifi_interference.str()),
            rows = choices.map { choice ->
                val dbm = powerDbm(choice.load)
                listOf(
                    choice.label + if (choice.dfs) " · DFS" else "",
                    choice.networks.toString(),
                    dbm?.let { "$it ${Res.string.unit_dbm.str()}" } ?: Res.string.wifi_free.str(),
                )
            },
            weights = listOf(1.4f, 1f, 1.2f),
            mono = false,
        )
        Hint(Res.string.wifi_channel_hint.str())
        if (band == WifiBand.GHZ_5) Hint(Res.string.wifi_dfs_hint.str())
    }
}

@Composable
private fun ChannelGraph(points: List<WifiAccessPoint>, band: WifiBand) {
    val channels = WifiChannels.channels(band)
    val margin = if (band == WifiBand.GHZ_2_4) 15.0 else 20.0
    val low = Wifi.frequency(channels.first(), band) - margin
    val high = Wifi.frequency(channels.last(), band) + margin
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val step = if (band == WifiBand.GHZ_6) 4 else 1
    ResultCard {
        BoxWithConstraints {
            val perChannel = if (band == WifiBand.GHZ_2_4) 22 else 28
            val width = max(maxWidth, (channels.size / step * perChannel).dp)
            Canvas(Modifier.horizontalScroll(rememberScrollState()).width(width).height(260.dp)) {
                val axis = 28.dp.toPx()
                val bottom = size.height - 18.dp.toPx()
                val top = 14.dp.toPx()
                fun x(frequency: Double) = axis + ((frequency - low) / (high - low) * (size.width - axis)).toFloat()
                fun y(dbm: Float) = bottom - (dbm.coerceIn(FLOOR_DBM, CEILING_DBM) - FLOOR_DBM) / (CEILING_DBM - FLOOR_DBM) * (bottom - top)
                for (level in -90..-30 step 10) {
                    val ly = y(level.toFloat())
                    drawLine(grid, Offset(axis, ly), Offset(size.width, ly), 1f)
                    val text = measurer.measure(level.toString(), labelStyle)
                    drawText(text, topLeft = Offset(0f, ly - text.size.height / 2f))
                }
                channels.filterIndexed { index, _ -> index % step == 0 }.forEach { channel ->
                    val cx = x(Wifi.frequency(channel, band).toDouble())
                    val text = measurer.measure(channel.toString(), labelStyle)
                    drawText(text, topLeft = Offset(cx - text.size.width / 2f, bottom + 2.dp.toPx()))
                }
                // a cubic whose control points sit a third above the peak passes exactly through it at the middle
                val peaks = points.sortedBy { it.rssi }.map { point ->
                    val span = WifiChannels.span(point)
                    val left = x(span.start)
                    val right = x(span.endInclusive)
                    val peak = y(point.rssi.toFloat())
                    val control = (4 * peak - bottom) / 3
                    val path = Path().apply {
                        moveTo(left, bottom)
                        cubicTo(left + (right - left) * 0.15f, control, right - (right - left) * 0.15f, control, right, bottom)
                    }
                    val color = wifiColor(point.ssid.ifEmpty { point.bssid })
                    drawPath(path, color.copy(alpha = 0.16f))
                    drawPath(path, color, style = Stroke(if (point.connected) 3.dp.toPx() else 1.5.dp.toPx()))
                    Triple(point, Offset((left + right) / 2, peak), color)
                }
                val placed = mutableListOf<Rect>()
                peaks.reversed().forEach { (point, peak, color) ->
                    val text = measurer.measure(point.ssid.ifEmpty { point.bssid }, labelStyle.copy(color = color), maxLines = 1)
                    val left = (peak.x - text.size.width / 2f).coerceIn(axis, size.width - text.size.width)
                    val box = Rect(Offset(left, (peak.y - text.size.height - 2.dp.toPx()).coerceAtLeast(0f)), Size(text.size.width.toFloat(), text.size.height.toFloat()))
                    if (placed.none { it.overlaps(box) }) {
                        placed += box
                        drawText(text, topLeft = box.topLeft)
                    }
                }
            }
        }
    }
}
