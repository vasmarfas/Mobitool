package com.vasmarfas.card.tools.fitness

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

class OneRmFormula(val name: String, val compute: (Double, Int) -> Double)

object OneRepMax {
    val formulas: List<OneRmFormula> = listOf(
        OneRmFormula("Epley") { w, r -> w * (1 + r / 30.0) },
        OneRmFormula("Brzycki") { w, r -> w * 36.0 / (37.0 - r) },
        OneRmFormula("Lander") { w, r -> 100.0 * w / (101.3 - 2.67123 * r) },
        OneRmFormula("Lombardi") { w, r -> w * r.toDouble().pow(0.10) },
        OneRmFormula("O'Conner") { w, r -> w * (1 + 0.025 * r) },
        OneRmFormula("Wathan") { w, r -> 100.0 * w / (48.8 + 53.8 * exp(-0.075 * r)) },
    )

    fun all(weight: Double, reps: Int): List<Pair<String, Double>> =
        formulas.map { it.name to if (reps == 1) weight else it.compute(weight, reps) }

    fun average(weight: Double, reps: Int): Double = all(weight, reps).map { it.second }.average()

    fun repsAtPercent(percent: Int): Int = (30.0 * (100.0 / percent - 1.0)).roundToInt().coerceAtLeast(1)

    fun percentTable(oneRm: Double): List<Triple<Int, Double, Int>> =
        (100 downTo 50 step 5).map { Triple(it, oneRm * it / 100.0, repsAtPercent(it)) }
}
