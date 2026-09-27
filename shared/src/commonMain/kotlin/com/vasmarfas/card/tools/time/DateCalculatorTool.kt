package com.vasmarfas.card.tools.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.DateField
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.pluralStringResource

private enum class DateMode { DIFFERENCE, SHIFT, INFO }

fun today(): LocalDate = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

val dateCalculatorTool = Tool(
    id = "date-calculator",
    category = ToolCategory.TIME,
    title = Res.string.date_calculator,
    description = Res.string.date_calculator_description,
    icon = Icons.Filled.DateRange,
    keywords = listOf("date", "days between", "weekday", "iso week", "leap year", "дата", "дней между", "день недели", "неделя", "високосный"),
) { DateCalculatorScreen() }

@Composable
private fun DateCalculatorScreen() {
    var mode by rememberSaveable { mutableStateOf(DateMode.DIFFERENCE) }
    SegmentedChoice(
        options = DateMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = {
            when (it) {
                DateMode.DIFFERENCE -> Res.string.difference.str()
                DateMode.SHIFT -> Res.string.add_subtract.str()
                DateMode.INFO -> Res.string.date_info.str()
            }
        },
    )
    val saved = rememberSaveableStateHolder()
    saved.SaveableStateProvider(mode) {
        when (mode) {
            DateMode.DIFFERENCE -> DifferenceSection()
            DateMode.SHIFT -> ShiftSection()
            DateMode.INFO -> InfoSection()
        }
    }
}

@Composable
private fun DifferenceSection() {
    var fromText by rememberSaveable { mutableStateOf(today().iso()) }
    var toText by rememberSaveable { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        DateField(fromText, { fromText = it }, Res.string.from__4.str(), Modifier.weight(1f))
        DateField(toText, { toText = it }, Res.string.date_to.str(), Modifier.weight(1f))
    }
    val from = parseDate(fromText)
    val to = parseDate(toText)
    if ((fromText.isNotBlank() && from == null) || (toText.isNotBlank() && to == null)) {
        ErrorText(Res.string.use_the_yyyy_mm_dd_format.str())
    }
    if (from != null && to != null) {
        val d = remember(from, to) { DateMath.difference(from, to) }
        ResultCard {
            KeyValueRow(
                Res.string.difference.str(),
                "${d.years} ${Res.string.y.str()} ${d.months} ${Res.string.m_2.str()} ${d.days} ${Res.string.d.str()}",
            )
            KeyValueRow(Res.string.total_days.str(), d.totalDays.toString())
            KeyValueRow(Res.string.weeks.str(), "${d.weeks} ${Res.string.w_2.str()} ${d.weekRemainderDays} ${Res.string.d.str()}")
            KeyValueRow(Res.string.working_days_mon_fri.str(), d.workingDays.toString())
            KeyValueRow(
                Res.string.order.str(),
                (if (from <= to) Res.string.from_is_earlier else Res.string.from_is_later).str(),
                mono = false,
                copyable = false,
            )
        }
    }
}

@Composable
private fun ShiftSection() {
    var dateText by rememberSaveable { mutableStateOf(today().iso()) }
    var amountText by rememberSaveable { mutableStateOf("30") }
    var unit by rememberSaveable { mutableStateOf(DateUnit.DAYS) }
    var subtract by rememberSaveable { mutableStateOf(false) }
    DateField(dateText, { dateText = it }, Res.string.date.str())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(amountText, { amountText = it }, Res.string.amount.str(), Modifier.weight(1f), isError = amountText.trim().toIntOrNull() == null)
    }
    SegmentedChoice(
        options = DateUnit.entries,
        selected = unit,
        onSelect = { unit = it },
        label = {
            when (it) {
                DateUnit.DAYS -> Res.string.days.str()
                DateUnit.WEEKS -> Res.string.date_weeks.str()
                DateUnit.MONTHS -> Res.string.months_field.str()
                DateUnit.YEARS -> Res.string.date_years.str()
            }
        },
    )
    SegmentedChoice(
        options = listOf(false, true),
        selected = subtract,
        onSelect = { subtract = it },
        label = { if (it) Res.string.subtract.str() else Res.string.date_add.str() },
    )
    val date = parseDate(dateText)
    val amount = amountText.trim().toIntOrNull()
    if (date != null && amount != null) {
        val result = remember(date, amount, unit, subtract) { DateMath.shift(date, amount, unit, subtract) }
        if (result == null) {
            ErrorText(Res.string.date_out_of_range.str())
        } else {
            ResultCard {
                KeyValueRow(Res.string.result.str(), result.iso())
                KeyValueRow(Res.string.weekday.str(), result.dayOfWeek.title().str(), mono = false)
                KeyValueRow(Res.string.days_from_today.str(), (result.toEpochDays() - today().toEpochDays()).toString())
            }
        }
    }
}

@Composable
private fun InfoSection() {
    var dateText by rememberSaveable { mutableStateOf(today().iso()) }
    DateField(dateText, { dateText = it }, Res.string.date.str())
    val date = parseDate(dateText)
    if (date == null) {
        if (dateText.isNotBlank()) ErrorText(Res.string.use_the_yyyy_mm_dd_format.str())
        return
    }
    val (weekYear, week) = DateMath.isoWeek(date)
    val leap = DateMath.isLeapYear(date.year)
    val yearDays = if (leap) 366 else 365
    val fromToday = today().daysUntil(date)
    ResultCard {
        KeyValueRow(Res.string.weekday.str(), date.dayOfWeek.title().str(), mono = false)
        KeyValueRow(Res.string.iso_week.str(), "$weekYear-W${week.pad2()}")
        KeyValueRow(Res.string.day_of_year.str(), "${date.dayOfYear} / $yearDays")
        KeyValueRow(Res.string.days_left_in_year.str(), DateMath.daysLeftInYear(date).toString())
        KeyValueRow(Res.string.quarter.str(), "Q${DateMath.quarter(date)}")
        KeyValueRow(Res.string.leap_year.str(), (if (leap) Res.string.date_yes else Res.string.date_no).str(), mono = false, copyable = false)
        KeyValueRow(Res.string.days_in_month.str(), DateMath.daysInMonth(date.year, date.month.number).toString())
        KeyValueRow(
            Res.string.relative_to_today.str(),
            when {
                fromToday == 0 -> Res.string.today_relative.str()
                fromToday > 0 -> pluralStringResource(Res.plurals.days_ahead, fromToday, fromToday)
                else -> pluralStringResource(Res.plurals.days_ago, -fromToday, -fromToday)
            },
            mono = false,
            copyable = false,
        )
    }
}
