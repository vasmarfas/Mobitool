package com.vasmarfas.card.tools.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth

class SalaryTest {
    @Test
    fun progressiveScale() {
        assertEquals(312_000.0, Ndfl.tax(2_400_000.0))
        assertEquals(702_000.0, Ndfl.tax(5_000_000.0))
        assertEquals(3_402_000.0, Ndfl.tax(20_000_000.0))
        assertEquals(9_402_000.0, Ndfl.tax(50_000_000.0))
        assertEquals(11_602_000.0, Ndfl.tax(60_000_000.0))
        assertEquals(13, Ndfl.rate(2_400_000.0))
        assertEquals(15, Ndfl.rate(2_400_001.0))
        assertEquals(22, Ndfl.rate(60_000_000.0))
    }

    @Test
    fun taxIsRoundedToWholeRubles() {
        assertEquals(13_001.0, Ndfl.tax(100_003.85))
        assertEquals(13_000.0, Ndfl.tax(100_003.0))
        assertEquals(14_000.0, Ndfl.taxOn(100_000.0, 2_350_000.0))
    }

    @Test
    fun flatYearBelowFirstThreshold() {
        val year = Salary.year(100_000.0)
        assertTrue(year.months.all { it.tax == 13_000.0 && it.net == 87_000.0 })
        assertEquals(1, year.rateSpans.size)
        assertEquals(1_044_000.0, year.net)
    }

    @Test
    fun rateGrowsInTheMonthIncomePassesTheThreshold() {
        val year = Salary.year(289_412.0)
        assertEquals(listOf(13, 15), year.rateSpans.map { it.first().rate })
        assertEquals(9, year.rateSpans[1].first().month)
        assertEquals(37_624.0, year.months[0].tax)
        assertEquals(37_623.0, year.months[1].tax)
        assertEquals(41_718.0, year.months[8].tax)
        assertEquals(43_412.0, year.months[11].tax)
        assertEquals(472_942.0, year.tax)
    }

    @Test
    fun largeSalaryStartsAboveThirteenPercent() {
        val year = Salary.year(30_000_000.0)
        assertEquals(listOf(20, 22), year.rateSpans.map { it.first().rate })
        assertEquals(5_402_000.0, year.months[0].tax)
        assertEquals(6_200_000.0, year.months[1].tax)
    }

    @Test
    fun grossFromNet() {
        assertEquals(100_000.0, Salary.grossFor(87_000.0))
        assertEquals(289_412.0, Salary.grossFor(250_000.0))
        assertEquals(0.0, Salary.grossFor(0.0))
        listOf(50_000.0, 150_000.0, 500_000.0, 3_000_000.0).forEach { net ->
            val average = Salary.year(Salary.grossFor(net)).net / 12
            assertTrue(average >= net && average - net < 1, "$net gives $average")
        }
    }

    @Test
    fun vacationPeriodIsTheTwelvePreviousMonths() {
        val period = VacationPay.period(LocalDate(2026, 10, 16))
        assertEquals(12, period.size)
        assertEquals(YearMonth(2025, 10), period.first())
        assertEquals(YearMonth(2026, 9), period.last())
        val days = VacationPay.countedDays(period, List(12) { 0 })
        assertEquals(351.6, days, 1e-9)
        assertEquals(3_412.97, VacationPay.averageDaily(1_200_000.0, days))
    }

    @Test
    fun vacationWithIncompleteMonths() {
        val period = VacationPay.period(LocalDate(2026, 1, 12))
        val excluded = period.map { if (it == YearMonth(2025, 6)) 16 else if (it == YearMonth(2025, 7)) 13 else 0 }
        val days = VacationPay.countedDays(period, excluded)
        assertEquals(29.3 * 10 + 29.3 / 30 * 14 + 29.3 / 31 * 18, days, 1e-9)
        assertEquals(3_089.41, VacationPay.averageDaily(1_000_000.0, days))
    }

    @Test
    fun sickPayByInsuranceRecord() {
        val start = LocalDate(2026, 3, 10)
        val full = SickLeave.calculate(start, 7, listOf(1_200_000.0, 1_500_000.0), InsuranceRecord.FROM_8)!!
        assertEquals(3_698.63, full.averageDaily)
        assertEquals(25_890.41, full.total)
        assertEquals(11_095.89, full.employer)
        assertEquals(14_794.52, full.fund, 1e-6)
        assertFalse(full.fromMinimumWage)
        val short = SickLeave.calculate(start, 7, listOf(1_200_000.0, 1_500_000.0), InsuranceRecord.UNDER_5)!!
        assertEquals(2_219.18, short.daily)
    }

    @Test
    fun sickPayIsCappedByContributionBase() {
        val pay = SickLeave.calculate(LocalDate(2026, 5, 4), 10, listOf(3_000_000.0, 3_000_000.0), InsuranceRecord.FROM_8)!!
        assertEquals(listOf(2_225_000.0, 2_759_000.0), pay.counted)
        assertEquals(6_827.40, pay.averageDaily)
    }

    @Test
    fun sickPayFromMinimumWage() {
        val pay = SickLeave.calculate(LocalDate(2026, 2, 2), 5, listOf(0.0, 0.0), InsuranceRecord.UNDER_5)!!
        assertEquals(890.73, pay.averageDaily)
        assertEquals(4_838.04, pay.total)
        assertTrue(pay.fromMinimumWage)
        val spanning = SickLeave.calculate(LocalDate(2026, 1, 30), 4, listOf(0.0, 0.0), InsuranceRecord.UNDER_5)!!
        assertEquals(3_683.15, spanning.total)
        assertEquals(2_715.54, spanning.employer)
    }

    @Test
    fun sickPayOutsideKnownYears() {
        assertNull(SickLeave.calculate(LocalDate(2027, 1, 10), 5, listOf(1.0, 1.0), InsuranceRecord.FROM_8))
        assertNull(SickLeave.calculate(LocalDate(2023, 6, 1), 5, listOf(1.0, 1.0), InsuranceRecord.FROM_8))
        assertEquals(2024..2026, SickLeave.years)
    }
}
