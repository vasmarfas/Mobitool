package com.vasmarfas.card.tools.electronics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcbTraceTest {
    @Test
    fun areaFollowsThePublishedWorkedExample() {
        assertEquals(42.4, PcbTrace.areaMil2(2.0, 10.0, external = true), 0.1)
    }

    @Test
    fun anInnerLayerNeedsAboutTwoPointSixTimesTheArea() {
        val ratio = PcbTrace.areaMil2(1.0, 10.0, external = false) / PcbTrace.areaMil2(1.0, 10.0, external = true)
        assertEquals(2.6, ratio, 0.01)
    }

    @Test
    fun oneAmpOnOneOunceCopperIsAboutThirtyHundredthsOfAMillimetre() {
        assertEquals(0.30, PcbTrace.areaMm2(1.0, 10.0, external = true) / 0.035, 0.01)
    }

    @Test
    fun resistanceIsCopperWarmedByTheRise() {
        assertEquals(0.017241 * (1 + 0.00393 * 15), PcbTrace.ohms(1.0, 1000.0, 10.0), 1e-9)
    }

    @Test
    fun chartLimitsDependOnTheLayer() {
        assertTrue(PcbTrace.withinCharts(30.0, 20.0, external = true, widthMm = 5.0))
        assertFalse(PcbTrace.withinCharts(30.0, 20.0, external = false, widthMm = 5.0))
        assertFalse(PcbTrace.withinCharts(1.0, 5.0, external = true, widthMm = 5.0))
        assertFalse(PcbTrace.withinCharts(1.0, 20.0, external = true, widthMm = 11.0))
    }
}
