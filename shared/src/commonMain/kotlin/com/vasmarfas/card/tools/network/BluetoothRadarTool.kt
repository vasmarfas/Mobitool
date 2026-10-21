package com.vasmarfas.card.tools.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.BleAdvert
import com.vasmarfas.card.core.BleException
import com.vasmarfas.card.core.BleProblem
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.bleAdverts
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ChartSeries
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.InteractiveChart
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SoftDivider
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val bluetoothRadarTool = Tool(
    id = "bluetooth-radar",
    category = ToolCategory.NETWORK,
    title = Res.string.bluetooth_radar,
    description = Res.string.bluetooth_radar_description,
    icon = Icons.Filled.BluetoothSearching,
    keywords = listOf(
        "bluetooth", "ble", "scanner", "airtag", "smarttag", "tile", "tracker", "find my", "ibeacon", "eddystone", "airpods", "headphones",
        "блютуз", "найти наушники", "трекер", "слежка", "маячок", "поиск устройств",
    ),
    platforms = PlatformKind.native,
) { BluetoothRadarScreen() }

private const val STALE_MS = 30_000L
private const val SAMPLES = 120

private const val SMOOTHING = 0.25

class BleEntry(val advert: BleAdvert, val kind: BleKind, val firstSeen: Long, val lastSeen: Long, val smoothed: Double, val samples: List<Int>)

private enum class BleFilter(val title: StringResource) {
    ALL(Res.string.all),
    TRACKERS(Res.string.ble_filter_trackers),
    NAMED(Res.string.ble_filter_named),
}

@Composable
private fun BluetoothRadarScreen() {
    val entries = remember { mutableStateMapOf<String, BleEntry>() }
    var problem by remember { mutableStateOf<BleProblem?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(BleFilter.ALL) }
    val names by produceState<BluetoothNames?>(null) { value = runCatching { BluetoothNames.load() }.getOrNull() }

    LaunchedEffect(attempt) {
        problem = null
        try {
            bleAdverts().collect { advert ->
                val now = currentEpochMillis()
                val old = entries[advert.id]
                entries[advert.id] = BleEntry(
                    advert = advert,
                    kind = BleDecoder.kind(advert),
                    firstSeen = old?.firstSeen ?: now,
                    lastSeen = now,
                    smoothed = old?.let { it.smoothed + SMOOTHING * (advert.rssi - it.smoothed) } ?: advert.rssi.toDouble(),
                    samples = ((old?.samples ?: emptyList()) + advert.rssi).takeLast(SAMPLES),
                )
            }
        } catch (e: BleException) {
            problem = e.problem
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000.milliseconds)
            val now = currentEpochMillis()
            entries.keys.filter { now - (entries[it]?.lastSeen ?: 0) > STALE_MS && it != selected }.forEach { entries.remove(it) }
        }
    }

    when (problem) {
        BleProblem.NO_PERMISSION -> PermissionPrompt(AppPermission.BLUETOOTH, Res.string.ble_permission.str()) { attempt++ }
        BleProblem.OFF -> {
            ErrorText(Res.string.ble_off.str())
            TextButton(onClick = { attempt++ }) { Text(Res.string.try_again.str()) }
        }
        BleProblem.UNSUPPORTED -> ErrorText(Res.string.ble_unsupported.str())
        BleProblem.FAILED -> {
            ErrorText((if (currentPlatform == PlatformKind.DESKTOP) Res.string.ble_failed_desktop else Res.string.ble_failed).str())
            TextButton(onClick = { attempt++ }) { Text(Res.string.try_again.str()) }
        }
        null -> if (entries.isEmpty()) LoadingRow(Res.string.ble_scanning.str())
    }
    if (problem != null) return

    entries[selected]?.let { entry ->
        Finder(entry, names) { selected = null }
        return
    }

    val all = entries.values.sortedByDescending { it.smoothed }
    val trackers = all.filter { it.kind.tracker }
    if (trackers.isNotEmpty()) {
        ResultCard(Res.string.ble_trackers_nearby.str()) {
            trackers.forEach { entry -> DeviceRow(entry, names) { selected = entry.advert.id } }
            Hint(Res.string.ble_trackers_hint.str())
        }
    }
    ChoiceChips(BleFilter.entries, filter, { filter = it }, { it.title.str() })
    val shown = all.filter {
        when (filter) {
            BleFilter.ALL -> true
            BleFilter.TRACKERS -> it.kind.tracker
            BleFilter.NAMED -> it.advert.name != null
        }
    }
    Text(stringResource(Res.string.ble_count, shown.size.toString()), style = MaterialTheme.typography.bodyMedium)
    if (shown.isNotEmpty()) {
        ResultCard {
            shown.forEachIndexed { index, entry ->
                if (index > 0) SoftDivider()
                DeviceRow(entry, names) { selected = entry.advert.id }
            }
        }
    }
    Hint(Res.string.ble_distance_hint.str())
}

