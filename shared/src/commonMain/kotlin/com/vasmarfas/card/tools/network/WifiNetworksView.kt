package com.vasmarfas.card.tools.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Wifi
import com.vasmarfas.card.core.WifiAccessPoint
import com.vasmarfas.card.core.WifiBand
import com.vasmarfas.card.core.appLang
import com.vasmarfas.card.core.regionName
import com.vasmarfas.card.core.saveBytes
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.media.SaveButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SoftDivider
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class WifiSort(val title: StringResource) {
    SIGNAL(Res.string.wifi_sort_signal),
    NAME(Res.string.wifi_sort_name),
    CHANNEL(Res.string.wifi_sort_channel),
}

@Composable
fun WifiNetworks(points: List<WifiAccessPoint>, onTrack: (String) -> Unit) {
    if (points.isEmpty()) return
    var band by rememberSaveable { mutableStateOf<WifiBand?>(null) }
    var sort by rememberSaveable { mutableStateOf(WifiSort.SIGNAL) }
    var expanded by remember { mutableStateOf<String?>(null) }
    val vendors by produceState(emptyMap<String, String>(), points.size) {
        val registry = runCatching { MacVendors.registry() }.getOrNull() ?: return@produceState
        value = points.associate { point ->
            val hex = MacAddress.normalize(point.bssid)
            point.bssid to (hex?.takeUnless(MacAddress::isLocal)?.let { registry.find(it)?.vendor }.orEmpty())
        }
    }
    val bands = points.map { it.band }.distinct().sorted()
    if (bands.size > 1) ChoiceChips(listOf<WifiBand?>(null) + bands, band, { band = it }, { it?.let { b -> bandLabel(b) } ?: Res.string.all.str() })
    SegmentedChoice(WifiSort.entries, sort, { sort = it }, { it.title.str() })
    val shown = points.filter { band == null || it.band == band }.let { list ->
        when (sort) {
            WifiSort.SIGNAL -> list.sortedWith(compareByDescending<WifiAccessPoint> { it.connected }.thenByDescending { it.rssi })
            WifiSort.NAME -> list.sortedWith(compareBy<WifiAccessPoint> { it.ssid.isEmpty() }.thenBy { it.ssid.lowercase() }.thenByDescending { it.rssi })
            WifiSort.CHANNEL -> list.sortedWith(compareBy<WifiAccessPoint> { it.frequency }.thenByDescending { it.rssi })
        }
    }
    ResultCard {
        shown.forEachIndexed { index, point ->
            if (index > 0) SoftDivider()
            NetworkRow(point, vendors[point.bssid].orEmpty()) { expanded = if (expanded == point.bssid) null else point.bssid }
            if (expanded == point.bssid) NetworkDetails(point, vendors[point.bssid].orEmpty(), onTrack)
        }
    }
    if (points.any { it.approximate }) Hint(Res.string.wifi_approximate.str())
    SaveButton(Res.string.wifi_save_csv.str()) { saveBytes(wifiCsv(points, vendors).encodeToByteArray(), "wifi-scan.csv") }
}

@Composable
fun bandLabel(band: WifiBand): String = stringResource(Res.string.wifi_band_ghz, band.label)

