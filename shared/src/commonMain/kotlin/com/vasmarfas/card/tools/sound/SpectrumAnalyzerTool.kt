package com.vasmarfas.card.tools.sound

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.SpectrumFrame
import com.vasmarfas.card.core.ensurePermission
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.microphoneSpectrumFlow
import com.vasmarfas.card.core.microphoneSupported
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.network.SimpleTable
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChartKind
import com.vasmarfas.card.ui.components.ChartSeries
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.InteractiveChart
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource

val spectrumAnalyzerTool = Tool(
    id = "spectrum-analyzer",
    category = ToolCategory.SOUND,
    title = Res.string.spectrum_analyzer,
    description = Res.string.spectrum_analyzer_description,
    icon = Icons.Filled.Equalizer,
    keywords = listOf(
        "spectrum", "fft", "harmonics", "thd", "frequency", "analyser", "octave",
        "спектр", "гармоники", "анализатор", "частота", "фурье", "искажения",
    ),
    expandable = true,
) { SpectrumAnalyzerScreen() }

internal fun micErrorLabel(message: String): StringResource = when {
    message.contains("NotAllowed") || message.contains("Permission") -> Res.string.mic_refused
    message.contains("NotFound") || message.contains("Devices") -> Res.string.mic_not_found
    message.contains("NotReadable") || message.contains("TrackStart") -> Res.string.mic_busy
    else -> Res.string.mic_failed
}

private val fftSizes = listOf(1024, 2048, 4096, 8192)

private const val Bars = 96

private const val MinBarHz = 20.0

private const val FloorDb = -100f

private const val Smoothing = 0.25f

private fun barEdgeHz(index: Int, count: Int, sampleRate: Int): Double {
    val maxHz = (sampleRate / 2.0).coerceAtLeast(MinBarHz * 2)
    return MinBarHz * (maxHz / MinBarHz).pow(index.toDouble() / count)
}

private fun barCentreHz(index: Int, count: Int, sampleRate: Int): Double =
    sqrt(barEdgeHz(index, count, sampleRate) * barEdgeHz(index + 1, count, sampleRate))

// log spacing to match hearing, and the loudest bin per bar so narrow peaks are not averaged away
private fun logBars(frame: SpectrumFrame, count: Int): FloatArray {
    val magnitudes = frame.magnitudesDb
    return FloatArray(count) { index ->
        val from = barEdgeHz(index, count, frame.sampleRate)
        val to = barEdgeHz(index + 1, count, frame.sampleRate)
        val firstBin = (from / frame.binHz).toInt().coerceIn(0, magnitudes.lastIndex)
        val lastBin = (to / frame.binHz).toInt().coerceIn(firstBin, magnitudes.lastIndex)
        var loudest = magnitudes[firstBin]
        for (bin in firstBin..lastBin) if (magnitudes[bin] > loudest) loudest = magnitudes[bin]
        loudest
    }
}

private fun formatHz(hz: Double, units: SpectrumUnits): String =
    if (hz >= 1000) "${(hz / 1000).fmt(2)} ${units.khz}" else "${hz.fmt(1)} ${units.hz}"

private class SpectrumUnits(val hz: String, val khz: String, val db: String)

private class SpectrumView(val frame: SpectrumFrame, val bars: FloatArray, val reading: SpectrumReading?)