@Composable
private fun BleEntry.title(names: BluetoothNames?): String =
    advert.name ?: if (kind != BleKind.OTHER) kind.title.str() else advert.manufacturer.keys.firstNotNullOfOrNull { names?.company(it) } ?: Res.string.ble_unnamed.str()

@Composable
private fun DeviceRow(entry: BleEntry, names: BluetoothNames?, onClick: () -> Unit) {
    val rssi = entry.smoothed.toInt()
    val icon = when {
        entry.kind.tracker -> Icons.Filled.Warning
        entry.kind == BleKind.AIRPODS || entry.kind == BleKind.FAST_PAIR -> Icons.Filled.Headphones
        entry.kind == BleKind.IBEACON || entry.kind == BleKind.EDDYSTONE -> Icons.Filled.Sensors
        else -> Icons.Filled.BluetoothSearching
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (entry.kind.tracker) LocalStatusColors.current.warn else signalColor(rssi + 15), modifier = Modifier.size(26.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title(names), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val company = entry.advert.manufacturer.keys.firstNotNullOfOrNull { names?.company(it) }?.takeIf { entry.advert.name != null || entry.kind != BleKind.OTHER }
            Text(listOfNotNull(company, entry.advert.id).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val minutes = (entry.lastSeen - entry.firstSeen) / 60_000
            if (entry.kind.tracker && minutes >= 1) {
                Text(stringResource(Res.string.ble_seen_for, minutes.toString()), style = MaterialTheme.typography.bodySmall, color = LocalStatusColors.current.warn)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(rssi.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("≈ ${BleDecoder.distance(entry.advert, entry.smoothed).fmt(1)} ${Res.string.unit_m.str()}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Finder(entry: BleEntry, names: BluetoothNames?, onBack: () -> Unit) {
    val advert = entry.advert
    val rssi = entry.smoothed
    val age = (currentEpochMillis() - entry.lastSeen) / 1000
    TextButton(onClick = onBack) { Text(Res.string.ble_back.str()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Filled.LocationSearching, contentDescription = null)
        Text(entry.title(names), style = MaterialTheme.typography.titleLarge)
    }
    val recent = entry.samples.takeLast(5).average()
    val before = entry.samples.dropLast(5).takeLast(5).ifEmpty { null }?.average()
    val trend = when {
        before == null -> Res.string.ble_trend_wait
        recent - before > 3 -> Res.string.ble_trend_closer
        before - recent > 3 -> Res.string.ble_trend_farther
        else -> Res.string.ble_trend_same
    }
    AnswerCard("${rssi.toInt()} ${Res.string.unit_dbm.str()}", "${trend.str()} · ≈ ${BleDecoder.distance(advert, rssi).fmt(1)} ${Res.string.unit_m.str()}")
    if (age > 5) Hint(stringResource(Res.string.ble_lost, age.toString()))
    var sound by remember { mutableStateOf(true) }
    SwitchRow(Res.string.wifi_sound.str(), sound, { sound = it }, description = Res.string.ble_sound_hint.str())
    SignalBeep(sound && age <= 5, ((rssi + 95) / 60).toFloat())
    if (entry.samples.size > 1) {
        ResultCard {
            InteractiveChart(series = listOf(ChartSeries(entry.title(names), LocalStatusColors.current.info, entry.samples.map { it.toFloat() })), windows = false)
        }
    }
    ResultCard(Res.string.ble_details.str()) {
        KeyValueRow(Res.string.ble_id.str(), advert.id)
        if (entry.kind != BleKind.OTHER) KeyValueRow(Res.string.ble_type.str(), entry.kind.title.str(), mono = false, copyable = false)
        BleDecoder.findMy(advert)?.takeIf { it.second }?.let { Hint(Res.string.ble_separated.str()) }
        BleDecoder.iBeacon(advert)?.let {
            KeyValueRow("iBeacon UUID", it.uuid)
            KeyValueRow("Major / Minor", "${it.major} / ${it.minor}", copyable = false)
        }
        BleDecoder.eddystoneUrl(advert)?.let { KeyValueRow("Eddystone URL", it) }
        advert.txPower?.let { KeyValueRow(Res.string.ble_tx_power.str(), "$it ${Res.string.unit_dbm.str()}", mono = false, copyable = false) }
        advert.connectable?.let { KeyValueRow(Res.string.ble_connectable.str(), (if (it) Res.string.yes else Res.string.no).str(), mono = false, copyable = false) }
        advert.manufacturer.forEach { (company, data) ->
            KeyValueRow(names?.company(company) ?: ("0x" + company.toString(16).padStart(4, '0').uppercase()), BleDecoder.hex(data))
        }
        advert.services.forEach { uuid -> KeyValueRow(Res.string.ble_service.str(), listOfNotNull(uuid, names?.service(uuid)).joinToString(" · ")) }
        advert.serviceData.forEach { (uuid, data) -> KeyValueRow(listOfNotNull(uuid, names?.service(uuid)).joinToString(" · "), BleDecoder.hex(data)) }
    }
}
