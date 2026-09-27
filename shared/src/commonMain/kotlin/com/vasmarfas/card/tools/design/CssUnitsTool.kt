package com.vasmarfas.card.tools.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
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
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import org.jetbrains.compose.resources.stringResource

private enum class CssUnitInput { PX, REM, EM, PT }

val cssUnitsTool = Tool(
    id = "css-units",
    category = ToolCategory.DESIGN,
    title = Res.string.css_units,
    description = Res.string.css_units_description,
    icon = Icons.Filled.Straighten,
    keywords = listOf("px", "rem", "em", "pt", "dp", "dpi", "css", "единицы", "пиксели", "плотность"),
) { CssUnitsScreen() }

@Composable
private fun CssUnitsScreen() {
    var unit by rememberSaveable { mutableStateOf(CssUnitInput.PX) }
    var valueText by rememberSaveable { mutableStateOf("16") }
    var rootText by rememberSaveable { mutableStateOf("16") }
    var parentText by rememberSaveable { mutableStateOf("16") }
    SegmentedChoice(
        options = CssUnitInput.entries,
        selected = unit,
        onSelect = { unit = it },
        label = { it.name.lowercase() },
    )
    NumberField(valueText, { valueText = it }, Res.string.value_.str(), suffix = unit.name.lowercase())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(rootText, { rootText = it }, Res.string.root_font_size.str(), Modifier.weight(1f), suffix = "px")
        NumberField(parentText, { parentText = it }, Res.string.parent_font_size.str(), Modifier.weight(1f), suffix = "px")
    }
    val value = valueText.toDoubleLenient()
    val root = rootText.toDoubleLenient()
    val parent = parentText.toDoubleLenient()
    if (value == null || root == null || parent == null || root <= 0 || parent <= 0) {
        ErrorText(Res.string.css_enter_numbers_root.str())
        return
    }
    val px = when (unit) {
        CssUnitInput.PX -> value
        CssUnitInput.REM -> CssUnits.remToPx(value, root)
        CssUnitInput.EM -> CssUnits.remToPx(value, parent)
        CssUnitInput.PT -> CssUnits.ptToPx(value)
    }
    ResultCard {
        KeyValueRow("px", px.fmt(4))
        KeyValueRow("rem", "${CssUnits.pxToRem(px, root).fmt(4)} ${stringResource(Res.string.css_of_root, root.fmt(2))}")
        KeyValueRow("em", "${CssUnits.pxToRem(px, parent).fmt(4)} ${stringResource(Res.string.css_of_parent, parent.fmt(2))}")
        KeyValueRow("pt", CssUnits.pxToPt(px).fmt(4))
        KeyValueRow("%", "${CssUnits.pxToPercent(px, parent).fmt(2)} %")
    }
    ToolSection(Res.string.android_dp_px.str()) {
        ResultCard {
            AndroidDensity.entries.forEach { density ->
                KeyValueRow(density.label, "${CssUnits.dpToPx(px, density).fmt(2)} px")
            }
        }
    }
}
