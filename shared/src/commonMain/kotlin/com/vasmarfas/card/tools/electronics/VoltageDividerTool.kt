package com.vasmarfas.card.tools.electronics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.tools.network.SimpleTable
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import com.vasmarfas.card.ui.components.ToolSection
import org.jetbrains.compose.resources.stringResource

private enum class DividerTarget { VOUT, PICK }

private enum class KnownResistor { R1, R2 }

val voltageDividerTool = Tool(
    id = "voltage-divider",
    category = ToolCategory.ELECTRONICS,
    title = Res.string.voltage_divider,
    description = Res.string.voltage_divider_description,
    icon = Icons.AutoMirrored.Filled.CallSplit,
    keywords = listOf("divider", "resistor", "vout", "voltage", "делитель", "напряжение", "резистор", "понизить", "ардуино"),
) { VoltageDividerScreen() }

@Composable
private fun VoltageDividerScreen() {
    var target by rememberSaveable { mutableStateOf(DividerTarget.PICK) }
    var vinText by rememberSaveable { mutableStateOf("12") }
    var voutText by rememberSaveable { mutableStateOf("5") }
    var r1Text by rememberSaveable { mutableStateOf("10k") }
    var r2Text by rememberSaveable { mutableStateOf("4.7k") }
    var known by rememberSaveable { mutableStateOf(KnownResistor.R2) }
    var knownText by rememberSaveable { mutableStateOf("10k") }
    var e24 by rememberSaveable { mutableStateOf(false) }
    val vin = Si.parse(vinText)?.takeIf { it > 0 }
    val vout = Si.parse(voutText)?.takeIf { it > 0 }
    val r1 = Si.parse(r1Text)?.takeIf { it > 0 }
    val r2 = Si.parse(r2Text)?.takeIf { it > 0 }
    val volt = Res.string.unit_v.str()
    val ohm = Res.string.unit_ohm.str()
    SegmentedChoice(
        options = DividerTarget.entries,
        selected = target,
        onSelect = { target = it },
        label = {
            when (it) {
                DividerTarget.PICK -> Res.string.divider_pick.str()
                DividerTarget.VOUT -> Res.string.find_vout.str()
            }
        },
    )
    val pairs = remember(vin, vout, e24) {
        if (vin != null && vout != null && vout < vin) VoltageDivider.pairs(vin, vout, if (e24) LedResistor.e24 else LedResistor.e12) else emptyList()
    }
    val shown = when (target) {
        DividerTarget.VOUT -> if (vin != null && r1 != null && r2 != null) DividerPair(r1, r2, VoltageDivider.vout(vin, r1, r2)) else null
        DividerTarget.PICK -> pairs.firstOrNull()
    }
    DividerCircuit(
        vin = vin?.let { Si.format(it, volt) } ?: "?",
        r1 = shown?.let { Si.format(it.r1, ohm) } ?: "?",
        r2 = shown?.let { Si.format(it.r2, ohm) } ?: "?",
        vout = shown?.let { Si.format(it.vout, volt) } ?: "?",
    )
    Hint(Res.string.divider_scheme_hint.str())
    Hint(Res.string.divider_load_hint.str())
    DividerField(vinText, { vinText = it }, "Vin", volt, vin)
    when (target) {
        DividerTarget.VOUT -> {
            DividerField(r1Text, { r1Text = it }, "R1", ohm, r1)
            DividerField(r2Text, { r2Text = it }, "R2", ohm, r2)
            if (vin != null && shown != null) {
                val current = VoltageDivider.current(vin, shown.r1, shown.r2)
                ResultCard {
                    KeyValueRow("Vout", Si.format(shown.vout, volt))
                    KeyValueRow(Res.string.current.str(), Si.format(current, Res.string.unit_a.str()))
                    KeyValueRow(Res.string.power_in_r1.str(), Si.format(current * current * shown.r1, Res.string.unit_w.str()))
                    KeyValueRow(Res.string.power_in_r2.str(), Si.format(current * current * shown.r2, Res.string.unit_w.str()))
                    KeyValueRow(Res.string.ratio_vout_vin.str(), (shown.vout / vin).fmt(4), copyable = false)
                }
            }
        }

        DividerTarget.PICK -> {
            DividerField(voutText, { voutText = it }, "Vout", volt, vout)
            if (vin == null || vout == null) return
            if (vout >= vin) {
                ErrorText(Res.string.vout_must_be_lower_than_vin.str())
                return
            }
            SegmentedChoice(options = listOf(false, true), selected = e24, onSelect = { e24 = it }, label = { if (it) "E24" else "E12" })
            ResultCard(Res.string.divider_best_pairs.str()) {
                SimpleTable(
                    header = listOf("R1", "R2", "Vout", Res.string.divider_error.str()),
                    rows = pairs.map { p ->
                        val error = (p.vout - vout) / vout * 100
                        listOf(Si.format(p.r1, ohm), Si.format(p.r2, ohm), Si.format(p.vout, volt), stringResource(Res.string.percent_value, (if (error >= 0) "+" else "") + error.fmt(1)))
                    },
                    highlight = 0,
                )
                Hint(Res.string.divider_pairs_hint.str())
            }
            ToolSection(Res.string.divider_have_one.str()) {
                SegmentedChoice(
                    options = KnownResistor.entries,
                    selected = known,
                    onSelect = { known = it },
                    label = { if (it == KnownResistor.R1) Res.string.divider_r1_top.str() else Res.string.divider_r2_bottom.str() },
                )
                val fixed = Si.parse(knownText)?.takeIf { it > 0 }
                DividerField(knownText, { knownText = it }, known.name, ohm, fixed)
                if (fixed != null) {
                    val exact = if (known == KnownResistor.R1) VoltageDivider.r2(vin, vout, fixed) else VoltageDivider.r1(vin, vout, fixed)
                    val standard = LedResistor.nearestInSeries(exact, LedResistor.e24)
                    val safe = if (known == KnownResistor.R1) LedResistor.previousInSeries(exact, LedResistor.e24) else LedResistor.nextInSeries(exact, LedResistor.e24)
                    val other = if (known == KnownResistor.R1) "R2" else "R1"
                    fun outWith(r: Double) = if (known == KnownResistor.R1) VoltageDivider.vout(vin, fixed, r) else VoltageDivider.vout(vin, r, fixed)
                    ResultCard {
                        KeyValueRow(other, Si.format(exact, ohm))
                        KeyValueRow(Res.string.nearest_e24_value.str(), "${Si.format(standard, ohm)} → ${Si.format(outWith(standard), volt)}")
                        if (safe != standard) KeyValueRow(Res.string.divider_not_above.str(), "${Si.format(safe, ohm)} → ${Si.format(outWith(safe), volt)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun DividerField(value: String, onValueChange: (String) -> Unit, label: String, unit: String, parsed: Double?) {
    ToolInputField(
        value = value,
        onValueChange = onValueChange,
        label = "$label ($unit)",
        placeholder = "4.7k · 100m · 1e3",
        keyboardType = KeyboardType.Ascii,
        isError = value.isNotBlank() && parsed == null,
        monospace = true,
    )
}

@Composable
private fun DividerCircuit(vin: String, r1: String, r2: String, vout: String) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelMedium.copy(color = colors.onSurface)
    val ground = Res.string.divider_ground.str()
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val stroke = 2.dp.toPx()
        val x = 32.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        val span = bottom - top
        val node = top + span / 2
        val rw = 16.dp.toPx()
        val rh = 40.dp.toPx()
        val wire = colors.onSurfaceVariant
        drawLine(wire, Offset(x, top), Offset(x, bottom), stroke)
        drawCircle(colors.surface, 5.dp.toPx(), Offset(x, top))
        drawCircle(colors.onSurface, 5.dp.toPx(), Offset(x, top), style = Stroke(stroke))
        fun text(value: String, at: Offset) {
            val layout = measurer.measure(value, label)
            drawText(layout, topLeft = Offset(at.x, at.y - layout.size.height / 2f))
        }
        text("Vin · $vin", Offset(x + 14.dp.toPx(), top))
        for ((y, name) in listOf(top + span / 4 to "R1 · $r1", top + span * 3 / 4 to "R2 · $r2")) {
            drawRect(colors.surface, Offset(x - rw / 2, y - rh / 2), Size(rw, rh))
            drawRect(colors.onSurface, Offset(x - rw / 2, y - rh / 2), Size(rw, rh), style = Stroke(stroke))
            text(name, Offset(x + rw / 2 + 10.dp.toPx(), y))
        }
        val out = x + 56.dp.toPx()
        drawCircle(colors.onSurface, 4.dp.toPx(), Offset(x, node))
        drawLine(wire, Offset(x, node), Offset(out, node), stroke)
        drawCircle(colors.surface, 5.dp.toPx(), Offset(out, node))
        drawCircle(colors.primary, 5.dp.toPx(), Offset(out, node), style = Stroke(stroke))
        text("Vout · $vout", Offset(out + 10.dp.toPx(), node))
        for (i in 0..2) {
            val half = (12 - i * 4).dp.toPx()
            val y = bottom + i * 4.dp.toPx()
            drawLine(colors.onSurface, Offset(x - half, y), Offset(x + half, y), stroke)
        }
        text(ground, Offset(x + 18.dp.toPx(), bottom + 4.dp.toPx()))
    }
}
