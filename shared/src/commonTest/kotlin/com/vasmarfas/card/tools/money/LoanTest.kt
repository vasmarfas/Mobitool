package com.vasmarfas.card.tools.money

import kotlin.test.Test
import kotlin.test.assertEquals

class LoanTest {
    @Test
    fun annuity() {
        assertEquals(8884.88, Loan.annuityPayment(100000.0, 12.0, 12), 0.01)
        val result = Loan.schedule(100000.0, 12.0, 12, LoanType.ANNUITY)
        assertEquals(12, result.schedule.size)
        assertEquals(0.0, result.schedule.last().balance, 1e-6)
        assertEquals(100000.0, result.schedule.sumOf { it.principal }, 1e-6)
        assertEquals(6618.55, result.interest, 0.01)
    }

    @Test
    fun differentiated() {
        val result = Loan.schedule(120000.0, 12.0, 12, LoanType.DIFFERENTIATED)
        assertEquals(11200.0, result.schedule.first().payment, 1e-6)
        assertEquals(10100.0, result.schedule.last().payment, 1e-6)
        assertEquals(0.0, result.schedule.last().balance, 1e-6)
    }

    @Test
    fun zeroRate() {
        assertEquals(1000.0, Loan.annuityPayment(12000.0, 0.0, 12), 1e-9)
    }

    private fun oneOff(amount: Double, month: Int, mode: PrepayMode) = Prepayment(0.0, amount, month, mode)

    @Test
    fun prepaymentShortensTheTerm() {
        val plain = Loan.schedule(100000.0, 12.0, 12, LoanType.ANNUITY)
        val result = Loan.schedule(100000.0, 12.0, 12, LoanType.ANNUITY, oneOff(20000.0, 6, PrepayMode.SHORTEN_TERM))
        assertEquals(10, result.schedule.size)
        assertEquals(8884.88, result.schedule[8].payment, 0.01)
        assertEquals(5579.52, result.schedule.last().payment, 0.01)
        assertEquals(5543.43, result.interest, 0.01)
        assertEquals(100000.0, result.schedule.sumOf { it.principal + it.extra }, 1e-6)
        assertEquals(100000.0 + result.interest, result.total, 1e-6)
        assertEquals(plain.interest - result.interest, Loan.interestSaved(plain, result).last(), 1e-9)
    }

    @Test
    fun prepaymentLowersThePayment() {
        val result = Loan.schedule(100000.0, 12.0, 12, LoanType.ANNUITY, oneOff(20000.0, 6, PrepayMode.LOWER_PAYMENT))
        assertEquals(12, result.schedule.size)
        // the balance of 51492.11 less 20000 spread over the 6 months left
        assertEquals(51492.11, result.schedule[5].balance + 20000.0, 0.01)
        assertEquals(Loan.annuityPayment(31492.11, 12.0, 6), result.schedule[6].payment, 0.01)
        assertEquals(5433.91, result.schedule.last().payment, 0.01)
        assertEquals(5912.74, result.interest, 0.01)
    }

    @Test
    fun regularPrepayment() {
        val result = Loan.schedule(100000.0, 12.0, 12, LoanType.ANNUITY, Prepayment(1000.0, 0.0, 0, PrepayMode.SHORTEN_TERM))
        assertEquals(11, result.schedule.size)
        assertEquals(5963.74, result.interest, 0.01)
    }

    @Test
    fun differentiatedPrepayment() {
        val shorter = Loan.schedule(120000.0, 12.0, 12, LoanType.DIFFERENTIATED, oneOff(30000.0, 3, PrepayMode.SHORTEN_TERM))
        assertEquals(9, shorter.schedule.size)
        assertEquals(5400.0, shorter.interest, 0.01)
        val lower = Loan.schedule(120000.0, 12.0, 12, LoanType.DIFFERENTIATED, oneOff(30000.0, 3, PrepayMode.LOWER_PAYMENT))
        assertEquals(12, lower.schedule.size)
        assertEquals(6300.0, lower.interest, 0.01)
        assertEquals(6733.33, lower.schedule.last().payment, 0.01)
    }

    @Test
    fun prepaymentOverTheBalanceClosesTheLoan() {
        val result = Loan.schedule(100000.0, 12.0, 12, LoanType.ANNUITY, oneOff(1_000_000.0, 3, PrepayMode.LOWER_PAYMENT))
        assertEquals(3, result.schedule.size)
        assertEquals(0.0, result.schedule.last().balance, 1e-9)
        assertEquals(100000.0, result.schedule.sumOf { it.principal + it.extra }, 1e-6)
    }

    @Test
    fun savingsGrowOverTheSchedule() {
        val plain = Loan.schedule(100000.0, 12.0, 12, LoanType.ANNUITY)
        val result = Loan.schedule(100000.0, 12.0, 12, LoanType.ANNUITY, oneOff(20000.0, 6, PrepayMode.LOWER_PAYMENT))
        val saved = Loan.interestSaved(plain, result)
        assertEquals(0.0, saved[5], 1e-9)
        assertEquals(200.0, saved[6], 0.01)
        assertEquals(saved.sorted(), saved)
    }
}
