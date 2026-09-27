package com.vasmarfas.card.tools.calculators

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.AnswerCard
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource

val fractionCalculatorTool = Tool(
    id = "fraction-calculator",
    category = ToolCategory.CALCULATORS,
    title = Res.string.fractions,
    description = Res.string.fraction_calculator_description,
    icon = Icons.Filled.Functions,
    keywords = listOf(
        "fraction", "numerator", "denominator", "mixed number", "simplify", "reduce", "gcd", "lcm", "repeating decimal",
        "дроби", "дробь", "числитель", "знаменатель", "смешанное число", "сократить", "нод", "нок", "десятичная",
        "периодическая", "обыкновенная",
    ),
) { FractionScreen() }

private enum class FractionMode { SOLVE, SIMPLIFY, DECIMAL }

private class FractionInput(val fraction: Fraction?, val mixed: String?, val zeroDenominator: Boolean)

private fun readFraction(whole: String, numerator: String, denominator: String): FractionInput {
    val w = whole.trim().ifEmpty { "0" }.toLongOrNull()
    val n = numerator.trim().ifEmpty { "0" }.toLongOrNull()
    val d = denominator.trim().ifEmpty { "1" }.toLongOrNull()
    if (w == null || n == null || d == null) return FractionInput(null, null, false)
    if (d == 0L) return FractionInput(null, null, true)
    val fraction = runCatching { Fractions.mixed(w, n, d) }.getOrNull()
    val mixed = if (w != 0L && n != 0L) "$w $n/$d" else null
    return FractionInput(fraction, mixed, false)
}

@Composable
private fun FractionScreen() {
    var mode by rememberSaveable { mutableStateOf(FractionMode.SOLVE) }
    SegmentedChoice(
        options = FractionMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = {
            when (it) {
                FractionMode.SOLVE -> Res.string.fraction_mode_solve.str()
                FractionMode.SIMPLIFY -> Res.string.fraction_mode_simplify.str()
                FractionMode.DECIMAL -> Res.string.fraction_mode_decimal.str()
            }
        },
    )
    val saved = rememberSaveableStateHolder()
    saved.SaveableStateProvider(mode) {
        when (mode) {
            FractionMode.SOLVE -> SolveFractions()
            FractionMode.SIMPLIFY -> SimplifyFraction()
            FractionMode.DECIMAL -> DecimalToFraction()
        }
    }
}

