package com.vasmarfas.card.tools.electronics

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.card.core.fmt
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.toDoubleLenient
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolInputField
import org.jetbrains.compose.resources.stringResource

private enum class TimerMode { ASTABLE, MONOSTABLE }

private enum class TimerDirection { PARTS, TIMING }

val timer555Tool = Tool(
    id = "timer-555",
    category = ToolCategory.ELECTRONICS,
    title = Res.string.timer_555,
    description = Res.string.timer_555_description,
    icon = Icons.Filled.Timer,
    keywords = listOf(
        "555", "ne555", "timer", "astable", "monostable", "multivibrator", "oscillator", "duty cycle", "pulse",
        "таймер", "мультивибратор", "генератор", "скважность", "заполнение", "импульс", "одновибратор",
    ),
) { Timer555Screen() }

@Composable
private fun Timer555Screen() {
    var mode by rememberSaveable { mutableStateOf(TimerMode.ASTABLE) }
    var direction by rememberSaveable { mutableStateOf(TimerDirection.PARTS) }
    var raText by rememberSaveable { mutableStateOf("10k") }
    var rbText by rememberSaveable { mutableStateOf("47k") }
    var capacitorText by rememberSaveable { mutableStateOf("100n") }
    var frequencyText by rememberSaveable { mutableStateOf("1k") }
    var dutyText by rememberSaveable { mutableStateOf("60") }
    var pulseText by rememberSaveable { mutableStateOf("1") }
    val ra = Si.parse(raText)?.takeIf { it > 0 }
    val rb = Si.parse(rbText)?.takeIf { it > 0 }
    val c = Si.parse(capacitorText)?.takeIf { it > 0 }
    val frequency = Si.parse(frequencyText)?.takeIf { it > 0 }
    val duty = dutyText.toDoubleLenient()?.div(100)
    val pulse = Si.parse(pulseText)?.takeIf { it > 0 }
    val astable = mode == TimerMode.ASTABLE
    val fromParts = direction == TimerDirection.PARTS
    SegmentedChoice(
        options = TimerMode.entries,
        selected = mode,
        onSelect = { mode = it },
        label = { if (it == TimerMode.ASTABLE) Res.string.timer_555_astable.str() else Res.string.timer_555_monostable.str() },
    )
    SegmentedChoice(
        options = TimerDirection.entries,
        selected = direction,
        onSelect = { direction = it },
        label = { if (it == TimerDirection.PARTS) Res.string.timer_555_parts_to_timing.str() else Res.string.timer_555_timing_to_parts.str() },
    )
    when {
        fromParts -> {
            SiField(raText, "RA", "10k · 4k7 · 1M", ra == null) { raText = it }
            if (astable) SiField(rbText, "RB", "47k", rb == null) { rbText = it }
        }
        astable -> {
            SiField(frequencyText, Res.string.timer_555_frequency_hz.str(), "1k · 440 · 2.5k", frequency == null) { frequencyText = it }
            NumberField(
                value = dutyText,
                onValueChange = { dutyText = it },
                label = Res.string.timer_555_duty.str(),
                suffix = "%",
                isError = dutyText.isNotBlank() && duty == null,
            )
        }
        else -> SiField(pulseText, Res.string.timer_555_pulse_seconds.str(), "1 · 250m · 40u", pulse == null) { pulseText = it }
    }
    SiField(capacitorText, Res.string.smd_codes_capacitance.str(), "100n · 4.7u · 22p", c == null) { capacitorText = it }
    if (c == null) return
    when {
        fromParts && astable -> if (ra != null && rb != null) AstableCard(ra, rb, c)
        fromParts -> if (ra != null) PulseCard(ra, c)
        astable -> if (frequency != null && duty != null) AstablePartsCards(frequency, duty, c)
        else -> if (pulse != null) PulsePartsCards(pulse, c)
    }
}

@Composable
private fun SiField(value: String, label: String, placeholder: String, invalid: Boolean, onChange: (String) -> Unit) {
    ToolInputField(
        value = value,
        onValueChange = onChange,
        label = label,
        placeholder = placeholder,
        keyboardType = KeyboardType.Ascii,
        isError = value.isNotBlank() && invalid,
        monospace = true,
    )
}

@Composable
private fun AstablePartsCards(frequency: Double, duty: Double, c: Double) {
    val parts = Timer555.astableParts(frequency, duty, c)
    if (parts == null) {
        ErrorText(Res.string.timer_555_duty_range.str())
        return
    }
    val ohm = Res.string.unit_ohm.str()
    val nearestA = LedResistor.nearestInSeries(parts.ra, LedResistor.e24)
    val nearestB = LedResistor.nearestInSeries(parts.rb, LedResistor.e24)
    ResultCard {
        KeyValueRow("RA", Si.format(parts.ra, ohm))
        KeyValueRow("RB", Si.format(parts.rb, ohm))
        KeyValueRow("RA, E24", Si.format(nearestA, ohm))
        KeyValueRow("RB, E24", Si.format(nearestB, ohm))
    }
    Hint(Res.string.timer_555_with_e24.str())
    AstableCard(nearestA, nearestB, c)
}

@Composable
private fun PulsePartsCards(pulse: Double, c: Double) {
    val ohm = Res.string.unit_ohm.str()
    val exact = Timer555.pulseResistor(pulse, c)
    val nearest = LedResistor.nearestInSeries(exact, LedResistor.e24)
    ResultCard {
        KeyValueRow("RA", Si.format(exact, ohm))
        KeyValueRow("RA, E24", Si.format(nearest, ohm))
    }
    Hint(Res.string.timer_555_with_e24.str())
    PulseCard(nearest, c)
}

@Composable
private fun AstableCard(ra: Double, rb: Double, c: Double) {
    val timing = Timer555.astable(ra, rb, c)
    val second = Res.string.unit_s.str()
    ResultCard {
        KeyValueRow(Res.string.frequency.str(), Si.format(timing.frequency, Res.string.unit_hz.str()))
        KeyValueRow(Res.string.period.str(), Si.format(timing.period, second))
        KeyValueRow(Res.string.timer_555_high.str(), Si.format(timing.high, second))
        KeyValueRow(Res.string.timer_555_low.str(), Si.format(timing.low, second))
        KeyValueRow(Res.string.timer_555_duty.str(), stringResource(Res.string.percent_value, (timing.duty * 100).fmt(1)))
        KeyValueRow(Res.string.timer_555_period_ratio.str(), (1 / timing.duty).fmt(2))
    }
    if (timing.frequency > Timer555.MAX_FREQUENCY) Hint(Res.string.timer_555_too_fast.str())
    if (outOfRange(ra) || outOfRange(rb)) Hint(Res.string.timer_555_resistor_range.str())
}

@Composable
private fun PulseCard(ra: Double, c: Double) {
    val pulse = Timer555.pulse(ra, c)
    ResultCard { KeyValueRow(Res.string.timer_555_pulse.str(), Si.format(pulse, Res.string.unit_s.str())) }
    if (pulse < Timer555.MIN_PULSE) Hint(Res.string.timer_555_too_short.str())
    if (outOfRange(ra)) Hint(Res.string.timer_555_resistor_range.str())
}

private fun outOfRange(ohms: Double) = ohms < Timer555.MIN_RESISTOR || ohms > Timer555.MAX_RESISTOR
