package com.vasmarfas.card.tools.time

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.resolveHost
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.udpQuery
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.localDateTime
import com.vasmarfas.card.tools.developer.pad2
import com.vasmarfas.card.tools.network.ConnectionProbe
import com.vasmarfas.card.tools.network.SimpleTable
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.math.abs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val exactTimeTool = Tool(
    id = "exact-time",
    category = ToolCategory.TIME,
    title = Res.string.exact_time,
    description = Res.string.exact_time_description,
    icon = Icons.Filled.AccessTime,
    keywords = listOf("ntp", "atomic clock", "exact time", "time sync", "clock offset", "точное время", "сверить часы", "атомные часы", "синхронизация"),
) { ExactTimeScreen() }

private val servers = listOf("ntp1.vniiftri.ru", "time.google.com", "time.cloudflare.com", "time.apple.com", "pool.ntp.org", "time.windows.com")

// the address is resolved first, so the lookup does not land between the two timestamps of the exchange
private suspend fun query(server: String): NtpReply? {
    val address = resolveHost(server).firstOrNull { '.' in it } ?: return null
    val sent = currentEpochMillis()
    val response = udpQuery(address, 123, Ntp.request(sent), 2_000)
    return Ntp.parse(server, response, sent, currentEpochMillis())
}

// a browser cannot send UDP; the Cloudflare trace gives whole seconds, half a second later on average
private suspend fun browserReply(): NtpReply? = runCatching {
    val sent = currentEpochMillis()
    val seconds = ConnectionProbe.trace(Net.client.get("https://www.cloudflare.com/cdn-cgi/trace").bodyAsText()).getValue("ts").toDouble()
    val received = currentEpochMillis()
    NtpReply("www.cloudflare.com", seconds * 1000 + 500 - (sent + received) / 2.0, (received - sent).toDouble(), 0, "HTTPS")
}.getOrNull()

fun bestOffset(replies: List<NtpReply>): Double? =
    replies.sortedBy { it.delayMs }.take(3).map { it.offsetMs }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

@Composable
private fun offsetText(offset: Double, coarse: Boolean): String {
    val size = abs(offset)
    val amount = when {
        coarse -> "≈ ${(size / 1000).fmt(1)} ${Res.string.unit_s.str()}"
        size < 1000 -> "${size.fmt(0)} ${Res.string.unit_ms.str()}"
        else -> "${(size / 1000).fmt(3)} ${Res.string.unit_s.str()}"
    }
    return when {
        size < (if (coarse) 1000 else 50) -> stringResource(Res.string.exact_time_accurate, amount)
        offset < 0 -> stringResource(Res.string.exact_time_ahead, amount)
        else -> stringResource(Res.string.exact_time_behind, amount)
    }
}

@Composable
private fun ExactTimeScreen() {
    val udp = NetCapabilities.udp
    var run by remember { mutableStateOf(0) }
    var replies by remember { mutableStateOf<List<NtpReply>?>(null) }
    var now by remember { mutableStateOf(currentEpochMillis()) }

    LaunchedEffect(run) {
        replies = null
        replies = if (udp) coroutineScope { servers.map { async { runCatching { query(it) }.getOrNull() } }.awaitAll().filterNotNull() } else listOfNotNull(browserReply())
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = currentEpochMillis()
            delay(47.milliseconds)
        }
    }

    val found = replies
    val offset = found?.let(::bestOffset)
    if (offset != null) {
        val exact = localDateTime(now + offset.toLong())
        Text(
            "${pad2(exact.hour)}:${pad2(exact.minute)}:${pad2(exact.second)}.${(exact.nanosecond / 100_000_000)}",
            style = MaterialTheme.typography.displayMedium,
        )
        Text("${exact.year}-${pad2(exact.month.ordinal + 1)}-${pad2(exact.day)}", style = MaterialTheme.typography.bodyLarge)
        AnswerCard(offsetText(offset, coarse = !udp), Res.string.exact_time_device.str(), copyValue = offset.fmt(0))
    }
    when {
        found == null -> LoadingRow(Res.string.exact_time_asking.str())
        found.isEmpty() -> ErrorText(Res.string.exact_time_none.str())
        else -> ResultCard {
            SimpleTable(
                header = listOf(Res.string.exact_time_server.str(), Res.string.exact_time_offset.str(), Res.string.exact_time_rtt.str(), Res.string.exact_time_stratum.str()),
                rows = found.sortedBy { it.delayMs }.map {
                    listOf(it.server, "${if (it.offsetMs > 0) "+" else ""}${it.offsetMs.fmt(0)}", it.delayMs.fmt(0), if (it.stratum == 0) it.reference else "${it.stratum} · ${it.reference}")
                },
                weights = listOf(1.8f, 1f, 0.9f, 1.3f),
                mono = false,
            )
            Hint((if (udp) Res.string.exact_time_hint else Res.string.exact_time_browser_hint).str())
        }
    }
    ActionButton(Res.string.refresh.str(), onClick = { run++ }, enabled = found != null)
}
