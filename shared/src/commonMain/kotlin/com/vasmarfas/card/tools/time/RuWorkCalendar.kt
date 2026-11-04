package com.vasmarfas.card.tools.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber

private const val MAX_SPAN_YEARS = 1000

object RuWorkCalendar {
    private class Transfers(val daysOff: List<LocalDate>, val workingWeekends: List<LocalDate> = emptyList())

    // Labour Code, article 112, month to day
    private val holidays = listOf(
        1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 1 to 6, 1 to 7, 1 to 8,
        2 to 23, 3 to 8, 5 to 1, 5 to 9, 6 to 12, 11 to 4,
    )

    // government decrees 1335 of 2024-10-04, 1466 of 2025-09-24, 1187 of 2026-09-17,
    // plus the weekdays that take a weekend holiday under article 112
    private val transfers = mapOf(
        2025 to Transfers(
            listOf(LocalDate(2025, 5, 2), LocalDate(2025, 5, 8), LocalDate(2025, 6, 13), LocalDate(2025, 11, 3), LocalDate(2025, 12, 31)),
            listOf(LocalDate(2025, 11, 1)),
        ),
        2026 to Transfers(
            listOf(LocalDate(2026, 1, 9), LocalDate(2026, 3, 9), LocalDate(2026, 5, 11), LocalDate(2026, 12, 31)),
        ),
        2027 to Transfers(
            listOf(
                LocalDate(2027, 2, 22), LocalDate(2027, 5, 3), LocalDate(2027, 5, 10),
                LocalDate(2027, 6, 14), LocalDate(2027, 11, 5), LocalDate(2027, 12, 31),
            ),
            listOf(LocalDate(2027, 2, 20)),
        ),
    )

    val years: IntRange = transfers.keys.min()..transfers.keys.max()

    // from is counted, to is not, as in DateMath.workingDays; null for a span no calendar can speak for
    fun workingDays(from: LocalDate, to: LocalDate): Long? {
        if (to.year - from.year > MAX_SPAN_YEARS) return null
        var count = DateMath.workingDays(from, to)
        for (year in from.year..to.year) {
            val moved = transfers[year]
            val daysOff = holidays.map { (month, day) -> LocalDate(year, month, day) } + moved?.daysOff.orEmpty()
            count -= daysOff.count { it >= from && it < to && it.dayOfWeek.isoDayNumber <= 5 }
            count += moved?.workingWeekends.orEmpty().count { it >= from && it < to }
        }
        return count
    }
}
