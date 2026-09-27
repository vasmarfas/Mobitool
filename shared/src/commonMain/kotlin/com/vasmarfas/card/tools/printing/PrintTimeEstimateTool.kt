package com.vasmarfas.card.tools.printing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.formatDurationMs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import org.jetbrains.compose.resources.stringResource

val printTimeEstimateTool = Tool(
    id = "print-time-estimate",
    category = ToolCategory.PRINTING,
    title = Res.string.print_time_estimate,
    description = Res.string.print_time_estimate_description,
    icon = Icons.Filled.Schedule,
    keywords = listOf("print time", "estimate", "infill", "walls", "speed", "время печати", "оценка", "заполнение", "стенки", "скорость"),
) { PrintTimeEstimateScreen() }

@Composable
private fun PrintTimeEstimateScreen() {
    var volumeText by rememberSaveable { mutableStateOf("60") }
    var layerText by rememberSaveable { mutableStateOf("0.2") }
    var widthText by rememberSaveable { mutableStateOf("0.45") }
    var speedText by rememberSaveable { mutableStateOf("80") }
    var infillText by rememberSaveable { mutableStateOf("20") }
    var wallsText by rememberSaveable { mutableStateOf("3") }

    Text(
        text = Res.string.print_time_rough_estimate.str(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NumberField(
        value = volumeText,
        onValueChange = { volumeText = it },
        label = Res.string.model_volume.str(),
        suffix = Res.string.unit_cm3.str(),
        isError = volumeText.toDoubleLenient().let { it == null || it <= 0 },
    )
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
            value = infillText,
            onValueChange = { infillText = it },
            label = Res.string.infill.str(),
            modifier = Modifier.weight(1f),
            suffix = "%",
            isError = infillText.toDoubleLenient().let { it == null || it < 0 || it > 100 },
        )
    }
    NumberField(
        value = wallsText,
        onValueChange = { wallsText = it },
        label = Res.string.wall_count.str(),
        isError = wallsText.trim().toIntOrNull().let { it == null || it < 1 },
    )

    val volume = volumeText.toDoubleLenient()
    val layer = layerText.toDoubleLenient()
    val width = widthText.toDoubleLenient()
    val speed = speedText.toDoubleLenient()
    val infill = infillText.toDoubleLenient()
    val walls = wallsText.trim().toIntOrNull()
    if (volume == null || volume <= 0 || layer == null || layer <= 0 || width == null || width <= 0 ||
        speed == null || speed <= 0 || infill == null || infill < 0 || infill > 100 || walls == null || walls < 1
    ) {
        ErrorText(Res.string.print_time_check_the_values_infill.str())
        return
    }

    val estimate = PrintEconomics.timeEstimate(volume, layer, width, speed, infill, walls)
    val plaGrams = estimate.materialCm3 * FilamentMaterial.PLA.density
    ResultCard {
        KeyValueRow(Res.string.estimated_time.str(), formatDurationMs((estimate.seconds * 1000).toLong()))
        KeyValueRow(Res.string.material_volume.str(), "${estimate.materialCm3.fmt(2)} ${Res.string.unit_cm3.str()}")
        KeyValueRow(Res.string.weight_in_pla.str(), "${plaGrams.fmt(1)} ${Res.string.unit_g.str()}")
        KeyValueRow(Res.string.filament_1_75.str(), "${Filament.lengthFromVolumeM(estimate.materialCm3, 1.75).fmt(2)} ${Res.string.unit_m.str()}")
        KeyValueRow(Res.string.solid_fraction.str(), stringResource(Res.string.percent_value, (estimate.solidFraction * 100).fmt(1)))
        KeyValueRow(Res.string.shell_share.str(), stringResource(Res.string.percent_value, (estimate.shellFraction * 100).fmt(1)))
        KeyValueRow(Res.string.effective_flow.str(), "${estimate.flowMm3S.fmt(2)} ${Res.string.unit_mm3_s.str()}")
    }
}
