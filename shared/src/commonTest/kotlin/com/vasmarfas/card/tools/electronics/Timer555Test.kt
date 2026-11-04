package com.vasmarfas.card.tools.electronics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Timer555Test {
    // RA = 5 kΩ, RB = 3 kΩ, C = 0.15 µF is the circuit of figure 6-6 in the TI datasheet
    @Test
    fun astableMatchesTheDatasheetEquations() {
        val timing = Timer555.astable(5e3, 3e3, 0.15e-6)
        assertEquals(0.693 * 8e3 * 0.15e-6, timing.high, 1e-9)
        assertEquals(0.693 * 3e3 * 0.15e-6, timing.low, 1e-9)
        assertEquals(1.44 / (11e3 * 0.15e-6), timing.frequency, 2.0)
        assertEquals(8.0 / 11.0, timing.duty, 1e-9)
    }

    @Test
    fun monostablePulseIsOnePointOneRC() {
        assertEquals(100.1e-6, Timer555.pulse(9.1e3, 0.01e-6), 1e-9)
        assertEquals(275e-6, Timer555.pulse(1250.0, 0.2e-6), 1e-9)
    }

    @Test
    fun partsGiveBackTheRequestedTiming() {
        val parts = assertNotNull(Timer555.astableParts(1000.0, 0.6, 100e-9))
        val timing = Timer555.astable(parts.ra, parts.rb, 100e-9)
        assertEquals(1000.0, timing.frequency, 1e-6)
        assertEquals(0.6, timing.duty, 1e-9)
        assertEquals(1e-3, Timer555.pulse(Timer555.pulseResistor(1e-3, 1e-6), 1e-6), 1e-12)
    }

    @Test
    fun dutyOfHalfOrLessNeedsAnotherCircuit() {
        assertNull(Timer555.astableParts(1000.0, 0.5, 100e-9))
        assertNull(Timer555.astableParts(1000.0, 1.0, 100e-9))
    }
}
