package com.vasmarfas.card.tools.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.time.monthShortNames
import com.vasmarfas.card.tools.time.today
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.DateField
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import com.vasmarfas.card.ui.components.ToolSection
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.number
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class PayKind(val title: StringResource) {
    SALARY(Res.string.salary_mode_salary),
    VACATION(Res.string.salary_mode_vacation),
    SICK(Res.string.salary_mode_sick),
}

private enum class SalaryDirection(val title: StringResource) {
    GROSS_TO_NET(Res.string.salary_gross_to_net),
    NET_TO_GROSS(Res.string.salary_net_to_gross),
}

private val InsuranceRecord.title: StringResource
    get() = when (this) {
        InsuranceRecord.UNDER_5 -> Res.string.salary_record_under_5
        InsuranceRecord.FROM_5_TO_8 -> Res.string.salary_record_5_to_8
        InsuranceRecord.FROM_8 -> Res.string.salary_record_from_8
    }

val salaryCalculatorTool = Tool(
    id = "salary-calculator",
    category = ToolCategory.MONEY,
    title = Res.string.salary_calculator,
    description = Res.string.salary_calculator_description,
    icon = Icons.Filled.AccountBalanceWallet,
    keywords = listOf(
        "salary", "income tax", "take-home", "net", "gross", "vacation pay", "sick pay", "ndfl",
        "зарплата", "ндфл", "на руки", "оклад", "отпускные", "больничный", "пособие", "налог", "прогрессивная шкала",
    ),
) { SalaryCalculatorScreen() }

@Composable
private fun SalaryCalculatorScreen() {
    var kind by rememberSaveable { mutableStateOf(PayKind.SALARY) }
    SegmentedChoice(
        options = PayKind.entries,
        selected = kind,
        onSelect = { kind = it },
        label = { it.title.str() },
    )
    val saved = rememberSaveableStateHolder()
    saved.SaveableStateProvider(kind) {
        when (kind) {
            PayKind.SALARY -> SalarySection()
            PayKind.VACATION -> VacationSection()
            PayKind.SICK -> SickLeaveSection()
        }
    }
}

@Composable
private fun SalarySection() {
    var direction by rememberSaveable { mutableStateOf(SalaryDirection.GROSS_TO_NET) }
    var amountText by rememberSaveable { mutableStateOf("100000") }
    val amount = amountText.toDoubleLenient()?.takeIf { it >= 0 }
    SegmentedChoice(
        options = SalaryDirection.entries,
        selected = direction,
        onSelect = { direction = it },
        label = { it.title.str() },
    )
    NumberField(
        value = amountText,
        onValueChange = { amountText = it },
        label = (if (direction == SalaryDirection.GROSS_TO_NET) Res.string.salary_gross_monthly else Res.string.salary_net_monthly).str(),
        isError = amountText.isNotBlank() && amount == null,
    )
    if (amount != null) {
        val gross = if (direction == SalaryDirection.GROSS_TO_NET) amount else Salary.grossFor(amount)
        val year = remember(gross) { Salary.year(gross) }
        if (direction == SalaryDirection.GROSS_TO_NET) {
            val first = year.months.first()
            val flat = year.rateSpans.size == 1 && first.rate == Ndfl.brackets.first().rate
            AnswerCard(
                first.net.fmt(2, grouping = true),
                (if (flat) Res.string.salary_net_every_month else Res.string.salary_net_in_january).str(),
                copyValue = first.net.fmt(2),
            )
        } else {
            AnswerCard(gross.fmt(2, grouping = true), Res.string.salary_gross_monthly.str(), copyValue = gross.fmt(2))
            Hint(Res.string.salary_net_to_gross_hint.str())
        }
        ResultCard {
            year.rateSpans.forEach { span ->
                KeyValueRow(
                    stringResource(Res.string.salary_rate_label, stringResource(Res.string.percent_value, span.first().rate)),
                    if (span.size == 12) Res.string.salary_all_year.str() else monthRange(span.first().month, span.last().month),
                    copyable = false,
                )
            }
            KeyValueRow(Res.string.salary_average_net.str(), (year.net / 12).fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_net_year.str(), year.net.fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_tax_year.str(), year.tax.fmt(2, grouping = true))
            KeyValueRow(
                Res.string.salary_effective_rate.str(),
                if (year.gross == 0.0) "—" else stringResource(Res.string.percent_value, (year.tax / year.gross * 100).fmt(2)),
                copyable = false,
            )
        }
        ToolSection(Res.string.salary_by_month.str()) {
            val header = monthLine(
                Res.string.month.str(),
                Res.string.salary_column_gross.str(),
                Res.string.salary_column_tax.str(),
                Res.string.salary_column_net.str(),
                Res.string.salary_column_rate.str(),
            )
            MonoTable(
                listOf(header) + year.months.map {
                    monthLine(monthShortNames[it.month - 1].str(), it.gross.fmt(2), it.tax.fmt(2), it.net.fmt(2), stringResource(Res.string.percent_value, it.rate))
                },
            )
        }
    }
    Hint(Res.string.salary_scale_hint.str())
}

