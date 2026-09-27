package com.vasmarfas.card.tools.printing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.network.SimpleTable
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private enum class ShrinkTab { SHRINK, CUBE }

private val typicalShrinkage = listOf(
    FilamentMaterial.PLA to 0.3,
    FilamentMaterial.PETG to 0.6,
    FilamentMaterial.ABS to 0.8,
    FilamentMaterial.ASA to 0.7,
    FilamentMaterial.TPU to 1.2,
    FilamentMaterial.NYLON to 1.5,
    FilamentMaterial.PC to 0.8,
    FilamentMaterial.PLA_WOOD to 0.3,
    FilamentMaterial.CARBON to 0.2,
    FilamentMaterial.PCTG to 0.5,
    FilamentMaterial.HIPS to 0.6,
    FilamentMaterial.PP to 1.8,
    FilamentMaterial.POM to 2.0,
    FilamentMaterial.PA12 to 1.2,
    FilamentMaterial.PC_ABS to 0.6,
    FilamentMaterial.PVDF to 2.0,
    FilamentMaterial.PPS to 0.7,
    FilamentMaterial.PEEK to 1.2,
    FilamentMaterial.PEI_9085 to 0.7,
    FilamentMaterial.TPU_85A to 1.5,
    FilamentMaterial.PETG_CF_20 to 0.3,
    FilamentMaterial.ABS_CF_20 to 0.4,
    FilamentMaterial.PA6_CF_20 to 0.4,
    FilamentMaterial.PA6_GF_30 to 0.5,
    FilamentMaterial.PC_CF_20 to 0.3,
    FilamentMaterial.PEEK_CF_30 to 0.3,
)

val shrinkageScaleTool = Tool(
    id = "shrinkage-scale",
    category = ToolCategory.PRINTING,
    title = Res.string.shrinkage_and_scale,
    description = Res.string.shrinkage_scale_description,
    icon = Icons.Filled.Scale,
    keywords = listOf("shrinkage", "scale", "calibration cube", "steps per mm", "усадка", "масштаб", "куб", "шаги", "калибровка"),
) { ShrinkageScaleScreen() }

@Composable
private fun ShrinkageScaleScreen() {
    var tab by rememberSaveable { mutableStateOf(ShrinkTab.SHRINK) }
    SegmentedChoice(
        options = ShrinkTab.entries,
        selected = tab,
        onSelect = { tab = it },
        label = { if (it == ShrinkTab.SHRINK) Res.string.shrinkage.str() else Res.string.cube.str() },
    )
    if (tab == ShrinkTab.SHRINK) ShrinkSection() else CubeSection()
}

@Composable
private fun ShrinkSection() {
    var shrinkText by rememberSaveable { mutableStateOf("0.3") }
    var nominalText by rememberSaveable { mutableStateOf("100") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = shrinkText,
            onValueChange = { shrinkText = it },
            label = Res.string.shrinkage.str(),
            modifier = Modifier.weight(1f),
            suffix = "%",
            isError = shrinkText.toDoubleLenient().let { it == null || it <= -100 || it >= 100 },
        )
        NumberField(
            value = nominalText,
            onValueChange = { nominalText = it },
            label = Res.string.nominal_dimension.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = nominalText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }
    val shrink = shrinkText.toDoubleLenient()
    val nominal = nominalText.toDoubleLenient()
    if (shrink == null || shrink <= -100 || shrink >= 100 || nominal == null || nominal <= 0) {
        ErrorText(Res.string.shrinkage_must_be_between_100.str())
        return
    }
    val factor = Shrinkage.scaleFactor(shrink)
    ResultCard {
        KeyValueRow(Res.string.scale_factor.str(), factor.fmt(5))
        KeyValueRow(Res.string.scale_in_the_slicer.str(), stringResource(Res.string.percent_value, (factor * 100).fmt(3)))
        KeyValueRow(Res.string.model_dimension.str(), "${(nominal * factor).fmt(3)} ${Res.string.unit_mm.str()}")
        KeyValueRow(Res.string.compensation.str(), "${(nominal * factor - nominal).fmt(3)} ${Res.string.unit_mm.str()}")
    }
    ToolSection(Res.string.typical_shrinkage.str()) {
        SimpleTable(
            header = listOf(
                Res.string.material.str(),
                Res.string.shrinkage.str(),
                Res.string.scale.str(),
            ),
            rows = typicalShrinkage.map { (m, s) ->
                listOf(m.title.str(), stringResource(Res.string.percent_value, s.fmt(2)), stringResource(Res.string.percent_value, (Shrinkage.scaleFactor(s) * 100).fmt(3)))
            },
            weights = listOf(1.5f, 1f, 1f),
            mono = false,
        )
    }
}

