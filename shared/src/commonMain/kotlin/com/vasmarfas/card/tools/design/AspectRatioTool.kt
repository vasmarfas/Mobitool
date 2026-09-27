package com.vasmarfas.card.tools.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import kotlin.math.roundToInt

private enum class RatioMode { RATIO, DENSITY, PPI }

private enum class PxUnit { PX, DP, SP }

val aspectRatioTool = Tool(
    id = "aspect-ratio",
    category = ToolCategory.DESIGN,
    title = Res.string.aspect_ratio,
    description = Res.string.aspect_ratio_description,
    icon = Icons.Filled.AspectRatio,
    keywords = listOf("aspect", "ratio", "16:9", "resolution", "dp", "sp", "px", "ppi", "dpi", "density", "соотношение", "разрешение", "плотность", "экран"),
) { AspectRatioScreen() }

@Composable
private fun AspectRatioScreen() {
    var mode by rememberSaveable { mutableStateOf(RatioMode.RATIO) }
    SegmentedChoice(
        options = RatioMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = {
            when (it) {
                RatioMode.RATIO -> Res.string.ratio.str()
                RatioMode.DENSITY -> "px · dp · sp"
                RatioMode.PPI -> "PPI"
            }
        },
    )
    val saved = rememberSaveableStateHolder()
    saved.SaveableStateProvider(mode) {
        when (mode) {
            RatioMode.RATIO -> RatioSection()
            RatioMode.DENSITY -> DensitySection()
            RatioMode.PPI -> PpiSection()
        }
    }
}

@Composable
private fun RatioSection() {
    var widthText by rememberSaveable { mutableStateOf("1920") }
    var heightText by rememberSaveable { mutableStateOf("1080") }
    var newWidthText by rememberSaveable { mutableStateOf("1280") }
    var newHeightText by rememberSaveable { mutableStateOf("") }
    val width = widthText.trim().toIntOrNull()?.takeIf { it > 0 }
    val height = heightText.trim().toIntOrNull()?.takeIf { it > 0 }
    val newWidth = newWidthText.toDoubleLenient()?.takeIf { it > 0 }
    val newHeight = newHeightText.toDoubleLenient()?.takeIf { it > 0 }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumberField(
            value = widthText,
            onValueChange = { widthText = it },
            label = Res.string.width.str(),
            modifier = Modifier.weight(1f),
            isError = widthText.isNotBlank() && width == null,
        )
        NumberField(
            value = heightText,
            onValueChange = { heightText = it },
            label = Res.string.height.str(),
            modifier = Modifier.weight(1f),
            isError = heightText.isNotBlank() && height == null,
        )
    }
    if (width != null && height != null) {
        val (w, h) = Ratios.simplify(width, height)
        val ratio = width.toDouble() / height
        ResultCard {
            KeyValueRow(Res.string.ratio.str(), "$w:$h")
            KeyValueRow(Res.string.decimal.str(), "${ratio.fmt(4)}:1")
            KeyValueRow(Res.string.closest_common.str(), Ratios.closest(ratio) ?: "—", copyable = false)
            KeyValueRow(Res.string.pixels.str(), "${(width.toLong() * height / 1e6).fmt(2)} ${Res.string.unit_megapixel.str()}", copyable = false)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NumberField(
                value = newWidthText,
                onValueChange = { newWidthText = it },
                label = Res.string.new_width.str(),
                modifier = Modifier.weight(1f),
                isError = newWidthText.isNotBlank() && newWidth == null,
            )
            NumberField(
                value = newHeightText,
                onValueChange = { newHeightText = it },
                label = Res.string.new_height.str(),
                modifier = Modifier.weight(1f),
                isError = newHeightText.isNotBlank() && newHeight == null,
            )
        }
        if (newWidth != null || newHeight != null) {
            ResultCard(Res.string.scaled_keeping_the_ratio.str()) {
                if (newWidth != null) KeyValueRow("${newWidth.fmt(0)} × ?", "${newWidth.fmt(0)} × ${(newWidth / ratio).roundToInt()}")
                if (newHeight != null) KeyValueRow("? × ${newHeight.fmt(0)}", "${(newHeight * ratio).roundToInt()} × ${newHeight.fmt(0)}")
            }
        }
    }
}

