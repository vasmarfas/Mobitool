package com.vasmarfas.card.tools.measure

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.LocationFix
import com.vasmarfas.card.core.PlatformKind
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.hasPermission
import com.vasmarfas.card.core.locationFlow
import com.vasmarfas.card.core.locationSupported
import com.vasmarfas.card.core.openUrl
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LoadingRow
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.catch

val locationTool = Tool(
    id = "gps-location",
    category = ToolCategory.MEASURE,
    title = Res.string.gps_and_speedometer,
    description = Res.string.gps_location_description,
    icon = Icons.Filled.GpsFixed,
    keywords = listOf("location", "coordinates", "speed", "altitude", "latitude", "longitude", "координаты", "скорость", "высота", "геолокация"),
    platforms = PlatformKind.mobileAndWeb,
) { LocationScreen() }

object Geo {
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180
        val dLon = (lon2 - lon1) * PI / 180
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * PI / 180) * cos(lat2 * PI / 180) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    fun dms(value: Double, positive: Char, negative: Char): String {
        val abs = kotlin.math.abs(value)
        val deg = abs.toInt()
        val minFull = (abs - deg) * 60
        val min = minFull.toInt()
        val sec = (minFull - min) * 60
        return "$deg°$min′${sec.fmt(2)}″${if (value >= 0) positive else negative}"
    }
}

@Composable
private fun LocationScreen() {
    var permission by remember { mutableStateOf(hasPermission(AppPermission.LOCATION)) }
    var fix by remember { mutableStateOf<LocationFix?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var maxSpeed by remember { mutableStateOf(0.0) }
    var distance by remember { mutableStateOf(0.0) }
    var last by remember { mutableStateOf<LocationFix?>(null) }
    var attempt by remember { mutableStateOf(0) }

    if (!locationSupported()) {
        Text(Res.string.not_available_on_this_platform.str())
        return
    }
    if (!permission) {
        PermissionPrompt(AppPermission.LOCATION, Res.string.gps_location_access_is_needed.str()) { permission = true }
        return
    }
    LaunchedEffect(permission, attempt) {
        error = null
        locationFlow().catch { error = it.message ?: it.toString() }.collect { f ->
            last?.let { prev ->
                val d = Geo.haversineMeters(prev.latitude, prev.longitude, f.latitude, f.longitude)
                if (d > (f.accuracy ?: 10.0) / 2 && d < 500) distance += d
            }
            last = f
            fix = f
            f.speed?.let { if (it * 3.6 > maxSpeed) maxSpeed = it * 3.6 }
        }
    }
    error?.let {
        ErrorText(it)
        TextButton(onClick = { attempt++ }) { Text(Res.string.try_again.str()) }
    }
    val f = fix
    if (f == null) {
        if (error == null) LoadingRow(Res.string.waiting_for_a_fix.str())
        return
    }
    val speedKmh = (f.speed ?: 0.0) * 3.6
    Text("${speedKmh.fmt(1)} ${Res.string.unit_kmh.str()}", style = MaterialTheme.typography.displayLarge)
    ResultCard {
        KeyValueRow(Res.string.latitude_longitude.str(), "${f.latitude.fmt(6)}, ${f.longitude.fmt(6)}")
        KeyValueRow("DMS", "${Geo.dms(f.latitude, 'N', 'S')} ${Geo.dms(f.longitude, 'E', 'W')}")
        KeyValueRow(Res.string.accuracy.str(), f.accuracy?.let { "±${it.fmt(0)} ${Res.string.unit_m.str()}" } ?: "—", copyable = false)
        KeyValueRow(Res.string.altitude.str(), f.altitude?.let { "${it.fmt(0)} ${Res.string.unit_m.str()}" } ?: "—", copyable = false)
        KeyValueRow(Res.string.bearing.str(), f.bearing?.let { "${it.fmt(0)}°" } ?: "—", copyable = false)
        KeyValueRow(Res.string.max_speed.str(), "${maxSpeed.fmt(1)} ${Res.string.unit_kmh.str()}", copyable = false)
        KeyValueRow(Res.string.gps_distance.str(), if (distance > 1000) "${(distance / 1000).fmt(2)} ${Res.string.unit_km.str()}" else "${distance.fmt(0)} ${Res.string.unit_m.str()}", copyable = false)
        KeyValueRow(Res.string.provider.str(), f.provider, mono = false, copyable = false)
        TextButton(onClick = { openUrl("https://www.openstreetmap.org/?mlat=${f.latitude}&mlon=${f.longitude}#map=16/${f.latitude}/${f.longitude}") }) {
            Text(Res.string.open_in_openstreetmap.str())
        }
        TextButton(onClick = { openUrl("geo:${f.latitude},${f.longitude}") }) { Text(Res.string.open_in_maps_app.str()) }
    }
    ActionButton(text = Res.string.reset.str(), onClick = { maxSpeed = 0.0; distance = 0.0 })
}
