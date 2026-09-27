package com.vasmarfas.card.tools.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.systemTimeZoneId
import com.vasmarfas.card.core.timeZoneOffsetSeconds
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.number
import org.jetbrains.compose.resources.stringResource

private enum class ZoneChoice { LOCAL, UTC }

private fun localOffset(epochSeconds: Long): Int = timeZoneOffsetSeconds(systemTimeZoneId(), epochSeconds) ?: 0

val unixTimestampTool = Tool(
    id = "unix-timestamp",
    category = ToolCategory.TIME,
    title = Res.string.unix_timestamp,
    description = Res.string.unix_timestamp_description,
    icon = Icons.Filled.Schedule,
    keywords = listOf("epoch", "unix", "timestamp", "utc", "iso 8601", "время", "эпоха", "дата"),
) { UnixTimestampScreen() }

@Composable
private fun UnixTimestampScreen() {
    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            now = currentEpochMillis()
        }
    }
    val zoneId = remember { systemTimeZoneId() }
    val nowOffset = remember(now / 60_000) { localOffset(now / 1000) }
    ResultCard(Res.string.now.str()) {
        KeyValueRow(Res.string.seconds_field.str(), (now / 1000).toString())
        KeyValueRow(Res.string.milliseconds.str(), now.toString())
        KeyValueRow(Res.string.local_time.str(), Timestamps.wallTime(now, nowOffset).isoDateTime())
        KeyValueRow(Res.string.local_zone.str(), "$zoneId (UTC${Timestamps.formatOffset(nowOffset)})", copyable = false)
    }

    ToolSection(Res.string.timestamp_date.str()) {
        var tsText by rememberSaveable { mutableStateOf("") }
        ToolInputField(
            value = tsText,
            onValueChange = { tsText = it },
            label = Res.string.timestamp.str(),
            placeholder = "1700000000 · 1700000000000",
            keyboardType = KeyboardType.Number,
            isError = tsText.isNotBlank() && tsText.trim().toLongOrNull() == null,
            monospace = true,
        )
        val raw = tsText.trim().toLongOrNull()
        if (tsText.isNotBlank() && raw == null) {
            ErrorText(Res.string.enter_an_integer_number.str())
        }
        if (raw != null) {
            val unit = Timestamps.detectUnit(raw)
            val ms = Timestamps.toEpochMillis(raw, unit)
            val offset = localOffset(ms.floorDiv(1000L))
            val local = Timestamps.wallTime(ms, offset)
            val utc = Timestamps.wallTime(ms, 0)
            ResultCard {
                KeyValueRow(Res.string.detected_unit.str(), unit.label, copyable = false)
                KeyValueRow(Res.string.seconds_field.str(), ms.floorDiv(1000L).toString())
                KeyValueRow(Res.string.milliseconds.str(), ms.toString())
                KeyValueRow(Res.string.local.str(), "${local.isoDateTime()} (${local.date.dayOfWeek.shortTitle().str()})")
                KeyValueRow("UTC", "${utc.isoDateTime()} (${utc.date.dayOfWeek.shortTitle().str()})")
                KeyValueRow("ISO 8601 UTC", Timestamps.iso8601(ms, 0))
                KeyValueRow(Res.string.iso_8601_local.str(), Timestamps.iso8601(ms, offset))
                val diff = (ms - now) / 1000
                KeyValueRow(
                    Res.string.relative_to_now.str(),
                    relativeText(diff),
                    mono = false,
                    copyable = false,
                )
            }
        }
    }

    ToolSection(Res.string.date_timestamp.str()) {
        val initial = remember { Timestamps.wallTime(currentEpochMillis(), localOffset(currentEpochMillis() / 1000)) }
        var year by rememberSaveable { mutableStateOf(initial.year.toString()) }
        var month by rememberSaveable { mutableStateOf(initial.month.number.toString()) }
        var day by rememberSaveable { mutableStateOf(initial.day.toString()) }
        var hour by rememberSaveable { mutableStateOf(initial.hour.toString()) }
        var minute by rememberSaveable { mutableStateOf(initial.minute.toString()) }
        var second by rememberSaveable { mutableStateOf("0") }
        var zone by rememberSaveable { mutableStateOf(ZoneChoice.LOCAL) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(year, { year = it }, Res.string.year.str(), Modifier.weight(1f))
            NumberField(month, { month = it }, Res.string.month.str(), Modifier.weight(1f))
            NumberField(day, { day = it }, Res.string.unix_day.str(), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(hour, { hour = it }, Res.string.hour.str(), Modifier.weight(1f))
            NumberField(minute, { minute = it }, Res.string.minute.str(), Modifier.weight(1f))
            NumberField(second, { second = it }, Res.string.second.str(), Modifier.weight(1f))
        }
        SegmentedChoice(
            options = ZoneChoice.entries,
            selected = zone,
            onSelect = { zone = it },
            label = { if (it == ZoneChoice.LOCAL) Res.string.local_time.str() else "UTC" },
        )
        val local = runCatching {
            LocalDateTime(year.trim().toInt(), month.trim().toInt(), day.trim().toInt(), hour.trim().toInt(), minute.trim().toInt(), second.trim().toInt())
        }.getOrNull()
        if (local == null) {
            ErrorText(Res.string.invalid_date_or_time.str())
        } else {
            val epoch = if (zone == ZoneChoice.UTC) Timestamps.epochSecondsOf(local) { 0 } else Timestamps.epochSecondsOf(local) { localOffset(it) }
            ResultCard {
                KeyValueRow(Res.string.seconds_field.str(), epoch.toString())
                KeyValueRow(Res.string.milliseconds.str(), (epoch * 1000).toString())
                KeyValueRow("ISO 8601", Timestamps.iso8601(epoch * 1000, if (zone == ZoneChoice.UTC) 0 else localOffset(epoch)))
            }
        }
    }
}

@Composable
private fun relativeText(diffSeconds: Long): String {
    val a = if (diffSeconds < 0) -diffSeconds else diffSeconds
    val amount = when {
        a < 60 -> "$a ${Res.string.unit_s.str()}"
        a < 3600 -> "${a / 60} ${Res.string.unit_min.str()}"
        a < 86_400 -> "${a / 3600} ${Res.string.unit_h.str()}"
        else -> "${a / 86_400} ${Res.string.d.str()}"
    }
    return stringResource(if (diffSeconds < 0) Res.string.time_ago else Res.string.time_in, amount)
}
