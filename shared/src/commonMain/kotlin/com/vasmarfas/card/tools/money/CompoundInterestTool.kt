package com.vasmarfas.card.tools.money

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
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
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.MonoTable
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolSection
import org.jetbrains.compose.resources.stringResource

private const val MAX_YEARS = 100

val compoundInterestTool = Tool(
    id = "compound-interest",
    category = ToolCategory.MONEY,
    title = Res.string.compound_interest,
    description = Res.string.compound_interest_description,
    icon = Icons.AutoMirrored.Filled.TrendingUp,
    keywords = listOf("deposit", "investment", "savings", "capitalization", "interest", "вклад", "инвестиции", "капитализация", "накопления", "проценты"),
) { CompoundInterestScreen() }

@Composable
private fun CompoundInterestScreen() {
    var principalText by rememberSaveable { mutableStateOf("100000") }
    var rateText by rememberSaveable { mutableStateOf("8") }
    var yearsText by rememberSaveable { mutableStateOf("10") }
    var contributionText by rememberSaveable { mutableStateOf("5000") }
    var compounding by rememberSaveable { mutableStateOf(Compounding.MONTHLY) }
    val principal = principalText.toDoubleLenient()?.takeIf { it >= 0 }
    val rate = rateText.toDoubleLenient()
    val years = yearsText.trim().toIntOrNull()?.takeIf { it >= 1 }
    val contribution = if (contributionText.isBlank()) 0.0 else contributionText.toDoubleLenient()?.takeIf { it >= 0 }
    NumberField(
        value = principalText,
        onValueChange = { principalText = it },
        label = Res.string.initial_amount.str(),
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
        value = yearsText,
        onValueChange = { yearsText = it },
        label = Res.string.years.str(),
        isError = yearsText.isNotBlank() && years == null,
    )
    NumberField(
        value = contributionText,
        onValueChange = { contributionText = it },
        label = Res.string.monthly_contribution.str(),
        isError = contributionText.isNotBlank() && contribution == null,
    )
    ChoiceChips(
        options = Compounding.entries,
        selected = compounding,
        onSelect = { compounding = it },
        label = { it.title.str() },
    )
    if (years != null && years > MAX_YEARS) {
        ErrorText(stringResource(Res.string.compound_term_limit, MAX_YEARS))
    } else if (principal != null && rate != null && years != null && contribution != null) {
        val rows = remember(principal, rate, years, contribution, compounding) {
            CompoundInterest.grow(principal, rate, years, compounding, contribution)
        }
        val last = rows.last()
        AnswerCard(last.balance.fmt(2, grouping = true), Res.string.final_balance.str(), copyValue = last.balance.fmt(2))
        ResultCard {
            KeyValueRow(Res.string.total_contributed.str(), last.contributed.fmt(2, grouping = true))
            KeyValueRow(Res.string.total_interest.str(), last.interest.fmt(2, grouping = true))
            KeyValueRow(
                Res.string.growth.str(),
                if (last.contributed == 0.0) "—" else stringResource(Res.string.percent_value, (last.interest / last.contributed * 100).fmt(1)),
                copyable = false,
            )
        }
        ToolSection(Res.string.by_year.str()) {
            MonoTable(
                listOf(yearLine(Res.string.year.str(), Res.string.contributed.str(), Res.string.interest.str(), Res.string.balance.str())) +
                    rows.map { yearLine(it.year.toString(), it.contributed.fmt(2), it.interest.fmt(2), it.balance.fmt(2)) },
            )
        }
    }
}

private fun yearLine(year: String, contributed: String, interest: String, balance: String): String =
    year.padEnd(6) + contributed.padStart(16) + interest.padStart(16) + balance.padStart(16)
