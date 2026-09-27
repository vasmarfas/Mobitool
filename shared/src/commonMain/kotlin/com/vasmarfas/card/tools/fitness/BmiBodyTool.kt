package com.vasmarfas.card.tools.fitness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import org.jetbrains.compose.resources.stringResource
import kotlin.math.floor

private enum class BodyUnits { METRIC, IMPERIAL }

private const val KG_PER_LB = 0.45359237

val bmiBodyTool = Tool(
    id = "bmi-body",
    category = ToolCategory.FITNESS,
    title = Res.string.bmi_and_body_calculator,
    description = Res.string.bmi_body_description,
    icon = Icons.Filled.FitnessCenter,
    keywords = listOf("bmi", "bmr", "tdee", "calories", "body fat", "ideal weight", "имт", "калории", "жир", "вес", "метаболизм"),
) { BmiBodyScreen() }

@Composable
private fun BmiBodyScreen() {
    var units by rememberSaveable { mutableStateOf(BodyUnits.METRIC) }
    var sex by rememberSaveable { mutableStateOf(Sex.MALE) }
    var ageText by rememberSaveable { mutableStateOf("30") }
    var heightText by rememberSaveable { mutableStateOf("175") }
    var inchesText by rememberSaveable { mutableStateOf("") }
    var weightText by rememberSaveable { mutableStateOf("70") }
    var waistText by rememberSaveable { mutableStateOf("") }
    var neckText by rememberSaveable { mutableStateOf("") }
    var hipText by rememberSaveable { mutableStateOf("") }
    var activity by rememberSaveable { mutableStateOf(ActivityLevel.LIGHT) }
    val metric = units == BodyUnits.METRIC
    val age = ageText.trim().toIntOrNull()?.takeIf { it in 1..120 }
    val heightCm = if (metric) {
        heightText.toDoubleLenient()?.takeIf { it > 0 }
    } else {
        val feet = heightText.toDoubleLenient()
        val inches = if (inchesText.isBlank()) 0.0 else inchesText.toDoubleLenient()
        if (feet != null && inches != null) ((feet * 12 + inches) * 2.54).takeIf { it > 0 } else null
    }
    fun lengthCm(text: String): Double? = text.toDoubleLenient()?.takeIf { it > 0 }?.let { if (metric) it else it * 2.54 }
    fun lengthText(cm: Double?, current: String, toImperial: Boolean): String = cm?.let { if (toImperial) (it / 2.54).fmt(1) else it.fmt(1) } ?: current
    val weightUnit = if (metric) Res.string.unit_kg.str() else Res.string.unit_lb.str()
    fun weight(kg: Double): String = "${(if (metric) kg else kg / KG_PER_LB).fmt(1)} $weightUnit"
    val weightKg = weightText.toDoubleLenient()?.takeIf { it > 0 }?.let { if (metric) it else it * KG_PER_LB }
    val waist = lengthCm(waistText)
    val neck = lengthCm(neckText)
    val hip = lengthCm(hipText)
    val lengthUnit = if (metric) Res.string.unit_cm.str() else Res.string.unit_in.str()
    SegmentedChoice(
        options = BodyUnits.entries,
        selected = units,
        onSelect = { newUnits ->
            if (newUnits != units) {
                val toImperial = newUnits == BodyUnits.IMPERIAL
                heightCm?.let { cm ->
                    if (toImperial) {
                        val totalInches = cm / 2.54
                        val feet = floor(totalInches / 12)
                        heightText = feet.fmt(0)
                        inchesText = (totalInches - feet * 12).fmt(1)
                    } else {
                        heightText = cm.fmt(1)
                        inchesText = ""
                    }
                }
                weightKg?.let { weightText = if (toImperial) (it / KG_PER_LB).fmt(1) else it.fmt(1) }
                waistText = lengthText(waist, waistText, toImperial)
                neckText = lengthText(neck, neckText, toImperial)
                hipText = lengthText(hip, hipText, toImperial)
                units = newUnits
            }
        },
        label = { if (it == BodyUnits.METRIC) Res.string.metric.str() else Res.string.imperial.str() },
    )
    SegmentedChoice(
        options = Sex.entries,
        selected = sex,
        onSelect = { sex = it },
        label = { if (it == Sex.MALE) Res.string.male.str() else Res.string.female.str() },
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NumberField(
            value = ageText,
            onValueChange = { ageText = it },
            label = Res.string.age.str(),
            modifier = Modifier.weight(1f),
            isError = ageText.isNotBlank() && age == null,
        )
        NumberField(
            value = weightText,
            onValueChange = { weightText = it },
            label = Res.string.weight.str(),
            modifier = Modifier.weight(1f),
            suffix = weightUnit,
            isError = weightText.isNotBlank() && weightKg == null,
        )
    }
    if (metric) {
        NumberField(
            value = heightText,
            onValueChange = { heightText = it },
            label = Res.string.body_height.str(),
            suffix = Res.string.unit_cm.str(),
            isError = heightText.isNotBlank() && heightCm == null,
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NumberField(
                value = heightText,
                onValueChange = { heightText = it },
                label = Res.string.body_height.str(),
                modifier = Modifier.weight(1f),
                suffix = Res.string.unit_ft.str(),
                isError = heightText.isNotBlank() && heightCm == null,
            )
            NumberField(
                value = inchesText,
                onValueChange = { inchesText = it },
                label = Res.string.inches.str(),
                modifier = Modifier.weight(1f),
                suffix = Res.string.unit_in.str(),
                isError = inchesText.isNotBlank() && inchesText.toDoubleLenient() == null,
            )
        }
    }
    DropdownChoice(
        options = ActivityLevel.entries,
        selected = activity,
        onSelect = { activity = it },
        label = Res.string.activity.str(),
        text = { it.title.str() },
    )
    ToolSection(Res.string.body_fat_section.str()) {
        Hint(Res.string.body_fat_section_hint.str())
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NumberField(
                value = waistText,
                onValueChange = { waistText = it },
                label = Res.string.waist.str(),
                modifier = Modifier.weight(1f),
                suffix = lengthUnit,
                isError = waistText.isNotBlank() && waist == null,
            )
            NumberField(
                value = neckText,
                onValueChange = { neckText = it },
                label = Res.string.neck.str(),
                modifier = Modifier.weight(1f),
                suffix = lengthUnit,
                isError = neckText.isNotBlank() && neck == null,
            )
            if (sex == Sex.FEMALE) {
                NumberField(
                    value = hipText,
                    onValueChange = { hipText = it },
                    label = Res.string.hips.str(),
                    modifier = Modifier.weight(1f),
                    suffix = lengthUnit,
                    isError = hipText.isNotBlank() && hip == null,
                )
            }
        }
    }
    if (heightCm != null && weightKg != null) {
        val bmi = Body.bmi(weightKg, heightCm)
        val (low, high) = Body.healthyWeightRange(heightCm)
        ResultCard(Res.string.body_mass_index.str()) {
            KeyValueRow(Res.string.bmi_short.str(), bmi.fmt(1))
            KeyValueRow(Res.string.category_who.str(), Body.bmiCategory(bmi).str(), mono = false, copyable = false)
            KeyValueRow(Res.string.healthy_weight_range.str(), "${weight(low)} – ${weight(high)}", copyable = false)
        }
        if (age != null) {
            val bmr = Body.bmr(sex, weightKg, heightCm, age)
            val tdee = bmr * activity.factor
            ResultCard(Res.string.daily_energy.str()) {
                KeyValueRow(Res.string.bmr_mifflin_st_jeor.str(), "${bmr.fmt(0)} ${Res.string.unit_kcal.str()}")
                KeyValueRow(Res.string.tdee_with_activity.str(), "${tdee.fmt(0)} ${Res.string.unit_kcal.str()}")
                KeyValueRow(Res.string.weight_loss_15.str(), "${(tdee * 0.85).fmt(0)} ${Res.string.unit_kcal.str()}")
                KeyValueRow(Res.string.weight_gain_15.str(), "${(tdee * 1.15).fmt(0)} ${Res.string.unit_kcal.str()}")
                Hint(Res.string.daily_energy_hint.str())
            }
        }
        ResultCard(Res.string.ideal_weight.str()) {
            Body.idealWeights(sex, heightCm).forEach { KeyValueRow(it.formula, weight(it.kg)) }
            Hint(Res.string.ideal_weight_hint.str())
        }
        if (waist != null && neck != null && (sex == Sex.MALE || hip != null)) {
            val fat = Body.navyBodyFat(sex, heightCm, waist, neck, hip ?: 0.0)
            if (fat == null) {
                ErrorText(Res.string.waist_must_be_larger_than_neck.str())
            } else {
                ResultCard(Res.string.body_fat_us_navy.str()) {
                    KeyValueRow(Res.string.body_fat.str(), stringResource(Res.string.percent_value, fat.fmt(1)))
                    KeyValueRow(Res.string.category.str(), Body.bodyFatCategory(sex, fat).str(), mono = false, copyable = false)
                    KeyValueRow(Res.string.fat_mass.str(), weight(weightKg * fat / 100))
                    KeyValueRow(Res.string.lean_mass.str(), weight(weightKg * (1 - fat / 100)))
                }
            }
        }
    }
}
