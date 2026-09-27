package com.vasmarfas.card.tools.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.monoFamily
import org.jetbrains.compose.resources.pluralStringResource

val programmerCalculatorTool = Tool(
    id = "programmer-calculator",
    category = ToolCategory.DEVELOPER,
    title = Res.string.programmer_calculator,
    description = Res.string.programmer_calculator_description,
    icon = Icons.Filled.Memory,
    keywords = listOf("bitwise", "hex", "binary", "shift", "xor", "two's complement", "побитовый", "сдвиг", "двоичный", "hex", "дополнительный код"),
) { ProgrammerCalculatorScreen() }

@Composable
private fun ProgrammerCalculatorScreen() {
    var base by rememberSaveable { mutableStateOf(NumBase.DEC) }
    var bits by rememberSaveable { mutableStateOf(32) }
    var aText by rememberSaveable { mutableStateOf("255") }
    var bText by rememberSaveable { mutableStateOf("15") }
    var op by rememberSaveable { mutableStateOf(BitOp.AND) }
    val a = remember(aText, base, bits) { ProgrammerMath.parse(aText, base, bits) }
    val b = remember(bText, base, bits) { ProgrammerMath.parse(bText, base, bits) }
    SegmentedChoice(
        options = NumBase.entries,
        selected = base,
        onSelect = { newBase ->
            a?.let { aText = ProgrammerMath.format(it, newBase, bits) }
            b?.let { bText = ProgrammerMath.format(it, newBase, bits) }
            base = newBase
        },
        label = { it.label },
    )
    SegmentedChoice(
        options = ProgrammerMath.wordSizes,
        selected = bits,
        onSelect = { newBits ->
            a?.let { aText = ProgrammerMath.format(it, base, newBits) }
            b?.let { bText = ProgrammerMath.format(it, base, newBits) }
            bits = newBits
        },
        label = { "$it ${Res.string.unit_bit.str()}" },
    )
    ToolInputField(
        value = aText,
        onValueChange = { aText = it },
        label = "A (${base.label})",
        keyboardType = KeyboardType.Ascii,
        isError = aText.isNotBlank() && a == null,
        monospace = true,
    )
    if (a != null) {
        BitRow(a, bits) { bit -> aText = ProgrammerMath.format(a xor (1L shl bit), base, bits) }
    }
    ChoiceChips(
        options = BitOp.entries,
        selected = op,
        onSelect = { op = it },
        label = { it.symbol },
    )
    if (!op.unary) {
        ToolInputField(
            value = bText,
            onValueChange = { bText = it },
            label = "B (${base.label})",
            keyboardType = KeyboardType.Ascii,
            isError = bText.isNotBlank() && b == null,
            monospace = true,
        )
    }
    val operandsValid = a != null && (op.unary || b != null)
    val result = if (a != null && operandsValid) ProgrammerMath.apply(op, a, b ?: 0L, bits) else null
    when {
        a == null && aText.isNotBlank() -> ErrorText(pluralStringResource(Res.plurals.operand_does_not_fit, bits, "A", bits, base.label))
        !op.unary && b == null && bText.isNotBlank() -> ErrorText(pluralStringResource(Res.plurals.operand_does_not_fit, bits, "B", bits, base.label))
        operandsValid && result == null -> ErrorText(Res.string.division_by_zero.str())
    }
    if (result != null) {
        ValueCard(Res.string.result.str(), result, bits)
    }
    if (a != null) {
        ValueCard("A", a, bits)
    }
}

@Composable
private fun ValueCard(title: String, value: Long, bits: Int) {
    ResultCard(title) {
        KeyValueRow("BIN", ProgrammerMath.grouped(ProgrammerMath.format(value, NumBase.BIN, bits), 4))
        KeyValueRow("OCT", ProgrammerMath.format(value, NumBase.OCT, bits))
        KeyValueRow(Res.string.dec_signed.str(), ProgrammerMath.format(value, NumBase.DEC, bits))
        KeyValueRow(Res.string.dec_unsigned.str(), ProgrammerMath.unsignedDecimal(value, bits))
        KeyValueRow("HEX", ProgrammerMath.format(value, NumBase.HEX, bits))
        KeyValueRow(Res.string.bits_set.str(), ProgrammerMath.truncate(value, bits).countOneBits().toString(), copyable = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BitRow(value: Long, bits: Int, onToggle: (Int) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (byte in (bits / 8 - 1) downTo 0) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (bit in (byte * 8 + 7) downTo byte * 8) {
                        val set = ((value ushr bit) and 1L) == 1L
                        Text(
                            text = if (set) "1" else "0",
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (set) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                                .clickable { onToggle(bit) }
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = monoFamily()),
                        )
                    }
                }
                Text(
                    "${byte * 8 + 7}…${byte * 8}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
