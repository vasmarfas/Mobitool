package com.vasmarfas.card.tools.device

import kotlin.math.abs

// port of netlib.org/benchmark/linpackjava, only the path the benchmark walks: job 0, unit strides
object Linpack {
    class Result(val mflops: Double, val seconds: Double, val residual: Double)

    fun run(n: Int, elapsed: (() -> Unit) -> Double): Result {
        val lda = n + 1
        val a = Array(n) { DoubleArray(lda) }
        val b = DoubleArray(n)
        val x = DoubleArray(n)
        val ipvt = IntArray(n)
        val ops = 2.0 * n * n * n / 3.0 + 2.0 * n * n

        var norma = matgen(a, n, b)
        val seconds = elapsed {
            dgefa(a, n, ipvt)
            dgesl(a, n, ipvt, b)
        }

        for (i in 0 until n) x[i] = b[i]
        norma = matgen(a, n, b)
        for (i in 0 until n) b[i] = -b[i]
        dmxpy(n, b, n, x, a)

        var resid = 0.0
        var normx = 0.0
        for (i in 0 until n) {
            resid = maxOf(resid, abs(b[i]))
            normx = maxOf(normx, abs(x[i]))
        }
        val eps = epslon(1.0)
        val residual = if (n * norma * normx * eps == 0.0) 0.0 else resid / (n * norma * normx * eps)
        val mflops = if (seconds <= 0.0) 0.0 else ops / (1.0e6 * seconds)
        return Result(mflops, seconds, residual)
    }

    private fun matgen(a: Array<DoubleArray>, n: Int, b: DoubleArray): Double {
        val gen = JavaRandom(1325)
        var norma = 0.0
        for (i in 0 until n) {
            for (j in 0 until n) {
                a[j][i] = gen.nextDouble() - 0.5
                if (a[j][i] > norma) norma = a[j][i]
            }
        }
        for (i in 0 until n) b[i] = 0.0
        for (j in 0 until n) {
            for (i in 0 until n) b[i] += a[j][i]
        }
        return norma
    }

    private fun dgefa(a: Array<DoubleArray>, n: Int, ipvt: IntArray) {
        for (k in 0 until n - 1) {
            val colK = a[k]
            val kp1 = k + 1
            val l = idamax(n - k, colK, k) + k
            ipvt[k] = l
            if (colK[l] != 0.0) {
                if (l != k) {
                    val t = colK[l]
                    colK[l] = colK[k]
                    colK[k] = t
                }
                dscal(n - kp1, -1.0 / colK[k], colK, kp1)
                for (j in kp1 until n) {
                    val colJ = a[j]
                    val t = colJ[l]
                    if (l != k) {
                        colJ[l] = colJ[k]
                        colJ[k] = t
                    }
                    daxpy(n - kp1, t, colK, kp1, colJ, kp1)
                }
            }
        }
        ipvt[n - 1] = n - 1
    }

    private fun dgesl(a: Array<DoubleArray>, n: Int, ipvt: IntArray, b: DoubleArray) {
        for (k in 0 until n - 1) {
            val l = ipvt[k]
            val t = b[l]
            if (l != k) {
                b[l] = b[k]
                b[k] = t
            }
            daxpy(n - (k + 1), t, a[k], k + 1, b, k + 1)
        }
        for (kb in 0 until n) {
            val k = n - (kb + 1)
            b[k] /= a[k][k]
            daxpy(k, -b[k], a[k], 0, b, 0)
        }
    }

    private fun daxpy(n: Int, da: Double, dx: DoubleArray, dxOff: Int, dy: DoubleArray, dyOff: Int) {
        if (n <= 0 || da == 0.0) return
        for (i in 0 until n) dy[i + dyOff] += da * dx[i + dxOff]
    }

    private fun dscal(n: Int, da: Double, dx: DoubleArray, dxOff: Int) {
        for (i in 0 until n) dx[i + dxOff] *= da
    }

    private fun idamax(n: Int, dx: DoubleArray, dxOff: Int): Int {
        if (n < 1) return -1
        if (n == 1) return 0
        var itemp = 0
        var dmax = abs(dx[dxOff])
        for (i in 0 until n) {
            val dtemp = abs(dx[i + dxOff])
            if (dtemp > dmax) {
                itemp = i
                dmax = dtemp
            }
        }
        return itemp
    }

    private fun dmxpy(n1: Int, y: DoubleArray, n2: Int, x: DoubleArray, m: Array<DoubleArray>) {
        for (j in 0 until n2) {
            for (i in 0 until n1) y[i] += x[j] * m[j][i]
        }
    }

    private fun epslon(x: Double): Double {
        val a = 4.0 / 3.0
        var eps = 0.0
        while (eps == 0.0) {
            val b = a - 1.0
            val c = b + b + b
            eps = abs(c - 1.0)
        }
        return eps * abs(x)
    }
}

// the java.util.Random LCG, so matgen builds the reference matrix on every target
private class JavaRandom(seed: Long) {
    private var state = (seed xor MULTIPLIER) and MASK

    private fun next(bits: Int): Int {
        state = (state * MULTIPLIER + ADDEND) and MASK
        return (state ushr (48 - bits)).toInt()
    }

    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) * DOUBLE_UNIT

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val ADDEND = 0xBL
        const val MASK = (1L shl 48) - 1
        const val DOUBLE_UNIT = 1.0 / (1L shl 53)
    }
}
