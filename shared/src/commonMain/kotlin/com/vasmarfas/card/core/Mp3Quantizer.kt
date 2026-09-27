package com.vasmarfas.card.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

private const val MAX_QUANTIZED = 8206

// |xr|^(3/4) times the quantizer factor has to stay below this to round to at most MAX_QUANTIZED
private const val MAX_SCALED = MAX_QUANTIZED + 0.5946
private const val ROUNDING = 0.4054
private const val STEP_OFFSET = 64
private const val MAX_ITERATIONS = 30
private const val INVALID = Int.MAX_VALUE
private const val SILENCE = 1e-12
private const val MAX_GROUPS = 39

private val QUANTIZE = DoubleArray(320) { 2.0.pow(-0.1875 * (it - STEP_OFFSET - 210)) }

private val STEP = DoubleArray(320) { 2.0.pow(0.25 * (it - STEP_OFFSET - 210)) }

private val POW43 = DoubleArray(MAX_QUANTIZED + 1) { it.toDouble().pow(4.0 / 3) }

private val SCFSI_BANDS = intArrayOf(0, 6, 11, 16, 21)

internal class Mp3Granule {
    val values = IntArray(576)

    // 21 long bands or 12 short bands x 3 windows, preemphasis already taken out
    val scalefactors = IntArray(36)

    // slen1 and slen2 in MPEG-1, the four partition lengths in MPEG-2
    val slen = IntArray(4)
    val tableSelect = IntArray(3)
    var blockType = NORMAL_BLOCK
    var globalGain = 0
    var scalefacCompress = 0
    var scalefacScale = 0
    var preflag = 0
    var scfsi = 0
    var partitions = 0
    var part23Length = 0
    var bigValues = 0
    var count1 = 0
    var region0Count = 0
    var region1Count = 0
    var count1Table = 0
}

// ISO inner and outer loops, a group is a long band or one window of a short band
internal class Mp3Quantizer(private val mpeg1: Boolean, private val longEdges: IntArray, private val shortGroups: IntArray) {
    private val huffman = Mp3Huffman()
    private val magnitude = DoubleArray(576)
    private val xr34 = DoubleArray(576)
    private val groupPeak = DoubleArray(MAX_GROUPS)
    private val noise = DoubleArray(MAX_GROUPS)
    private val amplification = IntArray(MAX_GROUPS)
    private val bestAmplification = IntArray(MAX_GROUPS)
    private val bestValues = IntArray(576)
    private val lsfSlen = IntArray(4)
    private var edges = longEdges
    private var groups = 22
    private var scaled = 21
    private var short = false
    private var scale = 0
    private var lastGain = -1
    private var overCount = 0
    private var overNoise = 0.0
    private var totalNoise = 0.0
    private var chosenCompress = 0
    private var candidate = 0
    private var chosenPreflag = 0
    private var chosenScfsi = 0
    private var chosenPartitions = 0

    // reference is granule 0 of the channel when out is granule 1 of an MPEG-1 frame and both are long,
    // groups equal to it are shared through scfsi
    fun quantize(lines: DoubleArray, xmin: DoubleArray, maxBits: Int, out: Mp3Granule, reference: Mp3Granule?) {
        short = out.blockType == SHORT_BLOCK
        edges = if (short) shortGroups else longEdges
        groups = edges.size - 1
        scaled = if (short) 36 else 21
        var peak = 0.0
        for (i in 0 until 576) {
            val m = abs(lines[i])
            magnitude[i] = m
            xr34[i] = sqrt(m * sqrt(m))
            if (m > peak) peak = m
        }
        for (g in 0 until groups) {
            var p = 0.0
            for (i in edges[g] until edges[g + 1]) if (xr34[i] > p) p = xr34[i]
            groupPeak[g] = p
        }
        amplification.fill(0)
        scale = 0
        if (peak < SILENCE || !fullSearch(maxBits - scalefactorBits(reference), out)) {
            out.values.fill(0)
            finish(out, 210, scalefactorBits(reference))
            return
        }
        var gain = lastGain
        measure(gain, out.values, xmin)
        save(out.values)
        var bestOver = overNoise
        var bestTotal = totalNoise
        var bestGain = gain
        var bestScale = scale
        for (iteration in 1 until MAX_ITERATIONS) {
            if (overCount == 0 || !amplify(xmin)) break
            var part2 = scalefactorBits(reference)
            if (part2 == INVALID && scale == 0) {
                scale = 1
                for (g in 0 until scaled) amplification[g] = (amplification[g] + 1) / 2
                part2 = scalefactorBits(reference)
            }
            if (part2 == INVALID || part2 > maxBits) break
            gain = search(maxBits - part2, gain, out)
            if (gain < 0) break
            measure(gain, out.values, xmin)
            if (overNoise < bestOver - 1e-9 || (overNoise <= bestOver + 1e-9 && totalNoise < bestTotal)) {
                save(out.values)
                bestOver = overNoise
                bestTotal = totalNoise
                bestGain = gain
                bestScale = scale
            }
        }
        bestAmplification.copyInto(amplification)
        bestValues.copyInto(out.values)
        scale = bestScale
        finish(out, bestGain, scalefactorBits(reference))
    }

