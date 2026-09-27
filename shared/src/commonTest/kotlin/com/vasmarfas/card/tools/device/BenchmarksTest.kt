package com.vasmarfas.card.tools.device

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// a benchmark that computes the wrong thing still gives a number, so every kernel is checked against
// a property that holds however fast the machine is
class BenchmarksTest {
    @Test
    fun linpackSolvesTheSystemItGenerates() {
        val result = Linpack.run(100) { block -> timeSeconds(block) }
        // the normalised residual is O(1) for a correct solve, a broken one runs into the thousands
        assertTrue(result.residual < 10.0, "residual ${result.residual}")
        assertTrue(result.mflops > 0.0)
    }

    @Test
    fun monteCarloApproximatesPi() {
        val estimate = SciMark.monteCarloIntegrate(2_000_000)
        assertTrue(abs(estimate - PI) < 0.01, "estimate $estimate")
    }

    @Test
    fun fftRoundTripRestoresTheSignal() {
        val points = 64
        val data = DoubleArray(2 * points) { it * 0.125 - 3.0 }
        val original = data.copyOf()

        SciMark.fftTransform(data.size, data)
        assertTrue(data.indices.any { abs(data[it] - original[it]) > 1e-6 }, "transform did nothing")

        SciMark.fftInverse(data.size, data)
        for (i in data.indices) {
            assertTrue(abs(data[i] - original[i]) < 1e-9, "index $i: ${data[i]} vs ${original[i]}")
        }
    }

    @Test
    fun luFactorReproducesThePivotedMatrix() {
        val n = 12
        val random = SciMarkRandom(7)
        val a = Array(n) { DoubleArray(n) { random.nextDouble() } }
        val lu = Array(n) { a[it].copyOf() }
        val pivot = IntArray(n)

        SciMark.luFactor(n, n, lu, pivot)

        val permuted = Array(n) { a[it].copyOf() }
        for (j in 0 until n) {
            val jp = pivot[j]
            if (jp != j) {
                val tmp = permuted[j]
                permuted[j] = permuted[jp]
                permuted[jp] = tmp
            }
        }
        for (i in 0 until n) {
            for (j in 0 until n) {
                var sum = 0.0
                for (k in 0..minOf(i, j)) {
                    val l = if (k == i) 1.0 else lu[i][k]
                    sum += l * lu[k][j]
                }
                assertTrue(abs(sum - permuted[i][j]) < 1e-9, "[$i][$j]: $sum vs ${permuted[i][j]}")
            }
        }
    }

    @Test
    fun sciMarkRandomStaysInRange() {
        val random = SciMarkRandom(101)
        repeat(10_000) {
            val value = random.nextDouble()
            assertTrue(value >= 0.0 && value < 1.0, "value $value")
        }
    }

    @Test
    fun whetstoneGivesTheSameCheckSumEveryRun() {
        val first = Whetstone().majorLoop(10)
        val second = Whetstone().majorLoop(10)
        assertEquals(first, second)
        assertTrue(first.isFinite(), "checksum $first")
    }

    @Test
    fun whetstoneScoreFollowsTheReferenceFormula() {
        // the source defines LOOP=10 as one million Whetstone instructions per major loop,
        // so one of those per second has to come out as exactly 1 MWIPS
        assertEquals(1.0, Whetstone.mwips(loop = 10, majorLoops = 1, seconds = 1.0))

        // KIPS = 100 * loop * majorLoops / seconds, the reported figure is KIPS / 1000
        assertEquals(1000.0, Whetstone.mwips(loop = 1000, majorLoops = 10, seconds = 1.0))
        assertEquals(500.0, Whetstone.mwips(loop = 1000, majorLoops = 10, seconds = 2.0))
        assertEquals(0.0, Whetstone.mwips(loop = 1000, majorLoops = 10, seconds = 0.0))
    }

    @Test
    fun mandelbrotCountsTheSameIterationsEveryRun() {
        val first = mandelbrot(64, 100)
        assertEquals(first, mandelbrot(64, 100))
        assertTrue(first > 0)
        // the interior of the set costs the full iteration budget, the exterior much less
        assertTrue(first < 64L * 64 * 100, "every pixel hit the cap: $first")
    }

    @Test
    fun everyBenchmarkReportsFiniteScores() {
        for (benchmark in benchmarks) {
            val scores = benchmark.run(0.01)
            assertTrue(scores.isNotEmpty(), "${benchmark.id} returned nothing")
            for (score in scores) {
                assertTrue(score.value.isFinite(), "${benchmark.id}/${score.name} = ${score.value}")
                assertTrue(score.value > 0.0, "${benchmark.id}/${score.name} = ${score.value}")
            }
        }
    }

    @Test
    fun benchmarkIdsAreUnique() {
        val ids = benchmarks.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate ids in $ids")
    }
}
