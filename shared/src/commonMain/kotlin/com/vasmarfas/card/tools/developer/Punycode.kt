package com.vasmarfas.card.tools.developer

import com.vasmarfas.card.tools.text.appendCodePoint
import com.vasmarfas.card.tools.text.codePointList

object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    private fun adapt(delta0: Long, numPoints: Int, firstTime: Boolean): Int {
        var delta = if (firstTime) delta0 / DAMP else delta0 / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return k + ((BASE - TMIN + 1) * delta / (delta + SKEW)).toInt()
    }

    private fun threshold(k: Int, bias: Int): Int = when {
        k <= bias -> TMIN
        k >= bias + TMAX -> TMAX
        else -> k - bias
    }

    private fun digit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    private fun digitValue(c: Char): Int? = when (c) {
        in 'a'..'z' -> c - 'a'
        in 'A'..'Z' -> c - 'A'
        in '0'..'9' -> c - '0' + 26
        else -> null
    }

    fun encode(input: String): String {
        val cps = input.codePointList()
        val sb = StringBuilder()
        for (cp in cps) if (cp < 0x80) sb.append(cp.toChar())
        val b = sb.length
        var h = b
        if (b > 0) sb.append('-')
        var n = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        while (h < cps.size) {
            val m = cps.filter { it >= n }.min()
            delta += (m - n).toLong() * (h + 1)
            n = m
            for (c in cps) {
                if (c < n) delta++
                if (c == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = threshold(k, bias)
                        if (q < t) break
                        sb.append(digit((t + (q - t) % (BASE - t)).toInt()))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    sb.append(digit(q.toInt()))
                    bias = adapt(delta, h + 1, h == b)
                    delta = 0
                    h++
                }
            }
            delta++
            n++
        }
        return sb.toString()
    }

    fun decode(input: String): String? {
        val out = mutableListOf<Int>()
        val dash = input.lastIndexOf('-')
        val basicEnd = if (dash >= 0) dash else 0
        for (i in 0 until basicEnd) {
            val c = input[i]
            if (c.code >= 0x80) return null
            out += c.code
        }
        var n = INITIAL_N.toLong()
        var i = 0L
        var bias = INITIAL_BIAS
        var pos = if (dash >= 0) dash + 1 else 0
        while (pos < input.length) {
            val oldI = i
            var w = 1L
            var k = BASE
            while (true) {
                if (pos >= input.length) return null
                val d = digitValue(input[pos++]) ?: return null
                i += d * w
                if (i > Int.MAX_VALUE) return null
                val t = threshold(k, bias)
                if (d < t) break
                w *= BASE - t
                if (w > Int.MAX_VALUE) return null
                k += BASE
            }
            bias = adapt(i - oldI, out.size + 1, oldI == 0L)
            n += i / (out.size + 1)
            i %= out.size + 1
            if (n > 0x10FFFF) return null
            out.add(i.toInt(), n.toInt())
            i++
        }
        return buildString { for (cp in out) appendCodePoint(cp) }
    }

    fun toAscii(host: String): String = host.trim().trimEnd('.').split('.').joinToString(".") { label ->
        val lower = label.lowercase()
        if (lower.all { it.code < 0x80 }) lower else "xn--" + encode(lower)
    }

    fun toUnicode(host: String): String? = host.trim().trimEnd('.').split('.').map { label ->
        if (label.lowercase().startsWith("xn--")) decode(label.substring(4)) ?: return null else label.lowercase()
    }.joinToString(".")
}
