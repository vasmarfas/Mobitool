package com.vasmarfas.card.tools.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContrastTest {
    private val white = Rgba(255, 255, 255)
    private val black = Rgba(0, 0, 0)

    @Test
    fun blackOnWhiteIsTwentyOne() {
        assertEquals(21.0, Wcag.rounded(Wcag.ratio(black, white)))
        assertEquals(1.0, Wcag.rounded(Wcag.ratio(white, white)))
        val gray = Wcag.check(Rgba(0x77, 0x77, 0x77), white)
        assertTrue(abs(gray.ratio - 4.478) < 0.01)
        assertFalse(gray.aaNormal)
        assertTrue(gray.aaLarge)
    }

    @Test
    fun nearestPassingReachesTarget() {
        val fixed = Wcag.nearestPassing(Rgba(0x77, 0x77, 0x77), white, 4.5)!!
        assertTrue(Wcag.ratio(fixed, white) >= 4.5)
        assertTrue(Wcag.relativeLuminance(fixed) < Wcag.relativeLuminance(Rgba(0x77, 0x77, 0x77)))
    }

    @Test
    fun nearestPassingCrossesToTheOtherSideWhenItsOwnCannotPass() {
        val background = Rgba(0x33, 0x33, 0x33)
        val fixed = Wcag.nearestPassing(Rgba(0x22, 0x22, 0x22), background, 4.5)!!
        assertEquals(Rgba(0x9C, 0x9C, 0x9C), fixed)
        assertTrue(Wcag.ratio(fixed, background) >= 4.5)
    }

    @Test
    fun translucentTextIsJudgedByWhatItMakesOnTheBackground() {
        val shown = Wcag.over(Rgba(0, 0, 0, 0x80), white)
        assertEquals(Rgba(0x7F, 0x7F, 0x7F), shown)
        val check = Wcag.check(shown, white)
        assertTrue(abs(check.ratio - 4.0) < 0.05, "ratio ${check.ratio}")
        assertFalse(check.aaNormal)
        assertEquals(black, Wcag.over(black, white))
    }

    @Test
    fun harmoniesRotateHue() {
        val base = Rgba(255, 0, 0)
        assertEquals(Rgba(0, 255, 255), Palette.complementary(base)[1])
        assertEquals(listOf(Rgba(255, 0, 0), Rgba(0, 255, 0), Rgba(0, 0, 255)), Palette.triadic(base))
        assertEquals(4, Palette.tetradic(base).size)
        assertEquals(5, Palette.tints(base).size)
        assertEquals(10, Palette.tonalRamp(base).size)
        assertTrue(Palette.shades(base).all { Wcag.relativeLuminance(it) <= Wcag.relativeLuminance(base) })
    }
}
