package com.vasmarfas.card.core

import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// dB SPL of a full-scale sine, whose MDCT energy is about 1 per granule (per window in short blocks)
private const val FULL_SCALE_SPL = 96.0
private const val NOISE_OFFSET_DB = 5.5
private const val TONAL_OFFSET_DB = 14.5
private const val SHORT_OFFSET_DB = 20.0

// how far the allowed noise of a short window may rise over the one before, pre-masking covers only
// a few ms
private const val SHORT_RISE = 2.0
private const val MIN_TONALITY_LINES = 12
private const val LOG10_E = 0.4342944819032518
private val LN_2 = ln(2.0)

// Terhardt, dB SPL
private fun absoluteThreshold(hz: Double): Double {
    val f = max(hz, 20.0) / 1000
    return 3.64 * f.pow(-0.8) - 6.5 * exp(-0.6 * (f - 3.3) * (f - 3.3)) + 1e-3 * f * f * f * f
}

private fun bark(hz: Double): Double = 13 * atan(0.00076 * hz) + 3.5 * atan((hz / 7500) * (hz / 7500))

// Schroeder, dz is maskee minus masker in Bark
private fun spreading(dz: Double): Double {
    val x = dz + 0.474
    val db = 15.81 + 7.5 * x - 17.5 * sqrt(1 + x * x)
    return if (db < -100) 0.0 else 10.0.pow(db / 10)
}

// Johnston's model with Schroeder spreading, Terhardt's threshold is the floor
internal class Mp3Masking(sampleRate: Int, private val longEdges: IntArray, shortEdges: IntArray) {
    private val longBands = longEdges.size - 1
    private val shortBands = shortEdges.size - 1

    val shortGroups = IntArray(3 * shortBands + 1).also { groups ->
        for (b in 0 until shortBands) {
            for (w in 0 until 3) groups[3 * b + w] = 3 * shortEdges[b] + w * (shortEdges[b + 1] - shortEdges[b])
        }
        groups[3 * shortBands] = 576
    }

    private val longAth = DoubleArray(longBands)
    private val shortAth = DoubleArray(shortBands)
    private val tonalOffset = DoubleArray(longBands)
    private val longSpread = DoubleArray(longBands * longBands)
    private val shortSpread = DoubleArray(shortBands * shortBands)
    private val masker = DoubleArray(3 * shortBands)
    private val shortOffset = 10.0.pow(-SHORT_OFFSET_DB / 10)

    init {
        val longBark = DoubleArray(longBands)
        for (b in 0 until longBands) {
            longAth[b] = athEnergy(longEdges[b], longEdges[b + 1], sampleRate / 1152.0)
            longBark[b] = bark((longEdges[b] + longEdges[b + 1]) * 0.5 * sampleRate / 1152)
            tonalOffset[b] = TONAL_OFFSET_DB + longBark[b]
        }
        for (b in 0 until longBands) {
            for (k in 0 until longBands) longSpread[b * longBands + k] = spreading(longBark[b] - longBark[k])
        }
        val shortBark = DoubleArray(shortBands) { bark((shortEdges[it] + shortEdges[it + 1]) * 0.5 * sampleRate / 384) }
        for (b in 0 until shortBands) {
            shortAth[b] = athEnergy(shortEdges[b], shortEdges[b + 1], sampleRate / 384.0)
            for (k in 0 until shortBands) shortSpread[b * shortBands + k] = spreading(shortBark[b] - shortBark[k])
        }
    }

    private fun athEnergy(from: Int, to: Int, lineHz: Double): Double {
        var quietest = Double.MAX_VALUE
        for (i in from until to) quietest = min(quietest, absoluteThreshold((i + 0.5) * lineHz))
        return 10.0.pow((quietest - FULL_SCALE_SPL) / 10)
    }

    fun groups(short: Boolean): IntArray = if (short) shortGroups else longEdges

    fun energies(lines: DoubleArray, short: Boolean, energy: DoubleArray) {
        val edges = groups(short)
        for (g in 0 until edges.size - 1) {
            var sum = 0.0
            for (i in edges[g] until edges[g + 1]) sum += lines[i] * lines[i]
            energy[g] = sum
        }
    }

    fun thresholds(lines: DoubleArray, short: Boolean, energy: DoubleArray, xmin: DoubleArray) {
        if (short) {
            for (g in 0 until 3 * shortBands) masker[g] = energy[g] * shortOffset
            for (b in 0 until shortBands) {
                for (w in 0 until 3) {
                    var sum = 0.0
                    val row = b * shortBands
                    for (k in 0 until shortBands) sum += masker[3 * k + w] * shortSpread[row + k]
                    xmin[3 * b + w] = max(sum, shortAth[b])
                    if (w > 0) xmin[3 * b + w] = min(xmin[3 * b + w], max(shortAth[b], SHORT_RISE * xmin[3 * b + w - 1]))
                }
            }
            return
        }
        for (k in 0 until longBands) {
            val offset = if (energy[k] > 0.0) {
                val tonality = tonality(lines, k)
                tonality * tonalOffset[k] + (1 - tonality) * NOISE_OFFSET_DB
            } else {
                NOISE_OFFSET_DB
            }
            masker[k] = energy[k] * 10.0.pow(-offset / 10)
        }
        for (b in 0 until longBands) {
            var sum = 0.0
            val row = b * longBands
            for (k in 0 until longBands) sum += masker[k] * longSpread[row + k]
            xmin[b] = max(sum, longAth[b])
        }
    }

    // perceptual entropy estimate
    fun demand(energy: DoubleArray, short: Boolean, xmin: DoubleArray): Double {
        val edges = groups(short)
        var bits = 0.0
        for (g in 0 until edges.size - 1) {
            if (energy[g] > xmin[g]) bits += (edges[g + 1] - edges[g]) * 0.5 * ln(energy[g] / xmin[g]) / LN_2
        }
        return bits
    }

    private fun tonality(lines: DoubleArray, band: Int): Double {
        var lo = longEdges[band]
        var hi = longEdges[band + 1]
        while (hi - lo < MIN_TONALITY_LINES) {
            if (lo > 0) lo--
            if (hi < 576) hi++
        }
        var logSum = 0.0
        var sum = 0.0
        for (i in lo until hi) {
            val e = lines[i] * lines[i] + 1e-30
            logSum += ln(e)
            sum += e
        }
        val n = hi - lo
        val flatnessDb = 10 * LOG10_E * (logSum / n - ln(sum / n))
        return ((-flatnessDb - 6) / 19).coerceIn(0.0, 1.0)
    }
}
