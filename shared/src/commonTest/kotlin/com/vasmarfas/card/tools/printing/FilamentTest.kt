package com.vasmarfas.card.tools.printing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilamentTest {
    @Test
    fun gramsAndMetresRoundTrip() {
        val density = FilamentMaterial.PLA.density
        assertEquals(2.9825, Filament.gramsPerMetre(1.75, density), 0.0005)
        assertEquals(7.9105, Filament.gramsPerMetre(2.85, density), 0.0005)
        val grams = Filament.grams(12.5, 1.75, density)
        assertEquals(12.5, Filament.lengthM(grams, 1.75, density), 1e-9)
        assertEquals(grams / density, Filament.volumeCm3(12.5, 1.75), 1e-9)
    }

    @Test
    fun printCostSplitsMaterialAndEnergy() {
        val cost = PrintEconomics.printCost(
            grams = 100.0,
            spoolPrice = 1500.0,
            spoolGrams = 1000.0,
            hours = 4.0,
            powerW = 100.0,
            pricePerKwh = 5.0,
            failurePercent = 10.0,
            markupPercent = 100.0,
        )
        assertEquals(150.0, cost.material, 1e-9)
        assertEquals(0.4, cost.energyKwh, 1e-9)
        assertEquals(2.0, cost.energy, 1e-9)
        assertEquals(152.0, cost.beforeFailures, 1e-9)
        assertEquals(152.0 / 0.9, cost.total, 1e-9)
        assertEquals(cost.total / 100.0, cost.perGram, 1e-9)
        assertEquals(cost.total * 2, cost.salePrice, 1e-9)
        val smallSpool = PrintEconomics.printCost(42.0, 1500.0, 750.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals(84.0, smallSpool.material, 1e-9)
    }

    @Test
    fun resinCostAddsWasteAndConsumables() {
        val cost = PrintEconomics.resinCost(
            modelMl = 40.0,
            supportWastePercent = 25.0,
            pricePerLitre = 3000.0,
            ipaMl = 100.0,
            ipaPricePerLitre = 400.0,
            glovePairs = 1.0,
            pricePerPair = 20.0,
            hours = 5.0,
            powerW = 60.0,
            pricePerKwh = 6.0,
        )
        assertEquals(50.0, cost.resinMl, 1e-9)
        assertEquals(150.0, cost.resin, 1e-9)
        assertEquals(40.0, cost.ipa, 1e-9)
        assertEquals(1.8, cost.energy, 1e-9)
        assertEquals(211.8, cost.total, 1e-9)
    }

    @Test
    fun timeEstimateGrowsWithInfill() {
        val sparse = PrintEconomics.timeEstimate(60.0, 0.2, 0.45, 80.0, 20.0, 3)
        val solid = PrintEconomics.timeEstimate(60.0, 0.2, 0.45, 80.0, 100.0, 3)
        assertEquals(5.4, sparse.flowMm3S, 1e-9)
        assertEquals(1.0, solid.solidFraction, 1e-9)
        assertEquals(60.0, solid.materialCm3, 1e-9)
        assertTrue(sparse.solidFraction in 0.3..0.4)
        assertTrue(sparse.seconds < solid.seconds)
    }
}
