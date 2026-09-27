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
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.SwitchRow
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

private enum class TermUnit { MONTHS, YEARS }

private const val MAX_MONTHS = 360

val loanCalculatorTool = Tool(
    id = "loan-calculator",
    category = ToolCategory.MONEY,
    title = Res.string.loan_calculator,
    description = Res.string.loan_calculator_description,
    icon = Icons.Filled.MonetizationOn,
    keywords = listOf("loan", "mortgage", "credit", "annuity", "interest", "schedule", "кредит", "ипотека", "аннуитет", "переплата", "график платежей"),
) { LoanCalculatorScreen() }

@Composable
private fun LoanCalculatorScreen() {
    var type by rememberSaveable { mutableStateOf(LoanType.ANNUITY) }
    var principalText by rememberSaveable { mutableStateOf("1000000") }
    var rateText by rememberSaveable { mutableStateOf("12") }
    var termText by rememberSaveable { mutableStateOf("5") }
    var termUnit by rememberSaveable { mutableStateOf(TermUnit.YEARS) }
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    val principal = principalText.toDoubleLenient()?.takeIf { it > 0 }
    val rate = rateText.toDoubleLenient()?.takeIf { it >= 0 }
    val term = termText.toDoubleLenient()
    val months = term?.let { if (termUnit == TermUnit.YEARS) (it * 12).roundToInt() else it.roundToInt() }?.takeIf { it >= 1 }
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
    if (months != null && months > MAX_MONTHS) {
        ErrorText(stringResource(Res.string.loan_term_limit, MAX_MONTHS))
    } else if (principal != null && rate != null && months != null) {
        val result = remember(principal, rate, months, type) { Loan.schedule(principal, rate, months, type) }
        val payment = result.schedule.first().payment
        AnswerCard(
            payment.fmt(2, grouping = true),
            (if (type == LoanType.ANNUITY) Res.string.monthly_payment else Res.string.first_payment).str(),
            copyValue = payment.fmt(2),
        )
        ResultCard {
            if (type != LoanType.ANNUITY) {
                KeyValueRow(Res.string.first_payment.str(), result.schedule.first().payment.fmt(2, grouping = true))
                KeyValueRow(Res.string.last_payment.str(), result.schedule.last().payment.fmt(2, grouping = true))
            }
            KeyValueRow(Res.string.total_paid.str(), result.total.fmt(2, grouping = true))
            KeyValueRow(Res.string.loan_total_interest.str(), result.interest.fmt(2, grouping = true))
            KeyValueRow(Res.string.overpayment.str(), stringResource(Res.string.percent_value, (result.interest / principal * 100).fmt(1)), copyable = false)
            KeyValueRow(Res.string.months.str(), months.toString(), copyable = false)
        }
        SwitchRow(
            label = Res.string.show_payment_schedule.str(),
            checked = showSchedule,
            onCheckedChange = { showSchedule = it },
        )
        if (showSchedule) {
            MonoTable(
                listOf(scheduleLine("#", Res.string.payment.str(), Res.string.principal.str(), Res.string.interest.str(), Res.string.loan_balance.str())) +
                    result.schedule.map { scheduleLine(it.month.toString(), it.payment.fmt(2), it.principal.fmt(2), it.interest.fmt(2), it.balance.fmt(2)) },
            )
        }
    }
}

private fun scheduleLine(month: String, payment: String, principal: String, interest: String, balance: String): String =
    month.padEnd(5) + payment.padStart(14) + principal.padStart(14) + interest.padStart(14) + balance.padStart(15)
