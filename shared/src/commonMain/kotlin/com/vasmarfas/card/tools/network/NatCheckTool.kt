package com.vasmarfas.card.tools.network

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
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
import com.vasmarfas.card.core.browserIceCandidates
import com.vasmarfas.card.core.browserJsonp
import com.vasmarfas.card.core.icmpPing
import com.vasmarfas.card.core.networkInterfaces
import com.vasmarfas.card.core.parseXml
import com.vasmarfas.card.core.ssdpDiscover
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpExchange
import com.vasmarfas.card.core.udpExchanges
import com.vasmarfas.card.core.udpQuery
import com.vasmarfas.card.core.wifiDetails
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SoftDivider
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

val natCheckTool = Tool(
    id = "nat-check",
    category = ToolCategory.NETWORK,
    title = Res.string.nat_check,
    description = Res.string.nat_check_description,
    icon = Icons.Filled.SwapHoriz,
    keywords = listOf(
        "white ip", "public ip", "cgnat", "carrier-grade nat", "nat type", "symmetric nat", "stun", "upnp", "nat-pmp", "port forwarding",
        "белый ip", "серый ip", "nat", "проброс портов", "тип nat", "внешний адрес роутера",
    ),
) { NatCheckScreen() }

private val stunServers = listOf(
    IpSource.STUN_GOOGLE to 19302, IpSource.STUN_CLOUDFLARE to 3478, IpSource.STUN_SIPNET to 3478, IpSource.STUN_ZADARMA to 3478,
)

private suspend fun stunSeen(local: Boolean): List<SeenIp> {
    if (!local) {
        return browserIceCandidates(stunServers.map { (source, port) -> "stun:${source.host}:$port" }, 5_000)
            .map { it.split(' ') }
            .filter { it.getOrNull(7) == "srflx" && '.' in it.getOrElse(4) { "" } }
            .map { "${it[4]}:${it[5]}" }
            .distinct()
            .map { SeenIp(IpSource.WEBRTC, it) }
    }
    val replies = udpExchanges(stunServers.map { (source, port) -> source.host to port }, Stun.request(Random.nextBytes(12)), 2_000)
    return stunServers.zip(replies) { (source, _), reply -> SeenIp(source, reply?.let(Stun::mappedAddress)) }
}

// Yandex sends no CORS headers, a page asks it through JSONP instead
private suspend fun httpSeen(source: IpSource, local: Boolean): String? = runCatching {
    when (source) {
        IpSource.YANDEX -> if (local) {
            Net.client.get("https://${source.host}/api/v0/ip").bodyAsText()
        } else {
            browserJsonp("https://${source.host}/api/v0/ip", 6_000)
        }
        IpSource.BEGET -> Net.client.get("https://${source.host}/").bodyAsText()
        IpSource.CLOUDFLARE -> ConnectionProbe.trace(Net.client.get("https://${source.host}/cdn-cgi/trace").bodyAsText())["ip"]
        else -> Net.client.get("https://${source.host}/").bodyAsText()
    }?.trim()?.trim('"')?.takeIf { Ipv4.parse(it) != null }
}.getOrNull()

private fun httpSources(local: Boolean) = listOf(IpSource.YANDEX, IpSource.BEGET, IpSource.CLOUDFLARE, IpSource.IPIFY).filter { local || it != IpSource.BEGET }

private suspend fun upnpExternal(gateway: String?): String? {
    val locations = ssdpDiscover(2_500).filter { gateway == null || it.address == gateway }.mapNotNull { it.headers["LOCATION"] }.distinct()
    for (location in locations) {
        val description = runCatching { parseXml(ConnectionProbe.body(plainGet(location, 3_000))) }.getOrNull() ?: continue
        val (type, control) = Upnp.wanService(description) ?: continue
        val url = Upnp.controlUrl(location, control)
        val authority = url.substringAfter("://").substringBefore('/')
        val response = runCatching {
            tcpExchange(authority.substringBefore(':'), authority.substringAfter(':', "80").toIntOrNull() ?: 80, Upnp.request(url, type, "GetExternalIPAddress"), 3_000)
        }.getOrNull() ?: continue
        Upnp.externalIp(response.decodeToString())?.let { return it }
    }
    return null
}

private suspend fun hops(target: String): List<String?> {
    val path = mutableListOf<String?>()
    for (ttl in 1..5) {
        val hop = icmpPing(target, ttl, 2_000, ttl).from
        path += hop
        if (hop == target) break
    }
    return path
}

