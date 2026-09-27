package com.vasmarfas.card.tools.measure

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vasmarfas.card.core.SensorReading
import com.vasmarfas.card.core.SensorType
import com.vasmarfas.card.core.availableSensors
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.requestMotionAccess
import com.vasmarfas.card.core.sensorFlow
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChartKind
import com.vasmarfas.card.ui.components.ChartSeries
import com.vasmarfas.card.ui.components.EmptyState
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.InteractiveChart
import com.vasmarfas.card.ui.components.LoadingRow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private val axisNames = listOf("X", "Y", "Z")

class SensorSession(val reading: SensorReading?, val error: String?, val supported: Boolean, val denied: Boolean, val onGranted: () -> Unit)

@Composable
fun rememberSensor(type: SensorType, history: MutableList<SensorReading>? = null, historySize: Int = 200): SensorSession {
    val supported = remember { type in availableSensors() }
    var reading by remember { mutableStateOf<SensorReading?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var access by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(type, supported) {
        if (supported) access = requestMotionAccess()
    }
    LaunchedEffect(type, access) {
        if (access != true) return@LaunchedEffect
        sensorFlow(type)
            .catch { error = it.message ?: it.toString() }
            .collect { r ->
                reading = r
                history?.let {
                    it.add(r)
                    if (it.size > historySize) it.removeAt(0)
                }
            }
    }
    return SensorSession(reading, error, supported, access == false) { access = true }
}

// desktop browsers announce motion events and never send one, so a sensor silent for three seconds
// is shown as missing
@Composable
fun SensorGate(session: SensorSession, content: @Composable (SensorReading) -> Unit) {
    var silent by remember { mutableStateOf(false) }
    LaunchedEffect(session.reading == null, session.denied) {
        silent = false
        if (session.reading == null) {
            delay(3.seconds)
            silent = true
        }
    }
    when {
        !session.supported -> EmptyState(Icons.Filled.SensorsOff, Res.string.sensor_missing_title.str(), description = Res.string.sensor_missing_description.str())
        session.denied -> MotionPermissionButton(session.onGranted)
        session.error != null -> ErrorText(session.error)
        session.reading == null && silent -> EmptyState(Icons.Filled.SensorsOff, Res.string.sensor_silent_title.str(), description = Res.string.sensor_silent_description.str())
        session.reading == null -> LoadingRow(Res.string.waiting_for_sensor_data.str())
        else -> content(session.reading)
    }
}

// Safari grants motion access only from a tap, the request goes out before the first suspension
@Composable
fun MotionPermissionButton(onGranted: () -> Unit) {
    val scope = rememberCoroutineScope()
    Text(Res.string.sensor_browser_needs_permission.str(), style = MaterialTheme.typography.bodyMedium)
    ActionButton(
        text = Res.string.grant_permission.str(),
        onClick = { scope.launch(start = CoroutineStart.UNDISPATCHED) { if (requestMotionAccess()) onGranted() } },
    )
}

@Composable
fun LineChart(
    series: List<List<Float>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    symmetric: Boolean = true,
    kind: ChartKind = ChartKind.LINE,
) {
    val sampleText = Res.string.sensor_sample.str()
    val fallback = MaterialTheme.colorScheme.primary
    val single = Res.string.value_.str()
    InteractiveChart(
        series = series.mapIndexed { index, values ->
            ChartSeries(
                label = if (series.size > 1) axisNames.getOrElse(index) { (index + 1).toString() } else single,
                color = colors.getOrElse(index) { fallback },
                points = values,
            )
        },
        modifier = modifier,
        kind = kind,
        symmetric = symmetric,
        xLabel = { "${sampleText} $it" },
        yFormat = { it.toDouble().fmt(2) },
    )
}
