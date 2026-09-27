package com.vasmarfas.card.tools.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection

val typographyScaleTool = Tool(
    id = "typography-scale",
    category = ToolCategory.DESIGN,
    title = Res.string.typography_scale,
    description = Res.string.typography_scale_description,
    icon = Icons.Filled.TextFormat,
    keywords = listOf("typography", "type scale", "font size", "line height", "modular scale", "типографика", "шкала", "кегль", "интерлиньяж"),
) { TypographyScaleScreen() }

@Composable
private fun TypographyScaleScreen() {
    var edited by rememberSaveable { mutableStateOf<String?>(null) }
    val sample = edited ?: Res.string.the_quick_brown_fox_jumps.str()
    ToolInputField(
        value = sample,
        onValueChange = { edited = it },
        label = Res.string.sample_text.str(),
    )
    val t = MaterialTheme.typography
    val styles = listOf(
        "displayLarge" to t.displayLarge,
        "displayMedium" to t.displayMedium,
        "displaySmall" to t.displaySmall,
        "headlineLarge" to t.headlineLarge,
        "headlineMedium" to t.headlineMedium,
        "headlineSmall" to t.headlineSmall,
        "titleLarge" to t.titleLarge,
        "titleMedium" to t.titleMedium,
        "titleSmall" to t.titleSmall,
        "bodyLarge" to t.bodyLarge,
        "bodyMedium" to t.bodyMedium,
        "bodySmall" to t.bodySmall,
        "labelLarge" to t.labelLarge,
        "labelMedium" to t.labelMedium,
        "labelSmall" to t.labelSmall,
    )
    ResultCard(Res.string.material_3_styles.str()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            styles.forEach { (name, style) -> StyleRow(name, style, sample) }
        }
    }

    ToolSection(Res.string.modular_scale.str()) {
        var baseText by rememberSaveable { mutableStateOf("16") }
        var ratio by rememberSaveable { mutableStateOf(ScaleRatio.MAJOR_THIRD) }
        NumberField(baseText, { baseText = it }, Res.string.base_size.str(), suffix = "sp")
        DropdownChoice(
            options = ScaleRatio.entries,
            selected = ratio,
            onSelect = { ratio = it },
            label = Res.string.type_scale_ratio.str(),
            text = { "${it.label} · ${it.value}" },
        )
        val base = baseText.toDoubleLenient()
        if (base == null || base <= 0) {
            ErrorText(Res.string.type_scale_base_size.str())
        } else {
            val steps = remember(base, ratio) { ModularScale.steps(base, ratio.value) }
            ResultCard {
                steps.forEach { (step, size) ->
                    KeyValueRow(
                        if (step == 0) "${Res.string.base.str()} (0)" else (if (step > 0) "+$step" else step.toString()),
                        "${size.fmt(2)} sp · ${Res.string.type_scale_line_height.str()} ${ModularScale.lineHeight(size).fmt(1)} sp",
                    )
                }
            }
        }
    }
}

@Composable
private fun StyleRow(name: String, style: TextStyle, sample: String) {
    Column {
        Text(
            text = "$name · ${style.fontSize.value.toDouble().fmt(0)} sp · ${style.lineHeight.value.toDouble().fmt(0)} sp · w${style.fontWeight?.weight ?: 400}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = sample, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
