package com.vasmarfas.card.tools.time

import kotlinx.datetime.DateTimeArithmeticException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.periodUntil
import kotlinx.datetime.plus

enum class DateUnit { DAYS, WEEKS, MONTHS, YEARS }

data class DateDifference(val years: Int, val months: Int, val days: Int, val totalDays: Long, val workingDays: Long) {
    val weeks: Long get() = totalDays / 7
    val weekRemainderDays: Long get() = totalDays % 7
}

object DateMath {
    fun difference(from: LocalDate, to: LocalDate): DateDifference {
        val a = minOf(from, to)
        val b = maxOf(from, to)
        val p = a.periodUntil(b)
        return DateDifference(p.years, p.months, p.days, b.toEpochDays() - a.toEpochDays(), workingDays(a, b))
    }

    fun workingDays(from: LocalDate, to: LocalDate): Long {
        val total = to.toEpochDays() - from.toEpochDays()
        if (total <= 0) return 0
        val fullWeeks = total / 7
        var count = fullWeeks * 5
        var day = from.plus(fullWeeks * 7, DateTimeUnit.DAY)
        while (day < to) {
            if (day.dayOfWeek.isoDayNumber <= 5) count++
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return count
    }

    fun shift(date: LocalDate, amount: Int, unit: DateUnit, subtract: Boolean): LocalDate? {
        val n = if (subtract) -amount else amount
        return try {
            when (unit) {
                DateUnit.DAYS -> date.plus(n, DateTimeUnit.DAY)
                DateUnit.WEEKS -> date.plus(n, DateTimeUnit.WEEK)
                DateUnit.MONTHS -> date.plus(n, DateTimeUnit.MONTH)
                DateUnit.YEARS -> date.plus(n, DateTimeUnit.YEAR)
            }
        } catch (e: DateTimeArithmeticException) {
            null
        }
    }

    fun isoWeek(date: LocalDate): Pair<Int, Int> {
        val thursday = date.plus(4 - date.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)
        return thursday.year to (thursday.dayOfYear - 1) / 7 + 1
    }

    fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    fun quarter(date: LocalDate): Int = (date.month.number - 1) / 3 + 1

    fun daysLeftInYear(date: LocalDate): Int = date.daysUntil(LocalDate(date.year, 12, 31))

    fun daysInMonth(year: Int, month: Int): Int =
        LocalDate(year, month, 1).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY).day
}
