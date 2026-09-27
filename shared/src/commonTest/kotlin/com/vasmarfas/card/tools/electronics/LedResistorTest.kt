package com.vasmarfas.card.tools.electronics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LedResistorTest {
    @Test
    fun series() {
        assertEquals(150.0, LedResistor.nextInSeries(150.0, LedResistor.e12), 1e-9)
        assertEquals(180.0, LedResistor.nextInSeries(151.0, LedResistor.e12), 1e-9)
        assertEquals(160.0, LedResistor.nextInSeries(151.0, LedResistor.e24), 1e-9)
        assertEquals(1000.0, LedResistor.nextInSeries(950.0, LedResistor.e12), 1e-9)
        assertEquals(150.0, LedResistor.nearestInSeries(160.0, LedResistor.e12), 1e-9)
        assertEquals(150.0, LedResistor.previousInSeries(179.0, LedResistor.e12), 1e-9)
        assertEquals(180.0, LedResistor.previousInSeries(180.0, LedResistor.e12), 1e-9)
        assertEquals(82.0, LedResistor.previousInSeries(99.0, LedResistor.e12), 1e-9)
    }

    @Test
    fun compute() {
        val result = LedResistor.compute(5.0, 2.0, 20.0, 1)!!
        assertEquals(150.0, result.resistance, 1e-9)
        assertEquals(150.0, result.e12, 1e-9)
        assertEquals(20.0, result.currentE12Ma, 1e-9)
        assertEquals(0.06, result.power, 1e-9)
        assertEquals(0.125, result.ratingW, 1e-9)
        assertNull(LedResistor.compute(3.0, 2.0, 20.0, 2))
        assertNull(LedResistor.compute(3.6, 1.2, 20.0, 3))
    }

    @Test
    fun chainLength() {
        assertEquals(2, LedResistor.maxInSeries(5.0, 2.0))
        assertEquals(2, LedResistor.maxInSeries(6.0, 2.0))
        assertEquals(3, LedResistor.maxInSeries(12.0, 3.2))
        assertEquals(0, LedResistor.maxInSeries(1.5, 2.0))
        assertEquals(2, LedResistor.maxInSeries(3.6, 1.2))
    }
}
