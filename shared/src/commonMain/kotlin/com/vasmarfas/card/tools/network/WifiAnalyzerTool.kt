package com.vasmarfas.card.tools.network

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkWifi1Bar
import androidx.compose.material.icons.filled.NetworkWifi2Bar
import androidx.compose.material.icons.filled.NetworkWifi3Bar
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalWifi0Bar
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.ScanLimit
import com.vasmarfas.card.core.WifiAccessPoint
import com.vasmarfas.card.core.WifiProblem
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.hasPermission
import com.vasmarfas.card.core.openScanLimitSettings
import com.vasmarfas.card.core.openAppSettings
import com.vasmarfas.card.core.scanWifi
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.wifiScanLimit
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val wifiAnalyzerTool = Tool(
    id = "wifi-analyzer",
    category = ToolCategory.NETWORK,
    title = Res.string.wifi_analyzer,
    description = Res.string.wifi_analyzer_description,
    icon = Icons.Filled.WifiFind,
    keywords = listOf(
        "wifi", "wi-fi", "wlan", "ssid", "bssid", "channel", "rssi", "signal", "heatmap", "survey", "access point", "interference",
        "wifiman", "netspot", "вайфай", "каналы", "сигнал", "тепловая карта", "карта покрытия", "точка доступа", "помехи",
    ),
) { WifiAnalyzerScreen() }

private enum class WifiTab(val title: StringResource) {
    NETWORKS(Res.string.wifi_tab_networks),
    CHANNELS(Res.string.wifi_tab_channels),
    SIGNAL(Res.string.wifi_tab_signal),
    MAP(Res.string.wifi_tab_map),
}

class WifiSnapshot(val time: Long, val signals: Map<String, Int>)

private const val STALE_MS = 30_000L
private const val HISTORY = 120

class WifiScanner {
    var points by mutableStateOf(emptyList<WifiAccessPoint>())
        private set
    var problem by mutableStateOf<WifiProblem?>(null)
        private set
    var throttled by mutableStateOf(false)
        private set
    var history by mutableStateOf(emptyList<WifiSnapshot>())
        private set
    private val seen = HashMap<String, Pair<WifiAccessPoint, Long>>()

    suspend fun scan() {
        val result = scanWifi()
        val now = currentEpochMillis()
        problem = result.problem
        throttled = !result.fresh && result.problem == null
        result.points.forEach { seen[it.bssid + it.ssid] = it to now }
        seen.values.removeAll { now - it.second > STALE_MS }
        points = seen.values.map { it.first }.sortedByDescending { it.rssi }
        if (result.fresh || history.isEmpty()) {
            history = (history + WifiSnapshot(now, result.points.associate { it.bssid to it.rssi })).takeLast(HISTORY)
        }
    }
}

@Composable
private fun WifiAnalyzerScreen() {
    val scans = currentPlatform == PlatformKind.ANDROID || currentPlatform == PlatformKind.DESKTOP
    val tabs = if (scans) WifiTab.entries else listOf(WifiTab.MAP)
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    var tracked by rememberSaveable { mutableStateOf<String?>(null) }
    var paused by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(0) }
    val scanner = remember { WifiScanner() }
    val mapOpen = tab == WifiTab.MAP

    LaunchedEffect(mapOpen, paused, granted) {
        if (!scans || mapOpen || paused) return@LaunchedEffect
        while (true) {
            scanner.scan()
            delay((if (scanner.throttled) 5_000 else 1_000).milliseconds)
        }
    }

    if (tabs.size > 1) SegmentedChoice(tabs, tab, { tab = it }, { it.title.str() })
    if (!mapOpen) ScanStatus(scanner, paused, { paused = it }) { granted++ }
    when (tab) {
        WifiTab.NETWORKS -> WifiNetworks(scanner.points) { tracked = it; tab = WifiTab.SIGNAL }
        WifiTab.CHANNELS -> WifiChannelsView(scanner.points)
        WifiTab.SIGNAL -> WifiSignal(scanner, tracked) { tracked = it }
        WifiTab.MAP -> WifiSurveyView(scans)
    }
}

