package com.vasmarfas.card.tools.calculators

import kotlin.math.abs

object Percentage {
    fun percentOf(percent: Double, value: Double): Double = value * percent / 100

    fun whatPercent(part: Double, whole: Double): Double = part / whole * 100

    fun change(from: Double, to: Double): Double = (to - from) / abs(from) * 100

    fun addPercent(value: Double, percent: Double): Double = value * (1 + percent / 100)

    fun subtractPercent(value: Double, percent: Double): Double = value * (1 - percent / 100)
}
