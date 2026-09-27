package com.vasmarfas.card.tools.time

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import org.jetbrains.compose.resources.pluralStringResource

val calendarTool = Tool(
    id = "calendar",
    category = ToolCategory.TIME,
    title = Res.string.calendar,
    description = Res.string.calendar_description,
    icon = Icons.Filled.CalendarMonth,
    keywords = listOf("calendar", "month", "week number", "календарь", "месяц", "номер недели"),
    expandable = true,
) { CalendarScreen() }

@Composable
private fun CalendarScreen() {
    val todayDate = remember { today() }
    var year by rememberSaveable { mutableStateOf(todayDate.year) }
    var month by rememberSaveable { mutableStateOf(todayDate.month.number) }
    var selectedText by rememberSaveable { mutableStateOf(todayDate.iso()) }
    val selected = parseDate(selectedText)
    val weeks = remember(year, month) { CalendarMath.monthGrid(year, month) }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { year-- }) { Icon(Icons.Filled.FirstPage, contentDescription = Res.string.previous_year.str()) }
        IconButton(onClick = { CalendarMath.shiftMonth(year, month, -1).let { year = it.first; month = it.second } }) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = Res.string.previous_month.str())
        }
        Text(
            text = "${monthNames[month - 1].str()} $year",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { CalendarMath.shiftMonth(year, month, 1).let { year = it.first; month = it.second } }) {
            Icon(Icons.Filled.ChevronRight, contentDescription = Res.string.next_month.str())
        }
        IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.LastPage, contentDescription = Res.string.next_year.str()) }
    }
    TextButton(
        onClick = {
            year = todayDate.year
            month = todayDate.month.number
            selectedText = todayDate.iso()
        },
    ) { Text(Res.string.today.str()) }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("#", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(32.dp), textAlign = TextAlign.Center)
            weekdayShortNames.forEach { name ->
                Text(
                    text = name.str(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = DateMath.isoWeek(week[0]).second.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(32.dp),
                )
                week.forEach { date ->
                    DayCell(
                        date = date,
                        inMonth = date.month.number == month,
                        isToday = date == todayDate,
                        isSelected = date == selected,
                        onClick = { selectedText = date.iso() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (selected != null) {
        val (weekYear, week) = DateMath.isoWeek(selected)
        val fromToday = todayDate.daysUntil(selected)
        ResultCard("${selected.day} ${monthNamesInDate[selected.month.number - 1].str()} ${selected.year}") {
            KeyValueRow(Res.string.weekday.str(), selected.dayOfWeek.title().str(), mono = false)
            KeyValueRow("ISO", selected.iso())
            KeyValueRow(Res.string.iso_week.str(), "$weekYear-W${week.pad2()}")
            KeyValueRow(Res.string.day_of_year.str(), "${selected.dayOfYear} / ${if (DateMath.isLeapYear(selected.year)) 366 else 365}")
            KeyValueRow(Res.string.quarter.str(), "Q${DateMath.quarter(selected)}")
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
}

@Composable
private fun DayCell(date: LocalDate, inMonth: Boolean, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val weekend = date.dayOfWeek.isoDayNumber >= 6
    val background = when {
        isToday -> MaterialTheme.colorScheme.primary
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = when {
        isToday -> MaterialTheme.colorScheme.onPrimary
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        !inMonth -> MaterialTheme.colorScheme.outline
        weekend -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(background)
            .then(if (isSelected && isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(date.day.toString(), style = MaterialTheme.typography.bodyMedium, color = textColor)
    }
}
