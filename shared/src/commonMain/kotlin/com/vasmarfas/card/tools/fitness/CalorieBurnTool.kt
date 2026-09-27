package com.vasmarfas.card.tools.fitness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
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
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection

val calorieBurnTool = Tool(
    id = "calorie-burn",
    category = ToolCategory.FITNESS,
    title = Res.string.calorie_burn,
    description = Res.string.calorie_burn_description,
    icon = Icons.Filled.LocalFireDepartment,
    keywords = listOf("calories", "met", "burn", "keytel", "heart rate", "калории", "расход", "мет", "пульс", "тренировка"),
) { CalorieBurnScreen() }

@Composable
private fun CalorieBurnScreen() {
    var query by rememberSaveable { mutableStateOf("") }
    var activityKey by rememberSaveable { mutableStateOf(activities.first().title.key) }
    var weightText by rememberSaveable { mutableStateOf("78") }
    var minutesText by rememberSaveable { mutableStateOf("45") }
    var useHr by rememberSaveable { mutableStateOf(false) }
    var hrText by rememberSaveable { mutableStateOf("140") }
    var ageText by rememberSaveable { mutableStateOf("30") }
    var sex by rememberSaveable { mutableStateOf(Sex.MALE) }

    ToolInputField(
        value = query,
        onValueChange = { query = it },
        label = Res.string.filter_activities.str(),
        placeholder = "running · swim · бег",
    )
    val filtered = activities.filter {
        val q = query.trim().lowercase()
        q.isEmpty() || it.title.matches(q) || it.group.matches(q)
    }
    if (filtered.isEmpty()) {
        ErrorText(Res.string.nothing_matches_the_filter.str())
        return
    }
    val activity = filtered.firstOrNull { it.title.key == activityKey } ?: filtered.first()
    DropdownChoice(
        options = filtered,
        selected = activity,
        onSelect = { activityKey = it.title.key },
        label = Res.string.activity.str(),
        text = { "${it.title.str()} · ${it.met.fmt(1)} ${Res.string.mets.str()}" },
    )
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
            value = minutesText,
            onValueChange = { minutesText = it },
            label = Res.string.duration.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_min.str(),
            isError = minutesText.toDoubleLenient().let { it == null || it <= 0 },
        )
    }

    val weight = weightText.toDoubleLenient()
    val minutes = minutesText.toDoubleLenient()
    if (weight == null || weight <= 0 || minutes == null || minutes <= 0) {
        ErrorText(Res.string.burn_weight_and_duration.str())
        return
    }
    val kcal = CalorieBurn.met(activity.met, weight, minutes)
    AnswerCard("${kcal.fmt(0, grouping = true)} ${Res.string.unit_kcal.str()}", Res.string.burned.str(), copyValue = kcal.fmt(0))
    ResultCard(Res.string.by_met.str()) {
        KeyValueRow(Res.string.per_minute.str(), "${(kcal / minutes).fmt(1)} ${Res.string.unit_kcal.str()}")
        KeyValueRow(Res.string.per_hour.str(), "${(kcal / minutes * 60).fmt(0)} ${Res.string.unit_kcal.str()}")
        KeyValueRow(Res.string.mets.str(), activity.met.fmt(1))
    }
    ResultCard(Res.string.same_session_other_activities.str()) {
        SimpleTable(
            header = listOf(
                Res.string.activity.str(),
                Res.string.mets.str(),
                Res.string.unit_kcal.str(),
            ),
            rows = filtered.take(20).map {
                listOf(it.title.str(), it.met.fmt(1), CalorieBurn.met(it.met, weight, minutes).fmt(0))
            },
            weights = listOf(2.4f, 0.8f, 1f),
            mono = false,
        )
    }

    ToolSection(Res.string.from_heart_rate.str()) {
        SwitchRow(
            Res.string.use_average_heart_rate.str(),
            useHr,
            { useHr = it },
            description = Res.string.keytel_formula_needs_sex_and_age.str(),
        )
        if (useHr) {
            SegmentedChoice(
                options = Sex.entries,
                selected = sex,
                onSelect = { sex = it },
                label = { if (it == Sex.MALE) Res.string.male.str() else Res.string.female.str() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NumberField(
                    value = hrText,
                    onValueChange = { hrText = it },
                    label = Res.string.average_hr.str(),
                    modifier = Modifier.weight(1f),
                    suffix = Res.string.unit_bpm.str(),
                    isError = hrText.toDoubleLenient().let { it == null || it < 60 || it > 220 },
                )
                NumberField(
                    value = ageText,
                    onValueChange = { ageText = it },
                    label = Res.string.age.str(),
                    modifier = Modifier.weight(1f),
                    isError = ageText.trim().toIntOrNull().let { it == null || it !in 5..100 },
                )
            }
        }
    }
    if (!useHr) return
    val hr = hrText.toDoubleLenient()
    val age = ageText.trim().toIntOrNull()
    if (hr == null || hr < 60 || hr > 220 || age == null || age !in 5..100) {
        ErrorText(Res.string.heart_rate_60_220_bpm_age_5_100.str())
        return
    }
    val byHr = CalorieBurn.keytel(sex, hr, weight, age, minutes)
    ResultCard(Res.string.by_heart_rate.str()) {
        KeyValueRow(Res.string.burned.str(), "${byHr.fmt(0, grouping = true)} ${Res.string.unit_kcal.str()}")
        KeyValueRow(Res.string.per_minute.str(), "${(byHr / minutes).fmt(1)} ${Res.string.unit_kcal.str()}")
        KeyValueRow(Res.string.difference_from_met.str(), "${(byHr - kcal).fmt(0)} ${Res.string.unit_kcal.str()}")
    }
}