@Composable
private fun NetworkRow(point: WifiAccessPoint, vendor: String, onClick: () -> Unit) {
    val color = signalColor(point.rssi)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(signalIcon(point.rssi), contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val name = point.title() + if (point.connected) " · " + Res.string.wifi_connected.str() else ""
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(point.bssid, vendor).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(
                    stringResource(Res.string.wifi_channel_short, point.channel.toString()),
                    bandLabel(point.band),
                    "${point.width} ${Res.string.unit_mhz.str()}",
                    point.generation,
                    point.security.ifEmpty { Res.string.wifi_open.str() },
                ).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(point.rssi.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
            Text(Res.string.unit_dbm.str(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NetworkDetails(point: WifiAccessPoint, vendor: String, onTrack: (String) -> Unit) {
    val mhz = Res.string.unit_mhz.str()
    Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyValueRow(Res.string.wifi_signal_strength.str(), "${point.rssi} ${Res.string.unit_dbm.str()} · ${signalQuality(point.rssi).str()} · ${stringResource(Res.string.percent_value, signalPercent(point.rssi).toString())}", mono = false, copyable = false)
        val center = if (point.centerFrequency != point.frequency) " · " + stringResource(Res.string.wifi_center, "${point.centerFrequency} $mhz") else ""
        KeyValueRow(Res.string.frequency.str(), "${point.frequency} $mhz$center", mono = false, copyable = false)
        val covered = (0 until point.width / 20).map { Wifi.channel(point.centerFrequency - point.width / 2 + 10 + 20 * it) }
        val channels = when {
            covered.size < 2 -> point.channel.toString()
            point.band == WifiBand.GHZ_2_4 -> covered.joinToString("+")
            else -> "${covered.first()}–${covered.last()}"
        }
        KeyValueRow(Res.string.wifi_channel.str(), channels + if (WifiChannels.isDfs(point.channel) && point.band == WifiBand.GHZ_5) " · DFS" else "", mono = false, copyable = false)
        KeyValueRow(Res.string.wifi_width.str(), "${point.width} $mhz", mono = false, copyable = false)
        if (point.standard.isNotEmpty()) KeyValueRow(Res.string.wifi_standard.str(), listOf(point.standard, point.generation).filter { it.isNotEmpty() }.joinToString(" · "), mono = false, copyable = false)
        KeyValueRow(Res.string.security.str(), point.security.ifEmpty { Res.string.wifi_open.str() }, mono = false, copyable = false)
        point.streams?.let { KeyValueRow(Res.string.wifi_streams.str(), "$it×$it", mono = false, copyable = false) }
        point.stations?.let { KeyValueRow(Res.string.wifi_stations.str(), it.toString(), mono = false, copyable = false) }
        point.utilization?.let { KeyValueRow(Res.string.wifi_utilization.str(), stringResource(Res.string.percent_value, it.toString()), mono = false, copyable = false) }
        point.country?.let { KeyValueRow(Res.string.country.str(), regionName(it, appLang)?.let { name -> "$name ($it)" } ?: it, mono = false, copyable = false) }
        if (point.features.isNotEmpty()) KeyValueRow(Res.string.wifi_features.str(), point.features.sorted().joinToString(", "), mono = false, copyable = false)
        val hex = MacAddress.normalize(point.bssid)
        KeyValueRow(
            Res.string.wifi_vendor.str(),
            when {
                hex != null && MacAddress.isLocal(hex) -> Res.string.wifi_local_mac.str()
                vendor.isNotEmpty() -> vendor
                else -> Res.string.wifi_vendor_unknown.str()
            },
            mono = false,
            copyable = false,
        )
        TextButton(onClick = { onTrack(point.bssid) }) { Text(Res.string.wifi_track.str()) }
    }
}

fun wifiCsv(points: List<WifiAccessPoint>, vendors: Map<String, String>): String {
    fun cell(value: Any?) = value?.toString().orEmpty().let { if (it.any { c -> c == ',' || c == '"' || c == '\n' }) "\"" + it.replace("\"", "\"\"") + "\"" else it }
    val header = "SSID,BSSID,Vendor,RSSI dBm,Frequency MHz,Channel,Width MHz,Security,Standard,Stations,Utilization %,Connected"
    return (listOf(header) + points.map { p ->
        listOf(p.ssid, p.bssid, vendors[p.bssid], p.rssi, p.frequency, p.channel, p.width, p.security, p.standard, p.stations, p.utilization, p.connected)
            .joinToString(",") { cell(it) }
    }).joinToString("\n", postfix = "\n")
}
