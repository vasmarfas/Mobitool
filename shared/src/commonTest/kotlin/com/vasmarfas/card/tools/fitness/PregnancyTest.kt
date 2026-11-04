package com.vasmarfas.card.tools.fitness

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class PregnancyTest {
    private fun due(start: LocalDate) = Pregnancy.atWeek(start, 40)

    @Test
    fun naegeleFromLastPeriod() {
        // minus 3 months, plus 7 days, next year
        assertEquals(LocalDate(2026, 10, 8), due(Pregnancy.startFromPeriod(LocalDate(2026, 1, 1), 28)))
    }

    @Test
    fun longerCycleMovesTheDateForward() {
        assertEquals(LocalDate(2026, 10, 15), due(Pregnancy.startFromPeriod(LocalDate(2026, 1, 1), 35)))
        assertEquals(LocalDate(2026, 10, 4), due(Pregnancy.startFromPeriod(LocalDate(2026, 1, 1), 24)))
    }

    @Test
    fun conceptionIs266DaysBeforeTheDueDate() {
        assertEquals(LocalDate(2026, 12, 6), due(Pregnancy.startFromConception(LocalDate(2026, 3, 15))))
    }

    @Test
    fun ultrasoundFixesTheTermOnItsDate() {
        val start = Pregnancy.startFromUltrasound(LocalDate(2026, 6, 1), 12, 3)
        assertEquals(LocalDate(2026, 12, 11), due(start))
        assertEquals(LocalDate(2026, 6, 1), Pregnancy.atWeek(start, 12, 3))
    }

    @Test
    fun trimesterBoundaries() {
        assertEquals(1, Pregnancy.trimester(13 * 7 + 6))
        assertEquals(2, Pregnancy.trimester(14 * 7))
        assertEquals(2, Pregnancy.trimester(27 * 7 + 6))
        assertEquals(3, Pregnancy.trimester(28 * 7))
    }
}
