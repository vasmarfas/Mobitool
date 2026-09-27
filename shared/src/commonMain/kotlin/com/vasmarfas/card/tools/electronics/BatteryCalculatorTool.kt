package com.vasmarfas.card.tools.electronics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery4Bar
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
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice

private enum class BatteryMode { RUNTIME, CHARGE, ENERGY }

private enum class LoadKind { CURRENT, POWER }

private enum class EnergyDirection { MAH_TO_WH, WH_TO_MAH }

private val voltagePresets = listOf("1.2", "1.5", "3.7", "5", "12")

val batteryCalculatorTool = Tool(
    id = "battery-calculator",
    category = ToolCategory.ELECTRONICS,
    title = Res.string.battery_calculator,
    description = Res.string.battery_calculator_description,
    icon = Icons.Filled.Battery4Bar,
    keywords = listOf("battery", "mah", "wh", "runtime", "charge", "power bank", "аккумулятор", "батарея", "мач", "втч", "зарядка", "автономность"),
) { BatteryCalculatorScreen() }

@Composable
private fun BatteryCalculatorScreen() {
    var mode by rememberSaveable { mutableStateOf(BatteryMode.RUNTIME) }
    var capacityText by rememberSaveable { mutableStateOf("5000") }
    var voltageText by rememberSaveable { mutableStateOf("3.7") }
    var loadText by rememberSaveable { mutableStateOf("500") }
    var loadKind by rememberSaveable { mutableStateOf(LoadKind.CURRENT) }
    var chargerText by rememberSaveable { mutableStateOf("1000") }
    var efficiencyText by rememberSaveable { mutableStateOf("85") }
    var chargeEfficiencyText by rememberSaveable { mutableStateOf("80") }
    var energyText by rememberSaveable { mutableStateOf("5000") }
    var direction by rememberSaveable { mutableStateOf(EnergyDirection.MAH_TO_WH) }
    val capacity = capacityText.toDoubleLenient()?.takeIf { it > 0 }
    val voltage = voltageText.toDoubleLenient()?.takeIf { it > 0 }
    SegmentedChoice(
        options = BatteryMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = {
            when (it) {
                BatteryMode.RUNTIME -> Res.string.runtime.str()
                BatteryMode.CHARGE -> Res.string.charging.str()
                BatteryMode.ENERGY -> Res.string.battery_mode_energy.str()
            }
        },
    )
    if (mode != BatteryMode.ENERGY) {
        NumberField(
            value = capacityText,
            onValueChange = { capacityText = it },
            label = Res.string.capacity.str(),
            suffix = Res.string.unit_mah.str(),
            isError = capacityText.isNotBlank() && capacity == null,
        )
    }
    if (mode != BatteryMode.CHARGE) {
        NumberField(
            value = voltageText,
            onValueChange = { voltageText = it },
            label = Res.string.nominal_voltage.str(),
            suffix = Res.string.unit_v.str(),
            isError = voltageText.isNotBlank() && voltage == null,
        )
        ChoiceChips(
            options = voltagePresets,
            selected = voltagePresets.firstOrNull { it.toDouble() == voltage },
            onSelect = { voltageText = it },
            label = { "$it ${Res.string.unit_v.str()}" },
        )
    }
    when (mode) {
        BatteryMode.RUNTIME -> {
            val load = loadText.toDoubleLenient()?.takeIf { it > 0 }
            val efficiency = efficiencyText.toDoubleLenient()?.takeIf { it in 1.0..100.0 }
            SegmentedChoice(
                options = LoadKind.entries,
                selected = loadKind,
                onSelect = { loadKind = it },
                label = { if (it == LoadKind.CURRENT) Res.string.load_in_ma.str() else Res.string.load_in_w.str() },
            )
            NumberField(
                value = loadText,
                onValueChange = { loadText = it },
                label = Res.string.load.str(),
                suffix = if (loadKind == LoadKind.CURRENT) Res.string.unit_ma.str() else Res.string.unit_w.str(),
                isError = loadText.isNotBlank() && load == null,
            )
            NumberField(
                value = efficiencyText,
                onValueChange = { efficiencyText = it },
                label = Res.string.usable_capacity.str(),
                suffix = "%",
                isError = efficiencyText.isNotBlank() && efficiency == null,
            )
            if (capacity != null && load != null && efficiency != null && (loadKind == LoadKind.CURRENT || voltage != null)) {
                val hours = if (loadKind == LoadKind.CURRENT) Battery.runtimeHours(capacity, load, efficiency) else Battery.runtimeHoursByPower(capacity, voltage!!, load, efficiency)
                ResultCard {
                    KeyValueRow(Res.string.runtime.str(), Battery.formatHours(hours))
                    KeyValueRow(Res.string.hours.str(), hours.fmt(2))
                    if (voltage != null) KeyValueRow(Res.string.energy.str(), "${Battery.mahToWh(capacity, voltage).fmt(2)} ${Res.string.unit_wh.str()}")
                }
            }
        }
        BatteryMode.CHARGE -> {
            val charger = chargerText.toDoubleLenient()?.takeIf { it > 0 }
            val efficiency = chargeEfficiencyText.toDoubleLenient()?.takeIf { it in 1.0..100.0 }
            NumberField(
                value = chargerText,
                onValueChange = { chargerText = it },
                label = Res.string.charger_current.str(),
                suffix = Res.string.unit_ma.str(),
                isError = chargerText.isNotBlank() && charger == null,
            )
            NumberField(
                value = chargeEfficiencyText,
                onValueChange = { chargeEfficiencyText = it },
                label = Res.string.charging_efficiency.str(),
                suffix = "%",
                isError = chargeEfficiencyText.isNotBlank() && efficiency == null,
            )
            if (capacity != null && charger != null && efficiency != null) {
                val hours = Battery.chargeHours(capacity, charger, efficiency)
                ResultCard {
                    KeyValueRow(Res.string.charging_time.str(), Battery.formatHours(hours))
                    KeyValueRow(Res.string.hours.str(), hours.fmt(2))
                    KeyValueRow(Res.string.charge_rate.str(), "${(charger / capacity).fmt(2)} C", copyable = false)
                }
            }
        }
        BatteryMode.ENERGY -> {
            val energy = energyText.toDoubleLenient()?.takeIf { it >= 0 }
            SegmentedChoice(
                options = EnergyDirection.entries,
                selected = direction,
                onSelect = { direction = it },
                label = {
                    val mah = Res.string.unit_mah.str()
                    val wh = Res.string.unit_wh.str()
                    if (it == EnergyDirection.MAH_TO_WH) "$mah → $wh" else "$wh → $mah"
                },
            )
            NumberField(
                value = energyText,
                onValueChange = { energyText = it },
                label = if (direction == EnergyDirection.MAH_TO_WH) Res.string.capacity.str() else Res.string.energy.str(),
                suffix = if (direction == EnergyDirection.MAH_TO_WH) Res.string.unit_mah.str() else Res.string.unit_wh.str(),
                isError = energyText.isNotBlank() && energy == null,
            )
            if (energy != null && voltage != null) {
                ResultCard {
                    if (direction == EnergyDirection.MAH_TO_WH) {
                        KeyValueRow(Res.string.unit_wh.str(), Battery.mahToWh(energy, voltage).fmt(2))
                    } else {
                        KeyValueRow(Res.string.unit_mah.str(), Battery.whToMah(energy, voltage).fmt(0))
                    }
                    KeyValueRow(Res.string.at_voltage.str(), "${voltage.fmt(2)} ${Res.string.unit_v.str()}", copyable = false)
                }
            }
        }
    }
}
