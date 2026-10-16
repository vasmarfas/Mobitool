package com.vasmarfas.card.tools.money

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.yearMonth

class TaxBracket(val from: Double, val rate: Int)

object Ndfl {
    val brackets = listOf(
        TaxBracket(0.0, 13),
        TaxBracket(2_400_000.0, 15),
        TaxBracket(5_000_000.0, 18),
        TaxBracket(20_000_000.0, 20),
        TaxBracket(50_000_000.0, 22),
    )

    fun exact(income: Double): Double = brackets.indices.sumOf { i ->
        val bracket = brackets[i]
        val to = brackets.getOrNull(i + 1)?.from ?: Double.MAX_VALUE
        if (income > bracket.from) (minOf(income, to) - bracket.from) * bracket.rate / 100 else 0.0
    }

    // whole rubles, 50 kopecks round up
    fun tax(income: Double): Double = floor(exact(income) + 0.5)

    fun rate(income: Double): Int = (brackets.lastOrNull { income > it.from } ?: brackets.first()).rate

    fun taxOn(payment: Double, earlierIncome: Double): Double = tax(earlierIncome + payment) - tax(earlierIncome)
}

class SalaryMonth(val month: Int, val gross: Double, val tax: Double, val rate: Int) {
    val net: Double get() = gross - tax
}

class SalaryYear(val months: List<SalaryMonth>) {
    val gross: Double = months.sumOf { it.gross }
    val tax: Double = months.sumOf { it.tax }
    val net: Double = gross - tax

    val rateSpans: List<List<SalaryMonth>> = months.groupBy { it.rate }.values.toList()
}

object Salary {
    fun year(monthlyGross: Double): SalaryYear {
        var withheld = 0.0
        return SalaryYear(
            (1..12).map { month ->
                val due = Ndfl.tax(monthlyGross * month)
                SalaryMonth(month, monthlyGross, due - withheld, Ndfl.rate(monthlyGross * month)).also { withheld = due }
            },
        )
    }

    fun grossFor(monthlyNet: Double): Double {
        val net = monthlyNet * 12
        val bracket = Ndfl.brackets.last { it.from - Ndfl.exact(it.from) <= net }
        val annual = bracket.from + (net - bracket.from + Ndfl.exact(bracket.from)) / (1 - bracket.rate / 100.0)
        return ceil(kopecks(annual / 12))
    }
}

object VacationPay {
    const val MONTH_DAYS = 29.3

    fun period(start: LocalDate): List<YearMonth> = (12 downTo 1).map { start.yearMonth.minus(it, DateTimeUnit.MONTH) }

    fun countedDays(period: List<YearMonth>, excluded: List<Int>): Double = period.zip(excluded).sumOf { (month, off) ->
        if (off == 0) MONTH_DAYS else MONTH_DAYS / month.numberOfDays * (month.numberOfDays - off)
    }

    fun averageDaily(earnings: Double, countedDays: Double): Double = kopecks(earnings / countedDays)
}

enum class InsuranceRecord(val percent: Int) { UNDER_5(60), FROM_5_TO_8(80), FROM_8(100) }

class SickPay(val counted: List<Double>, val averageDaily: Double, val fromMinimumWage: Boolean, perDay: List<Double>) {
    val daily: Double = kopecks(perDay.first())
    val total: Double = kopecks(perDay.sum())
    val employer: Double = kopecks(perDay.take(SickLeave.EMPLOYER_DAYS).sum())
    val fund: Double = total - employer
}

object SickLeave {
    const val EMPLOYER_DAYS = 3
    private const val DIVISOR = 730

    val contributionCaps = mapOf(2022 to 1_032_000.0, 2023 to 1_917_000.0, 2024 to 2_225_000.0, 2025 to 2_759_000.0)
    val minimumWage = mapOf(2024 to 19_242.0, 2025 to 22_440.0, 2026 to 27_093.0)
    val years: IntRange = minimumWage.keys.min()..minimumWage.keys.max()

    fun calculate(start: LocalDate, days: Int, earnings: List<Double>, record: InsuranceRecord): SickPay? {
        val wage = minimumWage[start.year] ?: return null
        val counted = earnings.mapIndexed { i, sum -> minOf(sum, contributionCaps.getValue(start.year - 2 + i)) }
        val belowWage = counted.sum() < wage * 24
        val average = kopecks((if (belowWage) wage * 24 else counted.sum()) / DIVISOR)
        val daily = kopecks(average * record.percent / 100)
        val perDay = (0 until days).map { start.plus(it, DateTimeUnit.DAY).yearMonth.numberOfDays }
            .map { monthDays -> if (daily * monthDays < wage) wage / monthDays else daily }
        return SickPay(counted, average, belowWage || perDay.any { it != daily }, perDay)
    }
}

private fun kopecks(value: Double): Double = (value * 100).roundToLong() / 100.0
