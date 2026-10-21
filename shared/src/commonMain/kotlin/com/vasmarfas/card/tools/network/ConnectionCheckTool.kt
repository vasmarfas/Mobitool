package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.appLang
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.httpPing
import com.vasmarfas.card.core.icmpPing
import com.vasmarfas.card.core.networkInterfaces
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.parseXml
import com.vasmarfas.card.core.regionName
import com.vasmarfas.card.core.resolveHost
import com.vasmarfas.card.core.ssdpDiscover
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpConnect
import com.vasmarfas.card.core.tcpExchange
import com.vasmarfas.card.core.wifiDetails
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SoftDivider
import com.vasmarfas.card.ui.theme.LocalStatusColors
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val connectionCheckTool = Tool(
    id = "connection-check",
    category = ToolCategory.NETWORK,
    title = Res.string.connection_check,
    description = Res.string.connection_check_description,
    icon = Icons.Filled.NetworkCheck,
    keywords = listOf(
        "internet", "no internet", "diagnostics", "troubleshoot", "router", "gateway", "dns", "dhcp", "captive portal", "clock",
        "нет интернета", "не работает интернет", "диагностика", "роутер", "шлюз", "страница входа", "часы",
    ),
) { ConnectionCheckScreen() }

private const val PINGS = 10
private const val PING_GAP_MS = 150L

private suspend fun series(target: String, probe: suspend (Int) -> Double?): PingStats {
    val times = mutableListOf<Double>()
    var sent = 0
    while (sent < PINGS && (sent < 3 || times.isNotEmpty())) {
        probe(sent)?.let { times += it }
        sent++
        delay(PING_GAP_MS.milliseconds)
    }
    return PingStats(target, sent, times)
}

private suspend fun reach(host: String): PingStats {
    val icmp = series(host) { icmpPing(host, it, 1000).timeMs }
    return if (icmp.received > 0) icmp else series(host) { tcpConnect(host, 443, 1500)?.toDouble() }
}

private suspend fun internetStats(local: Boolean): PingStats = coroutineScope {
    if (!local) {
        val url = "https://1.1.1.1/cdn-cgi/trace"
        runCatching { httpPing(url, 5000) }
        return@coroutineScope series("1.1.1.1") { runCatching { httpPing(url, 3000).toDouble() }.getOrNull() }
    }
    listOf(async { reach("1.1.1.1") }, async { reach("8.8.8.8") }).awaitAll()
        .maxWith(compareBy<PingStats> { it.received }.thenByDescending { it.average })
}

internal suspend fun plainGet(url: String, timeoutMs: Int, headers: List<String> = emptyList()): String {
    val (host, port, request) = ConnectionProbe.plainRequest(url, headers)
    return tcpExchange(host, port, request, timeoutMs).decodeToString()
}

private suspend fun routerName(gateway: String): String? {
    val location = ssdpDiscover(2500).firstNotNullOfOrNull { device -> device.headers["LOCATION"]?.takeIf { device.address == gateway } } ?: return null
    val device = parseXml(ConnectionProbe.body(plainGet(location, 3000))).child("device") ?: return null
    fun field(name: String) = device.child(name)?.text()?.trim().orEmpty()
    val maker = field("manufacturer")
    val model = listOf(field("modelName"), field("modelNumber")).filter { it.isNotEmpty() }.distinct().joinToString(" ")
    val name = if (maker.isEmpty() || model.contains(maker.substringBefore(' '), ignoreCase = true)) model else "$maker $model"
    return name.trim().ifEmpty { field("friendlyName") }.ifEmpty { null }
}

// the Date header counts only from Google's own 204: a portal answers with its own clock
private suspend fun portalProbe(): Pair<Portal, Long?> = runCatching {
    val started = currentEpochMillis()
    val response = plainGet(ConnectionProbe.PORTAL_URL, 5000)
    val middle = (started + currentEpochMillis()) / 2
    when (ConnectionProbe.status(response)) {
        204 -> Portal.NONE to ConnectionProbe.header(response, "Date")?.let(EmailHeaders::parseDate)?.let { (middle - it * 1000 - 500) / 1000 }
        null -> Portal.UNKNOWN to null
        else -> Portal.FOUND to null
    }
}.getOrDefault(Portal.UNKNOWN to null)

// the trace timestamp is cut to whole seconds, so the true server time is half a second later on average
private suspend fun httpsTrace(): Pair<Trace, Long?> = runCatching {
    val started = currentEpochMillis()
    val fields = ConnectionProbe.trace(Net.client.get("https://www.cloudflare.com/cdn-cgi/trace").bodyAsText())
    val middle = (started + currentEpochMillis()) / 2
    val country = fields["loc"]?.let { regionName(it, appLang) ?: it }.orEmpty()
    Trace(fields.getValue("ip"), country, fields["warp"] == "on") to fields["ts"]?.toDoubleOrNull()?.let { (middle - (it * 1000).toLong() - 500) / 1000 }
}.getOrElse { Trace(error = networkErrorText(it)) to null }

