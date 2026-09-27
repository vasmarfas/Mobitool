package com.vasmarfas.card.tools.money

import kotlin.test.Test
import kotlin.test.assertEquals

class TipSplitTest {
    @Test
    fun split() {
        val result = TipSplit.compute(100.0, 10.0, 4, roundUp = false)
        assertEquals(10.0, result.tip, 1e-9)
        assertEquals(110.0, result.total, 1e-9)
        assertEquals(27.5, result.perPerson, 1e-9)
        assertEquals(2.5, result.tipPerPerson, 1e-9)
    }

    @Test
    fun roundUp() {
        val result = TipSplit.compute(100.0, 10.0, 4, roundUp = true)
        assertEquals(28.0, result.perPerson, 1e-9)
        assertEquals(112.0, result.total, 1e-9)
        assertEquals(12.0, result.tip, 1e-9)
    }

    @Test
    fun exactSharesStayWhenRoundedUp() {
        assertEquals(110.0, TipSplit.compute(100.0, 10.0, 1, roundUp = true).perPerson, 1e-9)
        assertEquals(55.0, TipSplit.compute(100.0, 10.0, 2, roundUp = true).perPerson, 1e-9)
        assertEquals(1400.0, TipSplit.compute(2500.0, 12.0, 2, roundUp = true).perPerson, 1e-9)
        assertEquals(33_000_000.0, TipSplit.compute(30_000_000.0, 10.0, 1, roundUp = true).perPerson, 1e-9)
        assertEquals(34.0, TipSplit.compute(100.0, 0.0, 3, roundUp = true).perPerson, 1e-9)
        assertEquals(28.0, TipSplit.compute(54.01, 0.0, 2, roundUp = true).perPerson, 1e-9)
    }
}
