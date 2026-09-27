package com.vasmarfas.card.tools.measure

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.SensorReading
import com.vasmarfas.card.core.SensorType
import com.vasmarfas.card.core.currentPlatform
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.hasPermission
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChartKind
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.theme.LocalStatusColors
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import org.jetbrains.compose.resources.StringResource

val accelerometerTool = Tool(
    id = "accelerometer",
    category = ToolCategory.MEASURE,
    title = Res.string.accelerometer_and_vibration,
    description = Res.string.accelerometer_description,
    icon = Icons.Filled.Vibration,
    keywords = listOf("g-force", "vibration", "seismograph", "shake", "вибрация", "сейсмограф", "ускорение"),
    platforms = PlatformKind.mobileAndWeb,
    expandable = true,
) { AccelerometerScreen() }

@Composable
private fun AccelerometerScreen() {
    val history = remember { mutableStateListOf<SensorReading>() }
    val session = rememberSensor(SensorType.ACCELEROMETER, history)
    var peak by remember { mutableStateOf(0f) }
    SensorGate(session) { reading ->
        val magnitude = sqrt(reading.x * reading.x + reading.y * reading.y + reading.z * reading.z)
        val dynamic = kotlin.math.abs(magnitude - 9.80665f)
        if (dynamic > peak) peak = dynamic
        LineChart(
            series = listOf(history.map { it.x }, history.map { it.y }, history.map { it.z }),
            colors = LocalStatusColors.current.series,
        )
        ResultCard {
            val ms2 = Res.string.unit_mps2.str()
            KeyValueRow("X / Y / Z", "${reading.x.toDouble().fmt(2)} / ${reading.y.toDouble().fmt(2)} / ${reading.z.toDouble().fmt(2)} $ms2", copyable = false)
            KeyValueRow(Res.string.magnitude.str(), "${magnitude.toDouble().fmt(2)} $ms2 (${(magnitude / 9.80665f).toDouble().fmt(2)} g)", copyable = false)
            KeyValueRow(Res.string.vibration_deviation_from_1_g.str(), "${dynamic.toDouble().fmt(3)} $ms2", copyable = false)
            KeyValueRow(Res.string.peak.str(), "${peak.toDouble().fmt(3)} $ms2", copyable = false)
            val rms = history.takeLast(50).map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) - 9.80665f }.let { if (it.isEmpty()) 0.0 else sqrt(it.sumOf { v -> (v * v).toDouble() } / it.size) }
            KeyValueRow("RMS (2.5 ${Res.string.unit_s.str()})", "${rms.fmt(3)} $ms2", copyable = false)
        }
        ActionButton(text = Res.string.reset.str(), onClick = { peak = 0f; history.clear() })
    }
}

val magnetometerTool = Tool(
    id = "magnetometer",
    category = ToolCategory.MEASURE,
    title = Res.string.magnetometer_and_metal_detector,
    description = Res.string.magnetometer_description,
    icon = Icons.Filled.Sensors,
    keywords = listOf("magnetic", "metal detector", "tesla", "emf", "металл", "магнит", "поле"),
    platforms = PlatformKind.mobileAndWeb,
    expandable = true,
) { MagnetometerScreen() }

