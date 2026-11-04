package com.vasmarfas.card.tools.money

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private enum class TermUnit { MONTHS, YEARS }

private const val MAX_MONTHS = 360

val loanCalculatorTool = Tool(
    id = "loan-calculator",
    category = ToolCategory.MONEY,
    title = Res.string.loan_calculator,
    description = Res.string.loan_calculator_description,
    icon = Icons.Filled.MonetizationOn,
    keywords = listOf(
        "loan", "mortgage", "credit", "annuity", "interest", "schedule", "prepayment", "early repayment",
        "кредит", "ипотека", "аннуитет", "переплата", "график платежей", "досрочное погашение",
    ),
) { LoanCalculatorScreen() }

@Composable
private fun LoanCalculatorScreen() {
    var type by rememberSaveable { mutableStateOf(LoanType.ANNUITY) }
    var principalText by rememberSaveable { mutableStateOf("1000000") }
    var rateText by rememberSaveable { mutableStateOf("12") }
    var termText by rememberSaveable { mutableStateOf("5") }
    var termUnit by rememberSaveable { mutableStateOf(TermUnit.YEARS) }
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    var prepay by rememberSaveable { mutableStateOf(false) }
    var prepayMode by rememberSaveable { mutableStateOf(PrepayMode.SHORTEN_TERM) }
    var oneOffText by rememberSaveable { mutableStateOf("100000") }
    var oneOffMonthText by rememberSaveable { mutableStateOf("12") }
    var monthlyExtraText by rememberSaveable { mutableStateOf("") }
    val principal = principalText.toDoubleLenient()?.takeIf { it > 0 }
    val rate = rateText.toDoubleLenient()?.takeIf { it >= 0 }
    val term = termText.toDoubleLenient()
    val months = term?.let { if (termUnit == TermUnit.YEARS) (it * 12).roundToInt() else it.roundToInt() }?.takeIf { it >= 1 }
    val oneOff = if (oneOffText.isBlank()) 0.0 else oneOffText.toDoubleLenient()?.takeIf { it >= 0 }
    val oneOffMonth = oneOffMonthText.trim().toIntOrNull()?.takeIf { it >= 1 && (months == null || it <= months) }
    val monthlyExtra = if (monthlyExtraText.isBlank()) 0.0 else monthlyExtraText.toDoubleLenient()?.takeIf { it >= 0 }
    val prepayment = if (prepay && oneOff != null && monthlyExtra != null && oneOff + monthlyExtra > 0 && (oneOff == 0.0 || oneOffMonth != null)) {
        Prepayment(monthlyExtra, oneOff, oneOffMonth ?: 0, prepayMode)
    } else {
        null
    }
    SegmentedChoice(
        options = LoanType.entries,
        selected = type,
        onSelect = { type = it },
        label = { if (it == LoanType.ANNUITY) Res.string.annuity.str() else Res.string.differentiated.str() },
    )
    NumberField(
        value = principalText,
        onValueChange = { principalText = it },
        label = Res.string.loan_amount.str(),
        isError = principalText.isNotBlank() && principal == null,
    )
    NumberField(
        value = rateText,
        onValueChange = { rateText = it },
        label = Res.string.annual_rate.str(),
        suffix = "%",
        isError = rateText.isNotBlank() && rate == null,
    )
    NumberField(
        value = termText,
        onValueChange = { termText = it },
        label = Res.string.term.str(),
        isError = termText.isNotBlank() && months == null,
    )
    SegmentedChoice(
        options = TermUnit.entries,
        selected = termUnit,
        onSelect = { termUnit = it },
        label = { if (it == TermUnit.MONTHS) Res.string.months.str() else Res.string.years.str() },
    )
    SwitchRow(
        label = Res.string.loan_prepayment.str(),
        checked = prepay,
        onCheckedChange = { prepay = it },
    )
    if (prepay) {
        SegmentedChoice(
            options = PrepayMode.entries,
            selected = prepayMode,
            onSelect = { prepayMode = it },
            label = { if (it == PrepayMode.SHORTEN_TERM) Res.string.loan_prepay_shorten.str() else Res.string.loan_prepay_lower.str() },
        )
        NumberField(
            value = oneOffText,
            onValueChange = { oneOffText = it },
            label = Res.string.loan_prepay_one_off.str(),
            isError = oneOffText.isNotBlank() && oneOff == null,
        )
        NumberField(
            value = oneOffMonthText,
            onValueChange = { oneOffMonthText = it },
            label = Res.string.loan_prepay_one_off_month.str(),
            isError = oneOff != 0.0 && oneOffMonth == null,
        )
        NumberField(
            value = monthlyExtraText,
            onValueChange = { monthlyExtraText = it },
            label = Res.string.loan_prepay_monthly.str(),
            isError = monthlyExtraText.isNotBlank() && monthlyExtra == null,
        )
        Hint(Res.string.loan_prepay_hint.str())
    }
    if (months != null && months > MAX_MONTHS) {
        ErrorText(stringResource(Res.string.loan_term_limit, MAX_MONTHS))
    } else if (principal != null && rate != null && months != null) {
        val plain = remember(principal, rate, months, type) { Loan.schedule(principal, rate, months, type) }
        val result = remember(principal, rate, months, type, prepayment) { Loan.schedule(principal, rate, months, type, prepayment) }
        val saved = remember(plain, result) { Loan.interestSaved(plain, result) }
        val payment = result.schedule.first().payment
        AnswerCard(
            payment.fmt(2, grouping = true),
            (if (type == LoanType.ANNUITY && prepayment == null) Res.string.monthly_payment else Res.string.first_payment).str(),
            copyValue = payment.fmt(2),
        )
        ResultCard {
            if (type != LoanType.ANNUITY || prepayment != null) {
                KeyValueRow(Res.string.first_payment.str(), result.schedule.first().payment.fmt(2, grouping = true))
                KeyValueRow(Res.string.last_payment.str(), result.schedule.last().payment.fmt(2, grouping = true))
            }
            KeyValueRow(Res.string.total_paid.str(), result.total.fmt(2, grouping = true))
            KeyValueRow(Res.string.loan_total_interest.str(), result.interest.fmt(2, grouping = true))
            KeyValueRow(Res.string.overpayment.str(), stringResource(Res.string.percent_value, (result.interest / principal * 100).fmt(1)), copyable = false)
            KeyValueRow(Res.string.months.str(), result.schedule.size.toString(), copyable = false)
            if (prepayment != null) {
                KeyValueRow(Res.string.loan_interest_saved.str(), saved.last().fmt(2, grouping = true))
                val monthsCut = months - result.schedule.size
                if (monthsCut > 0) {
                    KeyValueRow(Res.string.loan_term_saved.str(), pluralStringResource(Res.plurals.month_count, monthsCut, monthsCut), mono = false, copyable = false)
                }
            }
        }
        SwitchRow(
            label = Res.string.show_payment_schedule.str(),
            checked = showSchedule,
            onCheckedChange = { showSchedule = it },
        )
        if (showSchedule) {
            val prepaidTitle = Res.string.loan_column_prepaid.str()
            val savedTitle = Res.string.loan_column_saved.str()
            MonoTable(
                listOf(
                    scheduleLine("#", Res.string.payment.str(), Res.string.principal.str(), Res.string.interest.str(), Res.string.loan_balance.str()) +
                        if (prepayment != null) prepayColumns(prepaidTitle, savedTitle) else "",
                ) + result.schedule.mapIndexed { i, row ->
                    scheduleLine(row.month.toString(), row.payment.fmt(2), row.principal.fmt(2), row.interest.fmt(2), row.balance.fmt(2)) +
                        if (prepayment != null) prepayColumns(row.extra.fmt(2), saved[i].fmt(2)) else ""
                },
            )
        }
    }
}

private fun scheduleLine(month: String, payment: String, principal: String, interest: String, balance: String): String =
    month.padEnd(5) + payment.padStart(14) + principal.padStart(14) + interest.padStart(14) + balance.padStart(15)

private fun prepayColumns(prepaid: String, saved: String): String = prepaid.padStart(14) + saved.padStart(14)
