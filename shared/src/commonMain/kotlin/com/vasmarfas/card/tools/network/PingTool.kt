package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.PingReply
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.httpPing
import com.vasmarfas.card.core.icmpPing
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpConnect
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ChartKind
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
import kotlin.math.sqrt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import org.jetbrains.compose.resources.StringResource

val pingTool = Tool(
    id = "ping",
    category = ToolCategory.NETWORK,
    title = Res.string.ping,
    description = Res.string.ping_description,
    icon = Icons.Filled.NetworkPing,
    keywords = listOf("icmp", "latency", "rtt", "packet loss", "reachability", "доступность", "задержка", "потери"),
    expandable = true,
) { PingScreen() }

private enum class PingMode(val title: String) { ICMP("ICMP"), TCP("TCP"), HTTP("HTTP") }

private val timedOut = Res.string.ping_timeout

private suspend fun pathMtu(host: String): Int? {
    suspend fun passes(payload: Int) = (1..2).any { icmpPing(host, it, 2000, size = payload).timeMs != null }
    var low = 548
    var high = 1472
    if (!passes(low)) return null
    if (passes(high)) return high + 28
    while (high - low > 1) {
        val middle = (low + high) / 2
        if (passes(middle)) low = middle else high = middle
    }
    return low + 28
}

fun mtuNote(mtu: Int): StringResource = when {
    mtu >= 1500 -> Res.string.ping_mtu_ethernet
    mtu >= 1492 -> Res.string.ping_mtu_pppoe
    mtu >= 1400 -> Res.string.ping_mtu_tunnel
    else -> Res.string.ping_mtu_low
}

