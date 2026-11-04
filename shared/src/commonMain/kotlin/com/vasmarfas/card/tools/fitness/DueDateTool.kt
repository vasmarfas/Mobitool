package com.vasmarfas.card.tools.fitness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PregnantWoman
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.time.iso
import com.vasmarfas.card.tools.time.parseDate
import com.vasmarfas.card.tools.time.today
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.DateField
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class DueDateInput { PERIOD, CONCEPTION, ULTRASOUND }

private const val MAX_TERM_DAYS = 44 * 7

val dueDateTool = Tool(
    id = "due-date",
    category = ToolCategory.FITNESS,
    title = Res.string.due_date,
    description = Res.string.due_date_description,
    icon = Icons.Filled.PregnantWoman,
    keywords = listOf(
        "pregnancy", "due date", "naegele", "trimester", "gestational age", "ultrasound",
        "беременность", "пдр", "срок родов", "дата родов", "негеле", "триместр", "акушерский срок", "скрининг", "узи",
    ),
) { DueDateScreen() }

@Composable
private fun DueDateScreen() {
    var input by rememberSaveable { mutableStateOf(DueDateInput.PERIOD) }
    var dateText by rememberSaveable { mutableStateOf(today().iso()) }
    var cycleText by rememberSaveable { mutableStateOf("28") }
    var weeksText by rememberSaveable { mutableStateOf("12") }
    var daysText by rememberSaveable { mutableStateOf("0") }
    val date = parseDate(dateText)?.takeIf { it.year in 1900..2200 }
    val cycle = cycleText.trim().toIntOrNull()?.takeIf { it in 20..45 }
    val weeks = weeksText.trim().toIntOrNull()?.takeIf { it in 1..42 }
    val days = daysText.trim().toIntOrNull()?.takeIf { it in 0..6 }

    SegmentedChoice(
        options = DueDateInput.entries,
        selected = input,
        onSelect = { input = it },
        label = {
            when (it) {
                DueDateInput.PERIOD -> Res.string.due_date_from_period.str()
                DueDateInput.CONCEPTION -> Res.string.due_date_from_conception.str()
                DueDateInput.ULTRASOUND -> Res.string.due_date_from_ultrasound.str()
            }
        },
    )
    DateField(
        dateText,
        { dateText = it },
        when (input) {
            DueDateInput.PERIOD -> Res.string.due_date_period_start.str()
            DueDateInput.CONCEPTION -> Res.string.due_date_conception_date.str()
            DueDateInput.ULTRASOUND -> Res.string.due_date_scan_date.str()
        },
        isError = dateText.isNotBlank() && date == null,
    )
    if (input == DueDateInput.PERIOD) {
        NumberField(cycleText, { cycleText = it }, Res.string.due_date_cycle.str(), isError = cycle == null)
    }
    if (input == DueDateInput.ULTRASOUND) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(weeksText, { weeksText = it }, Res.string.due_date_scan_weeks.str(), Modifier.weight(1f), isError = weeks == null)
            NumberField(daysText, { daysText = it }, Res.string.due_date_scan_days.str(), Modifier.weight(1f), isError = days == null)
        }
    }

    val start = date?.let {
        when (input) {
            DueDateInput.PERIOD -> cycle?.let { c -> Pregnancy.startFromPeriod(date, c) }
            DueDateInput.CONCEPTION -> Pregnancy.startFromConception(date)
            DueDateInput.ULTRASOUND -> if (weeks != null && days != null) Pregnancy.startFromUltrasound(date, weeks, days) else null
        }
    }
    if (start != null) {
        AnswerCard(Pregnancy.atWeek(start, 40).iso(), Res.string.due_date_result.str())
        val age = start.daysUntil(today())
        if (age in 0..MAX_TERM_DAYS) {
            ResultCard {
                KeyValueRow(Res.string.due_date_term_today.str(), stringResource(Res.string.due_date_term_value, age / 7, age % 7), copyable = false)
                KeyValueRow(Res.string.due_date_trimester.str(), Pregnancy.trimester(age).toString(), copyable = false)
            }
        }
        ToolSection(Res.string.due_date_key_dates.str()) {
            ResultCard {
                KeyValueRow(Res.string.due_date_screening_1.str(), dateRange(Pregnancy.atWeek(start, 11), Pregnancy.atWeek(start, 13, 6)))
                WeekRow(start, 12)
                KeyValueRow(Res.string.due_date_screening_2.str(), dateRange(Pregnancy.atWeek(start, 18), Pregnancy.atWeek(start, 20, 6)))
                WeekRow(start, 20)
                KeyValueRow(Res.string.due_date_third_scan.str(), dateRange(Pregnancy.atWeek(start, 34), Pregnancy.atWeek(start, 35, 6)))
                WeekRow(start, 40)
            }
        }
    }
    Hint(Res.string.due_date_note.str())
}

@Composable
private fun WeekRow(start: LocalDate, week: Int) =
    KeyValueRow(pluralStringResource(Res.plurals.due_date_weeks, week, week), Pregnancy.atWeek(start, week).iso())

private fun dateRange(from: LocalDate, to: LocalDate): String = "${from.iso()} – ${to.iso()}"
