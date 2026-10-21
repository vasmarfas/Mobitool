package com.vasmarfas.card.tools.network

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.card.core.Net
import com.vasmarfas.card.core.NetCapabilities
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.httpPing
import com.vasmarfas.card.core.resolveHost
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpConnect
import com.vasmarfas.card.core.tcpExchange
import com.vasmarfas.card.core.tlsHandshake
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.developer.Punycode
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SoftDivider
import com.vasmarfas.card.ui.components.ToolInputField
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val siteCheckTool = Tool(
    id = "site-check",
    category = ToolCategory.NETWORK,
    title = Res.string.site_check,
    description = Res.string.site_check_description,
    icon = Icons.Filled.PublicOff,
    keywords = listOf(
        "is it down", "down for everyone", "site down", "unreachable", "blocked", "dns", "sni", "tls", "http", "globalping",
        "сайт не открывается", "не работает сайт", "недоступен", "лежит", "блокировка", "доступность",
    ),
) { SiteCheckScreen() }

private suspend fun publicDns(resolver: DnsResolver, host: String): DnsView = runCatching {
    val answers = (listOf(1, 28)).flatMap { type ->
        val response = DnsClient.queryDoh(resolver.dohUrl!!, host, type, DohFlavour.JSON)
        if (response.rcode == 3) return@runCatching DnsView(emptyList(), nxdomain = true)
        response.answers.filter { it.type == type }.map { it.data }
    }
    DnsView(answers, nxdomain = false)
}.getOrDefault(DnsView(emptyList(), nxdomain = false))

private fun probeAddresses(facts: SiteFacts): List<String> {
    val all = (facts.system.orEmpty().filterNot { SiteFacts.isStub(it) } + facts.publicAddresses).distinct()
    return all.filter { '.' in it }.take(3) + all.filter { ':' in it }.take(1)
}

private suspend fun connectAll(addresses: List<String>): Map<String, Long?> = coroutineScope {
    addresses.map { address -> async { address to tcpConnect(address, 443, 5000) } }.awaitAll().toMap()
}

private suspend fun handshake(host: String, address: String?): TlsView = runCatching {
    val info = tlsHandshake(host, 443, 8000)
    TlsView(info.protocol, info.trust)
}.getOrElse { error ->
    val other = address?.let { runCatching { tlsHandshake(it, 443, 8000, "example.com"); true }.getOrDefault(false) }
    TlsView(error = networkErrorText(error), otherName = other)
}

private suspend fun plainRedirect(host: String): String? = runCatching {
    val (address, port, request) = ConnectionProbe.plainRequest("http://$host/")
    val response = tcpExchange(address, port, request, 6000).decodeToString()
    val target = ConnectionProbe.header(response, "Location")?.let(::hostFrom).orEmpty()
    target.takeIf { ConnectionProbe.status(response) in 300..399 && it.isNotEmpty() && !SiteFacts.sameSite(host, it) }
}.getOrNull()

private suspend fun httpCheck(host: String, local: Boolean): HttpView {
    if (!local) {
        return runCatching { httpPing("https://$host/", 15_000); HttpView() }.getOrElse { HttpView(error = getString(Res.string.site_browser_failed)) }
    }
    val redirect = plainRedirect(host)
    return runCatching {
        val started = currentEpochMillis()
        Net.client.prepareGet("https://$host/") { timeout { requestTimeoutMillis = 15_000 } }.execute { response ->
            HttpView(response.status.value, currentEpochMillis() - started, response.headers["Server"], redirect = redirect)
        }
    }.getOrElse { HttpView(error = networkErrorText(it), redirect = redirect) }
}

private suspend fun worldCheck(host: String): List<WorldProbe>? = runCatching {
    val country = runCatching { ConnectionProbe.trace(Net.client.get("https://www.cloudflare.com/cdn-cgi/trace").bodyAsText())["loc"] }
        .getOrNull()?.takeIf { it.length == 2 && it != "XX" && it != "T1" }
    suspend fun create(country: String?): String? {
        val response = Net.client.post(Globalping.API) {
            contentType(ContentType.Application.Json)
            setBody(Globalping.request(host, country))
        }
        return (Net.json.parseToJsonElement(response.bodyAsText()).jsonObject["id"] as? JsonPrimitive)?.content
    }
    // a country without probes fails the whole request, the world alone always has some
    val id = create(country) ?: create(null) ?: return@runCatching null
    repeat(20) {
        delay(1_500.milliseconds)
        val body = Net.json.parseToJsonElement(Net.client.get("${Globalping.API}/$id").bodyAsText()).jsonObject
        Globalping.results(body)?.let { return@runCatching it }
    }
    null
}.getOrNull()