@Composable
private fun PingScreen() {
    val modes = PingMode.entries.filter {
        when (it) {
            PingMode.ICMP -> NetCapabilities.icmpPing
            PingMode.TCP -> NetCapabilities.tcp
            PingMode.HTTP -> true
        }
    }
    var host by rememberSaveable { mutableStateOf("1.1.1.1") }
    var mode by rememberSaveable { mutableStateOf(modes.first()) }
    var port by rememberSaveable { mutableStateOf("443") }
    var count by rememberSaveable { mutableStateOf("10") }
    var interval by rememberSaveable { mutableStateOf("1000") }
    val replies = remember { mutableStateListOf<PingReply>() }
    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var mtu by remember { mutableStateOf<Result<Int?>?>(null) }
    var findingMtu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun start() {
        val target = hostFrom(host)
        if (target.isEmpty()) return
        replies.clear()
        running = true
        job = scope.launch {
            val total = count.toIntOrNull()?.coerceIn(1, 1000) ?: 10
            val gap = interval.toLongOrNull()?.coerceIn(100, 10_000) ?: 1000
            val p = port.toIntOrNull()?.coerceIn(1, 65535) ?: 443
            for (seq in 1..total) {
                val started = currentEpochMillis()
                val reply = when (mode) {
                    PingMode.ICMP -> icmpPing(target, seq, 3000)
                    PingMode.TCP -> tcpConnect(target, p, 3000).let { ms -> if (ms == null) PingReply(seq, null, null, null, null) else PingReply(seq, ms.toDouble(), null, "$target:$p") }
                    PingMode.HTTP -> runCatching {
                        val url = if (host.contains("://")) host.trim() else "https://$target"
                        PingReply(seq, httpPing(url, 5000).toDouble(), null, url)
                    }.getOrElse { PingReply(seq, null, null, null, it.message ?: "error") }
                }
                replies += reply
                val spent = currentEpochMillis() - started
                if (seq < total) delay((gap - spent).coerceAtLeast(0).milliseconds)
            }
            running = false
        }
    }

    ToolInputField(value = host, onValueChange = { host = it }, label = Res.string.host_or_ip_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
    if (modes.size > 1) SegmentedChoice(options = modes, selected = mode, onSelect = { mode = it }, label = { it.title })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (mode == PingMode.TCP) NumberField(value = port, onValueChange = { port = it }, label = Res.string.port.str(), modifier = Modifier.weight(1f))
        NumberField(value = count, onValueChange = { count = it }, label = Res.string.count.str(), modifier = Modifier.weight(1f))
        NumberField(value = interval, onValueChange = { interval = it }, label = Res.string.interval_ms.str(), modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = Res.string.start.str(), onClick = ::start, enabled = !running)
        if (running) TextButton(onClick = { job?.cancel(); running = false }) { Text(Res.string.stop_short.str()) }
        if (mode == PingMode.ICMP && currentPlatform in PlatformKind.jvm && ':' !in hostFrom(host)) {
            TextButton(
                onClick = {
                    findingMtu = true
                    scope.launch {
                        mtu = runCatching { pathMtu(hostFrom(host)) }
                        findingMtu = false
                    }
                },
                enabled = !running && !findingMtu,
            ) { Text(Res.string.ping_find_mtu.str()) }
        }
    }
    if (findingMtu) LoadingRow(Res.string.ping_mtu_running.str())
    mtu?.let { result ->
        val value = result.getOrNull()
        if (value == null) {
            ErrorText(Res.string.ping_mtu_failed.str())
        } else {
            ResultCard {
                AnswerCard("$value ${Res.string.unit_byte.str()}", Res.string.ping_mtu.str())
                Hint(mtuNote(value).str())
            }
        }
    }
    if (!NetCapabilities.icmpPing) {
        val note = if (NetCapabilities.tcp) {
            Res.string.ping_icmp_is_not_available.str()
        } else {
            Res.string.net_browser_has_no_sockets.str() + " " + Res.string.ping_http_ping_measures.str()
        }
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (replies.isNotEmpty()) {
        val times = replies.mapNotNull { it.timeMs }
        val lost = replies.count { it.timeMs == null }
        ResultCard(title = Res.string.statistics.str()) {
            PingChart(replies)
            KeyValueRow("${Res.string.sent.str()} / ${Res.string.received.str()} / ${Res.string.lost.str()}", "${replies.size} / ${replies.size - lost} / $lost (${(lost * 100.0 / replies.size).fmt(0)}%)", copyable = false)
            if (times.isNotEmpty()) {
                val avg = times.average()
                val jitter = if (times.size > 1) sqrt(times.sumOf { (it - avg) * (it - avg) } / times.size) else 0.0
                KeyValueRow("${Res.string.min_2.str()} / ${Res.string.avg.str()} / ${Res.string.max.str()}", "${times.min().fmt(1)} / ${avg.fmt(1)} / ${times.max().fmt(1)} ${Res.string.unit_ms.str()}", copyable = false)
                KeyValueRow("${Res.string.jitter.str()} (σ)", "${jitter.fmt(1)} ${Res.string.unit_ms.str()}", copyable = false)
            }
        }
        ResultCard(title = Res.string.replies.str()) {
            TableBlock {
                replies.asReversed().forEach { r ->
                    val text = if (r.timeMs != null) "${r.timeMs.fmt(1)} ${Res.string.unit_ms.str()}" + (r.ttl?.let { " · TTL $it" } ?: "") + (r.from?.let { " · $it" } ?: "")
                    else (r.error ?: timedOut.str())
                    TableRow(listOf("#${r.sequence}", text), weights = listOf(0.4f, 4f))
                }
            }
        }
    }
}

@Composable
fun PingChart(replies: List<PingReply>) {
    val timedOutText = timedOut.str()
    val bar = MaterialTheme.colorScheme.primary
    val lost = MaterialTheme.colorScheme.error
    val shown = replies.toList()
    val max = shown.mapNotNull { it.timeMs }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val ms = Res.string.unit_ms.str()
    InteractiveChart(
        series = listOf(
            ChartSeries(
                label = "",
                color = bar,
                points = shown.map { (it.timeMs ?: max).toFloat() },
                colorAt = { if (shown[it].timeMs == null) lost else bar },
                tooltip = { index -> shown[index].timeMs?.let { "${it.fmt(1)} $ms" } ?: timedOutText },
            ),
        ),
        modifier = Modifier.height(140.dp),
        xLabel = { "#${shown[it].sequence}" },
        yFormat = { "${it.toDouble().fmt(1)} $ms" },
        kind = ChartKind.BAR,
    )
}