@Composable
private fun VacationSection() {
    var startText by rememberSaveable { mutableStateOf(today().toString()) }
    var daysText by rememberSaveable { mutableStateOf("14") }
    var earningsText by rememberSaveable { mutableStateOf("1200000") }
    var earlierText by rememberSaveable { mutableStateOf("") }
    var excludeDays by rememberSaveable { mutableStateOf(false) }
    var excludedText by rememberSaveable { mutableStateOf("") }
    val start = runCatching { LocalDate.parse(startText.trim()) }.getOrNull()
    val days = daysText.trim().toIntOrNull()?.takeIf { it >= 1 }
    val earnings = earningsText.toDoubleLenient()?.takeIf { it >= 0 }
    val earlier = if (earlierText.isBlank()) 0.0 else earlierText.toDoubleLenient()?.takeIf { it >= 0 }
    val period = start?.let { VacationPay.period(it) }
    val excludedFields = excludedText.split(',').let { parts -> List(12) { parts.getOrElse(it) { "" } } }
    DateField(value = startText, onValueChange = { startText = it }, label = Res.string.salary_vacation_start.str())
    NumberField(
        value = daysText,
        onValueChange = { daysText = it },
        label = Res.string.salary_vacation_days.str(),
        isError = daysText.isNotBlank() && days == null,
    )
    NumberField(
        value = earningsText,
        onValueChange = { earningsText = it },
        label = Res.string.salary_period_earnings.str(),
        isError = earningsText.isNotBlank() && earnings == null,
        supportingText = period?.let { stringResource(Res.string.salary_period_range, monthLabel(it.first()), monthLabel(it.last())) },
    )
    EarlierIncomeField(earlierText, { earlierText = it }, earlier)
    SwitchRow(
        label = Res.string.salary_exclude_days.str(),
        checked = excludeDays,
        onCheckedChange = { excludeDays = it },
        description = Res.string.salary_exclude_days_hint.str(),
    )
    val excluded = period?.mapIndexed { i, month ->
        if (excludeDays) excludedFields[i].trim().ifEmpty { "0" }.toIntOrNull()?.takeIf { it in 0..month.numberOfDays } else 0
    }
    if (excludeDays && period != null && excluded != null) {
        period.indices.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { i ->
                    NumberField(
                        value = excludedFields[i],
                        onValueChange = { typed ->
                            excludedText = excludedFields.toMutableList().also { it[i] = typed.filter(Char::isDigit) }.joinToString(",")
                        },
                        label = monthLabel(period[i]),
                        suffix = "/ ${period[i].numberOfDays}",
                        isError = excluded[i] == null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    val counted = if (period != null && excluded != null && excluded.all { it != null }) VacationPay.countedDays(period, excluded.filterNotNull()) else null
    if (counted != null && counted <= 0.0) {
        ErrorText(Res.string.salary_no_days.str())
    } else if (counted != null && days != null && earnings != null && earlier != null) {
        val daily = VacationPay.averageDaily(earnings, counted)
        val pay = daily * days
        val tax = Ndfl.taxOn(pay, earlier)
        AnswerCard(pay.fmt(2, grouping = true), pluralStringResource(Res.plurals.salary_vacation_pay_days, days, days), copyValue = pay.fmt(2))
        ResultCard {
            KeyValueRow(Res.string.salary_average_daily.str(), daily.fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_counted_days.str(), counted.fmt(2), copyable = false)
            KeyValueRow(Res.string.salary_tax.str(), tax.fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_net_pay.str(), (pay - tax).fmt(2, grouping = true))
        }
    }
    Hint(Res.string.salary_vacation_hint.str())
}

@Composable
private fun SickLeaveSection() {
    var startText by rememberSaveable { mutableStateOf(today().toString()) }
    var daysText by rememberSaveable { mutableStateOf("7") }
    var record by rememberSaveable { mutableStateOf(InsuranceRecord.FROM_8) }
    var olderText by rememberSaveable { mutableStateOf("1200000") }
    var lastText by rememberSaveable { mutableStateOf("1200000") }
    var earlierText by rememberSaveable { mutableStateOf("") }
    val start = runCatching { LocalDate.parse(startText.trim()) }.getOrNull()
    val days = daysText.trim().toIntOrNull()?.takeIf { it >= 1 }
    val older = olderText.toDoubleLenient()?.takeIf { it >= 0 }
    val last = lastText.toDoubleLenient()?.takeIf { it >= 0 }
    val earlier = if (earlierText.isBlank()) 0.0 else earlierText.toDoubleLenient()?.takeIf { it >= 0 }
    val year = start?.year ?: today().year
    DateField(value = startText, onValueChange = { startText = it }, label = Res.string.salary_sick_start.str())
    NumberField(
        value = daysText,
        onValueChange = { daysText = it },
        label = Res.string.salary_sick_days.str(),
        isError = daysText.isNotBlank() && days == null,
    )
    Hint(Res.string.salary_insurance_record.str())
    SegmentedChoice(
        options = InsuranceRecord.entries,
        selected = record,
        onSelect = { record = it },
        label = { it.title.str() },
    )
    NumberField(
        value = olderText,
        onValueChange = { olderText = it },
        label = stringResource(Res.string.salary_earnings_for_year, year - 2),
        isError = olderText.isNotBlank() && older == null,
    )
    NumberField(
        value = lastText,
        onValueChange = { lastText = it },
        label = stringResource(Res.string.salary_earnings_for_year, year - 1),
        isError = lastText.isNotBlank() && last == null,
    )
    EarlierIncomeField(earlierText, { earlierText = it }, earlier)
    val pay = if (start != null && days != null && older != null && last != null) SickLeave.calculate(start, days, listOf(older, last), record) else null
    if (start != null && start.year !in SickLeave.years) {
        ErrorText(stringResource(Res.string.salary_sick_years, SickLeave.years.first, SickLeave.years.last))
    } else if (start != null && days != null && older != null && last != null && pay != null && earlier != null) {
        val tax = Ndfl.taxOn(pay.total, earlier)
        AnswerCard(pay.total.fmt(2, grouping = true), pluralStringResource(Res.plurals.salary_sick_pay_days, days, days), copyValue = pay.total.fmt(2))
        ResultCard {
            listOf(older, last).zip(pay.counted).forEachIndexed { i, (entered, counted) ->
                if (counted < entered) KeyValueRow(stringResource(Res.string.salary_counted_for_year, start.year - 2 + i), counted.fmt(2, grouping = true))
            }
            KeyValueRow(Res.string.salary_average_daily.str(), pay.averageDaily.fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_benefit_per_day.str(), pay.daily.fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_employer_part.str(), pay.employer.fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_fund_part.str(), pay.fund.fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_tax.str(), tax.fmt(2, grouping = true))
            KeyValueRow(Res.string.salary_net_pay.str(), (pay.total - tax).fmt(2, grouping = true))
        }
        if (pay.fromMinimumWage) {
            Hint(stringResource(Res.string.salary_sick_minimum_wage, SickLeave.minimumWage.getValue(start.year).fmt(0, grouping = true)))
        }
        Hint(
            stringResource(
                Res.string.salary_sick_hint,
                SickLeave.contributionCaps.getValue(start.year - 2).fmt(0, grouping = true),
                start.year - 2,
                SickLeave.contributionCaps.getValue(start.year - 1).fmt(0, grouping = true),
                start.year - 1,
            ),
        )
    }
}

@Composable
private fun EarlierIncomeField(text: String, onChange: (String) -> Unit, value: Double?) {
    NumberField(
        value = text,
        onValueChange = onChange,
        label = Res.string.salary_earlier_income.str(),
        isError = text.isNotBlank() && value == null,
        supportingText = Res.string.salary_earlier_income_hint.str(),
    )
}

@Composable
private fun monthLabel(month: YearMonth): String = "${monthShortNames[month.month.number - 1].str()} ${month.year}"

@Composable
private fun monthRange(from: Int, to: Int): String =
    if (from == to) monthShortNames[from - 1].str() else "${monthShortNames[from - 1].str()} – ${monthShortNames[to - 1].str()}"

private fun monthLine(month: String, gross: String, tax: String, net: String, rate: String): String =
    month.padEnd(5) + gross.padStart(11) + tax.padStart(10) + net.padStart(11) + rate.padStart(7)
