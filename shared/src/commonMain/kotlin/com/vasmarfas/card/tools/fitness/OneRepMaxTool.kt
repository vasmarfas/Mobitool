package com.vasmarfas.card.tools.fitness

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
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import org.jetbrains.compose.resources.stringResource

val oneRepMaxTool = Tool(
    id = "one-rep-max",
    category = ToolCategory.FITNESS,
    title = Res.string.one_rep_max,
    description = Res.string.one_rep_max_description,
    icon = Icons.Filled.Scale,
    keywords = listOf("1rm", "one rep max", "epley", "brzycki", "strength", "рм", "разовый максимум", "эпли", "сила", "проценты"),
) { OneRepMaxScreen() }

@Composable
private fun OneRepMaxScreen() {
    var weightText by rememberSaveable { mutableStateOf("80") }
    var repsText by rememberSaveable { mutableStateOf("5") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = weightText,
            onValueChange = { weightText = it },
            label = Res.string.weight.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_kg.str(),
            isError = weightText.toDoubleLenient().let { it == null || it <= 0 },
        )
        NumberField(
            value = repsText,
            onValueChange = { repsText = it },
            label = Res.string.reps.str(),
            modifier = Modifier.weight(1f),
            isError = repsText.trim().toIntOrNull().let { it == null || it < 1 || it > 30 },
        )
    }
    ChoiceChips(
        options = listOf(1, 3, 5, 8, 10, 12),
        selected = repsText.trim().toIntOrNull(),
        onSelect = { repsText = it.toString() },
        label = { "$it" },
    )

    val weight = weightText.toDoubleLenient()
    val reps = repsText.trim().toIntOrNull()
    if (weight == null || weight <= 0 || reps == null || reps < 1 || reps > 30) {
        ErrorText(Res.string.one_rep_weight_above_zero_reps.str())
        return
    }

    val results = OneRepMax.all(weight, reps)
    val average = OneRepMax.average(weight, reps)
    ResultCard(Res.string.estimated_1rm.str()) {
        KeyValueRow(Res.string.one_rep_average.str(), "${average.fmt(1)} ${Res.string.unit_kg.str()}")
        KeyValueRow(Res.string.spread.str(), "${results.minOf { it.second }.fmt(1)} – ${results.maxOf { it.second }.fmt(1)} ${Res.string.unit_kg.str()}")
        SimpleTable(
            header = listOf(Res.string.formula.str(), Res.string.one_rep_max_short.str(), "%"),
            rows = results.map { (name, value) ->
                listOf(name, value.fmt(1), (weight / value * 100).fmt(1))
            },
            weights = listOf(1.4f, 1f, 1f),
            mono = false,
        )
    }
    if (reps > 12) {
        ErrorText(
            Res.string.one_rep_above_12_reps_every.str(),
        )
    }
    ResultCard(Res.string.training_weights.str()) {
        SimpleTable(
            header = listOf(
                "%",
                Res.string.weight.str(),
                Res.string.reps.str(),
            ),
            rows = OneRepMax.percentTable(average).map { (percent, value, estimatedReps) ->
                listOf(stringResource(Res.string.percent_value, percent), value.fmt(1), "≈ $estimatedReps")
            },
            weights = listOf(0.8f, 1f, 1f),
        )
    }
}
