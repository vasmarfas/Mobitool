package com.vasmarfas.card.tools.converters

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.stringResource

private val bases = (2..36).toList()
private val quickBases = listOf(2, 8, 10, 16)

val numberBaseTool = Tool(
    id = "number-base",
    category = ToolCategory.CONVERTERS,
    title = Res.string.number_base_converter,
    description = Res.string.number_base_description,
    icon = Icons.Filled.Numbers,
    keywords = listOf("binary", "hex", "octal", "radix", "base", "двоичное", "шестнадцатеричное", "восьмеричное", "основание"),
) { NumberBaseScreen() }

@Composable
private fun NumberBaseScreen() {
    var input by rememberSaveable { mutableStateOf("255") }
    var from by rememberSaveable { mutableStateOf(10) }
    var to by rememberSaveable { mutableStateOf(16) }
    val result = remember(input, from, to) { BaseConvert.convert(input, from, to) }
    ToolInputField(
        value = input,
        onValueChange = { input = it },
        label = Res.string.number.str(),
        placeholder = "1010 · 0xFF · zz",
        keyboardType = KeyboardType.Ascii,
        isError = input.isNotBlank() && result == null,
        supportingText = if (input.isNotBlank() && result == null) stringResource(Res.string.invalid_digits_for_base, from) else null,
        monospace = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DropdownChoice(
            options = bases,
            selected = from,
            onSelect = { from = it },
            label = Res.string.from_base.str(),
            text = { baseName(it) },
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                val previous = from
                from = to
                to = previous
                if (result != null) input = result
            },
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = Res.string.swap.str())
        }
        DropdownChoice(
            options = bases,
            selected = to,
            onSelect = { to = it },
            label = Res.string.to_base.str(),
            text = { baseName(it) },
            modifier = Modifier.weight(1f),
        )
    }
    ChoiceChips(
        options = quickBases,
        selected = from,
        onSelect = { from = it },
        label = { stringResource(Res.string.from__2, baseName(it)) },
    )
    if (result != null) {
        ResultCard {
            KeyValueRow(stringResource(Res.string.base_n, to), result)
        }
        ResultCard(Res.string.common_bases.str()) {
            KeyValueRow("BIN", BaseConvert.convert(input, from, 2) ?: "")
            KeyValueRow("OCT", BaseConvert.convert(input, from, 8) ?: "")
            KeyValueRow("DEC", BaseConvert.convert(input, from, 10) ?: "")
            KeyValueRow("HEX", BaseConvert.convert(input, from, 16) ?: "")
        }
    }
}

private fun baseName(base: Int): String = when (base) {
    2 -> "2 · BIN"
    8 -> "8 · OCT"
    10 -> "10 · DEC"
    16 -> "16 · HEX"
    else -> base.toString()
}
