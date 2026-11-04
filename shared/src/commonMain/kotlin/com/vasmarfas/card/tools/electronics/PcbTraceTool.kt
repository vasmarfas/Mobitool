package com.vasmarfas.card.tools.electronics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import org.jetbrains.compose.resources.stringResource

private enum class TraceLayer { EXTERNAL, INTERNAL }

private class CopperWeight(val micrometres: Int, val ounces: String)

private val copperWeights = listOf(CopperWeight(18, "0.5"), CopperWeight(35, "1"), CopperWeight(70, "2"), CopperWeight(105, "3"))

private val riseSteps = listOf(10, 20, 30, 45)

val pcbTraceTool = Tool(
    id = "pcb-trace-width",
    category = ToolCategory.ELECTRONICS,
    title = Res.string.pcb_trace_width,
    description = Res.string.pcb_trace_width_description,
    icon = Icons.Filled.DeveloperBoard,
    keywords = listOf(
        "pcb", "trace", "track", "width", "ipc-2221", "ipc2221", "copper", "current", "voltage drop",
        "печатная плата", "дорожка", "ширина дорожки", "проводник", "медь", "ток", "падение напряжения", "перегрев",
    ),
) { PcbTraceScreen() }

@Composable
private fun PcbTraceScreen() {
    var layer by rememberSaveable { mutableStateOf(TraceLayer.EXTERNAL) }
    var currentText by rememberSaveable { mutableStateOf("1") }
    var riseText by rememberSaveable { mutableStateOf("10") }
    var copperText by rememberSaveable { mutableStateOf("35") }
    var lengthText by rememberSaveable { mutableStateOf("100") }
    val amps = currentText.toDoubleLenient()?.takeIf { it > 0 }
    val rise = riseText.toDoubleLenient()?.takeIf { it > 0 }
    val copper = copperText.toDoubleLenient()?.takeIf { it > 0 }
    val length = lengthText.toDoubleLenient()?.takeIf { it > 0 }
    val celsius = Res.string.unit_celsius.str()
    val mm = Res.string.unit_mm.str()
    SegmentedChoice(
        options = TraceLayer.entries,
        selected = layer,
        onSelect = { layer = it },
        label = { if (it == TraceLayer.EXTERNAL) Res.string.pcb_trace_external.str() else Res.string.pcb_trace_internal.str() },
    )
    NumberField(
        value = currentText,
        onValueChange = { currentText = it },
        label = Res.string.current.str(),
        suffix = Res.string.unit_a.str(),
        isError = currentText.isNotBlank() && amps == null,
    )
    NumberField(
        value = riseText,
        onValueChange = { riseText = it },
        label = Res.string.pcb_trace_rise.str(),
        suffix = celsius,
        isError = riseText.isNotBlank() && rise == null,
    )
    ChoiceChips(
        options = riseSteps,
        selected = riseSteps.firstOrNull { it.toDouble() == rise },
        onSelect = { riseText = it.toString() },
        label = { "$it $celsius" },
    )
    NumberField(
        value = copperText,
        onValueChange = { copperText = it },
        label = Res.string.pcb_trace_copper.str(),
        suffix = Res.string.unit_um.str(),
        isError = copperText.isNotBlank() && copper == null,
    )
    ChoiceChips(
        options = copperWeights,
        selected = copperWeights.firstOrNull { it.micrometres.toDouble() == copper },
        onSelect = { copperText = it.micrometres.toString() },
        label = { stringResource(Res.string.pcb_trace_copper_weight, it.micrometres.toString(), it.ounces) },
    )
    NumberField(
        value = lengthText,
        onValueChange = { lengthText = it },
        label = Res.string.length.str(),
        suffix = mm,
        isError = lengthText.isNotBlank() && length == null,
    )
    if (amps == null || rise == null || copper == null) return
    val external = layer == TraceLayer.EXTERNAL
    val area = PcbTrace.areaMm2(amps, rise, external)
    val width = area / (copper / 1000)
    ResultCard {
        KeyValueRow(Res.string.width.str(), "${width.fmt(3)} $mm · ${PcbTrace.milsFromMm(width).fmt(1)} ${Res.string.pcb_trace_unit_mil.str()}")
        KeyValueRow(Res.string.cross_section.str(), "${area.fmt(4)} ${Res.string.unit_mm2.str()}")
        if (length != null) {
            val ohms = PcbTrace.ohms(area, length, rise)
            KeyValueRow(Res.string.resistance.str(), Si.format(ohms, Res.string.unit_ohm.str()))
            KeyValueRow(Res.string.pcb_trace_voltage_drop.str(), Si.format(ohms * amps, Res.string.unit_v.str()))
        }
    }
    Hint(Res.string.pcb_trace_hint.str())
    if (!PcbTrace.withinCharts(amps, rise, external, width)) Hint(Res.string.pcb_trace_out_of_charts.str())
}
