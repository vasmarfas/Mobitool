package com.vasmarfas.card.tools.electronics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
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
import com.vasmarfas.card.tools.network.SimpleTable
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import kotlin.math.abs

private enum class WireLookup { AWG, AREA, DIAMETER }

private enum class WireTable { AWG, METRIC }

val wireGaugeTool = Tool(
    id = "wire-gauge",
    category = ToolCategory.ELECTRONICS,
    title = Res.string.wire_gauge,
    description = Res.string.wire_gauge_description,
    icon = Icons.Filled.Cable,
    keywords = listOf("awg", "wire", "gauge", "mm2", "ampacity", "copper", "cable", "провод", "сечение", "кабель", "ток", "медь", "диаметр"),
) { WireGaugeScreen() }

@Composable
private fun WireGaugeScreen() {
    var lookup by rememberSaveable { mutableStateOf(WireLookup.AWG) }
    var awg by rememberSaveable { mutableStateOf(14) }
    var valueText by rememberSaveable { mutableStateOf("2.5") }
    var table by rememberSaveable { mutableStateOf(WireTable.AWG) }
    val value = valueText.toDoubleLenient()?.takeIf { it > 0 }
    val mm = Res.string.unit_mm.str()
    val mm2 = Res.string.unit_mm2.str()
    SegmentedChoice(
        options = WireLookup.entries,
        selected = lookup,
        onSelect = { lookup = it },
        label = {
            when (it) {
                WireLookup.AWG -> "AWG"
                WireLookup.AREA -> mm2
                WireLookup.DIAMETER -> Res.string.diameter.str()
            }
        },
    )
    val row = when (lookup) {
        WireLookup.AWG -> {
            DropdownChoice(
                options = WireGauge.table,
                selected = WireGauge.table.first { it.awg == awg },
                onSelect = { awg = it.awg },
                label = "AWG",
                text = { "AWG ${it.label} · ${it.areaMm2.fmt(2)} $mm2" },
            )
            WireGauge.table.first { it.awg == awg }
        }
        WireLookup.AREA -> {
            NumberField(
                value = valueText,
                onValueChange = { valueText = it },
                label = Res.string.cross_section.str(),
                suffix = mm2,
                isError = valueText.isNotBlank() && value == null,
            )
            value?.let { WireGauge.notThicker(WireGauge.awgFromArea(it)) }
        }
        WireLookup.DIAMETER -> {
            NumberField(
                value = valueText,
                onValueChange = { valueText = it },
                label = Res.string.conductor_diameter.str(),
                suffix = mm,
                isError = valueText.isNotBlank() && value == null,
            )
            value?.let { WireGauge.notThicker(WireGauge.awgFromDiameter(it)) }
        }
    }
    if (row != null) {
        ResultCard {
            if (lookup != WireLookup.AWG && value != null) {
                val exact = if (lookup == WireLookup.AREA) WireGauge.awgFromArea(value) else WireGauge.awgFromDiameter(value)
                KeyValueRow(Res.string.exact_awg.str(), exact.fmt(2))
            }
            KeyValueRow(Res.string.nearest_awg.str(), row.label)
            KeyValueRow(Res.string.diameter.str(), "${row.diameterMm.fmt(3)} $mm")
            KeyValueRow(Res.string.cross_section.str(), "${row.areaMm2.fmt(3)} $mm2")
            KeyValueRow(Res.string.copper_resistance.str(), "${row.ohmsPerKm.fmt(3)} ${Res.string.unit_ohm_km.str()}")
            KeyValueRow(Res.string.ampacity_chassis_wiring.str(), "${row.chassisAmps.fmt(2)} ${Res.string.unit_a.str()}")
            KeyValueRow(Res.string.ampacity_power_transmission.str(), "${row.transmissionAmps.fmt(3)} ${Res.string.unit_a.str()}")
            Hint(Res.string.wire_current_hint.str())
        }
    }
    ToolSection(Res.string.awg_table_copper.str()) {
        SegmentedChoice(options = WireTable.entries, selected = table, onSelect = { table = it }, label = { if (it == WireTable.AWG) "AWG" else mm2 })
        when (table) {
            WireTable.AWG -> SimpleTable(
                header = listOf("AWG", mm2, "Ø $mm", Res.string.wire_header_chassis.str()),
                rows = WireGauge.table.map { listOf(it.label, it.areaMm2.fmt(3), it.diameterMm.fmt(3), it.chassisAmps.fmt(2)) },
                highlight = row?.let { WireGauge.table.indexOf(it) },
            )
            WireTable.METRIC -> {
                val area = if (lookup == WireLookup.AREA) value else row?.areaMm2
                SimpleTable(
                    header = listOf(mm2, "Ø $mm", "≈ AWG", Res.string.unit_ohm_km.str()),
                    rows = WireGauge.metric.map {
                        listOf(it.fmt(2), WireGauge.diameterFromArea(it).fmt(2), WireGauge.nearest(WireGauge.awgFromArea(it)).label, WireGauge.ohmsPerKm(it).fmt(2))
                    },
                    highlight = area?.let { a -> WireGauge.metric.indices.minBy { abs(WireGauge.metric[it] - a) } },
                )
            }
        }
    }
}
