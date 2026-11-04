package com.vasmarfas.card.tools.electronics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource

private enum class SmdPart { RESISTOR, CAPACITOR }

private enum class SmdDirection { DECODE, ENCODE }

val smdCodesTool = Tool(
    id = "smd-codes",
    category = ToolCategory.ELECTRONICS,
    title = Res.string.smd_codes,
    description = Res.string.smd_codes_description,
    icon = Icons.Filled.Memory,
    keywords = listOf(
        "smd", "smt", "code", "marking", "eia-96", "eia96", "resistor", "capacitor", "chip", "103", "104", "4r7",
        "смд", "маркировка", "код", "резистор", "конденсатор", "чип", "номинал", "расшифровка",
    ),
) { SmdCodesScreen() }

@Composable
private fun SmdCodesScreen() {
    var part by rememberSaveable { mutableStateOf(SmdPart.RESISTOR) }
    var direction by rememberSaveable { mutableStateOf(SmdDirection.DECODE) }
    var codeText by rememberSaveable { mutableStateOf("103") }
    var valueText by rememberSaveable { mutableStateOf("4.7k") }
    val farad = Res.string.unit_farad.str()
    SegmentedChoice(
        options = SmdPart.entries,
        selected = part,
        onSelect = { part = it },
        label = { if (it == SmdPart.RESISTOR) Res.string.smd_codes_resistor.str() else Res.string.smd_codes_capacitor.str() },
    )
    SegmentedChoice(
        options = SmdDirection.entries,
        selected = direction,
        onSelect = { direction = it },
        label = { if (it == SmdDirection.DECODE) Res.string.smd_codes_code_to_value.str() else Res.string.smd_codes_value_to_code.str() },
    )
    when {
        direction == SmdDirection.DECODE && part == SmdPart.RESISTOR -> {
            val readings = SmdCode.resistor(codeText)
            SmdField(codeText, Res.string.smd_codes_code.str(), "103 · 1002 · 4R7 · 01C", codeText.isNotBlank() && readings.isEmpty()) { codeText = it }
            if (readings.size > 1) Hint(Res.string.smd_codes_two_readings.str())
            readings.forEach { ReadingCard(it) }
        }
        direction == SmdDirection.DECODE -> {
            val farads = SmdCode.capacitor(codeText)
            SmdField(codeText, Res.string.smd_codes_code.str(), "104 · 225 · 4R7", codeText.isNotBlank() && farads == null) { codeText = it }
            if (farads != null) ResultCard { KeyValueRow(Res.string.smd_codes_capacitance.str(), Si.format(farads, farad)) }
            Hint(Res.string.smd_codes_capacitor_hint.str())
        }
        part == SmdPart.RESISTOR -> {
            val ohms = Si.parse(valueText)?.takeIf { it >= 0 }
            SmdField(valueText, Res.string.resistance.str(), "4.7k · 220 · 1M · 4R7", valueText.isNotBlank() && ohms == null) { valueText = it }
            if (ohms != null) CodesCard(ohms)
        }
        else -> {
            val farads = Si.parse(valueText)?.takeIf { it > 0 }
            SmdField(valueText, Res.string.smd_codes_capacitance.str(), "100n · 4.7u · 22p", valueText.isNotBlank() && farads == null) { valueText = it }
            val code = farads?.let(SmdCode::capacitorCode)
            if (farads != null && code == null) ErrorText(Res.string.smd_codes_no_code.str())
            if (code != null) {
                ResultCard {
                    KeyValueRow(Res.string.smd_codes_code.str(), code)
                    SmdCode.capacitor(code)?.let { KeyValueRow(Res.string.smd_codes_capacitance.str(), Si.format(it, farad)) }
                }
            }
        }
    }
}

@Composable
private fun SmdField(value: String, label: String, placeholder: String, isError: Boolean, onChange: (String) -> Unit) {
    ToolInputField(
        value = value,
        onValueChange = onChange,
        label = label,
        placeholder = placeholder,
        keyboardType = KeyboardType.Ascii,
        isError = isError,
        monospace = true,
    )
}

@Composable
private fun ReadingCard(reading: SmdReading) {
    ResultCard {
        KeyValueRow(Res.string.resistance.str(), Si.format(reading.value, Res.string.unit_ohm.str()))
        KeyValueRow(Res.string.smd_codes_format.str(), reading.format.title.str(), mono = false, copyable = false)
        reading.format.tolerance?.let {
            KeyValueRow(Res.string.smd_codes_usual_tolerance.str(), stringResource(Res.string.percent_value, "±" + it.fmt(0)), copyable = false)
        }
    }
}

@Composable
private fun CodesCard(ohms: Double) {
    val codes = SmdCode.resistorCodes(ohms)
    if (codes.isEmpty()) {
        ErrorText(Res.string.smd_codes_no_code.str())
        return
    }
    val ohm = Res.string.unit_ohm.str()
    ResultCard {
        codes.forEach {
            val exact = abs(it.value - ohms) <= ohms * 1e-6
            KeyValueRow(it.format.title.str(), if (exact) it.code else "${it.code} ≈ ${Si.format(it.value, ohm)}", copyValue = it.code)
        }
    }
}
