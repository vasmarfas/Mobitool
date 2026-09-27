package com.vasmarfas.card.tools.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.LocalLang
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.systemTimeZoneId
import com.vasmarfas.card.core.timeZoneIds
import com.vasmarfas.card.core.timeZoneOffsetSeconds
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.DateField
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import com.vasmarfas.card.ui.components.monoFamily
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import org.jetbrains.compose.resources.StringResource

val worldClockTool = Tool(
    id = "world-clock",
    category = ToolCategory.TIME,
    title = Res.string.world_clock,
    description = Res.string.world_clock_description,
    icon = Icons.Filled.Public,
    keywords = listOf("time zone", "utc", "clock", "converter", "часовой пояс", "часы", "время", "конвертер"),
) { WorldClockScreen() }

@Composable
private fun WorldClockScreen() {
    val systemZone = remember { systemTimeZoneId() }
    val zones = remember {
        mutableStateListOf<String>().apply {
            addAll(WorldClock.decode(Prefs.store.get(WorldClock.PREF_KEY)) ?: (listOf(systemZone) + WorldClock.defaultZones).distinct())
        }
    }
    fun save() = Prefs.store.put(WorldClock.PREF_KEY, WorldClock.encode(zones))
    var now by remember { mutableStateOf(currentEpochMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            now = currentEpochMillis() / 1000
        }
    }
    val localOffset = timeZoneOffsetSeconds(systemZone, now) ?: 0
    val cities by produceState(emptyList<ClockCity>()) {
        value = runCatching { Cities.parse(Res.readBytes("files/cities.json").decodeToString()) }.getOrDefault(emptyList())
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        zones.forEach { entry ->
            ZoneRow(
                zone = WorldClock.zoneOf(entry),
                name = entryName(entry, cities),
                now = now,
                localOffset = localOffset,
                isLocal = entry == systemZone,
                onRemove = {
                    zones.remove(entry)
                    save()
                },
            )
        }
    }

    ToolSection(Res.string.add_a_zone.str()) {
        var query by rememberSaveable { mutableStateOf("") }
        val allIds = remember { timeZoneIds() }
        ToolInputField(
            value = query,
            onValueChange = { query = it },
            label = Res.string.search.str(),
            placeholder = Res.string.world_clock_search_hint.str(),
        )
        val q = query.trim()
        val matches = remember(q, zones.size, cities) {
            if (q.length < 2) {
                emptyList()
            } else {
                val found = Cities.search(cities, q)
                val byCity = found.map { "${it.zone}|${it.en}" }
                val names = found.map { it.en }.toSet()
                val byZone = allIds.filter { it.contains(q, ignoreCase = true) && it.substringAfterLast('/').replace('_', ' ') !in names }
                (byCity + byZone).filter { it !in zones }.take(24)
            }
        }
        if (matches.isNotEmpty()) {
            ChoiceChips(
                options = matches,
                selected = null,
                onSelect = {
                    zones.add(it)
                    save()
                    query = ""
                },
                label = { entryName(it, cities) },
            )
        } else if (q.length >= 2) {
            Text(Res.string.nothing_found.str(), style = MaterialTheme.typography.bodyMedium)
        }
    }

    ToolSection(Res.string.converter.str()) {
        var fromZone by rememberSaveable { mutableStateOf(zones.firstOrNull() ?: "UTC") }
        var dateText by rememberSaveable { mutableStateOf(today().iso()) }
        var timeText by rememberSaveable { mutableStateOf("12:00") }
        DropdownChoice(
            options = zones.toList(),
            selected = fromZone,
            onSelect = { fromZone = it },
            label = Res.string.zone.str(),
            text = { entryName(it, cities) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DateField(dateText, { dateText = it }, Res.string.date.str(), Modifier.weight(1f), isError = parseDate(dateText) == null)
            ToolInputField(
                value = timeText,
                onValueChange = { timeText = it },
                label = Res.string.time.str(),
                modifier = Modifier.weight(1f),
                placeholder = "HH:MM",
                isError = parseHhMm(timeText) == null,
                monospace = true,
            )
        }
        val date = parseDate(dateText)
        val minutes = parseHhMm(timeText)
        if (date == null || minutes == null) {
            ErrorText(Res.string.enter_a_date_as_yyyy_mm_dd_and_a_time_as_hh.str())
        } else {
            val epoch = Timestamps.epochSecondsOf(LocalDateTime(date, LocalTime(minutes / 60, minutes % 60))) {
                timeZoneOffsetSeconds(WorldClock.zoneOf(fromZone), it) ?: 0
            }
            ResultCard {
                zones.forEach { entry ->
                    val offset = timeZoneOffsetSeconds(WorldClock.zoneOf(entry), epoch)
                    val text = if (offset == null) "—" else {
                        val t = WorldClock.wallTime(epoch, offset)
                        "${t.date.iso()} ${t.hour.pad2()}:${t.minute.pad2()} (${t.date.dayOfWeek.shortTitle().str()})"
                    }
                    KeyValueRow(entryName(entry, cities), text)
                }
            }
        }
    }
}

@Composable
private fun ZoneRow(zone: String, name: String, now: Long, localOffset: Int, isLocal: Boolean, onRemove: () -> Unit) {
    val offset = timeZoneOffsetSeconds(zone, now)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name + if (isLocal) " · " + Res.string.world_clock_local.str() else "",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(zone, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (offset != null) {
                    Text(
                        text = "UTC${Timestamps.formatOffset(offset)} · ${WorldClock.formatRelative(offset - localOffset)} ${Res.string.unit_h.str()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (offset == null) {
                ErrorText(Res.string.unknown_zone.str())
            } else {
                val t = WorldClock.wallTime(now, offset)
                Icon(
                    imageVector = if (WorldClock.isDaytime(t.hour)) Icons.Filled.WbSunny else Icons.Filled.Bedtime,
                    contentDescription = null,
                    tint = if (WorldClock.isDaytime(t.hour)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${t.hour.pad2()}:${t.minute.pad2()}:${t.second.pad2()}",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = monoFamily()),
                    )
                    Text(
                        text = "${t.date.dayOfWeek.shortTitle().str()}, ${t.date.day} ${monthNamesInDate[t.date.month.number - 1].str()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = Res.string.remove.str())
            }
        }
    }
}

private val zoneNames: Map<String, StringResource> = mapOf(
    "Europe/Moscow" to Res.string.city_moscow,
    "Europe/London" to Res.string.city_london,
    "America/New_York" to Res.string.city_new_york,
    "Asia/Tokyo" to Res.string.city_tokyo,
    "Asia/Dubai" to Res.string.city_dubai,
    "Asia/Shanghai" to Res.string.city_shanghai,
    "Europe/Paris" to Res.string.city_paris,
    "Europe/Berlin" to Res.string.city_berlin,
    "Europe/Rome" to Res.string.city_rome,
    "Europe/Madrid" to Res.string.city_madrid,
    "Europe/Istanbul" to Res.string.city_istanbul,
    "Europe/Minsk" to Res.string.city_minsk,
    "Europe/Kyiv" to Res.string.city_kyiv,
    "Europe/Kaliningrad" to Res.string.city_kaliningrad,
    "Europe/Samara" to Res.string.city_samara,
    "Europe/Simferopol" to Res.string.city_simferopol,
    "Asia/Yekaterinburg" to Res.string.city_yekaterinburg,
    "Asia/Omsk" to Res.string.city_omsk,
    "Asia/Novosibirsk" to Res.string.city_novosibirsk,
    "Asia/Krasnoyarsk" to Res.string.city_krasnoyarsk,
    "Asia/Irkutsk" to Res.string.city_irkutsk,
    "Asia/Yakutsk" to Res.string.city_yakutsk,
    "Asia/Vladivostok" to Res.string.city_vladivostok,
    "Asia/Magadan" to Res.string.city_magadan,
    "Asia/Kamchatka" to Res.string.city_kamchatka,
    "Asia/Almaty" to Res.string.city_almaty,
    "Asia/Tashkent" to Res.string.city_tashkent,
    "Asia/Tbilisi" to Res.string.city_tbilisi,
    "Asia/Yerevan" to Res.string.city_yerevan,
    "Asia/Baku" to Res.string.city_baku,
    "Asia/Kolkata" to Res.string.city_kolkata,
    "Asia/Bangkok" to Res.string.city_bangkok,
    "Asia/Singapore" to Res.string.city_singapore,
    "Asia/Hong_Kong" to Res.string.city_hong_kong,
    "Asia/Seoul" to Res.string.city_seoul,
    "America/Los_Angeles" to Res.string.city_los_angeles,
    "America/Chicago" to Res.string.city_chicago,
    "America/Toronto" to Res.string.city_toronto,
    "America/Sao_Paulo" to Res.string.city_sao_paulo,
    "Australia/Sydney" to Res.string.city_sydney,
)

@Composable
private fun zoneName(zone: String): String = zoneNames[zone]?.str() ?: WorldClock.shortName(zone)

@Composable
private fun entryName(entry: String, cities: List<ClockCity>): String {
    val city = WorldClock.cityOf(entry)?.let { name -> cities.firstOrNull { it.en == name } }
    return city?.name(LocalLang.current) ?: zoneName(WorldClock.zoneOf(entry))
}
