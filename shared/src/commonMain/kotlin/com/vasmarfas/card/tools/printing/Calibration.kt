package com.vasmarfas.card.tools.printing

import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.resources.*
import kotlin.math.ceil
import kotlin.math.round
import org.jetbrains.compose.resources.StringResource

class LayerAdvice(
    val minLayer: Double,
    val maxLayer: Double,
    val recommendedWidth: Double,
    val minWidth: Double,
    val maxWidth: Double,
    val firstLayerHeight: Double,
    val firstLayerWidth: Double,
    val layerCount: Int,
    val exactHeight: Double,
    val warnings: List<StringResource>,
)

class TowerSegment(
    val index: Int,
    val firstLayer: Int,
    val lastLayer: Int,
    val zStart: Double,
    val zEnd: Double,
    val temperature: Int,
)

object LayerSettings {
    fun advice(nozzleMm: Double, layerMm: Double, targetHeightMm: Double): LayerAdvice {
        val minLayer = nozzleMm * 0.25
        val maxLayer = nozzleMm * 0.75
        val firstLayer = round(maxLayer * 50.0) / 50.0
        val count = if (targetHeightMm <= firstLayer) 1 else 1 + ceil((targetHeightMm - firstLayer) / layerMm - 1e-9).toInt()
        val exact = firstLayer + (count - 1) * layerMm
        val warnings = buildList {
            if (layerMm < minLayer) add(Res.string.layer_below_quarter_nozzle)
            if (layerMm > maxLayer) add(Res.string.layer_above_three_quarters_nozzle)
            if (layerMm < 0.04) add(Res.string.layer_below_z_resolution)
            if (targetHeightMm > 0 && exact - targetHeightMm > 1e-6) add(Res.string.layer_height_not_whole)
        }
        return LayerAdvice(
            minLayer = minLayer,
            maxLayer = maxLayer,
            recommendedWidth = nozzleMm * 1.2,
            minWidth = nozzleMm,
            maxWidth = nozzleMm * 1.5,
            firstLayerHeight = firstLayer,
            firstLayerWidth = nozzleMm * 1.4,
            layerCount = count,
            exactHeight = exact,
            warnings = warnings,
        )
    }
}

object Extrusion {
    fun newESteps(oldSteps: Double, requestedMm: Double, extrudedMm: Double): Double = oldSteps * requestedMm / extrudedMm

    fun newFlowPercent(currentPercent: Double, expectedMm: Double, measuredMm: Double): Double =
        currentPercent * expectedMm / measuredMm

    fun volumetricRate(layerMm: Double, widthMm: Double, speedMmS: Double): Double = layerMm * widthMm * speedMmS

    fun maxSpeed(maxRateMm3S: Double, layerMm: Double, widthMm: Double): Double = maxRateMm3S / (layerMm * widthMm)
}

object Shrinkage {
    fun scaleFactor(shrinkPercent: Double): Double = 100.0 / (100.0 - shrinkPercent)

    fun correctedSteps(oldSteps: Double, nominalMm: Double, measuredMm: Double): Double = oldSteps * nominalMm / measuredMm

    fun errorPercent(nominalMm: Double, measuredMm: Double): Double = (measuredMm - nominalMm) / nominalMm * 100.0
}

object TemperatureTower {
    fun plan(
        startC: Int,
        stepC: Int,
        layersPerSegment: Int,
        segments: Int,
        firstLayerMm: Double,
        layerMm: Double,
    ): List<TowerSegment> = (0 until segments).map { i ->
        val first = 1 + i * layersPerSegment
        val last = first + layersPerSegment - 1
        TowerSegment(
            index = i,
            firstLayer = first,
            lastLayer = last,
            zStart = firstLayerMm + (first - 1) * layerMm,
            zEnd = firstLayerMm + (last - 1) * layerMm,
            temperature = startC + i * stepC,
        )
    }

    fun slicerScript(segments: List<TowerSegment>): List<String> = segments.drop(1).map {
        "{if layer_num==${it.firstLayer - 1}}M104 S${it.temperature}{endif}"
    }

    fun marlinScript(segments: List<TowerSegment>): List<String> = segments.mapIndexed { i, s ->
        val command = if (i == 0) "M109 S${s.temperature}" else "M104 S${s.temperature}"
        "$command ; layer ${s.firstLayer}, Z=${s.zStart.fmt(2)}"
    }
}
