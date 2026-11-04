package com.vasmarfas.card.tools.money

import kotlin.test.Test
import kotlin.test.assertEquals

class CompoundInterestTest {
    @Test
    fun yearlyWithoutContributions() {
        val rows = CompoundInterest.grow(1000.0, 10.0, 2, Compounding.YEARLY, 0.0)
        assertEquals(2, rows.size)
        assertEquals(1100.0, rows[0].balance, 1e-9)
        assertEquals(1210.0, rows[1].balance, 1e-9)
        assertEquals(210.0, rows[1].interest, 1e-9)
    }

    @Test
    fun monthlyCompounding() {
        val rows = CompoundInterest.grow(1000.0, 10.0, 2, Compounding.MONTHLY, 0.0)
        assertEquals(1220.39, rows[1].balance, 0.01)
    }

    @Test
    fun contributionsAreCounted() {
        val rows = CompoundInterest.grow(0.0, 0.0, 1, Compounding.MONTHLY, 100.0)
        assertEquals(1200.0, rows[0].contributed, 1e-9)
        assertEquals(1200.0, rows[0].balance, 1e-9)
    }

    @Test
    fun rarerCompoundingNeverPaysMore() {
        assertEquals(1278.0, CompoundInterest.grow(0.0, 12.0, 1, Compounding.YEARLY, 100.0)[0].balance, 1e-9)
        assertEquals(1_142_792.40, CompoundInterest.grow(100_000.0, 8.0, 10, Compounding.MONTHLY, 5000.0).last().balance, 0.01)
        val balances = Compounding.entries.map { CompoundInterest.grow(100_000.0, 8.0, 10, it, 5000.0).last().balance }
        assertEquals(balances.sorted(), balances)
    }

    @Test
    fun depositTaxLimit() {
        assertEquals(210_000.0, DepositTax.limit(21.0), 1e-9)
        assertEquals(160_000.0, DepositTax.limit(16.0), 1e-9)
    }

    @Test
    fun depositTaxFollowsTheFnsExample() {
        assertEquals(7_800.0, DepositTax.ndfl(270_000.0, 210_000.0), 1e-6)
        assertEquals(0.0, DepositTax.ndfl(100_000.0, 210_000.0), 1e-9)
    }

    @Test
    fun depositTaxAboveTheThreshold() {
        assertEquals(312_000.0, DepositTax.ndfl(2_610_000.0, 210_000.0), 1e-6)
        assertEquals(402_000.0, DepositTax.ndfl(3_210_000.0, 210_000.0), 1e-6)
    }

    @Test
    fun depositTaxIsCountedPerYear() {
        val rows = CompoundInterest.grow(2_000_000.0, 12.0, 2, Compounding.YEARLY, 0.0)
        val tax = DepositTax.byYear(rows, 160_000.0)
        assertEquals(10_400.0, tax[0], 1e-6)
        assertEquals(14_144.0, tax[1], 1e-6)
    }
}