@Composable
private fun SpectrumAnalyzerScreen() {
    val units = SpectrumUnits(Res.string.unit_hz.str(), Res.string.unit_khz.str(), Res.string.unit_db.str())
    var fftSize by rememberSaveable { mutableStateOf(2048) }
    var running by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<SpectrumView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (!microphoneSupported()) {
        Text(Res.string.not_available_on_this_platform.str())
        return
    }

    LaunchedEffect(running, fftSize) {
        if (!running) return@LaunchedEffect
        error = null
        var smoothed: FloatArray? = null
        microphoneSpectrumFlow(fftSize)
            .filter { !frozen }
            .map { incoming ->
                val previous = smoothed
                val merged = if (previous != null && previous.size == incoming.magnitudesDb.size) {
                    FloatArray(previous.size) { i ->
                        previous[i] + (incoming.magnitudesDb[i] - previous[i]) * Smoothing
                    }
                } else {
                    incoming.magnitudesDb.copyOf()
                }
                smoothed = merged
                val frame = SpectrumFrame(merged, incoming.sampleRate)
                SpectrumView(frame, logBars(frame, Bars), SpectrumAnalysis.analyse(frame))
            }
            .flowOn(Dispatchers.Default)
            .catch { error = it.message ?: it.toString(); running = false }
            .collect { view = it }
    }

    Text(Res.string.fft_window.str(), style = MaterialTheme.typography.labelLarge)
    SegmentedChoice(
        options = fftSizes,
        selected = fftSize,
        onSelect = { fftSize = it },
        label = { it.toString() },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        Res.string.fft_window_hint.str(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ActionButton(
            text = if (running) Res.string.stop.str() else Res.string.start.str(),
            onClick = {
                if (running) {
                    running = false
                    frozen = false
                } else {
                    scope.launch { if (ensurePermission(AppPermission.MICROPHONE)) running = true else refused = true }
                }
            },
            modifier = Modifier.weight(1f),
        )
        if (running) {
            ActionButton(
                text = if (frozen) Res.string.resume_spectrum.str() else Res.string.freeze_spectrum.str(),
                onClick = { frozen = !frozen },
                icon = if (frozen) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (refused) {
        PermissionPrompt(AppPermission.MICROPHONE, Res.string.mic_access_needed.str()) {
            refused = false
            running = true
        }
    }
    error?.let { ErrorText(micErrorLabel(it).str()) }

    val shown = view ?: return
    val current = shown.frame
    val bars = shown.bars

    InteractiveChart(
        series = listOf(
            ChartSeries(
                label = Res.string.level.str(),
                color = MaterialTheme.colorScheme.primary,
                points = bars.map { (it - FloorDb).coerceAtLeast(0f) },
                tooltip = { index -> "${bars[index].toDouble().fmt(1)} ${units.db}" },
            ),
        ),
        kind = ChartKind.BAR,
        xLabel = { index -> formatHz(barCentreHz(index, Bars, current.sampleRate), units) },
        yFormat = { "${(it + FloorDb).toDouble().fmt(0)} ${units.db}" },
        windows = false,
    )

    val reading = shown.reading
    if (reading == null) {
        Text(
            Res.string.spectrum_too_quiet.str(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    ResultCard {
        KeyValueRow(Res.string.peak_frequency.str(), formatHz(reading.peakHz, units), copyable = false)
        SpectrumAnalysis.note(reading.peakHz)?.let { (name, cents) ->
            val sign = if (cents >= 0) "+" else ""
            KeyValueRow(Res.string.nearest_note.str(), "$name ${pluralStringResource(Res.plurals.cents_count, abs(cents), "$sign$cents")}", copyable = false)
        }
        KeyValueRow(Res.string.level.str(), "${reading.peakDb.toDouble().fmt(1)} ${units.db}", copyable = false)
        KeyValueRow(
            Res.string.resolution.str(),
            "${current.binHz.fmt(2)} ${Res.string.unit_hz.str()} · ${current.sampleRate} ${Res.string.unit_hz.str()}",
            copyable = false,
        )
        reading.thdPercent?.let {
            KeyValueRow(Res.string.thd.str(), "${it.fmt(2)} %", copyable = false)
        }
    }

    if (reading.harmonics.isNotEmpty()) {
        SimpleTable(
            header = listOf(
                Res.string.harmonic.str(),
                Res.string.frequency.str(),
                Res.string.level.str(),
                Res.string.relative.str(),
            ),
            rows = reading.harmonics.map {
                listOf(
                    "${it.order}×",
                    formatHz(it.frequencyHz, units),
                    "${it.levelDb.toDouble().fmt(1)} ${units.db}",
                    "${it.relativeDb.toDouble().fmt(1)} ${units.db}",
                )
            },
        )
    }
    Text(
        Res.string.spectrum_note.str(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
