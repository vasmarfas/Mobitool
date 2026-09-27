package com.vasmarfas.card.tools.printing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalibrationTest {
    @Test
    fun layerAdviceKeepsNozzleLimits() {
        val advice = LayerSettings.advice(0.4, 0.2, 20.0)
        assertEquals(0.1, advice.minLayer, 1e-9)
        assertEquals(0.3, advice.maxLayer, 1e-9)
        assertEquals(0.48, advice.recommendedWidth, 1e-9)
        assertEquals(0.3, advice.firstLayerHeight, 1e-9)
        assertEquals(100, advice.layerCount)
        assertEquals(20.1, advice.exactHeight, 1e-6)
        assertEquals(1, advice.warnings.size)
        assertTrue(LayerSettings.advice(0.4, 0.35, 10.0).warnings.isNotEmpty())
        val whole = LayerSettings.advice(0.4, 0.15, 3.0)
        assertEquals(19, whole.layerCount)
        assertEquals(3.0, whole.exactHeight, 1e-9)
        assertTrue(whole.warnings.isEmpty())
    }

    @Test
    fun extrusionCorrections() {
        assertEquals(95.3846, Extrusion.newESteps(93.0, 100.0, 97.5), 0.0001)
        assertEquals(95.2381, Extrusion.newFlowPercent(100.0, 0.8, 0.84), 0.0001)
        assertEquals(10.8, Extrusion.volumetricRate(0.2, 0.45, 120.0), 1e-9)
        assertEquals(133.3333, Extrusion.maxSpeed(12.0, 0.2, 0.45), 0.0001)
    }

    @Test
    fun shrinkageCompensatesInBothDirections() {
        assertEquals(1.005025, Shrinkage.scaleFactor(0.5), 1e-6)
        assertEquals(100.5025, 100.0 * Shrinkage.scaleFactor(0.5), 1e-4)
        assertEquals(79.602, Shrinkage.correctedSteps(80.0, 20.0, 20.1), 1e-3)
        assertEquals(0.5, Shrinkage.errorPercent(20.0, 20.1), 1e-9)
    }

    @Test
    fun towerPlanAndScripts() {
        val plan = TemperatureTower.plan(220, -5, 40, 5, 0.3, 0.2)
        assertEquals(5, plan.size)
        assertEquals(220, plan[0].temperature)
        assertEquals(200, plan[4].temperature)
        assertEquals(1, plan[0].firstLayer)
        assertEquals(41, plan[1].firstLayer)
        assertEquals(0.3, plan[0].zStart, 1e-9)
        assertEquals(8.3, plan[1].zStart, 1e-9)
        assertEquals(200, plan.last().lastLayer)

        val slicer = TemperatureTower.slicerScript(plan)
        assertEquals(4, slicer.size)
        assertEquals("{if layer_num==40}M104 S215{endif}", slicer[0])
        assertTrue(TemperatureTower.marlinScript(plan)[0].startsWith("M109 S220"))
    }

    @Test
    fun gcodeSearchMatchesCodeAndDescription() {
        assertEquals(GcodeReference.entries.size, GcodeReference.search("  ").size)
        assertTrue(GcodeReference.search("M104").isNotEmpty())
        assertTrue(GcodeReference.search("pressure advance").any { it.code == "SET_PRESSURE_ADVANCE" })
        assertTrue(GcodeReference.search("сетк").any { it.code == "BED_MESH_CALIBRATE" })
        assertTrue(GcodeReference.search("zzzz").isEmpty())
    }
}