@Composable
private fun CubeSection() {
    var targetText by rememberSaveable { mutableStateOf("20") }
    var xText by rememberSaveable { mutableStateOf("20.1") }
    var yText by rememberSaveable { mutableStateOf("20.05") }
    var zText by rememberSaveable { mutableStateOf("19.95") }
    var stepsXText by rememberSaveable { mutableStateOf("80") }
    var stepsYText by rememberSaveable { mutableStateOf("80") }
    var stepsZText by rememberSaveable { mutableStateOf("400") }

    NumberField(
        value = targetText,
        onValueChange = { targetText = it },
        label = Res.string.nominal_cube_size.str(),
        suffix = Res.string.unit_mm.str(),
        isError = targetText.toDoubleLenient().let { it == null || it <= 0 },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(xText, { xText = it }, Res.string.measured_x.str(), Modifier.weight(1f), isError = xText.toDoubleLenient() == null)
        NumberField(yText, { yText = it }, Res.string.measured_y.str(), Modifier.weight(1f), isError = yText.toDoubleLenient() == null)
        NumberField(zText, { zText = it }, Res.string.measured_z.str(), Modifier.weight(1f), isError = zText.toDoubleLenient() == null)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(stepsXText, { stepsXText = it }, "X, ${Res.string.unit_steps_mm.str()}", Modifier.weight(1f), isError = stepsXText.toDoubleLenient() == null)
        NumberField(stepsYText, { stepsYText = it }, "Y, ${Res.string.unit_steps_mm.str()}", Modifier.weight(1f), isError = stepsYText.toDoubleLenient() == null)
        NumberField(stepsZText, { stepsZText = it }, "Z, ${Res.string.unit_steps_mm.str()}", Modifier.weight(1f), isError = stepsZText.toDoubleLenient() == null)
    }

    val target = targetText.toDoubleLenient()
    val measured = listOf(xText, yText, zText).map { it.toDoubleLenient() }
    val steps = listOf(stepsXText, stepsYText, stepsZText).map { it.toDoubleLenient() }
    if (target == null || target <= 0 || measured.any { it == null || it <= 0 } || steps.any { it == null || it <= 0 }) {
        ErrorText(Res.string.shrinkage_fill_in_every.str())
        return
    }
    val axes = listOf("X", "Y", "Z")
    val corrected = axes.indices.map { Shrinkage.correctedSteps(steps[it]!!, target, measured[it]!!) }
    val errors = axes.indices.map { Shrinkage.errorPercent(target, measured[it]!!) }
    ResultCard(Res.string.steps_correction.str()) {
        SimpleTable(
            header = listOf(
                Res.string.axis.str(),
                Res.string.shrinkage_measured.str(),
                Res.string.error.str(),
                Res.string.new_steps.str(),
            ),
            rows = axes.mapIndexed { i, axis ->
                listOf(axis, measured[i]!!.fmt(3), stringResource(Res.string.percent_value, errors[i].fmt(2)), corrected[i].fmt(3))
            },
            weights = listOf(0.6f, 1f, 1f, 1.2f),
        )
        MonoText("M92 X${corrected[0].fmt(3)} Y${corrected[1].fmt(3)} Z${corrected[2].fmt(3)}\nM500")
    }
    if (errors.maxOf { abs(it) } > 2.0) {
        ErrorText(
            Res.string.shrinkage_error_above_2.str(),
        )
    }
}