@Composable
private fun DensitySection() {
    var valueText by rememberSaveable { mutableStateOf("48") }
    var unit by rememberSaveable { mutableStateOf(PxUnit.DP) }
    var densityText by rememberSaveable { mutableStateOf("3") }
    var fontScaleText by rememberSaveable { mutableStateOf("1") }
    val value = valueText.toDoubleLenient()
    val density = densityText.toDoubleLenient()?.takeIf { it > 0 }
    val fontScale = fontScaleText.toDoubleLenient()?.takeIf { it > 0 }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumberField(
            value = valueText,
            onValueChange = { valueText = it },
            label = Res.string.size.str(),
            modifier = Modifier.weight(1f),
            isError = valueText.isNotBlank() && value == null,
        )
        SegmentedChoice(
            options = PxUnit.entries,
            selected = unit,
            onSelect = { unit = it },
            label = { it.name.lowercase() },
            modifier = Modifier.weight(1f),
        )
    }
    NumberField(
        value = densityText,
        onValueChange = { densityText = it },
        label = Res.string.density_px_per_dp.str(),
        isError = densityText.isNotBlank() && density == null,
    )
    ChoiceChips(
        options = Ratios.densities,
        selected = Ratios.densities.firstOrNull { it.density == density },
        onSelect = { densityText = it.density.fmt(2) },
        label = { "${it.name} ×${it.density.fmt(2)}" },
    )
    NumberField(
        value = fontScaleText,
        onValueChange = { fontScaleText = it },
        label = Res.string.font_scale_for_sp.str(),
        isError = fontScaleText.isNotBlank() && fontScale == null,
    )
    if (value != null && density != null && fontScale != null) {
        val px = when (unit) {
            PxUnit.PX -> value
            PxUnit.DP -> Ratios.dpToPx(value, density)
            PxUnit.SP -> Ratios.spToPx(value, density, fontScale)
        }
        ResultCard {
            KeyValueRow("px", px.fmt(2))
            KeyValueRow("dp", Ratios.pxToDp(px, density).fmt(2))
            KeyValueRow("sp", Ratios.pxToSp(px, density, fontScale).fmt(2))
            KeyValueRow(Res.string.bucket.str(), "${Ratios.bucket(density)} · ${(density * 160).fmt(0)} dpi", copyable = false)
        }
    }
}

@Composable
private fun PpiSection() {
    var widthText by rememberSaveable { mutableStateOf("2400") }
    var heightText by rememberSaveable { mutableStateOf("1080") }
    var diagonalText by rememberSaveable { mutableStateOf("6.5") }
    val width = widthText.trim().toIntOrNull()?.takeIf { it > 0 }
    val height = heightText.trim().toIntOrNull()?.takeIf { it > 0 }
    val diagonal = diagonalText.toDoubleLenient()?.takeIf { it > 0 }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumberField(
            value = widthText,
            onValueChange = { widthText = it },
            label = Res.string.width.str(),
            modifier = Modifier.weight(1f),
            suffix = "px",
            isError = widthText.isNotBlank() && width == null,
        )
        NumberField(
            value = heightText,
            onValueChange = { heightText = it },
            label = Res.string.height.str(),
            modifier = Modifier.weight(1f),
            suffix = "px",
            isError = heightText.isNotBlank() && height == null,
        )
    }
    NumberField(
        value = diagonalText,
        onValueChange = { diagonalText = it },
        label = Res.string.diagonal.str(),
        suffix = Res.string.unit_in.str(),
        isError = diagonalText.isNotBlank() && diagonal == null,
    )
    if (width != null && height != null && diagonal != null) {
        val ppi = Ratios.ppi(width, height, diagonal)
        val density = ppi / 160
        val bucketDensity = Ratios.densities.first { it.name == Ratios.bucket(density) }.density
        ResultCard {
            KeyValueRow("PPI", ppi.fmt(1))
            KeyValueRow(Res.string.density.str(), "×${density.fmt(3)} · ${Ratios.bucket(density)}", copyable = false)
            KeyValueRow(Res.string.size_in_dp_bucket.str(), "${(width / bucketDensity).roundToInt()} × ${(height / bucketDensity).roundToInt()} dp")
            KeyValueRow(Res.string.physical_size.str(), "${(width / ppi * 2.54).fmt(1)} × ${(height / ppi * 2.54).fmt(1)} ${Res.string.unit_cm.str()}", copyable = false)
            KeyValueRow(Res.string.ratio.str(), Ratios.simplify(width, height).let { "${it.first}:${it.second}" }, copyable = false)
        }
    }
}