@Composable
private fun SolveFractions() {
    var leftWhole by rememberSaveable { mutableStateOf("") }
    var leftNumerator by rememberSaveable { mutableStateOf("1") }
    var leftDenominator by rememberSaveable { mutableStateOf("2") }
    var rightWhole by rememberSaveable { mutableStateOf("") }
    var rightNumerator by rememberSaveable { mutableStateOf("1") }
    var rightDenominator by rememberSaveable { mutableStateOf("3") }
    var op by rememberSaveable { mutableStateOf(FractionOp.ADD) }
    val left = readFraction(leftWhole, leftNumerator, leftDenominator)
    val right = readFraction(rightWhole, rightNumerator, rightDenominator)
    ToolSection(Res.string.first_fraction.str()) {
        FractionFields(leftWhole, leftNumerator, leftDenominator, { leftWhole = it }, { leftNumerator = it }, { leftDenominator = it }, left.zeroDenominator)
        Text(Res.string.whole_part_can_stay_empty.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ChoiceChips(
        options = FractionOp.entries,
        selected = op,
        onSelect = { op = it },
        label = { if (it == FractionOp.COMPARE) Res.string.compare.str() else it.symbol },
    )
    ToolSection(Res.string.second_fraction.str()) {
        FractionFields(rightWhole, rightNumerator, rightDenominator, { rightWhole = it }, { rightNumerator = it }, { rightDenominator = it }, right.zeroDenominator)
    }
    if (left.zeroDenominator || right.zeroDenominator) {
        ErrorText(Res.string.denominator_cannot_be_zero.str())
        return
    }
    val a = left.fraction ?: return
    val b = right.fraction ?: return
    if (op == FractionOp.DIVIDE && b.numerator == 0L) {
        ErrorText(Res.string.division_by_zero.str())
        return
    }
    val solution = remember(a, b, op, left.mixed, right.mixed) { runCatching { Fractions.solve(a, op, b, left.mixed, right.mixed) }.getOrNull() }
    if (solution == null) {
        ErrorText(Res.string.numbers_too_large.str())
        return
    }
    val value = solution.value
    if (value != null) {
        FractionAnswer(value)
    } else {
        val sign = when (solution.comparison) {
            1 -> ">"
            -1 -> "<"
            else -> "="
        }
        AnswerCard(
            value = "${a.reduced()} $sign ${b.reduced()}",
            caption = when (solution.comparison) {
                1 -> Res.string.first_fraction_is_bigger.str()
                -1 -> Res.string.second_fraction_is_bigger.str()
                else -> Res.string.fractions_are_equal.str()
            },
        )
    }
    SolutionSteps(solution.steps)
}

@Composable
private fun SimplifyFraction() {
    var whole by rememberSaveable { mutableStateOf("") }
    var numerator by rememberSaveable { mutableStateOf("18") }
    var denominator by rememberSaveable { mutableStateOf("24") }
    val input = readFraction(whole, numerator, denominator)
    FractionFields(whole, numerator, denominator, { whole = it }, { numerator = it }, { denominator = it }, input.zeroDenominator)
    if (input.zeroDenominator) {
        ErrorText(Res.string.denominator_cannot_be_zero.str())
        return
    }
    val fraction = input.fraction ?: return
    val solution = remember(fraction, input.mixed) { Fractions.simplify(fraction, input.mixed) }
    FractionAnswer(solution.value ?: return)
    if (solution.steps.none { it is FractionStep.Reduce }) {
        Text(Res.string.fraction_cannot_be_reduced.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (solution.steps.isNotEmpty()) SolutionSteps(solution.steps)
}

@Composable
private fun DecimalToFraction() {
    var text by rememberSaveable { mutableStateOf("0.375") }
    ToolInputField(
        value = text,
        onValueChange = { text = it },
        label = Res.string.decimal_fraction.str(),
        keyboardType = KeyboardType.Ascii,
        supportingText = Res.string.repeating_part_in_brackets.str(),
    )
    if (text.isBlank()) return
    val solution = remember(text) { runCatching { Fractions.fromDecimal(text) } }
    val value = solution.getOrNull()?.value
    if (solution.isFailure) {
        ErrorText(Res.string.numbers_too_large.str())
        return
    }
    if (value == null) {
        ErrorText(Res.string.not_a_decimal_number.str())
        return
    }
    FractionAnswer(value)
    val close = remember(value) { Fractions.approximate(value, 100) }
    if (close != value) {
        KeyValueRow(Res.string.close_fraction_up_to_100.str(), close.mixed())
    }
    SolutionSteps(solution.getOrNull()?.steps.orEmpty())
}

@Composable
private fun FractionFields(
    whole: String,
    numerator: String,
    denominator: String,
    onWhole: (String) -> Unit,
    onNumerator: (String) -> Unit,
    onDenominator: (String) -> Unit,
    zeroDenominator: Boolean,
) {
    val style = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center)
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = whole,
            onValueChange = onWhole,
            modifier = Modifier.width(96.dp),
            textStyle = style,
            singleLine = true,
            label = { Text(Res.string.fraction_whole.str()) },
            keyboardOptions = keyboard,
        )
        Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = numerator,
                onValueChange = onNumerator,
                textStyle = style,
                singleLine = true,
                label = { Text(Res.string.numerator.str()) },
                keyboardOptions = keyboard,
            )
            HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            OutlinedTextField(
                value = denominator,
                onValueChange = onDenominator,
                textStyle = style,
                singleLine = true,
                label = { Text(Res.string.denominator.str()) },
                isError = zeroDenominator,
                keyboardOptions = keyboard,
            )
        }
    }
}

