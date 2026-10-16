package com.vasmarfas.card.tools.time

import com.vasmarfas.card.resources.*
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import org.jetbrains.compose.resources.StringResource

fun Int.pad2(): String = toString().padStart(2, '0')

fun formatStopwatch(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = ((totalSec % 3600) / 60).toInt()
    val s = (totalSec % 60).toInt()
    val millis = (ms % 1000).toInt().toString().padStart(3, '0')
    return if (h > 0) "$h:${m.pad2()}:${s.pad2()}.$millis" else "${m.pad2()}:${s.pad2()}.$millis"
}

fun formatCountdown(ms: Long): String {
    val totalSec = (ms + 999) / 1000
    val h = totalSec / 3600
    val m = ((totalSec % 3600) / 60).toInt()
    val s = (totalSec % 60).toInt()
    return if (h > 0) "$h:${m.pad2()}:${s.pad2()}" else "${m.pad2()}:${s.pad2()}"
}

fun formatHm(minutes: Int): String = "${minutes / 60}:${(minutes % 60).pad2()}"

fun LocalDate.iso(): String = "$year-${month.number.pad2()}-${day.pad2()}"

fun LocalDateTime.isoDateTime(): String = "${date.iso()} ${hour.pad2()}:${minute.pad2()}:${second.pad2()}"

fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()

fun parseHhMm(text: String): Int? {
    val parts = text.trim().split(':', '.')
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

val weekdayNames: List<StringResource> = listOf(
    Res.string.monday,
    Res.string.tuesday,
    Res.string.wednesday,
    Res.string.thursday,
    Res.string.friday,
    Res.string.saturday,
    Res.string.sunday,
)

val weekdayShortNames: List<StringResource> = listOf(
    Res.string.mon,
    Res.string.tue,
    Res.string.wed,
    Res.string.thu,
    Res.string.fri,
    Res.string.sat,
    Res.string.sun,
)

val monthNames: List<StringResource> = listOf(
    Res.string.january,
    Res.string.february,
    Res.string.march,
    Res.string.april,
    Res.string.may,
    Res.string.june,
    Res.string.july,
    Res.string.august,
    Res.string.september,
    Res.string.october,
    Res.string.november,
    Res.string.december,
)

val monthShortNames: List<StringResource> = listOf(
    Res.string.month_short_1, Res.string.month_short_2, Res.string.month_short_3, Res.string.month_short_4,
    Res.string.month_short_5, Res.string.month_short_6, Res.string.month_short_7, Res.string.month_short_8,
    Res.string.month_short_9, Res.string.month_short_10, Res.string.month_short_11, Res.string.month_short_12,
)

val monthNamesInDate: List<StringResource> = listOf(
    Res.string.january_of,
    Res.string.february_of,
    Res.string.march_of,
    Res.string.april_of,
    Res.string.may_of,
    Res.string.june_of,
    Res.string.july_of,
    Res.string.august_of,
    Res.string.september_of,
    Res.string.october_of,
    Res.string.november_of,
    Res.string.december_of,
)

fun DayOfWeek.title(): StringResource = weekdayNames[isoDayNumber - 1]

fun DayOfWeek.shortTitle(): StringResource = weekdayShortNames[isoDayNumber - 1]
