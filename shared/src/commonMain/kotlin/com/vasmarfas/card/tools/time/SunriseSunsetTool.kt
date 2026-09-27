package com.vasmarfas.card.tools.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.AppPermission
import com.vasmarfas.card.core.ensurePermission
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.locationFlow
import com.vasmarfas.card.core.locationSupported
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.systemTimeZoneId
import com.vasmarfas.card.core.timeZoneOffsetSeconds
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.DateField
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.PermissionPrompt
import com.vasmarfas.card.ui.components.ResultCard
import kotlin.math.roundToInt
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.StringResource

private class City(val name: StringResource, val lat: Double, val lon: Double, val zone: String)

private const val LOCATE_TIMEOUT_MS = 20_000L

private val cities = listOf(
    City(Res.string.city_simferopol, 44.95, 34.10, "Europe/Simferopol"),
    City(Res.string.city_moscow, 55.7558, 37.6173, "Europe/Moscow"),
    City(Res.string.city_saint_petersburg, 59.9343, 30.3351, "Europe/Moscow"),
    City(Res.string.city_london, 51.5074, -0.1278, "Europe/London"),
    City(Res.string.city_new_york, 40.7128, -74.0060, "America/New_York"),
    City(Res.string.city_tokyo, 35.6762, 139.6503, "Asia/Tokyo"),
)

val sunriseSunsetTool = Tool(
    id = "sunrise-sunset",
    category = ToolCategory.TIME,
    title = Res.string.sunrise_and_sunset,
    description = Res.string.sunrise_sunset_description,
    icon = Icons.Filled.WbSunny,
    keywords = listOf("sunrise", "sunset", "twilight", "solar noon", "day length", "восход", "закат", "сумерки", "долгота дня"),
) { SunriseSunsetScreen() }

@Composable
private fun SunriseSunsetScreen() {
    var latText by rememberSaveable { mutableStateOf("44.95") }
    var lonText by rememberSaveable { mutableStateOf("34.10") }
    var dateText by rememberSaveable { mutableStateOf(today().iso()) }
    var zone by rememberSaveable { mutableStateOf(systemTimeZoneId()) }
    var typedOffset by rememberSaveable { mutableStateOf<String?>(null) }
    val date = parseDate(dateText)
    val zoneOffsetHours = remember(date, zone) {
        val d = date ?: today()
        val noonUtc = LocalDateTime(d, LocalTime(12, 0)).toInstant(TimeZone.UTC).epochSeconds
        (timeZoneOffsetSeconds(zone, noonUtc) ?: 0) / 3600.0
    }
    val offsetText = typedOffset ?: zoneOffsetHours.fmt(2)
    var locating by remember { mutableStateOf(false) }
    var locateError by remember { mutableStateOf<String?>(null) }
    var refused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val noFix = Res.string.location_no_fix.str()

    fun locate() {
        scope.launch {
            locating = true
            locateError = null
            if (!ensurePermission(AppPermission.LOCATION)) {
                refused = true
            } else {
                runCatching { withTimeout(LOCATE_TIMEOUT_MS) { locationFlow().first() } }
                    .onSuccess {
                        latText = it.latitude.fmt(4)
                        lonText = it.longitude.fmt(4)
                        zone = systemTimeZoneId()
                        typedOffset = null
                    }
                    .onFailure { locateError = if (it is TimeoutCancellationException) noFix else it.message ?: noFix }
            }
            locating = false
        }
    }

    ChoiceChips(
        options = cities,
        selected = cities.firstOrNull { it.lat.toString() == latText.trim() && it.lon.toString() == lonText.trim() },
        onSelect = {
            latText = it.lat.toString()
            lonText = it.lon.toString()
            zone = it.zone
            typedOffset = null
        },
        label = { it.name.str() },
    )
    if (locationSupported()) {
        ActionButton(
            text = if (locating) Res.string.waiting_for_a_fix.str() else Res.string.my_location.str(),
            icon = Icons.Filled.MyLocation,
            enabled = !locating,
            onClick = ::locate,
        )
    }
    locateError?.let { ErrorText(it) }
    if (refused) {
        PermissionPrompt(AppPermission.LOCATION, Res.string.gps_location_access_is_needed.str()) {
            refused = false
            locate()
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(latText, { latText = it }, Res.string.latitude.str(), Modifier.weight(1f), suffix = "°")
        NumberField(lonText, { lonText = it }, Res.string.longitude.str(), Modifier.weight(1f), suffix = "°")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        DateField(dateText, { dateText = it }, Res.string.date.str(), Modifier.weight(1f), isError = date == null)
        NumberField(offsetText, { typedOffset = it }, Res.string.utc_offset_h.str(), Modifier.weight(1f))
    }

    val lat = latText.toDoubleLenient()
    val lon = lonText.toDoubleLenient()
    val offsetHours = offsetText.toDoubleLenient()
    when {
        lat == null || lat < -90 || lat > 90 -> ErrorText(Res.string.sun_latitude_must_be_between.str())
        lon == null || lon < -180 || lon > 180 -> ErrorText(Res.string.sun_longitude_must_be_between.str())
        date == null -> ErrorText(Res.string.use_the_yyyy_mm_dd_format.str())
        offsetHours == null || offsetHours < -14 || offsetHours > 14 -> ErrorText(Res.string.sun_offset_must_be_between.str())
        else -> {
            val offset = (offsetHours * 3600).roundToInt()
            val t = remember(date, lat, lon) { SolarMath.compute(date, lat, lon) }
            fun time(minutes: Double?) = if (minutes == null) "—" else SolarMath.formatMinutes(minutes, offset)
            ResultCard {
                KeyValueRow(Res.string.sunrise.str(), time(t.sunrise))
                KeyValueRow(Res.string.sunset.str(), time(t.sunset))
                KeyValueRow(Res.string.solar_noon.str(), time(t.solarNoon))
                val length = t.dayLengthMinutes
                KeyValueRow(
                    Res.string.day_length.str(),
                    when {
                        length != null -> SolarMath.durationParts(length).let { (h, m) -> "$h ${Res.string.unit_h.str()} $m ${Res.string.unit_min.str()}" }
                        t.polarDay -> Res.string.polar_day.str()
                        t.polarNight -> Res.string.polar_night.str()
                        else -> "—"
                    },
                    mono = false,
                    copyable = false,
                )
            }
            ResultCard(Res.string.twilight.str()) {
                KeyValueRow(Res.string.civil_dawn_dusk.str(), "${time(t.civilDawn)} – ${time(t.civilDusk)}")
                KeyValueRow(Res.string.nautical.str(), "${time(t.nauticalDawn)} – ${time(t.nauticalDusk)}")
                KeyValueRow(Res.string.astronomical.str(), "${time(t.astronomicalDawn)} – ${time(t.astronomicalDusk)}")
            }
        }
    }
}