@Composable
private fun FractionAnswer(value: Fraction) {
    val decimal = remember(value) { runCatching { Fractions.expand(value).toString() }.getOrNull() }
    val percent = remember(value) { runCatching { Fractions.expand(Fraction(Fractions.times(value.numerator, 100), value.denominator)).toString() }.getOrNull() }
    val improper = abs(value.numerator) > value.denominator && !value.isWhole
    ResultCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FractionView(value, Modifier.weight(1f))
            CopyIconButton(value.mixed())
        }
        if (improper) {
            KeyValueRow(Res.string.improper_fraction.str(), value.toString())
            KeyValueRow(Res.string.mixed_number.str(), value.mixed())
        }
        decimal?.let { KeyValueRow(Res.string.decimal_fraction.str(), it) }
        percent?.let { KeyValueRow(Res.string.as_percent.str(), stringResource(Res.string.percent_value, it)) }
    }
}

@Composable
private fun FractionView(value: Fraction, modifier: Modifier = Modifier) {
    val whole = abs(value.numerator) / value.denominator
    val rest = abs(value.numerator) % value.denominator
    val sign = if (value.numerator < 0) "−" else ""
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (whole != 0L || rest == 0L) {
            Text("$sign$whole", style = MaterialTheme.typography.displaySmall)
        } else if (sign.isNotEmpty()) {
            Text(sign, style = MaterialTheme.typography.displaySmall)
        }
        if (rest != 0L) {
            Column(Modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$rest", style = MaterialTheme.typography.headlineMedium)
                HorizontalDivider(thickness = 2.dp, color = LocalContentColor.current)
                Text("${value.denominator}", style = MaterialTheme.typography.headlineMedium)
            }
        }
    }
}

@Composable
private fun SolutionSteps(steps: List<FractionStep>) {
    if (steps.isEmpty()) return
    ResultCard(Res.string.solution.str()) {
        steps.forEachIndexed { index, step ->
            Text("${index + 1}. ${stepText(step)}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun stepText(step: FractionStep): String = when (step) {
    is FractionStep.FromDecimal -> stringResource(Res.string.step_from_decimal, "${step.text} = ${step.fraction}")
    is FractionStep.ToImproper -> stringResource(Res.string.step_to_improper, step.mixed, step.improper.toString())
    is FractionStep.CommonDenominator -> stringResource(
        Res.string.step_common_denominator,
        step.left.denominator,
        step.right.denominator,
        step.lcm,
        "${step.left} = ${step.leftScaled}",
        "${step.right} = ${step.rightScaled}",
    )
    is FractionStep.Combine -> stringResource(
        if (step.op == FractionOp.ADD) Res.string.step_add_numerators else Res.string.step_subtract_numerators,
        "${step.left} ${step.op.symbol} ${step.right} = ${step.result}",
    )
    is FractionStep.Compare -> stringResource(
        Res.string.step_compare_numerators,
        "${step.left} ${if (step.sign > 0) ">" else if (step.sign < 0) "<" else "="} ${step.right}",
    )
    is FractionStep.Flip -> stringResource(Res.string.step_flip_divisor, "${step.left} ÷ ${step.divisor} = ${step.left} × ${step.flipped}")
    is FractionStep.Multiply -> stringResource(Res.string.step_multiply, "${step.left} × ${step.right} = ${step.result}")
    is FractionStep.Reduce -> stringResource(
        Res.string.step_reduce,
        abs(step.from.numerator),
        step.from.denominator,
        step.gcd,
        "${step.from} = ${step.to}",
    )
    is FractionStep.WholePart -> stringResource(Res.string.step_whole_part, "${step.fraction} = ${step.fraction.mixed()}")
}
