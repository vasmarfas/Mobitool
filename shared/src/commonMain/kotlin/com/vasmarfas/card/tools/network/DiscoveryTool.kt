package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.DiscoveredDevice
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.mdnsQuery
import com.vasmarfas.card.core.ssdpDiscover
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.ResultCard
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource

val discoveryTool = Tool(
    id = "device-discovery",
    category = ToolCategory.NETWORK,
    title = Res.string.upnp_and_bonjour_discovery,
    description = Res.string.device_discovery_description,
    icon = Icons.Filled.Cast,
    keywords = listOf("ssdp", "upnp", "mdns", "bonjour", "dns-sd", "avahi", "printers", "chromecast", "устройства", "сервисы"),
    platforms = PlatformKind.jvm,
) { DiscoveryScreen() }

@Composable
private fun DiscoveryScreen() {
    var loading by remember { mutableStateOf(false) }
    var ssdp by remember { mutableStateOf<List<DiscoveredDevice>?>(null) }
    var mdns by remember { mutableStateOf<Map<String, List<DnsRecord>>?>(null) }
    val scope = rememberCoroutineScope()

    fun runSsdp() {
        loading = true
        scope.launch {
            ssdp = ssdpDiscover(4000)
            loading = false
        }
    }

    fun runMdns() {
        loading = true
        scope.launch {
            val records = mdnsQuery("_services._dns-sd._udp.local", 4000)
                .mapNotNull { runCatching { DnsMessage.parse(it) }.getOrNull() }
                .flatMap { it.answers + it.additional }
            val types = records.filter { it.type == 12 && it.name.endsWith("_dns-sd._udp.local") }.map { it.data }.distinct()
            val detail = LinkedHashMap<String, List<DnsRecord>>()
            for (type in types) {
                val instances = mdnsQuery(type, 2500)
                    .mapNotNull { runCatching { DnsMessage.parse(it) }.getOrNull() }
                    .flatMap { it.answers + it.additional }
                    .distinctBy { it.name + it.type + it.data }
                detail[type] = instances
            }
            mdns = detail
            loading = false
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(text = "UPnP / SSDP", onClick = ::runSsdp, enabled = !loading)
        ActionButton(text = "Bonjour / mDNS", onClick = ::runMdns, enabled = !loading)
    }
    if (loading) LoadingRow(Res.string.listening_for_replies.str())
    ssdp?.let { devices ->
        ResultCard(title = "SSDP · ${devices.size}") {
            if (devices.isEmpty()) Text(Res.string.no_results.str(), style = MaterialTheme.typography.bodyMedium)
            devices.forEach { d ->
                KeyValueRow(d.address, listOfNotNull(d.headers["SERVER"], d.headers["ST"], d.headers["LOCATION"], d.headers["USN"]).joinToString("\n"), mono = true)
            }
        }
    }
    mdns?.let { services ->
        ResultCard(title = "mDNS · " + pluralStringResource(Res.plurals.mdns_service_type_count, services.size, services.size)) {
            if (services.isEmpty()) Text(Res.string.no_results.str(), style = MaterialTheme.typography.bodyMedium)
            services.forEach { (type, records) ->
                val instances = records.filter { it.type == 12 }.map { it.data }
                val details = records.filter { it.type != 12 }.joinToString("\n") { "${it.typeName} ${it.name} → ${it.data}" }
                KeyValueRow(type, (instances.joinToString("\n") + "\n" + details).trim())
            }
        }
    }
}
