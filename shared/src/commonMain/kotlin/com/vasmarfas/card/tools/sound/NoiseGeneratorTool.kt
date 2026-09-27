package com.vasmarfas.card.tools.sound

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vasmarfas.card.core.PcmPlayer
import com.vasmarfas.card.core.currentEpochMillis
import com.vasmarfas.card.core.formatDurationMs
import com.vasmarfas.card.core.str
import com.vasmarfas.card.resources.*
import com.vasmarfas.card.tools.Tool
import com.vasmarfas.card.tools.ToolCategory
import com.vasmarfas.card.ui.components.ActionButton
import com.vasmarfas.card.ui.components.ChoiceChips
import com.vasmarfas.card.ui.components.ErrorText
import com.vasmarfas.card.ui.components.SegmentedChoice
import com.vasmarfas.card.ui.components.ToolSection
import kotlin.concurrent.Volatile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

val noiseGeneratorTool = Tool(
    id = "noise-generator",
    category = ToolCategory.SOUND,
    title = Res.string.noise_generator,
    description = Res.string.noise_generator_description,
    icon = Icons.Filled.Waves,
    keywords = listOf(
        "white noise", "pink noise", "brown noise", "sleep", "focus", "baby", "tinnitus", "masking",
        "белый шум", "розовый шум", "коричневый шум", "сон", "уснуть", "концентрация", "малыш", "шум",
    ),
) { NoiseGeneratorScreen() }

private const val NOISE_RATE = 44_100
private const val FADE_MS = 3_000L

private val sleepTimers = listOf(0, 15, 30, 60, 90)

private class NoiseControls {
    @Volatile
    var color = NoiseColor.PINK

    @Volatile
    var volume = 0.0
}

@Composable
private fun NoiseGeneratorScreen() {
    var color by rememberSaveable { mutableStateOf(NoiseColor.PINK) }
    var volume by rememberSaveable { mutableStateOf(0.5f) }
    var timer by rememberSaveable { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var fading by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf<Long?>(null) }
    val player = remember { PcmPlayer() }
    val controls = remember { NoiseControls() }
    val scope = rememberCoroutineScope()
    val level = if (fading) 0.0 else volume.toDouble() * volume
    SideEffect {
        controls.color = color
        controls.volume = level
    }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(playing) {
        if (!playing) {
            player.stop()
            return@LaunchedEffect
        }
        val generator = NoiseGenerator(currentEpochMillis())
        player.start(NOISE_RATE, 1) { buffer ->
            generator.fill(buffer, controls.color, controls.volume, NOISE_RATE)
            buffer.size
        }
        if (!player.playing) {
            failed = true
            playing = false
        }
    }
    LaunchedEffect(playing, timer) {
        left = null
        if (!playing || timer == 0) return@LaunchedEffect
        val end = currentEpochMillis() + timer * 60_000L
        while (true) {
            val remaining = end - currentEpochMillis()
            left = remaining.coerceAtLeast(0)
            if (remaining <= FADE_MS) fading = true
            if (remaining <= 0) break
            delay(minOf(1000L, remaining))
        }
        playing = false
    }

    SegmentedChoice(
        options = NoiseColor.entries,
        selected = color,
        onSelect = { color = it },
        label = {
            when (it) {
                NoiseColor.WHITE -> Res.string.noise_white.str()
                NoiseColor.PINK -> Res.string.noise_pink.str()
                NoiseColor.BROWN -> Res.string.noise_brown.str()
            }
        },
    )
    Text(
        when (color) {
            NoiseColor.WHITE -> Res.string.noise_white_hint.str()
            NoiseColor.PINK -> Res.string.noise_pink_hint.str()
            NoiseColor.BROWN -> Res.string.noise_brown_hint.str()
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(Res.string.sound_volume.str(), style = MaterialTheme.typography.labelLarge)
    Slider(value = volume, onValueChange = { volume = it })
    ToolSection(Res.string.turn_off_after.str()) {
        val minutes = Res.string.unit_min.str()
        ChoiceChips(
            options = sleepTimers,
            selected = timer,
            onSelect = { timer = it },
            label = { if (it == 0) Res.string.do_not_turn_off.str() else "$it $minutes" },
        )
    }
    ActionButton(
        text = if (playing) Res.string.stop.str() else Res.string.start.str(),
        icon = if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
        onClick = {
            if (!playing) {
                fading = false
                failed = false
                playing = true
            } else {
                scope.launch {
                    fading = true
                    delay(600)
                    playing = false
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (failed) ErrorText(Res.string.playback_failed.str())
    left?.let {
        Text(
            stringResource(Res.string.turns_off_in, formatDurationMs(it)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
