package com.vasmarfas.card.tools.fitness

import com.vasmarfas.card.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitnessMathTest {
    @Test
    fun oneRepMaxFormulas() {
        val results = OneRepMax.all(100.0, 5).toMap()
        assertEquals(116.6667, results.getValue("Epley"), 0.0001)
        assertEquals(112.5, results.getValue("Brzycki"), 0.0001)
        assertEquals(113.7089, results.getValue("Lander"), 0.0001)
        assertEquals(117.4619, results.getValue("Lombardi"), 0.0001)
        assertEquals(112.5, results.getValue("O'Conner"), 0.0001)
        assertEquals(116.5825, results.getValue("Wathan"), 0.0001)
        assertEquals(114.9034, OneRepMax.average(100.0, 5), 0.0001)
        assertEquals(100.0, OneRepMax.all(100.0, 1).toMap().getValue("Brzycki"), 1e-9)
        assertEquals(80.0, OneRepMax.average(80.0, 1), 1e-9)
        assertTrue(OneRepMax.all(80.0, 1).all { it.second == 80.0 })
    }

    @Test
    fun percentTableFallsFromMaxToHalf() {
        val table = OneRepMax.percentTable(200.0)
        assertEquals(11, table.size)
        assertEquals(Triple(100, 200.0, 1), table.first())
        assertEquals(50, table.last().first)
        assertEquals(100.0, table.last().second, 1e-9)
        assertEquals(3, OneRepMax.repsAtPercent(90))
        assertEquals(10, OneRepMax.repsAtPercent(75))
    }

    @Test
    fun paceParsingAndRiegel() {
        assertEquals(3000.0, Pace.parseTime("50:00")!!, 1e-9)
        assertEquals(5025.0, Pace.parseTime("1:23:45")!!, 1e-9)
        assertEquals("1:23:45", Pace.formatTime(5025.0))
        assertEquals("5:00", Pace.formatPace(300.0))
        assertEquals(300.0, Pace.paceSeconds(3000.0, 10.0), 1e-9)
        assertEquals(12.0, Pace.speed(10.0, 3000.0), 1e-9)
        assertEquals(2501.92, Pace.riegel(1200.0, 5.0, 10.0), 0.01)

        val splits = Pace.splits(10.0, 300.0, 1.0)
        assertEquals(10, splits.size)
        assertEquals(3000.0, splits.last().third, 1e-9)
        assertEquals(11, Pace.splits(10.5, 300.0, 1.0).size)
    }

    @Test
    fun heartRateZonesByMaxAndKarvonen() {
        assertEquals(190.0, HeartRate.maxHr(MaxHrFormula.CLASSIC, 30), 1e-9)
        assertEquals(187.0, HeartRate.maxHr(MaxHrFormula.TANAKA, 30), 1e-9)
        assertEquals(179.6, HeartRate.maxHr(MaxHrFormula.GULATI, 30), 1e-9)
        assertEquals(130.0, HeartRate.reserve(190.0, 60.0), 1e-9)
        assertEquals(133.0, HeartRate.byMaxPercent(190.0, 70), 1e-9)
        assertEquals(151.0, HeartRate.karvonen(190.0, 60.0, 70), 1e-9)
        assertEquals(190.0, HeartRate.karvonen(190.0, 60.0, 100), 1e-9)
        assertEquals(5, hrZones.size)
    }

    @Test
    fun calorieBurnByMetAndHeartRate() {
        assertEquals(588.0, CalorieBurn.met(8.0, 70.0, 60.0), 1e-9)
        assertEquals(294.0, CalorieBurn.met(8.0, 70.0, 30.0), 1e-9)
        assertEquals(762.85, CalorieBurn.keytel(Sex.MALE, 140.0, 70.0, 30, 60.0), 0.01)
        assertEquals(0.0, CalorieBurn.keytel(Sex.MALE, 40.0, 70.0, 30, 60.0), 1e-9)
        assertTrue(activities.size >= 40)
    }

    @Test
    fun macroSplitLeavesCarbsAsRemainder() {
        val split = Macros.split(2600.0, -20.0, 78.0, 1.8, 0.9)
        assertEquals(2080.0, split.targetKcal, 1e-6)
        assertEquals(140.4, split.proteinG, 1e-6)
        assertEquals(561.6, split.proteinKcal, 1e-6)
        assertEquals(70.2, split.fatG, 1e-6)
        assertEquals(631.8, split.fatKcal, 1e-6)
        assertEquals(886.6, split.carbsKcal, 1e-6)
        assertEquals(221.65, split.carbsG, 1e-6)
        assertEquals(1.0, Macros.mealShares(5).sum(), 1e-9)
        assertEquals(4, Macros.mealShares(4).size)
    }

    @Test
    fun bodyMetricsRatiosAndFfmi() {
        assertEquals(0.4719, BodyMetrics.waistToHeight(84.0, 178.0), 0.0001)
        assertEquals("Healthy", BodyMetrics.waistToHeightNote(0.47).english())
        assertEquals("High risk", BodyMetrics.waistToHipNote(Sex.MALE, 1.02).english())
        assertEquals("Moderate risk", BodyMetrics.waistToHipNote(Sex.FEMALE, 0.82).english())
        assertEquals(1.8485, BodyMetrics.bsaDuBois(70.0, 175.0), 0.0005)
        assertEquals(1.8447, BodyMetrics.bsaMosteller(70.0, 175.0), 0.0005)

        val boer = BodyMetrics.leanMasses(Sex.MALE, 70.0, 175.0).first { it.name == "Boer" }.value
        assertEquals(56.015, boer, 1e-6)
        assertEquals(18.2906, BodyMetrics.ffmi(boer, 175.0), 0.0001)
        assertEquals(18.5956, BodyMetrics.ffmiNormalized(boer, 175.0), 0.0001)
        assertEquals(63.0, BodyMetrics.leanFromBodyFat(70.0, 10.0), 1e-9)
    }
}