@Composable
private fun NatCheckScreen() {
    val local = NetCapabilities.udp
    var run by remember { mutableStateOf(0) }
    var facts by remember { mutableStateOf(NatFacts(local)) }

    LaunchedEffect(run) {
        facts = NatFacts(local)
        fun update(change: NatFacts.() -> NatFacts) {
            facts = facts.change()
        }
        launch {
            val paths = coroutineScope {
                val http = async { httpSources(local).map { source -> async { SeenIp(source, httpSeen(source, local)) } }.awaitAll() }
                val stun = async { stunSeen(local) }
                http.await() to stun.await()
            }
            update { copy(http = paths.first, stun = paths.second) }
            if (local) {
                launch {
                    val path = hops(facts.directIp ?: "77.88.8.8")
                    update { copy(hops = path) }
                }
            }
            val holders = coroutineScope {
                facts.observed.map { ip -> async { ip to runCatching { RipeStat.lookup(ip)?.origins?.firstOrNull() }.getOrNull() } }.awaitAll()
            }.mapNotNull { (ip, origin) -> origin?.let { ip to "AS${it.first} ${it.second}" } }.toMap()
            update { copy(holders = holders) }
        }
        launch {
            val address = ipv6Address()
            update { copy(ipv6 = address) }
        }
        if (local) {
            launch {
                val link = wifiDetails()
                // Android keeps mobile data up next to Wi-Fi, so only the addresses of the network in use count
                val addresses = (link["Addresses"]?.split(", ") ?: networkInterfaces().filter { it.isUp && !it.isLoopback }.flatMap { it.addresses })
                    .map { it.substringBefore('/') }.filter { '.' in it && !it.startsWith("169.254.") }
                val gateway = link["Gateway"]?.takeIf { it.isNotEmpty() && it != "0.0.0.0" }
                update { copy(addresses = addresses, gateway = gateway) }
                val pmp = gateway?.let { NatPmp.externalAddress(udpQuery(it, 5351, NatPmp.request, 1_500)) }
                val upnp = if (pmp == null) upnpExternal(gateway) else null
                update { copy(router = pmp ?: upnp, routerSource = if (pmp != null) "NAT-PMP" else if (upnp != null) "UPnP" else "", routerDone = true) }
            }
        }
    }

    val current = facts
    if (!current.done) {
        LoadingRow(Res.string.conn_checking.str())
    } else {
        val ipClass = current.ipClass
        val verdict = when (ipClass) {
            IpClass.WHITE, IpClass.WHITE_LIKELY -> Verdict.OK
            IpClass.GRAY, IpClass.GRAY_LIKELY -> Verdict.WARN
            IpClass.UNKNOWN -> Verdict.INFO
        }
        val hint = when (ipClass) {
            IpClass.WHITE, IpClass.WHITE_LIKELY -> Res.string.nat_white_hint
            IpClass.GRAY, IpClass.GRAY_LIKELY -> Res.string.nat_gray_hint
            IpClass.UNKNOWN -> if (local) Res.string.nat_unknown_hint else Res.string.nat_unknown_browser
        }
        Summary(Finding(verdict, hint), ipClass.title)
    }
    ResultCard {
        StepRow(Res.string.nat_step_public.str(), current.pathsFinding()) {
            if (current.http != null && current.stun != null) SeenTable(current)
            current.ipv6?.takeIf { it.isNotEmpty() }?.let { Hint(stringResource(Res.string.nat_ipv6, it)) }
        }
        if (local) {
            SoftDivider()
            val addresses = current.addresses
            StepRow(
                Res.string.nat_step_device.str(),
                addresses?.let { list ->
                    when {
                        list.isEmpty() -> Finding(Verdict.INFO, Res.string.nat_device_none)
                        list.any { it in current.observed } -> Finding(Verdict.OK, Res.string.nat_device_public, list.joinToString(", "))
                        else -> Finding(Verdict.INFO, Res.string.nat_device, list.joinToString(", "))
                    }
                },
            )
            SoftDivider()
            StepRow(Res.string.nat_step_router.str(), current.routerFinding())
            SoftDivider()
            StepRow(Res.string.nat_step_path.str(), current.pathFinding()) {
                current.hops?.let { list -> Hint(list.mapIndexed { index, hop -> "${index + 1}: ${hop ?: "*"}" }.joinToString("  ")) }
            }
        }
        SoftDivider()
        StepRow(Res.string.nat_step_type.str(), current.natFinding())
    }
    ActionButton(Res.string.check.str(), onClick = { run++ }, enabled = current.done)
    if (!local) Hint(Res.string.nat_browser_hint.str())
}

@Composable
private fun SeenTable(facts: NatFacts) {
    val failed = Res.string.nat_no_answer.str()
    facts.seen.forEach { seen ->
        val via = if (seen.source.udp) "UDP" else "HTTPS"
        Text("${seen.source.host} · $via: ${seen.address ?: failed}", style = MaterialTheme.typography.bodySmall)
    }
    facts.observed.forEach { ip -> facts.holders[ip]?.let { Hint("$ip — $it") } }
}
