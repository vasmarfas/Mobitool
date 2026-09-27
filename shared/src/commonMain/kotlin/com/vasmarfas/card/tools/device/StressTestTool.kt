package com.vasmarfas.card.tools.device

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.ThermalLevel
import com.vasmarfas.card.core.ThermalSource
import com.vasmarfas.card.core.cpuCoreCount
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.parallelWorkers
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.thermalSupported
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChartSeries
import com.vasmarfas.card.ui.components.InteractiveChart
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

val stressTestTool = Tool(
    id = "stress-test",
    category = ToolCategory.DEVICE,
    title = Res.string.stress_test,
    description = Res.string.stress_test_description,
    icon = Icons.Filled.Whatshot,
    keywords = listOf(
        "stress", "throttling", "temperature", "thermal", "load", "burn-in", "sustained",
        "стресс", "тест", "троттлинг", "температура", "нагрев", "нагрузка", "троттлинг процессора",
    ),
) { StressTestScreen() }

private val autoStopMinutes = listOf(0, 1, 5, 15, 30)

private fun levelLabel(level: ThermalLevel): StringResource = when (level) {
    ThermalLevel.NONE -> Res.string.thermal_none
    ThermalLevel.LIGHT -> Res.string.thermal_light
    ThermalLevel.MODERATE -> Res.string.thermal_moderate
    ThermalLevel.SEVERE -> Res.string.thermal_severe
    ThermalLevel.CRITICAL -> Res.string.thermal_critical
    ThermalLevel.UNKNOWN -> Res.string.thermal_unknown
}

private fun sourceLabel(source: ThermalSource): StringResource = when (source) {
    ThermalSource.BATTERY -> Res.string.thermal_source_battery
    ThermalSource.SENSOR -> Res.string.thermal_source_sensor
    ThermalSource.THROTTLING -> Res.string.thermal_source_throttling
}

@Composable
private fun StressTestScreen() {
    val cores = cpuCoreCount()
    val workerOptions = remember(cores) {
        listOf(1, 2, 4, 8, cores).filter { it in 1..cores }.distinct().sorted()
    }
    var workers by rememberSaveable { mutableStateOf(parallelWorkers) }
    var autoStop by rememberSaveable { mutableStateOf(5) }
    val samples = remember { mutableStateListOf<StressSample>() }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    val running = job?.isActive == true

    if (currentPlatform == PlatformKind.WEB) {
        Text(
            Res.string.stress_web_note.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        SegmentedChoice(
            options = workerOptions,
            selected = workers.coerceIn(1, cores),
            onSelect = { workers = it },
            label = { it.toString() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            Res.string.stress_threads_hint.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SegmentedChoice(
        options = autoStopMinutes,
        selected = autoStop,
        onSelect = { autoStop = it },
        label = {
            if (it == 0) Res.string.auto_stop_off.str() else stringResource(Res.string.minutes_short, it)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        Res.string.auto_stop_hint.str(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ActionButton(
        text = if (running) Res.string.stop.str() else Res.string.start.str(),
        onClick = {
            if (running) {
                job?.cancel()
                job = null
            } else {
                samples.clear()
                job = stressFlow(
                    workers = if (currentPlatform == PlatformKind.WEB) 1 else workers.coerceIn(1, cores),
                    autoStopSeconds = autoStop.takeIf { it > 0 }?.times(60),
                ).onEach { samples.add(it) }.launchIn(scope)
            }
        },
        icon = if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
        modifier = Modifier.fillMaxWidth(),
    )

    if (samples.isEmpty()) {
        if (!thermalSupported()) {
            Text(
                Res.string.stress_no_temperature.str(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val summary = StressSummary(samples.toList())
    val secondUnit = Res.string.unit_s.str()

    ToolSection(Res.string.compute_power.str()) {
        InteractiveChart(
            series = listOf(
                ChartSeries(
                    label = Res.string.unit_mwips.str(),
                    color = MaterialTheme.colorScheme.primary,
                    points = samples.map { it.mwips.toFloat() },
                ),
            ),
            xLabel = { elapsedLabel(samples.getOrNull(it)?.second ?: 0, secondUnit) },
        )
        KeyValueRow(Res.string.peak.str(), "${summary.peakMwips.fmt(1)} ${Res.string.unit_mwips.str()}", copyable = false)
        KeyValueRow(Res.string.average.str(), "${summary.averageMwips.fmt(1)} ${Res.string.unit_mwips.str()}", copyable = false)
        KeyValueRow(Res.string.current.str(), "${summary.lastMwips.fmt(1)} ${Res.string.unit_mwips.str()}", copyable = false)
        KeyValueRow(Res.string.sustained_share.str(), "${(summary.retention * 100).fmt(0)} %", copyable = false)
    }

    val temperatures = samples.map { it.reading?.celsius }
    if (temperatures.any { it != null }) {
        val source = samples.firstNotNullOfOrNull { it.reading?.source }
        ToolSection(Res.string.temperature.str()) {
            InteractiveChart(
                series = listOf(
                    ChartSeries(
                        label = Res.string.temperature.str(),
                        color = MaterialTheme.colorScheme.error,
                        points = temperatures.map { (it ?: 0.0).toFloat() },
                    ),
                ),
                xLabel = { elapsedLabel(samples.getOrNull(it)?.second ?: 0, secondUnit) },
                yFormat = { "${it.toDouble().fmt(1)} °C" },
            )
            summary.peakCelsius?.let {
                KeyValueRow(Res.string.peak.str(), "${it.fmt(1)} °C", copyable = false)
            }
            samples.firstNotNullOfOrNull { it.reading?.label }?.let {
                KeyValueRow(Res.string.sensor.str(), it, copyable = false)
            }
            source?.let { KeyValueRow(Res.string.source.str(), sourceLabel(it).str(), copyable = false) }
        }
    }

    if (summary.worstLevel != ThermalLevel.UNKNOWN) {
        ResultCard(title = Res.string.throttling.str()) {
            KeyValueRow(Res.string.worst_state.str(), levelLabel(summary.worstLevel).str(), copyable = false)
        }
    } else if (temperatures.all { it == null }) {
        Text(
            Res.string.stress_no_temperature.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun elapsedLabel(second: Int, unit: String): String {
    val minutes = second / 60
    val rest = second % 60
    return if (minutes == 0) "$rest $unit" else "$minutes:${rest.toString().padStart(2, '0')}"
}