    private fun finish(out: Mp3Granule, gain: Int, part2: Int) {
        out.globalGain = gain
        out.scalefacScale = scale
        out.preflag = chosenPreflag
        out.scfsi = chosenScfsi
        out.partitions = chosenPartitions
        out.scalefactors.fill(0)
        for (g in 0 until scaled) out.scalefactors[g] = amplification[g] - if (short) 0 else chosenPreflag * Mp3Tables.pretab[g]
        if (mpeg1) {
            out.scalefacCompress = chosenCompress
            out.slen[0] = Mp3Tables.slen1[chosenCompress]
            out.slen[1] = Mp3Tables.slen2[chosenCompress]
        } else {
            lsfSlen.copyInto(out.slen)
            val s = out.slen
            out.scalefacCompress = when (chosenPartitions) {
                0 -> ((s[0] * 5 + s[1]) shl 4) + (s[2] shl 2) + s[3]
                1 -> 400 + ((s[0] * 5 + s[1]) shl 2) + s[2]
                else -> 500 + s[0] * 3 + s[1]
            }
        }
        out.part23Length = part2 + huffman.countBest(out.values, out, longEdges)
    }

    private fun save(values: IntArray) {
        values.copyInto(bestValues)
        amplification.copyInto(bestAmplification)
    }

    private fun quantizeAt(gain: Int, values: IntArray): Boolean {
        val shift = if (scale == 1) 4 else 2
        for (g in 0 until groups) {
            val factor = QUANTIZE[gain - shift * amplification[g] + STEP_OFFSET]
            if (groupPeak[g] * factor >= MAX_SCALED) return false
            for (i in edges[g] until edges[g + 1]) values[i] = (xr34[i] * factor + ROUNDING).toInt()
        }
        return true
    }

    private fun fits(gain: Int, budget: Int, out: Mp3Granule): Boolean {
        lastGain = gain
        return quantizeAt(gain, out.values) && huffman.count(out.values, out, longEdges) <= budget
    }

