package com.vasmarfas.card.tools.money

import com.vasmarfas.card.resources.*
import kotlin.math.pow
import org.jetbrains.compose.resources.StringResource

enum class Compounding(val perYear: Int, val title: StringResource) {
    YEARLY(1, Res.string.yearly),
    QUARTERLY(4, Res.string.quarterly),
    MONTHLY(12, Res.string.monthly),
    DAILY(365, Res.string.daily),
}

class YearRow(val year: Int, val contributed: Double, val interest: Double, val balance: Double)

object CompoundInterest {
    fun grow(principal: Double, annualRate: Double, years: Int, compounding: Compounding, monthlyContribution: Double): List<YearRow> {
        val periods = compounding.perYear
        val monthsPerPeriod = (12 / periods).coerceAtLeast(1)
        val monthlyRate = if (periods > 12) (1 + annualRate / 100 / periods).pow(periods / 12.0) - 1 else annualRate / 100 / 12
        var balance = principal
        var contributed = principal
        var interest = 0.0
        var accrued = 0.0
        val rows = ArrayList<YearRow>(years)
        for (year in 1..years) {
            for (month in 1..12) {
                balance += monthlyContribution
                contributed += monthlyContribution
                accrued += balance * monthlyRate
                if (month % monthsPerPeriod == 0) {
                    balance += accrued
                    interest += accrued
                    accrued = 0.0
                }
            }
            rows.add(YearRow(year, contributed, interest, balance))
        }
        return rows
    }
}
