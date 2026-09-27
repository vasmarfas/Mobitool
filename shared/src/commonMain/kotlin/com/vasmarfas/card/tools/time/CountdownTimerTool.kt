package com.vasmarfas.card.tools.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.playTone
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.vibrate
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.expandedTextStyle
import com.vasmarfas.card.ui.components.monoFamily
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

private enum class TimerMode { TIMER, POMODORO }

private val timerPresets = listOf(1, 3, 5, 10, 15, 30, 60)

// how long the end of a timer rings when nobody switches it off
private const val RING_MS = 60_000L

val countdownTimerTool = Tool(
    id = "countdown-timer",
    category = ToolCategory.TIME,
    title = Res.string.countdown_timer,
    description = Res.string.countdown_timer_description,
    icon = Icons.Filled.HourglassBottom,
    keywords = listOf("timer", "countdown", "pomodoro", "alarm", "таймер", "отсчёт", "помодоро", "будильник"),
    expandable = true,
) { CountdownTimerScreen() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CountdownTimerScreen() {
    var hText by rememberSaveable { mutableStateOf("0") }
    var mText by rememberSaveable { mutableStateOf("5") }
    var sText by rememberSaveable { mutableStateOf("0") }
    var workText by rememberSaveable { mutableStateOf("25") }
    var breakText by rememberSaveable { mutableStateOf("5") }
    var longText by rememberSaveable { mutableStateOf("15") }
    var state by remember { mutableStateOf(TimerState.decode(Prefs.store.get(TimerState.PREF_KEY)) ?: TimerState()) }
    var mode by rememberSaveable { mutableStateOf(if (state.pomodoro) TimerMode.POMODORO else TimerMode.TIMER) }
    var ringing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var chime by remember { mutableStateOf(0) }
    var now by remember { mutableStateOf(currentEpochMillis()) }
    fun update(next: TimerState) {
        state = next
        Prefs.store.put(TimerState.PREF_KEY, next.encode())
    }

    val h = hText.trim().toIntOrNull()
    val m = mText.trim().toIntOrNull()
    val s = sText.trim().toIntOrNull()
    val timerMs = if (h != null && m != null && s != null && h >= 0 && m >= 0 && s >= 0) (h * 3600L + m * 60L + s) * 1000L else -1L
    val workMs = workText.trim().toIntOrNull()?.takeIf { it > 0 }?.let { it * 60_000L } ?: -1L
    val breakMs = breakText.trim().toIntOrNull()?.takeIf { it > 0 }?.let { it * 60_000L } ?: -1L
    val longMs = longText.trim().toIntOrNull()?.takeIf { it >= 0 }?.let { it * 60_000L } ?: -1L
    fun breakAfter(cycles: Int) = if (TimerState.isLongBreak(work = false, cycles = cycles, longBreakMs = longMs)) longMs else breakMs
    val pomodoro = mode == TimerMode.POMODORO
    val configured = if (pomodoro) workMs else timerMs
    val valid = if (pomodoro) workMs > 0 && breakMs > 0 && longMs >= 0 else timerMs > 0

    LaunchedEffect(state.running) {
        while (state.running) {
            now = currentEpochMillis()
            if (now >= state.endAt) {
                if (state.pomodoro) {
                    val cycles = if (state.work) state.cycles + 1 else state.cycles
                    val next = if (state.work) breakAfter(cycles) else workMs
                    update(state.copy(work = !state.work, cycles = cycles, total = next, endAt = now + next))
                    chime++
                } else {
                    // a timer that ran out while the tool was closed long ago is only shown, not rung
                    ringing = now - state.endAt < RING_MS
                    finished = true
                    update(TimerState())
                }
            }
            delay(30)
        }
    }
    LaunchedEffect(chime) {
        if (chime > 0) {
            vibrate(300)
            repeat(3) {
                playTone(880.0, 400)
                delay(500.milliseconds)
            }
        }
    }
    LaunchedEffect(ringing) {
        var rang = 0L
        while (ringing && rang < RING_MS) {
            vibrate(300)
            playTone(880.0, 400)
            delay(600.milliseconds)
            playTone(880.0, 400)
            delay(1400.milliseconds)
            rang += 2000
        }
        ringing = false
    }

    val remaining = if (state.started) state.left(now) else maxOf(0L, configured)
    val total = if (state.started) state.total else configured
    val fraction = if (total > 0) (1f - remaining.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    if (!state.started) {
        SegmentedChoice(
            options = TimerMode.entries,
            selected = mode,
            onSelect = { mode = it },
            label = { if (it == TimerMode.TIMER) Res.string.timer.str() else Res.string.pomodoro.str() },
        )
        if (mode == TimerMode.TIMER) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NumberField(hText, { hText = it }, Res.string.hours_field.str(), Modifier.weight(1f), isError = h == null)
                NumberField(mText, { mText = it }, Res.string.minutes_field.str(), Modifier.weight(1f), isError = m == null)
                NumberField(sText, { sText = it }, Res.string.seconds_field.str(), Modifier.weight(1f), isError = s == null)
            }
            ChoiceChips(
                options = timerPresets,
                selected = if (h == 0 && s == 0) m else null,
                onSelect = {
                    hText = "0"
                    mText = it.toString()
                    sText = "0"
                },
                label = { "$it ${Res.string.unit_min.str()}" },
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val min = Res.string.unit_min.str()
                NumberField(workText, { workText = it }, Res.string.work.str(), Modifier.weight(1f), suffix = min, isError = workMs <= 0)
                NumberField(breakText, { breakText = it }, Res.string.break_.str(), Modifier.weight(1f), suffix = min, isError = breakMs <= 0)
                NumberField(longText, { longText = it }, Res.string.long_break.str(), Modifier.weight(1f), suffix = min, isError = longMs < 0)
            }
            Text(Res.string.long_break_hint.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!valid) ErrorText(Res.string.timer_enter_a_duration_greater.str())
    }

    ResultCard {
        if (state.pomodoro) {
            val phase = when {
                state.work -> Res.string.work
                TimerState.isLongBreak(state.work, state.cycles, longMs) -> Res.string.long_break
                else -> Res.string.break_
            }
            Text(
                text = phase.str() + " · " + Res.string.cycles.str() + ": " + state.cycles,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = formatCountdown(remaining),
            style = expandedTextStyle(MaterialTheme.typography.displayLarge.copy(fontFamily = monoFamily())),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        if (finished && !state.started) {
            Text(
                text = Res.string.time_is_up.str(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (ringing) {
        ActionButton(
            text = Res.string.stop_alarm.str(),
            onClick = { ringing = false },
            icon = Icons.Filled.NotificationsOff,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.running) {
            ActionButton(
                text = Res.string.pause.str(),
                onClick = { update(state.copy(running = false, remaining = state.left(currentEpochMillis()))) },
                icon = Icons.Filled.Pause,
                modifier = Modifier.weight(1f),
            )
        } else {
            ActionButton(
                text = (if (state.started) Res.string.resume else Res.string.start).str(),
                onClick = {
                    val t = currentEpochMillis()
                    now = t
                    ringing = false
                    finished = false
                    update(
                        if (state.started) {
                            state.copy(running = true, endAt = t + state.remaining)
                        } else {
                            TimerState(pomodoro = pomodoro, running = true, started = true, endAt = t + configured, total = configured)
                        },
                    )
                },
                enabled = state.started || valid,
                icon = Icons.Filled.PlayArrow,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.started && !state.pomodoro) {
            ActionButton(
                text = Res.string.plus_minute.str(),
                onClick = { update(state.plus(60_000)) },
                modifier = Modifier.weight(1f),
            )
        }
        ActionButton(
            text = Res.string.reset.str(),
            onClick = {
                update(TimerState())
                ringing = false
                finished = false
            },
            enabled = state.started || finished,
            icon = Icons.Filled.Refresh,
            modifier = Modifier.weight(1f),
        )
    }
}
