package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.TracerouteHop
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.icmpPing
import com.vasmarfas.card.core.resolveHost
import com.vasmarfas.card.core.reverseLookup
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

val tracerouteTool = Tool(
    id = "traceroute",
    category = ToolCategory.NETWORK,
    title = Res.string.traceroute,
    description = Res.string.traceroute_description,
    icon = Icons.Filled.Route,
    keywords = listOf("tracert", "mtr", "hops", "path", "ttl", "маршрут", "узлы"),
    platforms = PlatformKind.native,
) { TracerouteScreen() }

@Composable
private fun TracerouteScreen() {
    val cannotResolveHostText = Res.string.cannot_resolve_host.str()
    var host by rememberSaveable { mutableStateOf("vasmarfas.com") }
    var resolveNames by rememberSaveable { mutableStateOf(true) }
    val hops = remember { mutableStateListOf<TracerouteHop>() }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    fun start() {
        val h = hostFrom(host)
        if (h.isEmpty()) return
        hops.clear(); error = null; running = true
        job = scope.launch {
            val address = resolveHost(h).firstOrNull { Ipv4.parse(it) != null } ?: run {
                error = cannotResolveHostText
                running = false
                return@launch
            }
            target = address
            var consecutiveTimeouts = 0
            for (ttl in 1..30) {
                val reply = icmpPing(address, ttl, 2500, ttl)
                val from = reply.from
                val reached = reply.error == null && reply.timeMs != null && (from == address || from == null)
                val name = if (resolveNames && from != null && from != address) reverseLookup(from) else null
                val hop = TracerouteHop(ttl, from ?: if (reached) address else null, name, reply.timeMs)
                hops += hop
                if (reached) break
                consecutiveTimeouts = if (from == null) consecutiveTimeouts + 1 else 0
                if (consecutiveTimeouts >= 8) break
            }
            running = false
        }
    }

    ToolInputField(value = host, onValueChange = { host = it }, label = Res.string.host_or_ip_address.str(), keyboardType = KeyboardType.Uri, monospace = true)
    SwitchRow(Res.string.resolve_hostnames.str(), resolveNames, { resolveNames = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = Res.string.start.str(), onClick = ::start, enabled = !running)
        if (running) TextButton(onClick = { job?.cancel(); running = false }) { Text(Res.string.stop_short.str()) }
    }
    error?.let { ErrorText(it) }
    if (running) LoadingRow(Res.string.tracing.str())
    if (hops.isNotEmpty()) {
        ResultCard(title = "→ $target") {
            TableBlock {
                TableRow(listOf("#", Res.string.address.str(), Res.string.name.str(), Res.string.time.str()), header = true, weights = listOf(0.4f, 1.6f, 2.4f, 0.9f))
                hops.forEach { hop ->
                    TableRow(
                        listOf(
                            hop.hop.toString(),
                            hop.address ?: "*",
                            hop.hostname ?: "",
                            hop.timeMs?.let { "${it.fmt(1)} ${Res.string.unit_ms.str()}" } ?: "*",
                        ),
                        weights = listOf(0.4f, 1.6f, 2.4f, 0.9f),
                    )
                }
            }
        }
    }
}
