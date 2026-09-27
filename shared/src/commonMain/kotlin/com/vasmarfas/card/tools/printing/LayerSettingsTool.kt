package com.vasmarfas.card.tools.printing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
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
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import org.jetbrains.compose.resources.stringResource

private val commonNozzles = listOf(0.2, 0.3, 0.4, 0.6, 0.8, 1.0)

val layerSettingsTool = Tool(
    id = "layer-settings",
    category = ToolCategory.PRINTING,
    title = Res.string.layer_and_nozzle,
    description = Res.string.layer_settings_description,
    icon = Icons.Filled.Layers,
    keywords = listOf("layer height", "nozzle", "extrusion width", "first layer", "слой", "сопло", "ширина экструзии", "первый слой"),
) { LayerSettingsScreen() }

@Composable
private fun LayerSettingsScreen() {
    var nozzleText by rememberSaveable { mutableStateOf("0.4") }
    var layerText by rememberSaveable { mutableStateOf("0.2") }
    var heightText by rememberSaveable { mutableStateOf("20") }

    ChoiceChips(
        options = commonNozzles,
        selected = nozzleText.toDoubleLenient(),
        onSelect = { nozzleText = it.fmt(2) },
        label = { "${it.fmt(2)} ${Res.string.unit_mm.str()}" },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = nozzleText,
            onValueChange = { nozzleText = it },
            label = Res.string.nozzle_diameter.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_mm.str(),
            isError = nozzleText.toDoubleLenient().let { it == null || it <= 0 },
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
    NumberField(
        value = heightText,
        onValueChange = { heightText = it },
        label = Res.string.model_height.str(),
        suffix = Res.string.unit_mm.str(),
        isError = heightText.toDoubleLenient().let { it == null || it < 0 },
    )

    val nozzle = nozzleText.toDoubleLenient()
    val layer = layerText.toDoubleLenient()
    val height = heightText.toDoubleLenient()
    if (nozzle == null || nozzle <= 0 || layer == null || layer <= 0 || height == null || height < 0) {
        ErrorText(Res.string.layer_nozzle_and_layer.str())
        return
    }

    val advice = LayerSettings.advice(nozzle, layer, height)
    ResultCard(Res.string.recommended.str()) {
        KeyValueRow(
            Res.string.layer_height_range.str(),
            "${advice.minLayer.fmt(3)} – ${advice.maxLayer.fmt(3)} ${Res.string.unit_mm.str()}",
        )
        KeyValueRow(Res.string.extrusion_width.str(), "${advice.recommendedWidth.fmt(2)} ${Res.string.unit_mm.str()}")
        KeyValueRow(
            Res.string.width_range.str(),
            "${advice.minWidth.fmt(2)} – ${advice.maxWidth.fmt(2)} ${Res.string.unit_mm.str()}",
        )
        KeyValueRow(Res.string.first_layer_height.str(), "${advice.firstLayerHeight.fmt(2)} ${Res.string.unit_mm.str()}")
        KeyValueRow(Res.string.first_layer_width.str(), "${advice.firstLayerWidth.fmt(2)} ${Res.string.unit_mm.str()}")
        KeyValueRow(Res.string.layer_nozzle.str(), stringResource(Res.string.percent_value, (layer / nozzle * 100).fmt(1)))
    }
    ResultCard(Res.string.for_this_model.str()) {
        KeyValueRow(Res.string.layers.str(), advice.layerCount.toString())
        KeyValueRow(Res.string.real_height.str(), "${advice.exactHeight.fmt(3)} ${Res.string.unit_mm.str()}")
    }
    advice.warnings.forEach { ErrorText(stringResource(it, advice.exactHeight.fmt(3))) }
    ResultCard(Res.string.layer_height_limits_per_nozzle.str()) {
        SimpleTable(
            header = listOf(
                Res.string.nozzle.str(),
                Res.string.min_2.str(),
                Res.string.max.str(),
                Res.string.width.str(),
            ),
            rows = commonNozzles.map {
                listOf(it.fmt(2), (it * 0.25).fmt(3), (it * 0.75).fmt(3), (it * 1.2).fmt(2))
            },
            weights = listOf(1f, 1f, 1f, 1f),
        )
    }
}
