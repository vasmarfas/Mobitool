package com.vasmarfas.card.tools.printing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Thermostat
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
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoText
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import org.jetbrains.compose.resources.stringResource

val temperatureTowerTool = Tool(
    id = "temperature-tower",
    category = ToolCategory.PRINTING,
    title = Res.string.temperature_tower,
    description = Res.string.temperature_tower_description,
    icon = Icons.Filled.Thermostat,
    keywords = listOf("temperature tower", "calibration", "m104", "m109", "layer change", "башня", "температура", "калибровка", "смена слоя"),
) { TemperatureTowerScreen() }

@Composable
private fun TemperatureTowerScreen() {
    var material by rememberSaveable { mutableStateOf(FilamentMaterial.PLA) }
    var startText by rememberSaveable { mutableStateOf(FilamentMaterial.PLA.nozzleC.last.toString()) }
    var stepText by rememberSaveable { mutableStateOf("-5") }
    var layersText by rememberSaveable { mutableStateOf("40") }
    var segmentsText by rememberSaveable { mutableStateOf("7") }
    var firstLayerText by rememberSaveable { mutableStateOf("0.3") }
    var layerText by rememberSaveable { mutableStateOf("0.2") }

    DropdownChoice(
        options = FilamentMaterial.entries,
        selected = material,
        onSelect = {
            material = it
            startText = it.nozzleC.last.toString()
        },
        label = Res.string.material.str(),
        text = { "${it.title.str()} · ${it.nozzleC.first}–${it.nozzleC.last} °C" },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = startText,
            onValueChange = { startText = it },
            label = Res.string.start_temperature.str(),
            modifier = Modifier.weight(1f),
            suffix = "°C",
            isError = startText.trim().toIntOrNull() == null,
        )
        NumberField(
            value = stepText,
            onValueChange = { stepText = it },
            label = Res.string.step.str(),
            modifier = Modifier.weight(1f),
            suffix = "°C",
            isError = stepText.trim().toIntOrNull().let { it == null || it == 0 },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = layersText,
            onValueChange = { layersText = it },
            label = Res.string.layers_per_segment.str(),
            modifier = Modifier.weight(1f),
            isError = layersText.trim().toIntOrNull().let { it == null || it <= 0 },
        )
        NumberField(
            value = segmentsText,
            onValueChange = { segmentsText = it },
            label = Res.string.segments.str(),
            modifier = Modifier.weight(1f),
            isError = segmentsText.trim().toIntOrNull().let { it == null || it !in 2..30 },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = firstLayerText,
            onValueChange = { firstLayerText = it },
            label = Res.string.first_layer_height.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = firstLayerText.toDoubleLenient().let { it == null || it <= 0 },
        )
        NumberField(
            value = layerText,
            onValueChange = { layerText = it },
            label = Res.string.layer_height.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = layerText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }

    val start = startText.trim().toIntOrNull()
    val step = stepText.trim().toIntOrNull()
    val layers = layersText.trim().toIntOrNull()
    val segments = segmentsText.trim().toIntOrNull()
    val firstLayer = firstLayerText.toDoubleLenient()
    val layer = layerText.toDoubleLenient()
    if (start == null || step == null || step == 0 || layers == null || layers <= 0 ||
        segments == null || segments !in 2..30 || firstLayer == null || firstLayer <= 0 || layer == null || layer <= 0
    ) {
        ErrorText(Res.string.tower_check_the_values_2.str())
        return
    }

    val plan = TemperatureTower.plan(start, step, layers, segments, firstLayer, layer)
    val outOfRange = plan.filter { it.temperature !in material.nozzleC }
    ResultCard(Res.string.tower.str()) {
        KeyValueRow(
            Res.string.temperature_range.str(),
            "${plan.minOf { it.temperature }} — ${plan.maxOf { it.temperature }} °C",
        )
        KeyValueRow(Res.string.total_height.str(), "${plan.last().zEnd.fmt(2)} ${Res.string.unit_mm.str()}")
        KeyValueRow(Res.string.total_layers.str(), plan.last().lastLayer.toString())
        KeyValueRow(Res.string.bed.str(), "${material.bedC.first}–${material.bedC.last} °C")
    }
    ResultCard(Res.string.tower_segments.str()) {
        SimpleTable(
            header = listOf(
                "#",
                Res.string.tower_layers.str(),
                "Z, ${Res.string.unit_mm.str()}",
                "°C",
            ),
            rows = plan.map {
                listOf(
                    (it.index + 1).toString(),
                    "${it.firstLayer}–${it.lastLayer}",
                    "${it.zStart.fmt(2)}–${it.zEnd.fmt(2)}",
                    it.temperature.toString(),
                )
            },
            weights = listOf(0.5f, 1f, 1.3f, 0.7f),
        )
    }
    if (outOfRange.isNotEmpty()) {
        val materialTitle = material.title.str()
        ErrorText(
            stringResource(
                Res.string.tower_segments_out_of_range,
                materialTitle,
                "${material.nozzleC.first}–${material.nozzleC.last}",
                outOfRange.joinToString(", ") { it.temperature.toString() },
            ),
        )
    }

    val slicer = TemperatureTower.slicerScript(plan).joinToString("\n")
    val marlin = TemperatureTower.marlinScript(plan).joinToString("\n")
    ResultCard(Res.string.after_layer_change_g_code.str()) {
        MonoText(slicer)
        CopyIconButton(slicer)
    }
    ResultCard(Res.string.plain_commands.str()) {
        MonoText(marlin)
        CopyIconButton(marlin)
    }
}
