package com.vasmarfas.card.tools.electronics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

class WireRow(
    val awg: Int,
    val label: String,
    val diameterMm: Double,
    val areaMm2: Double,
    val chassisAmps: Double,
    val transmissionAmps: Double,
    val ohmsPerKm: Double,
)

object WireGauge {
    private const val COPPER_RESISTIVITY = 17.241

    private val ampacity = mapOf(
        -3 to (380.0 to 302.0), -2 to (328.0 to 239.0), -1 to (283.0 to 190.0), 0 to (245.0 to 150.0),
        1 to (211.0 to 119.0), 2 to (181.0 to 94.0), 3 to (158.0 to 75.0), 4 to (135.0 to 60.0),
        5 to (118.0 to 47.0), 6 to (101.0 to 37.0), 7 to (89.0 to 30.0), 8 to (73.0 to 24.0),
        9 to (64.0 to 19.0), 10 to (55.0 to 15.0), 11 to (47.0 to 12.0), 12 to (41.0 to 9.3),
        13 to (35.0 to 7.4), 14 to (32.0 to 5.9), 15 to (28.0 to 4.7), 16 to (22.0 to 3.7),
        17 to (19.0 to 2.9), 18 to (16.0 to 2.3), 19 to (14.0 to 1.8), 20 to (11.0 to 1.5),
        21 to (9.0 to 1.2), 22 to (7.0 to 0.92), 23 to (4.7 to 0.729), 24 to (3.5 to 0.577),
        25 to (2.7 to 0.457), 26 to (2.2 to 0.361), 27 to (1.7 to 0.288), 28 to (1.4 to 0.226),
        29 to (1.2 to 0.182), 30 to (0.86 to 0.142), 31 to (0.7 to 0.113), 32 to (0.53 to 0.091),
        33 to (0.43 to 0.072), 34 to (0.33 to 0.056), 35 to (0.27 to 0.044), 36 to (0.21 to 0.035),
        37 to (0.17 to 0.0289), 38 to (0.13 to 0.0228), 39 to (0.11 to 0.0175), 40 to (0.09 to 0.0137),
    )

    val table: List<WireRow> = (-3..40).map { awg ->
        val (chassis, transmission) = ampacity.getValue(awg)
        val area = areaMm2(awg)
        WireRow(awg, label(awg), diameterMm(awg), area, chassis, transmission, ohmsPerKm(area))
    }

    val metric = listOf(0.35, 0.5, 0.75, 1.0, 1.5, 2.5, 4.0, 6.0, 10.0, 16.0, 25.0, 35.0, 50.0, 70.0, 95.0, 120.0)

    fun label(awg: Int): String = when (awg) {
        -3 -> "4/0"
        -2 -> "3/0"
        -1 -> "2/0"
        0 -> "1/0"
        else -> awg.toString()
    }

    fun diameterMm(awg: Int): Double = 0.127 * 92.0.pow((36 - awg) / 39.0)

    fun areaMm2(awg: Int): Double = PI / 4 * diameterMm(awg).pow(2)

    fun awgFromDiameter(mm: Double): Double = 36 - 39 * ln(mm / 0.127) / ln(92.0)

    fun diameterFromArea(mm2: Double): Double = sqrt(4 * mm2 / PI)

    fun awgFromArea(mm2: Double): Double = awgFromDiameter(diameterFromArea(mm2))

    fun ohmsPerKm(mm2: Double): Double = COPPER_RESISTIVITY / mm2

    fun nearest(awg: Double): WireRow = table.minBy { abs(it.awg - awg) }

    fun notThicker(awg: Double): WireRow = table.firstOrNull { it.awg >= awg - 0.01 } ?: table.last()
}
