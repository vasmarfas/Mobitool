package com.vasmarfas.card.tools.printing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import org.jetbrains.compose.resources.stringResource

private enum class FlowTab { ESTEPS, FLOW, VOLUMETRIC }

val flowAndEstepsTool = Tool(
    id = "flow-and-esteps",
    category = ToolCategory.PRINTING,
    title = Res.string.flow_and_e_steps,
    description = Res.string.flow_and_esteps_description,
    icon = Icons.Filled.Speed,
    keywords = listOf("esteps", "e-steps", "flow", "extrusion multiplier", "m92", "calibration", "калибровка", "поток", "экструдер", "стенка"),
) { FlowAndEstepsScreen() }

@Composable
private fun FlowAndEstepsScreen() {
    var tab by rememberSaveable { mutableStateOf(FlowTab.ESTEPS) }
    SegmentedChoice(
        options = FlowTab.entries,
        selected = tab,
        onSelect = { tab = it },
        label = {
            when (it) {
                FlowTab.ESTEPS -> "E-steps"
                FlowTab.FLOW -> Res.string.flow.str()
                FlowTab.VOLUMETRIC -> Res.string.limit.str()
            }
        },
    )
    val saved = rememberSaveableStateHolder()
    saved.SaveableStateProvider(tab) {
        when (tab) {
            FlowTab.ESTEPS -> EstepsSection()
            FlowTab.FLOW -> FlowSection()
            FlowTab.VOLUMETRIC -> VolumetricSection()
        }
    }
}

@Composable
private fun EstepsSection() {
    var oldStepsText by rememberSaveable { mutableStateOf("93") }
    var requestedText by rememberSaveable { mutableStateOf("100") }
    var extrudedText by rememberSaveable { mutableStateOf("97.5") }

    NumberField(
        value = oldStepsText,
        onValueChange = { oldStepsText = it },
        label = Res.string.current_e_steps.str(),
        suffix = Res.string.unit_steps_mm.str(),
        isError = oldStepsText.toDoubleLenient().let { it == null || it <= 0 },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = requestedText,
            onValueChange = { requestedText = it },
            label = Res.string.asked_to_extrude.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = requestedText.toDoubleLenient().let { it == null || it <= 0 },
        )
        NumberField(
            value = extrudedText,
            onValueChange = { extrudedText = it },
            label = Res.string.actually_extruded.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = extrudedText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }
    val old = oldStepsText.toDoubleLenient()
    val requested = requestedText.toDoubleLenient()
    val extruded = extrudedText.toDoubleLenient()
    if (old == null || old <= 0 || requested == null || requested <= 0 || extruded == null || extruded <= 0) {
        ErrorText(Res.string.flow_all_three_values.str())
        return
    }
    val steps = Extrusion.newESteps(old, requested, extruded)
    val error = (requested - extruded) / requested * 100.0
    ResultCard {
        KeyValueRow(Res.string.new_e_steps.str(), steps.fmt(3))
        KeyValueRow(Res.string.correction.str(), stringResource(Res.string.percent_value, error.fmt(2)))
        MonoText("M92 E${steps.fmt(3)}\nM500")
    }
    if (error < 0) {
        ErrorText(
            Res.string.flow_extruder_pushed_more.str(),
        )
    }
}

@Composable
private fun FlowSection() {
    var currentFlowText by rememberSaveable { mutableStateOf("100") }
    var expectedText by rememberSaveable { mutableStateOf("0.8") }
    var measuredText by rememberSaveable { mutableStateOf("0.84") }

    NumberField(
        value = currentFlowText,
        onValueChange = { currentFlowText = it },
        label = Res.string.current_flow.str(),
        suffix = "%",
        isError = currentFlowText.toDoubleLenient().let { it == null || it <= 0 },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = expectedText,
            onValueChange = { expectedText = it },
            label = Res.string.expected_wall.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = expectedText.toDoubleLenient().let { it == null || it <= 0 },
        )
        NumberField(
            value = measuredText,
            onValueChange = { measuredText = it },
            label = Res.string.measured_wall.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = measuredText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }
    val current = currentFlowText.toDoubleLenient()
    val expected = expectedText.toDoubleLenient()
    val measured = measuredText.toDoubleLenient()
    if (current == null || current <= 0 || expected == null || expected <= 0 || measured == null || measured <= 0) {
        ErrorText(Res.string.flow_all_three_values.str())
        return
    }
    val flow = Extrusion.newFlowPercent(current, expected, measured)
    ResultCard {
        KeyValueRow(Res.string.new_flow.str(), stringResource(Res.string.percent_value, flow.fmt(2)))
        KeyValueRow(Res.string.extrusion_multiplier.str(), (flow / 100.0).fmt(4))
        KeyValueRow(Res.string.wall_deviation.str(), stringResource(Res.string.percent_value, ((measured - expected) / expected * 100).fmt(2)))
        MonoText("M221 S${flow.fmt(1)}")
    }
    if (flow < 85 || flow > 115) {
        ErrorText(
            Res.string.flow_correction_over_15_usually.str(),
        )
    }
}

@Composable
private fun VolumetricSection() {
    var layerText by rememberSaveable { mutableStateOf("0.2") }
    var widthText by rememberSaveable { mutableStateOf("0.45") }
    var speedText by rememberSaveable { mutableStateOf("120") }
    var maxRateText by rememberSaveable { mutableStateOf("12") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = layerText,
            onValueChange = { layerText = it },
            label = Res.string.layer_height.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = layerText.toDoubleLenient().let { it == null || it <= 0 },
        )
        NumberField(
            value = widthText,
            onValueChange = { widthText = it },
            label = Res.string.extrusion_width.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = widthText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = speedText,
            onValueChange = { speedText = it },
            label = Res.string.print_speed.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm_s.str(),
            isError = speedText.toDoubleLenient().let { it == null || it <= 0 },
        )
        NumberField(
            value = maxRateText,
            onValueChange = { maxRateText = it },
            label = Res.string.hotend_limit.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm3_s.str(),
            isError = maxRateText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }
    val layer = layerText.toDoubleLenient()
    val width = widthText.toDoubleLenient()
    val speed = speedText.toDoubleLenient()
    val maxRate = maxRateText.toDoubleLenient()
    if (layer == null || layer <= 0 || width == null || width <= 0 || speed == null || speed <= 0 || maxRate == null || maxRate <= 0) {
        ErrorText(Res.string.flow_all_four_values.str())
        return
    }
    val rate = Extrusion.volumetricRate(layer, width, speed)
    ResultCard {
        KeyValueRow(Res.string.required_flow.str(), "${rate.fmt(2)} ${Res.string.unit_mm3_s.str()}")
        KeyValueRow(Res.string.headroom.str(), "${(maxRate - rate).fmt(2)} ${Res.string.unit_mm3_s.str()}")
        KeyValueRow(Res.string.flow_load.str(), stringResource(Res.string.percent_value, (rate / maxRate * 100).fmt(1)))
        KeyValueRow(Res.string.max_speed_at_this_limit.str(), "${Extrusion.maxSpeed(maxRate, layer, width).fmt(1)} ${Res.string.unit_mm_s.str()}")
    }
    if (rate > maxRate) {
        ErrorText(
            Res.string.flow_hotend_cannot_melt.str(),
        )
    }
}
