package com.vasmarfas.card.tools.electronics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.TagChips
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class CodeDirection { DECODE, ENCODE }

private val bandCounts = listOf(4, 5, 6)

val resistorColorCodeTool = Tool(
    id = "resistor-color-code",
    category = ToolCategory.ELECTRONICS,
    title = Res.string.resistor_color_code,
    description = Res.string.resistor_color_code_description,
    icon = Icons.Filled.ElectricalServices,
    keywords = listOf("resistor", "color", "bands", "tolerance", "ohm", "резистор", "цвет", "полосы", "маркировка", "допуск", "номинал"),
) { ResistorColorCodeScreen() }

@Composable
private fun ResistorColorCodeScreen() {
    var direction by rememberSaveable { mutableStateOf(CodeDirection.DECODE) }
    var bandCount by rememberSaveable { mutableStateOf(4) }
    var digit1 by rememberSaveable { mutableStateOf(ResistorColor.YELLOW) }
    var digit2 by rememberSaveable { mutableStateOf(ResistorColor.VIOLET) }
    var digit3 by rememberSaveable { mutableStateOf(ResistorColor.BLACK) }
    var multiplier by rememberSaveable { mutableStateOf(ResistorColor.RED) }
    var tolerance by rememberSaveable { mutableStateOf(ResistorColor.GOLD) }
    var tempco by rememberSaveable { mutableStateOf(ResistorColor.BROWN) }
    var valueText by rememberSaveable { mutableStateOf("4.7k") }
    SegmentedChoice(
        options = CodeDirection.entries,
        selected = direction,
        onSelect = { direction = it },
        label = { if (it == CodeDirection.DECODE) Res.string.colours_value.str() else Res.string.value_colours.str() },
    )
    SegmentedChoice(
        options = bandCounts,
        selected = bandCount,
        onSelect = { bandCount = it },
        label = { pluralStringResource(Res.plurals.band_count, it, it) },
    )
    when (direction) {
        CodeDirection.DECODE -> {
            ColorChoice(Res.string.s_1st_digit.str(), ResistorCode.digitColors, digit1) { digit1 = it }
            ColorChoice(Res.string.s_2nd_digit.str(), ResistorCode.digitColors, digit2) { digit2 = it }
            if (bandCount >= 5) ColorChoice(Res.string.s_3rd_digit.str(), ResistorCode.digitColors, digit3) { digit3 = it }
            ColorChoice(Res.string.multiplier.str(), ResistorCode.multiplierColors, multiplier) { multiplier = it }
            ColorChoice(Res.string.tolerance.str(), ResistorCode.toleranceColors, tolerance) { tolerance = it }
            if (bandCount == 6) ColorChoice(Res.string.temperature_coefficient.str(), ResistorCode.tempcoColors, tempco) { tempco = it }
            val bands = buildList {
                add(digit1)
                add(digit2)
                if (bandCount >= 5) add(digit3)
                add(multiplier)
                add(tolerance)
                if (bandCount == 6) add(tempco)
            }
            ResistorImage(bands)
            ResistorCode.decode(bands)?.let { ValueCard(it) }
        }
        CodeDirection.ENCODE -> {
            val ohms = Si.parse(valueText)
            ToolInputField(
                value = valueText,
                onValueChange = { valueText = it },
                label = Res.string.resistance.str(),
                placeholder = "4.7k · 220 · 1M · 4R7",
                keyboardType = KeyboardType.Ascii,
                isError = valueText.isNotBlank() && ohms == null,
                monospace = true,
            )
            ColorChoice(Res.string.tolerance.str(), ResistorCode.toleranceColors, tolerance) { tolerance = it }
            if (bandCount == 6) ColorChoice(Res.string.temperature_coefficient.str(), ResistorCode.tempcoColors, tempco) { tempco = it }
            val bands = ohms?.let { ResistorCode.encode(it, bandCount, tolerance, tempco) }
            if (ohms != null && bands == null) {
                ErrorText(Res.string.resistor_value_cannot_be_shown.str())
            }
            if (bands != null) {
                ResistorImage(bands)
                TagChips(bands.map { it.title.str() })
                ResistorCode.decode(bands)?.let { ValueCard(it) }
            }
        }
    }
}

@Composable
private fun ColorChoice(label: String, options: List<ResistorColor>, selected: ResistorColor, onSelect: (ResistorColor) -> Unit) {
    DropdownChoice(
        options = options,
        selected = selected,
        onSelect = onSelect,
        label = label,
        text = { it.title.str() },
    )
}

@Composable
private fun ValueCard(value: ResistorValue) {
    val min = value.ohms * (1 - value.tolerance / 100)
    val max = value.ohms * (1 + value.tolerance / 100)
    ResultCard {
        KeyValueRow(Res.string.resistance.str(), Si.format(value.ohms, Res.string.unit_ohm.str()))
        KeyValueRow(Res.string.tolerance.str(), stringResource(Res.string.percent_value, "±" + value.tolerance.fmt(2)))
        KeyValueRow(Res.string.range.str(), "${Si.format(min, Res.string.unit_ohm.str())} – ${Si.format(max, Res.string.unit_ohm.str())}", copyable = false)
        if (value.tempco != null) KeyValueRow(Res.string.temperature_coefficient.str(), "${value.tempco} ppm/K")
    }
}

@Composable
fun ResistorImage(bands: List<ResistorColor>) {
    val toleranceIndex = ResistorCode.digitCount(bands.size) + 1
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(3.dp).background(Color(0xFF9E9E9E)))
        Row(
            modifier = Modifier
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFFE8D5B5))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            bands.forEachIndexed { index, band ->
                if (index == toleranceIndex) Spacer(Modifier.width(6.dp))
                if (band != ResistorColor.NONE) {
                    Box(
                        Modifier
                            .width(9.dp)
                            .fillMaxHeight()
                            .background(Color(band.argb))
                            .border(0.5.dp, Color(0x33000000)),
                    )
                }
            }
        }
        Box(Modifier.weight(1f).height(3.dp).background(Color(0xFF9E9E9E)))
    }
}
