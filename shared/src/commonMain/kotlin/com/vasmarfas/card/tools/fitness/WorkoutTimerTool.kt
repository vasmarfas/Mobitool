package com.vasmarfas.card.tools.fitness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vasmarfas.card.core.Prefs
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.formatDurationMs
import com.vasmarfas.card.core.playTone
import com.vasmarfas.card.core.str
import com.vasmarfas.card.core.vibrate
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.DropdownChoice
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.Hint
import com.vasmarfas.card.ui.components.KeyValueRow
import com.vasmarfas.card.ui.components.LocalOpenTool
import com.vasmarfas.card.ui.components.NumberField
import com.vasmarfas.card.ui.components.ResultCard
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.expandedTextStyle
import com.vasmarfas.card.ui.components.monoFamily
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

private enum class TimerSource { PLAN, INTERVAL }

val workoutTimerTool = Tool(
    id = "workout-timer",
    category = ToolCategory.FITNESS,
    title = Res.string.workout_timer,
    description = Res.string.workout_timer_description,
    icon = Icons.Filled.Timer,
    keywords = listOf("interval", "hiit", "tabata", "timer", "rest", "интервалы", "хиит", "табата", "таймер", "отдых"),
    expandable = true,
) { WorkoutTimerScreen() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkoutTimerScreen() {
    val plans = remember { WorkoutPlans.load() }
    val openTool = LocalOpenTool.current
    var source by rememberSaveable { mutableStateOf(TimerSource.PLAN) }
    var planId by rememberSaveable { mutableStateOf(plans.firstOrNull()?.id ?: "") }
    var prepareText by rememberSaveable { mutableStateOf("10") }
    var workText by rememberSaveable { mutableStateOf("20") }
    var restText by rememberSaveable { mutableStateOf("10") }
    var roundsText by rememberSaveable { mutableStateOf("8") }
    var setsText by rememberSaveable { mutableStateOf("1") }
    var setRestText by rememberSaveable { mutableStateOf("60") }
    var cooldownText by rememberSaveable { mutableStateOf("0") }

    var running by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    var stepIndex by rememberSaveable { mutableStateOf(0) }
    var endAt by rememberSaveable { mutableStateOf(0L) }
    var remainingPaused by rememberSaveable { mutableStateOf(0L) }
    var transitions by remember { mutableStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(currentEpochMillis()) }
    LaunchedEffect(Unit) {
        val sent = Prefs.store.get(WorkoutPlans.START_KEY) ?: return@LaunchedEffect
        Prefs.store.remove(WorkoutPlans.START_KEY)
        if (!running && plans.any { it.id == sent }) {
            source = TimerSource.PLAN
            planId = sent
            started = false
            finished = false
            stepIndex = 0
        }
    }

    SegmentedChoice(
        options = TimerSource.entries,
        selected = source,
        onSelect = {
            if (!running) {
                source = it
                started = false
                finished = false
                stepIndex = 0
            }
        },
        label = { if (it == TimerSource.PLAN) Res.string.plan.str() else Res.string.intervals.str() },
    )

    val plan = plans.firstOrNull { it.id == planId } ?: plans.firstOrNull()
    if (source == TimerSource.PLAN) {
        if (plan == null) {
            Hint(Res.string.workout_timer_no_saved_plans.str())
            ActionButton(
                text = Res.string.create_a_plan.str(),
                onClick = { openTool("workout-builder") },
                icon = Icons.Filled.Add,
            )
            return
        }
        DropdownChoice(
            options = plans,
            selected = plan,
            onSelect = {
                if (!running) {
                    planId = it.id
                    started = false
                    finished = false
                    stepIndex = 0
                }
            },
            label = Res.string.plan.str(),
            text = { it.name.ifBlank { Res.string.untitled.str() } },
        )
        TextButton(onClick = { openTool("workout-builder") }) { Text(Res.string.edit_plans.str()) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(prepareText, { prepareText = it }, Res.string.prepare.str(), Modifier.weight(1f), suffix = Res.string.unit_s.str(), isError = prepareText.toIntOrZero() < 0)
            NumberField(workText, { workText = it }, Res.string.work.str(), Modifier.weight(1f), suffix = Res.string.unit_s.str(), isError = workText.toIntOrZero() <= 0)
            NumberField(restText, { restText = it }, Res.string.rest.str(), Modifier.weight(1f), suffix = Res.string.unit_s.str(), isError = restText.toIntOrZero() < 0)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(roundsText, { roundsText = it }, Res.string.rounds.str(), Modifier.weight(1f), isError = roundsText.toIntOrZero() !in 1..WorkoutSteps.MAX_ROUNDS)
            NumberField(setsText, { setsText = it }, Res.string.workout_timer_sets.str(), Modifier.weight(1f), isError = setsText.toIntOrZero() !in 1..WorkoutSteps.MAX_SETS)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NumberField(setRestText, { setRestText = it }, Res.string.rest_between_sets.str(), Modifier.weight(1f), suffix = Res.string.unit_s.str(), isError = setRestText.toIntOrZero() < 0)
            NumberField(cooldownText, { cooldownText = it }, Res.string.cooldown.str(), Modifier.weight(1f), suffix = Res.string.unit_s.str(), isError = cooldownText.toIntOrZero() < 0)
        }
    }

    val steps = when (source) {
        TimerSource.PLAN -> remember(plan) { WorkoutSteps.fromPlan(plan ?: WorkoutPlan()) }
        TimerSource.INTERVAL -> remember(prepareText, workText, restText, roundsText, setsText, setRestText, cooldownText) {
            WorkoutSteps.interval(
                prepareSec = prepareText.toIntOrZero(),
                workSec = workText.toIntOrZero(),
                restSec = restText.toIntOrZero(),
                rounds = roundsText.toIntOrZero(),
                sets = setsText.toIntOrZero(),
                restBetweenSetsSec = setRestText.toIntOrZero(),
                cooldownSec = cooldownText.toIntOrZero(),
            )
        }
    }
    if (steps.isEmpty()) {
        ErrorText(Res.string.workout_timer_nothing_to_run_check.str())
        return
    }

    val index = stepIndex.coerceIn(0, steps.lastIndex)
    val step = steps[index]
    val stepMs = step.seconds * 1000L

    LaunchedEffect(running, steps) {
        while (running) {
            delay(250.milliseconds)
            now = currentEpochMillis()
            if (now >= endAt) {
                transitions++
                val current = stepIndex.coerceIn(0, steps.lastIndex)
                if (current < steps.lastIndex) {
                    stepIndex = current + 1
                    endAt = now + steps[current + 1].seconds * 1000L
                } else {
                    running = false
                    started = false
                    finished = true
                }
            }
        }
    }
    LaunchedEffect(transitions) {
        if (transitions > 0) {
            vibrate(300)
            playTone(880.0, 300)
        }
    }

    val remaining = when {
        running -> (endAt - now).coerceAtLeast(0L)
        started -> remainingPaused
        else -> stepMs
    }
    val fraction = if (stepMs > 0) (1f - remaining.toFloat() / stepMs.toFloat()).coerceIn(0f, 1f) else 0f
    val elapsed = steps.take(index).sumOf { it.seconds } * 1000L + (stepMs - remaining)
    val total = WorkoutSteps.totalSeconds(steps) * 1000L
    val kindTitle = when (step.kind) {
        StepKind.PREPARE -> Res.string.get_ready
        StepKind.WORK -> Res.string.work
        StepKind.REST -> Res.string.rest
        StepKind.COOLDOWN -> Res.string.cooldown
    }

    ResultCard {
        Text(
            text = kindTitle.str() + step.name.let { if (it.isBlank()) "" else " · $it" },
            style = MaterialTheme.typography.titleMedium,
            color = if (step.kind == StepKind.WORK) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = formatClock(remaining),
            style = expandedTextStyle(MaterialTheme.typography.displayLarge.copy(fontFamily = monoFamily())),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            text = stepSubtitle(step, index, steps.size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (finished) {
            Text(
                text = Res.string.done.str(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (running) {
            ActionButton(
                text = Res.string.pause.str(),
                onClick = {
                    remainingPaused = (endAt - currentEpochMillis()).coerceAtLeast(0L)
                    running = false
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Pause,
            )
        } else {
            ActionButton(
                text = (if (started) Res.string.resume else Res.string.start).str(),
                onClick = {
                    val nowMs = currentEpochMillis()
                    if (!started) {
                        stepIndex = 0
                        remainingPaused = steps[0].seconds * 1000L
                        started = true
                        finished = false
                    }
                    now = nowMs
                    endAt = nowMs + remainingPaused
                    running = true
                },
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.PlayArrow,
            )
        }
        FilledTonalIconButton(
            onClick = {
                if (index < steps.lastIndex) {
                    stepIndex = index + 1
                    remainingPaused = steps[index + 1].seconds * 1000L
                    if (running) {
                        now = currentEpochMillis()
                        endAt = now + remainingPaused
                    }
                } else {
                    running = false
                    started = false
                    finished = true
                }
            },
            enabled = started || running,
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = Res.string.skip.str()) }
        FilledTonalIconButton(
            onClick = {
                running = false
                started = false
                finished = false
                stepIndex = 0
                remainingPaused = 0L
                transitions = 0
            },
            enabled = started || running || finished,
        ) { Icon(Icons.Filled.Refresh, contentDescription = Res.string.reset.str()) }
    }

    ResultCard(Res.string.session.str()) {
        KeyValueRow(Res.string.step.str(), "${index + 1} / ${steps.size}")
        KeyValueRow(Res.string.elapsed.str(), formatDurationMs(elapsed))
        KeyValueRow(Res.string.total_all.str(), formatDurationMs(total))
        KeyValueRow(Res.string.left.str(), formatDurationMs((total - elapsed).coerceAtLeast(0L)))
        val next = steps.getOrNull(index + 1)
        KeyValueRow(
            Res.string.next.str(),
            if (next == null) "—" else nextLabel(next),
            mono = false,
            copyable = false,
        )
    }
}

@Composable
private fun stepSubtitle(step: TimerStep, index: Int, count: Int): String = when {
    step.roundCount > 0 && step.setCount > 1 ->
        "${Res.string.round.str()} ${step.round}/${step.roundCount} · ${Res.string.set_.str()} ${step.setIndex}/${step.setCount}"
    step.roundCount > 0 -> "${Res.string.round.str()} ${step.round}/${step.roundCount}"
    step.setCount > 0 -> "${Res.string.set__2.str()} ${step.setIndex}/${step.setCount}"
    else -> "${index + 1} / $count"
}

@Composable
private fun nextLabel(step: TimerStep): String {
    val kind = when (step.kind) {
        StepKind.PREPARE -> Res.string.get_ready
        StepKind.WORK -> Res.string.work
        StepKind.REST -> Res.string.rest
        StepKind.COOLDOWN -> Res.string.cooldown
    }
    val name = if (step.name.isBlank()) "" else " · ${step.name}"
    return "${kind.str()}$name · ${step.seconds} ${Res.string.unit_s.str()}"
}

private fun formatClock(ms: Long): String {
    val total = (ms + 999) / 1000
    val m = total / 60
    val s = total % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

private fun String.toIntOrZero(): Int = trim().toIntOrNull() ?: 0
