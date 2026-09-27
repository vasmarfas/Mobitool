package com.vasmarfas.card.tools.fitness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
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
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource

val waterAndMacrosTool = Tool(
    id = "water-and-macros",
    category = ToolCategory.FITNESS,
    title = Res.string.macros_and_meals,
    description = Res.string.water_and_macros_description,
    icon = Icons.Filled.Restaurant,
    keywords = listOf("macros", "protein", "carbs", "fat", "cutting", "bulking", "meals", "макросы", "белок", "углеводы", "жиры", "сушка", "набор"),
) { WaterAndMacrosScreen() }

private class PerKg(val title: StringResource, val grams: String)

private val proteinLevels = listOf(
    PerKg(Res.string.protein_level_sedentary, "1"),
    PerKg(Res.string.protein_level_training, "1.6"),
    PerKg(Res.string.protein_level_muscle, "1.8"),
    PerKg(Res.string.protein_level_cutting, "2"),
)

private val fatLevels = listOf(
    PerKg(Res.string.fat_level_minimum, "0.6"),
    PerKg(Res.string.fat_level_usual, "0.9"),
    PerKg(Res.string.fat_level_high, "1.2"),
)

@Composable
private fun WaterAndMacrosScreen() {
    var tdeeText by rememberSaveable { mutableStateOf("2600") }
    var goal by rememberSaveable { mutableStateOf(NutritionGoal.MAINTAIN) }
    var percentText by rememberSaveable { mutableStateOf("0") }
    var weightText by rememberSaveable { mutableStateOf("78") }
    var proteinText by rememberSaveable { mutableStateOf("1.8") }
    var fatText by rememberSaveable { mutableStateOf("0.9") }
    var meals by rememberSaveable { mutableStateOf(4) }

    NumberField(
        value = tdeeText,
        onValueChange = { tdeeText = it },
        label = Res.string.daily_calories_tdee.str(),
        suffix = Res.string.unit_kcal.str(),
        isError = tdeeText.toDoubleLenient().let { it == null || it <= 0 },
        supportingText = Res.string.take_it_from_the_bmi_body_tool.str(),
    )
    SegmentedChoice(
        options = NutritionGoal.entries,
        selected = goal,
        onSelect = {
            goal = it
            percentText = it.defaultPercent.fmt(0)
        },
        label = { it.title.str() },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = percentText,
            onValueChange = { percentText = it },
            label = Res.string.calorie_shift.str(),
            modifier = Modifier.weight(1f),
            suffix = "%",
            isError = percentText.toDoubleLenient().let { it == null || it <= -60 || it >= 60 },
        )
        NumberField(
            value = weightText,
            onValueChange = { weightText = it },
            label = Res.string.weight.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_kg.str(),
            isError = weightText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }
    ToolSection(Res.string.protein_per_kg.str()) {
        Hint(Res.string.protein_hint.str())
        ChoiceChips(
            options = proteinLevels,
            selected = proteinLevels.firstOrNull { it.grams.toDoubleLenient() == proteinText.toDoubleLenient() },
            onSelect = { proteinText = it.grams },
            label = { "${it.title.str()} · ${it.grams.toDoubleLenient()?.fmt(1)} ${Res.string.unit_g.str()}" },
        )
    }
    ToolSection(Res.string.fat_per_kg.str()) {
        Hint(Res.string.fat_hint.str())
        ChoiceChips(
            options = fatLevels,
            selected = fatLevels.firstOrNull { it.grams.toDoubleLenient() == fatText.toDoubleLenient() },
            onSelect = { fatText = it.grams },
            label = { "${it.title.str()} · ${it.grams.toDoubleLenient()?.fmt(1)} ${Res.string.unit_g.str()}" },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = proteinText,
            onValueChange = { proteinText = it },
            label = Res.string.protein_per_kg.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_g.str(),
            isError = proteinText.toDoubleLenient().let { it == null || it < 0 || it > 5 },
        )
        NumberField(
            value = fatText,
            onValueChange = { fatText = it },
            label = Res.string.fat_per_kg.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_g.str(),
            isError = fatText.toDoubleLenient().let { it == null || it < 0 || it > 4 },
        )
    }
    ChoiceChips(
        options = listOf(3, 4, 5, 6),
        selected = meals,
        onSelect = { meals = it },
        label = { pluralStringResource(Res.plurals.meal_count, it, it) },
    )

    val tdee = tdeeText.toDoubleLenient()
    val percent = percentText.toDoubleLenient()
    val weight = weightText.toDoubleLenient()
    val protein = proteinText.toDoubleLenient()
    val fat = fatText.toDoubleLenient()
    if (tdee == null || tdee <= 0 || percent == null || percent <= -60 || percent >= 60 ||
        weight == null || weight <= 0 || protein == null || protein < 0 || protein > 5 ||
        fat == null || fat < 0 || fat > 4
    ) {
        ErrorText(
            Res.string.macros_check_the_values_shift.str(),
        )
        return
    }

    val split = Macros.split(tdee, percent, weight, protein, fat)
    if (split.carbsKcal < 0) {
        ErrorText(
            Res.string.macros_protein_and_fat_alone.str(),
        )
        return
    }
    ResultCard(Res.string.daily_target.str()) {
        KeyValueRow(Res.string.calories.str(), "${split.targetKcal.fmt(0, grouping = true)} ${Res.string.unit_kcal.str()}")
        KeyValueRow(Res.string.difference_from_tdee.str(), "${(split.targetKcal - tdee).fmt(0)} ${Res.string.unit_kcal.str()}")
        SimpleTable(
            header = listOf(
                Res.string.macro.str(),
                Res.string.unit_g.str(),
                Res.string.unit_kcal.str(),
                "%",
            ),
            rows = listOf(
                listOf(Res.string.protein.str(), split.proteinG.fmt(0), split.proteinKcal.fmt(0), (split.proteinKcal / split.targetKcal * 100).fmt(0)),
                listOf(Res.string.fat.str(), split.fatG.fmt(0), split.fatKcal.fmt(0), (split.fatKcal / split.targetKcal * 100).fmt(0)),
                listOf(Res.string.carbs.str(), split.carbsG.fmt(0), split.carbsKcal.fmt(0), (split.carbsKcal / split.targetKcal * 100).fmt(0)),
            ),
            weights = listOf(1.5f, 1f, 1f, 0.8f),
            mono = false,
        )
    }
    ResultCard(Res.string.per_meal.str()) {
        SimpleTable(
            header = listOf(
                "#",
                Res.string.unit_kcal.str(),
                "${Res.string.protein.str()}, ${Res.string.unit_g.str()}",
                "${Res.string.fat.str()}, ${Res.string.unit_g.str()}",
                "${Res.string.carbs.str()}, ${Res.string.unit_g.str()}",
            ),
            rows = Macros.mealShares(meals).mapIndexed { index, share ->
                listOf(
                    (index + 1).toString(),
                    (split.targetKcal * share).fmt(0),
                    (split.proteinG * share).fmt(0),
                    (split.fatG * share).fmt(0),
                    (split.carbsG * share).fmt(0),
                )
            },
            weights = listOf(0.5f, 1f, 1f, 1f, 1.2f),
        )
    }
    Hint(Res.string.macros_water_is_not_part.str())
}
