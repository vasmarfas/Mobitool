package com.vasmarfas.card.tools.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WorkHistory
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField

val workingHoursTool = Tool(
    id = "working-hours",
    category = ToolCategory.TIME,
    title = Res.string.working_hours,
    description = Res.string.working_hours_description,
    icon = Icons.Filled.WorkHistory,
    keywords = listOf("hours", "shift", "timesheet", "overtime", "pay", "рабочие часы", "смена", "табель", "ставка"),
) { WorkingHoursScreen() }

@Composable
private fun WorkingHoursScreen() {
    val shifts = remember { mutableStateListOf(Shift("09:00", "18:00", "60")) }
    var rateText by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        shifts.forEachIndexed { index, shift ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ToolInputField(
                    value = shift.start,
                    onValueChange = { shifts[index] = shift.copy(start = it) },
                    label = Res.string.shift_start.str(),
                    modifier = Modifier.weight(1f),
                    placeholder = "09:00",
                    isError = parseHhMm(shift.start) == null,
                    monospace = true,
                )
                ToolInputField(
                    value = shift.end,
                    onValueChange = { shifts[index] = shift.copy(end = it) },
                    label = Res.string.end.str(),
                    modifier = Modifier.weight(1f),
                    placeholder = "18:00",
                    isError = parseHhMm(shift.end) == null,
                    monospace = true,
                )
                NumberField(
                    value = shift.breakMinutes,
                    onValueChange = { shifts[index] = shift.copy(breakMinutes = it) },
                    label = Res.string.break_.str(),
                    modifier = Modifier.weight(1f),
                    suffix = Res.string.unit_min.str(),
                    isError = shift.breakMinutes.trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it >= 0 } == null,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${Res.string.total.str()}: ${shift.minutes?.let { formatHm(it) } ?: "—"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { shifts.removeAt(index) }, enabled = shifts.size > 1) {
                    Icon(Icons.Filled.Close, contentDescription = Res.string.remove.str())
                }
            }
        }
    }
    ActionButton(
        text = Res.string.add_shift.str(),
        onClick = { shifts.add(Shift("09:00", "18:00", "60")) },
        icon = Icons.Filled.Add,
    )
    NumberField(rateText, { rateText = it }, Res.string.hourly_rate_optional.str())

    val minutes = shifts.map { it.minutes }
    if (minutes.any { it == null }) {
        ErrorText(Res.string.shift_check_the_times_hh.str())
    } else {
        val total = minutes.sumOf { it!! }
        val hours = WorkHours.decimalHours(total)
        val rate = rateText.toDoubleLenient()
        ResultCard {
            KeyValueRow(Res.string.total.str(), formatHm(total))
            KeyValueRow(Res.string.decimal_hours.str(), hours.fmt(2))
            KeyValueRow(Res.string.shifts.str(), shifts.size.toString(), copyable = false)
            if (rate != null) KeyValueRow(Res.string.pay.str(), (hours * rate).fmt(2, grouping = true))
        }
    }
}
