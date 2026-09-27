package com.vasmarfas.card.tools.calculators

import kotlin.test.Test
import kotlin.test.assertEquals

class PercentageTest {
    @Test
    fun forms() {
        assertEquals(30.0, Percentage.percentOf(15.0, 200.0), 1e-9)
        assertEquals(25.0, Percentage.whatPercent(50.0, 200.0), 1e-9)
        assertEquals(-20.0, Percentage.change(50.0, 40.0), 1e-9)
        assertEquals(50.0, Percentage.change(-10.0, -5.0), 1e-9)
        assertEquals(-100.0, Percentage.change(-10.0, -20.0), 1e-9)
        assertEquals(120.0, Percentage.addPercent(100.0, 20.0), 1e-9)
        assertEquals(80.0, Percentage.subtractPercent(100.0, 20.0), 1e-9)
    }
}
