package com.vasmarfas.card.tools.electronics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

class LedResult(
    val resistance: Double,
    val power: Double,
    val e12: Double,
    val e24: Double,
    val currentE12Ma: Double,
    val currentE24Ma: Double,
    val ratingW: Double,
)

object LedResistor {
    val e12 = listOf(1.0, 1.2, 1.5, 1.8, 2.2, 2.7, 3.3, 3.9, 4.7, 5.6, 6.8, 8.2)
    val e24 = listOf(1.0, 1.1, 1.2, 1.3, 1.5, 1.6, 1.8, 2.0, 2.2, 2.4, 2.7, 3.0, 3.3, 3.6, 3.9, 4.3, 4.7, 5.1, 5.6, 6.2, 6.8, 7.5, 8.2, 9.1)
    private val ratings = listOf(0.125, 0.25, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0)

    fun nextInSeries(value: Double, series: List<Double>): Double {
        if (value <= 0) return series.first()
        val decade = 10.0.pow(floor(log10(value)))
        val normalized = value / decade
        val candidate = series.firstOrNull { it >= normalized - 1e-9 }
        return if (candidate != null) candidate * decade else series.first() * decade * 10
    }

    fun previousInSeries(value: Double, series: List<Double>): Double {
        val decade = 10.0.pow(floor(log10(value)))
        val normalized = value / decade
        val candidate = series.lastOrNull { it <= normalized + 1e-9 }
        return if (candidate != null) candidate * decade else series.last() * decade / 10
    }

    fun nearestInSeries(value: Double, series: List<Double>): Double {
        if (value <= 0) return series.first()
        val decade = 10.0.pow(floor(log10(value)))
        val candidates = series.map { it * decade } + series.first() * decade * 10
        return candidates.minBy { abs(it - value) }
    }

    // one chain takes as many LEDs as leave the resistor some voltage of its own
    fun maxInSeries(supply: Double, forward: Double): Int = ceil(supply / forward - 1e-9).toInt() - 1

    fun compute(supply: Double, forward: Double, currentMa: Double, ledsInSeries: Int): LedResult? {
        val drop = supply - forward * ledsInSeries
        if (drop <= 1e-6 * supply || currentMa <= 0) return null
        val current = currentMa / 1000
        val resistance = drop / current
        val e12Value = nextInSeries(resistance, e12)
        val e24Value = nextInSeries(resistance, e24)
        val power = drop * current
        val rating = ratings.firstOrNull { it >= power * 2 } ?: ratings.last()
        return LedResult(resistance, power, e12Value, e24Value, drop / e12Value * 1000, drop / e24Value * 1000, rating)
    }
}
