package com.vasmarfas.card.tools.printing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import org.jetbrains.compose.resources.stringResource

private enum class AmountMode { WEIGHT, LENGTH }

val printCostTool = Tool(
    id = "print-cost",
    category = ToolCategory.PRINTING,
    title = Res.string.print_cost,
    description = Res.string.print_cost_description,
    icon = Icons.Filled.Calculate,
    keywords = listOf("3d print", "filament", "cost", "price", "fdm", "spool", "себестоимость", "пруток", "катушка", "цена печати"),
) { PrintCostScreen() }

@Composable
private fun PrintCostScreen() {
    var mode by rememberSaveable { mutableStateOf(AmountMode.WEIGHT) }
    var material by rememberSaveable { mutableStateOf(FilamentMaterial.PLA) }
    var diameter by rememberSaveable { mutableStateOf(1.75) }
    var weightText by rememberSaveable { mutableStateOf("42") }
    var lengthText by rememberSaveable { mutableStateOf("14") }
    var priceText by rememberSaveable { mutableStateOf("1500") }
    var spoolText by rememberSaveable { mutableStateOf("1000") }
    var hoursText by rememberSaveable { mutableStateOf("4") }
    var minutesText by rememberSaveable { mutableStateOf("30") }
    var powerText by rememberSaveable { mutableStateOf("120") }
    var kwhText by rememberSaveable { mutableStateOf("6") }
    var failureText by rememberSaveable { mutableStateOf("10") }
    var markupText by rememberSaveable { mutableStateOf("100") }

    SegmentedChoice(
        options = AmountMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = { if (it == AmountMode.WEIGHT) Res.string.by_weight.str() else Res.string.by_length.str() },
    )

    val weight = weightText.toDoubleLenient()
    val length = lengthText.toDoubleLenient()
    if (mode == AmountMode.WEIGHT) {
        NumberField(
            value = weightText,
            onValueChange = { weightText = it },
            label = Res.string.model_weight.str(),
            suffix = Res.string.unit_g.str(),
            isError = weight == null || weight <= 0,
        )
    } else {
        DropdownChoice(
            options = FilamentMaterial.entries,
            selected = material,
            onSelect = { material = it },
            label = Res.string.material.str(),
            text = { it.title.str() },
        )
        SegmentedChoice(
            options = filamentDiameters,
            selected = diameter,
            onSelect = { diameter = it },
            label = { "${it.fmt(2)} ${Res.string.unit_mm.str()}" },
        )
        Hint(Res.string.print_cost_material_hint.str())
        NumberField(
            value = lengthText,
            onValueChange = { lengthText = it },
            label = Res.string.filament_length.str(),
            suffix = Res.string.unit_m.str(),
            isError = length == null || length <= 0,
        )
    }

    val price = priceText.toDoubleLenient()
    val spool = spoolText.toDoubleLenient()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = priceText,
            onValueChange = { priceText = it },
            label = Res.string.spool_price.str(),
            modifier = Modifier.weight(1f),
            isError = price == null || price < 0,
        )
        NumberField(
            value = spoolText,
            onValueChange = { spoolText = it },
            label = Res.string.spool_net_weight.str(),
            modifier = Modifier.weight(1f),
            suffix = Res.string.unit_g.str(),
            isError = spool == null || spool <= 0,
        )
    }

    ToolSection(Res.string.machine_time.str()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(
                value = hoursText,
                onValueChange = { hoursText = it },
                label = Res.string.hours_field.str(),
                modifier = Modifier.weight(1f),
                isError = hoursText.toDoubleLenient() == null,
            )
            NumberField(
                value = minutesText,
                onValueChange = { minutesText = it },
                label = Res.string.minutes_field.str(),
                modifier = Modifier.weight(1f),
                isError = minutesText.toDoubleLenient() == null,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(
                value = powerText,
                onValueChange = { powerText = it },
                label = Res.string.printer_power.str(),
                modifier = Modifier.weight(1f),
                suffix = Res.string.unit_w.str(),
                isError = powerText.toDoubleLenient() == null,
            )
            NumberField(
                value = kwhText,
                onValueChange = { kwhText = it },
                label = Res.string.price_per_kwh.str(),
                modifier = Modifier.weight(1f),
                isError = kwhText.toDoubleLenient() == null,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField(
            value = failureText,
            onValueChange = { failureText = it },
            label = Res.string.failure_rate.str(),
            modifier = Modifier.weight(1f),
            suffix = "%",
            isError = failureText.toDoubleLenient().let { it == null || it < 0 || it >= 100 },
        )
        NumberField(
            value = markupText,
            onValueChange = { markupText = it },
            label = Res.string.markup.str(),
            modifier = Modifier.weight(1f),
            suffix = "%",
            isError = markupText.toDoubleLenient().let { it == null || it < 0 },
        )
    }

    val hours = hoursText.toDoubleLenient()
    val minutes = minutesText.toDoubleLenient()
    val power = powerText.toDoubleLenient()
    val kwh = kwhText.toDoubleLenient()
    val failure = failureText.toDoubleLenient()
    val markup = markupText.toDoubleLenient()
    val grams = when (mode) {
        AmountMode.WEIGHT -> weight
        AmountMode.LENGTH -> length?.let { Filament.grams(it, diameter, material.density) }
    }
    if (grams == null || grams <= 0 || price == null || spool == null || spool <= 0 ||
        hours == null || minutes == null || power == null || kwh == null ||
        failure == null || failure < 0 || failure >= 100 || markup == null || markup < 0
    ) {
        ErrorText(Res.string.print_cost_fill_in_the_fields.str())
        return
    }

    val totalHours = hours + minutes / 60.0
    val cost = PrintEconomics.printCost(grams, price, spool, totalHours, power, kwh, failure, markup)
    ResultCard(Res.string.material.str()) {
        KeyValueRow(Res.string.weight.str(), "${grams.fmt(1)} ${Res.string.unit_g.str()}")
        if (mode == AmountMode.LENGTH && length != null) {
            KeyValueRow(Res.string.filament_length.str(), "${length.fmt(2)} ${Res.string.unit_m.str()}")
            KeyValueRow(Res.string.volume.str(), "${(grams / material.density).fmt(2)} ${Res.string.unit_cm3.str()}")
        }
        KeyValueRow(Res.string.share_of_a_spool.str(), stringResource(Res.string.percent_value, (grams / spool * 100).fmt(1)))
        KeyValueRow(Res.string.prints_per_spool.str(), (spool / grams).fmt(1))
    }
    ResultCard(Res.string.cost.str()) {
        KeyValueRow(Res.string.filament.str(), cost.material.fmt(2, grouping = true))
        KeyValueRow(Res.string.electricity.str(), "${cost.energy.fmt(2, grouping = true)} · ${cost.energyKwh.fmt(3)} ${Res.string.unit_kwh.str()}")
        KeyValueRow(Res.string.without_failures.str(), cost.beforeFailures.fmt(2, grouping = true))
        KeyValueRow(Res.string.total_cost.str(), cost.total.fmt(2, grouping = true))
        KeyValueRow(Res.string.per_gram.str(), cost.perGram.fmt(3))
    }
    ResultCard(Res.string.price.str()) {
        KeyValueRow(Res.string.suggested_price.str(), cost.salePrice.fmt(2, grouping = true))
        KeyValueRow(Res.string.profit.str(), cost.profit.fmt(2, grouping = true))
    }
}
