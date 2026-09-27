package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.LanHost
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.icmpPing
import com.vasmarfas.card.core.networkInterfaces
import com.vasmarfas.card.core.reverseLookup
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpConnect
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.pluralStringResource

val lanScannerTool = Tool(
    id = "lan-scanner",
    category = ToolCategory.NETWORK,
    title = Res.string.lan_scanner,
    description = Res.string.lan_scanner_description,
    icon = Icons.Filled.Devices,
    keywords = listOf("network scan", "devices", "arp", "subnet", "hosts", "устройства", "сеть", "кто в сети"),
    platforms = PlatformKind.native,
) { LanScannerScreen() }

private val probePorts = listOf(80, 443, 22, 445, 8080, 8291, 62078, 5000)

private suspend fun localSubnetGuess(): String {
    val candidates = networkInterfaces().filter { it.isUp && !it.isLoopback }.flatMap { it.addresses }
        .filter { it.contains('.') }
        .mapNotNull { a ->
            val ip = Ipv4.parse(a.substringBefore('/')) ?: return@mapNotNull null
            val prefix = a.substringAfter('/', "24").toIntOrNull() ?: 24
            Ipv4Subnet(ip, prefix.coerceIn(16, 30))
        }
    val best = candidates.firstOrNull { it.isPrivate } ?: candidates.firstOrNull() ?: return "192.168.1.0/24"
    val prefix = best.prefix.coerceAtLeast(24)
    val net = Ipv4Subnet(best.address, prefix)
    return "${Ipv4.format(net.network)}/$prefix"
}

@Composable
private fun LanScannerScreen() {
    val enterASubnetOf20OrSmallerText = Res.string.enter_a_subnet_of_20_or_smaller.str()
    var subnet by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (subnet.isEmpty()) subnet = localSubnetGuess() }
    val hosts = remember { mutableStateListOf<LanHost>() }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        val net = Ipv4.parseSubnet(subnet)
        if (net == null || net.prefix < 20) {
            error = enterASubnetOf20OrSmallerText
            return
        }
        error = null; hosts.clear(); running = true; done = 0
        val addresses = (net.firstHost..net.lastHost).map { Ipv4.format(it) }
        total = addresses.size
        job = scope.launch {
            val semaphore = Semaphore(48)
            coroutineScope {
                addresses.map { address ->
                    async {
                        semaphore.withPermit {
                            val open = mutableListOf<Int>()
                            var best: Long? = null
                            val ping = icmpPing(address, 1, 1000)
                            if (ping.timeMs != null) best = ping.timeMs.toLong()
                            coroutineScope {
                                probePorts.map { port ->
                                    async {
                                        val ms = tcpConnect(address, port, 500)
                                        if (ms != null) {
                                            open += port
                                            if (best == null || ms < best!!) best = ms
                                        }
                                    }
                                }.awaitAll()
                            }
                            best?.let { rtt ->
                                val name = reverseLookup(address)
                                hosts += LanHost(address, name, rtt, open.sorted())
                            }
                            done++
                        }
                    }
                }.awaitAll()
            }
            running = false
        }
    }

    ToolInputField(value = subnet, onValueChange = { subnet = it }, label = Res.string.subnet.str(), keyboardType = KeyboardType.Uri, monospace = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = Res.string.start.str(), onClick = ::start, enabled = !running)
        if (running) TextButton(onClick = { job?.cancel(); running = false }) { Text(Res.string.stop_short.str()) }
        TextButton(onClick = { scope.launch { subnet = localSubnetGuess() } }) { Text(Res.string.detect.str()) }
    }
    error?.let { ErrorText(it) }
    if (running) LoadingRow("${Res.string.scanning.str()} $done / $total")
    if (hosts.isNotEmpty() || (!running && total > 0)) {
        ResultCard(title = pluralStringResource(Res.plurals.lan_host_count, hosts.size, hosts.size)) {
            TableBlock {
                TableRow(listOf(Res.string.address.str(), Res.string.name.str(), Res.string.open_ports.str(), Res.string.unit_ms.str()), header = true, weights = listOf(1.4f, 2f, 1.6f, 0.6f))
                hosts.sortedBy { Ipv4.parse(it.address) ?: 0 }.forEach { h ->
                    TableRow(
                        listOf(h.address, h.hostname ?: "", h.openPorts.joinToString(",") { p -> WellKnownPorts.service(p)?.let { s -> "$p $s" } ?: p.toString() }, h.timeMs.toString()),
                        weights = listOf(1.4f, 2f, 1.6f, 0.6f),
                    )
                }
            }
            if (hosts.isEmpty() && !running) Text(Res.string.no_hosts_responded.str(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
