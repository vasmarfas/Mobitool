package com.vasmarfas.card.tools.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuWorkCalendarTest {
    private fun monthly(year: Int): List<Long?> = (1..12).map { month ->
        val first = LocalDate(year, month, 1)
        RuWorkCalendar.workingDays(first, first.plus(1, DateTimeUnit.MONTH))
    }

    @Test
    fun publishedMonthlyNorms() {
        assertEquals(listOf<Long?>(17, 20, 21, 22, 18, 19, 23, 21, 22, 23, 19, 22), monthly(2025))
        assertEquals(listOf<Long?>(15, 19, 21, 22, 19, 21, 23, 21, 22, 22, 20, 22), monthly(2026))
        assertEquals(listOf<Long?>(15, 19, 22, 22, 19, 21, 22, 22, 22, 21, 20, 22), monthly(2027))
    }

    @Test
    fun wholeYearsAre247Days() {
        for (year in RuWorkCalendar.years) {
            assertEquals(247L, RuWorkCalendar.workingDays(LocalDate(year, 1, 1), LocalDate(year + 1, 1, 1)))
        }
    }

    @Test
    fun movedDays() {
        assertEquals(1L, RuWorkCalendar.workingDays(LocalDate(2025, 11, 1), LocalDate(2025, 11, 2)))
        assertEquals(0L, RuWorkCalendar.workingDays(LocalDate(2025, 11, 3), LocalDate(2025, 11, 4)))
        assertEquals(0L, RuWorkCalendar.workingDays(LocalDate(2026, 1, 9), LocalDate(2026, 1, 10)))
        assertEquals(1L, RuWorkCalendar.workingDays(LocalDate(2027, 2, 20), LocalDate(2027, 2, 21)))
        assertEquals(0L, RuWorkCalendar.workingDays(LocalDate(2027, 2, 22), LocalDate(2027, 2, 23)))
    }

    @Test
    fun theEndDateIsNotCounted() {
        assertEquals(0L, RuWorkCalendar.workingDays(LocalDate(2026, 3, 10), LocalDate(2026, 3, 10)))
        assertEquals(1L, RuWorkCalendar.workingDays(LocalDate(2026, 3, 10), LocalDate(2026, 3, 11)))
    }

    @Test
    fun yearsWithoutACalendarKeepOnlyTheHolidays() {
        assertEquals(16L, RuWorkCalendar.workingDays(LocalDate(2028, 1, 1), LocalDate(2028, 2, 1)))
    }

    @Test
    fun farSpansAreNotCounted() {
        assertNull(RuWorkCalendar.workingDays(LocalDate(2026, 9, 27), LocalDate.parse("+10000000-01-01")))
    }
}
