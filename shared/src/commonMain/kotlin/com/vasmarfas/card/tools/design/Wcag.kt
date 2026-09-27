package com.vasmarfas.card.tools.design

import kotlin.math.pow
import kotlin.math.roundToInt

data class ContrastCheck(val ratio: Double) {
    val aaNormal: Boolean get() = ratio >= 4.5
    val aaLarge: Boolean get() = ratio >= 3.0
    val aaaNormal: Boolean get() = ratio >= 7.0
    val aaaLarge: Boolean get() = ratio >= 4.5
}
object Wcag {
    fun relativeLuminance(c: Rgba): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.r) + 0.7152 * channel(c.g) + 0.0722 * channel(c.b)
    }
    fun ratio(a: Rgba, b: Rgba): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
    fun check(a: Rgba, b: Rgba): ContrastCheck = ContrastCheck(ratio(a, b))
    fun rounded(value: Double): Double = (value * 100).roundToInt() / 100.0
    fun over(foreground: Rgba, background: Rgba): Rgba {
        val a = foreground.a / 255.0
        fun mix(f: Int, b: Int) = (f * a + b * (1 - a)).roundToInt()
        return Rgba(mix(foreground.r, background.r), mix(foreground.g, background.g), mix(foreground.b, background.b))
    }
    fun nearestPassing(foreground: Rgba, background: Rgba, target: Double): Rgba? {
        val darker = relativeLuminance(foreground) < relativeLuminance(background)
        return search(foreground, background, target, darker) ?: search(foreground, background, target, !darker)
    }
    private fun search(foreground: Rgba, background: Rgba, target: Double, darker: Boolean): Rgba? {
        val hsl = ColorMath.toHsl(foreground)
        val steps = (0..100).map { if (darker) hsl.l - it / 100.0 else hsl.l + it / 100.0 }
        for (l in steps) {
            if (l < 0.0 || l > 1.0) continue
            val candidate = ColorMath.fromHsl(Hsl(hsl.h, hsl.s, l), foreground.a)
            if (ratio(candidate, background) >= target) return candidate
        }
        val fallback = if (darker) Rgba(0, 0, 0, foreground.a) else Rgba(255, 255, 255, foreground.a)
        return fallback.takeIf { ratio(it, background) >= target }
    }
}
