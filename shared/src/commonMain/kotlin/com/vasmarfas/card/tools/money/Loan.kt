package com.vasmarfas.card.tools.money

import kotlin.math.pow

enum class LoanType { ANNUITY, DIFFERENTIATED }
enum class PrepayMode { SHORTEN_TERM, LOWER_PAYMENT }
data class Prepayment(val monthly: Double, val oneOff: Double, val oneOffMonth: Int, val mode: PrepayMode) {
    fun on(month: Int): Double = monthly + if (month == oneOffMonth) oneOff else 0.0
}
class LoanPayment(val month: Int, val payment: Double, val principal: Double, val interest: Double, val balance: Double, val extra: Double = 0.0)
class LoanResult(val schedule: List<LoanPayment>) {
    val total: Double = schedule.sumOf { it.payment + it.extra }
    val interest: Double = schedule.sumOf { it.interest }
}
object Loan {
    fun annuityPayment(principal: Double, annualRate: Double, months: Int): Double {
        val r = annualRate / 100 / 12
        if (r == 0.0) return principal / months
        return principal * r / (1 - (1 + r).pow(-months))
    }
    fun schedule(principal: Double, annualRate: Double, months: Int, type: LoanType, prepayment: Prepayment? = null): LoanResult {
        val r = annualRate / 100 / 12
        var annuity = annuityPayment(principal, annualRate, months)
        var principalPart = principal / months
        var balance = principal
        val payments = ArrayList<LoanPayment>(months)
        for (month in 1..months) {
            val interest = balance * r
            val principalPaid = when {
                month == months -> balance
                type == LoanType.ANNUITY -> minOf(annuity - interest, balance)
                else -> minOf(principalPart, balance)
            }
            balance -= principalPaid
            val extra = minOf(prepayment?.on(month) ?: 0.0, balance)
            balance -= extra
            payments.add(LoanPayment(month, principalPaid + interest, principalPaid, interest, balance.coerceAtLeast(0.0), extra))
            if (balance < 0.005) break
            if (extra > 0 && prepayment?.mode == PrepayMode.LOWER_PAYMENT) {
                annuity = annuityPayment(balance, annualRate, months - month)
                principalPart = balance / (months - month)
            }
        }
        return LoanResult(payments)
    }

    // running difference from the plain schedule, the last row takes in the months that are no longer paid
    fun interestSaved(plain: LoanResult, actual: LoanResult): List<Double> {
        var saved = 0.0
        return actual.schedule.mapIndexed { i, row ->
            saved += plain.schedule[i].interest - row.interest
            if (i == actual.schedule.lastIndex) plain.interest - actual.interest else saved
        }
    }
}