@Composable
private fun MagnetometerScreen() {
    val history = remember { mutableStateListOf<SensorReading>() }
    val session = rememberSensor(SensorType.MAGNETOMETER, history)
    var baseline by remember { mutableStateOf<Float?>(null) }
    SensorGate(session) { reading ->
        val magnitude = sqrt(reading.x * reading.x + reading.y * reading.y + reading.z * reading.z)
        if (baseline == null && history.size > 10) baseline = history.takeLast(10).map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }.average().toFloat()
        val delta = baseline?.let { magnitude - it }
        val microtesla = Res.string.unit_microtesla.str()
        LineChart(
            series = listOf(history.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }),
            colors = listOf(MaterialTheme.colorScheme.primary),
            symmetric = false,
        )
        Text(
            "${magnitude.toDouble().fmt(1)} $microtesla",
            style = MaterialTheme.typography.displayMedium,
            color = if (delta != null && delta > 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        ResultCard {
            KeyValueRow("X / Y / Z", "${reading.x.toDouble().fmt(1)} / ${reading.y.toDouble().fmt(1)} / ${reading.z.toDouble().fmt(1)} $microtesla", copyable = false)
            KeyValueRow(Res.string.baseline.str(), baseline?.let { "${it.toDouble().fmt(1)} $microtesla" } ?: "…", copyable = false)
            KeyValueRow(Res.string.deviation.str(), delta?.let { "${it.toDouble().fmt(1)} $microtesla" } ?: "…", copyable = false)
            Text(
                Res.string.motion_earth_s_field.str(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ActionButton(text = Res.string.set_baseline.str(), onClick = { baseline = magnitude })
    }
}

val lightMeterTool = Tool(
    id = "light-meter",
    category = ToolCategory.MEASURE,
    title = Res.string.light_meter,
    description = Res.string.light_meter_description,
    icon = Icons.Filled.WbSunny,
    keywords = listOf("lux", "illuminance", "brightness", "ev", "люкс", "освещённость", "яркость"),
    platforms = PlatformKind.mobileAndWeb,
    expandable = true,
) { LightMeterScreen() }

@Composable
private fun LightMeterScreen() {
    val history = remember { mutableStateListOf<SensorReading>() }
    val session = rememberSensor(SensorType.LIGHT, history)
    SensorGate(session) { reading ->
        val lux = reading.x.toDouble()
        val lx = Res.string.unit_lux.str()
        Text("${lux.fmt(0)} $lx", style = MaterialTheme.typography.displayLarge)
        LineChart(
            series = listOf(history.map { it.x }),
            colors = listOf(MaterialTheme.colorScheme.primary),
            symmetric = false,
            kind = ChartKind.AREA,
        )
        val ev = if (lux > 0) (ln(lux / 2.5) / ln(2.0)) else Double.NaN
        ResultCard {
            KeyValueRow("EV (ISO 100)", if (ev.isNaN()) "—" else ev.fmt(1), copyable = false)
            KeyValueRow(Res.string.reference.str(), lightReference(lux).str(), mono = false, copyable = false)
            val recent = history.takeLast(40).map { it.x.toDouble() }
            if (recent.isNotEmpty()) KeyValueRow(Res.string.min_avg_max.str(), "${recent.min().fmt(0)} / ${recent.average().fmt(0)} / ${recent.max().fmt(0)} $lx", copyable = false)
        }
    }
}

private fun lightReference(lux: Double): StringResource = when {
    lux < 1 -> Res.string.moonless_night
    lux < 20 -> Res.string.twilight_dim_hallway
    lux < 100 -> Res.string.living_room_lighting
    lux < 300 -> Res.string.office_minimum_300_lx_recommended
    lux < 500 -> Res.string.good_office_reading_light
    lux < 1000 -> Res.string.motion_bright_workshop_overcast
    lux < 10_000 -> Res.string.daylight_shade
    lux < 50_000 -> Res.string.overcast_to_bright_daylight
    else -> Res.string.direct_sunlight
}

val barometerTool = Tool(
    id = "barometer",
    category = ToolCategory.MEASURE,
    title = Res.string.barometer_and_altimeter,
    description = Res.string.barometer_description,
    icon = Icons.Filled.Compress,
    keywords = listOf("pressure", "altitude", "hpa", "mmhg", "weather", "давление", "высота", "погода"),
    platforms = PlatformKind.mobile,
    expandable = true,
) { BarometerScreen() }

@Composable
private fun BarometerScreen() {
    val history = remember { mutableStateListOf<SensorReading>() }
    val session = rememberSensor(SensorType.PRESSURE, history, historySize = 600)
    var seaLevel by rememberSaveable { mutableStateOf("1013.25") }
    SensorGate(session) { reading ->
        val hpa = reading.x.toDouble()
        val p0 = seaLevel.replace(',', '.').toDoubleOrNull() ?: 1013.25
        val altitude = 44330.0 * (1 - (hpa / p0).pow(1 / 5.255))
        val hectopascal = Res.string.unit_hpa.str()
        Text("${hpa.fmt(1)} $hectopascal", style = MaterialTheme.typography.displayMedium)
        LineChart(
            series = listOf(history.map { it.x }),
            colors = listOf(MaterialTheme.colorScheme.primary),
            symmetric = false,
            kind = ChartKind.AREA,
        )
        NumberField(value = seaLevel, onValueChange = { seaLevel = it }, label = Res.string.sea_level_pressure_qnh_hpa.str())
        ResultCard {
            KeyValueRow(Res.string.unit_mmhg.str(), (hpa * 0.750062).fmt(1), copyable = false)
            KeyValueRow(Res.string.unit_inhg.str(), (hpa * 0.02953).fmt(2), copyable = false)
            KeyValueRow(Res.string.barometric_altitude.str(), "${altitude.fmt(0)} ${Res.string.unit_m.str()}", copyable = false)
            val first = history.firstOrNull()?.x?.toDouble()
            if (first != null) KeyValueRow(Res.string.change_since_start.str(), "${(hpa - first).fmt(2)} $hectopascal", copyable = false)
        }
    }
}

val pedometerTool = Tool(
    id = "pedometer",
    category = ToolCategory.MEASURE,
    title = Res.string.pedometer,
    description = Res.string.pedometer_description,
    icon = Icons.AutoMirrored.Filled.DirectionsWalk,
    keywords = listOf("steps", "walking", "distance", "calories", "шаги", "ходьба", "расстояние"),
    platforms = PlatformKind.mobile,
) { PedometerScreen() }

@Composable
private fun PedometerScreen() {
    var permission by remember { mutableStateOf(hasPermission(AppPermission.ACTIVITY_RECOGNITION)) }
    var stride by rememberSaveable { mutableStateOf("75") }
    var weight by rememberSaveable { mutableStateOf("75") }
    if (!permission) {
        PermissionPrompt(AppPermission.ACTIVITY_RECOGNITION, Res.string.motion_step_counting_needs.str()) { permission = true }
        return
    }
    val session = rememberSensor(SensorType.STEP_COUNTER)
    val sinceOpen = currentPlatform == PlatformKind.IOS
    var start by remember { mutableStateOf(if (sinceOpen) 0f else null) }
    SensorGate(session) { reading ->
        if (start == null) start = reading.x
        val steps = (reading.x - (start ?: reading.x)).toInt()
        val strideM = (stride.toDoubleOrNull() ?: 75.0) / 100
        val kg = weight.toDoubleOrNull() ?: 75.0
        Text("$steps", style = MaterialTheme.typography.displayLarge)
        ResultCard {
            KeyValueRow(Res.string.distance.str(), "${(steps * strideM / 1000).fmt(2)} ${Res.string.unit_km.str()}", copyable = false)
            KeyValueRow(Res.string.calories.str(), "${(steps * strideM / 1000 * kg * 0.9).fmt(0)} ${Res.string.unit_kcal.str()}", copyable = false)
            if (!sinceOpen) KeyValueRow(Res.string.total_since_reboot.str(), reading.x.toInt().toString(), copyable = false)
        }
        NumberField(value = stride, onValueChange = { stride = it }, label = Res.string.stride_length_cm.str())
        NumberField(value = weight, onValueChange = { weight = it }, label = Res.string.weight_kg.str())
        ActionButton(text = Res.string.reset.str(), onClick = { start = reading.x })
    }
}