internal suspend fun ipv6Address(): String =
    withTimeoutOrNull(6000.milliseconds) { runCatching { Net.client.get("https://api6.ipify.org").bodyAsText().trim() }.getOrNull() }
        ?.takeIf { ':' in it }.orEmpty()

private suspend fun dohAddresses(): List<String> = runCatching {
    DnsClient.queryDoh(DnsResolver.CLOUDFLARE.dohUrl!!, ConnectionProbe.CHECK_HOST, 1, DohFlavour.JSON).answers.filter { it.type == 1 }.map { it.data }
}.getOrDefault(emptyList())

@Composable
private fun ConnectionCheckScreen() {
    val local = NetCapabilities.interfaces
    var run by remember { mutableStateOf(0) }
    var facts by remember { mutableStateOf(ConnectionFacts(local)) }

    LaunchedEffect(run) {
        facts = ConnectionFacts(local)
        fun update(change: ConnectionFacts.() -> ConnectionFacts) {
            facts = facts.change()
        }
        if (local) {
            launch {
                val link = wifiDetails()
                val interfaces = networkInterfaces()
                update { copy(link = link, interfaces = interfaces) }
                val gateway = facts.gatewayAddress
                if (!gateway.isNullOrEmpty()) {
                    launch { runCatching { routerName(gateway) }.getOrNull()?.let { name -> update { copy(router = name) } } }
                    val stats = series(gateway) { icmpPing(gateway, it, 1000).timeMs }
                    update { copy(gateway = stats) }
                }
            }
            launch {
                val system = withTimeoutOrNull(8000.milliseconds) { resolveHost(ConnectionProbe.CHECK_HOST) }.orEmpty()
                update { copy(systemDns = system) }
            }
            launch {
                val (portal, skew) = portalProbe()
                update { copy(portal = portal, clockSkew = clockSkew ?: skew) }
            }
        }
        launch {
            val stats = internetStats(local)
            update { copy(internet = stats) }
        }
        launch {
            val doh = dohAddresses()
            update { copy(doh = doh) }
        }
        launch {
            val (trace, skew) = httpsTrace()
            update { copy(https = trace, clockSkew = skew ?: clockSkew) }
        }
        launch {
            val address = ipv6Address()
            update { copy(ipv6 = address) }
        }
    }

    val summary = facts.summary()
    if (summary == null) LoadingRow(Res.string.conn_checking.str()) else Summary(summary)
    ResultCard {
        facts.visibleSteps().forEachIndexed { index, step ->
            if (index > 0) SoftDivider()
            val finding = facts.finding(step)
            val gateway = facts.gatewayAddress
            StepRow(step.title.str(), finding) {
                if (step == CheckStep.GATEWAY && !gateway.isNullOrEmpty()) {
                    facts.router?.let { Hint(stringResource(Res.string.conn_router_model, it)) }
                    TextButton(onClick = { openUrl("http://$gateway") }) { Text(Res.string.conn_open_router.str()) }
                }
                if (step == CheckStep.PORTAL && finding?.verdict == Verdict.FAIL) {
                    TextButton(onClick = { openUrl("http://neverssl.com") }) { Text(Res.string.conn_open_portal.str()) }
                }
            }
        }
    }
    ActionButton(text = Res.string.check.str(), onClick = { run++ }, enabled = summary != null)
    if (!local) Hint(Res.string.conn_browser_hint.str())
}

@Composable
fun VerdictIcon(verdict: Verdict, size: Dp = 24.dp) {
    val colors = LocalStatusColors.current
    val (icon, tint) = when (verdict) {
        Verdict.OK -> Icons.Filled.CheckCircle to colors.good
        Verdict.INFO -> Icons.Filled.Info to colors.info
        Verdict.WARN -> Icons.Filled.Warning to colors.warn
        Verdict.FAIL -> Icons.Filled.Error to colors.bad
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

@Composable
fun Summary(
    finding: Finding,
    title: StringResource = when (finding.verdict) {
        Verdict.FAIL -> Res.string.conn_summary_fail
        Verdict.WARN -> Res.string.conn_summary_warn
        else -> Res.string.conn_summary_ok
    },
) {
    ResultCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VerdictIcon(finding.verdict, 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title.str(), style = MaterialTheme.typography.titleMedium)
                if (finding.verdict != Verdict.OK) Text(stringResource(finding.text, *finding.args), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StepRow(title: String, finding: Finding?, extra: @Composable ColumnScope.() -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (finding == null) LoadingIndicator(Modifier.size(24.dp)) else VerdictIcon(finding.verdict)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (finding != null) Text(stringResource(finding.text, *finding.args), style = MaterialTheme.typography.bodyMedium)
            extra()
        }
    }
}
