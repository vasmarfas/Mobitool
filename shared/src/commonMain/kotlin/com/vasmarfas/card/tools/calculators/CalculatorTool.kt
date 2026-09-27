package com.vasmarfas.card.tools.calculators

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.converters.fmtSig
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.CopyIconButton
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.ResultCard
import org.jetbrains.compose.resources.stringResource

private class HistoryEntry(val expression: String, val result: String)

private const val HISTORY_KEY = "calculator.history"

private val functions = listOf("sin" to "sin(", "cos" to "cos(", "tan" to "tan(", "ln" to "ln(", "log" to "log(", "√" to "√(", "xʸ" to "^", "x!" to "!", "π" to "π", "e" to "e")

private val keypad = listOf(
    listOf("C", "(", ")", "÷"),
    listOf("7", "8", "9", "×"),
    listOf("4", "5", "6", "−"),
    listOf("1", "2", "3", "+"),
    listOf("0", ".", "⌫", "="),
)

val calculatorTool = Tool(
    id = "calculator",
    category = ToolCategory.CALCULATORS,
    title = Res.string.scientific_calculator,
    description = Res.string.calculator_description,
    icon = Icons.Filled.Calculate,
    keywords = listOf("math", "expression", "sin", "cos", "sqrt", "log", "factorial", "математика", "выражение", "корень", "синус", "логарифм", "факториал"),
) { CalculatorScreen() }

private fun loadHistory(): List<HistoryEntry> = Prefs.store.get(HISTORY_KEY).orEmpty().lines()
    .mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { HistoryEntry(it[0], it[1]) } }

@Composable
private fun CalculatorScreen() {
    var field by remember { mutableStateOf(TextFieldValue()) }
    var degrees by rememberSaveable { mutableStateOf(true) }
    var previous by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var fresh by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<HistoryEntry>().apply { addAll(loadHistory()) } }
    val input = field.text
    val preview = remember(input, degrees) {
        if (input.isBlank() || input.toDoubleOrNull() != null) null else runCatching { ExpressionParser.evaluate(input, degrees).fmtSig(12) }.getOrNull()
    }

    fun replace(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
        failure = null
    }

    fun type(token: String) {
        val continues = token.first() in "+−×÷^!)"
        val before = if (fresh && !continues) "" else input
        val glue = if (token == "e" && before.lastOrNull()?.let { it.isLetterOrDigit() || it == '.' } == true) "×" else ""
        replace(before + glue + token)
        fresh = false
    }

    fun erase() {
        val function = functions.map { it.second }.filter { it.length > 1 }.firstOrNull { input.endsWith(it) }
        replace(input.dropLast(function?.length ?: 1))
        fresh = false
    }

    fun evaluate() {
        if (input.isBlank()) return
        runCatching { ExpressionParser.evaluate(input, degrees) }
            .onSuccess { value ->
                val result = value.fmtSig(12)
                history.add(0, HistoryEntry(input, result))
                if (history.size > 10) history.removeAt(history.lastIndex)
                Prefs.store.put(HISTORY_KEY, history.joinToString("\n") { "${it.expression}\t${it.result}" })
                previous = "$input ="
                if (value.isFinite()) replace(result)
                fresh = true
            }
            .onFailure { failure = it }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AssistChip(
                            onClick = { degrees = !degrees },
                            label = { Text(if (degrees) Res.string.degrees.str() else Res.string.radians.str()) },
                            leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                        Text(
                            previous.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BasicTextField(
                        value = field,
                        onValueChange = { changed ->
                            if ('=' in changed.text) {
                                evaluate()
                            } else {
                                field = changed
                                failure = null
                                fresh = false
                            }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.displaySmall.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { evaluate() }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterEnd) {
                                if (input.isEmpty()) Text("0", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                inner()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            preview?.let { "= $it" }.orEmpty(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End,
                        )
                        CopyIconButton(preview ?: input.ifEmpty { "0" })
                    }
                }
            }
            failure?.let { ErrorText((it as? ExpressionException)?.let { e -> stringResource(e.error, *e.args) } ?: it.message ?: Res.string.error.str()) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                functions.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, token) ->
                            Key(label, MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f).height(48.dp)) { type(token) }
                        }
                    }
                }
                keypad.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { label ->
                            val colors = MaterialTheme.colorScheme
                            val modifier = Modifier.weight(1f).height(56.dp)
                            when (label) {
                                "C" -> Key(label, colors.tertiaryContainer, colors.onTertiaryContainer, modifier) {
                                    replace("")
                                    previous = null
                                }
                                "⌫" -> Key(label, colors.surfaceContainerHighest, colors.onSurface, modifier, Icons.AutoMirrored.Filled.Backspace, Res.string.erase.str()) { erase() }
                                "=" -> Key(label, colors.primary, colors.onPrimary, modifier) { evaluate() }
                                "(", ")", "÷", "×", "−", "+" -> Key(label, colors.secondaryContainer, colors.onSecondaryContainer, modifier) { type(label) }
                                else -> Key(label, colors.surfaceContainerHighest, colors.onSurface, modifier) { type(label) }
                            }
                        }
                    }
                }
            }
            if (history.isNotEmpty()) {
                ResultCard(Res.string.history_tap_to_reuse.str()) {
                    history.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { type(entry.result) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                entry.expression,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text("= ${entry.result}", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    ActionButton(
                        text = Res.string.clear_history.str(),
                        onClick = {
                            history.clear()
                            Prefs.store.remove(HISTORY_KEY)
                        },
                    )
                }
            }
        }
    }
}

// never takes focus, so a hardware keyboard keeps typing into the display
@Composable
private fun Key(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier,
    icon: ImageVector? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.focusProperties { canFocus = false },
        shape = RoundedCornerShape(20.dp),
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = description) else Text(label, style = MaterialTheme.typography.titleLarge)
        }
    }
}
