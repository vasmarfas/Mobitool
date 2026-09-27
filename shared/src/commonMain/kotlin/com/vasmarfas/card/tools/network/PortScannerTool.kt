package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radar
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
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.PortProbe
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.tcpConnect
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.compose.resources.stringResource

val portScannerTool = Tool(
    id = "port-scanner",
    category = ToolCategory.NETWORK,
    title = Res.string.port_scanner,
    description = Res.string.port_scanner_description,
    icon = Icons.Filled.Radar,
    keywords = listOf("nmap", "open ports", "tcp", "scan", "открытые порты", "сканирование"),
    platforms = PlatformKind.native,
    expandable = true,
) { PortScannerScreen() }

fun parsePorts(spec: String): List<Int> {
    val out = LinkedHashSet<Int>()
    spec.split(Regex("[,;\\s]+")).filter { it.isNotBlank() }.forEach { part ->
        if (part.contains('-')) {
            val a = part.substringBefore('-').toIntOrNull()?.coerceAtLeast(1)
            val b = part.substringAfter('-').toIntOrNull()?.coerceAtMost(65535)
            if (a != null && b != null && a <= b) out.addAll(a..b)
        } else {
            part.toIntOrNull()?.takeIf { it in 1..65535 }?.let { out += it }
        }
    }
    return out.toList()
}

@Composable
private fun PortScannerScreen() {
    val enterAHostAndPortsText = Res.string.enter_a_host_and_ports.str()
    var host by rememberSaveable { mutableStateOf("192.168.88.1") }
    var spec by rememberSaveable { mutableStateOf(WellKnownPorts.topTcp.joinToString(",")) }
    var timeout by rememberSaveable { mutableStateOf("800") }
    var concurrency by rememberSaveable { mutableStateOf("64") }
    val results = remember { mutableStateListOf<PortProbe>() }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        val target = hostFrom(host)
        val ports = parsePorts(spec)
        if (target.isEmpty() || ports.isEmpty()) {
            error = enterAHostAndPortsText
            return
        }
        error = null; results.clear(); running = true; done = 0; total = ports.size
        val t = timeout.toIntOrNull()?.coerceIn(100, 10_000) ?: 800
        val parallel = concurrency.toIntOrNull()?.coerceIn(1, 256) ?: 64
        job = scope.launch {
            val semaphore = Semaphore(parallel)
            coroutineScope {
                ports.map { port ->
                    async {
                        semaphore.withPermit {
                            val ms = tcpConnect(target, port, t)
                            if (ms != null) results += PortProbe(port, true, ms)
                            done++
                        }
                    }
                }.awaitAll()
            }
            running = false
        }
    }

    ToolInputField(value = host, onValueChange = { host = it }, label = Res.string.host_or_ip_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
    ToolInputField(value = spec, onValueChange = { spec = it }, label = Res.string.ports_22_80_443_or_1_1024.str(), monospace = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { spec = WellKnownPorts.topTcp.joinToString(",") }) { Text(Res.string.top_ports.str()) }
        TextButton(onClick = { spec = "1-1024" }) { Text("1–1024") }
        TextButton(onClick = { spec = "1-65535" }) { Text(Res.string.all.str()) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(value = timeout, onValueChange = { timeout = it }, label = Res.string.timeout_ms.str(), modifier = Modifier.weight(1f))
        NumberField(value = concurrency, onValueChange = { concurrency = it }, label = Res.string.parallel.str(), modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = Res.string.start.str(), onClick = ::start, enabled = !running)
        if (running) TextButton(onClick = { job?.cancel(); running = false }) { Text(Res.string.stop_short.str()) }
    }
    error?.let { ErrorText(it) }
    if (running) LoadingRow("${Res.string.scanning.str()} $done / $total")
    if (results.isNotEmpty() || (!running && total > 0)) {
        ResultCard(title = stringResource(Res.string.ports_open_count, results.size) + " · $done / $total") {
            results.sortedBy { it.port }.forEach { p ->
                KeyValueRow("${p.port}/tcp", "${WellKnownPorts.service(p.port) ?: "?"} · ${p.timeMs} ${Res.string.unit_ms.str()}", mono = false, copyable = false)
            }
            if (results.isEmpty() && !running) Text(Res.string.no_open_ports_found.str(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
