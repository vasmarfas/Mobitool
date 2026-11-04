package com.vasmarfas.card.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StereoMixTest {
    private val h = 0.70710678

    private fun assertGains(expected: List<Double>, actual: FloatArray) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { i, gain -> assertTrue(abs(gain - actual[i]) < 1e-4, "channel $i: $gain != ${actual[i]}") }
    }

    private fun scaled(vararg gains: Double): List<Double> = gains.map { it / gains.sum() }

    // ITU-R BS.775 table 2: L0 = L + 0.7071 C + 0.7071 Ls, R0 = R + 0.7071 C + 0.7071 Rs
    @Test
    fun fivePointOneFollowsTheTable() {
        val (left, right) = stereoMatrix(6)
        assertGains(listOf(0.41421, 0.0, 0.29289, 0.0, 0.29289, 0.0), left)
        assertGains(listOf(0.0, 0.41421, 0.29289, 0.0, 0.0, 0.29289), right)
    }

    @Test
    fun threePointZero() {
        val (left, right) = stereoMatrix(3)
        assertGains(scaled(1.0, 0.0, h), left)
        assertGains(scaled(0.0, 1.0, h), right)
    }

    @Test
    fun quadro() {
        val (left, right) = stereoMatrix(4)
        assertGains(scaled(1.0, 0.0, h, 0.0), left)
        assertGains(scaled(0.0, 1.0, 0.0, h), right)
    }

    @Test
    fun fivePointZero() {
        val (left, right) = stereoMatrix(5)
        assertGains(scaled(1.0, 0.0, h, h, 0.0), left)
        assertGains(scaled(0.0, 1.0, h, 0.0, h), right)
    }

    @Test
    fun sixPointOneSendsTheBackCentreBothWays() {
        val (left, right) = stereoMatrix(7)
        assertGains(scaled(1.0, 0.0, h, 0.0, h, 0.0, h), left)
        assertGains(scaled(0.0, 1.0, h, 0.0, 0.0, h, h), right)
    }

    @Test
    fun sevenPointOneKeepsCentreAndBothSurroundPairs() {
        val (left, right) = stereoMatrix(8)
        assertGains(scaled(1.0, 0.0, h, 0.0, h, 0.0, h, 0.0), left)
        assertGains(scaled(0.0, 1.0, h, 0.0, 0.0, h, 0.0, h), right)
    }

    @Test
    fun lfeNeverReachesTheOutputAndNothingClips() {
        for (channels in 3..8) {
            for (row in stereoMatrix(channels)) {
                assertTrue(abs(row.sum() - 1f) < 1e-5, "$channels channels sum to ${row.sum()}")
                if (channels >= 6) assertEquals(0f, row[3])
            }
        }
    }

    @Test
    fun anUnknownLayoutTakesTheFirstTwo() {
        val (left, right) = stereoMatrix(10)
        assertEquals(1f, left[0])
        assertEquals(1f, right[1])
        assertEquals(1f, left.sum())
        assertEquals(1f, right.sum())
    }
}
