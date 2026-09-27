package com.vasmarfas.card.tools.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitsTest {
    @Test
    fun cssConversions() {
        assertEquals(1.5, CssUnits.pxToRem(24.0, 16.0))
        assertEquals(24.0, CssUnits.remToPx(1.5, 16.0))
        assertEquals(12.0, CssUnits.pxToPt(16.0))
        assertEquals(16.0, CssUnits.ptToPx(12.0))
        assertEquals(150.0, CssUnits.pxToPercent(24.0, 16.0))
        assertEquals(48.0, CssUnits.dpToPx(16.0, AndroidDensity.XXHDPI))
    }

    @Test
    fun modularScale() {
        val steps = ModularScale.steps(16.0, 1.25)
        assertEquals(9, steps.size)
        assertEquals(16.0, steps.first { it.first == 0 }.second)
        assertTrue(abs(steps.first { it.first == 2 }.second - 25.0) < 0.001)
        assertTrue(abs(steps.first { it.first == -1 }.second - 12.8) < 0.001)
        assertEquals(22.4, ModularScale.lineHeight(16.0))
    }

    @Test
    fun schemeExport() {
        val roles = listOf("primary" to Rgba(0x67, 0x50, 0xA4), "onPrimaryContainer" to Rgba(255, 255, 255))
        val kotlin = SchemeExport.kotlin(false, roles)
        assertTrue(kotlin.startsWith("lightColorScheme("))
        assertTrue(kotlin.contains("primary = Color(0xFF6750A4),"))
        assertTrue(SchemeExport.kotlin(true, roles).startsWith("darkColorScheme("))
        assertTrue(SchemeExport.css(roles).contains("--md-sys-color-on-primary-container: #ffffff;"))
        assertEquals("surface-container-high", SchemeExport.kebab("surfaceContainerHigh"))
    }

    @Test
    fun gradientCode() {
        val stops = listOf(Rgba(0, 0, 0), Rgba(255, 255, 255))
        assertEquals("linear-gradient(90deg, #000000 0%, #ffffff 100%)", Gradients.cssLinear(90, stops))
        assertTrue(Gradients.cssRadial(stops).startsWith("radial-gradient(circle,"))
        assertTrue(Gradients.composeLinear(90, stops).contains("Color(0xFF000000), Color(0xFFFFFFFF)"))
        assertEquals(1000 to 0, Gradients.endOffset(90))
        assertEquals(0 to 1000, Gradients.endOffset(180))
    }

    @Test
    fun gradientsPointingUpOrLeftStartFromTheFarEdge() {
        assertEquals(0 to 1000, Gradients.startOffset(0))
        assertEquals(0 to 0, Gradients.endOffset(0))
        assertEquals(0 to 707, Gradients.startOffset(45))
        assertEquals(707 to 0, Gradients.endOffset(45))
        assertEquals(1000 to 0, Gradients.startOffset(270))
        assertEquals(0 to 0, Gradients.endOffset(270))
        assertEquals(0 to 0, Gradients.startOffset(135))
        val code = Gradients.composeLinear(0, listOf(Rgba(0, 0, 0), Rgba(255, 255, 255)))
        assertTrue(code.contains("start = Offset(0f, 1000f),\n    end = Offset(0f, 0f),"), code)
    }
}
