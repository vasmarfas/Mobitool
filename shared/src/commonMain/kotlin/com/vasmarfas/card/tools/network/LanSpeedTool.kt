package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.NetException
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.connectTcp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.formatBytes
import com.vasmarfas.card.core.listenTcp
import com.vasmarfas.card.core.networkInterfaces
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ChartSeries
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.InteractiveChart
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

val lanSpeedTool = Tool(
    id = "lan-speed",
    category = ToolCategory.NETWORK,
    title = Res.string.lan_speed,
    description = Res.string.lan_speed_description,
    icon = Icons.Filled.SwapVert,
    keywords = listOf("iperf", "iperf3", "throughput", "bandwidth", "lan", "wifi", "ethernet", "mbps", "скорость", "локальная сеть", "пропускная способность", "роутер"),
    platforms = PlatformKind.native,
) { LanSpeedScreen() }

private enum class LanMode { IPERF, PEER }

private enum class PeerRole { CONNECT, WAIT }

private data class LanSpeedState(
    val running: Boolean = false,
    val upload: List<Float> = emptyList(),
    val download: List<Float> = emptyList(),
    val results: List<LanSpeedResult> = emptyList(),
    val error: String? = null,
)

private suspend fun lanSpeedError(e: Throwable): String {
    val text = networkErrorText(e)
    return if (text == e.message) getString(Res.string.lan_speed_lost) else text
}

@Composable
private fun LanSpeedScreen() {
    var mode by rememberSaveable { mutableStateOf(LanMode.IPERF) }
    var role by rememberSaveable { mutableStateOf(PeerRole.CONNECT) }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf(Iperf3.PORT.toString()) }
    var duration by rememberSaveable { mutableStateOf("10") }
    var streams by rememberSaveable { mutableStateOf("1") }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var state by remember { mutableStateOf(LanSpeedState()) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val waiting = mode == LanMode.PEER && role == PeerRole.WAIT
    val number = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val addresses by produceState(emptyList<String>()) {
        value = runCatching { networkInterfaces() }.getOrDefault(emptyList()).filter { it.isUp && !it.isLoopback }
            .flatMap { it.addresses }.map { it.substringBefore('/') }.filter { Ipv4.parse(it) != null }.distinct()
    }

    fun start(target: Int) {
        val seconds = duration.toIntOrNull()?.coerceIn(1, 3600) ?: 10
        val parallel = streams.toIntOrNull()?.coerceIn(1, Iperf3.MAX_STREAMS) ?: 1
        val server = hostFrom(host)
        val onSecond: OnSecond = { download, second, mbps ->
            state = if (download) state.copy(download = state.download.take(second) + mbps.toFloat()) else state.copy(upload = state.upload.take(second) + mbps.toFloat())
        }
        val onResult: (LanSpeedResult) -> Unit = { result -> state = state.copy(results = state.results.filter { it.download != result.download } + result) }
        suspend fun measure(back: Boolean) = onResult(iperfClient(seconds, parallel, back, onSecond) { connectTcp(server, target, 5_000) })
        state = LanSpeedState(running = true)
        job = scope.launch(Dispatchers.Default) {
            runCatching {
                when {
                    mode == LanMode.IPERF -> measure(reverse)
                    role == PeerRole.WAIT -> iperfServer(runCatching { listenTcp(target) }.getOrElse { throw NetException(Res.string.lan_speed_port_busy, target.toString()) }, onSecond, onResult)
                    else -> {
                        measure(false)
                        measure(true)
                    }
                }
            }.onFailure { failure -> if (isActive) state = state.copy(error = lanSpeedError(failure)) }
            state = state.copy(running = false)
        }
    }

    SegmentedChoice(LanMode.entries, mode, { mode = it }, { if (it == LanMode.IPERF) Res.string.lan_speed_mode_iperf.str() else Res.string.lan_speed_mode_peer.str() })
    if (mode == LanMode.PEER) {
        SegmentedChoice(PeerRole.entries, role, { role = it }, { if (it == PeerRole.CONNECT) Res.string.lan_speed_connect.str() else Res.string.lan_speed_wait.str() })
    }
    if (!waiting) ToolInputField(host, { host = it }, Res.string.host_or_ip_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
    NumberField(port, { port = it }, Res.string.port.str(), isError = number == null)
    if (!waiting) {
        NumberField(duration, { duration = it }, Res.string.duration.str(), suffix = Res.string.unit_s.str())
        NumberField(streams, { streams = it }, Res.string.lan_speed_streams.str())
    }
    if (mode == LanMode.IPERF) {
        SegmentedChoice(listOf(false, true), reverse, { reverse = it }, { if (it) Res.string.download.str() else Res.string.upload.str() })
    }
    Hint(
        when {
            mode == LanMode.IPERF -> Res.string.lan_speed_iperf_hint.str()
            waiting -> Res.string.lan_speed_wait_hint.str()
            else -> Res.string.lan_speed_connect_hint.str()
        },
    )
    if (waiting && addresses.isNotEmpty()) {
        ResultCard {
            KeyValueRow(Res.string.lan_speed_addresses.str(), addresses.joinToString("\n"))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(
            text = if (state.running) Res.string.net_running.str() else Res.string.start.str(),
            onClick = { number?.let(::start) },
            enabled = !state.running && number != null && (waiting || host.isNotBlank()),
        )
        if (state.running) {
            TextButton(onClick = { job?.cancel(); state = state.copy(running = false) }) { Text(Res.string.stop_short.str()) }
        }
    }
    if (state.running && waiting) LoadingRow(stringResource(Res.string.lan_speed_waiting, number ?: Iperf3.PORT))
    state.error?.let { ErrorText(it) }
    val mbit = Res.string.unit_mbit_s.str()
    val second = Res.string.unit_s.str()
    val uploadText = Res.string.upload.str()
    val downloadText = Res.string.download.str()
    if (state.upload.isNotEmpty() || state.download.isNotEmpty()) {
        InteractiveChart(
            series = listOfNotNull(
                ChartSeries(uploadText, MaterialTheme.colorScheme.primary, state.upload).takeIf { state.upload.isNotEmpty() },
                ChartSeries(downloadText, MaterialTheme.colorScheme.tertiary, state.download).takeIf { state.download.isNotEmpty() },
            ),
            xLabel = { "${it + 1} $second" },
            yFormat = { "${it.toDouble().fmt(1)} $mbit" },
            windows = false,
        )
    }
    state.results.forEach { result ->
        AnswerCard("${result.mbps.fmt(1)} $mbit", if (result.download) downloadText else uploadText, copyValue = result.mbps.fmt(1))
        ResultCard {
            KeyValueRow(Res.string.sent.str(), formatBytes(result.sent, binary = false), copyable = false)
            KeyValueRow(Res.string.received.str(), formatBytes(result.received, binary = false), copyable = false)
            KeyValueRow(Res.string.duration.str(), "${result.seconds.fmt(2)} $second", copyable = false)
            result.retransmits?.let { KeyValueRow(Res.string.lan_speed_retransmits.str(), it.toString(), copyable = false) }
        }
    }
}