@Composable
private fun ScanStatus(scanner: WifiScanner, paused: Boolean, onPause: (Boolean) -> Unit, onGranted: () -> Unit) {
    when (scanner.problem) {
        WifiProblem.NO_PERMISSION -> if (hasPermission(AppPermission.LOCATION)) {
            Text(Res.string.wifi_precise_location.str(), style = MaterialTheme.typography.bodyMedium)
            ActionButton(Res.string.open_settings.str(), onClick = { openAppSettings() }, icon = Icons.Filled.Settings)
        } else {
            PermissionPrompt(AppPermission.LOCATION, Res.string.wifi_location_needed.str(), onGranted)
        }
        WifiProblem.LOCATION_OFF -> ErrorText((if (currentPlatform == PlatformKind.DESKTOP) Res.string.wifi_location_off_windows else Res.string.wifi_location_off).str())
        WifiProblem.WIFI_OFF -> ErrorText(Res.string.wifi_off.str())
        else -> Unit
    }
    if (scanner.problem == WifiProblem.NO_PERMISSION) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        val status = when {
            paused -> Res.string.wifi_paused.str()
            scanner.points.isEmpty() -> Res.string.wifi_scanning.str()
            else -> stringResource(Res.string.wifi_count, scanner.points.map { it.ssid }.distinct().size.toString(), scanner.points.size.toString())
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { onPause(!paused) }) {
            Icon(
                if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                contentDescription = (if (paused) Res.string.wifi_resume else Res.string.wifi_pause).str(),
            )
        }
    }
    ScanLimitCard()
}

fun signalPercent(rssi: Int): Int = ((rssi + 100) * 2).coerceIn(0, 100)

fun signalQuality(rssi: Int): StringResource = when {
    rssi >= -50 -> Res.string.wifi_quality_excellent
    rssi >= -60 -> Res.string.wifi_quality_good
    rssi >= -70 -> Res.string.wifi_quality_fair
    rssi >= -80 -> Res.string.wifi_quality_weak
    else -> Res.string.wifi_quality_poor
}

@Composable
fun signalColor(rssi: Int): Color = LocalStatusColors.current.let {
    when {
        rssi >= -65 -> it.good
        rssi >= -75 -> it.warn
        else -> it.bad
    }
}

fun signalIcon(rssi: Int): ImageVector = when {
    rssi >= -55 -> Icons.Filled.SignalWifi4Bar
    rssi >= -65 -> Icons.Filled.NetworkWifi3Bar
    rssi >= -75 -> Icons.Filled.NetworkWifi2Bar
    rssi >= -85 -> Icons.Filled.NetworkWifi1Bar
    else -> Icons.Filled.SignalWifi0Bar
}

@Composable
fun WifiAccessPoint.title(): String = ssid.ifEmpty { Res.string.wifi_hidden.str() }

val wifiPalette = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00), Color(0xFF8E24AA), Color(0xFF00ACC1),
    Color(0xFFD81B60), Color(0xFF7CB342), Color(0xFF5E35B1), Color(0xFFFDD835), Color(0xFF6D4C41), Color(0xFF00897B),
)

fun wifiColor(key: String): Color = wifiPalette[(key.hashCode() and Int.MAX_VALUE) % wifiPalette.size]

@Composable
private fun ScanLimitCard() {
    var limit by remember { mutableStateOf(wifiScanLimit()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { limit = wifiScanLimit() }
    val hidden = limit == ScanLimit.HIDDEN
    if (limit != ScanLimit.ON && !hidden) return
    ResultCard(Res.string.wifi_limit_title.str()) {
        Text((if (hidden) Res.string.wifi_limit_hidden else Res.string.wifi_limit_on).str(), style = MaterialTheme.typography.bodyMedium)
        ActionButton(
            (if (hidden) Res.string.wifi_limit_open_info else Res.string.wifi_limit_open_developer).str(),
            onClick = { openScanLimitSettings() },
            icon = Icons.Filled.Settings,
        )
    }
}