    private fun fullSearch(budget: Int, out: Mp3Granule): Boolean {
        if (budget < 0 || !fits(255, budget, out)) return false
        var lo = -1
        var hi = 255
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (fits(mid, budget, out)) hi = mid else lo = mid
        }
        if (lastGain != hi) fits(hi, budget, out)
        return true
    }

    private fun search(budget: Int, start: Int, out: Mp3Granule): Int {
        var lo: Int
        var hi: Int
        var step = 1
        if (fits(start, budget, out)) {
            hi = start
            lo = start - step
            while (lo >= 0 && fits(lo, budget, out)) {
                hi = lo
                step *= 2
                lo = hi - step
            }
            lo = max(lo, -1)
        } else {
            lo = start
            hi = start + step
            while (hi < 255 && !fits(hi, budget, out)) {
                lo = hi
                step *= 2
                hi = lo + step
            }
            if (hi >= 255) {
                if (lo == 255 || !fits(255, budget, out)) return -1
                hi = 255
            }
        }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (fits(mid, budget, out)) hi = mid else lo = mid
        }
        if (lastGain != hi) fits(hi, budget, out)
        return hi
    }

    private fun measure(gain: Int, values: IntArray, xmin: DoubleArray) {
        val shift = if (scale == 1) 4 else 2
        overCount = 0
        overNoise = 0.0
        totalNoise = 0.0
        for (g in 0 until groups) {
            val step = STEP[gain - shift * amplification[g] + STEP_OFFSET]
            var sum = 0.0
            for (i in edges[g] until edges[g + 1]) {
                val d = magnitude[i] - POW43[values[i]] * step
                sum += d * d
            }
            noise[g] = sum
            totalNoise += sum
            if (sum > xmin[g]) {
                overNoise += 10 * log10(sum / xmin[g])
                if (g < scaled) overCount++
            }
        }
    }

    private fun amplify(xmin: DoubleArray): Boolean {
        var active = 0
        var raised = 0
        for (g in 0 until scaled) {
            if (groupPeak[g] > 0.0) active++
            if (noise[g] > xmin[g]) {
                amplification[g]++
                raised++
            }
        }
        return raised in 1 until active
    }

    private fun scalefactorBits(reference: Mp3Granule?): Int = when {
        !mpeg1 -> lsfBits()
        short -> mpeg1ShortBits()
        else -> mpeg1LongBits(reference)
    }

    private fun mpeg1LongBits(reference: Mp3Granule?): Int {
        var best = INVALID
        for (pre in 0..1) {
            if (pre == 1 && !preemphasisFits()) continue
            var max1 = 0
            var max2 = 0
            var n1 = 0
            var n2 = 0
            var scfsi = 0
            for (group in 0 until 4) {
                var shared = reference != null
                var peak = 0
                for (b in SCFSI_BANDS[group] until SCFSI_BANDS[group + 1]) {
                    val value = amplification[b] - pre * Mp3Tables.pretab[b]
                    if (reference != null && reference.scalefactors[b] != value) shared = false
                    peak = max(peak, value)
                }
                val count = SCFSI_BANDS[group + 1] - SCFSI_BANDS[group]
                when {
                    shared -> scfsi = scfsi or (8 shr group)
                    group < 2 -> {
                        max1 = max(max1, peak)
                        n1 += count
                    }
                    else -> {
                        max2 = max(max2, peak)
                        n2 += count
                    }
                }
            }
            val bits = mpeg1Compress(max1, max2, n1, n2)
            if (bits < best) {
                best = bits
                chosenCompress = candidate
                chosenPreflag = pre
                chosenScfsi = scfsi
            }
        }
        return best
    }

    private fun mpeg1ShortBits(): Int {
        var max1 = 0
        var max2 = 0
        for (g in 0 until 18) max1 = max(max1, amplification[g])
        for (g in 18 until 36) max2 = max(max2, amplification[g])
        chosenPreflag = 0
        chosenScfsi = 0
        val bits = mpeg1Compress(max1, max2, 18, 18)
        chosenCompress = candidate
        return bits
    }

    private fun mpeg1Compress(max1: Int, max2: Int, n1: Int, n2: Int): Int {
        var best = INVALID
        for (c in 0 until 16) {
            val s1 = Mp3Tables.slen1[c]
            val s2 = Mp3Tables.slen2[c]
            if (max1 >= (1 shl s1) || max2 >= (1 shl s2)) continue
            val bits = n1 * s1 + n2 * s2
            if (bits < best) {
                best = bits
                candidate = c
            }
        }
        return best
    }

    private fun lsfBits(): Int {
        var best = INVALID
        val table = if (short) Mp3Tables.lsfShortPartitions else Mp3Tables.lsfPartitions
        for (option in 0 until 3) {
            val pre = if (option == 2 && !short) 1 else 0
            if (pre == 1 && !preemphasisFits()) continue
            val partitions = table[option]
            val limits = Mp3Tables.lsfSlenLimits[option]
            var first = 0
            var bits = 0
            var fits = true
            var packed = 0
            for (p in 0 until 4) {
                var peak = 0
                for (g in first until first + partitions[p]) peak = max(peak, if (pre == 1) amplification[g] - Mp3Tables.pretab[g] else amplification[g])
                val length = 32 - peak.countLeadingZeroBits()
                if (length > limits[p]) {
                    fits = false
                    break
                }
                packed = packed or (length shl (4 * p))
                bits += length * partitions[p]
                first += partitions[p]
            }
            if (fits && bits < best) {
                best = bits
                chosenPartitions = option
                chosenPreflag = pre
                chosenScfsi = 0
                for (p in 0 until 4) lsfSlen[p] = (packed ushr (4 * p)) and 15
            }
        }
        return best
    }

    private fun preemphasisFits(): Boolean {
        for (b in 11 until 21) if (amplification[b] < Mp3Tables.pretab[b]) return false
        return true
    }
}
