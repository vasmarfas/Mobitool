package com.vasmarfas.card.tools.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.LocalLang
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.stringResource

val cronParserTool = Tool(
    id = "cron-parser",
    category = ToolCategory.DEVELOPER,
    title = Res.string.cron_parser,
    description = Res.string.cron_parser_description,
    icon = Icons.Filled.Schedule,
    keywords = listOf("cron", "crontab", "schedule", "job", "timer", "расписание", "крон", "планировщик"),
) { CronParserScreen() }

@Composable
private fun CronParserScreen() {
    var input by rememberSaveable { mutableStateOf("0 9 * * 1-5") }
    val lang = LocalLang.current
    val result = remember(input) { Cron.parse(input) }
    val expr = result.getOrNull()
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = Res.string.cron_expression_minute_hour_day.str(),
        placeholder = "*/15 9-18 * * mon-fri",
        isError = input.isNotBlank() && expr == null,
        monospace = true,
    )
    ChoiceChips(
        options = Cron.presets,
        selected = Cron.presets.firstOrNull { it.first == input.trim() },
        onSelect = { input = it.first },
        label = { it.second.str() },
    )
    if (expr == null) {
        val error = result.exceptionOrNull()
        if (input.isNotBlank()) ErrorText(if (error is CronException) stringResource(error.text, *error.args) else error?.message ?: "")
        return
    }
    val now = remember(input) { localDateTime(currentEpochMillis()) }
    val runs = remember(expr, now) {
        Cron.nextRuns(expr, CronTime(now.year, now.month.ordinal + 1, now.day, now.hour, now.minute), 5)
    }
    ResultCard {
        KeyValueRow(Res.string.meaning.str(), Cron.describe(expr, lang), mono = false)
        KeyValueRow(Res.string.minutes_field.str(), expr.minute.values.sorted().joinToString(","), copyable = false)
        KeyValueRow(Res.string.hours_field.str(), expr.hour.values.sorted().joinToString(","), copyable = false)
        KeyValueRow(Res.string.days_of_month.str(), expr.dayOfMonth.values.sorted().joinToString(","), copyable = false)
        KeyValueRow(Res.string.months_field.str(), expr.month.values.sorted().joinToString(","), copyable = false)
        KeyValueRow(Res.string.days_of_week_0_sunday.str(), expr.dayOfWeek.values.sorted().joinToString(","), copyable = false)
    }
    ResultCard(Res.string.next_runs_local_time.str()) {
        if (runs.isEmpty()) {
            Text(Res.string.no_run_in_the_next_30_years.str(), style = MaterialTheme.typography.bodyMedium)
        } else {
            MonoTable(runs.mapIndexed { i, t -> "${i + 1}. ${t.formatted()}" })
        }
    }
}
