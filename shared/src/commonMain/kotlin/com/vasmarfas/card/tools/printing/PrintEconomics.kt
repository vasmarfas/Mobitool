package com.vasmarfas.card.tools.printing

import kotlin.math.pow

class PrintCost(
    val material: Double,
    val energy: Double,
    val energyKwh: Double,
    val beforeFailures: Double,
    val total: Double,
    val perGram: Double,
    val salePrice: Double,
    val profit: Double,
)
class ResinCost(
    val resinMl: Double,
    val resin: Double,
    val ipa: Double,
    val gloves: Double,
    val energy: Double,
    val energyKwh: Double,
    val total: Double,
)
class PrintTimeEstimate(
    val shellFraction: Double,
    val solidFraction: Double,
    val materialCm3: Double,
    val flowMm3S: Double,
    val seconds: Double,
)
private const val FLOW_EFFICIENCY = 0.75
object PrintEconomics {
    fun printCost(
        grams: Double,
        spoolPrice: Double,
        spoolGrams: Double,
        hours: Double,
        powerW: Double,
        pricePerKwh: Double,
        failurePercent: Double,
        markupPercent: Double,
    ): PrintCost {
        val material = grams / spoolGrams * spoolPrice
        val energyKwh = powerW / 1000.0 * hours
        val energy = energyKwh * pricePerKwh
        val before = material + energy
        val survival = (1.0 - failurePercent / 100.0).coerceAtLeast(0.01)
        val total = before / survival
        val sale = total * (1.0 + markupPercent / 100.0)
        return PrintCost(
            material = material,
            energy = energy,
            energyKwh = energyKwh,
            beforeFailures = before,
            total = total,
            perGram = if (grams > 0) total / grams else 0.0,
            salePrice = sale,
            profit = sale - total,
        )
    }
    fun resinCost(
        modelMl: Double,
        supportWastePercent: Double,
        pricePerLitre: Double,
        ipaMl: Double,
        ipaPricePerLitre: Double,
        glovePairs: Double,
        pricePerPair: Double,
        hours: Double,
        powerW: Double,
        pricePerKwh: Double,
    ): ResinCost {
        val resinMl = modelMl * (1.0 + supportWastePercent / 100.0)
        val resin = resinMl / 1000.0 * pricePerLitre
        val ipa = ipaMl / 1000.0 * ipaPricePerLitre
        val gloves = glovePairs * pricePerPair
        val energyKwh = powerW / 1000.0 * hours
        val energy = energyKwh * pricePerKwh
        return ResinCost(
            resinMl = resinMl,
            resin = resin,
            ipa = ipa,
            gloves = gloves,
            energy = energy,
            energyKwh = energyKwh,
            total = resin + ipa + gloves + energy,
        )
    }
    fun timeEstimate(
        volumeCm3: Double,
        layerMm: Double,
        widthMm: Double,
        speedMmS: Double,
        infillPercent: Double,
        walls: Int,
    ): PrintTimeEstimate {
        val sideMm = (volumeCm3 * 1000.0).pow(1.0 / 3.0)
        val shellMm = walls * widthMm
        val innerMm = (sideMm - 2 * shellMm).coerceAtLeast(0.0)
        val shellFraction = (1.0 - (innerMm / sideMm).pow(3)).coerceIn(0.0, 1.0)
        val solid = (shellFraction + (1.0 - shellFraction) * infillPercent / 100.0).coerceIn(0.0, 1.0)
        val materialCm3 = volumeCm3 * solid
        val flow = layerMm * widthMm * speedMmS * FLOW_EFFICIENCY
        return PrintTimeEstimate(
            shellFraction = shellFraction,
            solidFraction = solid,
            materialCm3 = materialCm3,
            flowMm3S = flow,
            seconds = if (flow > 0) materialCm3 * 1000.0 / flow else 0.0,
        )
    }
}