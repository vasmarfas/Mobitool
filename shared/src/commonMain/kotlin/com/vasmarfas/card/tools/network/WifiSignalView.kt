package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.WifiAccessPoint
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.wifiDetails
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.developer.localDateTime
import com.vasmarfas.card.tools.developer.pad2
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ChartSeries
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.InteractiveChart
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

private class SignalSample(val time: Long, val rssi: Int)

private const val SAMPLES = 600
private const val CHART_LINES = 6

internal fun clock(time: Long): String = localDateTime(time).let { "${pad2(it.hour)}:${pad2(it.minute)}:${pad2(it.second)}" }

@Composable
fun WifiSignal(scanner: WifiScanner, tracked: String?, onTrack: (String) -> Unit) {
    val points = scanner.points
    val target = points.firstOrNull { it.bssid == tracked } ?: points.firstOrNull { it.connected } ?: points.firstOrNull() ?: return
    val bssid = target.bssid
    var samples by remember(bssid) { mutableStateOf(emptyList<SignalSample>()) }
    var link by remember { mutableStateOf(emptyMap<String, String>()) }

    LaunchedEffect(bssid) {
        while (true) {
            val current = scanner.points.firstOrNull { it.bssid == bssid }
            val rssi = if (current?.connected == true) {
                link = wifiDetails()
                link["RSSI"]?.toIntOrNull()?.takeIf { link["BSSID"].equals(bssid, ignoreCase = true) } ?: current.rssi
            } else {
                current?.rssi
            }
            if (rssi != null) samples = (samples + SignalSample(currentEpochMillis(), rssi)).takeLast(SAMPLES)
            delay(1_000.milliseconds)
        }
    }

    val ordered = points.sortedWith(compareByDescending<WifiAccessPoint> { it.connected }.thenByDescending { it.rssi })
    DropdownChoice(ordered, target, { onTrack(it.bssid) }, Res.string.wifi_target.str(), { "${it.title()} · ${it.bssid}" })

    val rssi = samples.lastOrNull()?.rssi ?: target.rssi
    val color = signalColor(rssi)
    AnswerCard(
        "$rssi ${Res.string.unit_dbm.str()}",
        "${signalQuality(rssi).str()} · ${stringResource(Res.string.percent_value, signalPercent(rssi).toString())}",
    )
    LinearProgressIndicator(
        progress = { signalPercent(rssi) / 100f },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )

    var sound by remember { mutableStateOf(false) }
    SwitchRow(Res.string.wifi_sound.str(), sound, { sound = it }, description = Res.string.wifi_sound_hint.str())
    SignalBeep(sound, (rssi + 95) / 65f)

    ResultCard(Res.string.wifi_signal_history.str()) {
        InteractiveChart(
            series = listOf(ChartSeries(target.title(), color, samples.map { it.rssi.toFloat() })),
            xLabel = { samples.getOrNull(it)?.let { s -> clock(s.time) }.orEmpty() },
        )
    }

    if (target.connected) {
        ResultCard(Res.string.connection.str()) {
            link["Link speed"]?.let { KeyValueRow(Res.string.wifi_link_speed.str(), "$it ${Res.string.unit_mbit_s.str()}", mono = false, copyable = false) }
            KeyValueRow(Res.string.frequency.str(), "${target.frequency} ${Res.string.unit_mhz.str()} · ${stringResource(Res.string.wifi_channel_short, target.channel.toString())}", mono = false, copyable = false)
            link["Wi-Fi standard"]?.takeIf { it.isNotEmpty() }?.let { KeyValueRow(Res.string.wifi_standard.str(), it, mono = false, copyable = false) }
            KeyValueRow("BSSID", target.bssid)
        }
    }

    val history = scanner.history
    if (history.size > 1) {
        val lines = points.take(CHART_LINES)
        ResultCard(Res.string.wifi_all_history.str()) {
            InteractiveChart(
                series = lines.map { point ->
                    ChartSeries(point.title(), wifiColor(point.ssid.ifEmpty { point.bssid }), history.map { (it.signals[point.bssid] ?: -100).toFloat() })
                },
                xLabel = { history.getOrNull(it)?.let { s -> clock(s.time) }.orEmpty() },
            )
        }
    }
}
