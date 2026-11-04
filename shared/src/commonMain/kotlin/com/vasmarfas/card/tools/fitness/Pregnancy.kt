package com.vasmarfas.card.tools.fitness

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// every input is brought to day 0 of the obstetric term, the due date is its 280th day
object Pregnancy {
    fun startFromPeriod(lastPeriod: LocalDate, cycleDays: Int): LocalDate = lastPeriod.plus(cycleDays - 28, DateTimeUnit.DAY)

    fun startFromConception(conception: LocalDate): LocalDate = conception.minus(14, DateTimeUnit.DAY)

    fun startFromUltrasound(scan: LocalDate, weeks: Int, days: Int): LocalDate = scan.minus(weeks * 7 + days, DateTimeUnit.DAY)

    fun atWeek(start: LocalDate, weeks: Int, days: Int = 0): LocalDate = start.plus(weeks * 7 + days, DateTimeUnit.DAY)

    fun trimester(ageDays: Int): Int = when {
        ageDays < 14 * 7 -> 1
        ageDays < 28 * 7 -> 2
        else -> 3
    }
}