@Composable
private fun SiteCheckScreen() {
    val local = NetCapabilities.tcp
    val tls = currentPlatform in PlatformKind.jvm
    var input by rememberSaveable { mutableStateOf("") }
    var run by remember { mutableStateOf(0) }
    var facts by remember { mutableStateOf<SiteFacts?>(null) }

    LaunchedEffect(run) {
        val host = Punycode.toAscii(hostFrom(input)).lowercase().trimEnd('.')
        if (run == 0 || host.isEmpty()) return@LaunchedEffect
        facts = SiteFacts(host, local, tls)
        fun update(change: SiteFacts.() -> SiteFacts) {
            facts = facts?.change()
        }
        launch {
            val world = worldCheck(host)
            update { if (world == null) copy(worldFailed = true) else copy(world = world) }
        }
        launch {
            val http = httpCheck(host, local)
            update { copy(http = http) }
        }
        coroutineScope {
            launch {
                val view = publicDns(DnsResolver.CLOUDFLARE, host)
                update { copy(cloudflare = view) }
            }
            launch {
                val view = publicDns(DnsResolver.GOOGLE, host)
                update { copy(google = view) }
            }
            if (local) launch {
                val system = withTimeoutOrNull(8_000.milliseconds) { resolveHost(host) }.orEmpty()
                update { copy(system = system) }
            }
        }
        val addresses = facts?.let(::probeAddresses).orEmpty()
        if (addresses.isEmpty()) return@LaunchedEffect
        if (!local) return@LaunchedEffect
        val results = connectAll(addresses)
        update { copy(connect = results) }
        val reachable = results.filterValues { it != null }.keys.firstOrNull()
        if (tls) {
            val view = if (reachable == null) TlsView(skipped = true) else handshake(host, reachable)
            update { copy(handshake = view) }
        }
    }

    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = Res.string.site_address.str(),
        placeholder = "example.com",
        keyboardType = KeyboardType.Uri,
        monospace = true,
    )
    val current = facts
    val running = current != null && current.summary() == null
    ActionButton(Res.string.check.str(), onClick = { run++ }, enabled = input.isNotBlank() && !running)
    if (current == null) {
        Hint(Res.string.site_intro.str())
        if (!local) Hint(Res.string.site_browser_hint.str())
        return
    }
    val summary = current.summary()
    if (summary == null) LoadingRow(Res.string.conn_checking.str()) else Summary(summary.finding, summary.headline)
    ResultCard {
        current.visibleSteps().forEachIndexed { index, step ->
            if (index > 0) SoftDivider()
            StepRow(step.title.str(), current.finding(step)) {
                when (step) {
                    SiteStep.DNS -> {
                        if (local && current.system != null) DnsLine(Res.string.site_dns_system.str(), current.system)
                        current.cloudflare?.let { DnsLine("Cloudflare", it.addresses) }
                        current.google?.let { DnsLine("Google", it.addresses) }
                    }
                    SiteStep.HTTP -> current.http?.server?.let { Hint(stringResource(Res.string.site_http_server_header, it)) }
                    SiteStep.WORLD -> current.world?.forEach { probe ->
                        val result = if (probe.ok || probe.status != null) "${probe.status} · ${probe.ms ?: "—"} ${Res.string.unit_ms.str()}" else probe.error
                        Text("${if (probe.ok) "✓" else "✗"} ${probe.place}: $result", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Unit
                }
            }
        }
    }
    Hint(Res.string.site_world_note.str())
}

@Composable
private fun DnsLine(label: String, addresses: List<String>) {
    Text("$label: ${addresses.joinToString(", ").ifEmpty { "—" }}", style = MaterialTheme.typography.bodySmall)
}
